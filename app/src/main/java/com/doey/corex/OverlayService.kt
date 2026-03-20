package com.doey.corex

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.gridlayout.widget.GridLayout
import kotlinx.coroutines.*

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isExpanded = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var cache: LearningCache
    private lateinit var macroEngine: MacroEngine
    private lateinit var numberOverlay: NumberOverlay
    private val recordingSteps = mutableListOf<MacroEngine.MacroStep>()
    private var recordingKey = ""
    private var isRecording = false
    private val history = mutableListOf<String>()
    private lateinit var params: WindowManager.LayoutParams
    private var tvHistorial: TextView? = null
    private var tvDebug: TextView? = null
    private var tvChat: TextView? = null
    private var scrollChat: android.widget.ScrollView? = null
    private val chatMessages = mutableListOf<String>()
    private var panelPicker: View? = null
    private var pickerGrid: GridLayout? = null
    private var pickerButtons: LinearLayout? = null
    private var pickerScroll: View? = null
    private var tvPickerTitle: TextView? = null

    companion object {
        const val CHANNEL_ID = "corex_overlay"
        const val NOTIF_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        cache = LearningCache(this)
        macroEngine = MacroEngine(this)
        numberOverlay = NumberOverlay(this)
        macroEngine = MacroEngine(this)
        numberOverlay = NumberOverlay(this)
        DebugLog.init(this)
        CrashHandler.install(this)
        DebugLog.log("Service", "onCreate")
        mainHandler.postDelayed({ addChat("Hola! Soy Corex. Cuéntame qué necesitas y lo resuelvo") }, 1000)
        try {
            createNotificationChannel()
            startForeground(NOTIF_ID, buildNotification("Corex activo"))
            setupOverlay()
        } catch (e: Exception) {
            DebugLog.logError("Service.onCreate", e)
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

    // Grid de apps con iconos
    private fun showAppGrid(title: String, apps: List<Triple<String, String, Drawable?>>, onChoice: (String) -> Unit) {
        mainHandler.post {
            tvPickerTitle?.text = "$title (${apps.size} apps)"
            pickerGrid?.removeAllViews()
            pickerScroll?.visibility = View.GONE
            pickerGrid?.visibility = View.VISIBLE

            // Cancelar
            pickerGrid?.addView(Button(this).apply {
                text = "✕ Cancelar"
                setBackgroundResource(R.drawable.btn_tonal_bg)
                setPadding(16, 8, 16, 8)
                setOnClickListener { hidePicker() }
            }, GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply { width = 0; setMargins(4, 4, 4, 4) })

            // Todas las apps
            for (app in apps) {
                val label = app.first
                val pkg = app.second
                val icon = app.third
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    setPadding(4, 8, 4, 8)
                    setOnClickListener { hidePicker(); onChoice(pkg) }
                }
                cell.addView(ImageView(this).apply {
                    if (icon != null) setImageDrawable(icon)
                    else setImageResource(android.R.drawable.sym_def_app_icon)
                    layoutParams = LinearLayout.LayoutParams(56.dpToPx(), 56.dpToPx())
                    scaleType = ImageView.ScaleType.FIT_CENTER
                })
                cell.addView(TextView(this).apply {
                    text = label.take(12)
                    textSize = 9f
                    gravity = android.view.Gravity.CENTER
                    maxLines = 2
                })
                pickerGrid?.addView(cell, GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                ).apply { width = 0; setMargins(2, 2, 2, 2) })
            }
            panelPicker?.visibility = View.VISIBLE
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    // Confirmación Sí/No
    private fun showConfirmation(question: String, onYes: () -> Unit, onNo: () -> Unit) {
        mainHandler.post {
            tvPickerTitle?.text = question
            pickerGrid?.removeAllViews()
            pickerGrid?.visibility = View.VISIBLE
            pickerScroll?.visibility = View.GONE
            pickerGrid?.addView(Button(this).apply {
                text = "✓ Sí"
                setBackgroundResource(R.drawable.btn_send_bg)
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(32, 8, 32, 8)
                setOnClickListener { hidePicker(); onYes() }
            })
            pickerGrid?.addView(Button(this).apply {
                text = "✕ No"
                setBackgroundResource(R.drawable.btn_tonal_bg)
                setPadding(32, 8, 32, 8)
                setOnClickListener { hidePicker(); onNo() }
            })
            panelPicker?.visibility = View.VISIBLE
        }
    }

    // Picker de elementos de pantalla
    private fun showElementPicker(title: String, elements: List<ScreenElement>, onChoice: (ScreenElement?) -> Unit) {
        mainHandler.post {
            tvPickerTitle?.text = title
            pickerGrid?.removeAllViews()
            pickerGrid?.visibility = View.GONE
            pickerScroll?.visibility = View.VISIBLE
            pickerButtons?.removeAllViews()
            pickerButtons?.addView(Button(this).apply {
                text = "✕ No está"
                setBackgroundResource(R.drawable.btn_tonal_bg)
                setPadding(24, 8, 24, 8)
                setOnClickListener { hidePicker(); onChoice(null) }
            })
            for (i in 0 until minOf(elements.size, 15)) {
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
        val scrollHistorial = v.findViewById<View>(R.id.scrollHistorial)
        val scrollDebug = v.findViewById<View>(R.id.scrollDebug)
        val panelAjustes = v.findViewById<View>(R.id.panelAjustes)
        tvHistorial = v.findViewById(R.id.tvHistorial)
        tvChat = v.findViewById(R.id.tvChat)
        scrollChat = v.findViewById(R.id.scrollChat)
        tvDebug = v.findViewById(R.id.tvDebug)
        panelPicker = v.findViewById(R.id.panelPicker)
        pickerGrid = v.findViewById(R.id.pickerGrid)
        pickerButtons = v.findViewById(R.id.pickerButtons)
        pickerScroll = v.findViewById(R.id.pickerScroll)
        tvPickerTitle = v.findViewById(R.id.tvPickerTitle)
        val tabChat = v.findViewById<TextView>(R.id.tabChat)
        val tabHistorial = v.findViewById<TextView>(R.id.tabHistorial)
        val tabDebug = v.findViewById<TextView>(R.id.tabDebug)
        val tabAjustes = v.findViewById<TextView>(R.id.tabAjustes)
        val seekDelay = v.findViewById<SeekBar>(R.id.seekDelay)
        val tvDelayVal = v.findViewById<TextView>(R.id.tvDelayVal)
        val btnGuardar = v.findViewById<Button>(R.id.btnGuardar)
        val btnExport = v.findViewById<Button>(R.id.btnExport)
        val etApiKey = v.findViewById<EditText>(R.id.etApiKey)

        input.setOnClickListener {
            enableInput()
            input.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
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
            scrollDebug.visibility = if (tab == 1) View.VISIBLE else View.GONE
            panelAjustes.visibility = if (tab == 2) View.VISIBLE else View.GONE
            val p = 0xFF6750A4.toInt(); val g = 0xFF79747E.toInt()
            tabHistorial.setTextColor(if (tab == 0) p else g)
            tabDebug.setTextColor(if (tab == 1) p else g)
            tabAjustes.setTextColor(if (tab == 2) p else g)
        }
        tabHistorial.setOnClickListener { selectTab(0) }
        tabDebug.setOnClickListener { selectTab(1); val cl = CrashHandler.readLastCrash(this@OverlayService); tvDebug?.text = "CRASH:" + cl + "DEBUG:" + DebugLog.getLastEntries(30) }
        tabAjustes.setOnClickListener { selectTab(2) }

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

        btnExport.setOnClickListener {
            try {
                val file = cache.exportToFile()
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, android.net.Uri.fromFile(file))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(Intent.createChooser(intent, "Exportar caché").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            } catch (e: Exception) { DebugLog.logError("Export", e) }
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
            DebugLog.log("Input", text)

            if (isRecording) {
                // Modo grabacion
                when {
                    text.equals("listo", ignoreCase = true) || text.equals("fin", ignoreCase = true) -> {
                        stopRecording()
                    }
                    text.lowercase().startsWith("abre ") || text.lowercase().startsWith("abrir ") -> {
                        val appName = text.replace(Regex("(?i)^abri?r?\\s+"), "").trim()
                        val cached = cache.getAll().firstOrNull { it.key.equals("open_$appName", ignoreCase = true) }
                        if (cached != null) {
                            recordingSteps.add(MacroEngine.MacroStep("OPEN_APP", cached.packageName, label = appName))
                            addLog("🔴 +Paso", "Abrir $appName")
                            mainHandler.postDelayed({ launchApp(appName) }, 400)
                        } else {
                            addLog("⚠", "Primero enséñame a abrir $appName sin grabar")
                        }
                    }
                    text.lowercase().startsWith("escribe ") || text.lowercase().startsWith("escrib ") -> {
                        val msg = text.replace(Regex("(?i)^escrib[ei]r?\\s+"), "").trim()
                        recordingSteps.add(MacroEngine.MacroStep("TYPE", msg, label = "escribir: $msg"))
                        addLog("🔴 +Paso", "Escribir: $msg")
                        mainHandler.postDelayed({ CorexAccessibilityService.typeText(msg) }, 400)
                    }
                    text.equals("toca", ignoreCase = true) || text.equals("tocar", ignoreCase = true) -> {
                        // Mostrar overlay de numeros para elegir elemento
                        mainHandler.postDelayed({
                            showNumberOverlay { el ->
                                recordingSteps.add(MacroEngine.MacroStep("TAP", el.text.ifEmpty { el.contentDesc },
                                    el.bounds.centerX().toFloat(), el.bounds.centerY().toFloat(),
                                    el.text.ifEmpty { el.contentDesc }))
                                addLog("🔴 +Paso", "Tocar: ${el.text.ifEmpty { el.contentDesc }}")
                                CorexAccessibilityService.tapElement(el.index)
                            }
                        }, 400)
                    }
                    text.equals("atras", ignoreCase = true) || text.equals("atrás", ignoreCase = true) -> {
                        recordingSteps.add(MacroEngine.MacroStep("BACK", "", label = "atrás"))
                        addLog("🔴 +Paso", "Atrás")
                        CorexAccessibilityService.pressBack()
                    }
                    text.equals("inicio", ignoreCase = true) || text.equals("home", ignoreCase = true) -> {
                        recordingSteps.add(MacroEngine.MacroStep("HOME", "", label = "inicio"))
                        addLog("🔴 +Paso", "Inicio")
                        CorexAccessibilityService.pressHome()
                    }
                    text.equals("scroll", ignoreCase = true) || text.equals("bajar", ignoreCase = true) -> {
                        recordingSteps.add(MacroEngine.MacroStep("SCROLL_DOWN", "", label = "scroll abajo"))
                        addLog("🔴 +Paso", "Scroll abajo")
                        CorexAccessibilityService.scrollDown()
                    }
                    else -> {
                        addLog("💡", "Comandos: 'abre X', 'escribe X', 'toca', 'atras', 'scroll', 'listo'")
                    }
                }
                return@setOnClickListener
            }

            mainHandler.postDelayed({ processGoal(text) }, 400)
        }

        btnClose.setOnClickListener { stopSelf() }
    }

    private fun getAppsWithIcons(): List<Triple<String, String, Drawable?>> {
        // Usar AccessibilityService que tiene acceso completo al sistema
        val fromService = CorexAccessibilityService.getInstalledAppsWithIcons()
        if (fromService.isNotEmpty()) {
            DebugLog.log("getApps", "AccessibilityService: ${fromService.size} apps")
            return fromService
        }
        // Fallback: packageManager directo
        return try {
            val pm = applicationContext.packageManager
            val apps = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            )
            DebugLog.log("getApps", "Fallback: ${apps.size} apps")
            apps.map { info ->
                Triple(
                    info.loadLabel(pm).toString(),
                    info.activityInfo.packageName,
                    try { info.loadIcon(pm) } catch (e: Exception) { null }
                )
            }.sortedBy { it.first }
        } catch (e: Exception) {
            DebugLog.logError("getAppsWithIcons", e)
            emptyList()
        }
    }

    private fun launchApp(name: String) {
        val prefs = getSharedPreferences("corex_prefs", MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        DebugLog.log("launchApp", "Buscando: $name")
        addChat("Voy a abrir $name, dame un seg...")

        scope.launch {
            val cached = cache.getAll().firstOrNull { it.key.equals("open_$name", ignoreCase = true) || it.key.equals("open_${name.lowercase()}", ignoreCase = true) }
            if (cached != null && cached.packageName.isNotEmpty()) {
                DebugLog.log("launchApp", "Cache hit: ${cached.key} -> ${cached.packageName}")
                try {
                    val intent = applicationContext.packageManager.getLaunchIntentForPackage(cached.packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        applicationContext.startActivity(intent)
                        addLog("✓ Abrí", name)
                        return@launch
                    } else {
                        DebugLog.log("launchApp", "Intent null, borrando cache corrupto")
                        cache.forget("open_$name", cached.packageName)
                    }
                } catch (e: Exception) {
                    DebugLog.logError("launchApp.cache", e)
                    addLog("⛔", e.message ?: "")
                }
            }

            val apps = getAppsWithIcons()
            if (apps.isEmpty()) { addLog("⚠", "No pude obtener lista de apps"); return@launch }
            DebugLog.log("launchApp", "Apps encontradas: ${apps.size}")

            if (apiKey.isNotEmpty()) {
                val appListStr = apps.joinToString("\n") { "${it.first}|${it.second}" }
                DebugLog.log("launchApp", "Consultando IA para: $name")
                GroqClient.chooseApp(name, appListStr, apiKey) { pkg: String ->
                    DebugLog.log("launchApp", "IA eligió: $pkg")
                    @Suppress("UNUSED_VARIABLE") val svc = CorexAccessibilityService.instance
                    if (pkg != "NONE" && pkg.contains(".")) {
                        val appLabel = apps.firstOrNull { it.second == pkg }?.first ?: pkg
                        showConfirmation("¿Es este $name?\n→ $appLabel", {
                            launchPkg(pkg, name) 
                            cache.learn("open_$name", pkg, -1, name, 0f, 0f)
                            addLog("✓ Aprendí", "$name = $appLabel")
                        }, {
                            showAppGrid("¿Cuál es $name? (todas)", apps) { chosenPkg: String ->
                                launchPkg(chosenPkg, name) 
                                val label = apps.firstOrNull { it.second == chosenPkg }?.first ?: chosenPkg
                                cache.learn("open_$name", chosenPkg, -1, name, 0f, 0f)
                                addLog("✓ Aprendí", "$name = $label")
                            }
                        })
                    } else {
                        showAppGrid("¿Cuál es $name? (todas)", apps) { chosenPkg: String ->
                            launchPkg(chosenPkg, name) 
                            val label = apps.firstOrNull { it.second == chosenPkg }?.first ?: chosenPkg
                            cache.learn("open_$name", chosenPkg, -1, name, 0f, 0f)
                            addLog("✓ Aprendí", "$name = $label")
                        }
                    }
                }
            } else {
                showAppGrid("¿Cuál es $name? (todas)", apps) { chosenPkg: String ->
                    launchPkg(chosenPkg, name)
                    val label = apps.firstOrNull { it.second == chosenPkg }?.first ?: chosenPkg
                    cache.learn("open_$name", chosenPkg, -1, name, 0f, 0f)
                    addLog("✓ Aprendí", "$name = $label")
                }
            }
        }
    }

    private fun launchPkg(pkg: String, name: String) {
        scope.launch(Dispatchers.IO) {
            val ok = CorexAccessibilityService.launchPackage(pkg, applicationContext)
            if (ok) {
                addLog("✓ Abrí", name)
                DebugLog.log("launchPkg", "OK: $pkg")
            } else {
                // Fallback directo
                try {
                    val intent = applicationContext.packageManager.getLaunchIntentForPackage(pkg)
                    intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent?.let { applicationContext.startActivity(it) }
                    addLog("✓ Abrí", "$name (fallback)")
                } catch (e: Exception) {
                    DebugLog.logError("launchPkg", e)
                    addLog("⛔", "No pude abrir $name")
                }
            }
        }
    }

    private fun processGoal(goal: String) {
        val prefs = getSharedPreferences("corex_prefs", MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isEmpty()) { addLog("Corex", "⚠ Configura tu API key en Ajustes"); return }
        val delay = prefs.getInt("delay", 400).toLong()
        DebugLog.log("processGoal", goal)

        scope.launch {
            // Verificar macro guardada
            addChat("Ok, voy a ver qué puedo hacer con: \"$goal\"")
            val existingMacro = macroEngine.find(goal)
            if (existingMacro != null) {
                addChat("Tengo esto memorizado, lo hago sin internet")
            addLog("⚡ Macro", "Ejecutando offline: ${existingMacro.key}")
                macroEngine.execute(existingMacro, delay,
                    onStep = { msg -> addLog("▶", msg) },
                    onDone = { ok -> if (ok) addLog("✅", "Listo") else addLog("⛔", "Macro falló") }
                )
                return@launch
            }

            val openRegex = Regex("(?i)^(abre?r?|open|lanzar?|inicia?)\\s+(.+)$")
            val openMatch = openRegex.find(goal.trim())
            if (openMatch != null) {
                launchApp(openMatch.groupValues[2].trim())
                return@launch
            }

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

                DebugLog.log("Dump", dump.take(500))
                addLog("📱", "${elements.size} elementos")
                if (elements.isEmpty()) addChat("Hmm, no veo nada en pantalla, dame un momento...")

                var responded = false
                var decision = GroqClient.Decision("ASK", "timeout")
                GroqClient.decide(goal, dump, histStr, learned, apiKey) { d: GroqClient.Decision ->
                    decision = d; responded = true
                }
                val timeout = System.currentTimeMillis() + 8000
                while (!responded && System.currentTimeMillis() < timeout) Thread.sleep(100)

                DebugLog.log("IA→", "${decision.action}: ${decision.value}")
                addLog("🤖", "${decision.action}: ${decision.value}")

                when (decision.action) {
                    "DONE" -> { done = true; addLog("Corex", "✅ Listo"); addChat("Listo, ya terminé lo que me pediste") }
                    "ASK" -> {
                        done = true
                        val question = decision.askUser.ifEmpty { "¿Cuál toco para: $goal?" }
                        showElementPicker(question, elements) { chosen: ScreenElement? ->
                            if (chosen != null) {
                                val pkg = CorexAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString() ?: ""
                                cache.learn(goal, pkg, chosen.index, chosen.text.ifEmpty { chosen.contentDesc }, chosen.bounds.centerX().toFloat(), chosen.bounds.centerY().toFloat())
                                CorexAccessibilityService.tapElement(chosen.index)
                                addLog("✓ Aprendí", "#${chosen.index}")
                                DebugLog.log("Learn", "$goal → #${chosen.index}")
                                scope.launch { Thread.sleep(delay); processGoal(goal) }
                            }
                        }
                    }
                    "OPEN_APP" -> {
                        addChat("Voy a abrir ${decision.value} para continuar")
                        launchApp(decision.value)
                        stepHistory.add("OPEN(${decision.value})")
                        Thread.sleep(1500)
                    }
                    "TAP" -> {
                        val idx = decision.value.toIntOrNull()
                        numberOverlay.show(elements) {}
                        Thread.sleep(600)
                        if (idx != null && idx < elements.size) {
                            val el = elements[idx]
                            val label = el.text.ifEmpty { el.contentDesc }
                            val pkg = CorexAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString() ?: ""
                            val cached = cache.find(goal, pkg)
                            if (cached != null) {
                                CorexAccessibilityService.tapElement(idx)
                                stepHistory.add("TAP($idx)")
                            } else {
                                done = true
                                showConfirmation("¿Toco este?\n→ \"$label\"", {
                                    cache.learn(goal, pkg, idx, label, el.bounds.centerX().toFloat(), el.bounds.centerY().toFloat())
                                    CorexAccessibilityService.tapElement(idx)
                                    addLog("✓ Aprendí", "#$idx '$label'")
                                    DebugLog.log("Learn", "$goal → TAP #$idx '$label'")
                                    scope.launch { Thread.sleep(delay); processGoal(goal) }
                                }, {
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
                                    val pkg = CorexAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString() ?: ""
                                    CorexAccessibilityService.tapElement(chosen.index)
                                    cache.learn(goal, pkg, chosen.index, chosen.text.ifEmpty { chosen.contentDesc }, chosen.bounds.centerX().toFloat(), chosen.bounds.centerY().toFloat())
                                    addLog("✓ Aprendí", "#${chosen.index}")
                                }
                            }
                        }
                        Thread.sleep(500)
                    }
                    "TYPE" -> { addChat("Escribo: \"${decision.value}\""); CorexAccessibilityService.typeText(decision.value); stepHistory.add("TYPE"); Thread.sleep(300) }
                    "SCROLL_DOWN" -> { addChat("Bajo un poco para ver más..."); CorexAccessibilityService.scrollDown(); stepHistory.add("SCROLL_DOWN"); Thread.sleep(700) }
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

    private fun startRecording(macroName: String) {
        recordingKey = macroName
        recordingSteps.clear()
        isRecording = true
        addLog("🔴 Grabando", "Macro: '$macroName'")
        addLog("💡", "Ahora dame las instrucciones paso a paso. Di 'listo' para terminar.")
    }

    private fun stopRecording() {
        isRecording = false
        if (recordingSteps.isEmpty()) {
            addLog("⚠", "No grabé ningún paso")
            return
        }
        val macro = MacroEngine.Macro(recordingKey, recordingSteps.toList())
        macroEngine.save(macro)
        addLog("✅ Guardado", "Macro '${recordingKey}' con ${recordingSteps.size} pasos")
        recordingKey = ""
        recordingSteps.clear()
    }

    private fun showNumberOverlay(onChoice: (ScreenElement) -> Unit) {
        val elements = CorexAccessibilityService.getScreenElements()
        if (elements.isEmpty()) {
            addLog("⚠", "No veo elementos en pantalla")
            return
        }
        numberOverlay.show(elements) { chosen ->
            onChoice(chosen)
        }
        addLog("🔢", "${elements.size} elementos numerados en pantalla")
    }

    private fun addChat(msg: String) {
        chatMessages.add(msg)
        if (chatMessages.size > 100) chatMessages.removeAt(0)
        mainHandler.post {
            tvChat?.text = chatMessages.joinToString("\n\n")

")
            scrollChat?.post { scrollChat?.fullScroll(android.view.View.FOCUS_DOWN) }
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
        DebugLog.log("Service", "onDestroy")
        scope.cancel()
        numberOverlay.hide()
        numberOverlay.hide()
        try { overlayView?.let { windowManager?.removeView(it) } } catch (e: Exception) {}
    }
}
