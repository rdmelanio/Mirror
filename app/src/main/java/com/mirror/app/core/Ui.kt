package com.mirror.app.core

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
fun Context.label(value: String, size: Float = 18f) = if (com.mirror.app.phone.PhoneTheme.enabled(this)) com.mirror.app.phone.PhoneTheme.label(this, value, size) else TextView(this).apply {
    text = value; textSize = size; setTextColor(Color.WHITE); setPadding(dp(8), dp(8), dp(8), dp(8))
}
fun Context.action(value: String, click: () -> Unit): Button {
    if (com.mirror.app.phone.PhoneTheme.enabled(this)) return com.mirror.app.phone.PhoneTheme.action(this, value, click)
    return Button(this).apply {
    text = value; textSize = 17f; isAllCaps = false; isFocusable = true
    minHeight = dp(56); setTextColor(Color.WHITE)
    fun background(color: String) = GradientDrawable().apply {
        setColor(Color.parseColor(color)); cornerRadius = dp(8).toFloat()
        setStroke(dp(2), Color.parseColor("#FFE3BC"))
    }
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), background("#715633"))
        addState(intArrayOf(android.R.attr.state_pressed), background("#715633"))
        addState(intArrayOf(), background("#26303D"))
    }
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(4)) }
    setOnClickListener { click() }
    }
}
fun Activity.insetContent(view: View, padding: Int = 16) {
    view.setOnApplyWindowInsetsListener { v, insets ->
        val p = dp(if (com.mirror.app.phone.PhoneTheme.enabled(this)) 16 else padding)
        v.setPadding(p + insets.systemWindowInsetLeft, p + insets.systemWindowInsetTop,
            p + insets.systemWindowInsetRight, p + insets.systemWindowInsetBottom)
        insets
    }
    view.requestApplyInsets()
}

