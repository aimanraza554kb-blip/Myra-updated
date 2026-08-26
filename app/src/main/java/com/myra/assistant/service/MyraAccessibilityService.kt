package com.myra.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.myra.assistant.util.Logger

/**
 * Accessibility service that lets MYRA perform system-wide gestures: go home,
 * go back, open recents, take a screenshot and close the current app. This
 * powers the phone-automation features requested by voice.
 */
class MyraAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Logger.i(TAG, "Accessibility connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* reactive automation hook */ }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun goHome() = safeGlobal(GLOBAL_ACTION_HOME)
    fun goBack() = safeGlobal(GLOBAL_ACTION_BACK)
    fun openRecents() = safeGlobal(GLOBAL_ACTION_RECENTS)
    fun openNotifications() = safeGlobal(GLOBAL_ACTION_NOTIFICATIONS)
    fun takeScreenshotAction() = safeGlobal(GLOBAL_ACTION_TAKE_SCREENSHOT)

    /** Global actions can throw if the service was just killed/reconnected. */
    private fun safeGlobal(action: Int): Boolean = try {
        performGlobalAction(action)
    } catch (e: Exception) {
        Logger.e(TAG, "Global action $action failed", e)
        false
    }

    /** Swipe-scroll the current screen up or down. */
    fun scroll(down: Boolean): Boolean {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val startY = if (down) metrics.heightPixels * 0.72f else metrics.heightPixels * 0.28f
        val endY = if (down) metrics.heightPixels * 0.28f else metrics.heightPixels * 0.72f
        return swipe(x, startY, x, endY, 320L)
    }

    /** Tap at absolute screen coordinates. */
    fun clickAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchSafe(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
                .build()
        )
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return dispatchSafe(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build()
        )
    }

    private fun dispatchSafe(gesture: GestureDescription): Boolean = try {
        dispatchGesture(gesture, null, null)
    } catch (e: Exception) {
        Logger.e(TAG, "Gesture dispatch failed", e)
        false
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * A wa.me deep link only prefills a WhatsApp message; it does not send it.
     * Tap the Send button for the user once the chat has loaded. Retries a few
     * times because the chat may still be opening.
     */
    fun clickSendAfterDelay() {
        var attempts = 0
        val runnable = object : Runnable {
            override fun run() {
                if (clickSendButton() || attempts++ >= 6) return
                mainHandler.postDelayed(this, 500L)
            }
        }
        mainHandler.postDelayed(runnable, 1200L)
    }

    /** Find and click WhatsApp's Send button in the current window. */
    fun clickSendButton(): Boolean {
        val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return false
        val node = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")?.firstOrNull()
            ?: findByDesc(root, "Send")
            ?: return false
        val clickable = generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
        return clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    private fun findByDesc(node: AccessibilityNodeInfo?, desc: String): AccessibilityNodeInfo? {
        if (node == null) return null
        node.contentDescription?.toString()?.let { if (it.equals(desc, ignoreCase = true)) return node }
        for (i in 0 until node.childCount) {
            findByDesc(node.getChild(i), desc)?.let { return it }
        }
        return null
    }

    // ---- Generic "search & play" automation that works for ANY app ----

    fun tapFirstResultAfterDelay(initialDelayMs: Long = 1600L) {
        var attempts = 0
        val runnable = object : Runnable {
            override fun run() {
                if (tapFirstResult() || attempts++ >= 10) return
                mainHandler.postDelayed(this, 600L)
            }
        }
        mainHandler.postDelayed(runnable, initialDelayMs)
    }

    fun tapFirstResult(): Boolean {
        val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return false
        val list = tallestScrollable(root) ?: root
        val item = firstClickableInside(list) ?: return false
        val clickable = generateSequence(item) { it.parent }.firstOrNull { it.isClickable } ?: item
        return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun searchAndPlay(query: String) {
        mainHandler.postDelayed({ driveSearch(query, 0) }, 1500L)
    }

    private fun driveSearch(query: String, attempt: Int) {
        val root = try { rootInActiveWindow } catch (e: Exception) { null }
        if (root == null) {
            if (attempt < 12) mainHandler.postDelayed({ driveSearch(query, attempt + 1) }, 700L)
            return
        }
        val edit = findEditText(root)
        if (edit != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
            }
            edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            mainHandler.postDelayed({ submitSearchAndPlay() }, 600L)
            return
        }
        val searchBtn = findByDesc(root, "Search")
            ?: findByViewIdContains(root, "search")
            ?: findClickableContaining(root, "search")
        searchBtn?.let { n -> generateSequence(n) { it.parent }.firstOrNull { it.isClickable } }
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (attempt < 12) mainHandler.postDelayed({ driveSearch(query, attempt + 1) }, 700L)
    }

    private fun submitSearchAndPlay() {
        val root = try { rootInActiveWindow } catch (e: Exception) { null }
        val edit = root?.let { findEditText(it) }
        if (edit != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            edit.performAction(android.R.id.accessibilityActionImeEnter)
        }
        tapFirstResultAfterDelay(1400L)
    }

    private fun tallestScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestH = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.isScrollable) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (r.height() > bestH) { bestH = r.height(); best = n }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return best
    }

    private fun firstClickableInside(container: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until container.childCount) container.getChild(i)?.let { queue.add(it) }
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.isClickable && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun findEditText(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val cls = node.className?.toString() ?: ""
        if (cls.contains("EditText") && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            findEditText(node.getChild(i))?.let { return it }
        }
        return null
    }

    private fun findByViewIdContains(node: AccessibilityNodeInfo?, idPart: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val id = node.viewIdResourceName ?: ""
        if (id.contains(idPart, ignoreCase = true) && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            findByViewIdContains(node.getChild(i), idPart)?.let { return it }
        }
        return null
    }

    private fun findClickableContaining(node: AccessibilityNodeInfo?, term: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val txt = node.text?.toString()?.lowercase() ?: ""
        if ((desc.contains(term) || txt.contains(term)) && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            findClickableContaining(node.getChild(i), term)?.let { return it }
        }
        return null
    }

    companion object {
        private const val TAG = "MyraA11y"
        @Volatile
        var instance: MyraAccessibilityService? = null
            private set
    }
}
