package io.toolbox.host.ui

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>

    data class Content<T>(val value: T) : UiState<T>

    data class Error(
        val code: String,
        val message: String,
    ) : UiState<Nothing>
}
