package com.mirror.app.phone.roster

import org.junit.Assert.*
import org.junit.Test

class RosterLegendTest {
    @Test fun exactPrintedColumnsAndCollapsedColumns() {
        val expected = mapOf("OFF" to "Day off", "M" to "Day Memo", "AS" to "AIRPORT STANDBY", "RVL" to "Requested Vacation Leave")
        assertEquals(expected, RosterLegend.parse("OFF - Day off                M - Day Memo\nAS - AIRPORT STANDBY    RVL - Requested Vacation Leave"))
        assertEquals(expected, RosterLegend.parse("OFF - Day off M - Day Memo\nAS - AIRPORT STANDBY RVL - Requested Vacation Leave"))
    }
}
