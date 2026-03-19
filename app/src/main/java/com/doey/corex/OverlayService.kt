package com.doey.corex

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
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
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        setupOverlay()
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_bar, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        windowManager.addView(overlayView, params)
        setupListeners(params)
    }

    private fun setupListeners(params: WindowManager.LayoutParams) {
        val input = overlayView.findViewById<EditText>(R.id.inputOverlay)
        val btnSend = overlayView.findViewById<ImageButton>(R.id.btnSend)
        val btnClose = overlayView.findViewById<ImageButton>(R.id.btnClose)
        val btnExpand = overlayView.findViewById<ImageButton>(R.id.btnExpand)
        val panelExpanded = overlayView.findViewById<View>(R.id.panelExpanded)
        val recycler = overlayView.findViewById<RecyclerView>(R.id.recyclerHistorial)
        val panelAcciones = overlayView.findViewById<View>(R.id.panelAcciones)
        val panelAjustes = overlayView.findViewById<View>(R.id.panelAjustes)
        val tabHistorial = overlayView.findViewById<TextView>(R.id.tabHistorial)
        val tabAcciones = overlayView.findViewById<TextView>(R.id.tabAcciones)
        val tabAjustes = overlayView.findViewById<TextView>(R.id.tabAjustes)
        val seekDelay = overlayView.findViewById<SeekBar>(R.id.seekDelay)
        val tvDelayVal = overlayView.findViewById<TextView>(R.id.tvDelayVal)
        val btnGuardar = overlayView.findViewById<MaterialButton>(R.id.btnGuardar)
        val etApiKey = overlayView.findViewById<EditText>(R.id.etApiKey)

        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }

        val focusListener = View.OnFocusChangeListener { _, hasFocus ->
            params.flags = if (hasFocus)
                params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else
                params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            windowManager.updateViewLayout(overlayView, params)
        }
        input.onFocusChangeListener = focusListener
        etApiKey.onFocusChangeListener = focusListener

        btnExpand.setOnClickListener {
            isExpanded = !isExpanded
            if (isExpanded) {
                panelExpanded.visibility = View.VISIBLE
                panelExpanded.alpha = 0f
                panelExpanded.animate().alpha(1f).setDuration(250)
                    .setInterpolator(DecelerateInterpolator()).start()
                btnExpand.setImageResource(android.R.drawable.arrow_down_float)
            } else {
                panelExpanded.animate().alpha(0f).setDuration(200)
                    .withEndAction { panelExpanded.visibility = View.GONE }.start()
                btnExpand.setImageResource(android.R.drawable.arrow_up_float)
            }
        }

        fun selectTab(tab: Int) {
            recycler.visibility = if (tab == 0) View.VISIBLE else View.GONE
            panelAcciones.visibility = if (tab == 1) View.VISIBLE else View.GONE
            panelAjustes.visibility = if (tab == 2) View.VISIBLE else View.GONE
            val purple = 0xFF6750A4.toInt()
            val gray = 0xFF79747E.toInt()
            tabHistorial.setTextColor(if (tab == 0) purple else gray)
            tabAcciones.setTextColor(if (tab == 1) purple else gray)
            tabAjustes.setTextColor(if (tab == 2) purple else gray)
        }

        tabHistorial.setOnClickListener { selectTab(0) }
        tabAcciones.setOnClickListener { selectTab(1) }
        tabAjustes.setOnClickListener { selectTab(2) }

        overlayView.findViewById<MaterialButton>(R.id.btnWhatsApp).setOnClickListener {
            launchApp("com.whatsapp")
        }
        overlayView.findViewById<MaterialButton>(R.id.btnMaps).setOnClickListener {
            launchApp("com.google.android.apps.maps")
        }
        overlayView.findViewById<MaterialButton>(R.id.btnYoutube).setOnClickListener {
            launchApp("com.google.android.youtube")
        }

        seekDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvDelayVal.text = "${progress}ms"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnGuardar.setOnClickListener {
            getSharedPreferences("corex_prefs", MODE_PRIVATE).edit()
                .putString("api_key", etApiKey.text.toString().trim())
                .putInt("delay", seekDelay.progress)
                .apply()
            Toast.makeText(this, "Guardado", Toast.LENGTH_SHORT).show()
        }

        val prefs = getSharedPreferences("corex_prefs", MODE_PRIVATE)
        etApiKey.setText(prefs.getString("api_key", ""))
        seekDelay.progress = prefs.getInt("delay", 400)
        tvDelayVal.text = "${seekDelay.progress}ms"

        btnSend.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) input.setText("")
        }

        btnClose.setOnClickListener { stopSelf() }
    }

    private fun launchApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            Toast.makeText(this, "App no instalada", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Corex Activo", NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Corex activo")
            .setContentText("Toca para cerrar")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cerrar", stopIntent)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (::overlayView.isInitialized) {
            try { windowManager.removeView(overlayView) } catch (e: Exception) {}
        }
    }
}
