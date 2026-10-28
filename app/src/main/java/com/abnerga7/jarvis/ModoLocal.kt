package com.abnerga7.jarvis

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * JARVIS en el celular cuando la PC no está disponible: conversa con Groq (gratis) y
 * controla el teléfono (abrir apps, alarmas, temporizador, linterna, llamadas, mapas...).
 */
class ModoLocal(private val contexto: Context) {
    private val prefs = contexto.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    private var modelo: String? = null
    private val fallidos = mutableSetOf<String>()
    private val historial = mutableListOf<JSONObject>()

    var clave: String
        get() = prefs.getString("groq", "") ?: ""
        set(valor) {
            prefs.edit().putString("groq", valor.trim()).apply()
            modelo = null
        }

    val disponible: Boolean get() = clave.length > 20

    private val trato: String get() = prefs.getString("trato", null) ?: "señor"

    private fun sistema(): String {
        val ahora = SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy, HH:mm", Locale.forLanguageTag("es-PE")).format(Date())
        return """
Eres J.A.R.V.I.S., el asistente de Tony Stark: brillante, leal, elegante y con humor británico sutil.
Ahora vives en el celular Android del usuario y le dices "$trato". Hoy es $ahora.
Respondes SIEMPRE en español, en 1 a 3 frases cortas (se leen en voz alta), sin emojis ni listas.
Responde ÚNICAMENTE con JSON: {"respuesta": "lo que dirás", "acciones": [{"tipo": "...", "valor": "..."}]}
Acciones del celular:
- abrir_app: nombre de una app instalada ("WhatsApp", "Spotify", "Cámara")
- alarma: valor = hora "HH:MM" en 24 h; opcional "mensaje"
- temporizador: valor = segundos
- buscar_google: texto
- reproducir: canción o video (abre YouTube)
- mapa: lugar o dirección
- llamar: número de teléfono
- linterna: "encender" o "apagar"
- whatsapp: texto del mensaje a compartir
Si es conversación o una pregunta, responde tú mismo con "acciones": [].
""".trim()
    }

    // ------------------------------------------------------------------ Groq
    private fun groq(metodo: String, ruta: String, cuerpo: JSONObject? = null): Pair<Int, String> {
        val c = URL("https://api.groq.com/openai/v1$ruta").openConnection() as HttpURLConnection
        try {
            c.requestMethod = metodo
            c.connectTimeout = 8_000
            c.readTimeout = 45_000
            c.setRequestProperty("Authorization", "Bearer $clave")
            if (cuerpo != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(cuerpo.toString().toByteArray(Charsets.UTF_8)) }
            }
            val codigo = c.responseCode
            val texto = (if (codigo < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return codigo to texto
        } finally {
            c.disconnect()
        }
    }

    private fun elegirModelo(): String {
        val preferidos = listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "moonshotai/kimi-k2-instruct",
            "qwen/qwen3-32b", "openai/gpt-oss-20b", "llama-3.1-8b-instant")
        val (codigo, texto) = groq("GET", "/models")
        if (codigo == 401 || codigo == 403) throw IllegalStateException("La clave de Groq no es válida.")
        val disponibles = mutableListOf<String>()
        if (codigo == 200) {
            val datos = JSONObject(texto).optJSONArray("data") ?: JSONArray()
            for (i in 0 until datos.length()) disponibles += datos.getJSONObject(i).optString("id")
        }
        val malos = listOf("whisper", "guard", "orpheus", "tts", "playai", "compound", "embed")
        val chat = disponibles.filter { id -> malos.none { id.lowercase().contains(it) } && id !in fallidos }
        return preferidos.firstOrNull { it in chat } ?: chat.firstOrNull() ?: "openai/gpt-oss-120b"
    }

    suspend fun preguntar(texto: String): String = withContext(Dispatchers.IO) {
        val m = modelo ?: elegirModelo().also { modelo = it }
        val usuario = JSONObject().put("role", "user").put("content", texto)
        val mensajes = JSONArray().put(JSONObject().put("role", "system").put("content", sistema()))
        historial.takeLast(8).forEach { mensajes.put(it) }
        mensajes.put(usuario)
        var conRazonamiento = true
        var respuesta = ""
        for (intento in 0 until 3) {
            val cuerpo = JSONObject()
                .put("model", m)
                .put("messages", mensajes)
                .put("temperature", 0.5)
                .put("max_tokens", 700)
                .put("response_format", JSONObject().put("type", "json_object"))
            if (conRazonamiento && m.contains("gpt-oss")) cuerpo.put("reasoning_effort", "low")
            val (codigo, cuerpoRespuesta) = groq("POST", "/chat/completions", cuerpo)
            when {
                codigo == 200 -> {
                    respuesta = JSONObject(cuerpoRespuesta).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").optString("content")
                    break
                }
                codigo == 400 && cuerpoRespuesta.contains("reasoning") -> conRazonamiento = false
                codigo == 404 -> {
                    fallidos += m
                    modelo = null
                    if (fallidos.size > 4) return@withContext "Groq no tiene modelos disponibles para tu cuenta, $trato."
                    return@withContext preguntar(texto)
                }
                codigo == 429 -> return@withContext "Alcancé el límite gratuito por ahora, $trato. Inténtelo en unos minutos."
                codigo == 401 -> return@withContext "Mi clave de Groq no es válida, $trato. Revísela en los ajustes."
                else -> if (intento == 2) return@withContext "Tuve un problema al pensar, $trato. ¿Lo repetimos?"
            }
        }
        val plan = try {
            JSONObject(respuesta.substring(respuesta.indexOf('{'), respuesta.lastIndexOf('}') + 1))
        } catch (e: Exception) {
            JSONObject().put("respuesta", respuesta)
        }
        historial += usuario
        historial += JSONObject().put("role", "assistant").put("content", plan.toString())
        while (historial.size > 16) historial.removeAt(0)
        val extras = mutableListOf<String>()
        val acciones = plan.optJSONArray("acciones") ?: JSONArray()
        withContext(Dispatchers.Main) {
            for (i in 0 until acciones.length()) {
                val a = acciones.optJSONObject(i) ?: continue
                ejecutar(a)?.let { extras += it }
            }
        }
        (listOf(plan.optString("respuesta")) + extras).filter { it.isNotBlank() }.joinToString(" ")
    }

    // ------------------------------------------------------------------ acciones del teléfono
    private fun abrir(intent: Intent): Boolean = try {
        contexto.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    private fun normal(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").trim()

    private fun web(url: String) = abrir(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun cod(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Ejecuta una acción; devuelve algo que decir si hubo un problema. */
    fun ejecutar(a: JSONObject): String? {
        val valor = a.optString("valor").trim()
        when (a.optString("tipo").lowercase()) {
            "abrir_app" -> {
                val pm = contexto.packageManager
                val lanzables = pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                val buscado = normal(valor)
                val app = lanzables.firstOrNull { normal(it.loadLabel(pm).toString()) == buscado }
                    ?: lanzables.firstOrNull { normal(it.loadLabel(pm).toString()).contains(buscado) }
                    ?: lanzables.firstOrNull { buscado.contains(normal(it.loadLabel(pm).toString())) }
                val intent = app?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
                if (intent == null || !abrir(intent)) return "No encuentro $valor en el celular."
            }
            "alarma" -> {
                val partes = valor.split(":")
                val hora = partes.getOrNull(0)?.trim()?.toIntOrNull() ?: return "No entendí la hora de la alarma."
                val minuto = partes.getOrNull(1)?.trim()?.take(2)?.toIntOrNull() ?: 0
                abrir(Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hora)
                    .putExtra(AlarmClock.EXTRA_MINUTES, minuto)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("mensaje", "JARVIS"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            }
            "temporizador" -> {
                val segundos = valor.filter { it.isDigit() }.toIntOrNull() ?: return "No entendí el tiempo."
                abrir(Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, segundos)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            }
            "buscar_google" -> web("https://www.google.com/search?q=${cod(valor)}")
            "reproducir", "buscar_youtube" -> web("https://www.youtube.com/results?search_query=${cod(valor)}")
            "mapa" -> abrir(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${cod(valor)}")))
            "llamar" -> abrir(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${valor.filter { it.isDigit() || it == '+' }}")))
            "whatsapp" -> web("https://wa.me/?text=${cod(valor)}")
            "linterna" -> try {
                val cm = contexto.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = cm.cameraIdList.firstOrNull {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: return "Este celular no tiene linterna."
                cm.setTorchMode(id, !normal(valor).startsWith("apag"))
            } catch (e: Exception) {
                return "No pude usar la linterna."
            }
        }
        return null
    }
}
