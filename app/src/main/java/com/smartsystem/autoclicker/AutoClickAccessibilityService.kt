package com.smartsystem.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility Service — two core capabilities:
 *
 * 1. findNodeCenter(text)   — reads the real Android UI tree to find any node
 *    whose text/contentDescription contains [text] (case-insensitive).
 *
 * 2. fillTextField(hint, value) — finds an editable field by its hint/placeholder
 *    text and types [value] into it using ACTION_SET_TEXT (no simulated keystrokes).
 *
 * 3. tapByText(text)   — find + tap a node in one call.
 *
 * 4. hasAnyText(vararg queries) — returns the first matching query if any text
 *    appears on screen, or null if none found.
 *
 * 5. tap(x, y)         — dispatch a synthetic tap gesture at screen coordinates.
 *
 * 6. pressBack()       — dispatch GLOBAL_ACTION_BACK.
 * 7. pressHome()       — dispatch GLOBAL_ACTION_HOME.
 */
class AutoClickAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.d(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { Log.w(TAG, "Interrupted") }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ─── Root collection ──────────────────────────────────────────────────────

    private fun getAllRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try { rootInActiveWindow?.let { roots.add(it) } } catch (_: Exception) {}
        try {
            windows?.forEach { win ->
                try { win.root?.let { roots.add(it) } } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return roots
    }

    // ─── Node search ──────────────────────────────────────────────────────────

    /** Find center coordinates of the first node containing [query] (text or contentDescription). */
    fun findNodeCenter(query: String): PointF? {
        val q = query.lowercase().trim()
        for (root in getAllRoots()) {
            val result = searchNodeForText(root, q)
            try { root.recycle() } catch (_: Exception) {}
            if (result != null) return result
        }
        return null
    }

    private fun searchNodeForText(node: AccessibilityNodeInfo, query: String): PointF? {
        val text = (node.text?.toString() ?: "").lowercase()
        val desc = (node.contentDescription?.toString() ?: "").lowercase()
        if (text.contains(query) || desc.contains(query)) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty && bounds.width() > 0 && bounds.height() > 0) {
                Log.d(TAG, "findNodeCenter('$query') → bounds=$bounds")
                return PointF(bounds.exactCenterX(), bounds.exactCenterY())
            }
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            val r = searchNodeForText(child, query)
            try { child.recycle() } catch (_: Exception) {}
            if (r != null) return r
        }
        return null
    }

    // ─── Text field filling ────────────────────────────────────────────────────

    /**
     * Find an editable field whose hint/placeholder contains [hintQuery] and
     * set its text to [value] using ACTION_SET_TEXT.
     * Returns true if the field was found and filled.
     */
    fun fillTextField(hintQuery: String, value: String): Boolean {
        val q = hintQuery.lowercase().trim()
        for (root in getAllRoots()) {
            val node = findEditableNode(root, q)
            if (node != null) {
                val bundle = Bundle()
                bundle.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                try { node.recycle() } catch (_: Exception) {}
                try { root.recycle() } catch (_: Exception) {}
                Log.d(TAG, "fillTextField('$hintQuery', '***') → $ok")
                return ok
            }
            try { root.recycle() } catch (_: Exception) {}
        }
        return false
    }

    private fun findEditableNode(node: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        if (node.isEditable) {
            val hint = (node.hintText?.toString() ?: "").lowercase()
            val text = (node.text?.toString() ?: "").lowercase()
            val desc = (node.contentDescription?.toString() ?: "").lowercase()
            if (hint.contains(query) || text.contains(query) || desc.contains(query) || query.isEmpty()) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            val result = findEditableNode(child, query)
            if (result != null) return result
            try { child.recycle() } catch (_: Exception) {}
        }
        return null
    }

    // ─── Presence check ───────────────────────────────────────────────────────

    /**
     * Returns the first query that appears on screen (text or contentDescription),
     * or null if none of them are found.
     */
    fun hasAnyText(vararg queries: String): String? {
        val lq = queries.map { it.lowercase() }
        for (root in getAllRoots()) {
            val found = searchAnyText(root, lq)
            try { root.recycle() } catch (_: Exception) {}
            if (found != null) return found
        }
        return null
    }

    private fun searchAnyText(node: AccessibilityNodeInfo, queries: List<String>): String? {
        val text = (node.text?.toString() ?: "").lowercase()
        val desc = (node.contentDescription?.toString() ?: "").lowercase()
        for (q in queries) {
            if (text.contains(q) || desc.contains(q)) return q
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            val found = searchAnyText(child, queries)
            try { child.recycle() } catch (_: Exception) {}
            if (found != null) return found
        }
        return null
    }

    // ─── Convenience: tap by text ─────────────────────────────────────────────

    /** Find a node containing [text] and tap its center. Returns true if tapped. */
    fun tapByText(text: String, onDone: (() -> Unit)? = null): Boolean {
        val pt = findNodeCenter(text) ?: return false
        tap(pt.x, pt.y, onDone)
        return true
    }

    // ─── Gesture: tap at coordinates ──────────────────────────────────────────

    fun tap(x: Float, y: Float, onDone: (() -> Unit)? = null) {
        Log.d(TAG, "tap($x, $y)")
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 80L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { onDone?.invoke() }
            override fun onCancelled(g: GestureDescription?) {
                Log.w(TAG, "tap cancelled at ($x,$y)")
                onDone?.invoke()
            }
        }, null)
    }

    // ─── Global actions ───────────────────────────────────────────────────────

    fun pressBack() { performGlobalAction(GLOBAL_ACTION_BACK) }
    fun pressHome() { performGlobalAction(GLOBAL_ACTION_HOME) }

    companion object {
        private const val TAG = "AutoClickService"

        var instance: AutoClickAccessibilityService? = null
            private set

        val isConnected: Boolean get() = instance != null
    }
}
