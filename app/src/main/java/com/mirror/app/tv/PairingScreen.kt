package com.mirror.app.tv

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import com.mirror.app.core.action
import com.mirror.app.core.dp
import com.mirror.app.core.label

/** Explicit focusable buttons work without an IME on remote-only TVs. */
class PairingScreen(context: Context, name: String, private val submit: (String) -> Unit,
                    cancel: () -> Unit) : ScrollView(context) {
    private var digits = ""
    private val prompt = context.label("Enter the 6-digit code shown on your phone (Mirror > Pair new TV)", 20f)
    private val code = context.label("_ _ _ _ _ _", 28f)
    private val buttons = mutableListOf<Button>()
    private var busy = false
    init {
        isFillViewport = true; setBackgroundColor(Color.rgb(18, 23, 31))
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        addView(content)
        setPadding(context.dp(24), context.dp(8), context.dp(24), context.dp(8))
        content.addView(context.label("Pair with $name", 24f)); content.addView(prompt); content.addView(code)
        var first: Button? = null
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("Delete", "0", "OK")).forEach { row ->
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { text ->
                val button = context.action(text) {
                    if (!busy) {
                        when (text) {
                            "Delete" -> digits = digits.dropLast(1)
                            "OK" -> if (digits.length == 6) { setBusy(true); submit(digits) } else error("Enter all 6 digits")
                            else -> if (digits.length < 6) digits += text
                        }
                        code.text = digits.padEnd(6, '_').toList().joinToString(" ")
                    }
                }
                line.addView(button, LinearLayout.LayoutParams(context.dp(150), context.dp(52)).apply { setMargins(context.dp(4), context.dp(2), context.dp(4), context.dp(2)) })
                buttons.add(button); if (first == null) first = button
            }
            content.addView(line)
        }
        val back = context.action("Back") { cancel() }
        content.addView(back, LinearLayout.LayoutParams(context.dp(300), context.dp(52)))
        post { first?.requestFocus() }
    }
    fun setBusy(value: Boolean) { busy = value; buttons.forEach { it.isEnabled = !value }; if (value) prompt.text = "Pairing..." }
    fun error(value: String) { setBusy(false); prompt.text = value; buttons.last().requestFocus() }
}
