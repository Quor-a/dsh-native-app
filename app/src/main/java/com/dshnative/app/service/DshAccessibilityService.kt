package com.dshnative.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 无障碍服务：为 AI 工具提供 UI 树、截图、点击、滑动、输入等能力。
 * 通过 [instance] 静态实例暴露给工具执行器调用。
 */
class DshAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 事件订阅为后续自动化预留
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** 根节点 → 简化 UI 树 JSON */
    fun getUiTreeJson(): String {
        val root = rootInActiveWindow ?: return """{"error":"no active window"}"""
        val sb = StringBuilder()
        sb.append('{')
        dumpNode(root, sb, 0)
        sb.append('}')
        return sb.toString()
    }

    private fun dumpNode(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 0) sb.append(',')
        sb.append('"').append(depth).append("\":{")
        sb.append("\"cls\":\"").append(node.className?.toString()?.replace("\"", "\\\"") ?: "").append('"')
        val text = node.text?.toString()
        if (!text.isNullOrEmpty()) {
            sb.append(",\"txt\":\"").append(text.replace("\"", "\\\"").take(200)).append('"')
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        sb.append(",\"b\":[").append(bounds.left).append(',').append(bounds.top).append(',').append(bounds.right).append(',').append(bounds.bottom).append(']')
        if (node.isClickable) sb.append(",\"click\":true")
        if (node.isScrollable) sb.append(",\"scroll\":true")
        if (node.isEditable) sb.append(",\"edit\":true")
        if (node.viewIdResourceName != null) {
            sb.append(",\"id\":\"").append(node.viewIdResourceName).append('"')
        }
        sb.append('}')
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNode(child, sb, depth + 1)
        }
        node.recycle()
    }

    /** API 30+ 截图 → JSON（base64 PNG + 尺寸）；低版本返回错误。 */
    fun takeScreenshotJson(): String {
        if (Build.VERSION.SDK_INT < 30) {
            return """{"error":"screenshot requires Android 11+"}"""
        }
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        var error: String? = null
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor(),
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, null)
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    error = "screenshot failed code=$errorCode"
                    latch.countDown()
                }
            }
        )
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) return """{"error":"screenshot timeout"}"""
        } catch (e: InterruptedException) {
            return """{"error":"interrupted"}"""
        }
        if (error != null) return """{"error":"$error"}"""
        val bmp = bitmap ?: return """{"error":"no bitmap"}"""
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return """{"base64":"$b64","width":${bmp.width},"height":${bmp.height}}"""
    }

    private fun mainExecutor() = java.util.concurrent.Executor { command -> mainHandler.post(command) }

    /** 按文本点击（优先系统 findAccessibilityNodeInfosByText，WebView 文本点击更可靠） */
    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (n in nodes) {
            val clickable = clickableAncestor(n)
            if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }
        // 兜底：文本节点自身尝试点击
        for (n in nodes) {
            if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        while (cur != null) {
            if (cur.isClickable) return cur
            cur = cur.parent
        }
        return null
    }

    /** 坐标点击 */
    fun tap(x: Int, y: Int): Boolean {
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(android.graphics.Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 0, 80))
                .build(),
            null,
            null
        )
    }

    /** 滑动 */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, millis: Long): Boolean {
        val path = android.graphics.Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, millis))
                .build(),
            null,
            null
        )
    }

    /** 输入文本 */
    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root)
        return focused?.let { node ->
            val bundle = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        } ?: false
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditable(child)
            if (result != null) return result
        }
        return null
    }

    /** 全局动作 */
    fun globalAction(actionId: Int): Boolean {
        return performGlobalAction(actionId)
    }

    companion object {
        @Volatile
        var instance: DshAccessibilityService? = null
            private set
    }
}