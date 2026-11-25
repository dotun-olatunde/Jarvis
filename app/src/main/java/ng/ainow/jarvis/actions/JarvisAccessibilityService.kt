package ng.ainow.jarvis.actions

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import ng.ainow.jarvis.ui.SetupNotice

/** One thing on screen you can interact with. */
class Item(
    val label: String,
    val rect: Rect,
    val node: AccessibilityNodeInfo,
    val clickable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    val weak: Boolean,       // label guessed from the developer's view id
    val unlabeled: Boolean   // no usable name: candidate for OCR / icon naming
)

class Screen(val controls: List<Item>, val texts: List<String>, val hasPassword: Boolean, val pkg: String)

/**
 * The hands and eyes of Jarvis. Runs next to Jieshuo/TalkBack: it never speaks and never
 * steals focus, it only reads the screen tree and performs actions when asked.
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var inst: JarvisAccessibilityService? = null
    }

    override fun onServiceConnected() {
        inst = this
        SetupNotice.cancel(this) // the "turn on accessibility" message is no longer needed
    }

    override fun onUnbind(intent: Intent?): Boolean {
        inst = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        inst = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------- reading the screen ----------

    fun scan(): Screen {
        val root = rootInActiveWindow ?: return Screen(emptyList(), emptyList(), false, "")
        val controls = ArrayList<Item>()
        val texts = ArrayList<String>()
        var password = false

        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40) return
            if (!n.isVisibleToUser) return
            val cls = n.className?.toString() ?: ""
            val edit = n.isEditable || cls.endsWith("EditText")
            if (n.isPassword) password = true
            val text = n.text?.toString()?.trim() ?: ""
            val desc = n.contentDescription?.toString()?.trim() ?: ""
            val idName = n.viewIdResourceName?.substringAfterLast('/')?.replace('_', ' ')?.replace('-', ' ') ?: ""

            if (!edit && !n.isPassword) {
                val t = if (text.isNotEmpty()) text else desc
                if (t.isNotEmpty() && (texts.isEmpty() || texts.last() != t)) texts.add(t)
            }

            val actionable = n.isClickable || n.isCheckable || edit
            if (actionable) {
                var label = if (edit) {
                    n.hintText?.toString()?.trim().orEmpty().ifEmpty {
                        desc.ifEmpty { n.labeledBy?.text?.toString()?.trim().orEmpty() }
                    }
                } else {
                    text.ifEmpty { desc }
                }
                if (label.isEmpty() && !edit) label = descendantText(n)
                var weak = false
                if (label.isEmpty() && idName.isNotEmpty()) {
                    label = idName
                    weak = true
                }
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    controls.add(
                        Item(label, r, n, n.isClickable, edit, n.isEnabled, weak, label.isEmpty() || weak)
                    )
                }
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c, depth + 1)
            }
        }

        walk(root, 0)
        return Screen(controls, texts, password, root.packageName?.toString() ?: "")
    }

    private fun descendantText(n: AccessibilityNodeInfo): String {
        val found = ArrayList<String>()
        fun dig(x: AccessibilityNodeInfo, depth: Int) {
            if (depth > 4 || found.size >= 3) return
            for (i in 0 until x.childCount) {
                val c = x.getChild(i) ?: continue
                val t = c.text?.toString()?.trim().orEmpty().ifEmpty { c.contentDescription?.toString()?.trim().orEmpty() }
                if (t.isNotEmpty()) found.add(t)
                dig(c, depth + 1)
            }
        }
        dig(n, 0)
        return found.joinToString(" ")
    }

    fun focusedInput(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    // ---------- doing things ----------

    /** Clicks the item. Tries the normal accessibility click, then a real touch at its centre. */
    fun click(item: Item, done: (Boolean) -> Unit) {
        var n: AccessibilityNodeInfo? = item.node
        var hops = 0
        while (n != null && hops < 5) {
            if (n.isClickable && n.isEnabled && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                done(true)
                return
            }
            n = n.parent
            hops++
        }
        tapAt(item.rect.exactCenterX(), item.rect.exactCenterY(), done)
    }

    fun tapAt(x: Float, y: Float, done: (Boolean) -> Unit) {
        val p = Path()
        p.moveTo(x, y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
            .build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { done(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { done(false) }
        }, null)
        if (!ok) done(false)
    }

    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    /** Presses the keyboard's Enter / Search key on [node] (Android 11+; older phones can't). */
    fun pressEnter(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        fun find(n: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (depth > 40) return null
            if (n.isScrollable && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                find(c, depth + 1)?.let { return it }
            }
            return null
        }
        val s = find(root, 0) ?: return false
        return s.performAction(action)
    }

    fun global(action: Int): Boolean = performGlobalAction(action)

    /** Full-screen picture, Android 11+. Black on screens that block screenshots (banking, passwords). */
    fun screenshot(cb: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) {
            cb(null)
            return
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    var bmp: Bitmap? = null
                    try {
                        val hb = result.hardwareBuffer
                        val wrapped = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                        bmp = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                        wrapped?.recycle()
                        hb.close()
                    } catch (e: Exception) {
                        bmp = null
                    }
                    cb(bmp)
                }

                override fun onFailure(errorCode: Int) {
                    cb(null)
                }
            })
        } catch (e: Exception) {
            cb(null)
        }
    }
}
