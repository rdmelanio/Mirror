package com.mirror.app.phone
internal object AirTransportSchedule {
    fun delay(connected:Boolean, socket:Boolean, pending:Boolean):Long = when {
        pending && connected -> 0
        connected -> 80
        socket -> 20
        else -> 500
    }
}
