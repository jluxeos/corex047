package com.doey.corex

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class CorexAccessibilityService : AccessibilityService() {

    companion object {
        var instance: CorexAccessibilityService? = null

        fun getScreenElements(): List<ScreenElement> {
            val service = instance ?: return emptyList()
            val root = service.rootInActiveWindow ?: return emptyList()
            val elements = mutableListOf<ScreenElement>()
            collectElements(root, elements)
            root.recycle()
            return elements
        }

        private fun collectElements(node: AccessibilityNodeInfo, elements: MutableList<ScreenElement>) {
            if (node.packageName?.toString() == "com.doey.corex") return
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val label = text.ifEmpty { desc }
            if (label.isNotEmpty() && label.length < 60 && (node.isClickable || node.isEditable)) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty && bounds.width() > 0 && bounds.height() > 0) {
                    elements.add(ScreenElement(
                        index = elements.size,
                        text = text,
                        contentDesc = desc,
                        bounds = bounds,
                        isClickable = node.isClickable,
                        isEditable = node.isEditable
                    ))
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectElements(child, elements)
                child.recycle()
            }
        }

        fun tapElement(index: Int): Boolean {
            val service = instance ?: return false
            val elements = getScreenElements()
            if (index < 0 || index >= elements.size) return false
            val bounds = elements[index].bounds
            val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
            service.dispatchGesture(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build(), null, null)
            return true
        }

        fun typeText(text: String): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            val field = findEditable(root) ?: return false
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(150)
            val result = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            field.recycle(); root.recycle()
            return result
        }

        fun scrollDown(): Boolean {
            val service = instance ?: return false
            val path = Path().apply { moveTo(540f, 1400f); lineTo(540f, 400f) }
            service.dispatchGesture(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build(), null, null)
            return true
        }

        fun scrollUp(): Boolean {
            val service = instance ?: return false
            val path = Path().apply { moveTo(540f, 400f); lineTo(540f, 1400f) }
            service.dispatchGesture(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build(), null, null)
            return true
        }

        fun pressBack() { instance?.performGlobalAction(GLOBAL_ACTION_BACK) }
        fun pressHome() { instance?.performGlobalAction(GLOBAL_ACTION_HOME) }

        fun getInstalledApps(): List<Pair<String, String>> {
            val service = instance ?: return emptyList()
            val pm = service.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(intent, 0)
            return apps.map { info ->
                info.loadLabel(pm).toString() to info.activityInfo.packageName
            }.sortedBy { it.first }
        }

        fun getInstalledAppsWithIcons(): List<Triple<String, String, android.graphics.drawable.Drawable?>> {
            val service = instance ?: return emptyList()
            val pm = service.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(intent, 0)
            android.util.Log.d("CorexApps", "Apps desde AccessibilityService: ${apps.size}")
            return apps.map { info ->
                Triple(
                    info.loadLabel(pm).toString(),
                    info.activityInfo.packageName,
                    try { info.loadIcon(pm) } catch (e: Exception) { null }
                )
            }.sortedBy { it.first }
        }

        fun launchPackage(pkg: String, context: android.content.Context): Boolean {
            return try {
                val service = instance
                val pm = service?.packageManager ?: context.packageManager
                val intent = pm.getLaunchIntentForPackage(pkg) ?: return false
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                android.util.Log.e("CorexApps", "launchPackage error: ${e.message}")
                false
            }
        }

        fun getCurrentApp(): String {
            val root = instance?.rootInActiveWindow ?: return "desconocida"
            return root.packageName?.toString() ?: "desconocida"
        }

        fun getEnrichedContext(): String {
            val pkg = getCurrentApp()
            val appName = try {
                instance?.packageManager?.getApplicationLabel(
                    instance?.packageManager?.getApplicationInfo(pkg, 0)!!
                )?.toString() ?: pkg
            } catch (e: Exception) { pkg }
            val dump = getDumpForAI()
            return "App actual: $appName ($pkg)

Elementos en pantalla:
$dump"
        }

        fun getDumpForAI(): String {
            val elements = getScreenElements()
            if (elements.isEmpty()) return "PANTALLA VACÍA"
            return elements.joinToString("\n") { e ->
                val type = if (e.isEditable) "[IN]" else "[TAP]"
                "${e.index}.$type ${e.text.ifEmpty { e.contentDesc }}"
            }
        }

        private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (root.isEditable) return root
            for (i in 0 until root.childCount) {
                val child = root.getChild(i) ?: continue
                val found = findEditable(child)
                if (found != null) return found
                child.recycle()
            }
            return null
        }
    }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Notificar cambio de pantalla para ocultar overlay de numeros
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            OverlayService.onScreenChanged()
        }
    }
    override fun onInterrupt() { instance = null }
    override fun onDestroy() { super.onDestroy(); instance = null }
}
