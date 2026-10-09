package com.mirror.app.phone

import org.junit.Assert.*
import org.junit.Test

class ClockEditingTest {
    @Test fun pinchAndDragPersistIndependentlyAcrossScreensAndOrientations() {
        val folded = ClockLayout.screenKey(360, 780)
        val unfolded = ClockLayout.screenKey(720, 780)
        val landscape = ClockLayout.screenKey(780, 360)
        val edited = ClockSettings().resized(folded, 17).moved(folded, "clock", ClockPosition(.2f, .3f))
            .resized(unfolded, 96).moved(unfolded, "clock", ClockPosition(.7f, .6f))
        val saved = ClockSettings.parse(edited.json())
        assertEquals(17, saved.layoutFor(folded).sizePercent)
        assertEquals(96, saved.layoutFor(unfolded).sizePercent)
        assertEquals(90, saved.layoutFor(landscape).sizePercent)
        assertEquals(ClockPosition(.2f, .3f), saved.layoutFor(folded).positions["clock"])
        assertEquals(ClockPosition(.7f, .6f), saved.layoutFor(unfolded).positions["clock"])
        assertFalse(saved.layoutFor(landscape).positions.containsKey("clock"))
        assertEquals(1, saved.resized(folded, -10).layoutFor(folded).sizePercent)
        assertEquals(100, saved.resized(folded, 200).layoutFor(folded).sizePercent)
    }
    @Test fun clockFitsOnlyTheSafeScreenAndItemsCanOccupyTheSamePlace() {
        val width = 1280f; val height = 576f; val density = 2f
        val scale = ClockLayout.freeScale(width, height, density, 400f, 300f, 100, true)
        val zones = ClockLayout.zones(width, height, density)
        assertTrue(scale > ClockLayout.scale(zones.clock.width, zones.clock.height, 0f, 400f, 300f, 100))
        val edge = ClockLayout.safeInset(width, height, density)
        assertTrue(400 * scale <= width - 2 * edge + .001f)
        assertTrue(300 * scale <= height - 2 * edge + .001f)
        val position = ClockPosition(.5f, .5f)
        val clock = ClockLayout.place(width, height, edge, 400 * scale, 300 * scale, position)
        val info = ClockLayout.place(width, height, edge, 200f, 60f, position)
        assertEquals(clock.centerX, info.centerX, .001f)
        assertEquals(clock.centerY, info.centerY, .001f)
        assertTrue(info.left < clock.right && info.right > clock.left)
        assertEquals(scale, ClockLayout.freeScale(width, height, density, 400f, 300f, 100, true), 0f)
    }
    @Test fun tapBringsElementsToFrontAndAnnunciatorsAlwaysStayAboveThem() {
        val screen = ClockLayout.screenKey(360, 780)
        var s = ClockSettings().front(screen, "weather").front(screen, "clock")
        s = ClockSettings.parse(s.json())
        val order = ClockLayout.ordered(s.layoutFor(screen).zOrder)
        assertEquals("clock", order[order.lastIndex - 1])
        assertEquals("roster_changed", order.last())
        assertTrue(order.indexOf("weather") < order.indexOf("clock"))
        assertEquals(ClockLayout.items.size, order.distinct().size)
        assertEquals("roster_changed", ClockLayout.ordered(listOf("roster_changed", "label_loc", "clock")).last())
    }
    @Test fun aboveAndHiddenLabelsFreeWidthForLargerDigitsAtTheSamePinchScale() {
        fun scale(mode: String): Float {
            val width = ClockLayout.rowWidth(400f, 75f, 100f, mode)
            val height = ClockLayout.rowHeight(100f, 1.18f, true, mode)
            return ClockLayout.freeScale(400f, 1400f, 1f, width, height, 75, true)
        }
        assertTrue(scale("Above") > scale("Beside"))
        assertTrue(scale("Hidden") > scale("Beside"))
        for (mode in listOf("Beside", "Above", "Hidden")) assertEquals(mode, ClockSettings.parse(ClockSettings(timeLabel = mode).json()).timeLabel)
        assertEquals("Beside", ClockSettings.parse("{\"schema\":7}").timeLabel)
    }
    @Test fun resetClearsProfilesLayersAndLabelPlacementAndKeepsColorsAndStyles() {
        val screen = ClockLayout.screenKey(360, 780)
        val custom = ClockSettings(style = "Stacked", timeLabel = "Hidden", color = -65536, autoNight = false)
            .resized(screen, 99).moved(screen, "clock", ClockPosition(.3f, .2f)).front(screen, "clock")
        val reset = ClockSettings.parse(custom.resetLayout().json())
        assertTrue(reset.screenLayouts.isEmpty()); assertTrue(reset.positions.isEmpty())
        assertEquals(90, reset.layoutFor(screen).sizePercent); assertEquals("Beside", reset.timeLabel)
        assertEquals(ClockLayout.items, ClockLayout.ordered(reset.layoutFor(screen).zOrder))
        assertEquals(custom.style, reset.style); assertEquals(custom.color, reset.color); assertFalse(reset.autoNight)
    }
}
