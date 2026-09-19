package io.toolbox.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Reads the final catalog while holding the same boundary as restore and catalog commits. */
class CatalogLayoutRepository(
    private val settings: HostSettingsRepository,
    private val catalog: CatalogRepository,
    private val mutations: DataMutationLock,
) {
    constructor(repositories: CoreDataRepositories) : this(repositories.settings, repositories.catalog, repositories.mutations)

    suspend fun update(transform: (CatalogLayout, Set<String>) -> CatalogLayout): DataResult<Unit> =
        withContext(Dispatchers.IO) {
            mutations.write {
                val installed = catalog.observeCatalogProjection().first().mapTo(hashSetOf()) { it.toolId }
                settings.update { current ->
                    current.copy(catalogLayout = transform(current.catalogLayout, installed).validated())
                }
            }
        }

    /** Call only after startup recovery or a completed package/restore transaction, never on Flow emissions. */
    suspend fun reconcile(): DataResult<Unit> = update { layout, installed -> layout.reconcile(installed) }
}
