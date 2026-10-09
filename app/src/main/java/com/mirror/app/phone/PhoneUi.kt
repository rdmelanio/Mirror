package com.mirror.app.phone

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.mirror.app.R
import com.mirror.app.core.dp

/** Phone-only presentation. TV widgets and camera processing are untouched. */
object PhoneUi {
    val GREEN = ClockSettings().color
    val CYAN = ClockSettings().secondColor
    fun style(view: View) {
        if (PhoneTheme.enabled(view.context)) { PhoneTheme.style(view); return }
        if (PhoneTheme.protected(view)) return
        val c = view.context
        if (view is TextView) {
            view.typeface = c.resources.getFont(R.font.b612_regular)
            when (view) {
                is CompoundButton -> { view.setTextColor(Color.WHITE); view.buttonTintList = android.content.res.ColorStateList.valueOf(GREEN) }
                is Button -> { view.setTextColor(GREEN); view.background = button(c); view.isAllCaps = false }
                is EditText -> { view.setTextColor(Color.WHITE); view.setHintTextColor(Color.GRAY) }
                else -> view.setTextColor(Color.WHITE)
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) style(view.getChildAt(i))
    }
    fun button(c: Context): StateListDrawable {
        fun face(color: Int, fill: Int) = GradientDrawable().apply { setColor(fill); setStroke(c.dp(1), color); cornerRadius = c.dp(3).toFloat() }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), face(CYAN, 0xFF09221A.toInt()))
            addState(intArrayOf(android.R.attr.state_pressed), face(CYAN, 0xFF123025.toInt()))
            addState(intArrayOf(android.R.attr.state_focused), face(CYAN, 0xFF123025.toInt()))
            addState(intArrayOf(), face(0xFF305540.toInt(), Color.BLACK))
        }
    }
}

