package com.mirror.app.phone

import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import com.mirror.app.core.label
internal class AirRemotePanel(activity: ComponentActivity, content: LinearLayout) {
    init { content.addView(activity.label("Install mirror-phone.apk on an arm64 phone for Air Remote.")) }
    fun start() {}
    fun stop() {}
}
