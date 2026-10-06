package com.mirror.app.phone

import org.junit.Assert.*
import org.junit.Test

class EcamLayoutTest {
    @Test fun alertAndStopRegionsStaySeparateInPortraitLandscapeAndPreview() {
        for ((width, height) in listOf(1280f to 576f, 1080f to 2340f, 780f to 360f, 360f to 780f, 280f to 280f)) {
            val z = EcamLayout.regions(width, height, 2f)
            val areas = listOf(z.header, z.duty, z.action, z.stop, z.footer)
            val motion = minOf(8f, z.inset/3)*2
            areas.forEach {
                assertTrue(it.width > 0 && it.height > 0)
                assertTrue(it.left-motion >= 0 && it.top-motion >= 0)
                assertTrue(it.right+motion <= width && it.bottom+motion <= height)
            }
            assertTrue(z.header.bottom < z.duty.top)
            assertTrue(z.duty.bottom < z.stop.top)
            assertTrue(z.action.bottom < z.stop.top)
            assertTrue(z.stop.bottom < z.footer.top)
            if (width > height*1.2f) assertTrue(z.duty.right < z.action.left)
            else assertTrue(z.duty.bottom < z.action.top)
        }
    }
}
