package ng.ainow.jarvis.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ng.ainow.jarvis.R

/** Shared layout and native controls for the home and settings screens. */
internal class JarvisScreen(private val activity: Activity) {
    val ink = activity.getColor(R.color.jarvis_text)
    val muted = activity.getColor(R.color.jarvis_secondary)
    val accent = activity.getColor(R.color.jarvis_accent)
    private val panel = activity.getColor(R.color.jarvis_panel)
    private val canvasColor = activity.getColor(R.color.jarvis_icon_background)

    fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    fun page(paneTitle: CharSequence): LinearLayout {
        activity.title = paneTitle
        val body = column().apply {
            setPadding(dp(24), dp(16), dp(24), dp(24))
            if (Build.VERSION.SDK_INT >= 28) accessibilityPaneTitle = paneTitle
        }
        activity.setContentView(ScrollView(activity).apply {
            isFillViewport = true
            setBackgroundColor(canvasColor)
            addView(body, MATCH_PARENT, WRAP_CONTENT)
        })
        return body
    }

    fun text(value: CharSequence, size: Float = 16f, color: Int = muted) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(color)
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    fun heading(value: CharSequence, size: Float = 30f) = text(value, size, ink).apply {
        setTypeface(typeface, Typeface.BOLD)
        if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    private fun shape(color: Int, focus: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(16).toFloat()
        if (focus) setStroke(dp(3), if (color == accent) canvasColor else accent)
    }

    fun button(value: CharSequence, primary: Boolean = false, onClick: () -> Unit) = Button(activity).apply {
        text = value
        textSize = 16f
        isAllCaps = false
        minHeight = dp(56)
        minimumHeight = dp(56)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setTextColor(if (primary) canvasColor else ink)
        val fill = if (primary) accent else panel
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(fill, focus = true))
            addState(intArrayOf(), shape(fill))
        }
        backgroundTintList = null
        background = RippleDrawable(ColorStateList.valueOf(0x335A8E9F), states, null)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    fun add(body: LinearLayout, view: View, top: Int = 0) {
        body.addView(view, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(top)
        })
    }
}
