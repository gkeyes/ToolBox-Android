package io.toolbox.core.data

/** Host-owned presentation only. Room remains authoritative for installed tools. */
enum class CatalogSort { INSTALLED, NAME, LAST_OPENED }

data class CatalogGroup(
    val id: String,
    val name: String,
    val members: List<String> = emptyList(),
    val expanded: Boolean = true,
)

data class CatalogLayout(
    val version: Int = 1,
    val favorites: List<String> = emptyList(),
    // List position is the group's order; IDs stay stable across renames.
    val groups: List<CatalogGroup> = emptyList(),
    val sort: CatalogSort = CatalogSort.INSTALLED,
) {
    fun validated(): CatalogLayout = apply {
        require(version == 1) { "CATALOG_LAYOUT_VERSION" }
        require(favorites.all(String::isNotBlank) && favorites.distinct().size == favorites.size) { "CATALOG_LAYOUT_INVALID" }
        require(groups.map { it.id }.distinct().size == groups.size) { "CATALOG_LAYOUT_INVALID" }
        groups.forEach {
            require(it.id.isNotBlank() && it.name.isNotBlank()) { "CATALOG_LAYOUT_INVALID" }
            require(it.members.all(String::isNotBlank) && it.members.distinct().size == it.members.size) { "CATALOG_LAYOUT_INVALID" }
        }
    }

    fun reconcile(installedIds: Set<String>): CatalogLayout = copy(
        favorites = favorites.filter(installedIds::contains),
        groups = groups.map { it.copy(members = it.members.filter(installedIds::contains)) },
    )

    fun favorite(toolId: String, selected: Boolean): CatalogLayout = copy(
        favorites = if (selected) (favorites + toolId).distinct() else favorites - toolId,
    )

    fun groupMembership(groupId: String, toolId: String, selected: Boolean): CatalogLayout = copy(
        groups = groups.map { group -> if (group.id != groupId) group else group.copy(
            members = if (selected) (group.members + toolId).distinct() else group.members - toolId,
        ) },
    )

    fun moveFavorite(toolId: String, offset: Int): CatalogLayout = copy(favorites = favorites.moved(toolId, offset))
    fun moveGroup(groupId: String, offset: Int): CatalogLayout = copy(groups = groups.moved(groups.firstOrNull { it.id == groupId }, offset))
    fun moveMember(groupId: String, toolId: String, offset: Int): CatalogLayout = copy(
        groups = groups.map { if (it.id == groupId) it.copy(members = it.members.moved(toolId, offset)) else it },
    )
}

private fun <T> List<T>.moved(value: T?, offset: Int): List<T> {
    if (value == null) return this
    val from = indexOf(value)
    if (from < 0 || offset == 0) return this
    val to = (from.toLong() + offset).coerceIn(0L, lastIndex.toLong()).toInt()
    return toMutableList().apply { add(to, removeAt(from)) }
}
