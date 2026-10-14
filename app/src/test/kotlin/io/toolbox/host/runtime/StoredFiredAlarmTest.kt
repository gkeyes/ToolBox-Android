package io.toolbox.host.runtime

import io.toolbox.tool.runtime.RpcValue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StoredFiredAlarmTest {
    @Test
    fun aFiredAlarmSurvivesPersistenceWithItsFiringTime() {
        val fired = StoredFiredAlarm(StoredAlarm("wake", triggerAt = 2_000, scheduledAt = 1_000), firedAt = 2_050)
        assertEquals(fired, StoredFiredAlarm.fromJson(fired.toJson()))
    }

    @Test
    fun theReplayedEventCarriesTheOriginalFiringTime() {
        val event = StoredFiredAlarm(StoredAlarm("wake", triggerAt = 2_000, scheduledAt = 1_000), firedAt = 2_050).toEvent()
        assertEquals(RpcValue.StringValue("wake"), event.value["id"])
        assertEquals(RpcValue.Number(2_050.0), event.value["firedAt"])
    }
}
