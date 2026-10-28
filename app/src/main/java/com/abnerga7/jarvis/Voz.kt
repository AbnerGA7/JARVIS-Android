package com.abnerga7.jarvis

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Voz de JARVIS en el celular.
 * 1) La misma voz neural que la PC: el audio se lo pide a la PC (suena idéntico, con la misma velocidad y tono).
 * 2) Sin PC: el sintetizador de Android, con la voz en español elegida y a velocidad normal, lo más parecida a la voz de la PC.
 * Habla frase por frase y prepara la siguiente mientras suena la actual.
 */
class Voz(contexto: Context, private val alHablar: (Boolean) -> Unit) : TextToSpeech.OnInitListener {
    private val app = contexto.applicationContext
    private val tts = TextToSpeech(app, this)
    private var lista = false
    private val esperando = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var cola = Channel<String>(Channel.UNLIMITED)
    private val pendientes = AtomicInteger(0)
    private var trabajo: Job? = null
    private var reproductor: MediaPlayer? = null

    /** Pide a la PC el audio con la voz de JARVIS; devuelve null si la PC no está disponible. */
    var vozDeLaPc: (suspend (String) -> ByteArray?)? = null

    var velocidad = 1.05f
        set(valor) { field = valor; if (lista) tts.setSpeechRate(valor) }
    var tono = 0.82f
        set(valor) { field = valor; if (lista) tts.setPitch(valor) }
    var nombreVozAndroid: String? = null
        set(valor) { field = valor; if (lista) elegirVozAndroid() }

    init {
        iniciarConsumidor()
    }

    override fun onInit(estado: Int) {
        if (estado != TextToSpeech.SUCCESS) return
        for (tag in listOf("es-MX", "es-US", "es-ES", "es")) {
            val r = tts.setLanguage(Locale.forLanguageTag(tag))
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) break
        }
        elegirVozAndroid()
        tts.setPitch(tono)
        tts.setSpeechRate(velocidad)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { id?.let { esperando.remove(it)?.complete(Unit) } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { id?.let { esperando.remove(it)?.complete(Unit) } }
            override fun onStop(id: String?, interrupted: Boolean) { id?.let { esperando.remove(it)?.complete(Unit) } }
        })
        lista = true
    }

    /** Voces en español disponibles en el celular (para elegir la de respaldo). */
    fun vocesAndroid(): List<Voice> = try {
        tts.voices.orEmpty().filter { it.locale.language == "es" }
            .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { it.name }))
    } catch (e: Exception) {
        emptyList()
    }

    private fun elegirVozAndroid() {
        val voces = vocesAndroid()
        val elegida = voces.firstOrNull { it.name == nombreVozAndroid }
            ?: voces.firstOrNull { it.locale.country in listOf("MX", "US") && !it.isNetworkConnectionRequired }
        elegida?.let { tts.voice = it }
    }

    // ------------------------------------------------------------------ hablar
    fun decir(texto: String) {
        val limpio = texto.replace(Regex("https?://\\S+"), "el enlace").replace(Regex("[*_#`]"), "").trim()
        if (limpio.isEmpty()) return
        pendientes.incrementAndGet()
        cola.trySend(limpio)
    }

    /** Prueba la voz de respaldo de Android (sin pasar por la PC). */
    fun probarVozAndroid(texto: String) {
        alcance.launch { hablarConAndroid(texto) }
    }

    fun callar() {
        trabajo?.cancel()
        while (cola.tryReceive().isSuccess) { /* se descartan las frases pendientes */ }
        pendientes.set(0)
        detenerReproductor()
        tts.stop()
        esperando.values.forEach { it.complete(Unit) }
        esperando.clear()
        alHablar(false)
        iniciarConsumidor()
    }

    fun cerrar() {
        trabajo?.cancel()
        detenerReproductor()
        tts.shutdown()
    }

    private fun iniciarConsumidor() {
        trabajo = alcance.launch {
            for (texto in cola) {
                alHablar(true)
                try {
                    hablarPorFrases(texto)
                } finally {
                    if (pendientes.decrementAndGet() <= 0) {
                        pendientes.set(0)
                        alHablar(false)
                    }
                }
            }
        }
    }

    private suspend fun hablarPorFrases(texto: String) {
        val frases = dividir(texto)
        var siguiente: Deferred<ByteArray?>? = null
        for ((i, frase) in frases.withIndex()) {
            val actual = siguiente ?: alcance.async(Dispatchers.IO) { audioNeural(frase) }
            siguiente = if (i + 1 < frases.size) alcance.async(Dispatchers.IO) { audioNeural(frases[i + 1]) } else null
            val audio = actual.await()
            if (audio != null && reproducir(audio)) continue
            hablarConAndroid(frase)
        }
    }

    private suspend fun audioNeural(frase: String): ByteArray? {
        val pedir = vozDeLaPc ?: return null
        return try {
            withTimeoutOrNull(12_000) { pedir(frase) }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun reproducir(audio: ByteArray): Boolean {
        val archivo = withContext(Dispatchers.IO) {
            File.createTempFile("jarvis", ".mp3", app.cacheDir).apply { writeBytes(audio) }
        }
        return try {
            suspendCancellableCoroutine { cont ->
                val mp = MediaPlayer()
                reproductor = mp
                try {
                    mp.setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                    )
                    mp.setDataSource(archivo.absolutePath)
                    mp.setOnCompletionListener { if (cont.isActive) cont.resume(true) }
                    mp.setOnErrorListener { _, _, _ -> if (cont.isActive) cont.resume(false); true }
                    mp.prepare()
                    mp.start()
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(false)
                }
                cont.invokeOnCancellation { detenerReproductor() }
            }
        } finally {
            detenerReproductor()
            archivo.delete()
        }
    }

    private fun detenerReproductor() {
        reproductor?.let {
            try { it.stop() } catch (_: Exception) { }
            it.release()
        }
        reproductor = null
    }

    private suspend fun hablarConAndroid(frase: String) {
        if (!lista) return
        val id = "jarvis-${System.nanoTime()}"
        val listo = CompletableDeferred<Unit>()
        esperando[id] = listo
        tts.speak(frase, TextToSpeech.QUEUE_ADD, null, id)
        withTimeoutOrNull(30_000) { listo.await() }
        esperando.remove(id)
    }

    companion object {
        /** Parte la respuesta en frases para empezar a hablar antes (las muy cortas se juntan). */
        fun dividir(texto: String, minimo: Int = 45): List<String> {
            val partes = texto.split(Regex("(?<=[.!?…;:])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
            val frases = mutableListOf<String>()
            var actual = ""
            for (p in partes) {
                actual = (actual + " " + p).trim()
                if (actual.length >= minimo) {
                    frases += actual
                    actual = ""
                }
            }
            if (actual.isNotEmpty()) {
                if (frases.isNotEmpty() && actual.length < 18) frases[frases.size - 1] = frases.last() + " " + actual
                else frases += actual
            }
            return frases.ifEmpty { listOf(texto) }
        }
    }
}

/** Oído: reconocimiento de voz de Android (es-PE). */
class Oido(
    contexto: Context,
    private val alNivel: (Float) -> Unit,
    private val alTexto: (String) -> Unit,
    private val alTerminar: (String?) -> Unit,
) {
    val disponible = SpeechRecognizer.isRecognitionAvailable(contexto)
    private val rec: SpeechRecognizer? =
        if (disponible) SpeechRecognizer.createSpeechRecognizer(contexto.applicationContext) else null

    init {
        rec?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = alNivel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = alNivel(0f)
            override fun onError(error: Int) {
                alNivel(0f)
                val mensaje = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Sin internet para reconocer la voz."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Falta el permiso del micrófono."
                    else -> "No te entendí, intenta otra vez."
                }
                alTerminar(mensaje)
            }
            override fun onResults(results: Bundle?) {
                val texto = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!texto.isNullOrBlank()) alTexto(texto)
                alTerminar(null)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    fun escuchar() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-PE")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-PE")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        rec?.startListening(intent)
    }

    fun parar() = rec?.stopListening()

    fun cerrar() = rec?.destroy()
}
