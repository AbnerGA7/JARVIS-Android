package com.abnerga7.jarvis

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abnerga7.jarvis.ui.Cian
import com.abnerga7.jarvis.ui.Fondo
import com.abnerga7.jarvis.ui.TemaJarvis
import com.abnerga7.jarvis.ui.Texto
import com.abnerga7.jarvis.ui.TextoTenue
import kotlinx.coroutines.launch
import org.json.JSONObject

/** "Compartir → JARVIS" desde cualquier app: un enlace se abre en la PC; un texto se copia o se le pregunta a JARVIS. */
class CompartirActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val texto = (intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.getStringExtra(Intent.EXTRA_SUBJECT) ?: "").trim()
        if (texto.isEmpty()) {
            finish()
            return
        }
        val enlace = Enlace(applicationContext)
        val esEnlace = Regex("^https?://\\S+$").matches(texto)
        setContent {
            TemaJarvis {
                var enviando by remember { mutableStateOf(false) }
                val alcance = rememberCoroutineScope()
                Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(18.dp)).background(Fondo)
                        .border(1.dp, Cian, RoundedCornerShape(18.dp)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("◤ COMPARTIR CON JARVIS", color = Cian, fontSize = 15.sp)
                    Text(texto.take(220) + if (texto.length > 220) "…" else "", color = Texto, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    if (enviando) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cian)
                    Button(
                        onClick = {
                            enviando = true
                            alcance.launch {
                                val mensaje = try {
                                    enlace.conectar()
                                    val r = enlace.pedir("POST", "/api/compartir", JSONObject().put("texto", texto), 15_000)
                                    r.json.optString("mensaje", "Enviado a la PC")
                                } catch (e: Exception) {
                                    "No encuentro tu PC. Revisa que JARVIS esté abierto."
                                }
                                Toast.makeText(this@CompartirActivity, mensaje, Toast.LENGTH_LONG).show()
                                finish()
                            }
                        },
                        enabled = !enviando && enlace.vinculado,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (esEnlace) "ABRIR EN LA PC" else "COPIAR EN LA PC") }
                    OutlinedButton(
                        onClick = {
                            startActivity(
                                Intent(this@CompartirActivity, MainActivity::class.java)
                                    .setAction(MainActivity.ACCION_PREGUNTAR)
                                    .putExtra(MainActivity.EXTRA_PREGUNTA, if (esEnlace) "Resúmeme esto: $texto" else texto)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            finish()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("PREGUNTAR A JARVIS") }
                    TextButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Cancelar", color = TextoTenue)
                    }
                    if (!enlace.vinculado) Text("Vincula tu PC en la app para enviarle cosas.", color = Color(0xFFFFB000), fontSize = 12.sp)
                }
                }
            }
        }
    }
}
