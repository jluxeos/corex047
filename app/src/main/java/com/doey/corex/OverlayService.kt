package com.doey.corex

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.*
import android.widget.*
import com.google.android.material.button.MaterialButton

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    companion object {
        const val CHANNEL_ID = "corex_overlay"
        const val NOTIF_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            startForeground(NOTIF_ID, buildNotification("Iniciando..."))
            setupOverlay()
            updateNotification("Corex activo ✓")
        } catch (e: Exception) {
            updateNotification("Error: ${e.javaClass.simpleName}: ${e.message?.take(50)}")
        }
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_bar, null)
        windowManager!!.addView(overlayView, params)
        setupListeners(params)
    }

    private fun setupListeners(params: WindowManager.LayoutParams) {
        val input = overlayView!!.findViewById<EditText>(R.id.inputOverlay)
        val btnSend = overlayView!!.findViewById<ImageButton>(R.id.btnSend)
        val btnClose = overlayView!!.findViewById<ImageButton>(R.id.btnClose)
        val btnExpand = overlayView!!.findViewById<ImageButton>(R.id.btnExpand)
        val panelExpanded = overlayView!!.findViewById<View>(R.id.panelExpanded)
        val panelAcciones = overlayView!!.findViewById<View>(R.id.panelAcciones)
        val panelAjustes = overlayView!!.findViewById<View>(R.id.panelAjustes)
        val scrollHistorial = overlayView!!.findViewById<View>(R.id.scrollHistorial)
        val tvHistorial = overlayView!!.findViewById<TextView>(R.id.tvHistorial)
        val tabHistorial = overlayView!!.findViewById<TextView>(R.id.tabHistorial)
        val tabAcciones = overlayView!!.findViewById<TextView>(R.id.tabAcciones)
        val tabAjustes = overlayView!!.findViewById<TextView>(R.id.tabAjustes)
        val seekDelay = overlayView!!.findViewById<SeekBar>(R.id.seekDelay)
        val tvDelayVal = overlayView!!.findViewById<TextView>(R.id.tvDelayVal)
        val btnGuardar = overlayView!!.findViewById<MaterialButton>(R.id.btnGuardar)
        val etApiKey = overlayView!!.findViewById<EditText>(R.id.etApiKey)

        val focusListener = View.OnFocusChangeListener { _, hasFocus ->
            params.flags = if (hasFocus)
                params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else
                params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            windowManager!!.updateViewLayout(overlayView, params)
        }
        input.onFocusChangeListener = focusListener
        etApiKey.onFocusChangeListener = focusListener

        var isExpanded = false
        btnExpand.setOnClickListener {
            isExpanded = !isExpanded
            panelExpanded.visibility = if (isExpanded) View.VISIBLE else View.GONE
            btnExpand.setImageResource(
                if (isExpanded) android.R.drawable.arrow_down_float
                else android.R.drawable.arrow_up_float
            )
        }

        fun selectTab(tab: Int) {
            scrollHistorial.visibility = if (tab == 0) View.VISIBLE else View.GONE
            panelAcciones.visibility = if (tab == 1) View.VISIBLE else View.GONE
            panelAjustes.visibility = if (tab == 2) View.VISIBLE else View.GONE
            tabHistorial.setTextColor(if (tab == 0) 0xFF6750A4.toInt() else 0xFF79747E.toInt())
            tabAcciones.setTextColor(if (tab == 1) 0xFF6750A4.toInt() else 0xFF79747E.toInt())
            tabAjustes.setTextColor(if (tab == 2) 0xFF6750A4.toInt() else 0xFF79747E.toInt())
        }

        tabHistorial.setOnClickListener { selectTab(0) }
        tabAcciones.setOnClickListener { selectTab(1) }
        tabAjustes.setOnClickListener { selectTab(2) }

        overlayView!!.findViewById<MaterialButton>(R.id.btnWhatsApp).setOnClickListener {
            launchApp("com.whatsapp")
        }
        overlayView!!.findViewById<MaterialButton>(R.id.btnMaps).setOnClickListener {
            launchApp("com.google.android.apps.maps")
        }
        overlayView!!.findViewById<MaterialButton>(R.id.btnYoutube).setOnClickListener {
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
            if (text.isNotEmpty()) {
                tvHistorial.append("\nTú: $text")
                input.setText("")
            }
        }

        btnClose.setOnClickListener { stopSelf() }
    }

    private fun launchApp(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
                ?: throw Exception("No instalada")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Corex", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Corex")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .addAction(android.R.drawable.ic_delete, "Cerrar", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { overlayView?.let { windowManager?.removeView(it) } } catch (e: Exception) {}
    }
}
