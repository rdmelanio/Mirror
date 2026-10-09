package com.mirror.app.phone

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.app.Application
import java.util.WeakHashMap
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.appbar.MaterialToolbar
import com.mirror.app.R
import com.mirror.app.core.dp
import com.mirror.app.core.action

/** All menu palette, typography and components live here. Clock and alarm views are excluded. */
object PhoneTheme {
    private fun preferences(c: Context) = c.getSharedPreferences("mirror_menu_theme", Context.MODE_PRIVATE)
    fun classic(c: Context) = preferences(c).getBoolean("classic", false)
    fun developer(c: Context) = preferences(c).getBoolean("developer", false)
    fun setDeveloper(c: Context, enabled: Boolean) { preferences(c).edit().putBoolean("developer", enabled).also { if (!enabled) it.putBoolean("classic", false) }.apply() }
    fun setClassic(c: Context, enabled: Boolean) { preferences(c).edit().putBoolean("classic", enabled).apply() }
    fun menuContext(c: Context): Boolean {
        var current = c
        while (current is ContextWrapper && current !is Activity && current.baseContext !== current) current = current.baseContext
        return current is Activity && current.javaClass.name.startsWith("com.mirror.app.phone.") && current.javaClass.simpleName !in setOf("ClockActivity", "RosterAlarmActivity", "RosterChangesActivity")
    }
    fun enabled(c: Context) = menuContext(c) && !classic(c)
    private val installed = WeakHashMap<Activity, Boolean>()
    private var observing = false
    fun install(activity: Activity) {
        installed[activity] = classic(activity)
        if (!observing) {
            observing = true
            activity.application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(a: Activity) { installed[a]?.let { if (it != classic(a)) a.recreate() } }
                override fun onActivityDestroyed(a: Activity) { installed.remove(a) }
                override fun onActivityCreated(a: Activity, b: Bundle?) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityPaused(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            })
        }
        activity.setTheme(if (classic(activity)) R.style.AppTheme else R.style.PhoneMenuTheme)
        if (!classic(activity)) activity.window.setBackgroundDrawable(backdrop(activity))
    }
    fun clockPreviewBackground() = Color.BLACK
    fun primary(c: Context) = if (classic(c)) Color.WHITE else c.getColor(R.color.glass_primary)
    fun secondary(c: Context) = if (classic(c)) Color.LTGRAY else c.getColor(R.color.glass_secondary)
    fun accent(c: Context) = if (classic(c)) ClockSettings().color else c.getColor(R.color.glass_accent)
    fun staleColor(c: Context): Int = if (System.currentTimeMillis() - com.mirror.app.phone.roster.RosterStore.prefs(c).getLong("lastSuccess", 0) > 24 * 3_600_000L) error(c) else caution(c)
    fun caution(c: Context) = c.getColor(R.color.glass_caution)
    fun error(c: Context) = c.getColor(R.color.glass_error)
    fun success(c: Context) = c.getColor(R.color.glass_success)
    fun divider(c: Context) = if (classic(c)) Color.DKGRAY else c.getColor(R.color.glass_divider)
    fun backdrop(c: Context) = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(c.getColor(R.color.glass_background_start), c.getColor(R.color.glass_background_end)))
    fun page(view: View) { view.background = if (classic(view.context)) android.graphics.drawable.ColorDrawable(Color.BLACK) else backdrop(view.context) }
    fun protected(view: View) = view is ClockView || view is MaterialToolbar || view is com.mirror.app.phone.roster.RosterChangeAnnunciator || view.tag == "clock-color-preview"
    fun label(c: Context, value: String, size: Float): TextView = TextView(c).apply {
        text = value; textSize = size; setTextColor(primary(c)); typeface = c.resources.getFont(R.font.inter_regular); tag = "glass-text"
        fontFeatureSettings = "tnum"; setPadding(c.dp(8), c.dp(8), c.dp(8), c.dp(8))
    }
    private fun colors(c: Context, filled: Boolean) = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
        intArrayOf(c.getColor(R.color.glass_disabled), c.getColor(if (filled) R.color.glass_pressed else R.color.glass_disabled), if (filled) accent(c) else Color.TRANSPARENT))
    fun action(c: Context, value: String, click: () -> Unit): Button = MaterialButton(c).apply button@ {
        text = value; isAllCaps = false; textSize = 16f; typeface = c.resources.getFont(R.font.inter_medium)
        minHeight = c.dp(56); minimumHeight = c.dp(56); cornerRadius = c.dp(28)
        val filled = Regex("^(save|start|open ecrew|fetch|import|launch|connect|add)\\b", RegexOption.IGNORE_CASE).containsMatchIn(value)
        setTextColor(if (filled) c.getColor(R.color.glass_on_accent) else primary(c))
        backgroundTintList = colors(c, filled); strokeWidth = if (filled) 0 else c.dp(1)
        strokeColor = ColorStateList.valueOf(c.getColor(R.color.glass_border)); rippleColor = ColorStateList.valueOf(c.getColor(R.color.glass_pressed))
        stateListAnimator = android.animation.StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_pressed), android.animation.ObjectAnimator.ofFloat(this@button, "translationZ", c.dp(2).toFloat()).apply { duration = 150 })
            addState(intArrayOf(), android.animation.ObjectAnimator.ofFloat(this@button, "translationZ", 0f).apply { duration = 150 })
        }
        fontFeatureSettings = "tnum"
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, c.dp(4), 0, c.dp(4)) }
        setOnClickListener { click() }
    }
    fun switch(c: Context, checkbox: Boolean = false): CompoundButton = if (classic(c)) (if (checkbox) CheckBox(c) else Switch(c)) else MaterialSwitch(c).apply {
        minHeight = c.dp(56); textSize = 16f; setTextColor(primary(c)); typeface = c.resources.getFont(R.font.inter_regular)
        thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent(c), secondary(c)))
        trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(c.getColor(R.color.glass_disabled), c.getColor(R.color.glass_divider)))
        fontFeatureSettings = "tnum"; tag = "glass-text"
    }
    fun card(c: Context, highlight: Boolean = false): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; setPadding(c.dp(12), c.dp(8), c.dp(12), c.dp(8))
        background = GradientDrawable().apply { setColor(c.getColor(R.color.glass_surface)); cornerRadius = c.dp(16).toFloat(); setStroke(c.dp(1), if (highlight) accent(c) else c.getColor(R.color.glass_border)) }
        elevation = c.dp(2).toFloat()
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, c.dp(6), 0, c.dp(6)) }
    }
    fun appBar(a: Activity, title: String, back: () -> Unit): View {
        if (classic(a)) return LinearLayout(a).apply {
            addView(a.action("BACK", back))
            addView(TextView(a).apply { text = title; setTextColor(Color.WHITE) })
        }
        return MaterialToolbar(a).apply {
            this.title = title; setTitleTextAppearance(a, R.style.GlassToolbarTitle); setTitleTextColor(primary(a)); navigationIcon = a.getDrawable(R.drawable.ic_back)
            navigationContentDescription = "Back"; setNavigationOnClickListener { back() }
            minimumHeight = a.dp(56); setPadding(0, 0, a.dp(8), 0)
        }
    }
    data class TopBar(val view: View, val title: (String) -> Unit)
    fun topBar(a: Activity, title: String, back: () -> Unit): TopBar {
        if (!classic(a)) {
            val toolbar = appBar(a, title, back) as MaterialToolbar
            return TopBar(toolbar) { toolbar.title = it }
        }
        val row = LinearLayout(a)
        row.addView(a.action("BACK", back), LinearLayout.LayoutParams(a.dp(90), -2))
        val heading = TextView(a).apply { text = title; setTextColor(ClockSettings().color); textSize = 20f }
        row.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        return TopBar(row) { heading.text = it }
    }
    fun row(c: Context, title: String, subtitle: String, value: String = "", click: () -> Unit): LinearLayout = LinearLayout(c).apply {
        val largeText = c.resources.configuration.fontScale > 1.25f
        orientation = if (largeText) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL; minimumHeight = c.dp(56)
        setPadding(c.dp(12), c.dp(6), c.dp(12), c.dp(6)); isClickable = true; isFocusable = true
        val words = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        words.addView(label(c, title, 16f).apply { setPadding(0, 0, 0, 0) })
        words.addView(label(c, subtitle, 13f).apply { setTextColor(secondary(c)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, 0, 0, 0) })
        addView(words, if (largeText) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f)); addView(label(c, "$value  ›", 16f).apply { setTextColor(secondary(c)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        background = RippleDrawable(ColorStateList.valueOf(c.getColor(R.color.glass_pressed)), null, GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = c.dp(12).toFloat() })
        contentDescription = listOf(title, subtitle, value).filter { it.isNotBlank() }.joinToString(", ")
        setOnClickListener { click() }
    }
    fun selectedRow(view: View, selected: Boolean) {
        view.isSelected = selected
        if (classic(view.context)) return
        val c = view.context
        val shape = GradientDrawable().apply { setColor(if (selected) c.getColor(R.color.glass_disabled) else Color.TRANSPARENT); cornerRadius = c.dp(12).toFloat() }
        view.background = RippleDrawable(ColorStateList.valueOf(c.getColor(R.color.glass_pressed)), shape, null)
    }
    fun groupSettings(content: LinearLayout) {
        if (classic(content.context) || content.tag == "glass-grouped") return
        val c = content.context; val original = (0 until content.childCount).map(content::getChildAt)
        content.removeAllViews(); var group: LinearLayout? = null
        fun next() { group = card(c); content.addView(group) }
        for (view in original) {
            if (protected(view)) { group = null; content.addView(view); continue }
            if (view is TextView && view !is Button && view !is EditText && view.textSize / c.resources.displayMetrics.scaledDensity >= 21) {
                group = null; view.text = view.text.toString().uppercase(); view.textSize = 12f; view.letterSpacing = .08f; view.tag = "glass-section"; view.setTextColor(secondary(c)); content.addView(view)
            } else {
                if (group == null) next()
                view.layoutParams = LinearLayout.LayoutParams(-1, -2)
                group!!.addView(view)
            }
        }
        content.tag = "glass-grouped"
        val transition = android.animation.LayoutTransition().apply { setDuration(150) }
        content.layoutTransition = transition
    }
    fun style(view: View) {
        if (protected(view)) return
        val c = view.context
        if (view is TextView) {
            view.typeface = c.resources.getFont(if (view is Button || view.tag == "glass-section") R.font.inter_medium else R.font.inter_regular)
            view.fontFeatureSettings = "tnum"
            if (view.tag == "glass-section") view.setTextColor(secondary(c))
            else if (view !is MaterialButton && view.tag != "glass-text") view.setTextColor(primary(c))
            if (view is EditText) { view.setHintTextColor(secondary(c)); view.minimumHeight = c.dp(56) }
            if (view is CompoundButton) { view.minimumHeight = c.dp(56); view.buttonTintList = ColorStateList.valueOf(accent(c)); view.contentDescription = view.text }
        }
        if (view is SeekBar) { view.progressTintList = ColorStateList.valueOf(accent(c)); view.thumbTintList = ColorStateList.valueOf(accent(c)) }
        if (view is ViewGroup) for (i in 0 until view.childCount) style(view.getChildAt(i))
    }
}
