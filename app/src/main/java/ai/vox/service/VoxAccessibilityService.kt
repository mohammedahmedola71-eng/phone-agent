package ai.vox.service

import ai.vox.session.SessionState
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Screen awareness and phone automation. Every entry point is a no-op unless a session is active. */
class VoxAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /** Flattens the visible UI into numbered lines the model can reference by index. */
    fun describeScreen(): String {
        if (!SessionState.isActive) return "Session not active."
        val root = rootInActiveWindow ?: return "No active window."
        nodes.clear()
        val sb = StringBuilder()
        walk(root, sb)
        return sb.toString().ifBlank { "Screen has no readable content." }
    }

    private val nodes = mutableListOf<AccessibilityNodeInfo>()

    private fun walk(node: AccessibilityNodeInfo, sb: StringBuilder) {
        val text = node.text?.toString() ?: node.contentDescription?.toString()
        if (node.isVisibleToUser && (text != null || node.isClickable || node.isEditable)) {
            val r = Rect().also { node.getBoundsInScreen(it) }
            sb.append('[').append(nodes.size).append("] ")
                .append(node.className?.toString()?.substringAfterLast('.'))
                .append(if (node.isClickable) " clickable" else "")
                .append(if (node.isEditable) " editable" else "")
                .append(" \"").append(text ?: "").append("\" @")
                .append(r.centerX()).append(',').append(r.centerY()).append('\n')
            nodes.add(node)
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, sb) }
    }

    fun click(index: Int): Boolean {
        if (!SessionState.isActive) return false
        var n: AccessibilityNodeInfo? = nodes.getOrNull(index)
        while (n != null && !n.isClickable) n = n.parent
        return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    fun typeText(index: Int, text: String): Boolean {
        if (!SessionState.isActive) return false
        val n = nodes.getOrNull(index) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun tap(x: Float, y: Float): Boolean = gesture(Path().apply { moveTo(x, y) }, 50)

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 300)

    private fun gesture(path: Path, durationMs: Long): Boolean {
        if (!SessionState.isActive) return false
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        return dispatchGesture(g, null, null)
    }

    fun back(): Boolean = SessionState.isActive && performGlobalAction(GLOBAL_ACTION_BACK)
    fun home(): Boolean = SessionState.isActive && performGlobalAction(GLOBAL_ACTION_HOME)

    companion object {
        @Volatile var instance: VoxAccessibilityService? = null
    }
}
