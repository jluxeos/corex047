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
    private lateinit var tvHistorial: TextView

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
        tvHistorial = v.findViewById(R.id.tvHistorial)
        val tabHistorial = v.findViewById<TextView>(R.id.tabHistorial)
        val tabAcciones = v.findViewById<TextView>(R.id.tabAcciones)
        val tabAjustes = v.findViewById<TextView>(R.id.tabAjustes)
        val seekDelay = v.findViewById<SeekBar>(R.id.seekDelay)
        val tvDelayVal = v.findViewById<TextView>(R.id.tvDelayVal)
        val btnGuardar = v.findViewById<Button>(R.id.btnGuardar)
        val etApiKey = v.findViewById<EditText>(R.id.etApiKey)

        input.setOnClickListener { enableInput() }
        etApiKey.setOnClickListener { enableInput() }
        input.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) disableInput() }

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

        v.findViewById<Button>(R.id.btnWhatsApp).setOnClickListener { launchByName("WhatsApp") }
        v.findViewById<Button>(R.id.btnMaps).setOnClickListener { launchByName("Maps") }
        v.findViewById<Button>(R.id.btnYoutube).setOnClickListener { launchByName("YouTube") }

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
            addLog("Sistema", "✓ Ajustes guardados")
        }

        val prefs = getSharedPreferences("corex_prefs", MODE_PRIVATE)
        etApiKey.setText(prefs.getString("api_key", ""))
        seekDelay.progress = prefs.getInt("delay", 400)
        tvDelayVal.text = "${seekDelay.progress}ms"

        btnSend.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            input.setText("")
            // IMPORTANTE: deshabilitar input ANTES de tomar el dump
            disableInput()

            if (pendingAsk != null) {
                val cb = pendingAsk!!
                pendingAsk = null
                addLog("Tú", text)
                cb(text)
                return@setOnClickListener
            }
            addLog("Tú", text)
            // Esperar 400ms para que el teclado cierre y la pantalla sea visible
            mainHandler.postDelayed({ processGoal(text) }, 400)
        }

        btnClose.setOnClickListener { stopSelf() }
    }

    private fun launchByName(name: String) {
        val prefs = getSharedPreferences("corex_prefs", MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isEmpty()) { addLog("Corex", "⚠ Configura API key"); return }
        addLog("Corex", "Abriendo $name...")
        scope.launch {
            val appList = CorexAccessibilityService.getInstalledApps()
            GroqClient.chooseApp(name, appList, apiKey) { pkg ->
                if (pkg != "NONE" && pkg.isNotEmpty()) {
                    val service = CorexAccessibilityService.instance
                    val intent = service?.packageManager?.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        applicationContext.startActivity(intent)
                        mainHandler.post { addLog("Corex", "✓ Abrí $name") }
                    } else {
                        mainHandler.post { addLog("Corex", "⚠ $name no instalado") }
                    }
                } else {
                    mainHandler.post { addLog("Corex", "⚠ No encontré $name") }
                }
            }
        }
    }

    private fun processGoal(goal: String) {
        val prefs = getSharedPreferences("corex_prefs", MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isEmpty()) { addLog("Corex", "⚠ Configura tu API key de Groq en Ajustes"); return }
        val delay = prefs.getInt("delay", 400).toLong()

        scope.launch {
            var maxSteps = 15
            var done = false
            val stepHistory = mutableListOf<String>()

            while (!done && maxSteps > 0) {
                maxSteps--
                Thread.sleep(delay)

                // Tomar dump DESPUÉS de que el teclado cierre
                val dump = CorexAccessibilityService.getDumpForAI()
                val learned = cache.getSummary()
                val histStr = stepHistory.takeLast(4).joinToString(" → ")

                // Mostrar dump en historial para debug
                mainHandler.post { addLog("📱 Pantalla", dump.take(200)) }

                // Caso especial: si el goal es abrir una app, usar lista de paquetes
                if (goal.lowercase().startsWith("abre") || goal.lowercase().startsWith("abrir") ||
                    goal.lowercase().startsWith("open")) {
                    val appName = goal.replace(Regex("(?i)abre?\\s*"), "").trim()
                    val appList = CorexAccessibilityService.getInstalledApps()
                    mainHandler.post { addLog("🔍 Buscando", appName) }
                    var responded = false
                    GroqClient.chooseApp(appName, appList, apiKey) { pkg ->
                        responded = true
                        if (pkg != "NONE" && pkg.isNotEmpty()) {
                            val service = CorexAccessibilityService.instance
                            val intent = service?.packageManager?.getLaunchIntentForPackage(pkg)
                            if (intent != null) {
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                applicationContext.startActivity(intent)
                                mainHandler.post { addLog("✓ IA eligió", pkg) }
                            }
                        } else {
                            mainHandler.post { addLog("⚠", "No encontré $appName") }
                        }
                    }
                    val t = System.currentTimeMillis() + 8000
                    while (!responded && System.currentTimeMillis() < t) Thread.sleep(100)
                    done = true
                    continue
                }

                var responded = false
                var decision = GroqClient.Decision("FAIL", "timeout")
                GroqClient.decide(goal, dump, histStr, learned, apiKey) { d ->
                    decision = d; responded = true
                }
                val timeout = System.currentTimeMillis() + 8000
                while (!responded && System.currentTimeMillis() < timeout) Thread.sleep(100)

                val action = decision.action
                val value = decision.value
                mainHandler.post { addLog("🤖 IA", "$action: $value") }

                when {
                    action == "DONE" -> {
                        done = true
                        mainHandler.post { addLog("Corex", "✅ Listo") }
                    }
                    action == "ASK" -> {
                        done = true
                        val question = decision.askUser.ifEmpty { value }
                        mainHandler.post {
                            addLog("Corex", "❓ $question\n(Responde con número del elemento o instrucción)")
                            pendingAsk = { answer ->
                                val idx = answer.toIntOrNull()
                                val currentPkg = CorexAccessibilityService.instance
                                    ?.rootInActiveWindow?.packageName?.toString() ?: ""
                                if (idx != null) {
                                    val elements = CorexAccessibilityService.getScreenElements()
                                    if (idx < elements.size) {
                                        val el = elements[idx]
                                        cache.learn(goal, currentPkg, idx,
                                            el.text.ifEmpty { el.contentDesc },
                                            el.bounds.centerX().toFloat(),
                                            el.bounds.centerY().toFloat())
                                        CorexAccessibilityService.tapElement(idx)
                                        addLog("Sistema", "✓ Aprendí: '$goal' → #$idx '${el.text.ifEmpty { el.contentDesc }}'")
                                        scope.launch { Thread.sleep(delay); processGoal(goal) }
                                    }
                                } else {
                                    scope.launch { processGoal("$goal. Usuario dice: $answer") }
                                }
                            }
                        }
                    }
                    action == "OPEN_APP" -> {
                        val appList = CorexAccessibilityService.getInstalledApps()
                        var r = false
                        GroqClient.chooseApp(value, appList, apiKey) { pkg ->
                            r = true
                            if (pkg != "NONE") {
                                val service = CorexAccessibilityService.instance
                                val intent = service?.packageManager?.getLaunchIntentForPackage(pkg)
                                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                intent?.let { applicationContext.startActivity(it) }
                                stepHistory.add("OPEN_APP($value)=OK")
                                mainHandler.post { addLog("✓ Abrí", "$value ($pkg)") }
                            } else {
                                stepHistory.add("OPEN_APP($value)=FAIL")
                            }
                        }
                        val t = System.currentTimeMillis() + 8000
                        while (!r && System.currentTimeMillis() < t) Thread.sleep(100)
                        Thread.sleep(1500)
                    }
                    action == "TAP" -> {
                        val idx = value.toIntOrNull()
                        if (idx != null) { CorexAccessibilityService.tapElement(idx); stepHistory.add("TAP($idx)") }
                        Thread.sleep(500)
                    }
                    action == "TYPE" -> { CorexAccessibilityService.typeText(value); stepHistory.add("TYPE"); Thread.sleep(300) }
                    action == "SCROLL_DOWN" -> { CorexAccessibilityService.scrollDown(); stepHistory.add("SCROLL_DOWN"); Thread.sleep(700) }
                    action == "SCROLL_UP" -> { CorexAccessibilityService.scrollUp(); stepHistory.add("SCROLL_UP"); Thread.sleep(700) }
                    action == "BACK" -> { CorexAccessibilityService.pressBack(); stepHistory.add("BACK"); Thread.sleep(500) }
                    action == "HOME" -> { CorexAccessibilityService.pressHome(); stepHistory.add("HOME"); Thread.sleep(500) }
                    else -> { done = true; mainHandler.post { addLog("Corex", "⛔ No pude completar") } }
                }

                if (stepHistory.size >= 3 && stepHistory.takeLast(3).all { it == stepHistory.last() }) {
                    done = true
                    mainHandler.post { addLog("Corex", "⚠ Sin progreso — sé más específico") }
                }
            }
        }
    }

    private fun addLog(who: String, msg: String) {
        history.add("$who: $msg")
        if (history.size > 60) history.removeAt(0)
        if (::tvHistorial.isInitialized) {
            mainHandler.post { tvHistorial.text = history.takeLast(25).joinToString("\n") }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Corex", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(this, 0,
            Intent(this, OverlayService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Corex").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .addAction(android.R.drawable.ic_delete, "Cerrar", stopIntent)
            .setOngoing(true).build()
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
