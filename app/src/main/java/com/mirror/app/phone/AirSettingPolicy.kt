package com.mirror.app.phone

/** Settings policy is pure and restricted to the Air Remote preference namespace. */
internal object AirSettingPolicy {
    const val VERSION=215
    val advancedKeys=setOf("gpu","model_hands","bitmap_input","input_mode","input_size")
    fun onePlus6T(manufacturer:String,model:String,device:String)=device.equals("fajita",true) ||
        manufacturer.equals("oneplus",true) && (model.uppercase().let { it.contains("A6010") || it.contains("A6013") || it.replace(" ","")=="ONEPLUS6T" })
    fun recommended(onePlus:Boolean)=mapOf<String,Any>("vision_engine" to "pose","pose_persons" to 2,"navigation" to "joystick","hand_lock" to "auto","joystick_sensitivity" to 50,
        "input_mode" to "auto","gpu" to false,"model_hands" to if(onePlus) 2 else 4,"input_size" to 640,"legacy" to false,
        "confidence" to 65,"point_hold" to 400L,"session_timeout" to 60_000L,"app_YOUTUBE" to true,"app_NETFLIX" to true,"app_STREMIO" to true)
    data class Migration(val values:Map<String,Any>,val backup:Map<String,Any>,val changed:Boolean)
    fun migrate(existing:Map<String,Any>,onePlus:Boolean):Migration {
        if((existing["settings_migration"] as? Int ?: 0)>=VERSION) return Migration(existing,emptyMap(),false)
        val out=existing.toMutableMap()
        recommended(onePlus).forEach { (k,v) -> if(k !in out) out[k]=v }
        if(onePlus) { out["gpu"]=false;out["input_mode"]="auto" }
        else if("input_mode" !in existing && existing["bitmap_input"]==true) out["input_mode"]="bitmap"
        out["settings_migration"]=VERSION
        val backup=existing.filter { (k,v) -> k in advancedKeys && out[k]!=v || k=="bitmap_input" && v==true && onePlus }
        out["migration_notice"]=if(onePlus) "OnePlus 6T: automatic safe input and CPU selected; two hands unless you saved another choice. Your timeout, pairing, camera and Clock are preserved." else "Automatic input includes Bitmap fallback. Saved navigation, timeout, pairing and Clock are preserved."
        return Migration(out,backup,true)
    }
    fun restore(existing:Map<String,Any>,onePlus:Boolean)=existing+recommended(onePlus)
    fun preset(value:Int)=when { value<35 -> 0;value>70 -> 2;else -> 1 }
    val presetValues=listOf(25,50,85)
    const val BASIC_GUIDE="Hold open palm for one second. Extend one index finger, briefly hold still to center, then move your whole hand. Return toward that center to rearm; a deliberate hold repeats slowly. Thumb up = OK; thumb down = Back; Victory = Home."
}
