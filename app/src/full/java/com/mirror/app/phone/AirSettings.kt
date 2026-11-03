package com.mirror.app.phone

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONObject

internal object AirSettings {
    fun onePlus()=AirSettingPolicy.onePlus6T(Build.MANUFACTURER,Build.MODEL,Build.DEVICE)
    fun handDefault()=if(onePlus()) 2 else 4
    private fun values(p:SharedPreferences)=p.all.mapNotNull { (k,v) -> v?.let { k to it } }.toMap()
    private fun write(p:SharedPreferences,values:Map<String,Any>) {
        val e=p.edit()
        values.forEach { (k,v) -> when(v) { is String -> e.putString(k,v);is Boolean -> e.putBoolean(k,v);is Int -> e.putInt(k,v);is Long -> e.putLong(k,v);is Float -> e.putFloat(k,v) } };e.apply()
    }
    fun applyMigration(context:Context) {
        val p=AirRemoteTv.prefs(context);val migration=AirSettingPolicy.migrate(values(p),onePlus())
        if(!migration.changed) return
        val next=migration.values.toMutableMap()
        if(migration.backup.isNotEmpty()) next["migration_215_backup"]=JSONObject(migration.backup).toString()
        write(p,next)
    }
    fun restore(context:Context) { val p=AirRemoteTv.prefs(context);AirRemoteTv.discardPending();write(p,AirSettingPolicy.recommended(onePlus())) }
    fun undoUpgrade(context:Context) {
        val p=AirRemoteTv.prefs(context);val backup=JSONObject(p.getString("migration_215_backup","{}").orEmpty())
        val restored=backup.keys().asSequence().filter { it in AirSettingPolicy.advancedKeys }.associateWith { backup.get(it) }.toMutableMap()
        if(restored["bitmap_input"]==true) restored["input_mode"]="bitmap"
        write(p,restored)
    }
}
