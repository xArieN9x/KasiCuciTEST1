package com.example.allinoneflushapp

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AccessibilityAutomationService : AccessibilityService() {

    companion object {
        private val handler = Handler(Looper.getMainLooper())
        private const val AIRPLANE_DELAY = 4000L
        private val storageKeys = arrayOf("Storage usage", "Storage usage ", "Storage", "Storage & cache", "App storage")
        private val forceStopKeys = arrayOf("Force stop", "Force stop ", "Force Stop", "Paksa berhenti", "Paksa Hentikan")
        private val confirmOkKeys = arrayOf("OK", "Yes", "Confirm", "Ya", "Force stop ", "Force stop")
        private val clearCacheKeys = arrayOf("Clear cache", "Clear cache ", "Clear Cache", "Kosongkan cache")

        fun requestClearAndForceStop(packageName: String) {
            val ctx = AppGlobals.applicationContext
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            ctx.startActivity(intent)

            handler.postDelayed({
                val svc = AppGlobals.accessibilityService ?: return@postDelayed
                // Click Force Stop
                val clicked = svc.findAndClick(*forceStopKeys)
                if (clicked) {
                    // Confirm dialog
                    handler.postDelayed({ svc.findAndClick(*confirmOkKeys) }, 700)
                }
                // Go to Storage then Clear cache
                handler.postDelayed({
                    svc.findAndClick(*storageKeys)
                    // ✅ FIXED: 900ms → 1500ms (give time for screen to load)
                    handler.postDelayed({ svc.findAndClick(*clearCacheKeys) }, 1500)
                    handler.postDelayed({ svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }, 2700)
                }, 1600)
            }, 1200)
        }

        fun requestToggleAirplane() {
            val svc = AppGlobals.accessibilityService ?: return
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            handler.postDelayed({
                // Click airplane ON
                val clicked = svc.findAndClick("Airplane mode", "Airplane mode ","Airplane", "Mod Pesawat", "Mod Penerbangan", "Aeroplane mode", maxRetries = 3)
                if (!clicked) {
                    svc.findAndClick("Airplane", maxRetries = 3)
                }
                // Wait ON
                SystemClock.sleep(AIRPLANE_DELAY)
                // Click airplane OFF
                svc.findAndClick("Airplane mode", "Airplane mode ", "Airplane", "Mod Pesawat", "Mod Penerbangan", maxRetries = 3)
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }, 700)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppGlobals.accessibilityService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    // ✅ OPTIMIZED: Reduce retry dari 6 → 3 untuk speed
    fun findAndClick(vararg keys: String, maxRetries: Int = 3, delayMs: Long = 700L): Boolean {
        repeat(maxRetries) {
            val root = rootInActiveWindow
            if (root != null) {
                for (k in keys) {
                    // Exact text matches
                    val nodes = root.findAccessibilityNodeInfosByText(k)
                    if (!nodes.isNullOrEmpty()) {
                        for (n in nodes) {
                            if (n.isClickable) { n.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true }
                            var p = n.parent
                            while (p != null) {
                                if (p.isClickable) { p.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true }
                                p = p.parent
                            }
                        }
                    }
                    // Content-desc scan
                    val desc = findNodeByDescription(root, k)
                    if (desc != null) { desc.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true }
                    // ViewId fallback
                    val idNode = findNodeByViewId(root, k)
                    if (idNode != null) {
                        if (idNode.isClickable) { idNode.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true }
                        var p = idNode.parent
                        while (p != null) {
                            if (p.isClickable) { p.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true }
                            p = p.parent
                        }
                    }
                }
                // Try scroll to reveal
                root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            Thread.sleep(delayMs)
        }
        return false
    }

    private fun findNodeByDescription(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            try {
                val cd = n.contentDescription
                if (cd != null && cd.toString().contains(text, true)) return n
            } catch (_: Exception) {}
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    private fun findNodeByViewId(root: AccessibilityNodeInfo, idPart: String): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            try {
                val vid = n.viewIdResourceName
                if (vid != null && vid.contains(idPart, true)) return n
            } catch (_: Exception) {}
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return null
    }
}
