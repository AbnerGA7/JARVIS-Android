package com.abnerga7.jarvis

import android.app.Application
import android.graphics.Bitmap
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

    private val voz = Voz(app) { hablando = it }
    private val oido = Oido(
        app,
        alNivel = { nivel = it },
        alTexto = { enviar(it) },
        alTerminar = { error ->
            escuchando = false
            nivel = 0f
            error?.let { aviso = it }
        },
    )

    val conectadoAPc: Boolean get() = conexion == Conexion.WIFI || conexion == Conexion.INTERNET
    val trato: String get() = enlace.trato
    val nombrePc: String get() = enlace.nombrePc

    init {
        enlace.alCambiar = { conexion = it }
        if (!Cripto.autoprueba()) aviso = "Aviso: el cifrado no coincide con la PC."
        conectar()
    }

    fun conectar() {
        viewModelScope.launch { enlace.conectar() }
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
        if (!confirmado) mensajes += Mensaje(true, t)
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
        if (conVoz) voz.decir(texto)
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
                if (r.codigo == 200) estado = r.json
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

    override fun onCleared() {
        voz.cerrar()
        oido.cerrar()
    }
}
