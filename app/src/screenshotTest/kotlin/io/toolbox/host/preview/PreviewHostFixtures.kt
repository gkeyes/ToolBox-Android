package io.toolbox.host.preview

import io.toolbox.core.data.GrantScope
import io.toolbox.core.data.GrantSource
import io.toolbox.core.data.GrantState
import io.toolbox.core.data.LaunchState
import io.toolbox.core.data.SignatureState
import io.toolbox.host.catalog.CatalogTool
import io.toolbox.host.catalog.CatalogUiState
import io.toolbox.host.importflow.ImportGrantChoice
import io.toolbox.host.importflow.ImportPermissionFact
import io.toolbox.host.importflow.ImportReviewFacts
import io.toolbox.host.importflow.ImportReviewPhase
import io.toolbox.host.importflow.ImportReviewUiState
import io.toolbox.host.permissions.PermissionCenterUiState
import io.toolbox.host.permissions.PermissionGrantItem
import io.toolbox.tool.packagekit.RiskFinding
import io.toolbox.tool.packagekit.RiskFindingCode
import io.toolbox.tool.packagekit.SecurityProfile
import io.toolbox.tool.packagekit.SignatureEvidence
import io.toolbox.tool.packagekit.SignatureState as PackageSignatureState

object PreviewHostFixtures {
    val catalog = CatalogUiState(
        isLoaded = true,
        tools = listOf(
            tool(
                id = "io.toolbox.preview.calculator",
                name = "仓位计算器",
                signature = SignatureState.VERIFIED_TRUSTED,
                version = "1.2.0",
                bytes = 8_388_608L,
                openedAt = 1_700_000_000_000L,
                category = "计算",
                pinnedOrder = 0,
            ),
            tool(
                id = "io.toolbox.preview.json",
                name = "JSON 格式化",
                signature = SignatureState.UNSIGNED,
                version = "1.0.3",
                bytes = 6_186_598L,
                openedAt = 1_699_000_000_000L,
                category = "开发",
                pinnedOrder = null,
            ),
        ),
    )

    val permissionCenter = PermissionCenterUiState(
        toolId = "io.toolbox.preview.calculator",
        isLoaded = true,
        grants = listOf(
            PermissionGrantItem(
                permission = "storage",
                title = "专属存储",
                state = GrantState.GRANTED,
                scope = GrantScope.SESSION,
                expiresAt = null,
                source = GrantSource.INSTALL,
            ),
        ),
    )

    val importReview = ImportReviewUiState(
        phase = ImportReviewPhase.REVIEW,
        selectedName = "preview-position-calculator.tbx",
        review = ImportReviewFacts(
            sessionId = "preview-inspection-session",
            sourceName = "preview-position-calculator.tbx",
            toolId = "io.toolbox.preview.calculator",
            toolName = "仓位计算器",
            version = "1.2.0",
            versionCode = 12,
            entry = "index.html",
            apiVersion = "1.0",
            securityProfile = SecurityProfile.STRICT,
            signature = SignatureEvidence(
                state = PackageSignatureState.UNSIGNED,
                detail = "未提供签名",
            ),
            publisherName = null,
            publisherKeyId = null,
            compressedBytes = 1_572_864L,
            extractedBytes = 2_621_440L,
            fileCount = 48,
            files = listOf("manifest.json", "index.html", "app.js"),
            permissions = listOf(
                ImportPermissionFact("storage", "保存计算配置", required = true),
                ImportPermissionFact("network", "访问明确的行情域名", required = false),
            ),
            networkDomains = listOf("api.example.com"),
            riskFindings = listOf(
                RiskFinding(
                    code = RiskFindingCode.INLINE_SCRIPT,
                    file = "index.html",
                    detail = "内联脚本需要审核",
                ),
            ),
            blockers = emptyList(),
            installable = true,
        ),
        grants = mapOf(
            "storage" to ImportGrantChoice.ALLOW_SESSION,
            "network" to ImportGrantChoice.DENY,
        ),
    )

    private fun tool(
        id: String,
        name: String,
        signature: SignatureState,
        version: String,
        bytes: Long,
        openedAt: Long,
        category: String,
        pinnedOrder: Int?,
    ) = CatalogTool(
        toolId = id,
        name = name,
        signatureState = signature,
        activeVersionCode = 1,
        activeVersionName = version,
        bundleBytes = bytes,
        launchState = LaunchState.STABLE,
        lastOpenedAt = openedAt,
        categoryId = category,
        pinnedOrder = pinnedOrder,
    )
}
