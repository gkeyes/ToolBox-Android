package io.toolbox.core.data

internal fun interface CatalogCommitHook {
    fun beforeCommit()

    companion object {
        val None = CatalogCommitHook { }
    }
}
