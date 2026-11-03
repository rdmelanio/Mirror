package com.mirror.app.tv

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.mirror.app.core.dp
import com.mirror.app.phone.AirFeedbackDisplay
import java.lang.ref.WeakReference

/** No window exists while idle. Every display is noninteractive, bounded and removed after fading. */
internal class AirFeedbackOverlay(private val service: android.content.Context) {
    private val main=Handler(Looper.getMainLooper())
    private var view: TextView?=null
    private var remove: (() -> Unit)?=null
    private val hide=Runnable { clear() }
    fun show(event: String) {
        val text=AirFeedbackDisplay.caption(event) ?: return
        clear()
        val label=TextView(service).apply {
            this.text=text;textSize=15f;setTextColor(Color.WHITE);setPadding(service.dp(14),service.dp(8),service.dp(14),service.dp(8))
            background=GradientDrawable().apply { setColor(Color.argb(175,20,24,30));cornerRadius=service.dp(12).toFloat() }
            isFocusable=false;isClickable=false;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        var shown=false
        if(Settings.canDrawOverlays(service)) {
            val wm=service.getSystemService(WindowManager::class.java)
            val params=WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply {
                gravity=Gravity.TOP or Gravity.END;x=service.dp(32);y=service.dp(28)
                title="Mirror transient Air Remote feedback"
            }
            shown=runCatching { wm.addView(label,params);remove={runCatching { wm.removeView(label) };Unit};true }.getOrDefault(false)
            windowRejected=!shown
            if(!shown) AirFeedbackService.status="Overlay rejected · in-app feedback only"
        }
        if(!shown) {
            val host=activity?.get()?.takeIf { !it.isFinishing && it.hasWindowFocus() }
            val root=host?.findViewById<FrameLayout>(android.R.id.content)
            if(root!=null) { root.addView(label,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END).apply { topMargin=service.dp(28);rightMargin=service.dp(32) });remove={root.removeView(label)};shown=true }
        }
        if(!shown) return
        view=label
        main.postDelayed({ if(view===label) { label.animate().alpha(0f).setDuration(150).start() } },AirFeedbackDisplay.duration(event)-150)
        main.postDelayed(hide,AirFeedbackDisplay.duration(event))
    }
    fun clear() { main.removeCallbacksAndMessages(null);view?.animate()?.cancel();remove?.invoke();remove=null;view=null }
    companion object {
        @Volatile var windowRejected=false
        private var activity: WeakReference<Activity>?=null
        fun attach(value: Activity) { activity=WeakReference(value) }
        fun detach(value: Activity) { if(activity?.get()===value) activity=null }
    }
}
