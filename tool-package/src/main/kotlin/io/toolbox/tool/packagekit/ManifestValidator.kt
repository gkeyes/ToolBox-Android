package io.toolbox.tool.packagekit

internal object ManifestValidator {
    private val idPattern = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*){2,}$")
    private val versionPattern = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$")
    private val apiPattern = Regex("^1\\.0$")
    private val domainPattern = Regex("^(?:\\*\\.)?[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")
    private val allowedPermissions = setOf(
        "storage", "storage.secure", "clipboard.write", "clipboard.read", "share", "browser",
        "files.open", "files.save", "network", "device.basic", "haptics", "notifications",
        "shortcuts", "camera", "location", "background.tasks", "background.runtime",
        "location.background", "alarms",
    )

    fun parse(bytes: ByteArray): ToolManifest {
        val root = StrictJson.parse(bytes).asObject("manifest")
        root.requireOnly("manifest", TOP_LEVEL_FIELDS)
        val schemaVersion = requireInt(root, "schemaVersion", 1, 1)
        val id = requireString(root, "id", 5, 255, idPattern)
        val name = requireString(root, "name", 1, Int.MAX_VALUE)
        val shortName = optionalString(root, "shortName", 1, Int.MAX_VALUE)
        val description = optionalString(root, "description", 0, Int.MAX_VALUE)
        val version = requireString(root, "version", 1, Int.MAX_VALUE, versionPattern)
        val versionCode = requireInt(root, "versionCode", 1, Int.MAX_VALUE)
        val entry = validateRelativePath(root.required("entry").asString("entry"), htmlOnly = true)
        val icon = root["icon"]?.asString("icon")?.let {
            validateRelativePath(it, htmlOnly = false)
        }
        val apiVersion = requireString(root, "apiVersion", 1, 16, apiPattern)
        val minHostVersion = requireString(root, "minHostVersion", 1, Int.MAX_VALUE, versionPattern)
        val permissions = parsePermissions(root.required("permissions"))
        val v03Capabilities = setOf("background.runtime", "location.background", "alarms")
        if (permissions.any { it.name in v03Capabilities } && !versionAtLeast(minHostVersion, 0, 3, 0)) {
            throw JsonFormatException("0.3 capabilities require minHostVersion 0.3.0")
        }
        val securityProfile = when (root.required("securityProfile").asString("securityProfile")) {
            "strict" -> SecurityProfile.STRICT
            "compat" -> SecurityProfile.COMPAT
            else -> throw JsonFormatException("securityProfile must be strict or compat")
        }
        val network = root["network"]?.let { parseNetwork(it) }
        val categories = root["categories"]?.let(::parseCategories).orEmpty()
        val ui = root["ui"]?.let(::parseUi) ?: ManifestUi(
            orientation = null,
            allowFullscreen = false,
            statusBarStyle = ManifestStatusBarStyle.AUTO,
            showHostToolbar = true,
        )
        root["limits"]?.let(::validateLegacyLimits)
        return ToolManifest(
            schemaVersion = schemaVersion,
            id = id,
            name = name,
            shortName = shortName,
            description = description,
            version = version,
            versionCode = versionCode,
            entry = entry,
            icon = icon,
            apiVersion = apiVersion,
            minHostVersion = minHostVersion,
            categories = categories,
            permissions = permissions,
            securityProfile = securityProfile,
            network = network,
            ui = ui,
        )
    }

    private fun parsePermissions(value: JsonValue): List<ManifestPermission> {
        val values = value.asArray("permissions")
        val seen = mutableSetOf<String>()
        return values.mapIndexed { index, item ->
            val permission = item.asObject("permissions[$index]")
            permission.requireOnly("permissions[$index]", setOf("name", "reason", "required"))
            val name = permission.required("name").asString("permissions[$index].name")
            if (name !in allowedPermissions) throw JsonFormatException("Unsupported permission: $name")
            if (!seen.add(name)) throw JsonFormatException("Duplicate permission: $name")
            ManifestPermission(
                name = name,
                reason = requireString(permission, "reason", 1, Int.MAX_VALUE),
                required = permission["required"]?.asBoolean("permissions[$index].required") ?: false,
            )
        }
    }

    private fun parseNetwork(value: JsonValue): ManifestNetwork {
        val network = value.asObject("network")
        network.requireOnly("network", setOf("allowDomains", "allowRedirects", "maxResponseBytes", "timeoutMs", "allowUserDomains"))
        val domains = network["allowDomains"]?.asArray("network.allowDomains")?.mapIndexed { index, item ->
            val domain = item.asString("network.allowDomains[$index]")
            if (domain.length !in 1..253 || !domainPattern.matches(domain)) {
                throw JsonFormatException("Invalid network domain: $domain")
            }
            domain
        }.orEmpty()
        if (domains.toSet().size != domains.size) {
            throw JsonFormatException("network.allowDomains must contain unique domains")
        }
        val timeoutMs = network["timeoutMs"]?.let {
            requireIntValue(it, "network.timeoutMs", 0, Int.MAX_VALUE)
        } ?: 0
        network["allowUserDomains"]?.asBoolean("network.allowUserDomains")
        network["allowRedirects"]?.asBoolean("network.allowRedirects")
        return ManifestNetwork(
            maxResponseBytes = network["maxResponseBytes"]?.let {
                val number = (it as? JsonValue.NumberValue)?.value
                    ?: throw JsonFormatException("network.maxResponseBytes must be an integer")
                val bytes = try { number.longValueExact() } catch (_: ArithmeticException) {
                    throw JsonFormatException("network.maxResponseBytes must be a safe integer")
                }
                if (bytes !in 1..9_007_199_254_740_991L) throw JsonFormatException("network.maxResponseBytes must be a positive safe integer")
                bytes
            },
            timeoutMs = timeoutMs,
        )
    }

    private fun parseCategories(value: JsonValue): List<String> {
        val categories = value.asArray("categories").mapIndexed { index, item ->
            val category = item.asString("categories[$index]")
            if (category.isEmpty()) throw JsonFormatException("categories[$index] length is invalid")
            category
        }
        if (categories.toSet().size != categories.size) {
            throw JsonFormatException("categories must contain unique values")
        }
        return categories
    }

    private fun parseUi(value: JsonValue): ManifestUi {
        val ui = value.asObject("ui")
        ui.requireOnly("ui", setOf("orientation", "allowFullscreen", "statusBarStyle", "showHostToolbar"))
        val orientation = ui["orientation"]?.asString("ui.orientation")?.let {
            when (it) {
                "unspecified" -> ManifestOrientation.UNSPECIFIED
                "portrait" -> ManifestOrientation.PORTRAIT
                "landscape" -> ManifestOrientation.LANDSCAPE
                else -> throw JsonFormatException("Invalid ui.orientation")
            }
        }
        val statusBarStyle = ui["statusBarStyle"]?.asString("ui.statusBarStyle")?.let {
            when (it) {
                "auto" -> ManifestStatusBarStyle.AUTO
                "light" -> ManifestStatusBarStyle.LIGHT
                "dark" -> ManifestStatusBarStyle.DARK
                else -> throw JsonFormatException("Invalid ui.statusBarStyle")
            }
        } ?: ManifestStatusBarStyle.AUTO
        return ManifestUi(
            orientation = orientation,
            allowFullscreen = ui["allowFullscreen"]?.asBoolean("ui.allowFullscreen") ?: false,
            statusBarStyle = statusBarStyle,
            showHostToolbar = ui["showHostToolbar"]?.asBoolean("ui.showHostToolbar") ?: true,
        )
    }

    private fun validateLegacyLimits(value: JsonValue) {
        val limits = value.asObject("limits")
        limits.requireOnly("limits", setOf("storageBytes", "maxBridgePayloadBytes"))
        // Retain legacy JSON shape compatibility without retaining inactive quotas.
        limits.forEach { (name, value) ->
            val number = (value as? JsonValue.NumberValue)?.value
                ?: throw JsonFormatException("limits.$name must be an integer")
            if (number.signum() < 0 || number.stripTrailingZeros().scale() > 0) {
                throw JsonFormatException("limits.$name must be a non-negative integer")
            }
        }
    }

    private fun validateRelativePath(path: String, htmlOnly: Boolean): String {
        val safe = try {
            PackagePathPolicy.validate(path)
        } catch (error: InspectionRejected) {
            throw JsonFormatException(error.rejection.detail)
        }
        if (safe.directory || !PATH_CHARACTERS.matches(safe.normalized) || (htmlOnly && !safe.normalized.endsWith(".html"))) {
            throw JsonFormatException("Invalid relative ${if (htmlOnly) "HTML " else ""}path: $path")
        }
        return safe.normalized
    }

    private fun requireString(
        values: Map<String, JsonValue>,
        name: String,
        min: Int,
        max: Int,
        pattern: Regex? = null,
    ): String {
        val value = values.required(name).asString(name)
        if (value.length !in min..max || pattern?.matches(value) == false) {
            throw JsonFormatException("$name does not match its schema")
        }
        return value
    }

    private fun optionalString(values: Map<String, JsonValue>, name: String, min: Int, max: Int): String? {
        val value = values[name]?.asString(name) ?: return null
        if (value.length !in min..max) throw JsonFormatException("$name does not match its schema")
        return value
    }

    private fun requireInt(values: Map<String, JsonValue>, name: String, min: Int, max: Int): Int =
        requireIntValue(values.required(name), name, min, max)

    private fun requireIntValue(value: JsonValue, name: String, min: Int, max: Int): Int {
        val number = value.asInt(name)
        if (number !in min..max) throw JsonFormatException("$name must be between $min and $max")
        return number
    }

    private fun versionAtLeast(value: String, major: Int, minor: Int, patch: Int): Boolean {
        val numeric = value.substringBefore('-').substringBefore('+').split('.').map {
            it.toIntOrNull() ?: throw JsonFormatException("minHostVersion contains an out-of-range number")
        }
        return numeric.zip(listOf(major, minor, patch)).firstOrNull { (left, right) -> left != right }
            ?.let { (left, right) -> left > right }
            ?: true
    }

    private val TOP_LEVEL_FIELDS = setOf(
        "schemaVersion", "id", "name", "shortName", "description", "version", "versionCode",
        "entry", "icon", "apiVersion", "minHostVersion", "categories", "permissions", "network",
        "securityProfile", "ui", "limits",
    )
    private val PATH_CHARACTERS = Regex("^[A-Za-z0-9._/-]+$")
}
