package com.mirror.app.phone

/** Classification threshold; independent of MediaPipe's internal detector/presence thresholds. */
internal object AirConfidence {
    fun percent(value:Int) = ((value.coerceIn(50,95)+2)/5)*5
    fun fraction(value:Int) = percent(value)/100f
}
