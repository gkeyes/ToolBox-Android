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
                require(group.keys == setOf("id", "name", "members", "expanded")) { "CATALOG_LAYOUT_INVALID" }
                CatalogGroup(group.getValue("id").text(), group.getValue("name").text(),
                    group.getValue("members").ids(), group.getValue("expanded").jsonPrimitive.let { require(!it.isString); it.boolean })
            },
            sort = CatalogSort.valueOf(root.getValue("sort").text()),
        ).validated()
    }
    private fun JsonElement.text(): String = jsonPrimitive.let { require(it.isString); it.content }
    private fun JsonElement.ids(): List<String> = jsonArray.map { it.text() }
}
