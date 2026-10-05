package com.mirror.app.phone

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Nv21TransformTest {
    @Test fun rotationKeepsLumaAndVuPairsUpright() {
        val input = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 11, 12, 21, 22)
        val output = ByteArray(input.size)
        Nv21Transform.rotate(input, output, 4, 2, 90)
        assertArrayEquals(byteArrayOf(5, 1, 6, 2, 7, 3, 8, 4, 11, 12, 21, 22), output)
        Nv21Transform.rotate(input, output, 4, 2, 180)
        assertArrayEquals(byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1, 21, 22, 11, 12), output)
        Nv21Transform.rotate(input, output, 4, 2, 270)
        assertArrayEquals(byteArrayOf(4, 8, 3, 7, 2, 6, 1, 5, 21, 22, 11, 12), output)
    }
    @Test fun fourQuarterTurnsRestoreAllPixelsIncludingChroma() {
        val original = ByteArray(8 * 6 * 3 / 2) { it.toByte() }
        var data = original; var w = 8; var h = 6
        repeat(4) {
            val rotated = ByteArray(data.size)
            Nv21Transform.rotate(data, rotated, w, h, 90)
            data = rotated; val old = w; w = h; h = old
        }
        assertArrayEquals(original, data)
    }
    @Test fun everyMountAndModeHasExactAspectRatioAndChromaAlignedCrop() {
        for (rotation in listOf(0, 90, 180, 270)) for (mode in listOf("landscape", "portrait", "auto")) {
            val crop = Nv21Transform.crop(1280, 720, rotation, mode)
            val w = if (rotation % 180 != 0) crop.height else crop.width
            val h = if (rotation % 180 != 0) crop.width else crop.height
            val landscape = mode == "landscape" || (mode == "auto" && rotation % 180 == 0)
            assertEquals(if (landscape) 16 * h else 9 * h, if (landscape) 9 * w else 16 * w)
            assertTrue(crop.left >= 0 && crop.top >= 0 && crop.left + crop.width <= 1280 && crop.top + crop.height <= 720)
            assertEquals(0, (crop.left or crop.top or crop.width or crop.height) and 1)
        }
        assertEquals(Nv21Transform.Crop(0, 0, 1280, 720), Nv21Transform.crop(1280, 720, 0, "landscape"))
        assertEquals(Nv21Transform.Crop(442, 8, 396, 704), Nv21Transform.crop(1280, 720, 90, "landscape"))
    }
}
