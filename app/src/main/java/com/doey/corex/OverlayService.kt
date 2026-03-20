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
    private val history = mutableListOf<String>()
    private lateinit var params: WindowManager.LayoutParams
    private var tvHistorial: TextView? = null
    private var panelPicker: View? = null
    private var pickerButtons: LinearLayout? = null
    private var tvPickerTitle: TextView? = null

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
        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        windowManager?.updateViewLayout(overlayView, params)
    }

    private fun disableInput() {
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        windowManager?.updateViewLayout(overlayView, params)
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        overlayView?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    // Mostrar confirmación: "¿Es este X?" con Sí/No
    private fun showConfirmation(question: String, onYes: () -> Unit, onNo: () -> Unit) {
        mainHandler.post {
            tvPickerTitle?.text = question
            pickerButtons?.removeAllViews()
            pickerButtons?.addView(Button(this).apply {
                text = "✓ Sí"
                setBackgroundResource(R.drawable.btn_send_bg)
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(32, 8, 32, 8)
                setOnClickListener { hidePicker(); onYes() }
            })
            pickerButtons?.addView(Button(this).apply {
                text = "✕ No"
                setBackgroundResource(R.drawable.btn_tonal_bg)
                setPadding(32, 8, 32, 8)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(8, 0, 0, 0) }
                setOnClickListener { hidePicker(); onNo() }
            })
            panelPicker?.visibility = View.VISIBLE
        }
    }

    // Picker de apps
    private fun showAppPicker(title: String, options: List<Pair<String, String>>, onChoice: (String) -> Unit) {
        mainHandler.post {
            tvPickerTitle?.text = title
            pickerButtons?.removeAllViews()
            pickerButtons?.addView(Button(this).apply {
                text = "✕ Ninguna"
                setBackgroundResource(R.drawable.btn_tonal_bg)
                setPadding(24, 8, 24, 8)
                setOnClickListener { hidePicker() }
            })
            for (i in options.indices) {
                val label = options[i].first
                val pkg = options[i].second
                pickerButtons?.addView(Button(this).apply {
                    text = label
                    setBackgroundResource(R.drawable.btn_send_bg)
                    setTextColor(0xFFFFFFFF.toInt())
                    setPadding(24, 8, 24, 8)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(8, 0, 0, 0) }
                    setOnClickListener { hidePicker(); onChoice(pkg) }
                })
            }
            panelPicker?.visibility = View.VISIBLE
        }
    }

    // Picker de elementos de pantalla
    private fun showElementPicker(title: String, elements: List<ScreenElement>, onChoice: (ScreenElement?) -> Unit) {
        mainHandler.post {
            tvPickerTitle?.text = title
            pickerButtons?.removeAllViews()
            pickerButtons?.addView(Button(this).apply {
                text = "✕ No está"
                setBackgroundResource(R.drawable.btn_tonal_bg)
                setPadding(24, 8, 24, 8)
                setOnClickListener { hidePicker(); onChoice(null) }
            })
            for (i in 0 until minOf(elements.size, 12)) {
                val el = elements[i]
                val label = el.text.ifEmpty { el.contentDesc }.take(20)
                pickerButtons?.addView(Button(this).apply {
                    text = "${el.index}.$label"
                    setBackgroundResource(R.drawable.btn_send_bg)
                    setTextColor(0xFFFFFFFF.toInt())
                    setPadding(24, 8, 24, 8)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(8, 0, 0, 0) }
                    setOnClickListener { hidePicker(); onChoice(el) }
                })
            }
            panelPicker?.visibility = View.VISIBLE
        }
    }

    private fun hidePicker() {
        mainHandler.post { panelPicker?.visibility = View.GONE }
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
        panelPicker = v.findViewById(R.id.panelPicker)
        pickerButtons = v.findViewById(R.id.pickerButtons)
        tvPickerTitle = v.findViewById(R.id.tvPickerTitle)
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
                panelExpanded.animate().alpha(1f).setDuration(250).setInterpolator(DecelerateInterpolator()).start()
                btnExpand.setImageResource(android.R.drawable.arrow_down_float)
            } else {
                panelExpanded.animate().alpha(0f).setDuration(200).withEndAction { panelExpanded.visibility = View.GONE }.start()
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

        // Acciones rápidas — launcher directo
        v.findViewById<Button>(R.id.btnWhatsApp).setOnClickListener { launchApp("WhatsApp") }
        v.findViewById<Button>(R.id.btnMaps).setOnClickListener { launchApp("Maps") }
        v.findViewById<Button>(R.id.btnYoutube).setOnClickListener { launchApp("YouTube") }

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
            addLog("Sistema", "✓ Guardado")
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
            addLog("Tú", text)
            mainHandler.postDelayed({ processGoal(text) }, 400)
        }

        btnClose.setOnClickListener { stopSelf() }
    }

    // LAUNCHER INTERNO: busca en apps instaladas, IA elige, confirma con usuario
    private fun launchApp(name: String) {
        val prefs = getSharedPreferences("corex_prefs", MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        scope.launch {
            val apps = CorexAccessibilityService.getInstalledApps()
            if (apps.isEmpty()) { addLog("⚠", "Activa el servicio de accesibilidad"); return@launch }

            // Verificar caché primero
            val cached = cache.getAll().firstOrNull { it.key.equals("open_$name", ignoreCase = true) }
            if (cached != null && cached.packageName.isNotEmpty()) {
                val svc = CorexAccessibilityService.instance ?: return@launch
                val intent = svc.packageManager.getLaunchIntentForPackage(cached.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    applicationContext.startActivity(intent)
                    addLog("✓", "Abrí $name (caché)")
                    return@launch
                }
            }

            if (apiKey.isEmpty()) {
                // Sin API key — mostrar picker directamente
                val candidates = apps.filter { it.first.contains(name, ignoreCase = true) }.take(10).ifEmpty { apps.take(10) }
                showAppPicker("¿Cuál es $name?", candidates) { pkg: String ->
                    launchPkg(pkg, name)
                }
                return@launch
            }

            // Con API key — IA elige y pide confirmación
            val appListStr = apps.joinToString("\n") { "${it.first}|${it.second}" }
            GroqClient.chooseApp(name, appListStr, apiKey) { pkg: String ->
                val svc = CorexAccessibilityService.instance ?: return@chooseApp
                if (pkg != "NONE" && pkg.contains(".")) {
                    val appLabel = apps.firstOrNull { it.second == pkg }?.first ?: pkg
                    // Primera vez — pedir confirmación
                    showConfirmation("¿Es este $name?\n→ $appLabel", {
                        // Sí
                        launchPkg(pkg, name)
                        cache.learn("open_$name", pkg, -1, name, 0f, 0f)
                        addLog("✓ Aprendí", "$name = $appLabel")
                    }, {
                        // No — mostrar picker
                        val candidates = apps.filter { it.first.contains(name, ignoreCase = true) }.take(10).ifEmpty { apps.take(10) }
                        showAppPicker("¿Cuál es $name entonces?", candidates) { chosenPkg: String ->
                            launchPkg(chosenPkg, name)
                            cache.learn("open_$name", chosenPkg, -1, name, 0f, 0f)
                        }
                    })
                } else {
                    val candidates = apps.filter { it.first.contains(name, ignoreCase = true) }.take(10).ifEmpty { apps.take(10) }
                    showAppPicker("¿Cuál es $name?", candidates) { chosenPkg: String ->
                        launchPkg(chosenPkg, name)
                        cache.learn("open_$name", chosenPkg, -1, name, 0f, 0f)
                    }
                }
            }
        }
    }

    private fun launchPkg(pkg: String, name: String) {
        val svc = CorexAccessibilityService.instance ?: return
        val intent = svc.packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        applicationContext.startActivity(intent)
        addLog("✓ Abrí", name)
    }

    private fun processGoal(goal: String) {
        val prefs = getSharedPreferences("corex_prefs", MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isEmpty()) { addLog("Corex", "⚠ Configura tu API key en Ajustes"); return }
        val delay = prefs.getInt("delay", 400).toLong()

        scope.launch {
            // Detectar si es abrir una app
            val openRegex = Regex("(?i)^(abre?r?|open|lanzar?|inicia?)\\s+(.+)$")
            val openMatch = openRegex.find(goal.trim())
            if (openMatch != null) {
                val appName = openMatch.groupValues[2].trim()
                launchApp(appName)
                return@launch
            }

            // Tarea de navegación
            var maxSteps = 15
            var done = false
            val stepHistory = mutableListOf<String>()

            while (!done && maxSteps > 0) {
                maxSteps--
                Thread.sleep(delay)
                val dump = CorexAccessibilityService.getDumpForAI()
                val elements = CorexAccessibilityService.getScreenElements()
                val learned = cache.getSummary()
                val histStr = stepHistory.takeLast(4).joinToString(" → ")
                addLog("📱", "${elements.size} elementos")

                var responded = false
                var decision = GroqClient.Decision("ASK", "timeout")
                GroqClient.decide(goal, dump, histStr, learned, apiKey) { d: GroqClient.Decision ->
                    decision = d; responded = true
                }
                val timeout = System.currentTimeMillis() + 8000
                while (!responded && System.currentTimeMillis() < timeout) Thread.sleep(100)
                addLog("🤖", "${decision.action}: ${decision.value}")

                when (decision.action) {
                    "DONE" -> { done = true; addLog("Corex", "✅ Listo") }
                    "ASK" -> {
                        done = true
                        val question = decision.askUser.ifEmpty { "¿Cuál toco para: $goal?" }
                        showElementPicker(question, elements) { chosen: ScreenElement? ->
                            if (chosen != null) {
                                val pkg = CorexAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString() ?: ""
                                cache.learn(goal, pkg, chosen.index, chosen.text.ifEmpty { chosen.contentDesc }, chosen.bounds.centerX().toFloat(), chosen.bounds.centerY().toFloat())
                                CorexAccessibilityService.tapElement(chosen.index)
                                addLog("✓ Aprendí", "#${chosen.index}")
                                scope.launch { Thread.sleep(delay); processGoal(goal) }
                            }
                        }
                    }
                    "OPEN_APP" -> {
                        launchApp(decision.value)
                        stepHistory.add("OPEN(${decision.value})")
                        Thread.sleep(1500)
                    }
                    "TAP" -> {
                        val idx = decision.value.toIntOrNull()
                        if (idx != null && idx < elements.size) {
                            val el = elements[idx]
                            val label = el.text.ifEmpty { el.contentDesc }
                            // Pedir confirmación la primera vez
                            val pkg = CorexAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString() ?: ""
                            val cached = cache.find(goal, pkg)
                            if (cached != null) {
                                // Ya aprendido — tocar directo
                                CorexAccessibilityService.tapElement(idx)
                                stepHistory.add("TAP($idx)")
                                Thread.sleep(500)
                            } else {
                                done = true
                                showConfirmation("¿Toco este botón?\n→ \"$label\"", {
                                    // Sí
                                    cache.learn(goal, pkg, idx, label, el.bounds.centerX().toFloat(), el.bounds.centerY().toFloat())
                                    CorexAccessibilityService.tapElement(idx)
                                    addLog("✓ Aprendí", "#$idx '$label'")
                                    scope.launch { Thread.sleep(delay); processGoal(goal) }
                                }, {
                                    // No — mostrar picker
                                    showElementPicker("¿Cuál es el correcto?", elements) { chosen: ScreenElement? ->
                                        if (chosen != null) {
                                            cache.learn(goal, pkg, chosen.index, chosen.text.ifEmpty { chosen.contentDesc }, chosen.bounds.centerX().toFloat(), chosen.bounds.centerY().toFloat())
                                            CorexAccessibilityService.tapElement(chosen.index)
                                            addLog("✓ Aprendí", "#${chosen.index}")
                                            scope.launch { Thread.sleep(delay); processGoal(goal) }
                                        }
                                    }
                                })
                            }
                        } else {
                            done = true
                            showElementPicker("¿Cuál toco para: $goal?", elements) { chosen: ScreenElement? ->
                                if (chosen != null) {
                                    CorexAccessibilityService.tapElement(chosen.index)
                                    val pkg = CorexAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString() ?: ""
                                    cache.learn(goal, pkg, chosen.index, chosen.text.ifEmpty { chosen.contentDesc }, chosen.bounds.centerX().toFloat(), chosen.bounds.centerY().toFloat())
                                    addLog("✓ Aprendí", "#${chosen.index}")
                                }
                            }
                        }
                    }
                    "TYPE" -> { CorexAccessibilityService.typeText(decision.value); stepHistory.add("TYPE"); Thread.sleep(300) }
                    "SCROLL_DOWN" -> { CorexAccessibilityService.scrollDown(); stepHistory.add("SCROLL_DOWN"); Thread.sleep(700) }
                    "SCROLL_UP" -> { CorexAccessibilityService.scrollUp(); stepHistory.add("SCROLL_UP"); Thread.sleep(700) }
                    "BACK" -> { CorexAccessibilityService.pressBack(); stepHistory.add("BACK"); Thread.sleep(500) }
                    "HOME" -> { CorexAccessibilityService.pressHome(); stepHistory.add("HOME"); Thread.sleep(500) }
                    else -> { done = true; addLog("Corex", "⛔ No pude completar") }
                }

                if (stepHistory.size >= 3 && stepHistory.takeLast(3).all { it == stepHistory.last() }) {
                    done = true
                    showElementPicker("Atascado — ¿Cuál toco?", elements) { chosen: ScreenElement? ->
                        if (chosen != null) { CorexAccessibilityService.tapElement(chosen.index); addLog("✓", "#${chosen.index}") }
                    }
                }
            }
        }
    }

    private fun addLog(who: String, msg: String) {
        history.add("$who: $msg")
        if (history.size > 60) history.removeAt(0)
        mainHandler.post { tvHistorial?.text = history.takeLast(25).joinToString("\n") }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Corex", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(this, 0,
            Intent(this, OverlayService::class.java).apply { action = "STOP" }, PendingIntent.FLAG_IMMUTABLE)
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
