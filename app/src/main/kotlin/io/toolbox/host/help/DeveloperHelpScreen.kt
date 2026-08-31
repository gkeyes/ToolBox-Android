package io.toolbox.host.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.component.ToolBoxText
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.ui.AppText
import io.toolbox.host.ui.DetailScreen
import io.toolbox.host.ui.SectionHeader
import io.toolbox.host.ui.SurfaceCard
import io.toolbox.tool.api.CapabilityDescriptor
import io.toolbox.tool.api.ContractPhase
import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxApiV1
import io.toolbox.tool.api.ToolBoxCapabilityId

internal object DeveloperHelpTestTags {
    const val Screen = "developer_help_screen"
    const val InstallExamples = "developer_help_install_examples"
}

@Composable
internal fun DeveloperHelpScreen(
    onBack: () -> Unit,
    onInstallExamples: () -> Unit,
) {
    DetailScreen(
        title = "开发帮助",
        onBack = onBack,
        modifier = Modifier.testTag(DeveloperHelpTestTags.Screen),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(ToolBoxThemeTokens.spacing.two),
            verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one),
        ) {
            item("overview") {
                SurfaceCard {
                    AppText(
                        "ToolBox API ${ToolBoxApiV1.API_VERSION}",
                        textStyle = ToolBoxThemeTokens.textStyles.sectionTitle,
                        weight = FontWeight.SemiBold,
                    )
                    AppText(
                        "这是离线的 .tbx 开发参考。小工具是 HTML、CSS 和 JavaScript；ToolBox 负责导入、权限、原生能力、持续运行环境和旧版后台任务。",
                        textStyle = ToolBoxThemeTokens.textStyles.metadata,
                        color = ToolBoxThemeTokens.colors.textSecondary,
                    )
                    ToolBoxPrimaryButton(
                        label = "安装三个范例",
                        onClick = onInstallExamples,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(DeveloperHelpTestTags.InstallExamples),
                    )
                }
            }
            item("quick-start") {
                HelpSection(
                    title = "1. 目录结构",
                    body = "工具只需要静态网页资源。打包时会连同完整性清单放进一个 .tbx ZIP。",
                    code = """
                        my-tool/
                        ├── manifest.json
                        ├── index.html
                        ├── style.css
                        ├── app.js
                        ├── icon.svg
                        └── integrity.json
                    """,
                )
            }
            item("manifest") {
                HelpSection(
                    title = "2. manifest.json",
                    body = "声明工具身份、入口、版本和所需能力。只有声明过的能力才会出现在这个工具的权限页；使用实时通知时，minHostVersion 至少为 0.3.1。",
                    code = manifestExample,
                )
            }
            item("permissions") {
                HelpSection(
                    title = "3. 权限如何生效",
                    body = "把每项能力写入 manifest 的 permissions；安装后在该工具的权限页独立开关。真正调用时仍同时检查声明、工具开关、系统许可、前台/手势条件及配额。\n\n默认开启：$defaultGrantedCapabilities。其余能力默认关闭。",
                )
            }
            item("capability-groups") {
                HelpContractSection(
                    title = "能力清单",
                    rows = capabilityGroups.flatMap { group ->
                        listOf(HelpRow(group.title, group.description)) +
                            group.ids.mapNotNull(ToolBoxApiV1::capability).map(::capabilityRow)
                    },
                )
            }
            item("api") {
                HelpContractSection(
                    title = "4. JavaScript API",
                    rows = apiPhaseRows(),
                    footer = "网页通过 window.ToolBox 调用异步 API。宿主事件在 ready 完成后投递；即使监听器稍后注册，有限数量的早到事件也会排队。files.open 与 camera.capture 返回的 token 只能用 files.read 读取一次；读取上限由当前会话消息配额决定。",
                )
            }
            item("api-example") {
                HelpSection(
                    title = "常用调用示例",
                    body = "所有调用都是 Promise；工具页面加载后先等待 ready。复制与触觉需要用户刚刚在工具页面内完成真实触摸。",
                    code = """
                        await ToolBox.ready()
                        await ToolBox.storage.set("draft", { amount: 1000 })
                        const draft = await ToolBox.storage.get("draft")
                        await ToolBox.clipboard.writeText("已复制")
                        await ToolBox.haptics.perform("confirm")
                    """,
                )
            }
            item("performance") {
                HelpSection(
                    title = "高性能计算",
                    body = "大量计算不要放在页面主线程。把 worker.js 一起打进 .tbx，用同源 Web Worker 计算，再通过 postMessage 把结果交给页面；Worker 不能直接调用 ToolBox API，系统能力仍由顶层页面发起。远程、blob 和 data Worker 均被阻止，ServiceWorker 仍不可用。",
                    code = """
                        // app.js
                        const worker = new Worker("worker.js")
                        worker.onmessage = ({ data }) => render(data)
                        worker.postMessage(input)

                        // worker.js
                        self.onmessage = ({ data }) => {
                          self.postMessage(runHeavyCalculation(data))
                        }
                    """,
                )
            }
            item("package") {
                HelpSection(
                    title = "5. 打包与导入",
                    body = "用项目中的范例脚本生成完整性清单和 .tbx，再在工具页选择“导入”。导入只会显示安装成功或明确失败；失败不会留下半安装的工具。",
                    code = """
                        ./scripts/package-examples.sh
                        # 输出：build/examples/<tool>.tbx
                    """,
                )
            }
            item("background") {
                HelpSection(
                    title = "6. 持续运行与后台任务",
                    body = "0.3 的 background.start 会把当前运行页面提升为持续环境。离开页面后 WebView 与桥接会从界面分离但不销毁，再次打开会挂回同一环境；重复 start 返回同一 sessionId。页面可用 setTimer 接收 background.timer，并在进程或重启恢复后通过 background.restore 自行恢复状态。连续运行每 12 小时会提醒一次，Android 仍可能回收进程，因此这是可恢复的尽力运行，不是永久存活保证。\n\nbackground.listSessions 只列持续环境；旧 background.list 仍列 WorkManager 任务。旧任务继续保持每工具 8 个活动任务、4 个周期任务和最短 15 分钟周期，三个现有范例无需修改。",
                )
            }
            item("live-notifications") {
                HelpSection(
                    title = "实时通知",
                    body = "先用 background.start 获得 sessionId，再调用 notifications.live.start。后续 update 会原位更新普通持续通知，并在系统支持时请求 Android 实时更新与 HyperOS 超级岛；REQUESTED 只表示已提交给系统。end 只结束实时展示，不停止后台会话。停止会话、关闭授权、更新或删除工具时宿主会自动清理。",
                    code = """
                        const session = await ToolBox.background.start()
                        await ToolBox.notifications.live.start({
                          sessionId: session.sessionId,
                          title: "示例状态",
                          primaryText: "12.34",
                          secondaryText: "+1.25% · 10:30",
                          shortText: "12.34",
                          tone: "positive",
                          accentColor: "#E53935"
                        })
                    """,
                )
            }
            item("network") {
                HelpSection(
                    title = "公网网络",
                    body = "network.request 支持 manifest.network.allowDomains 中精确声明的公网 HTTPS 主机与合法 HTTPS 端口，可使用 GET、POST、PUT、PATCH、DELETE、HEAD、自定义常用 Header、文本/JSON/Uint8Array 请求体、超时和响应上限。allowDomains 必须包含 1–32 个精确域名或 *.example.com 形式的子域通配项。\n\n宿主始终阻止未声明域名、回环、链路本地、私网、保留地址和 IP 字面量，并在每次 DNS 与重定向时重新检查。Host、Content-Length、Connection、Transfer-Encoding、Upgrade 与 Proxy 系列协议 Header 不能由页面控制。Authorization、Cookie、X-API-Key、Accept、Content-Type 等普通 Header 可以使用。响应实际大小还受当前 WebMessage 配额限制。",
                )
            }
            item("errors") {
                HelpContractSection(
                    title = "7. 常见错误码",
                    rows = errorGuidance,
                )
            }
            item("examples") {
                HelpContractSection(
                    title = "三个内置范例",
                    rows = exampleRows,
                    footer = "范例源码与各自 manifest 位于项目的 examples 目录；帮助页的“安装三个范例”与外部 .tbx 使用同一安装路径。",
                )
            }
        }
    }
}

@Composable
private fun HelpSection(
    title: String,
    body: String,
    code: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.compact)) {
        SectionHeader(title)
        SurfaceCard {
            AppText(
                body,
                textStyle = ToolBoxThemeTokens.textStyles.body,
                color = ToolBoxThemeTokens.colors.textPrimary,
            )
            if (code != null) HelpCode(code)
        }
    }
}

@Composable
private fun HelpContractSection(
    title: String,
    rows: List<HelpRow>,
    footer: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.compact)) {
        SectionHeader(title)
        SurfaceCard(contentPadding = ToolBoxThemeTokens.spacing.oneHalf) {
            rows.forEachIndexed { index, row ->
                AppText(
                    row.title,
                    textStyle = ToolBoxThemeTokens.textStyles.title,
                    weight = FontWeight.SemiBold,
                )
                AppText(
                    row.summary,
                    textStyle = ToolBoxThemeTokens.textStyles.metadata,
                    color = ToolBoxThemeTokens.colors.textSecondary,
                )
                if (index != rows.lastIndex) {
                    AppText(
                        "·",
                        textStyle = ToolBoxThemeTokens.textStyles.metadata,
                        color = ToolBoxThemeTokens.colors.divider,
                    )
                }
            }
            footer?.let {
                AppText(
                    it,
                    modifier = Modifier.padding(top = ToolBoxThemeTokens.spacing.one),
                    textStyle = ToolBoxThemeTokens.textStyles.metadata,
                    color = ToolBoxThemeTokens.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun HelpCode(code: String) {
    ToolBoxText(
        text = code.trimIndent(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = ToolBoxThemeTokens.spacing.one),
        style = ToolBoxThemeTokens.textStyles.metadata.copy(
            color = ToolBoxThemeTokens.colors.textSecondary,
            fontFamily = FontFamily.Monospace,
        ),
    )
}

private fun capabilityRow(capability: CapabilityDescriptor): HelpRow {
    val defaultGrant = if (capability.defaultGrant) "默认开启" else "默认关闭"
    val systemPermission = capability.systemPermissions
        .takeIf { it.isNotEmpty() }
        ?.joinToString(prefix = "；系统权限：")
        .orEmpty()
    val gesture = when (capability.gestureRequirement.name) {
        "NONE" -> ""
        "RECENT" -> "；需要近期真实触摸"
        else -> "；需要一次原生确认"
    }
    return HelpRow(
        title = capability.wireName,
        summary = "${capability.contractPhase.name} · $defaultGrant$gesture$systemPermission",
    )
}

private fun apiPhaseRows(): List<HelpRow> = ContractPhase.entries.map { phase ->
    val methods = ToolBoxApiV1.methods
        .filter { it.contractPhase == phase }
        .joinToString(separator = "、", transform = MethodDescriptor::name)
    HelpRow(
        title = phase.helpTitle,
        summary = methods,
    )
}

private val defaultGrantedCapabilities: String
    get() = ToolBoxApiV1.capabilities
        .filter(CapabilityDescriptor::defaultGrant)
        .joinToString(separator = "、", transform = CapabilityDescriptor::wireName)

private val ContractPhase.helpTitle: String
    get() = when (this) {
        ContractPhase.M1 -> "M1 · 基础网页能力"
        ContractPhase.M2 -> "M2 · 网络、通知与后台"
        ContractPhase.M3 -> "M3 · 系统交互能力"
    }

@Immutable
private data class HelpRow(
    val title: String,
    val summary: String,
)

@Immutable
private data class CapabilityGroup(
    val title: String,
    val description: String,
    val ids: Set<ToolBoxCapabilityId>,
)

private val capabilityGroups = listOf(
    CapabilityGroup(
        title = "数据与设备",
        description = "仅工具自己的数据和最小设备信息。",
        ids = setOf(
            ToolBoxCapabilityId.STORAGE,
            ToolBoxCapabilityId.STORAGE_SECURE,
            ToolBoxCapabilityId.DEVICE_BASIC,
        ),
    ),
    CapabilityGroup(
        title = "前台交互",
        description = "需要用户实际在工具页面操作后才能调用的系统交互。",
        ids = setOf(
            ToolBoxCapabilityId.CLIPBOARD_WRITE,
            ToolBoxCapabilityId.CLIPBOARD_READ,
            ToolBoxCapabilityId.HAPTICS,
            ToolBoxCapabilityId.SHARE,
            ToolBoxCapabilityId.FILES_OPEN,
            ToolBoxCapabilityId.FILES_SAVE,
            ToolBoxCapabilityId.SHORTCUTS,
            ToolBoxCapabilityId.CAMERA,
            ToolBoxCapabilityId.LOCATION,
        ),
    ),
    CapabilityGroup(
        title = "网络与后台",
        description = "公网 HTTPS、通知、持续页面、后台位置、精确闹钟与兼容的旧后台任务。",
        ids = setOf(
            ToolBoxCapabilityId.NETWORK,
            ToolBoxCapabilityId.NOTIFICATIONS,
            ToolBoxCapabilityId.BACKGROUND_TASKS,
            ToolBoxCapabilityId.BACKGROUND_RUNTIME,
            ToolBoxCapabilityId.LOCATION_BACKGROUND,
            ToolBoxCapabilityId.ALARMS,
        ),
    ),
)

private val manifestExample = """
    {
      "schemaVersion": 1,
      "id": "io.example.mytool",
      "name": "我的工具",
      "version": "1.0.0",
      "versionCode": 1,
      "entry": "index.html",
      "apiVersion": "1.0",
      "minHostVersion": "0.3.1",
      "permissions": [
        { "name": "storage", "reason": "保存工具数据" }
      ],
      "securityProfile": "strict"
    }
"""

private val errorGuidance = listOf(
    HelpRow("UNSUPPORTED", "当前宿主版本尚未提供该方法；改用 API 1.0 已声明的方法或升级宿主。"),
    HelpRow("INVALID_SESSION / WRONG_ORIGIN / NOT_MAIN_FRAME", "只从当前工具的顶层页面调用 ToolBox；页面重载或切换后重新执行 ready。"),
    HelpRow("NOT_DECLARED", "把对应 capability 加入 manifest 的 permissions。"),
    HelpRow("PERMISSION_DENIED", "在该工具的权限页开启对应能力。"),
    HelpRow("SYSTEM_PERMISSION_DENIED", "允许宿主的系统授权，或前往系统设置后重试。"),
    HelpRow("USER_GESTURE_REQUIRED", "让用户先点击工具内的按钮，再调用复制、触觉或系统交互。"),
    HelpRow("BUSY / DUPLICATE_TASK", "等待当前操作完成；后台任务 key 在同一工具内必须唯一。"),
    HelpRow("NETWORK_BLOCKED", "确认目标为公网 HTTPS；私网、回环、IP 字面量、危险重定向和协议级 Header 会被阻止。"),
    HelpRow("RATE_LIMITED / QUOTA_EXCEEDED", "降低调用频率或缩小单工具存储、消息和结果内容。"),
    HelpRow("CANCELLED / SESSION_ENDED", "工具被关闭、切换或权限关闭后，停止等待这次请求。"),
    HelpRow("INVALID_REQUEST / NOT_FOUND", "检查方法参数和任务 ID；不要使用未在 API 1.0 中声明的方法。"),
    HelpRow("INTERNAL_ERROR", "这次调用没有完成；稍后重试。持续出现时保留错误码和复现步骤以便排查。"),
)

private val exampleRows = listOf(
    HelpRow("仓位计算器", "保存输入、计算仓位、复制结果与触觉反馈；声明 storage、clipboard.write、haptics。"),
    HelpRow("快速笔记", "创建、编辑、删除、重开恢复和复制笔记；声明 storage、clipboard.write。"),
    HelpRow("后台任务演示", "创建、查看和取消 GitHub HTTP 与通知任务；仅允许 api.github.com，声明 background.tasks、network、notifications。"),
)
