package com.doey.corex

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.*
import kotlinx.coroutines.*

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isExpanded = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var cache: LearningCache
    private var pendingAsk: ((String) -> Unit)? = null
    private val history = mutableListOf<String>()
    private lateinit var params: WindowManager.LayoutParams

    companion object {
        const val CHANNEL_ID = "corex_overlay"
        const val NOTIF_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        cache = LearningCache(this)
        try {
            createNotificationChannel()
            startForeground(NOTIF_ID, buildNotification("Corex activo"))
            setupOverlay()
        } catch (e: Exception) {
            updateNotification("Error: ${e.javaClass.simpleName}: ${e.message?.take(60)}")
        }
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_bar, null)

        // FLAG_NOT_TOUCH_MODAL permite tocar lo que está detrás del overlay
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        windowManager!!.addView(overlayView, params)
        setupListeners()
    }

    private fun enableInput() {
        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        windowManager?.updateViewLayout(overlayView, params)
    }

    private fun disableInput() {
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        windowManager?.updateViewLayout(overlayView, params)
        // Ocultar teclado
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        overlayView?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun setupListeners() {
        val v = overlayView!!
        val input = v.findViewById<EditText>(R.id.inputOverlay)
        val btnSend = v.findViewById<ImageButton>(R.id.btnSend)
        val btnClose = v.findViewById<ImageButton>(R.id.btnClose)
        val btnExpand = v.findViewById<ImageButton>(R.id.btnExpand)
        val panelExpanded = v.findViewById<View>(R.id.panelExpanded)
        val panelAcciones = v.findViewById<View>(R.id.panelAcciones)
        val panelAjustes = v.findViewById<View>(R.id.panelAjustes)
        val scrollHistorial = v.findViewById<View>(R.id.scrollHistorial)
        val tvHistorial = v.findViewById<TextView>(R.id.tvHistorial)
        val tabHistorial = v.findViewById<TextView>(R.id.tabHistorial)
        val tabAcciones = v.findViewById<TextView>(R.id.tabAcciones)
        val tabAjustes = v.findViewById<TextView>(R.id.tabAjustes)
        val seekDelay = v.findViewById<SeekBar>(R.id.seekDelay)
        val tvDelayVal = v.findViewById<TextView>(R.id.tvDelayVal)
        val btnGuardar = v.findViewById<Button>(R.id.btnGuardar)
        val etApiKey = v.findViewById<EditText>(R.id.etApiKey)

        // Al tocar el input, habilitar foco
        input.setOnClickListener { enableInput() }
        etApiKey.setOnClickListener { enableInput() }

        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) disableInput()
        }

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
            scrollHistorial.visibility = if (tab == 0) View.VISIBLE else View.GONE
            panelAcciones.visibility = if (tab == 1) View.VISIBLE else View.GONE
            panelAjustes.visibility = if (tab == 2) View.VISIBLE else View.GONE
            val p = 0xFF6750A4.toInt(); val g = 0xFF79747E.toInt()
            tabHistorial.setTextColor(if (tab == 0) p else g)
            tabAcciones.setTextColor(if (tab == 1) p else g)
            tabAjustes.setTextColor(if (tab == 2) p else g)
        }
        tabHistorial.setOnClickListener { selectTab(0) }
        tabAcciones.setOnClickListener { selectTab(1) }
        tabAjustes.setOnClickListener { selectTab(2) }

        v.findViewById<Button>(R.id.btnWhatsApp).setOnClickListener {
            CorexAccessibilityService.openAppByName("WhatsApp")
        }
        v.findViewById<Button>(R.id.btnMaps).setOnClickListener {
            CorexAccessibilityService.openAppByName("Maps")
        }
        v.findViewById<Button>(R.id.btnYoutube).setOnClickListener {
            CorexAccessibilityService.openAppByName("YouTube")
        }

        seekDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) { tvDelayVal.text = "${p}ms" }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnGuardar.setOnClickListener {
            getSharedPreferences("corex_prefs", MODE_PRIVATE).edit()
                .putString("api_key", etApiKey.text.toString().trim())
                .putInt("delay", seekDelay.progress).apply()
            disableInput()
            addToHistory("Sistema", "Ajustes guardados ✓")
            mainHandler.post { updateHistory(tvHistorial) }
        }

        val prefs = getSharedPreferences("corex_prefs", MODE_PRIVATE)
        etApiKey.setText(prefs.getString("api_key", ""))
        seekDelay.progress = prefs.getInt("delay", 400)
        tvDelayVal.text = "${seekDelay.progress}ms"

        btnSend.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            input.setText("")
            disableInput()

            if (pendingAsk != null) {
                val cb = pendingAsk!!
                pendingAsk = null
                addToHistory("Tú", text)
                mainHandler.post { updateHistory(tvHistorial) }
                cb(text)
                return@setOnClickListener
            }

            addToHistory("Tú", text)
            mainHandler.post { updateHistory(tvHistorial) }
            processGoal(text, tvHistorial)
        }

        btnClose.setOnClickListener { stopSelf() }
    }

    private fun processGoal(goal: String, tvHistorial: TextView) {
        val prefs = getSharedPreferences("corex_prefs", MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isEmpty()) {
            addToHistory("Corex", "⚠ Configura tu API key de Groq en Ajustes")
            mainHandler.post { updateHistory(tvHistorial) }
            return
        }

        val delay = prefs.getInt("delay", 400).toLong()
        addToHistory("Corex", "Analizando...")
        mainHandler.post { updateHistory(tvHistorial) }

        scope.launch {
            var maxSteps = 15
            var done = false
            val stepHistory = mutableListOf<String>()

            while (!done && maxSteps > 0) {
                maxSteps--
                Thread.sleep(delay)

                val dump = CorexAccessibilityService.getDumpForAI()
                val learned = cache.getSummary()
                val histStr = stepHistory.takeLast(4).joinToString(" → ")

                var responded = false
                var decision = GroqClient.Decision("FAIL", "timeout")

                GroqClient.decide(goal, dump, histStr, learned, apiKey) { d ->
                    decision = d
                    responded = true
                }

                val timeout = System.currentTimeMillis() + 8000
                while (!responded && System.currentTimeMillis() < timeout) Thread.sleep(100)

                val action = decision.action
                val value = decision.value

                mainHandler.post {
                    addToHistory("▶", "$action: $value")
                    updateHistory(tvHistorial)
                }

                when {
                    action == "DONE" -> {
                        done = true
                        mainHandler.post { addToHistory("Corex", "✅ Listo"); updateHistory(tvHistorial) }
                    }
                    action == "ASK" -> {
                        done = true
                        val question = decision.askUser.ifEmpty { value }
                        mainHandler.post {
                            addToHistory("Corex", "❓ $question\n(Responde con el número del elemento o instrucción)")
                            updateHistory(tvHistorial)
                            pendingAsk = { answer ->
                                val currentPkg = CorexAccessibilityService.instance
                                    ?.rootInActiveWindow?.packageName?.toString() ?: ""
                                val idx = answer.toIntOrNull()
                                if (idx != null) {
                                    val elements = CorexAccessibilityService.getScreenElements()
                                    if (idx < elements.size) {
                                        val el = elements[idx]
                                        cache.learn(goal, currentPkg, idx,
                                            el.text.ifEmpty { el.contentDesc },
                                            el.bounds.centerX().toFloat(),
                                            el.bounds.centerY().toFloat())
                                        CorexAccessibilityService.tapElement(idx)
                                        addToHistory("Sistema", "✓ Aprendí: '$goal' → elemento $idx '${el.text.ifEmpty { el.contentDesc }}'")
                                        mainHandler.post { updateHistory(tvHistorial) }
                                        scope.launch {
                                            Thread.sleep(delay)
                                            processGoal(goal, tvHistorial)
                                        }
                                    }
                                } else {
                                    scope.launch { processGoal("$goal. Nota del usuario: $answer", tvHistorial) }
                                }
                            }
                        }
                    }
                    action == "OPEN_APP" -> {
                        val result = CorexAccessibilityService.openAppByName(value)
                        stepHistory.add("OPEN_APP($value)=$result")
                        Thread.sleep(1500)
                    }
                    action == "TAP" -> {
                        val idx = value.toIntOrNull()
                        if (idx != null) {
                            CorexAccessibilityService.tapElement(idx)
                            stepHistory.add("TAP($idx)")
                        }
                        Thread.sleep(500)
                    }
                    action == "TYPE" -> {
                        CorexAccessibilityService.typeText(value)
                        stepHistory.add("TYPE")
                        Thread.sleep(300)
                    }
                    action == "SCROLL_DOWN" -> { CorexAccessibilityService.scrollDown(); stepHistory.add("SCROLL_DOWN"); Thread.sleep(700) }
                    action == "SCROLL_UP" -> { CorexAccessibilityService.scrollUp(); stepHistory.add("SCROLL_UP"); Thread.sleep(700) }
                    action == "BACK" -> { CorexAccessibilityService.pressBack(); stepHistory.add("BACK"); Thread.sleep(500) }
                    action == "HOME" -> { CorexAccessibilityService.pressHome(); stepHistory.add("HOME"); Thread.sleep(500) }
                    else -> {
                        done = true
                        mainHandler.post { addToHistory("Corex", "⛔ No pude completar"); updateHistory(tvHistorial) }
                    }
                }

                if (stepHistory.size >= 3 && stepHistory.takeLast(3).all { it == stepHistory.last() }) {
                    done = true
                    mainHandler.post { addToHistory("Corex", "⚠ Sin progreso — sé más específico"); updateHistory(tvHistorial) }
                }
            }
        }
    }

    private fun addToHistory(who: String, msg: String) {
        history.add("$who: $msg")
        if (history.size > 50) history.removeAt(0)
    }

    private fun updateHistory(tv: TextView) {
        tv.text = history.takeLast(20).joinToString("\n")
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
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        try { overlayView?.let { windowManager?.removeView(it) } } catch (e: Exception) {}
    }
}
