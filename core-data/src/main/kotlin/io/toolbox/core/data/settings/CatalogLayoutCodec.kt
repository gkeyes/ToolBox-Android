package io.toolbox.core.data.settings

import io.toolbox.core.data.CatalogGroup
import io.toolbox.core.data.CatalogLayout
import io.toolbox.core.data.CatalogSort
import kotlinx.serialization.json.*

/** Missing is handled by callers. Present but malformed/unknown data must never become an empty layout. */
object CatalogLayoutCodec {
    fun encode(layout: CatalogLayout): String {
        layout.validated()
        return buildJsonObject {
            put("version", layout.version)
            put("favorites", JsonArray(layout.favorites.map(::JsonPrimitive)))
            put("groups", JsonArray(layout.groups.map { group -> buildJsonObject {
                put("id", group.id)
                put("name", group.name)
                put("members", JsonArray(group.members.map(::JsonPrimitive)))
                put("expanded", group.expanded)
                // Optional style keys are written only when set, so default groups keep the
                // original four-key shape that older builds can still read.
                group.color?.let { put("color", it) }
                group.icon?.let { put("icon", it) }
            } }))
            put("sort", layout.sort.name)
        }.toString()
    }

    fun decode(value: String): CatalogLayout {
        val root = Json.parseToJsonElement(value).jsonObject
        require(root.keys == setOf("version", "favorites", "groups", "sort")) { "CATALOG_LAYOUT_INVALID" }
        val version = root.getValue("version").jsonPrimitive.let { require(!it.isString); it.int }
        require(version == 1) { "CATALOG_LAYOUT_VERSION" }
        return CatalogLayout(
            version = version,
            favorites = root.getValue("favorites").ids(),
            groups = root.getValue("groups").jsonArray.map { item ->
                val group = item.jsonObject
                require(group.keys.containsAll(GROUP_KEYS) && (group.keys - GROUP_KEYS).all(GROUP_STYLE_KEYS::contains)) { "CATALOG_LAYOUT_INVALID" }
                CatalogGroup(group.getValue("id").text(), group.getValue("name").text(),
                    group.getValue("members").ids(), group.getValue("expanded").jsonPrimitive.let { require(!it.isString); it.boolean },
                    color = group["color"]?.text(), icon = group["icon"]?.text())
            },
            sort = CatalogSort.valueOf(root.getValue("sort").text()),
        ).validated()
    }
    private val GROUP_KEYS = setOf("id", "name", "members", "expanded")
    private val GROUP_STYLE_KEYS = setOf("color", "icon")
    private fun JsonElement.text(): String = jsonPrimitive.let { require(it.isString); it.content }
    private fun JsonElement.ids(): List<String> = jsonArray.map { it.text() }
}
