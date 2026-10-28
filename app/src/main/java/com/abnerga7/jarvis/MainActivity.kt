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
    private val vm: JarvisVM by viewModels()

    /** true cuando JARVIS se abrió con el botón/gesto de asistente del celular: empieza a escuchar. */
    private val escucharAlAbrir = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        escucharAlAbrir.value = intent?.action == Intent.ACTION_ASSIST
        setContent {
            TemaJarvis {
                App(vm, escucharAlAbrir.value) { escucharAlAbrir.value = false }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_ASSIST) escucharAlAbrir.value = true
    }

    override fun onResume() {
        super.onResume()
        vm.conectar()
    }
}
