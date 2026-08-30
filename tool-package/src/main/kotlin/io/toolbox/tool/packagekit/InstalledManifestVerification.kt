package io.toolbox.tool.packagekit

import io.toolbox.core.data.SecurityProfile as CatalogSecurityProfile

data class InstalledManifest(
    val id: String,
    val name: String,
    val versionCode: Int,
    val entry: String,
    val securityProfile: CatalogSecurityProfile,
    val permissions: Set<String>,
    val permissionDeclarations: List<InstalledManifestPermission>,
    val network: InstalledManifestNetwork?,
    val maxBridgePayloadBytes: Int,
)

data class InstalledManifestPermission(
    val name: String,
    val reason: String,
    val required: Boolean,
)

data class InstalledManifestNetwork(
    val allowDomains: Set<String>,
    val allowRedirects: Boolean,
    val maxResponseBytes: Int,
    val timeoutMs: Int,
)

sealed interface InstalledManifestVerification {
    data class Verified(val manifest: InstalledManifest) : InstalledManifestVerification
    data class Rejected(val detail: String) : InstalledManifestVerification
}

object InstalledManifestVerifier {
    fun verify(
        manifestBytes: ByteArray,
        expectedToolId: String,
        expectedVersionCode: Int,
        expectedSecurityProfile: CatalogSecurityProfile,
    ): InstalledManifestVerification {
        val parsed = try {
            ManifestValidator.parse(manifestBytes, PackageLimits())
        } catch (failure: JsonFormatException) {
            return InstalledManifestVerification.Rejected(
                failure.message ?: "Installed manifest is invalid",
            )
        } catch (failure: InspectionRejected) {
            return InstalledManifestVerification.Rejected(failure.rejection.detail)
        }
        val profile = CatalogSecurityProfile.valueOf(parsed.securityProfile.name)
        if (
            parsed.id != expectedToolId ||
            parsed.versionCode != expectedVersionCode ||
            profile != expectedSecurityProfile
        ) {
            return InstalledManifestVerification.Rejected(
                "Installed manifest identity does not match the active catalog version",
            )
        }
        return InstalledManifestVerification.Verified(
            InstalledManifest(
                id = parsed.id,
                name = parsed.name,
                versionCode = parsed.versionCode,
                entry = parsed.entry,
                securityProfile = profile,
                permissions = parsed.permissions.mapTo(linkedSetOf()) { it.name },
                permissionDeclarations = parsed.permissions.map { permission ->
                    InstalledManifestPermission(
                        name = permission.name,
                        reason = permission.reason,
                        required = permission.required,
                    )
                },
                network = parsed.network?.let { network ->
                    InstalledManifestNetwork(
                        allowDomains = network.allowDomains.toCollection(linkedSetOf()),
                        allowRedirects = network.allowRedirects,
                        maxResponseBytes = network.maxResponseBytes,
                        timeoutMs = network.timeoutMs,
                    )
                },
                maxBridgePayloadBytes = parsed.limits.maxBridgePayloadBytes,
            ),
        )
    }
}
