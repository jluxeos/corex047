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

    data class Decision(
        val action: String,
        val value: String,
        val askUser: String = ""
    )

    fun decide(
        goal: String,
        screenDump: String,
        history: String,
        learned: String,
        apiKey: String,
        onResult: (Decision) -> Unit
    ) {
        val system = """Eres Corex, agente Android. Decides UNA accion.

ACCIONES:
- OPEN_APP <nombre>: abrir app por nombre
- TAP <numero>: tocar elemento numerado de la pantalla
- TYPE <texto>: escribir en campo activo
- SCROLL_DOWN / SCROLL_UP
- BACK / HOME
- DONE: tarea completada
- ASK <pregunta>: preguntar al usuario si no sabes que tocar

REGLAS:
1. Usa el numero EXACTO del elemento que ves en pantalla
2. Si no ves el elemento, usa SCROLL_DOWN
3. Si llevas 3 TAP fallidos al mismo numero, usa ASK
4. Si la tarea esta lista, usa DONE

Acciones aprendidas del usuario: $learned

Responde SOLO JSON: {"action":"ACCION","value":"valor"}"""

        val user = "Meta: $goal\nHistorial: $history\nPantalla:\n$screenDump"

        call(system, user, apiKey) { response ->
            try {
                val clean = response.replace("```json","").replace("```","").trim()
                val s = clean.indexOf("{")
                val e = clean.lastIndexOf("}")
                val json = JSONObject(clean.substring(s, e + 1))
                val action = json.getString("action").uppercase().trim()
                val value = json.optString("value","").trim()
                if (action.startsWith("ASK")) {
                    onResult(Decision("ASK", "", value.ifEmpty { json.optString("value","¿Cómo debo hacer esto?") }))
                } else {
                    onResult(Decision(action, value))
                }
            } catch (ex: Exception) {
                onResult(Decision("FAIL", ex.message ?: ""))
            }
        }
    }

    fun chat(message: String, apiKey: String, onResult: (String) -> Unit) {
        call(
            "Eres Corex, asistente Android. Responde en español, breve.",
            message, apiKey
        ) { onResult(it) }
    }

    private fun call(system: String, user: String, apiKey: String, onResult: (String) -> Unit) {
        val body = JSONObject().apply {
            put("model", MODEL)
            put("temperature", 0.1)
            put("max_tokens", 256)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role","system"); put("content",system) })
                put(JSONObject().apply { put("role","user"); put("content",user) })
            })
        }
        val request = Request.Builder()
            .url(URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
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
