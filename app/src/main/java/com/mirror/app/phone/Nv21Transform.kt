package com.mirror.app.phone

/** Pure geometry/YUV code: crop before rotating, no Bitmap or extra JPEG pass. */
internal object Nv21Transform {
    data class Crop(val left: Int, val top: Int, val width: Int, val height: Int)
    fun crop(width: Int, height: Int, rotation: Int, orientation: String): Crop {
        val sideways = rotation % 180 != 0
        val uprightWidth = if (sideways) height else width
        val uprightHeight = if (sideways) width else height
        val landscape = when (orientation) {
            "portrait" -> false
            "auto" -> uprightWidth >= uprightHeight
            else -> true
        }
        // Exact 16:9 / 9:16 with even luma and chroma boundaries.
        val unitW = if (landscape) 32 else 18
        val unitH = if (landscape) 18 else 32
        val factor = minOf(uprightWidth / unitW, uprightHeight / unitH)
        require(factor > 0)
        val w = factor * if (sideways) unitH else unitW
        val h = factor * if (sideways) unitW else unitH
        return Crop(((width - w) / 2) and -2, ((height - h) / 2) and -2, w, h)
    }
    fun rotate(source: ByteArray, output: ByteArray, width: Int, height: Int, degrees: Int) {
        require(degrees in listOf(90, 180, 270))
        val ySize = width * height
        if (degrees == 180) {
            for (i in 0 until ySize) output[ySize - 1 - i] = source[i]
            var dst = source.size - 2
            var src = ySize
            while (src < source.size) {
                output[dst] = source[src]; output[dst + 1] = source[src + 1]
                src += 2; dst -= 2
            }
            return
        }
        // Traverse source rows contiguously. Address increments avoid division per pixel.
        for (y in 0 until height) {
            var dst = if (degrees == 90) height - 1 - y else (width - 1) * height + y
            val step = if (degrees == 90) height else -height
            var src = y * width
            repeat(width) { output[dst] = source[src++]; dst += step }
        }
        val chromaHeight = height / 2
        for (y in 0 until chromaHeight) {
            var dst = ySize + if (degrees == 90) (chromaHeight - 1 - y) * 2 else (width / 2 - 1) * height + y * 2
            val step = if (degrees == 90) height else -height
            var src = ySize + y * width
            repeat(width / 2) {
                output[dst] = source[src++]; output[dst + 1] = source[src++]; dst += step
            }
        }
    }
}
