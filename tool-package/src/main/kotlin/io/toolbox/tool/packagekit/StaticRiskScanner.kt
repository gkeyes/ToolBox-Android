package io.toolbox.tool.packagekit

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal object StaticRiskScanner {
    fun scan(bundle: Path, files: Set<String>, profile: SecurityProfile): List<RiskFinding> {
        val findings = mutableListOf<RiskFinding>()
        for (path in files.sorted()) {
            if (!path.endsWith(".html") && !path.endsWith(".js") && !path.endsWith(".css")) continue
            val file = bundle.resolve(path)
            if (Files.size(file) > MAX_SCAN_BYTES) continue
            val text = runCatching {
                Files.newBufferedReader(file, StandardCharsets.UTF_8).use { it.readText() }
            }.getOrNull() ?: continue
            if (profile == SecurityProfile.STRICT && INLINE_SCRIPT.containsMatchIn(text)) {
                findings += RiskFinding(RiskFindingCode.INLINE_SCRIPT, path, "Inline script may be incompatible with strict CSP")
            }
            if (DYNAMIC_CODE.containsMatchIn(text)) {
                findings += RiskFinding(RiskFindingCode.DYNAMIC_CODE, path, "Dynamic JavaScript construction requires review")
            }
            if (EMBEDDED_FRAME.containsMatchIn(text)) {
                findings += RiskFinding(RiskFindingCode.EMBEDDED_FRAME, path, "Embedded frame markup is blocked by runtime policy")
            }
            if (REMOTE_REFERENCE.containsMatchIn(text)) {
                findings += RiskFinding(RiskFindingCode.REMOTE_REFERENCE, path, "Direct remote references are blocked by runtime policy")
            }
        }
        return findings
    }

    private const val MAX_SCAN_BYTES = 1024L * 1024
    private val INLINE_SCRIPT = Regex("<script\\b(?![^>]*\\bsrc\\s*=)[^>]*>", RegexOption.IGNORE_CASE)
    private val DYNAMIC_CODE = Regex("(?:\\beval\\s*\\(|\\bnew\\s+Function\\s*\\()")
    private val EMBEDDED_FRAME = Regex("<(?:iframe|object|embed)(?:\\s|>)", RegexOption.IGNORE_CASE)
    private val REMOTE_REFERENCE = Regex("(?:https?:)?//[A-Za-z0-9]", RegexOption.IGNORE_CASE)
}
