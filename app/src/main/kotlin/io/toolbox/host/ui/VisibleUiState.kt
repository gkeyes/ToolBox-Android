package io.toolbox.host.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/** Pause only a covered UI subscription; its producer and retained UI state stay alive. */
@Composable
internal fun <T> StateFlow<T>.collectAsStateWhileVisible(visible: Boolean): State<T> = key(this, visible) {
    if (visible) {
        // Re-entry creates the collector with the authoritative current value immediately.
        collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(value) }
    }
}
