package com.mirror.app.phone

import org.junit.Assert.*
import org.junit.Test

class ClockTest {
    @Test fun settingsRoundTripAndDefaults() {
        val defaults = ClockSettings.parse(null)
        assertTrue(defaults.date && defaults.alarm && defaults.status && defaults.pixelShift && defaults.reposition)
        assertFalse(defaults.weather || defaults.seconds || defaults.drift)
        val custom = defaults.copy(style = "Stacked", primaryUtc = true, showUtc = false, sizePercent = 75, hourFormat = "12-hour", minimalFont = "B612",
            schedule = true, calendarId = 12, calendarName = "Roster", scheduleColor = 0xFFFF00FF.toInt(),
            dateColor = 0xFF00FFFF.toInt(), alarmColor = 0xFFFFFFFF.toInt(), weatherColor = 0xFFFF0000.toInt(),
            color = 0xFFFFB000.toInt(), secondColor = 0xFFFF40FF.toInt(), gradient = true,
            weather = true, city = "Manila", latitude = 14.6, longitude = 120.98, date = false,
            alarm = false, status = false, seconds = true, pixelShift = false, drift = true, reposition = false,
            autoBrightness = false, maxBrightness = 25, autoNight = false, away = false, awayHours = 12, dimLive = false)
        assertEquals(custom, ClockSettings.parse(custom.json()))
        assertEquals(defaults, ClockSettings.parse("broken json"))
        val invalid = ClockSettings.parse("{\"maxBrightness\":100,\"awayHours\":1,\"style\":\"Unknown\"}")
        assertEquals(60, invalid.maxBrightness); assertEquals(8, invalid.awayHours); assertEquals("Cockpit", invalid.style)
    }
    @Test fun oldPreferencesMigrateAndUtcTogglePreservesPrimaryChoice() {
        val old = ClockSettings.parse("{\"schema\":1,\"primaryUtc\":true,\"color\":-65536}")
        assertTrue(old.showUtc); assertTrue(old.usesUtc); assertEquals(90, old.sizePercent)
        val hidden = ClockSettings.parse(old.copy(showUtc = false).json())
        assertFalse(hidden.usesUtc); assertTrue(hidden.primaryUtc)
        assertTrue(hidden.copy(showUtc = true).usesUtc)
        assertEquals(40, ClockSettings.parse("{\"sizePercent\":-10}").sizePercent)
        assertEquals(100, ClockSettings.parse("{\"sizePercent\":999}").sizePercent)
    }
    @Test fun zoomFitsPortraitLandscapeAndLongInfoLinesWithShiftRoom() {
        for ((width, height) in listOf(360f to 780f, 780f to 360f, 280f to 280f)) {
            for ((blockWidth, blockHeight) in listOf(700f to 350f, 190f to 700f, 1100f to 300f)) {
                var previous = 0f
                for (percent in 40..100) {
                    val scale = ClockLayout.scale(width, height, 28f, blockWidth, blockHeight, percent)
                    assertTrue(scale > previous)
                    assertTrue(blockWidth * scale <= width - 56f + 0.001f)
                    assertTrue(blockHeight * scale <= height - 56f + 0.001f)
                    previous = scale
                }
            }
        }
        assertEquals(0f, ClockLayout.scale(0f, 0f, 28f, 300f, 300f, 90), 0f)
    }
    @Test fun nightRequiresContinuousLowAndHighWithHysteresis() {
        val p = ClockLightPolicy()
        p.sample(2f, 0); p.advance(59_999, false, Long.MIN_VALUE); assertFalse(p.night)
        p.advance(60_000, false, Long.MIN_VALUE); assertTrue(p.night)
        p.sample(10f, 61_000); p.advance(200_000, false, Long.MIN_VALUE); assertTrue(p.night)
        p.sample(20f, 200_000); p.advance(259_999, false, Long.MIN_VALUE); assertTrue(p.night)
        p.advance(260_000, false, Long.MIN_VALUE); assertFalse(p.night)
        p.sample(1f, 300_000); p.sample(6f, 350_000); p.sample(1f, 360_000)
        p.advance(410_000, false, Long.MIN_VALUE); assertFalse(p.night)
    }
    @Test fun awayNeedsDarknessAndNoViewingAndResumesImmediately() {
        val p = ClockLightPolicy(); val s = ClockSettings(awayHours = 4); val hours = 4 * 3_600_000L
        p.sample(0f, 0); p.advance(hours, false, Long.MIN_VALUE); assertTrue(p.blank(s, hours))
        p.advance(hours, true, hours); assertFalse(p.blank(s, hours))
        assertFalse(p.blank(s, hours * 2 - 1)); assertTrue(p.blank(s, hours * 2))
        p.sample(6f, hours * 2); assertFalse(p.blank(s, hours * 2))
        p.sample(0f, hours * 2 + 1); assertFalse(p.blank(s, hours * 2 + 2))
    }
    @Test fun brightnessIsBoundedAndManualFallbackAndLiveDimmingWork() {
        val p = ClockLightPolicy(); val s = ClockSettings(maxBrightness = 40)
        assertEquals(0.4f, p.brightness(s, false, 0), 0.001f)
        assertEquals(0.2f, p.brightness(s, true, 0), 0.001f)
        p.sample(10_000f, 0); assertEquals(0.4f, p.brightness(s, false, 0), 0.001f)
        p.sample(0f, 1); p.advance(60_001, false, Long.MIN_VALUE)
        assertEquals(0.01f, p.brightness(s, false, 60_001), 0.001f)
        assertTrue(p.brightness(s.copy(autoNight = false), false, 60_001) > 0.01f)
    }
    @Test fun deadlinesWorkWithoutRepeatedSensorEvents() {
        val p = ClockLightPolicy(); p.sample(0f, 0)
        assertEquals(60_000L, p.nextDeadline(0, ClockSettings()))
        p.advance(60_000, false, Long.MIN_VALUE)
        assertEquals(8 * 3_600_000L, p.nextDeadline(60_000, ClockSettings()))
    }
    @Test fun wordClockCoversFiveMinuteStepsAndHourRollover() {
        assertEquals("IT IS TWELVE O'CLOCK", ClockWords.time(0, 0))
        assertEquals("IT IS TWENTY FIVE PAST TEN", ClockWords.time(10, 29))
        assertEquals("IT IS HALF PAST TEN", ClockWords.time(10, 30))
        assertEquals("IT IS TWENTY FIVE TO ELEVEN", ClockWords.time(10, 35))
        assertEquals("IT IS FIVE TO TWELVE", ClockWords.time(23, 59))
    }
}
