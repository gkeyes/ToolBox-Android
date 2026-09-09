package io.toolbox.host.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Pause covered UI updates, but keep an outstanding confirmation reactive. */
@Composable
internal fun <T> StateFlow<T>.collectAsStateWhileVisible(
    visible: Boolean,
    retainWhile: ((T) -> Boolean)? = null,
): State<T> {
    val retained = if (retainWhile == null) {
        false
    } else {
        // Only a Boolean is observed while hidden; unrelated updates do not recompose UI.
        val keepCollecting by remember(this, retainWhile) {
            map(retainWhile).distinctUntilChanged()
        }.collectAsStateWithLifecycle(
            initialValue = remember(this, retainWhile) { retainWhile(value) },
        )
        keepCollecting
    }
    val active = visible || retained
    return key(this, active) {
        if (active) {
            // Re-entry immediately reads the authoritative current value.
            collectAsStateWithLifecycle()
        } else {
            remember { mutableStateOf(value) }
        }
    }
}
