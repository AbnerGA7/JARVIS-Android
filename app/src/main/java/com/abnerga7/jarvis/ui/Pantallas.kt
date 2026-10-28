package com.abnerga7.jarvis.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.abnerga7.jarvis.Conexion
import com.abnerga7.jarvis.JarvisVM
import com.abnerga7.jarvis.Mensaje
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private enum class Pestana(val titulo: String, val icono: ImageVector) {
    ASISTENTE("Jarvis", Icons.Filled.Mic),
    CONTROL("Control", Icons.Filled.Tune),
    PANTALLA("Pantalla", Icons.Filled.DesktopWindows),
    APPS("Apps", Icons.Filled.Apps),
    AJUSTES("Ajustes", Icons.Filled.Settings),
}

// =============================================================================== app
@Composable
fun App(vm: JarvisVM, escucharAlAbrir: Boolean, alEscucharAbierto: () -> Unit) {
    val contexto = LocalContext.current
    val avisos = remember { SnackbarHostState() }
    var pestana by remember { mutableStateOf(Pestana.ASISTENTE) }
    val permiso = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) vm.alternarMicrofono() else vm.aviso = "Necesito el micrófono para escucharte."
    }
    val microfono: () -> Unit = {
        if (ContextCompat.checkSelfPermission(contexto, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.alternarMicrofono()
        } else {
            permiso.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(vm.aviso) {
        vm.aviso?.let {
            avisos.showSnackbar(it)
            vm.aviso = null
        }
    }
    LaunchedEffect(escucharAlAbrir) {
        if (escucharAlAbrir) {
            pestana = Pestana.ASISTENTE
            microfono()
            alEscucharAbierto()
        }
    }
    vm.confirmacion?.let { c ->
        AlertDialog(
            onDismissRequest = { vm.confirmacion = null },
            containerColor = Panel,
            title = { Text("CONFIRMAR", style = MaterialTheme3.titleMedium, color = Dorado) },
            text = { Text(c.pregunta, color = Texto) },
            confirmButton = {
                Button(onClick = { vm.confirmacion = null; c.siConfirma() },
                    colors = ButtonDefaults.buttonColors(containerColor = Dorado, contentColor = Color.Black)) { Text("SÍ, HAZLO") }
            },
            dismissButton = { TextButton(onClick = { vm.confirmacion = null }) { Text("Cancelar", color = TextoTenue) } },
        )
    }

    val sinNada = vm.conexion == Conexion.SIN_VINCULAR && !vm.local.disponible
    Scaffold(
        containerColor = Fondo,
        snackbarHost = { SnackbarHost(avisos) },
        topBar = { BarraSuperior(vm) },
        bottomBar = {
            if (!sinNada) {
                NavigationBar(containerColor = Panel) {
                    Pestana.entries.forEach { p ->
                        NavigationBarItem(
                            selected = pestana == p,
                            onClick = { pestana = p },
                            icon = { Icon(p.icono, contentDescription = p.titulo) },
                            label = { Text(p.titulo, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.Black, indicatorColor = Cian,
                                selectedTextColor = Cian, unselectedIconColor = TextoTenue, unselectedTextColor = TextoTenue,
                            ),
                        )
                    }
                }
            }
        },
    ) { margen ->
        Box(Modifier.padding(margen).fillMaxSize()) {
            if (sinNada) {
                Bienvenida(vm)
            } else {
                when (pestana) {
                    Pestana.ASISTENTE -> Asistente(vm, microfono)
                    Pestana.CONTROL -> NecesitaPc(vm) { Control(vm) }
                    Pestana.PANTALLA -> NecesitaPc(vm) { PantallaRemota(vm) }
                    Pestana.APPS -> NecesitaPc(vm) { AppsPc(vm) }
                    Pestana.AJUSTES -> Ajustes(vm)
                }
            }
        }
    }
}

private val MaterialTheme3: androidx.compose.material3.Typography
    @Composable get() = androidx.compose.material3.MaterialTheme.typography

@Composable
private fun BarraSuperior(vm: JarvisVM) {
    val (texto, color) = when (vm.conexion) {
        Conexion.WIFI -> "${vm.nombrePc} · WiFi" to Turquesa
        Conexion.INTERNET -> "${vm.nombrePc} · Internet" to Cian
        Conexion.BUSCANDO -> "Buscando PC..." to Dorado
        Conexion.DESCONECTADA -> (if (vm.local.disponible) "PC fuera · modo celular" else "PC desconectada") to Rojo
        Conexion.SIN_VINCULAR -> (if (vm.local.disponible) "Modo celular" else "Sin vincular") to TextoTenue
    }
    Row(
        Modifier.fillMaxWidth().background(Fondo).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("J.A.R.V.I.S.", style = MaterialTheme3.titleLarge, color = Cian, modifier = Modifier.weight(1f))
        Row(
            Modifier.clip(RoundedCornerShape(50)).border(1.dp, color, RoundedCornerShape(50))
                .clickable { vm.conectar() }.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(texto, color = color, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun NecesitaPc(vm: JarvisVM, contenido: @Composable () -> Unit) {
    if (vm.conectadoAPc) {
        contenido()
    } else {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.CloudOff, null, tint = TextoTenue, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                if (vm.enlace.vinculado) "No encuentro tu PC. Revisa que JARVIS esté abierto en la laptop."
                else "Vincula tu PC en Ajustes para controlarla desde aquí.",
                color = Texto, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            if (vm.enlace.vinculado) {
                OutlinedButton(onClick = { vm.conectar() }) {
                    Icon(Icons.Filled.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text("REINTENTAR")
                }
            }
        }
    }
}

// =============================================================================== bienvenida / vincular
@Composable
private fun Bienvenida(vm: JarvisVM) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Reactor(0f, Cian, vm.conexion == Conexion.BUSCANDO, Modifier.size(170.dp))
        Spacer(Modifier.height(20.dp))
        Text("VINCULAR CON TU PC", style = MaterialTheme3.titleMedium, color = Cian)
        Spacer(Modifier.height(8.dp))
        Text(
            "En la laptop: clic derecho en el reactor de JARVIS → «Conectar celular». Escribe aquí el código que aparece. " +
                "Funciona con WiFi o con datos móviles.",
            color = Texto, textAlign = TextAlign.Center, fontSize = 14.sp,
        )
        Spacer(Modifier.height(20.dp))
        FormularioCodigo(vm)
        Spacer(Modifier.height(36.dp))
        Text("¿SIN PC A LA MANO?", style = MaterialTheme3.labelSmall, color = TextoTenue)
        Spacer(Modifier.height(8.dp))
        FormularioGroq(vm)
    }
}

@Composable
private fun FormularioCodigo(vm: JarvisVM) {
    var codigo by remember { mutableStateOf("") }
    val buscando = vm.conexion == Conexion.BUSCANDO
    OutlinedTextField(
        value = codigo,
        onValueChange = { nuevo ->
            val limpio = nuevo.uppercase().filter { it.isLetterOrDigit() }.take(8)
            codigo = if (limpio.length > 4) limpio.take(4) + "-" + limpio.drop(4) else limpio
        },
        label = { Text("Código (ej. K7P2-QX9M)") },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 24.sp, letterSpacing = 4.sp, color = Cian),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { vm.vincular(codigo) }),
        modifier = Modifier.fillMaxWidth(),
    )
    vm.errorVincular?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, color = Dorado, fontSize = 13.sp)
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { vm.vincular(codigo) },
        enabled = !buscando && codigo.count { it.isLetterOrDigit() } == 8,
        modifier = Modifier.fillMaxWidth().height(50.dp),
    ) {
        Text(if (buscando) "BUSCANDO TU PC..." else "VINCULAR", fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
    }
    if (buscando) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = Cian)
}

@Composable
private fun FormularioGroq(vm: JarvisVM) {
    var clave by remember { mutableStateOf("") }
    Text(
        "Pega tu clave gratis de Groq (console.groq.com/keys) y JARVIS responderá desde el celular: " +
            "conversar, abrir apps, alarmas, linterna y más. Al vincular la PC se copia sola.",
        color = TextoTenue, fontSize = 13.sp, textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = clave, onValueChange = { clave = it.trim() },
        label = { Text(if (vm.local.disponible) "Clave de Groq (ya guardada)" else "Clave de Groq") },
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { vm.guardarClaveGroq(clave); clave = "" }, enabled = clave.length > 20,
        modifier = Modifier.fillMaxWidth()) { Text("GUARDAR CLAVE") }
}

// =============================================================================== asistente
@Composable
private fun Asistente(vm: JarvisVM, microfono: () -> Unit) {
    var texto by remember { mutableStateOf("") }
    val lista = rememberLazyListState()
    LaunchedEffect(vm.mensajes.size) {
        if (vm.mensajes.isNotEmpty()) lista.animateScrollToItem(vm.mensajes.size - 1)
    }
    val color = when {
        vm.escuchando -> Turquesa
        vm.pensando -> Dorado
        else -> Cian
    }
    val estado = when {
        vm.escuchando -> "ESCUCHANDO"
        vm.pensando -> "PROCESANDO"
        vm.hablando -> "HABLANDO"
        else -> "EN ESPERA"
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(if (vm.mensajes.isEmpty()) 300.dp else 190.dp), contentAlignment = Alignment.Center) {
            Reactor(
                nivel = if (vm.escuchando) vm.nivel else if (vm.hablando) 0.45f else 0f,
                color = color, rapido = vm.pensando,
                modifier = Modifier.size(if (vm.mensajes.isEmpty()) 250.dp else 160.dp).clickable { microfono() },
            )
        }
        Text(estado, style = MaterialTheme3.labelSmall, color = color, modifier = Modifier.align(Alignment.CenterHorizontally))
        if (vm.mensajes.isEmpty()) {
            Text(
                "Toca el reactor o el micrófono y háblame.",
                color = TextoTenue, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = lista) {
            items(vm.mensajes) { m -> Burbuja(m) }
        }
        if (vm.conectadoAPc) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Responder también en la PC", color = TextoTenue, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = vm.hablarEnPc, onCheckedChange = { vm.hablarEnPc = it })
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = texto, onValueChange = { texto = it },
                placeholder = { Text("Escribe una orden...") },
                singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { vm.enviar(texto); texto = "" }),
            )
            Spacer(Modifier.width(8.dp))
            if (texto.isNotBlank()) {
                FilledIconButton(onClick = { vm.enviar(texto); texto = "" }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.Send, "Enviar")
                }
            } else {
                FilledIconButton(
                    onClick = microfono, modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (vm.escuchando) Turquesa else Cian),
                ) {
                    Icon(if (vm.escuchando) Icons.Filled.Stop else Icons.Filled.Mic, "Hablar", tint = Color.Black)
                }
            }
        }
    }
}

@Composable
private fun Burbuja(m: Mensaje) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (m.deMi) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.fillMaxWidth(0.85f).clip(RoundedCornerShape(14.dp))
                .background(if (m.deMi) Color(0xFF0B2E40) else Panel)
                .border(1.dp, if (m.deMi) Color(0xFF145A73) else Color(0xFF1E4A5C), RoundedCornerShape(14.dp))
                .padding(12.dp),
        ) {
            Text(if (m.deMi) "TÚ" else "JARVIS", fontSize = 10.sp, color = if (m.deMi) TextoTenue else Cian, fontFamily = Mono)
            Text(m.texto, color = Texto, fontSize = 15.sp)
        }
    }
}

// =============================================================================== control
@Composable
private fun Control(vm: JarvisVM) {
    LaunchedEffect(Unit) {
        while (isActive) {
            vm.actualizarEstado()
            delay(4_000)
        }
    }
    val e = vm.estado
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Seccion("ESTADO DE ${vm.nombrePc.uppercase()}")
        if (e == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cian)
        } else {
            Medidor("CPU", e.optDouble("cpu", 0.0).toFloat())
            Medidor("RAM", e.optDouble("ram", 0.0).toFloat())
            if (!e.isNull("bateria")) {
                Medidor("BATERÍA" + if (e.optBoolean("cargando")) " ⚡" else "", e.optDouble("bateria", 0.0).toFloat(), invertido = true)
            }
            val clima = e.optString("clima")
            if (clima.isNotBlank() && clima != "null") Text(clima, color = TextoTenue, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Text("Núcleo: " + e.optString("nucleo"), color = TextoTenue, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }

        Seccion("VOLUMEN")
        var volumen by remember(e?.optInt("volumen", -1)) { mutableFloatStateOf(e?.optDouble("volumen", 50.0)?.toFloat() ?: 50f) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.accion("volumen", "silenciar") }) { Icon(Icons.Filled.VolumeOff, "Silenciar", tint = Cian) }
            Slider(
                value = volumen, onValueChange = { volumen = it }, valueRange = 0f..100f,
                onValueChangeFinished = { vm.accion("volumen", volumen.toInt().toString()) },
                colors = SliderDefaults.colors(thumbColor = Cian, activeTrackColor = Cian),
                modifier = Modifier.weight(1f),
            )
            Text("${volumen.toInt()}%", color = Cian, fontFamily = Mono, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
        }

        Seccion("MÚSICA")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            BotonRedondo(Icons.Filled.SkipPrevious, "Anterior") { vm.accion("media", "anterior") }
            BotonRedondo(Icons.Filled.PlayArrow, "Pausa / play", grande = true) { vm.accion("media", "pausa") }
            BotonRedondo(Icons.Filled.SkipNext, "Siguiente") { vm.accion("media", "siguiente") }
        }

        Seccion("BRILLO")
        var brillo by remember { mutableFloatStateOf(70f) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LightMode, null, tint = Cian)
            Slider(
                value = brillo, onValueChange = { brillo = it }, valueRange = 0f..100f,
                onValueChangeFinished = { vm.accion("brillo", brillo.toInt().toString()) },
                colors = SliderDefaults.colors(thumbColor = Cian, activeTrackColor = Cian),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
        }

        Seccion("ACCIONES")
        Cuadricula(
            listOf(
                Triple(Icons.Filled.Minimize, "Escritorio") { vm.accion("ventana", "minimizar_todo") },
                Triple(Icons.Filled.SwapHoriz, "Alt+Tab") { vm.accion("ventana", "cambiar") },
                Triple(Icons.Filled.Screenshot, "Captura") { vm.accion("captura") },
                Triple(Icons.Filled.SportsEsports, "Modo gamer") { vm.accion("rutina", "modo gamer") },
                Triple(Icons.Filled.MenuBook, "Modo estudio") { vm.accion("rutina", "modo estudio") },
                Triple(Icons.Filled.Lock, "Bloquear") { vm.accion("bloquear") },
                Triple(Icons.Filled.Bedtime, "Suspender") { vm.accion("suspender") },
                Triple(Icons.Filled.RestartAlt, "Reiniciar") { vm.accion("reiniciar") },
                Triple(Icons.Filled.PowerSettingsNew, "Apagar") { vm.accion("apagar") },
            ),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Seccion(titulo: String) {
    Text(titulo, style = MaterialTheme3.labelSmall, color = Cian, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
}

@Composable
private fun Medidor(nombre: String, valor: Float, invertido: Boolean = false) {
    val alerta = if (invertido) valor < 20 else valor > 85
    Column(Modifier.padding(vertical = 4.dp)) {
        Row {
            Text(nombre, color = Texto, fontSize = 13.sp, fontFamily = Mono, modifier = Modifier.weight(1f))
            Text("${valor.toInt()}%", color = if (alerta) Rojo else Cian, fontSize = 13.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(
            progress = { (valor / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).padding(top = 4.dp),
            color = if (alerta) Rojo else Cian, trackColor = Panel,
        )
    }
}

@Composable
private fun BotonRedondo(icono: ImageVector, descripcion: String, grande: Boolean = false, alPulsar: () -> Unit) {
    FilledIconButton(
        onClick = alPulsar, modifier = Modifier.size(if (grande) 68.dp else 54.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (grande) Cian else Panel, contentColor = if (grande) Color.Black else Cian),
    ) { Icon(icono, descripcion, modifier = Modifier.size(if (grande) 34.dp else 26.dp)) }
}

@Composable
private fun Cuadricula(botones: List<Triple<ImageVector, String, () -> Unit>>) {
    botones.chunked(3).forEach { fila ->
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            fila.forEach { (icono, nombre, accion) ->
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Panel)
                        .border(1.dp, Color(0xFF1E4A5C), RoundedCornerShape(12.dp))
                        .clickable { accion() }.padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(icono, nombre, tint = if (nombre == "Apagar") Rojo else Cian)
                    Spacer(Modifier.height(6.dp))
                    Text(nombre, color = Texto, fontSize = 12.sp)
                }
            }
            repeat(3 - fila.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

// =============================================================================== pantalla remota
@Composable
private fun PantallaRemota(vm: JarvisVM) {
    val ancho = if (vm.conexion == Conexion.WIFI) 1100 else 800
    LaunchedEffect(vm.conexion) {
        while (isActive) {
            vm.capturarPantalla(ancho)
            delay(if (vm.conexion == Conexion.WIFI) 700 else 1_500)
        }
    }
    val acumulado = remember { floatArrayOf(0f, 0f) }
    var sensibilidad by remember { mutableFloatStateOf(1.6f) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(45)
            if (acumulado[0] != 0f || acumulado[1] != 0f) {
                vm.raton(acumulado[0] * sensibilidad, acumulado[1] * sensibilidad)
                acumulado[0] = 0f
                acumulado[1] = 0f
            }
        }
    }
    var escribir by remember { mutableStateOf("") }
    var rueda by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Box(
            Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black)
                .border(1.dp, Color(0xFF1E4A5C), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val imagen = vm.pantalla
            if (imagen != null) {
                Image(imagen.asImageBitmap(), "Pantalla de la PC", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                Text("Cargando pantalla...", color = TextoTenue)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("TOUCHPAD · toca = clic · doble toque = doble clic · mantén = clic derecho", style = MaterialTheme3.labelSmall, color = TextoTenue)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Box(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Panel)
                    .border(1.dp, Cian.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { cambio, arrastre ->
                            cambio.consume()
                            acumulado[0] += arrastre.x
                            acumulado[1] += arrastre.y
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { vm.clic("izq") },
                            onDoubleTap = { vm.clic("doble") },
                            onLongPress = { vm.clic("der") },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Mouse, null, tint = Cian.copy(alpha = 0.25f), modifier = Modifier.size(48.dp)) }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.width(52.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                BotonRedondo(Icons.Filled.KeyboardArrowUp, "Subir") { rueda++; vm.rueda(3) }
                BotonRedondo(Icons.Filled.KeyboardArrowDown, "Bajar") { rueda--; vm.rueda(-3) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.clic("izq") }, modifier = Modifier.weight(1f)) { Text("CLIC") }
            OutlinedButton(onClick = { vm.clic("der") }, modifier = Modifier.weight(1f)) { Text("DERECHO") }
            OutlinedButton(onClick = { vm.teclas("enter") }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.KeyboardReturn, "Enter") }
            OutlinedButton(onClick = { vm.teclas("backspace") }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Backspace, "Borrar") }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = escribir, onValueChange = { escribir = it }, singleLine = true,
                placeholder = { Text("Escribir en la PC...") }, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { vm.escribirEnPc(escribir); escribir = "" }),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { vm.escribirEnPc(escribir); escribir = "" }, enabled = escribir.isNotEmpty(),
                modifier = Modifier.size(52.dp)) { Icon(Icons.Filled.Keyboard, "Escribir") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sensibilidad", color = TextoTenue, fontSize = 12.sp)
            Slider(value = sensibilidad, onValueChange = { sensibilidad = it }, valueRange = 0.6f..3.5f,
                colors = SliderDefaults.colors(thumbColor = Cian, activeTrackColor = Cian), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

// =============================================================================== apps de la PC
@Composable
private fun AppsPc(vm: JarvisVM) {
    LaunchedEffect(Unit) { if (vm.apps.isEmpty()) vm.cargarApps() }
    var filtro by remember { mutableStateOf("") }
    val lista = vm.apps.filter { filtro.isBlank() || it.contains(filtro, ignoreCase = true) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = filtro, onValueChange = { filtro = it }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text("Buscar programa de la PC...") }, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        if (vm.apps.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cian)
        LazyColumn(Modifier.weight(1f)) {
            items(lista) { nombre ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.accion("abrir_app", nombre) }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Computer, null, tint = Cian, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(nombre, color = Texto, fontSize = 15.sp)
                }
            }
        }
    }
}

// =============================================================================== ajustes
@Composable
private fun Ajustes(vm: JarvisVM) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Seccion("PC VINCULADA")
        if (vm.enlace.vinculado) {
            Text("${vm.nombrePc} — ${estadoLegible(vm.conexion)}", color = Texto)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { vm.conectar() }) { Text("RECONECTAR") }
                OutlinedButton(onClick = { vm.desvincular() }) { Text("DESVINCULAR", color = Rojo) }
            }
        } else {
            Text("En la laptop: clic derecho en el reactor → «Conectar celular».", color = TextoTenue, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            FormularioCodigo(vm)
        }
        Seccion("MODO CELULAR (SIN PC)")
        FormularioGroq(vm)
        Seccion("CÓMO FUNCIONA")
        Text(
            "• En la misma WiFi el celular habla directo con la PC.\n" +
                "• Con datos móviles usa un túnel seguro de Cloudflare; la dirección viaja cifrada.\n" +
                "• Si la PC está apagada, JARVIS responde desde el celular con Groq.\n" +
                "• Puedes poner JARVIS como asistente: Ajustes de Android → Apps → Apps predeterminadas → Asistente digital.",
            color = TextoTenue, fontSize = 13.sp, lineHeight = 19.sp,
        )
    }
}

private fun estadoLegible(c: Conexion) = when (c) {
    Conexion.WIFI -> "conectada por WiFi"
    Conexion.INTERNET -> "conectada por internet"
    Conexion.BUSCANDO -> "buscando..."
    Conexion.DESCONECTADA -> "no responde"
    Conexion.SIN_VINCULAR -> "sin vincular"
}
