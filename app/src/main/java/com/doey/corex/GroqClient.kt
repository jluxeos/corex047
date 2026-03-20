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
        val system = """Eres Corex, agente Android inteligente. Decides UNA accion por turno.

ACCIONES:
- OPEN_APP <nombre>: abrir app por nombre
- TAP <numero>: tocar elemento numerado
- TYPE <texto>: escribir en campo activo
- SCROLL_DOWN / SCROLL_UP
- BACK / HOME
- DONE: tarea completada
- ASK <pregunta>: solo si es imposible continuar

REGLAS:
1. Para abrir apps SIEMPRE usa OPEN_APP
2. Si ves lista de chats en WhatsApp y necesitas abrir uno, usa TAP con el numero del contacto
3. Si necesitas escribir un mensaje, primero TAP al chat, luego TAP al campo de texto, luego TYPE
4. El boton enviar en WhatsApp tiene descripcion "Enviar" - busca ese elemento
5. Si no ves el elemento, SCROLL_DOWN
6. Genera mensajes naturales y emotivos cuando te pidan expresar sentimientos
7. Si la pantalla dice PANTALLA VACIA, usa OPEN_APP para abrir la app necesaria

Contexto aprendido: $learned

Responde SOLO JSON: {"action":"ACCION","value":"valor"}"""

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
