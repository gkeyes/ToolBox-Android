from pathlib import Path
import hashlib
import re
import shutil

ROOT = Path.cwd()

def read(path):
    return (ROOT / path).read_text(encoding='utf-8')

def write(path, text):
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding='utf-8')

def replace(path, old, new, count=1):
    text = read(path)
    actual = text.count(old)
    if actual != count:
        raise RuntimeError(f'{path}: expected {count} exact matches, found {actual}')
    write(path, text.replace(old, new))

# Fail before editing when any source file differs from the reviewed baseline.
for path, expected in {
    'tool-runtime/src/main/kotlin/io/toolbox/tool/runtime/DefaultRuntimeAuthorizationPolicy.kt': '5fc3eb5967cc06702b243fad8d431a6f5ca943cd',
    'tool-runtime/src/main/kotlin/io/toolbox/tool/runtime/RuntimeRpc.kt': '621a393f710bb612f758e8b67e37445b4f2b102c',
    'app/src/main/kotlin/io/toolbox/host/runtime/HostRuntimeBridgeProvider.kt': 'f0b3b799646e83563e0b1082f69b16a0d6820453',
}.items():
    data = (ROOT / path).read_bytes()
    actual = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
    if actual != expected:
        raise RuntimeError(f'Reviewed source changed: {path}')

policy = 'tool-runtime/src/main/kotlin/io/toolbox/tool/runtime/DefaultRuntimeAuthorizationPolicy.kt'
text = read(policy)
text = text.replace('import java.util.ArrayDeque\n', '')
text = text.replace('    private val clockMillis: () -> Long,\n', '')
text = text.replace('    private val maxCallsPerMinute: Int = 120,\n', '')
start = text.index('    private val rateWindows =')
end = text.index('    override suspend fun isCurrent', start)
text = text[:start] + text[end:]
start = text.index('        val quotaDecision = quota.admit')
text = text[:start] + '''        // Grants authorize use; admission protects retained message resources only.
        // Completed calls never consume a rolling per-minute allowance.
        return quota.admit(identity, method, encodedBytes)
    }
}
'''
write(policy, text)
host = 'app/src/main/kotlin/io/toolbox/host/runtime/HostRuntimeBridgeProvider.kt'
replace(host, '            clockMillis = SystemClock::elapsedRealtime,\n', '')
text = read(host)
if 'SystemClock.' not in text:
    write(host, text.replace('import android.os.SystemClock\n', ''))

broker = 'app/src/main/kotlin/io/toolbox/host/runtime/ForegroundCapabilityBroker.kt'
text = read(broker)
start = text.index('    private suspend fun readClipboardAfterConfirmation(): String')
end = text.index('    private suspend fun shareText', start)
text = text[:start] + '''    private suspend fun readClipboardText(): String = withContext(Dispatchers.Main.immediate) {
        // The dispatcher already verified declaration, grant and current session.
        // Keep foreground enforcement; do not ask the same permission again.
        ensureActive()
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
            ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "Clipboard is unavailable")
        if (!clipboard.hasPrimaryClip() || clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) != true) {
            return@withContext ""
        }
        clipboard.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()
    }

''' + text[end:]
text = text.replace('it.readClipboardAfterConfirmation()', 'it.readClipboardText()')
if 'AlertDialog.' not in text:
    text = text.replace('import android.app.AlertDialog\n', '')
write(broker, text)

# Clipboard access retains the existing foreground/recent-input contract, but no
# longer advertises an extra host confirmation. System clipboard UI is untouched.
contract = 'tool-api/src/main/resources/toolbox-api-v1.json'
replace(contract, '"gesture":"CONFIRMED_ONE_SHOT"', '"gesture":"RECENT"')
api = 'tool-api/src/main/kotlin/io/toolbox/tool/api/ToolBoxApiV1.kt'
replace(api, 'GestureRequirement.CONFIRMED_ONE_SHOT, CapabilityContext.FOREGROUND_ONLY)', 'GestureRequirement.RECENT, CapabilityContext.FOREGROUND_ONLY)')
old_hash = 'dbe81127fe54d37775006add8c64747243b0b14365f2bc482cbacb7d14908998'
new_hash = hashlib.sha256((ROOT / contract).read_bytes()).hexdigest()
replace(api, old_hash, new_hash)
sdk = 'sdk/toolbox-api.d.ts'
replace(sdk, old_hash, new_hash)
replace(sdk, '; at most 10 calls per minute.', '. No per-minute ToolBox call allowance applies.')

rpc = 'tool-runtime/src/main/kotlin/io/toolbox/tool/runtime/RuntimeRpc.kt'
text = read(rpc)
# These operations already arrive in a size-checked bridge envelope. Do not add
# unrelated 1 MiB or 64K limits inside that negotiated envelope.
for name in ['MAX_HASH_BYTES', 'MAX_FILE_CONTENT_BYTES', 'MAX_CLIPBOARD_CHARS', 'MAX_SHARE_TEXT_CHARS']:
    text, n = re.subn(r'^        const val ' + name + r' = [^\n]+\n', '', text, flags=re.M)
    if n != 1:
        raise RuntimeError(f'Missing payload constant {name}')
    text = text.replace(name, 'maxResponseBytes')
old = '''        is RpcValue.StringValue -> raw.value.toByteArray(StandardCharsets.UTF_8)
        is RpcValue.ArrayValue -> raw.value.map {
            val number = (it as? RpcValue.Number)?.value ?: throw IllegalArgumentException(name)
            require(number % 1.0 == 0.0 && number in 0.0..255.0)
            number.toInt().toByte()
        }.toByteArray()
'''
new = '''        is RpcValue.StringValue -> {
            require(raw.value.length <= maxBytes)
            raw.value.toByteArray(StandardCharsets.UTF_8)
        }
        is RpcValue.ArrayValue -> {
            require(raw.value.size <= maxBytes)
            ByteArray(raw.value.size) { index ->
                val number = (raw.value[index] as? RpcValue.Number)?.value ?: throw IllegalArgumentException(name)
                require(number % 1.0 == 0.0 && number in 0.0..255.0)
                number.toInt().toByte()
            }
        }
'''
if text.count(old) != 1:
    raise RuntimeError('Byte conversion source changed')
text = text.replace(old, new)
old = '''        } catch (_: Exception) {
            failure(RuntimeRpcErrorCode.INTERNAL_ERROR, "The native operation failed")
        }
'''
new = '''        } catch (error: Exception) {
            // Android's Binder capacity is a system boundary, not a ToolBox text quota.
            val ipcTooLarge = generateSequence<Throwable>(error) { it.cause }.take(8)
                .any { it is android.os.TransactionTooLargeException }
            if (ipcTooLarge) {
                failure(RuntimeRpcErrorCode.QUOTA_EXCEEDED, "Android rejected this IPC payload; use a file for large content")
            } else {
                failure(RuntimeRpcErrorCode.INTERNAL_ERROR, "The native operation failed")
            }
        }
'''
if text.count(old) != 1:
    raise RuntimeError('Dispatcher exception source changed')
write(rpc, text.replace(old, new))

# Replace only the tests for the deliberately retired default minute policy.
# Error serialization, grant/origin/nonce and resource-budget tests stay intact.
tests = 'tool-runtime/src/test/kotlin/io/toolbox/tool/runtime/RuntimeRpcDispatcherTest.kt'
text = read(tests)
def replace_test(text, name, replacement):
    pattern = r'    @Test\n    fun ' + re.escape(name) + r'\(\) = runTest \{.*?(?=\n    @Test\n)'
    result, n = re.subn(pattern, lambda _: replacement.rstrip() + '\n', text, count=1, flags=re.S)
    if n != 1:
        raise RuntimeError(f'Missing reviewed test {name}')
    return result
text = replace_test(text, 'rateLimitWaitUsesTheOldestCallAndNeverRaisesExistingLimits', '''    @Test
    fun completedCallsDoNotSpendMinuteQuotaAndResourcesStillGateAdmission() = runTest {
        var current = true
        var quota: RuntimePolicyDecision = RuntimePolicyDecision.Allowed
        val policy = DefaultRuntimeAuthorizationPolicy(
            object : RuntimeGrantStateSource {
                override suspend fun currentVersionCode(toolId: String) = if (current) identity.versionCode else null
                override suspend fun isGranted(toolId: String, capability: ToolBoxCapabilityId) = true
            }, RuntimeSystemPermissionChecker { true }, RuntimeQuotaChecker { _, _, _ -> quota },
        )
        for (name in listOf("browser.open", "clipboard.writeText", "haptics.perform", "ui.toast", "storage.get")) {
            val method = checkNotNull(ToolBoxApiV1.method(name))
            repeat(1_201) { assertEquals(RuntimePolicyDecision.Allowed, policy.admit(identity, method, 256)) }
        }
        val method = checkNotNull(ToolBoxApiV1.method("browser.open"))
        quota = RuntimePolicyDecision.Denied(RuntimeRpcErrorCode.QUOTA_EXCEEDED, "Message too large")
        assertEquals(quota, policy.admit(identity, method, 256))
        current = false
        assertEquals(RuntimeRpcErrorCode.INVALID_SESSION, (policy.admit(identity, method, 256) as RuntimePolicyDecision.Denied).code)
    }
''')
text = replace_test(text, 'streamReadsHaveABoundedIncrementalRateWithoutRaisingOtherMethodLimits', '''    @Test
    fun streamReadsAndCancellationHaveNoMinuteCounter() = runTest {
        val policy = DefaultRuntimeAuthorizationPolicy(
            object : RuntimeGrantStateSource {
                override suspend fun currentVersionCode(toolId: String) = identity.versionCode
                override suspend fun isGranted(toolId: String, capability: ToolBoxCapabilityId) = true
            }, RuntimeSystemPermissionChecker { true }, RuntimeQuotaChecker { _, _, _ -> RuntimePolicyDecision.Allowed },
        )
        for (name in listOf("network.readStream", "network.request", "network.cancelStream")) {
            val method = checkNotNull(ToolBoxApiV1.method(name))
            repeat(2_001) { assertEquals(RuntimePolicyDecision.Allowed, policy.admit(identity, method, 256)) }
        }
    }
''')
write(tests, text)

write('tool-runtime/src/test/kotlin/io/toolbox/tool/runtime/RuntimePayloadAdmissionTest.kt', '''package io.toolbox.tool.runtime

import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxCapabilityId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePayloadAdmissionTest {
    private val identity = RuntimeSessionIdentity(
        "io.toolbox.test", 1, "generation", "test", "nonce", "https://test.invalid",
        setOf("files.save", "clipboard.write", "share"),
    )
    private val authorization = object : RuntimeAuthorizationPolicy {
        override suspend fun isCurrent(identity: RuntimeSessionIdentity) = true
        override suspend fun isGranted(identity: RuntimeSessionIdentity, capability: ToolBoxCapabilityId) = true
        override suspend fun hasSystemPermissions(identity: RuntimeSessionIdentity, permissions: Set<String>) = true
        override suspend fun admit(identity: RuntimeSessionIdentity, method: MethodDescriptor, encodedBytes: Int) = RuntimePolicyDecision.Allowed
    }
    private fun request(method: String, params: Map<String, RpcValue>) = RuntimeRpcRequest(
        "payload-test", method, identity.nonce, identity.toolId, identity.versionCode,
        identity.generation, RpcValue.ObjectValue(params), 256,
    )

    @Test
    fun filesAndHashUseNegotiatedEnvelopeRatherThanOneMiB() = runTest {
        val content = "x".repeat(2 * 1024 * 1024)
        var savedBytes = 0
        val files = object : RuntimeFilesHandler {
            override suspend fun open(mimeTypes: List<String>): RuntimeFileToken? = error("unused")
            override suspend fun save(suggestedName: String, mimeType: String, content: ByteArray): RuntimeFileToken? {
                savedBytes = content.size
                return null
            }
            override suspend fun capabilityFor(token: String) = ToolBoxCapabilityId.FILES_SAVE
            override suspend fun consume(token: String, maxBytes: Int): ByteArray = error("unused")
        }
        val dispatcher = RuntimeRpcDispatcher(identity, authorization, RuntimeM1Handlers(),
            m3Handlers = RuntimeM3Handlers(files = files), maxResponseBytes = 4 * 1024 * 1024)
        val inbound = RuntimeInboundContext(identity.exactOrigin, true, 1)
        val save = request("files.save", mapOf("suggestedName" to RpcValue.StringValue("large.txt"),
            "mimeType" to RpcValue.StringValue("text/plain"), "content" to RpcValue.StringValue(content)))
        assertTrue(dispatcher.dispatch(save, inbound) is RuntimeRpcResponse.Success)
        assertEquals(content.length, savedBytes)
        assertTrue(dispatcher.dispatch(request("crypto.sha256", mapOf("value" to RpcValue.StringValue(content))), inbound) is RuntimeRpcResponse.Success)
        val tooLarge = save.copy(params = RpcValue.ObjectValue(save.params.value + ("content" to RpcValue.StringValue("x".repeat(4 * 1024 * 1024 + 1)))))
        assertTrue(dispatcher.dispatch(tooLarge, inbound) is RuntimeRpcResponse.Failure)
        assertEquals(content.length, savedBytes)
    }

    @Test
    fun clipboardAndShareDoNotHaveAnAdditionalSixtyFourKTextQuota() = runTest {
        val text = "x".repeat(64 * 1024 + 1)
        var copied = ""
        var shared = ""
        val dispatcher = RuntimeRpcDispatcher(identity, authorization,
            RuntimeM1Handlers(clipboardWrite = RuntimeClipboardWriteHandler { copied = it }),
            m3Handlers = RuntimeM3Handlers(shareText = RuntimeShareTextHandler { shared = it }))
        val inbound = RuntimeInboundContext(identity.exactOrigin, true, 1)
        val params = mapOf("text" to RpcValue.StringValue(text))
        assertTrue(dispatcher.dispatch(request("clipboard.writeText", params), inbound) is RuntimeRpcResponse.Success)
        assertTrue(dispatcher.dispatch(request("share.text", params), inbound) is RuntimeRpcResponse.Success)
        assertEquals(text, copied)
        assertEquals(text, shared)
    }
}
''')

# Keep the user-facing SDK and the copyable manual exactly synchronized.
text = read(sdk)
text = text.replace('    readText(): Promise<string>;', '    /** Granted foreground read with recent input; no additional host confirmation dialog. */\n    readText(): Promise<string>;')
text = text.replace('    save(suggestedName:', '    /** Uses the negotiated bridge message budget, not an extra 1 MiB file quota. Large files still need chunked APIs. */\n    save(suggestedName:')
write(sdk, text)
manual_path = 'sdk/help/manual.md'
manual = read(manual_path)
pattern = r'(```[^\n]*sdk/toolbox-api\.d\.ts\n).*?(\n```)'
manual, count = re.subn(pattern, lambda m: m.group(1) + read(sdk).rstrip() + m.group(2), manual, count=1, flags=re.S)
if count != 1:
    raise RuntimeError('Missing embedded SDK block')
lines = []
for line in manual.splitlines():
    if '剪贴板' in line:
        line = line.replace('每次确认', '已授权后不重复确认').replace('逐次确认', '已授权后不重复确认')
    line = line.replace('每分钟最多 10 次', '不设每分钟调用配额').replace('每分钟 10 次', '不设每分钟调用配额')
    lines.append(line)
write(manual_path, '\n'.join(lines) + '\n')

# Remove only the requested historical authoring material, not runtime assets or
# third-party licence/attribution material. Git history remains the rollback path.
for name in ['toolbox_ui_wireframe_v2.png', 'toolbox_ui_wireframe_v2.svg', 'DESIGN.md', 'AGENTS.md']:
    target = ROOT / name
    if not target.is_file():
        raise RuntimeError(f'Missing requested deletion: {name}')
    target.unlink()
for name in ['design', '.impeccable']:
    target = ROOT / name
    if not target.is_dir():
        raise RuntimeError(f'Missing requested directory: {name}')
    shutil.rmtree(target)
assert (ROOT / 'THIRD_PARTY_NOTICES.md').is_file()
tech = 'docs/ToolBox_Android_技术方案.md'
text = read(tech)
text = text.replace('[设计规范](../DESIGN.md)', '[宿主界面源码](../app/src/main/kotlin/io/toolbox/host)')
text = text.replace('[DESIGN.md](../DESIGN.md)', '[宿主界面源码](../app/src/main/kotlin/io/toolbox/host)')
text += '''

## 运行时授权与资源预算清理（2026-09-13）

权限开关决定能否调用能力；已完成的 RPC 不再消耗每分钟次数额度。来源、主 frame、会话、声明、授权、Android 权限和在途资源预算继续检查。剪贴板读取不再叠加宿主确认弹窗；本次保留既有前台和近期输入要求，Android 自身提示不受影响。

文件保存、摘要输入、剪贴板与分享文本不再有独立的 1 MiB / 64K 配额，统一受当前运行时协商的桥接消息预算约束。文件读取仍是有响应预算的整块接口；这不是无限大小文件支持。Android Binder 拒绝超大文本时返回可识别的容量错误，不绕过系统边界。

旧设计稿、设计生成配置与根目录代理指令已移除，不更改实际 UI、现有安全测试或签名发布配置。第三方许可和署名声明继续保留。编译和行为测试只在 GitHub Actions 执行。
'''
write(tech, text)
print('Scoped source changes prepared; compilation and behavior verification have not run yet.')
