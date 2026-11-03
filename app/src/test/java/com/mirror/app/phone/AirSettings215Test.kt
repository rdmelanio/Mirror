package com.mirror.app.phone
import org.junit.Assert.*
import org.junit.Test
class AirSettings215Test {
    @Test fun supportedOnePlusVariantsAndOtherHardwareAreDistinguished() {
        for(model in listOf("ONEPLUS A6010","ONEPLUS A6013","OnePlus 6T")) assertTrue(AirSettingPolicy.onePlus6T("OnePlus",model,"unknown"))
        assertTrue(AirSettingPolicy.onePlus6T("unknown","unknown","fajita"));assertFalse(AirSettingPolicy.onePlus6T("Samsung","Fold7","q7q"))
    }
    @Test fun newInstallDefaultsAreConservativeAndHardwareSpecific() {
        for(onePlus in listOf(false,true)) {
            val p=AirSettingPolicy.migrate(emptyMap(),onePlus).values
            assertEquals("joystick",p["navigation"]);assertEquals("auto",p["hand_lock"]);assertEquals("auto",p["input_mode"])
            assertEquals(false,p["gpu"]);assertEquals(if(onePlus) 2 else 4,p["model_hands"]);assertEquals(640,p["input_size"])
            assertEquals(60_000L,p["session_timeout"]);assertEquals(65,p["confidence"]);assertEquals(400L,p["point_hold"])
        }
    }
    @Test fun upgradePreservesDeliberateChoicesAndBacksUpOnlyAlteredAdvancedSettings() {
        val before=mapOf<String,Any>("host" to "saved address","pin" to "saved certificate","navigation" to "pointing","session_timeout" to 120_000L,
            "mirror" to false,"dim_clock" to true,"gpu" to true,"bitmap_input" to true,"legacy" to true,"confidence" to 75,"point_hold" to 650L,"model_hands" to 4,"app_NETFLIX" to false)
        val m=AirSettingPolicy.migrate(before,true)
        for(key in listOf("host","pin","navigation","session_timeout","mirror","dim_clock","legacy","confidence","point_hold","model_hands","app_NETFLIX")) assertEquals(key,before[key],m.values[key])
        assertEquals(false,m.values["gpu"]);assertEquals("auto",m.values["input_mode"]);assertEquals(true,m.backup["gpu"]);assertEquals(true,m.backup["bitmap_input"])
        assertFalse(m.backup.containsKey("pin"));assertFalse(m.backup.containsKey("host"))
        assertFalse(AirSettingPolicy.migrate(m.values,true).changed)
    }
    @Test fun nonOnePlusPreservesBitmapAndGpuAndRestoreTouchesOnlyAirOptions() {
        val before=mapOf<String,Any>("gpu" to true,"bitmap_input" to true,"host" to "opaque","pin" to "opaque","dim_clock" to true,"mirror" to false,"session_timeout" to 120_000L,"enabled" to true)
        val m=AirSettingPolicy.migrate(before,false);assertEquals("bitmap",m.values["input_mode"]);assertEquals(true,m.values["gpu"])
        val restored=AirSettingPolicy.restore(m.values,true)
        for(k in listOf("host","pin","dim_clock","mirror","enabled")) assertEquals(before[k],restored[k])
        assertEquals(60_000L,restored["session_timeout"]);assertEquals("joystick",restored["navigation"])
        assertTrue(AirSettingPolicy.BASIC_GUIDE.contains("whole hand"));assertTrue(AirSettingPolicy.BASIC_GUIDE.contains("center"))
    }
    @Test fun smartLockRetainsRoiThroughShortProductionRecoveryThenWidens() {
        val lock=AirHandLock();val points=List(21) { AirPointing.Point(.5f+(it%3)*.008f,.5f+(it/3)*.005f) }
        lock.observe(points,AirHandLock.Mode.AUTO,1000)
        val initial=lock.input(1000,AirHandLock.Mode.AUTO,true);assertTrue(initial.width<1)
        lock.observe(null,AirHandLock.Mode.AUTO,1350);assertEquals(initial,lock.input(1350,AirHandLock.Mode.AUTO,true))
        lock.observe(null,AirHandLock.Mode.AUTO,1800);assertTrue(lock.input(1800,AirHandLock.Mode.AUTO,true).width>=initial.width)
        lock.observe(null,AirHandLock.Mode.AUTO,3500);assertEquals(AirRoi.FULL,lock.input(3500,AirHandLock.Mode.AUTO,true))
    }
}
