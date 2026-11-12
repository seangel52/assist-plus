package com.havoice.assistant

import com.havoice.assistant.ScanFraming.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanFramingTest {
    // A 640x480 camera buffer whose middle 360x480 is what a tall preview shows.
    private val crop = Box(140, 0, 500, 480)

    @Test
    fun uprightTurnsTheVisibleAreaWithTheImage() {
        assertEquals(crop, ScanFraming.upright(crop, 640, 480, 0))
        // A phone held upright: the buffer is turned a quarter clockwise, so the area is 480 wide.
        assertEquals(Box(0, 140, 480, 500), ScanFraming.upright(crop, 640, 480, 90))
        assertEquals(Box(140, 0, 500, 480), ScanFraming.upright(crop, 640, 480, 180))
        assertEquals(Box(0, 140, 480, 500), ScanFraming.upright(crop, 640, 480, 270))
        assertEquals(Box(0, 0, 480, 640), ScanFraming.upright(Box(0, 0, 640, 480), 640, 480, 90))
    }

    @Test
    fun targetIsACenteredWideBox() {
        // Tall view: 80% of the width, height 60% of that.
        assertEquals(Box(100, 560, 900, 1040), ScanFraming.target(Box(0, 0, 1000, 1600)))
        // Short, wide view: limited to 80% of the height.
        assertEquals(Box(100, 20, 900, 180), ScanFraming.target(Box(0, 0, 1000, 200)))
    }

    @Test
    fun aBarcodeInsideTheCornersCounts() {
        assertTrue(ScanFraming.inTarget(Box(200, 600, 800, 1000), Box(0, 0, 1000, 1600)))
    }

    @Test
    fun aBarcodeCrossingTheCornersDoesNot() {
        val visible = Box(0, 0, 1000, 1600)
        // Hanging over the top of the target.
        assertFalse(ScanFraming.inTarget(Box(200, 500, 800, 700), visible))
        // In view, but past the target's right side.
        assertFalse(ScanFraming.inTarget(Box(300, 600, 950, 900), visible))
    }
}
