package com.mirror.app.phone

import org.junit.Assert.*
import org.junit.Test

class ClockLayoutZonesTest {
    @Test fun centerAndCornersFitBothOrientationsAndPreview() {
        for ((width, dimensions) in listOf(1280f to (576f to 2f), 1080f to (2340f to 3f),
                780f to (360f to 1f), 360f to (780f to 1f), 280f to (280f to 1f))) {
            val height = dimensions.first
            val z = ClockLayout.zones(width, height, density = dimensions.second)
            val areas = listOf(z.clock, z.date, z.weather, z.today, z.tomorrow, z.footer)
            areas.forEach { a ->
                assertTrue(a.width > 0f && a.height > 0f)
                assertTrue(a.left - z.motionLimit >= 0f)
                assertTrue(a.top - z.motionLimit >= 0f)
                assertTrue(a.right + z.motionLimit <= width + 0.001f)
                assertTrue(a.bottom + z.motionLimit <= height + 0.001f)
            }
            assertEquals(width / 2, z.clock.centerX, 0.001f)
            assertEquals(height / 2, z.clock.centerY, 0.001f)
            assertTrue(z.date.right < z.weather.left)
            assertTrue(z.date.bottom < z.clock.top)
            assertTrue(z.weather.bottom < z.clock.top)
            assertTrue(z.clock.bottom < z.today.top)
            assertTrue(z.clock.bottom < z.tomorrow.top)
            assertTrue(z.today.right < z.tomorrow.left)
            assertTrue(z.today.bottom < z.footer.top)
        }
    }
    @Test fun draggedItemsStayVisibleWithBurnInClearance() {
        for (position in listOf(ClockPosition(0f, 0f), ClockPosition(1f, 1f), ClockPosition(0.5f, 0.5f))) {
            val a = ClockLayout.place(1280f, 576f, 70f, 400f, 80f, position)
            assertTrue(a.left >= 70f && a.top >= 70f)
            assertTrue(a.right <= 1210f && a.bottom <= 506f)
            assertEquals(400f, a.width, 0.001f); assertEquals(80f, a.height, 0.001f)
        }
    }
    @Test fun clockZoomIsIndependentOfScheduleLengthAndCornerToggles() {
        val z = ClockLayout.zones(1280f, 576f, 2f)
        val original = ClockLayout.scale(z.clock.width, z.clock.height, 0f, 540f, 118f, 90)
        // Each corner fits independently, including long routes and several duties.
        for (infoWidth in listOf(100f, 700f, 1800f)) {
            for (infoHeight in listOf(30f, 100f, 400f)) {
                val corner = ClockLayout.scale(z.tomorrow.width, z.tomorrow.height, 0f, infoWidth, infoHeight, 100)
                assertTrue(corner > 0f)
                assertEquals(original, ClockLayout.scale(z.clock.width, z.clock.height, 0f, 540f, 118f, 90), 0f)
            }
        }
        val maximum = ClockLayout.scale(z.clock.width, z.clock.height, 0f, 540f, 118f, 100)
        assertTrue(maximum > original)
        assertTrue(540 * maximum <= z.clock.width + 0.001f)
        assertTrue(118 * maximum <= z.clock.height + 0.001f)
    }
}
