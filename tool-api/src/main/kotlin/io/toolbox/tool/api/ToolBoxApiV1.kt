package io.toolbox.tool.api

enum class ToolBoxCapabilityId {
    STORAGE,
    STORAGE_SECURE,
    CLIPBOARD_WRITE,
    CLIPBOARD_READ,
    SHARE,
    BROWSER,
    FILES_OPEN,
    FILES_SAVE,
    NETWORK,
    DEVICE_BASIC,
    HAPTICS,
    NOTIFICATIONS,
    SHORTCUTS,
    CAMERA,
    LOCATION,
    BACKGROUND_TASKS,
    BACKGROUND_RUNTIME,
    LOCATION_BACKGROUND,
    ALARMS,
}

data class CapabilityDescriptor(
    val id: ToolBoxCapabilityId,
    val wireName: String,
    val systemPermissions: Set<String>,
)

data class MethodDescriptor(
    val name: String,
    val capability: ToolBoxCapabilityId?,
    val requestType: String,
    val resultType: String,
)

object ToolBoxApiV1 {
    const val API_VERSION: String = "1.0"

    val capabilities: List<CapabilityDescriptor> = listOf(
        CapabilityDescriptor(ToolBoxCapabilityId.STORAGE, "storage", emptySet()),
        CapabilityDescriptor(ToolBoxCapabilityId.STORAGE_SECURE, "storage.secure", emptySet()),
        CapabilityDescriptor(ToolBoxCapabilityId.CLIPBOARD_WRITE, "clipboard.write", emptySet()),
        CapabilityDescriptor(ToolBoxCapabilityId.CLIPBOARD_READ, "clipboard.read", emptySet()),
        CapabilityDescriptor(ToolBoxCapabilityId.SHARE, "share", emptySet()),
        CapabilityDescriptor(ToolBoxCapabilityId.BROWSER, "browser", emptySet()),
        CapabilityDescriptor(ToolBoxCapabilityId.FILES_OPEN, "files.open", emptySet()),
        CapabilityDescriptor(ToolBoxCapabilityId.FILES_SAVE, "files.save", emptySet()),
        CapabilityDescriptor(ToolBoxCapabilityId.NETWORK, "network", setOf("android.permission.INTERNET")),
        CapabilityDescriptor(ToolBoxCapabilityId.DEVICE_BASIC, "device.basic", emptySet()),
        CapabilityDescriptor(ToolBoxCapabilityId.HAPTICS, "haptics", setOf("android.permission.VIBRATE")),
        CapabilityDescriptor(ToolBoxCapabilityId.NOTIFICATIONS, "notifications", setOf("android.permission.POST_NOTIFICATIONS")),
        CapabilityDescriptor(ToolBoxCapabilityId.SHORTCUTS, "shortcuts", emptySet()),
        CapabilityDescriptor(ToolBoxCapabilityId.CAMERA, "camera", emptySet()),
        CapabilityDescriptor(ToolBoxCapabilityId.LOCATION, "location", setOf("android.permission.ACCESS_COARSE_LOCATION")),
        CapabilityDescriptor(ToolBoxCapabilityId.BACKGROUND_TASKS, "background.tasks", emptySet()),
        CapabilityDescriptor(ToolBoxCapabilityId.BACKGROUND_RUNTIME, "background.runtime", emptySet()),
        CapabilityDescriptor(ToolBoxCapabilityId.LOCATION_BACKGROUND, "location.background", setOf("android.permission.ACCESS_BACKGROUND_LOCATION")),
        CapabilityDescriptor(ToolBoxCapabilityId.ALARMS, "alarms", emptySet()),
    )

    val methods: List<MethodDescriptor> = listOf(
        MethodDescriptor("ready", null, "void", "ReadyResult"),
        MethodDescriptor("runtime.getState", null, "void", "RuntimePresentationState"),
        MethodDescriptor("runtime.ackEvents", null, "RuntimeEventAck", "void"),
        MethodDescriptor("runtime.flushComplete", null, "RuntimeFlushResult", "void"),
        MethodDescriptor("ui.toast", null, "ToastRequest", "void"),
        MethodDescriptor("crypto.sha256", null, "Sha256Request", "Sha256Result"),
        MethodDescriptor("storage.get", ToolBoxCapabilityId.STORAGE, "StorageKeyRequest", "JsonValue | null"),
        MethodDescriptor("storage.getMany", ToolBoxCapabilityId.STORAGE, "StorageKeysRequest", "(JsonValue | null)[]"),
        MethodDescriptor("storage.apply", ToolBoxCapabilityId.STORAGE, "StorageApplyRequest", "void"),
        MethodDescriptor("storage.set", ToolBoxCapabilityId.STORAGE, "StorageSetRequest", "void"),
        MethodDescriptor("storage.remove", ToolBoxCapabilityId.STORAGE, "StorageKeyRequest", "void"),
        MethodDescriptor("storage.keys", ToolBoxCapabilityId.STORAGE, "void", "string[]"),
        MethodDescriptor("storage.clear", ToolBoxCapabilityId.STORAGE, "void", "void"),
        MethodDescriptor("storage.secure.get", ToolBoxCapabilityId.STORAGE_SECURE, "StorageKeyRequest", "JsonValue | null"),
        MethodDescriptor("storage.secure.set", ToolBoxCapabilityId.STORAGE_SECURE, "StorageSetRequest", "void"),
        MethodDescriptor("storage.secure.remove", ToolBoxCapabilityId.STORAGE_SECURE, "StorageKeyRequest", "void"),
        MethodDescriptor("device.getBasicInfo", ToolBoxCapabilityId.DEVICE_BASIC, "void", "BasicDeviceInfo"),
        MethodDescriptor("haptics.perform", ToolBoxCapabilityId.HAPTICS, "HapticsRequest", "void"),
        MethodDescriptor("clipboard.writeText", ToolBoxCapabilityId.CLIPBOARD_WRITE, "ClipboardWriteRequest", "void"),
        MethodDescriptor("network.request", ToolBoxCapabilityId.NETWORK, "NetworkRequest", "NetworkResponse"),
        MethodDescriptor("network.openStream", ToolBoxCapabilityId.NETWORK, "NetworkStreamOpenRequest", "NetworkStreamResponse"),
        MethodDescriptor("network.readStream", ToolBoxCapabilityId.NETWORK, "NetworkStreamIdRequest", "NetworkStreamChunk"),
        MethodDescriptor("network.cancelStream", ToolBoxCapabilityId.NETWORK, "NetworkStreamIdRequest", "void"),
        MethodDescriptor("network.openMedia", ToolBoxCapabilityId.NETWORK, "NetworkMediaOpenRequest", "NetworkMediaSession"),
        MethodDescriptor("network.closeMedia", ToolBoxCapabilityId.NETWORK, "NetworkMediaSessionIdRequest", "void"),
        MethodDescriptor("notifications.post", ToolBoxCapabilityId.NOTIFICATIONS, "NotificationPostRequest", "void"),
        MethodDescriptor("notifications.update", ToolBoxCapabilityId.NOTIFICATIONS, "NotificationPostRequest", "void"),
        MethodDescriptor("notifications.cancel", ToolBoxCapabilityId.NOTIFICATIONS, "NotificationCancelRequest", "void"),
        MethodDescriptor("notifications.live.start", ToolBoxCapabilityId.NOTIFICATIONS, "LiveNotificationRequest", "LiveNotificationResult"),
        MethodDescriptor("notifications.live.update", ToolBoxCapabilityId.NOTIFICATIONS, "LiveNotificationRequest", "LiveNotificationResult"),
        MethodDescriptor("notifications.live.end", ToolBoxCapabilityId.NOTIFICATIONS, "BackgroundSessionIdRequest", "void"),
        MethodDescriptor("background.enqueue", ToolBoxCapabilityId.BACKGROUND_TASKS, "BackgroundTaskSpec", "TaskIdResult"),
        MethodDescriptor("background.schedulePeriodic", ToolBoxCapabilityId.BACKGROUND_TASKS, "PeriodicTaskSpec", "TaskIdResult"),
        MethodDescriptor("background.start", ToolBoxCapabilityId.BACKGROUND_RUNTIME, "BackgroundStartOptions", "BackgroundSessionSummary"),
        MethodDescriptor("background.stop", ToolBoxCapabilityId.BACKGROUND_RUNTIME, "BackgroundSessionIdRequest", "void"),
        MethodDescriptor("background.status", ToolBoxCapabilityId.BACKGROUND_RUNTIME, "BackgroundSessionIdRequest", "BackgroundSessionSummary | null"),
        MethodDescriptor("background.updateActivity", ToolBoxCapabilityId.BACKGROUND_RUNTIME, "BackgroundActivityRequest", "void"),
        MethodDescriptor("background.list", ToolBoxCapabilityId.BACKGROUND_TASKS, "void", "TaskSummary[]"),
        MethodDescriptor("background.listSessions", ToolBoxCapabilityId.BACKGROUND_RUNTIME, "void", "BackgroundSessionSummary[]"),
        MethodDescriptor("background.getResult", ToolBoxCapabilityId.BACKGROUND_TASKS, "TaskIdRequest", "TaskRunResult | null"),
        MethodDescriptor("background.cancel", ToolBoxCapabilityId.BACKGROUND_TASKS, "TaskIdRequest", "void"),
        MethodDescriptor("background.setTimer", ToolBoxCapabilityId.BACKGROUND_RUNTIME, "BackgroundTimerRequest", "void"),
        MethodDescriptor("background.cancelTimer", ToolBoxCapabilityId.BACKGROUND_RUNTIME, "BackgroundTimerKeyRequest", "void"),
        MethodDescriptor("clipboard.readText", ToolBoxCapabilityId.CLIPBOARD_READ, "void", "ClipboardReadResult"),
        MethodDescriptor("share.text", ToolBoxCapabilityId.SHARE, "ShareTextRequest", "void"),
        MethodDescriptor("browser.open", ToolBoxCapabilityId.BROWSER, "BrowserOpenRequest", "void"),
        MethodDescriptor("files.open", ToolBoxCapabilityId.FILES_OPEN, "FileOpenRequest", "FileToken | null"),
        MethodDescriptor("files.read", null, "FileTokenRequest", "FileReadResult"),
        MethodDescriptor("files.save", ToolBoxCapabilityId.FILES_SAVE, "FileSaveRequest", "FileToken | null"),
        MethodDescriptor("shortcuts.pin", ToolBoxCapabilityId.SHORTCUTS, "ShortcutPinRequest", "ShortcutPinResult"),
        MethodDescriptor("camera.capture", ToolBoxCapabilityId.CAMERA, "void", "FileToken | null"),
        MethodDescriptor("location.getCurrent", ToolBoxCapabilityId.LOCATION, "LocationRequest", "LocationResult"),
        MethodDescriptor("location.watch", ToolBoxCapabilityId.LOCATION, "LocationWatchOptions", "WatchIdResult"),
        MethodDescriptor("location.clearWatch", ToolBoxCapabilityId.LOCATION, "WatchIdRequest", "void"),
        MethodDescriptor("alarms.schedule", ToolBoxCapabilityId.ALARMS, "AlarmScheduleOptions", "AlarmSummary"),
        MethodDescriptor("alarms.list", ToolBoxCapabilityId.ALARMS, "void", "AlarmSummary[]"),
        MethodDescriptor("alarms.cancel", ToolBoxCapabilityId.ALARMS, "AlarmIdRequest", "void"),
    )

    private val capabilitiesById = capabilities.associateBy(CapabilityDescriptor::id)
    private val capabilitiesByWireName = capabilities.associateBy(CapabilityDescriptor::wireName)
    private val methodsByName = methods.associateBy(MethodDescriptor::name)

    fun capability(id: ToolBoxCapabilityId): CapabilityDescriptor = checkNotNull(capabilitiesById[id])

    fun capability(wireName: String): CapabilityDescriptor? = capabilitiesByWireName[wireName]

    fun method(name: String): MethodDescriptor? = methodsByName[name]
}
