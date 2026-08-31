package io.toolbox.tool.api

enum class ContractPhase { M1, M2, M3 }

enum class GestureRequirement { NONE, RECENT, CONFIRMED_ONE_SHOT }

enum class CapabilityContext { FOREGROUND_ONLY, FOREGROUND_OR_DELEGATED_BACKGROUND }

enum class ToolBoxCapabilityId {
    STORAGE,
    STORAGE_SECURE,
    CLIPBOARD_WRITE,
    CLIPBOARD_READ,
    SHARE,
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
    val contractPhase: ContractPhase,
    val defaultGrant: Boolean,
    val systemPermissions: Set<String>,
    val gestureRequirement: GestureRequirement,
    val context: CapabilityContext,
)

data class MethodDescriptor(
    val name: String,
    val contractPhase: ContractPhase,
    val capability: ToolBoxCapabilityId?,
    val requestType: String,
    val resultType: String,
)

object ToolBoxApiV1 {
    const val API_VERSION: String = "1.0"
    const val CANONICAL_SHA256: String = "02de7340d7a5d18231ddcbe224d4adbfa24f30c690d63c9f43bb29df7f42cb57"

    val capabilities: List<CapabilityDescriptor> = listOf(
        CapabilityDescriptor(ToolBoxCapabilityId.STORAGE, "storage", ContractPhase.M1, true, emptySet(), GestureRequirement.NONE, CapabilityContext.FOREGROUND_ONLY),
        CapabilityDescriptor(ToolBoxCapabilityId.STORAGE_SECURE, "storage.secure", ContractPhase.M1, true, emptySet(), GestureRequirement.NONE, CapabilityContext.FOREGROUND_ONLY),
        CapabilityDescriptor(ToolBoxCapabilityId.CLIPBOARD_WRITE, "clipboard.write", ContractPhase.M1, true, emptySet(), GestureRequirement.RECENT, CapabilityContext.FOREGROUND_ONLY),
        CapabilityDescriptor(ToolBoxCapabilityId.CLIPBOARD_READ, "clipboard.read", ContractPhase.M3, false, emptySet(), GestureRequirement.CONFIRMED_ONE_SHOT, CapabilityContext.FOREGROUND_ONLY),
        CapabilityDescriptor(ToolBoxCapabilityId.SHARE, "share", ContractPhase.M3, false, emptySet(), GestureRequirement.RECENT, CapabilityContext.FOREGROUND_ONLY),
        CapabilityDescriptor(ToolBoxCapabilityId.FILES_OPEN, "files.open", ContractPhase.M3, false, emptySet(), GestureRequirement.RECENT, CapabilityContext.FOREGROUND_ONLY),
        CapabilityDescriptor(ToolBoxCapabilityId.FILES_SAVE, "files.save", ContractPhase.M3, false, emptySet(), GestureRequirement.RECENT, CapabilityContext.FOREGROUND_ONLY),
        CapabilityDescriptor(ToolBoxCapabilityId.NETWORK, "network", ContractPhase.M2, false, setOf("android.permission.INTERNET"), GestureRequirement.NONE, CapabilityContext.FOREGROUND_OR_DELEGATED_BACKGROUND),
        CapabilityDescriptor(ToolBoxCapabilityId.DEVICE_BASIC, "device.basic", ContractPhase.M1, true, emptySet(), GestureRequirement.NONE, CapabilityContext.FOREGROUND_ONLY),
        CapabilityDescriptor(ToolBoxCapabilityId.HAPTICS, "haptics", ContractPhase.M1, true, setOf("android.permission.VIBRATE"), GestureRequirement.RECENT, CapabilityContext.FOREGROUND_ONLY),
        CapabilityDescriptor(ToolBoxCapabilityId.NOTIFICATIONS, "notifications", ContractPhase.M2, false, setOf("android.permission.POST_NOTIFICATIONS"), GestureRequirement.NONE, CapabilityContext.FOREGROUND_OR_DELEGATED_BACKGROUND),
        CapabilityDescriptor(ToolBoxCapabilityId.SHORTCUTS, "shortcuts", ContractPhase.M3, false, emptySet(), GestureRequirement.RECENT, CapabilityContext.FOREGROUND_ONLY),
        CapabilityDescriptor(ToolBoxCapabilityId.CAMERA, "camera", ContractPhase.M3, false, emptySet(), GestureRequirement.RECENT, CapabilityContext.FOREGROUND_ONLY),
        CapabilityDescriptor(ToolBoxCapabilityId.LOCATION, "location", ContractPhase.M3, false, setOf("android.permission.ACCESS_COARSE_LOCATION"), GestureRequirement.NONE, CapabilityContext.FOREGROUND_OR_DELEGATED_BACKGROUND),
        CapabilityDescriptor(ToolBoxCapabilityId.BACKGROUND_TASKS, "background.tasks", ContractPhase.M2, false, emptySet(), GestureRequirement.NONE, CapabilityContext.FOREGROUND_OR_DELEGATED_BACKGROUND),
        CapabilityDescriptor(ToolBoxCapabilityId.BACKGROUND_RUNTIME, "background.runtime", ContractPhase.M3, false, emptySet(), GestureRequirement.NONE, CapabilityContext.FOREGROUND_OR_DELEGATED_BACKGROUND),
        CapabilityDescriptor(ToolBoxCapabilityId.LOCATION_BACKGROUND, "location.background", ContractPhase.M3, false, setOf("android.permission.ACCESS_BACKGROUND_LOCATION"), GestureRequirement.NONE, CapabilityContext.FOREGROUND_OR_DELEGATED_BACKGROUND),
        CapabilityDescriptor(ToolBoxCapabilityId.ALARMS, "alarms", ContractPhase.M3, false, emptySet(), GestureRequirement.NONE, CapabilityContext.FOREGROUND_OR_DELEGATED_BACKGROUND),
    )

    val methods: List<MethodDescriptor> = listOf(
        MethodDescriptor("ready", ContractPhase.M1, null, "void", "ReadyResult"),
        MethodDescriptor("ui.toast", ContractPhase.M1, null, "ToastRequest", "void"),
        MethodDescriptor("crypto.sha256", ContractPhase.M1, null, "Sha256Request", "Sha256Result"),
        MethodDescriptor("storage.get", ContractPhase.M1, ToolBoxCapabilityId.STORAGE, "StorageKeyRequest", "JsonValue | null"),
        MethodDescriptor("storage.set", ContractPhase.M1, ToolBoxCapabilityId.STORAGE, "StorageSetRequest", "void"),
        MethodDescriptor("storage.remove", ContractPhase.M1, ToolBoxCapabilityId.STORAGE, "StorageKeyRequest", "void"),
        MethodDescriptor("storage.keys", ContractPhase.M1, ToolBoxCapabilityId.STORAGE, "void", "string[]"),
        MethodDescriptor("storage.clear", ContractPhase.M1, ToolBoxCapabilityId.STORAGE, "void", "void"),
        MethodDescriptor("storage.secure.get", ContractPhase.M1, ToolBoxCapabilityId.STORAGE_SECURE, "StorageKeyRequest", "JsonValue | null"),
        MethodDescriptor("storage.secure.set", ContractPhase.M1, ToolBoxCapabilityId.STORAGE_SECURE, "StorageSetRequest", "void"),
        MethodDescriptor("storage.secure.remove", ContractPhase.M1, ToolBoxCapabilityId.STORAGE_SECURE, "StorageKeyRequest", "void"),
        MethodDescriptor("device.getBasicInfo", ContractPhase.M1, ToolBoxCapabilityId.DEVICE_BASIC, "void", "BasicDeviceInfo"),
        MethodDescriptor("haptics.perform", ContractPhase.M1, ToolBoxCapabilityId.HAPTICS, "HapticsRequest", "void"),
        MethodDescriptor("clipboard.writeText", ContractPhase.M1, ToolBoxCapabilityId.CLIPBOARD_WRITE, "ClipboardWriteRequest", "void"),
        MethodDescriptor("network.request", ContractPhase.M2, ToolBoxCapabilityId.NETWORK, "NetworkRequest", "NetworkResponse"),
        MethodDescriptor("notifications.post", ContractPhase.M2, ToolBoxCapabilityId.NOTIFICATIONS, "NotificationPostRequest", "void"),
        MethodDescriptor("notifications.update", ContractPhase.M3, ToolBoxCapabilityId.NOTIFICATIONS, "NotificationPostRequest", "void"),
        MethodDescriptor("notifications.cancel", ContractPhase.M2, ToolBoxCapabilityId.NOTIFICATIONS, "NotificationCancelRequest", "void"),
        MethodDescriptor("notifications.live.start", ContractPhase.M3, ToolBoxCapabilityId.NOTIFICATIONS, "LiveNotificationRequest", "LiveNotificationResult"),
        MethodDescriptor("notifications.live.update", ContractPhase.M3, ToolBoxCapabilityId.NOTIFICATIONS, "LiveNotificationRequest", "LiveNotificationResult"),
        MethodDescriptor("notifications.live.end", ContractPhase.M3, ToolBoxCapabilityId.NOTIFICATIONS, "BackgroundSessionIdRequest", "void"),
        MethodDescriptor("background.enqueue", ContractPhase.M2, ToolBoxCapabilityId.BACKGROUND_TASKS, "BackgroundTaskSpec", "TaskIdResult"),
        MethodDescriptor("background.schedulePeriodic", ContractPhase.M2, ToolBoxCapabilityId.BACKGROUND_TASKS, "PeriodicTaskSpec", "TaskIdResult"),
        MethodDescriptor("background.start", ContractPhase.M3, ToolBoxCapabilityId.BACKGROUND_RUNTIME, "BackgroundStartOptions", "BackgroundSessionSummary"),
        MethodDescriptor("background.stop", ContractPhase.M3, ToolBoxCapabilityId.BACKGROUND_RUNTIME, "BackgroundSessionIdRequest", "void"),
        MethodDescriptor("background.status", ContractPhase.M3, ToolBoxCapabilityId.BACKGROUND_RUNTIME, "BackgroundSessionIdRequest", "BackgroundSessionSummary | null"),
        MethodDescriptor("background.list", ContractPhase.M2, ToolBoxCapabilityId.BACKGROUND_TASKS, "void", "TaskSummary[]"),
        MethodDescriptor("background.listSessions", ContractPhase.M3, ToolBoxCapabilityId.BACKGROUND_RUNTIME, "void", "BackgroundSessionSummary[]"),
        MethodDescriptor("background.getResult", ContractPhase.M2, ToolBoxCapabilityId.BACKGROUND_TASKS, "TaskIdRequest", "TaskRunResult | null"),
        MethodDescriptor("background.cancel", ContractPhase.M2, ToolBoxCapabilityId.BACKGROUND_TASKS, "TaskIdRequest", "void"),
        MethodDescriptor("background.setTimer", ContractPhase.M3, ToolBoxCapabilityId.BACKGROUND_RUNTIME, "BackgroundTimerRequest", "void"),
        MethodDescriptor("background.cancelTimer", ContractPhase.M3, ToolBoxCapabilityId.BACKGROUND_RUNTIME, "BackgroundTimerKeyRequest", "void"),
        MethodDescriptor("clipboard.readText", ContractPhase.M3, ToolBoxCapabilityId.CLIPBOARD_READ, "void", "ClipboardReadResult"),
        MethodDescriptor("share.text", ContractPhase.M3, ToolBoxCapabilityId.SHARE, "ShareTextRequest", "void"),
        MethodDescriptor("files.open", ContractPhase.M3, ToolBoxCapabilityId.FILES_OPEN, "FileOpenRequest", "FileToken | null"),
        MethodDescriptor("files.read", ContractPhase.M3, null, "FileTokenRequest", "FileReadResult"),
        MethodDescriptor("files.save", ContractPhase.M3, ToolBoxCapabilityId.FILES_SAVE, "FileSaveRequest", "FileToken | null"),
        MethodDescriptor("shortcuts.pin", ContractPhase.M3, ToolBoxCapabilityId.SHORTCUTS, "ShortcutPinRequest", "ShortcutPinResult"),
        MethodDescriptor("camera.capture", ContractPhase.M3, ToolBoxCapabilityId.CAMERA, "void", "FileToken | null"),
        MethodDescriptor("location.getCurrent", ContractPhase.M3, ToolBoxCapabilityId.LOCATION, "LocationRequest", "LocationResult"),
        MethodDescriptor("location.watch", ContractPhase.M3, ToolBoxCapabilityId.LOCATION, "LocationWatchOptions", "WatchIdResult"),
        MethodDescriptor("location.clearWatch", ContractPhase.M3, ToolBoxCapabilityId.LOCATION, "WatchIdRequest", "void"),
        MethodDescriptor("alarms.schedule", ContractPhase.M3, ToolBoxCapabilityId.ALARMS, "AlarmScheduleOptions", "AlarmSummary"),
        MethodDescriptor("alarms.list", ContractPhase.M3, ToolBoxCapabilityId.ALARMS, "void", "AlarmSummary[]"),
        MethodDescriptor("alarms.cancel", ContractPhase.M3, ToolBoxCapabilityId.ALARMS, "AlarmIdRequest", "void"),
    )

    private val capabilitiesById = capabilities.associateBy(CapabilityDescriptor::id)
    private val capabilitiesByWireName = capabilities.associateBy(CapabilityDescriptor::wireName)
    private val methodsByName = methods.associateBy(MethodDescriptor::name)

    fun capability(id: ToolBoxCapabilityId): CapabilityDescriptor = checkNotNull(capabilitiesById[id])

    fun capability(wireName: String): CapabilityDescriptor? = capabilitiesByWireName[wireName]

    fun method(name: String): MethodDescriptor? = methodsByName[name]
}
