package com.doey.corex

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object GroqClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private const val URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODEL = "llama-3.1-8b-instant"

    data class Decision(val action: String, val value: String, val askUser: String = "")

    fun chooseApp(appName: String, appList: String, apiKey: String, onResult: (String) -> Unit) {
        val system = "Eres selector de apps Android. El usuario quiere abrir '$appName'. Devuelve SOLO el package name exacto de la lista, sin texto adicional. Si no existe devuelve NONE."
        val user = "Apps instaladas:\n${appList.take(3000)}\nAbrir: $appName"
        call(system, user, apiKey) { response ->
            val pkg = response.trim().lines().firstOrNull {
                it.contains(".") && !it.contains(" ") && it.length > 3
            } ?: "NONE"
            onResult(pkg)
        }
    }

    fun decide(
        goal: String,
        screenDump: String,
        history: String,
        learned: String,
        apiKey: String,
        onResult: (Decision) -> Unit
    ) {
        val system = """Eres Corex, agente Android. Decides UNA accion por turno.

ACCIONES DISPONIBLES:
- OPEN_APP <nombre>: abrir app (usa esto para abrir cualquier app)
- TAP <numero>: tocar elemento numerado de la pantalla actual
- TYPE <texto>: escribir texto en campo activo
- SCROLL_DOWN / SCROLL_UP: desplazar pantalla
- BACK / HOME: navegacion
- DONE: tarea completada exitosamente
- ASK <pregunta>: preguntar al usuario SOLO si es imposible continuar

REGLAS ESTRICTAS:
1. Para abrir apps SIEMPRE usa OPEN_APP, nunca TAP
2. Usa el numero EXACTO que aparece en la pantalla
3. Si el elemento no esta visible, usa SCROLL_DOWN
4. Si llevas el mismo TAP 3 veces seguido, usa ASK
5. Acciones aprendidas tienen prioridad sobre tu decision

Acciones aprendidas: $learned

Responde SOLO JSON valido: {"action":"ACCION","value":"valor"}"""

        val user = "Meta: $goal\nHistorial: $history\n\nPANTALLA ACTUAL:\n$screenDump"
        call(system, user, apiKey) { response ->
            try {
                val clean = response.replace("```json","").replace("```","").trim()
                val s = clean.indexOf("{"); val e = clean.lastIndexOf("}")
                if (s == -1 || e == -1) { onResult(Decision("ASK", "", "No entendí la pantalla")); return@call }
                val json = JSONObject(clean.substring(s, e + 1))
                val action = json.getString("action").uppercase().trim()
                val value = json.optString("value","").trim()
                if (action == "ASK") onResult(Decision("ASK", value, value))
                else onResult(Decision(action, value))
            } catch (ex: Exception) {
                onResult(Decision("ASK", "", "Error de IA: ${ex.message}"))
            }
        }
    }

    fun chat(message: String, apiKey: String, onResult: (String) -> Unit) {
        call("Eres Corex, asistente Android. Responde en español, muy breve.", message, apiKey, onResult)
    }

    private fun call(system: String, user: String, apiKey: String, onResult: (String) -> Unit) {
        val body = JSONObject().apply {
            put("model", MODEL); put("temperature", 0.1); put("max_tokens", 300)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role","system"); put("content",system) })
                put(JSONObject().apply { put("role","user"); put("content",user) })
            })
        }
        val request = Request.Builder().url(URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { onResult("FAIL: ${e.message}") }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = JSONObject(response.body?.string() ?: "")
                        .getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim()
                    onResult(text)
                } catch (ex: Exception) { onResult("FAIL: ${ex.message}") }
            }
        })
    }
}
