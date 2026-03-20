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

        data class ScreenElement(
            val index: Int,
            val text: String,
            val contentDesc: String,
            val bounds: Rect,
            val isClickable: Boolean,
            val isEditable: Boolean
        )

        fun getScreenElements(): List<ScreenElement> {
            val service = instance ?: return emptyList()
            val root = service.rootInActiveWindow ?: return emptyList()
            val elements = mutableListOf<ScreenElement>()
            collectElements(root, elements)
            root.recycle()
            return elements
        }

        private fun collectElements(node: AccessibilityNodeInfo, elements: MutableList<ScreenElement>) {
            // Excluir elementos del propio Corex
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
            return tapAt(service, bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }

        fun tapAt(service: CorexAccessibilityService, x: Float, y: Float): Boolean {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
            service.dispatchGesture(gesture, null, null)
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
            field.recycle()
            root.recycle()
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

        fun pressBack() = instance?.performGlobalAction(GLOBAL_ACTION_BACK) != null
        fun pressHome() = instance?.performGlobalAction(GLOBAL_ACTION_HOME) != null

        fun openAppByName(name: String): String {
            val service = instance ?: return "FAIL: Servicio no activo"
            val apps = service.packageManager.queryIntentActivities(
                Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0
            )
            // Mandar lista a IA para que elija
            val list = apps.map { "${it.loadLabel(service.packageManager)}=${it.activityInfo.packageName}" }
            val match = list.firstOrNull { it.startsWith(name, ignoreCase = true) }
                ?: list.firstOrNull { it.contains(name, ignoreCase = true) }
                ?: return "FAIL: $name no encontrado"
            val pkg = match.substringAfter("=")
            val intent = service.packageManager.getLaunchIntentForPackage(pkg) ?: return "FAIL: no intent"
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.applicationContext.startActivity(intent)
            return "OK:$pkg"
        }

        fun getInstalledApps(): String {
            val service = instance ?: return ""
            val apps = service.packageManager.queryIntentActivities(
                Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0
            )
            return apps.joinToString("\n") {
                "${it.loadLabel(service.packageManager)}|${it.activityInfo.packageName}"
            }
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
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { instance = null }
    override fun onDestroy() { super.onDestroy(); instance = null }
}
