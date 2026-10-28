package com.abnerga7.jarvis

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class Mensaje(val deMi: Boolean, val texto: String)

class Confirmacion(val pregunta: String, val siConfirma: () -> Unit)

class JarvisVM(app: Application) : AndroidViewModel(app) {
    val enlace = Enlace(app)
    val local = ModoLocal(app)

    var conexion by mutableStateOf(enlace.conexion)
        private set
    val mensajes = mutableStateListOf<Mensaje>()
    var pensando by mutableStateOf(false)
        private set
    var escuchando by mutableStateOf(false)
        private set
    var hablando by mutableStateOf(false)
        private set
    var nivel by mutableFloatStateOf(0f)
        private set
    var hablarEnPc by mutableStateOf(false)
    var estado by mutableStateOf<JSONObject?>(null)
        private set
    var pantalla by mutableStateOf<Bitmap?>(null)
        private set
    var apps by mutableStateOf<List<String>>(emptyList())
        private set
    var confirmacion by mutableStateOf<Confirmacion?>(null)
    var aviso by mutableStateOf<String?>(null)
    var errorVincular by mutableStateOf<String?>(null)
        private set
    var actualizacion by mutableStateOf<Actualizacion?>(null)
        private set

    private val prefs = app.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    private val principal = Handler(Looper.getMainLooper())
    private var ultimaFueVoz = false

    // ------------------------------------------------------------------ preferencias
    var responderConVoz by mutableStateOf(prefs.getBoolean("con_voz", true))
        private set
    var conversacionContinua by mutableStateOf(prefs.getBoolean("continua", true))
        private set
    var velocidadVoz by mutableFloatStateOf(prefs.getFloat("velocidad2", 0.92f))
        private set
    var tonoVoz by mutableFloatStateOf(prefs.getFloat("tono2", 0.78f))
        private set
    var usarVozDeLaPc by mutableStateOf(prefs.getBoolean("voz_pc", true))
        private set
    var vozAndroid by mutableStateOf(prefs.getString("voz_android", null))
        private set
    var nombreVozPc by mutableStateOf(prefs.getString("voz_pc_nombre", null))
        private set

    fun cambiarResponderConVoz(v: Boolean) { responderConVoz = v; prefs.edit().putBoolean("con_voz", v).apply() }
    fun cambiarConversacionContinua(v: Boolean) { conversacionContinua = v; prefs.edit().putBoolean("continua", v).apply() }
    fun cambiarVelocidad(v: Float) { velocidadVoz = v; voz.velocidad = v; prefs.edit().putFloat("velocidad2", v).apply() }
    fun cambiarTono(v: Float) { tonoVoz = v; voz.tono = v; prefs.edit().putFloat("tono2", v).apply() }
    fun cambiarUsarVozDeLaPc(v: Boolean) { usarVozDeLaPc = v; prefs.edit().putBoolean("voz_pc", v).apply() }
    fun elegirVozAndroid(nombre: String) {
        vozAndroid = nombre
        voz.nombreVozAndroid = nombre
        prefs.edit().putString("voz_android", nombre).apply()
        voz.probarVozAndroid("A su servicio, ${enlace.trato}.")
    }
    fun vocesAndroid() = voz.vocesAndroid()
    fun probarVoz() = voz.decir("A su servicio, ${enlace.trato}. Todos los sistemas en línea.")
    fun probarVozAndroid() = voz.probarVozAndroid("Así sueno sin la PC, ${enlace.trato}.")

    private val voz = Voz(app) { activo ->
        hablando = activo
        // conversación continua: al terminar de hablar, vuelve a escuchar si le hablaste por voz
        if (!activo && conversacionContinua && ultimaFueVoz) {
            principal.postDelayed({ if (!escuchando && !hablando && !pensando) alternarMicrofono() }, 350)
        }
    }
    private val oido = Oido(
        app,
        alNivel = { nivel = it },
        alTexto = { ultimaFueVoz = true; enviar(it) },
        alTerminar = { error ->
            escuchando = false
            nivel = 0f
            if (error != null && !ultimaFueVoz) aviso = error
            if (error == null && !pensando) ultimaFueVoz = false
        },
    )

    val conectadoAPc: Boolean get() = conexion == Conexion.WIFI || conexion == Conexion.INTERNET
    val trato: String get() = enlace.trato
    val nombrePc: String get() = enlace.nombrePc

    init {
        enlace.alCambiar = { conexion = it }
        if (!Cripto.autoprueba()) aviso = "Aviso: el cifrado no coincide con la PC."
        voz.velocidad = velocidadVoz
        voz.tono = tonoVoz
        voz.nombreVozAndroid = vozAndroid
        // la misma voz de JARVIS que en la PC: el celular le pide el audio a la PC
        voz.vozDeLaPc = { texto ->
            if (usarVozDeLaPc && conectadoAPc) {
                val r = enlace.pedir("POST", "/api/voz", JSONObject().put("texto", texto), 15_000)
                if (r.codigo == 200 && r.cuerpo.size > 500) r.cuerpo else null
            } else {
                null
            }
        }
        cargarHistorial()
        conectar()
        viewModelScope.launch { actualizacion = Actualizaciones.buscar() }
    }

    // ------------------------------------------------------------------ historial
    private fun cargarHistorial() {
        try {
            val datos = JSONArray(prefs.getString("historial", "[]"))
            for (i in 0 until datos.length()) {
                val m = datos.getJSONObject(i)
                mensajes += Mensaje(m.optBoolean("yo"), m.optString("t"))
            }
        } catch (_: Exception) {
        }
    }

    private fun guardarHistorial() {
        val datos = JSONArray()
        mensajes.takeLast(80).forEach { datos.put(JSONObject().put("yo", it.deMi).put("t", it.texto)) }
        prefs.edit().putString("historial", datos.toString()).apply()
    }

    fun borrarConversacion() {
        mensajes.clear()
        guardarHistorial()
    }

    fun conectar() {
        viewModelScope.launch {
            enlace.conectar()
            if (conectadoAPc) actualizarEstado()
        }
    }

    fun vincular(codigo: String) {
        errorVincular = null
        viewModelScope.launch {
            val error = enlace.vincular(codigo)
            errorVincular = error
            if (error == null) {
                val saludo = "Vinculado con ${enlace.nombrePc}. A su servicio, ${enlace.trato}."
                mensajes += Mensaje(false, saludo)
                voz.decir(saludo)
            }
        }
    }

    fun desvincular() {
        enlace.desvincular()
        mensajes.clear()
    }

    fun guardarClaveGroq(clave: String) {
        local.clave = clave
        aviso = if (local.disponible) "Clave guardada: ya puedo responder sin la PC." else "La clave parece incompleta."
    }

    // ------------------------------------------------------------------ voz
    fun alternarMicrofono() {
        if (escuchando) {
            oido.parar()
            return
        }
        if (!oido.disponible) {
            aviso = "Este celular no tiene reconocimiento de voz (instala la app de Google)."
            return
        }
        voz.callar()
        escuchando = true
        oido.escuchar()
    }

    fun callar() = voz.callar()

    // ------------------------------------------------------------------ asistente
    fun enviar(texto: String, confirmado: Boolean = false) {
        val t = texto.trim()
        if (t.isEmpty()) return
        if (!confirmado) {
            mensajes += Mensaje(true, t)
            guardarHistorial()
        }
        viewModelScope.launch {
            pensando = true
            try {
                if (conectadoAPc || (enlace.vinculado && !local.disponible)) {
                    val cuerpo = JSONObject().put("texto", t).put("hablar_en_pc", hablarEnPc).put("confirmado", confirmado)
                    val r = enlace.pedir("POST", "/api/orden", cuerpo)
                    manejar(r.json, hablarConVoz = !hablarEnPc) { enviar(t, confirmado = true) }
                } else if (local.disponible) {
                    responder(local.preguntar(t), true)
                } else {
                    responder("No estoy conectado a su PC y no tengo una clave de Groq para responder desde el celular, $trato.", true)
                }
            } catch (e: Exception) {
                if (local.disponible) {
                    responder(local.preguntar(t), true)
                } else {
                    responder("No logro comunicarme con su PC, $trato. Revise que JARVIS esté abierto.", true)
                }
            } finally {
                pensando = false
            }
        }
    }

    private fun manejar(r: JSONObject, hablarConVoz: Boolean, alConfirmar: () -> Unit) {
        val pregunta = r.optString("pregunta")
        if (pregunta.isNotBlank() && pregunta != "null") {
            confirmacion = Confirmacion(pregunta, alConfirmar)
            return
        }
        val respuestas = r.optJSONArray("respuestas")
        val partes = mutableListOf<String>()
        if (respuestas != null) for (i in 0 until respuestas.length()) partes += respuestas.optString(i)
        val texto = partes.filter { it.isNotBlank() }.joinToString(" ")
        when {
            texto.isNotBlank() -> responder(texto, hablarConVoz)
            r.has("error") -> responder(r.optString("error"), hablarConVoz)
            else -> responder("Hecho, $trato.", hablarConVoz)
        }
    }

    private fun responder(texto: String, conVoz: Boolean) {
        mensajes += Mensaje(false, texto)
        guardarHistorial()
        if (conVoz && responderConVoz) {
            voz.decir(texto)
        } else if (ultimaFueVoz && conversacionContinua) {
            principal.postDelayed({ if (!escuchando) alternarMicrofono() }, 400)
        }
    }

    // ------------------------------------------------------------------ portapapeles
    fun enviarPortapapeles() {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val texto = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            ?.coerceToText(getApplication())?.toString().orEmpty()
        if (texto.isBlank()) {
            aviso = "Tu portapapeles está vacío."
            return
        }
        viewModelScope.launch {
            aviso = try {
                val r = enlace.pedir("POST", "/api/portapapeles", JSONObject().put("texto", texto), 15_000)
                r.json.optString("mensaje", "Copiado en la PC")
            } catch (e: Exception) {
                "No pude enviarlo a la PC."
            }
        }
    }

    fun traerPortapapeles() {
        viewModelScope.launch {
            try {
                val r = enlace.pedir("GET", "/api/portapapeles", espera = 15_000)
                val texto = r.json.optString("texto")
                if (texto.isBlank()) {
                    aviso = "El portapapeles de la PC está vacío."
                } else {
                    val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("JARVIS", texto))
                    aviso = "Copiado en el celular: " + texto.take(40) + if (texto.length > 40) "…" else ""
                }
            } catch (e: Exception) {
                aviso = "No pude traer el portapapeles de la PC."
            }
        }
    }

    // ------------------------------------------------------------------ control remoto
    fun accion(tipo: String, valor: String = "", confirmado: Boolean = false) {
        viewModelScope.launch {
            try {
                val cuerpo = JSONObject().put("tipo", tipo).put("valor", valor).put("confirmado", confirmado)
                val r = enlace.pedir("POST", "/api/accion", cuerpo, 30_000)
                val pregunta = r.json.optString("pregunta")
                if (pregunta.isNotBlank() && pregunta != "null") {
                    confirmacion = Confirmacion(pregunta) { accion(tipo, valor, confirmado = true) }
                    return@launch
                }
                val respuestas = r.json.optJSONArray("respuestas")
                val texto = (0 until (respuestas?.length() ?: 0)).joinToString(" ") { respuestas!!.optString(it) }
                if (texto.isNotBlank()) aviso = texto
                actualizarEstado()
            } catch (e: Exception) {
                aviso = "No pude hablar con la PC."
            }
        }
    }

    fun actualizarEstado() {
        viewModelScope.launch {
            try {
                val r = enlace.pedir("GET", "/api/estado", espera = 8_000)
                if (r.codigo == 200) {
                    estado = r.json
                    r.json.optString("voz").takeIf { it.isNotBlank() && it != "null" }?.let {
                        nombreVozPc = it
                        prefs.edit().putString("voz_pc_nombre", it).apply()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private var capturando: Job? = null

    fun capturarPantalla(ancho: Int) {
        if (capturando?.isActive == true) return
        capturando = viewModelScope.launch {
            try {
                val calidad = if (conexion == Conexion.INTERNET) 45 else 60
                val r = enlace.pedir("GET", "/api/pantalla?ancho=$ancho&calidad=$calidad", espera = 10_000)
                if (r.codigo == 200) BitmapFactory.decodeByteArray(r.cuerpo, 0, r.cuerpo.size)?.let { pantalla = it }
            } catch (_: Exception) {
            }
        }
    }

    fun raton(dx: Float, dy: Float) = enviarSinRespuesta("/api/raton", JSONObject().put("dx", dx.toDouble()).put("dy", dy.toDouble()))

    fun clic(tipo: String) = enviarSinRespuesta("/api/raton", JSONObject().put("clic", tipo))

    fun rueda(pasos: Int) = enviarSinRespuesta("/api/raton", JSONObject().put("rueda", pasos))

    fun escribirEnPc(texto: String) = enviarSinRespuesta("/api/teclado", JSONObject().put("texto", texto))

    fun teclas(combinacion: String) = enviarSinRespuesta("/api/teclado", JSONObject().put("teclas", combinacion))

    private fun enviarSinRespuesta(ruta: String, cuerpo: JSONObject) {
        viewModelScope.launch {
            try { enlace.pedir("POST", ruta, cuerpo, 8_000) } catch (_: Exception) { }
        }
    }

    fun cargarApps() {
        viewModelScope.launch {
            try {
                val r = enlace.pedir("GET", "/api/apps", espera = 15_000)
                val lista = r.json.optJSONArray("apps")
                if (lista != null) apps = (0 until lista.length()).map { lista.optString(it) }
            } catch (_: Exception) {
                aviso = "No pude cargar las apps de la PC."
            }
        }
    }

    fun copiar(texto: String) {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("JARVIS", texto))
        aviso = "Copiado"
    }

    override fun onCleared() {
        voz.cerrar()
        oido.cerrar()
    }
}
