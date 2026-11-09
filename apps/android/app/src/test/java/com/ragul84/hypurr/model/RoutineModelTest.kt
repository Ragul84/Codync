package com.ragul84.hypurr.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineModelTest {
    @Test fun listingDecodesHostShape() {
        val json = """
            {"routines":[{"id":"r1","botId":"b1","name":"Morning","instruction":"Do it",
              "triggers":[{"type":"cron","expression":"0 9 * * 1-5","timeZone":"Asia/Calcutta"}],
              "enabled":true,"createdAt":1,"updatedAt":2,"timeoutSeconds":3600,
              "triggerDescriptions":["Weekdays at 9:00 AM"],"nextRunAt":99}],
             "runs":[{"id":"run1","routineId":"r1","botId":"b1","status":"pending","createdAt":3}]}
        """.trimIndent()
        val listing = HypurrJson.decodeFromString(RoutineListing.serializer(), json)
        assertEquals(1, listing.routines.size)
        assertEquals("Morning", listing.routines[0].name)
        assertEquals("cron", listing.routines[0].triggers[0].type)
        assertEquals("Asia/Calcutta", listing.routines[0].triggers[0].timeZone)
        assertTrue(listing.runs[0].isActive)
        assertFalse(listing.runs[0].copy(status = "succeeded").isActive)
    }

    @Test fun scheduleDraftRoundTrip() {
        val draft = RoutineScheduleDraft(kind = "cron", expression = "0 9 * * *", zone = "UTC", selectedDays = listOf(1, 2, 3))
        val encoded = HypurrJson.encodeToString(RoutineScheduleDraft.serializer(), draft)
        val back = HypurrJson.decodeFromString(RoutineScheduleDraft.serializer(), encoded)
        assertEquals(draft, back)
    }

    @Test fun summaryPrefersActiveRun() {
        val routine = Routine(id = "r1", name = "X", triggerDescriptions = listOf("Every hour"))
        val runs = listOf(RoutineRun(id = "a", routineId = "r1", status = "running"))
        assertEquals("Running", com.ragul84.hypurr.ui.screens.routineSummary(routine, runs))
    }
}
