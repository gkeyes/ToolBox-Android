import groovy.json.JsonSlurper
import java.math.BigDecimal
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.toolbox.tool.api"
    compileSdk = 37
    defaultConfig { minSdk = 33 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

val verifyToolBoxApiContract by tasks.registering {
    group = "verification"
    description = "Fails when the canonical ToolBox API v1 contract drifts from Kotlin, TypeScript, manifest schema, or package validation."

    val contractFile = layout.projectDirectory.file("src/main/resources/toolbox-api-v1.json")
    val kotlinFile = layout.projectDirectory.file("src/main/kotlin/io/toolbox/tool/api/ToolBoxApiV1.kt")
    val sdkFile = rootProject.layout.projectDirectory.file("sdk/toolbox-api.d.ts")
    val schemaFile = rootProject.layout.projectDirectory.file("schema/manifest.schema.json")
    val validatorFile = rootProject.layout.projectDirectory.file(
        "tool-package/src/main/kotlin/io/toolbox/tool/packagekit/ManifestValidator.kt",
    )
    val rpcFile = rootProject.layout.projectDirectory.file(
        "tool-runtime/src/main/kotlin/io/toolbox/tool/runtime/RuntimeRpc.kt",
    )
    val bridgeFile = rootProject.layout.projectDirectory.file(
        "tool-runtime/src/main/kotlin/io/toolbox/tool/runtime/RuntimeWebMessageBridge.kt",
    )
    inputs.files(contractFile, kotlinFile, sdkFile, schemaFile, validatorFile, rpcFile, bridgeFile)

    doLast {
        @Suppress("UNCHECKED_CAST")
        fun objectValue(value: Any?): Map<String, Any?> = value as? Map<String, Any?>
            ?: error("ToolBox API contract contains a non-object value")

        @Suppress("UNCHECKED_CAST")
        fun arrayValue(value: Any?): List<Any?> = value as? List<Any?>
            ?: error("ToolBox API contract contains a non-array value")

        val contractText = contractFile.asFile.readText()
        val contract = objectValue(JsonSlurper().parseText(contractText))
        val capabilities = arrayValue(contract["capabilities"]).map(::objectValue)
        val capabilityIds = capabilities.map { it["id"] as String }
        check(capabilityIds.size == 19 && capabilityIds.distinct().size == capabilityIds.size) {
            "Canonical ToolBox API v1 must contain exactly 19 unique capabilities"
        }
        val methods = arrayValue(contract["methods"]).map(::objectValue)
        val methodNames = methods.map { it["name"] as String }
        check(methodNames.distinct().size == methodNames.size) {
            "Canonical ToolBox API v1 contains duplicate method names"
        }
        methods.mapNotNull { it["capability"] as? String }.forEach { capability ->
            check(capability in capabilityIds) { "Method references unknown capability: $capability" }
        }

        val sourceHash = MessageDigest.getInstance("SHA-256")
            .digest(contractText.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val kotlinText = kotlinFile.asFile.readText()
        val sdkText = sdkFile.asFile.readText()
        check("const val CANONICAL_SHA256: String = \"$sourceHash\"" in kotlinText) {
            "Kotlin ToolBox API v1 descriptors drifted from the canonical contract"
        }
        check("export type ToolBoxContractSha256 = \"$sourceHash\";" in sdkText) {
            "sdk/toolbox-api.d.ts drifted from the canonical contract"
        }

        val kotlinCapabilityIds = Regex("CapabilityDescriptor\\(ToolBoxCapabilityId\\.[A-Z_]+, \\\"([^\\\"]+)\\\"")
            .findAll(kotlinText).map { it.groupValues[1] }.toList()
        check(kotlinCapabilityIds == capabilityIds) { "Kotlin capability descriptors do not match the canonical order" }
        val kotlinMethodNames = Regex("MethodDescriptor\\(\\\"([^\\\"]+)\\\"")
            .findAll(kotlinText).map { it.groupValues[1] }.toList()
        check(kotlinMethodNames == methodNames) { "Kotlin method descriptors do not match the canonical order" }

        fun unionValues(typeName: String): List<String> {
            val body = Regex("export type $typeName =([\\s\\S]*?);")
                .find(sdkText)?.groupValues?.get(1) ?: error("TypeScript union $typeName was not found")
            return Regex("\\\"([^\\\"]+)\\\"").findAll(body).map { it.groupValues[1] }.toList()
        }
        val sdkCapabilityIds = unionValues("ToolBoxCapability")
        check(sdkCapabilityIds == capabilityIds) { "TypeScript capabilities do not match the canonical order" }
        val sdkMethodNames = unionValues("ToolBoxMethodName")
        check(sdkMethodNames == methodNames) { "TypeScript methods do not match the canonical order" }
        val errorCodes = arrayValue(contract["errorCodes"]).map { it as String }
        check(unionValues("ToolBoxErrorCode") == errorCodes) { "TypeScript error codes drifted from the canonical contract" }
        val rpcText = rpcFile.asFile.readText()
        val rpcErrors = Regex("enum class RuntimeRpcErrorCode \\{([^}]+)}")
            .find(rpcText)?.groupValues?.get(1)
            ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
        check(rpcErrors == errorCodes) { "Runtime error codes drifted from the canonical contract" }

        val errorResponse = objectValue(contract["errorResponse"])
        val requiredErrorFields = arrayValue(errorResponse["required"]).map { it as String }
        check(errorResponse["type"] == "object" && requiredErrorFields == listOf("code", "message")) {
            "Canonical errors must require code and message while keeping retryAfterMs optional"
        }
        val errorProperties = objectValue(errorResponse["properties"])
        check(objectValue(errorProperties["code"])["type"] == "string" &&
            objectValue(errorProperties["message"])["type"] == "string") {
            "Canonical error code and message must remain strings"
        }
        val retryAfter = objectValue(errorProperties["retryAfterMs"])
        val retryMinimum = retryAfter["minimum"] as? Number
        val retryMaximum = retryAfter["maximum"] as? Number
        check(retryAfter["type"] == "integer" &&
            retryMinimum != null && BigDecimal(retryMinimum.toString()).compareTo(BigDecimal.ZERO) == 0 &&
            retryMaximum != null && BigDecimal(retryMaximum.toString()).compareTo(BigDecimal("9007199254740991")) == 0) {
            "Canonical retryAfterMs must be a non-negative JavaScript safe integer"
        }
        val sdkError = Regex("export interface ToolBoxApiError \\{([^}]+)}")
            .find(sdkText)?.groupValues?.get(1) ?: error("TypeScript ToolBoxApiError was not found")
        for ((field, type) in listOf("code" to "ToolBoxErrorCode", "message" to "string")) {
            check(Regex("(?m)^\\s*$field\\s*:\\s*$type\\s*;\\s*$").containsMatchIn(sdkError)) {
                "TypeScript ToolBoxApiError must retain required $field: $type"
            }
        }
        check(Regex("(?m)^\\s*retryAfterMs\\?\\s*:\\s*number\\s*;\\s*$").containsMatchIn(sdkError)) {
            "TypeScript ToolBoxApiError must expose optional retryAfterMs?: number"
        }
        for (type in listOf("RuntimeRpcError", "Denied", "RuntimeHandlerException", "RuntimeBackgroundTaskError")) {
            val constructor = Regex("\\bclass\\s+$type\\s*\\(([^)]*)\\)", RegexOption.DOT_MATCHES_ALL)
                .find(rpcText)?.groupValues?.get(1) ?: error("Runtime error type $type was not found")
            check(Regex("\\bval\\s+retryAfterMs\\s*:\\s*Long\\?\\s*=\\s*null\\b").containsMatchIn(constructor)) {
                "$type must expose optional retryAfterMs: Long? = null"
            }
        }

        val shimText = Regex("private fun shim\\([\\s\\S]*?return \"\"\"([\\s\\S]*?)\"\"\"\\.trimIndent\\(\\)")
            .find(bridgeFile.asFile.readText())?.groupValues?.get(1)
            ?: error("Runtime JS shim was not found")
        val shimMethods = Regex("\\bcall\\(\\s*['\"]([^'\"]+)['\"]")
            .findAll(shimText).map { it.groupValues[1] }.toSet()
        check(shimMethods == methodNames.toSet()) {
            "Runtime JS shim methods drifted: missing=${methodNames.toSet() - shimMethods}, unexpected=${shimMethods - methodNames.toSet()}"
        }

        val schema = objectValue(JsonSlurper().parseText(schemaFile.asFile.readText()))
        val properties = objectValue(schema["properties"])
        val permissions = objectValue(properties["permissions"])
        check((permissions["maxItems"] as Number).toInt() == capabilityIds.size) {
            "Manifest schema permissions.maxItems must equal the canonical capability count"
        }
        val permissionItems = objectValue(permissions["items"])
        val permissionProperties = objectValue(permissionItems["properties"])
        val nameSchema = objectValue(permissionProperties["name"])
        val schemaIds = arrayValue(nameSchema["enum"]).map { it as String }
        check(schemaIds == capabilityIds) { "Manifest schema capability enum drifted from the canonical contract" }

        val validatorText = validatorFile.asFile.readText()
        val permissionBlock = Regex(
            "private val allowedPermissions = setOf\\((.*?)\\n    \\)",
            RegexOption.DOT_MATCHES_ALL,
        ).find(validatorText)?.groupValues?.get(1) ?: error("ManifestValidator permission set was not found")
        val validatorIds = Regex("\\\"([^\\\"]+)\\\"").findAll(permissionBlock).map { it.groupValues[1] }.toList()
        check(validatorIds == capabilityIds) { "ManifestValidator capabilities drifted from the canonical contract" }
        check("values.size > ${capabilityIds.size}" in validatorText) {
            "ManifestValidator permission limit drifted from the canonical contract"
        }
        check("Duplicate permission:" in validatorText) {
            "ManifestValidator must reject duplicate permission names"
        }
    }
}

tasks.named("check").configure {
    dependsOn(verifyToolBoxApiContract)
}
