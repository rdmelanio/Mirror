package com.mirror.app.phone

import android.content.Context
import com.mirror.app.core.mirrorPreferences

object PhoneSecurity {
    @Volatile private var instance: PairingAuthority? = null
    @Synchronized fun get(context: Context): PairingAuthority {
        return instance ?: run {
            val prefs = context.applicationContext.mirrorPreferences()
            PairingAuthority(prefs.getString("security", "{}").orEmpty(), { prefs.edit().putString("security", it).commit(); Unit })
                .also { instance = it }
        }
    }
}
