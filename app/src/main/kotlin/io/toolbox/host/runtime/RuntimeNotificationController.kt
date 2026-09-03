package io.toolbox.host.runtime

internal interface RuntimeNotificationSink {
    fun promote(card: RuntimeNotificationCard, usesLocation: Boolean)
    fun post(card: RuntimeNotificationCard): Boolean
    fun cancel(notificationId: Int)
    fun stopForeground()
}

internal class RuntimeNotificationController(private val sink: RuntimeNotificationSink) {
    private val published = linkedMapOf<Int, RuntimeNotificationCard>()
    private var carrierId: Int? = null
    private var usesLocation = false

    val hasForegroundCarrier: Boolean get() = carrierId != null

    fun render(snapshot: RuntimeForegroundNotificationSnapshot) {
        val cards = snapshot.cards()
        require(cards.all { it.notificationId > 0 })
        require(cards.map(RuntimeNotificationCard::notificationId).distinct().size == cards.size)
        if (cards.isEmpty()) {
            clear()
            return
        }
        val carrier = cards.firstOrNull { it.notificationId == carrierId } ?: cards.first()
        if (carrier.notificationId != carrierId || usesLocation != snapshot.usesLocation) {
            sink.promote(carrier, snapshot.usesLocation)
            carrierId = carrier.notificationId
            usesLocation = snapshot.usesLocation
            published[carrier.notificationId] = carrier
        }
        val activeIds = cards.mapTo(hashSetOf(), RuntimeNotificationCard::notificationId)
        (published.keys - activeIds).forEach { id ->
            sink.cancel(id)
            published.remove(id)
        }
        cards.forEach { card ->
            if (published[card.notificationId] != card) {
                if (sink.post(card)) {
                    published[card.notificationId] = card
                }
            }
        }
    }

    fun clear() {
        if (carrierId != null) sink.stopForeground()
        published.keys.toList().forEach(sink::cancel)
        published.clear()
        carrierId = null
        usesLocation = false
    }
}

internal class RuntimeNotificationIds {
    private val assigned = mutableMapOf<String, Int>()
    private val occupied = mutableSetOf<Int>()
    private var next = FIRST_ID

    fun claim(sessionId: String, restoredId: Int = 0): Int {
        assigned[sessionId]?.let { return it }
        val id = if (restoredId >= FIRST_ID && occupied.add(restoredId)) {
            restoredId
        } else {
            while (!occupied.add(next)) next = if (next == Int.MAX_VALUE) FIRST_ID else next + 1
            next.also { next = if (next == Int.MAX_VALUE) FIRST_ID else next + 1 }
        }
        assigned[sessionId] = id
        return id
    }

    fun release(sessionId: String) {
        assigned.remove(sessionId)?.let(occupied::remove)
    }

    companion object {
        const val FIRST_ID = 0x550000
        const val LEGACY_RUNTIME_ID = 0x544258
    }
}
