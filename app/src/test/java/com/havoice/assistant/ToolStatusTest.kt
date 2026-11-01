package com.havoice.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolStatusTest {
    @Test
    fun friendlyStatusForEachKindOfTool() {
        assertEquals("Logging…", toolStatus("log_strength"))
        assertEquals("Logging…", toolStatus("log_exercise"))
        assertEquals("Looking up the food…", toolStatus("search_food"))
        assertEquals("Checking today's totals…", toolStatus("day_summary"))
        assertEquals("Working on the music…", toolStatus("spotify_play"))
        assertEquals("Saving to memory…", toolStatus("write_note"))
        assertEquals("Checking memory…", toolStatus("search_notes"))
        assertEquals("Controlling the house…", toolStatus("HassTurnOn"))
        assertEquals("Checking the house…", toolStatus("GetLiveContext"))
        assertEquals("Working on it…", toolStatus("something_new"))
    }
}
