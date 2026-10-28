package com.abnerga7.jarvis

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Voz de JARVIS en el celular (sintetizador de Android, en español). */
class Voz(contexto: Context, private val alHablar: (Boolean) -> Unit) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(contexto.applicationContext, this)
    private var lista = false
    private val pendientes = mutableListOf<String>()

    override fun onInit(estado: Int) {
        if (estado != TextToSpeech.SUCCESS) return
        val idiomas = listOf("es-MX", "es-US", "es-ES", "es")
        for (tag in idiomas) {
            val r = tts.setLanguage(Locale.forLanguageTag(tag))
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) break
        }
        tts.setPitch(0.82f)
        tts.setSpeechRate(1.05f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = alHablar(true)
            override fun onDone(id: String?) = alHablar(false)
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = alHablar(false)
        })
        lista = true
        pendientes.forEach { decir(it) }
        pendientes.clear()
    }

    fun decir(texto: String) {
        val limpio = texto.replace(Regex("https?://\\S+"), "el enlace").replace(Regex("[*_#`]"), "")
        if (!lista) {
            pendientes += limpio
            return
        }
        tts.speak(limpio, TextToSpeech.QUEUE_ADD, null, "jarvis-${System.nanoTime()}")
    }

    fun callar() {
        tts.stop()
        alHablar(false)
    }

    fun cerrar() = tts.shutdown()
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
