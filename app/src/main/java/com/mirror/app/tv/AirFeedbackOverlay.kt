package com.mirror.app.tv

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.Drawable
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
import com.mirror.app.phone.AirFeedbackView
import java.lang.ref.WeakReference

/** TV policy owns deadlines. Only one noninteractive corner window; passive viewing has no window. */
internal class AirFeedbackOverlay(private val service:android.content.Context) {
    private val main=Handler(Looper.getMainLooper())
    private var view:TextView?=null
    private var remove:(()->Unit)?=null
    private var shown:AirFeedbackView.View?=null
    private val hide=Runnable { clear() }
    fun render(value:AirFeedbackView.View?) {
        if(value==null) { if(view!=null) clear();return }
        if(value==shown) return
        main.removeCallbacksAndMessages(null)
        var label=view
        if(label==null) {
            label=TextView(service).apply {
                textSize=15f;setTextColor(Color.WHITE);setPadding(service.dp(12),service.dp(8),service.dp(12),service.dp(8))
                background=GradientDrawable().apply { setColor(Color.argb(160,20,24,30));cornerRadius=service.dp(12).toFloat() }
                isFocusable=false;isClickable=false;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                compoundDrawablePadding=service.dp(8)
            }
            val current=label
            var added=false
            if(Settings.canDrawOverlays(service)) {
                val wm=service.getSystemService(WindowManager::class.java)
                val params=WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply {
                    gravity=Gravity.TOP or Gravity.END;x=service.dp(32);y=service.dp(28);title="Mirror temporary Air Remote feedback"
                }
                added=runCatching { wm.addView(current,params);remove={ runCatching { wm.removeView(current) };Unit };true }.getOrDefault(false)
                windowRejected=!added
                if(!added) AirFeedbackService.status="Overlay rejected · in-app feedback only"
            }
            if(!added) {
                val root=activity?.get()?.takeIf { !it.isFinishing && it.hasWindowFocus() }?.findViewById<FrameLayout>(android.R.id.content)
                if(root!=null) { root.addView(current,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END).apply { topMargin=service.dp(28);rightMargin=service.dp(32) });remove={root.removeView(current)};added=true }
            }
            if(!added) return
            view=current
        }
        label.text=value.text
        val tint=when(value.color) { "AMBER" -> Color.rgb(255,190,65);"GREEN" -> Color.rgb(95,225,145);else -> Color.LTGRAY }
        label.setCompoundDrawables(Hand(tint).apply { setBounds(0,0,service.dp(22),service.dp(28)) },null,null,null)
        label.animate().cancel();label.alpha=1f
        shown=value
        val delay=(value.until-android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0)
        main.postDelayed({ if(shown==value) label.animate().alpha(0f).setDuration(150).start() },(delay-150).coerceAtLeast(0))
        main.postDelayed(hide,delay)
    }
    fun clear() { main.removeCallbacksAndMessages(null);view?.animate()?.cancel();remove?.invoke();remove=null;view=null;shown=null }
    private class Hand(private val tint:Int):Drawable() {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=tint }
        override fun draw(c:Canvas) {
            c.save();c.translate(bounds.left.toFloat(),bounds.top.toFloat());c.scale(bounds.width()/24f,bounds.height()/30f)
            c.drawRoundRect(5f,12f,22f,29f,6f,6f,paint)
            for((x,y) in listOf(5f to 6f,9.5f to 1f,14f to 3f,18.5f to 7f)) c.drawRoundRect(x,y,x+3.5f,20f,2f,2f,paint)
            c.rotate(-35f,7f,22f);c.drawRoundRect(1f,12f,6f,25f,3f,3f,paint);c.restore()
        }
        override fun setAlpha(alpha:Int) { paint.alpha=alpha }
        override fun setColorFilter(filter:ColorFilter?) { paint.colorFilter=filter }
        @Suppress("DEPRECATION") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
    companion object {
        @Volatile var windowRejected=false
        private var activity:WeakReference<Activity>?=null
        fun foreground()=activity?.get()?.let { !it.isFinishing && it.hasWindowFocus() }==true
        fun attach(value:Activity) { activity=WeakReference(value) }
        fun detach(value:Activity) { if(activity?.get()===value) activity=null }
    }
}
