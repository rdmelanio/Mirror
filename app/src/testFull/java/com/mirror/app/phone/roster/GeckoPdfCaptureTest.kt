package com.mirror.app.phone.roster

import org.junit.Assert.*
import org.junit.Test
import org.mozilla.geckoview.WebResponse
import java.io.ByteArrayInputStream

class GeckoPdfCaptureTest {
    @Test fun externalResponseUsesBodyMagicRatherThanMimeType() {
        val bytes = "%PDF-synthetic-response".toByteArray(); var closed = false
        val body = object : ByteArrayInputStream(bytes) { override fun close() { closed = true; super.close() } }
        val response = WebResponse.Builder("https://ecrew.cebupacificair.com/eCrew/report")
            .body(body).header("content-type", "application/octet-stream").statusCode(200).build()
        assertArrayEquals(bytes, GeckoPdfCapture.read(response)); assertTrue(closed)
        val html = WebResponse.Builder("https://ecrew.cebupacificair.com/eCrew/report")
            .body(ByteArrayInputStream("<html>session expired</html>".toByteArray())).header("content-type", "application/pdf").build()
        assertNull(GeckoPdfCapture.read(html))
    }
}
