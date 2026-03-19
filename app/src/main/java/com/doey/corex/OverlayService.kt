package com.doey.corex

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private var isExpanded = false

    companion object {
        const val CHANNEL_ID = "corex_overlay"
        const val NOTIF_ID = 1
        const val TAG = "OverlayService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        try {
            createNotificationChannel()
            startForeground(NOTIF_ID, buildNotification())
            setupOverlay()
        } catch (e: Exception) {
            Log.e(TAG, "Error en onCreate: ${e.message}", e)
        }
    }

    private fun setupOverlay() {
        Log.d(TAG, "setupOverlay")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_bar, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        windowManager.addView(overlayView, params)
        Log.d(TAG, "Overlay añadido")
        setupListeners(params)
    }

    private fun setupListeners(params: WindowManager.LayoutParams) {
        val btnExpand = overlayView.findViewById<MaterialButton>(R.id.btnExpand)
        val btnSend = overlayView.findViewById<MaterialButton>(R.id.btnSend)
        val inputOverlay = overlayView.findViewById<EditText>(R.id.inputOverlay)
        val cardHistory = overlayView.findViewById<CardView>(R.id.cardHistory)

        inputOverlay.setOnFocusChangeListener { _, hasFocus ->
            val flags = if (hasFocus)
                params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else
                params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            params.flags = flags
            windowManager.updateViewLayout(overlayView, params)
        }

        btnExpand.setOnClickListener {
            isExpanded = !isExpanded
            if (isExpanded) {
                cardHistory.visibility = View.VISIBLE
                cardHistory.alpha = 0f
                cardHistory.animate().alpha(1f).setDuration(250)
                    .setInterpolator(DecelerateInterpolator()).start()
            } else {
                cardHistory.animate().alpha(0f).setDuration(200)
                    .withEndAction { cardHistory.visibility = View.GONE }.start()
            }
        }

        btnSend.setOnClickListener {
            val text = inputOverlay.text.toString().trim()
            if (text.isNotEmpty()) {
                inputOverlay.setText("")
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Corex Activo",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Corex activo")
            .setContentText("Toca para abrir")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        if (::overlayView.isInitialized) {
            try { windowManager.removeView(overlayView) } catch (e: Exception) {}
        }
    }
}
