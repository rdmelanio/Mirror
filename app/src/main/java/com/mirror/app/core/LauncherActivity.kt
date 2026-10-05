package com.mirror.app.core

import android.app.Activity
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.widget.LinearLayout
import com.mirror.app.phone.PhoneActivity
import com.mirror.app.tv.TvActivity

class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = mirrorPreferences().getString("role", null)
        if (saved != null) { launch(saved); return }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
        }
        content.addView(label("Mirror", 36f))
        content.addView(label("Choose how to use this device"))
        val tv = action("TV - show the mirror") { choose("tv") }
        val phone = action("Phone - be the camera") { choose("phone") }
        content.addView(tv); content.addView(phone)
        setContentView(content); insetContent(content, 32)
        val television = getSystemService(UiModeManager::class.java).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        (if (television) tv else phone).requestFocus()
    }
    private fun choose(role: String) { mirrorPreferences().edit().putString("role", role).apply(); launch(role) }
    private fun launch(role: String) {
        startActivity(Intent(this, if (role == "tv") TvActivity::class.java else PhoneActivity::class.java))
        finish()
    }
    companion object {
        fun changeRole(activity: Activity) {
            activity.mirrorPreferences().edit().remove("role").apply()
            activity.startActivity(Intent(activity, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            activity.finish()
        }
    }
}
