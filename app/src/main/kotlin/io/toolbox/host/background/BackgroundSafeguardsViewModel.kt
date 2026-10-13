package io.toolbox.host.background

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class BackgroundSystemState(
    val notificationsAllowed: Boolean,
    val backgroundLocationAllowed: Boolean,
    val exactAlarmsAllowed: Boolean,
    val ignoresBatteryOptimizations: Boolean,
)

internal data class BackgroundSafeguardsUiState(
    val systemState: BackgroundSystemState,
    val focusState: LiveNotificationSupportState = LiveNotificationSupportState(
        hyperOsProtocolVersion = 0,
        hyperOsSupported = false,
        hyperOsPermissionReported = false,
        androidLiveAvailable = false,
        androidLiveAllowed = false,
    ),
)

/** System permission and live-notification state shown on the background safeguards page. */
internal interface BackgroundSystemStateSource {
    fun readSystemState(): BackgroundSystemState
    suspend fun readLiveSupport(): LiveNotificationSupportState
    fun invalidateLiveSupportCache()
}

internal class AndroidBackgroundSystemStateSource(context: Context) : BackgroundSystemStateSource {
    private val context = context.applicationContext

    override fun readSystemState(): BackgroundSystemState {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val powerManager = context.getSystemService(PowerManager::class.java)
        return BackgroundSystemState(
            notificationsAllowed = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED && notificationManager.areNotificationsEnabled(),
            backgroundLocationAllowed = context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
            exactAlarmsAllowed = alarmManager.canScheduleExactAlarms(),
            ignoresBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName),
        )
    }

    override suspend fun readLiveSupport(): LiveNotificationSupportState =
        AndroidNotificationGateway(context).liveSupport()

    override fun invalidateLiveSupportCache() {
        AndroidNotificationGateway.invalidateMiuiSupportCache()
    }
}

/**
 * Reads system state outside composition. The page asks for a refresh when it resumes and
 * after a system permission request returns, which is when the state can have changed.
 */
internal class BackgroundSafeguardsViewModel(
    private val source: BackgroundSystemStateSource,
) : ViewModel() {
    // Read once up front so the first frame shows real system state, as the page did before.
    private val mutableState = MutableStateFlow(BackgroundSafeguardsUiState(systemState = source.readSystemState()))
    val state: StateFlow<BackgroundSafeguardsUiState> = mutableState.asStateFlow()
    private var liveSupportRead: Job? = null

    init {
        readLiveSupport()
    }

    /** Returning to the page: the user may have changed settings elsewhere. */
    fun onResume() {
        source.invalidateLiveSupportCache()
        refresh()
    }

    /** A system permission request returned. */
    fun refresh() {
        mutableState.update { it.copy(systemState = source.readSystemState()) }
        readLiveSupport()
    }

    private fun readLiveSupport() {
        liveSupportRead?.cancel()
        liveSupportRead = viewModelScope.launch {
            val support = source.readLiveSupport()
            mutableState.update { it.copy(focusState = support) }
        }
    }
}

internal class BackgroundSafeguardsViewModelFactory(
    private val source: BackgroundSystemStateSource,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(BackgroundSafeguardsViewModel::class.java.isAssignableFrom(modelClass)) {
            "Unsupported background safeguards ViewModel: ${modelClass.name}"
        }
        return BackgroundSafeguardsViewModel(source) as T
    }
}
