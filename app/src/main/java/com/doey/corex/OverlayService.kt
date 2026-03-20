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

    // Mostrar picker de apps
    private fun showAppPicker(
        title: String,
        options: List<Pair<String, String>>, // label, packageName
        onChoice: (String) -> Unit
    ) {
        mainHandler.post {
            tvPickerTitle?.text = title
            pickerButtons?.removeAllViews()
            val noneBtn = Button(this).apply {
                text = "✕ Ninguna"
                setBackgroundResource(R.drawable.btn_tonal_bg)
                setPadding(24, 8, 24, 8)
                setOnClickListener {
                    hidePicker()
                    addLog("Corex", "⚠ No encontré la app correcta")
                }
            }
            pickerButtons?.addView(noneBtn)
            options.forEach { (label, pkg): Pair<String, String> ->
                val btn = Button(this).apply {
                    text = label
                    setBackgroundResource(R.drawable.btn_send_bg)
                    setTextColor(0xFFFFFFFF.toInt())
                    setPadding(24, 8, 24, 8)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(8, 0, 0, 0) }
                    layoutParams = lp
                    setOnClickListener {
                        hidePicker()
                        onChoice(pkg)
                    }
                }
                pickerButtons?.addView(btn)
            }
            panelPicker?.visibility = View.VISIBLE
        }
    }

    // Mostrar picker de elementos de pantalla
    private fun showElementPicker(
        title: String,
        elements: List<CorexAccessibilityService.ScreenElement>,
        onChoice: (CorexAccessibilityService.ScreenElement?) -> Unit
    ) {
        mainHandler.post {
            tvPickerTitle?.text = title
            pickerButtons?.removeAllViews()
            val noneBtn = Button(this).apply {
                text = "✕ No está"
                setBackgroundResource(R.drawable.btn_tonal_bg)
                setPadding(24, 8, 24, 8)
                setOnClickListener { hidePicker(); onChoice(null) }
            }
            pickerButtons?.addView(noneBtn)
            elements.take(12).forEach { el ->
                val label = el.text.ifEmpty { el.contentDesc }.take(20)
                val btn = Button(this).apply {
                    text = "${el.index}. $label"
                    setBackgroundResource(R.drawable.btn_send_bg)
                    setTextColor(0xFFFFFFFF.toInt())
                    setPadding(24, 8, 24, 8)
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(8, 0, 0, 0) }
                    layoutParams = lp
                    setOnClickListener { hidePicker(); onChoice(el) }
                }
                pickerButtons?.addView(btn)
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

        v.findViewById<Button>(R.id.btnWhatsApp).setOnClickListener { openAppWithPicker("WhatsApp") }
        v.findViewById<Button>(R.id.btnMaps).setOnClickListener { openAppWithPicker("Maps") }
        v.findViewById<Button>(R.id.btnYoutube).setOnClickListener { openAppWithPicker("YouTube") }

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
            disableInput()
            addLog("Tú", text)
            mainHandler.postDelayed({ processGoal(text) }, 400)
        }

        btnClose.setOnClickListener { stopSelf() }
    }

    private fun openAppWithPicker(name: String) {
        val prefs = getSharedPreferences("corex_prefs", MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isEmpty()) { addLog("Corex", "⚠ Configura API key"); return }
        scope.launch {
            val service = CorexAccessibilityService.instance
            val apps = service?.packageManager?.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            ) ?: return@launch
            val appList = apps.map {
                it.loadLabel(service.packageManager).toString() to it.activityInfo.packageName
            }
            val appListStr = appList.joinToString("\n") { "${it.first}|${it.second}" }

            GroqClient.chooseApp(name, appListStr, apiKey) { pkg ->
                if (pkg != "NONE" && pkg.isNotEmpty()) {
                    // Encontró el paquete directo
                    val intent = service.packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        applicationContext.startActivity(intent)
                        cache.learn("open_$name", pkg, -1, name, 0f, 0f)
                        addLog("✓ Abrí", "$name ($pkg)")
                    }
                } else {
                    // No encontró — mostrar picker con las más probables
                    val candidates = appList.filter {
                        it.first.contains(name, ignoreCase = true) ||
                        name.contains(it.first, ignoreCase = true)
                    }.take(8).ifEmpty { appList.take(8) }
                    showAppPicker("¿Cuál es $name?", candidates) { chosenPkg ->
                        val intent = service.packageManager.getLaunchIntentForPackage(chosenPkg)
                        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        intent?.let { applicationContext.startActivity(it) }
                        cache.learn("open_$name", chosenPkg, -1, name, 0f, 0f)
                        addLog("✓ Aprendí", "$name = $chosenPkg")
                    }
                }
            }
        }
    }

    private fun processGoal(goal: String) {
        val prefs = getSharedPreferences("corex_prefs", MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isEmpty()) { addLog("Corex", "⚠ Configura tu API key en Ajustes"); return }
        val delay = prefs.getInt("delay", 400).toLong()

        scope.launch {
            var maxSteps = 15
            var done = false
            val stepHistory = mutableListOf<String>()

            // Detectar si es abrir una app
            val openRegex = Regex("(?i)^(abre?|abrir|open|lanza?r?)\\s+(.+)$")
            val openMatch = openRegex.find(goal.trim())
            if (openMatch != null) {
                val appName = openMatch.groupValues[2].trim()
                // Verificar caché primero
                val cached = cache.getAll().firstOrNull {
                    it.key == "open_$appName" || it.key.contains(appName, ignoreCase = true)
                }
                if (cached != null && cached.packageName.isNotEmpty()) {
                    val service = CorexAccessibilityService.instance
                    val intent = service?.packageManager?.getLaunchIntentForPackage(cached.packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        applicationContext.startActivity(intent)
                        addLog("✓ Caché", "Abrí $appName")
                        return@launch
                    }
                }
                // No está en caché — usar IA + picker
                val service = CorexAccessibilityService.instance
                val apps = service?.packageManager?.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                ) ?: return@launch
                val appList = apps.map {
                    it.loadLabel(service.packageManager).toString() to it.activityInfo.packageName
                }
                val appListStr = appList.joinToString("\n") { "${it.first}|${it.second}" }
                addLog("🔍 IA buscando", appName)

                var responded = false
                GroqClient.chooseApp(appName, appListStr, apiKey) { pkg: String ->
                    responded = true
                    if (pkg != "NONE" && pkg.isNotEmpty() && pkg.contains(".")) {
                        val intent = service.packageManager.getLaunchIntentForPackage(pkg)
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            applicationContext.startActivity(intent)
                            cache.learn("open_$appName", pkg, -1, appName, 0f, 0f)
                            addLog("✓ IA eligió", "$appName → $pkg")
                        } else {
                            mainHandler.post { showAppPickerFromList(appName, appList) }
                        }
                    } else {
                        // Mostrar picker con candidatos
                        val candidates = appList.filter {
                            it.first.contains(appName, ignoreCase = true) ||
                            appName.contains(it.first, ignoreCase = true)
                        }.take(10).ifEmpty { appList.take(10) }
                        mainHandler.post { showAppPickerFromList(appName, appList, candidates) }
                    }
                }
                val t = System.currentTimeMillis() + 10000
                while (!responded && System.currentTimeMillis() < t) Thread.sleep(100)
                return@launch
            }

            // Tarea de navegación — loop percepción-acción
            while (!done && maxSteps > 0) {
                maxSteps--
                Thread.sleep(delay)

                val dump = CorexAccessibilityService.getDumpForAI()
                val elements = CorexAccessibilityService.getScreenElements()
                val learned = cache.getSummary()
                val histStr = stepHistory.takeLast(4).joinToString(" → ")

                addLog("📱 Veo", "${elements.size} elementos")

                var responded = false
                var decision = GroqClient.Decision("ASK", "No pude decidir")
                GroqClient.decide(goal, dump, histStr, learned, apiKey) { d ->
                    decision = d; responded = true
                }
                val timeout = System.currentTimeMillis() + 8000
                while (!responded && System.currentTimeMillis() < timeout) Thread.sleep(100)

                addLog("🤖 IA", "${decision.action}: ${decision.value}")

                when (decision.action) {
                    "DONE" -> { done = true; addLog("Corex", "✅ Listo") }
                    "ASK" -> {
                        done = true
                        // Mostrar picker con elementos visibles
                        showElementPicker(
                            decision.askUser.ifEmpty { "¿Cuál debo tocar?" },
                            elements
                        ) { chosen ->
                            if (chosen != null) {
                                val currentPkg = CorexAccessibilityService.instance
                                    ?.rootInActiveWindow?.packageName?.toString() ?: ""
                                cache.learn(
                                    goal, currentPkg, chosen.index,
                                    chosen.text.ifEmpty { chosen.contentDesc },
                                    chosen.bounds.centerX().toFloat(),
                                    chosen.bounds.centerY().toFloat()
                                )
                                CorexAccessibilityService.tapElement(chosen.index)
                                addLog("✓ Aprendí", "'$goal' → #${chosen.index} '${chosen.text.ifEmpty { chosen.contentDesc }}'")
                                scope.launch { Thread.sleep(delay); processGoal(goal) }
                            } else {
                                addLog("Corex", "⚠ Elemento no visible — intenta de otra forma")
                            }
                        }
                    }
                    "OPEN_APP" -> {
                        val service = CorexAccessibilityService.instance
                        val apps = service?.packageManager?.queryIntentActivities(
                            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                        ) ?: continue
                        val appList = apps.map {
                            it.loadLabel(service.packageManager).toString() to it.activityInfo.packageName
                        }
                        val appListStr = appList.joinToString("\n") { "${it.first}|${it.second}" }
                        var r = false
                        GroqClient.chooseApp(decision.value, appListStr, apiKey) { pkg: String ->
                            r = true
                            val intent = service.packageManager.getLaunchIntentForPackage(pkg)
                            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            intent?.let { applicationContext.startActivity(it) }
                            stepHistory.add("OPEN(${decision.value})")
                        }
                        val t = System.currentTimeMillis() + 8000
                        while (!r && System.currentTimeMillis() < t) Thread.sleep(100)
                        Thread.sleep(1500)
                    }
                    "TAP" -> {
                        val idx = decision.value.toIntOrNull()
                        if (idx != null && idx < elements.size) {
                            CorexAccessibilityService.tapElement(idx)
                            stepHistory.add("TAP($idx)")
                        } else {
                            // Índice inválido — mostrar picker
                            done = true
                            showElementPicker("¿Cuál debo tocar para: $goal?", elements) { chosen ->
                                if (chosen != null) {
                                    CorexAccessibilityService.tapElement(chosen.index)
                                    val pkg = CorexAccessibilityService.instance
                                        ?.rootInActiveWindow?.packageName?.toString() ?: ""
                                    cache.learn(goal, pkg, chosen.index,
                                        chosen.text.ifEmpty { chosen.contentDesc },
                                        chosen.bounds.centerX().toFloat(),
                                        chosen.bounds.centerY().toFloat())
                                    addLog("✓ Aprendí", "#${chosen.index}")
                                }
                            }
                        }
                        Thread.sleep(500)
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
                    showElementPicker("Estoy atascado en: $goal ¿Cuál toco?", elements) { chosen ->
                        if (chosen != null) {
                            CorexAccessibilityService.tapElement(chosen.index)
                            addLog("✓ Aprendí", "#${chosen.index}")
                        }
                    }
                }
            }
        }
    }

    private fun showAppPickerFromList(
        appName: String,
        allApps: List<Pair<String, String>>,
        candidates: List<Pair<String, String>> = allApps.filter {
            it.first.contains(appName, ignoreCase = true)
        }.take(10).ifEmpty { allApps.take(10) }
    ) {
        showAppPicker("¿Cuál es $appName?", candidates) { pkg ->
            val service = CorexAccessibilityService.instance
            val intent = service?.packageManager?.getLaunchIntentForPackage(pkg)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent?.let { applicationContext.startActivity(it) }
            cache.learn("open_$appName", pkg, -1, appName, 0f, 0f)
            addLog("✓ Aprendí", "$appName = $pkg")
        }
    }

    private fun addLog(who: String, msg: String) {
        history.add("$who: $msg")
        if (history.size > 60) history.removeAt(0)
        mainHandler.post {
            tvHistorial?.text = history.takeLast(25).joinToString("\n")
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
