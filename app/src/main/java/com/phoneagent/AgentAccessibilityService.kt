package com.phoneagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The agent's hands and eyes: reads the on-screen UI as numbered text and performs
 * taps, typing, scrolling and navigation through Android's accessibility APIs.
 */
class AgentAccessibilityService : AccessibilityService() {

    // id -> node from the most recent snapshot. Ids are only valid until the next snapshot.
    private val nodes = HashMap<Int, AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /** Returns a compact text description of the visible screen, numbering interactive/labelled nodes. */
    fun snapshot(): String {
        nodes.clear()
        val root = rootInActiveWindow ?: return "(no active window — the screen may be locked or loading)"
        val sb = StringBuilder()
        sb.append("App: ").append(root.packageName).append('\n')
        var next = 1
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (!node.isVisibleToUser) return
            val label = listOfNotNull(node.text, node.contentDescription, node.hintText)
                .map { it.toString().trim() }.firstOrNull { it.isNotEmpty() }
            val interactive = node.isClickable || node.isEditable || node.isScrollable || node.isCheckable
            if (label != null || interactive) {
                val id = next++
                nodes[id] = node
                val flags = buildList {
                    if (node.isClickable) add("clickable")
                    if (node.isEditable) add("editable")
                    if (node.isScrollable) add("scrollable")
                    if (node.isCheckable) add(if (node.isChecked) "checked" else "unchecked")
                    if (!node.isEnabled) add("disabled")
                }
                val kind = node.className?.toString()?.substringAfterLast('.') ?: "View"
                sb.append("  ".repeat(depth.coerceAtMost(6))).append('[').append(id).append("] ")
                    .append(kind)
                if (label != null) sb.append(" \"").append(label.take(120)).append('"')
                if (flags.isNotEmpty()) sb.append(" (").append(flags.joinToString(", ")).append(')')
                sb.append('\n')
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        if (nodes.isEmpty()) sb.append("(no readable elements)\n")
        return sb.toString()
    }

    suspend fun click(id: Int): String {
        val node = nodes[id] ?: return "Error: no element [$id] in the latest screen."
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Clicked [$id]."
        val r = Rect().also { node.getBoundsInScreen(it) }
        return if (tap(r.centerX().toFloat(), r.centerY().toFloat())) "Tapped [$id] by coordinates."
        else "Error: could not click [$id]."
    }

    fun typeText(id: Int, text: String): String {
        val node = nodes[id] ?: return "Error: no element [$id] in the latest screen."
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "Typed into [$id]."
        else "Error: [$id] did not accept text (is it editable?)."
    }

    fun scroll(id: Int?, forward: Boolean): String {
        val node = if (id != null) nodes[id] else nodes.values.firstOrNull { it.isScrollable }
        node ?: return "Error: nothing scrollable on screen."
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return if (node.performAction(action)) "Scrolled." else "Could not scroll further."
    }

    fun back(): String = if (performGlobalAction(GLOBAL_ACTION_BACK)) "Pressed back." else "Error: back failed."
    fun home(): String = if (performGlobalAction(GLOBAL_ACTION_HOME)) "Went home." else "Error: home failed."

    /** Opens an installed app by (partial, case-insensitive) label. */
    fun openApp(name: String): String {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = pm.queryIntentActivities(launcher, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val hit = matches.firstOrNull { it.first.equals(name, true) }
            ?: matches.firstOrNull { it.first.contains(name, true) }
            ?: return "Error: no app matching \"$name\". Installed: " +
                matches.joinToString { it.first }.take(600)
        val intent = pm.getLaunchIntentForPackage(hit.second)
            ?: return "Error: cannot launch ${hit.first}."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return "Opened ${hit.first}."
    }

    private suspend fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build()
        val done = CompletableDeferred<Boolean>()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { done.complete(true) }
            override fun onCancelled(g: GestureDescription?) { done.complete(false) }
        }, null)
        return ok && (withTimeoutOrNull(2000) { done.await() } ?: false)
    }

    /** Gives the UI a moment to settle after an action. */
    suspend fun settle() = delay(900)

    companion object {
        @Volatile var instance: AgentAccessibilityService? = null
    }
}
