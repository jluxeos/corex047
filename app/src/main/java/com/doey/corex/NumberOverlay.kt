package com.doey.corex

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class NumberOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val views = mutableListOf<View>()
    private val handler = Handler(Looper.getMainLooper())

    fun show(elements: List<ScreenElement>, onTap: (ScreenElement) -> Unit) {
        hide()
        handler.post {
            elements.take(20).forEach { el ->
                val tv = TextView(context).apply {
                    text = "${el.index}"
                    textSize = 11f
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundColor(0xCC6750A4.toInt())
                    setPadding(6, 2, 6, 2)
                    setOnClickListener { hide(); onTap(el) }
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = el.bounds.left
                    y = el.bounds.top
                }
                try {
                    windowManager.addView(tv, params)
                    views.add(tv)
                } catch (e: Exception) {}
            }
        }
    }

    fun hide() {
        handler.post {
            views.forEach { try { windowManager.removeView(it) } catch (e: Exception) {} }
            views.clear()
        }
    }
}
