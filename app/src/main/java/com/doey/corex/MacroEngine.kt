package com.doey.corex

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MacroEngine(private val context: Context) {

    data class MacroStep(
        val action: String,       // OPEN_APP, TAP, TYPE, SCROLL_DOWN, SCROLL_UP, BACK, HOME
        val value: String,        // packageName, texto, etc
        val x: Float = 0f,
        val y: Float = 0f,
        val label: String = ""    // descripción humana
    )

    data class Macro(
        val key: String,          // "manda mensaje a esposa"
        val steps: List<MacroStep>,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val file = File(context.filesDir, "macros.json")

    fun save(macro: Macro) {
        val all = loadAll().toMutableList()
        val existing = all.indexOfFirst { it.key.equals(macro.key, ignoreCase = true) }
        if (existing >= 0) all[existing] = macro else all.add(macro)
        persist(all)
    }

    fun find(key: String): Macro? {
        val keyLower = key.lowercase()
        return loadAll().firstOrNull {
            it.key.equals(key, ignoreCase = true) ||
            it.key.lowercase().contains(keyLower) ||
            keyLower.contains(it.key.lowercase())
        }
    }

    fun getAll(): List<Macro> = loadAll()

    fun delete(key: String) = persist(loadAll().filter { !it.key.equals(key, ignoreCase = true) })

    fun execute(
        macro: Macro,
        delay: Long,
        onStep: (String) -> Unit,
        onDone: (Boolean) -> Unit
    ) {
        Thread {
            try {
                for (step in macro.steps) {
                    Thread.sleep(delay)
                    val result = when (step.action) {
                        "OPEN_APP" -> {
                            onStep("📱 Abriendo ${step.label}")
                            val intent = context.packageManager.getLaunchIntentForPackage(step.value)
                            if (intent != null) {
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                                Thread.sleep(1500)
                                "OK"
                            } else "FAIL"
                        }
                        "TAP" -> {
                            onStep("👆 Tocando ${step.label}")
                            CorexAccessibilityService.tapAt(
                                CorexAccessibilityService.instance!!,
                                step.x, step.y
                            )
                            "OK"
                        }
                        "TYPE" -> {
                            onStep("⌨ Escribiendo: ${step.value}")
                            CorexAccessibilityService.typeText(step.value)
                            "OK"
                        }
                        "SCROLL_DOWN" -> {
                            onStep("⬇ Scroll abajo")
                            CorexAccessibilityService.scrollDown()
                            "OK"
                        }
                        "SCROLL_UP" -> {
                            onStep("⬆ Scroll arriba")
                            CorexAccessibilityService.scrollUp()
                            "OK"
                        }
                        "BACK" -> {
                            onStep("◀ Atrás")
                            CorexAccessibilityService.pressBack()
                            "OK"
                        }
                        "HOME" -> {
                            onStep("🏠 Inicio")
                            CorexAccessibilityService.pressHome()
                            "OK"
                        }
                        else -> "FAIL"
                    }
                    if (result == "FAIL") {
                        onDone(false)
                        return@Thread
                    }
                }
                onDone(true)
            } catch (e: Exception) {
                DebugLog.logError("MacroEngine", e)
                onDone(false)
            }
        }.start()
    }

    private fun loadAll(): List<Macro> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val stepsArr = o.getJSONArray("steps")
                val steps = (0 until stepsArr.length()).map { j ->
                    val s = stepsArr.getJSONObject(j)
                    MacroStep(
                        action = s.getString("action"),
                        value = s.getString("value"),
                        x = s.optDouble("x", 0.0).toFloat(),
                        y = s.optDouble("y", 0.0).toFloat(),
                        label = s.optString("label", "")
                    )
                }
                Macro(o.getString("key"), steps, o.optLong("ts", 0))
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun persist(list: List<Macro>) {
        val arr = JSONArray()
        list.forEach { m ->
            val stepsArr = JSONArray()
            m.steps.forEach { s ->
                stepsArr.put(JSONObject().apply {
                    put("action", s.action)
                    put("value", s.value)
                    put("x", s.x.toDouble())
                    put("y", s.y.toDouble())
                    put("label", s.label)
                })
            }
            arr.put(JSONObject().apply {
                put("key", m.key)
                put("steps", stepsArr)
                put("ts", m.createdAt)
            })
        }
        file.writeText(arr.toString())
    }
}
