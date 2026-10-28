package com.abnerga7.jarvis

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import com.abnerga7.jarvis.ui.App
import com.abnerga7.jarvis.ui.TemaJarvis

class MainActivity : ComponentActivity() {
    companion object {
        const val ACCION_ESCUCHAR = "com.abnerga7.jarvis.ESCUCHAR"
        const val ACCION_PREGUNTAR = "com.abnerga7.jarvis.PREGUNTAR"
        const val EXTRA_PREGUNTA = "pregunta"
    }

    private val vm: JarvisVM by viewModels()

    /** true cuando JARVIS se abrió para escuchar (botón de asistente o ajuste rápido). */
    private val escucharAlAbrir = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) atender(intent)
        setContent {
            TemaJarvis {
                App(vm, escucharAlAbrir.value) { escucharAlAbrir.value = false }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        atender(intent)
    }

    private fun atender(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_ASSIST, ACCION_ESCUCHAR -> escucharAlAbrir.value = true
            ACCION_PREGUNTAR -> intent.getStringExtra(EXTRA_PREGUNTA)?.let { vm.enviar(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.conectar()
    }
}
