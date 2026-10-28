package com.abnerga7.jarvis.ui

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

val Fondo = Color(0xFF04101A)
val Panel = Color(0xFF081C2A)
val Cian = Color(0xFF00D8FF)
val CianClaro = Color(0xFF7FE9FF)
val Turquesa = Color(0xFF29FFD8)
val Dorado = Color(0xFFFFB000)
val Rojo = Color(0xFFFF4B3E)
val Texto = Color(0xFFD9F6FF)
val TextoTenue = Color(0xFF5E8A9C)

private val Colores = darkColorScheme(
    primary = Cian,
    onPrimary = Color.Black,
    secondary = Dorado,
    onSecondary = Color.Black,
    tertiary = Turquesa,
    background = Fondo,
    onBackground = Texto,
    surface = Fondo,
    onSurface = Texto,
    surfaceVariant = Panel,
    onSurfaceVariant = Texto,
    surfaceContainer = Panel,
    surfaceContainerHigh = Panel,
    outline = Color(0xFF1E4A5C),
    error = Rojo,
)

val Mono = FontFamily.Monospace

private val Tipos = Typography(
    titleLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 3.sp),
    titleMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 1.5.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.sp),
)

@Composable
fun TemaJarvis(contenido: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colores, typography = Tipos, content = contenido)
}

/** El reactor Arc animado: gira siempre, acelera al pensar y late con la voz. */
@Composable
fun Reactor(nivel: Float, color: Color, rapido: Boolean, modifier: Modifier = Modifier) {
    val transicion = rememberInfiniteTransition(label = "reactor")
    val giro by transicion.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (rapido) 2200 else 9000, easing = LinearEasing)), label = "giro",
    )
    val pulso by transicion.animateFloat(
        initialValue = 0.94f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "pulso",
    )
    val nivelSuave by animateFloatAsState(nivel, label = "nivel")
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0)
            val r1 = r * 0.90f
            val r2 = if (i % 5 == 0) r * 0.99f else r * 0.95f
            drawLine(
                color.copy(alpha = if (i % 5 == 0) 0.9f else 0.3f),
                Offset(c.x + r1 * cos(a).toFloat(), c.y + r1 * sin(a).toFloat()),
                Offset(c.x + r2 * cos(a).toFloat(), c.y + r2 * sin(a).toFloat()),
                strokeWidth = r * 0.012f,
            )
        }
        fun arco(radio: Float, inicio: Float, barrido: Float, ancho: Float, col: Color) = drawArc(
            col, inicio, barrido, false,
            topLeft = Offset(c.x - radio, c.y - radio), size = Size(radio * 2, radio * 2),
            style = Stroke(width = ancho, cap = StrokeCap.Round),
        )
        for (k in 0 until 3) arco(r * 0.80f, giro + k * 120f, 80f, r * 0.045f, color)
        for (k in 0 until 24) arco(r * 0.68f, -giro * 0.8f + k * 15f, 7f, r * 0.018f, color.copy(alpha = if (k % 4 == 0) 0.9f else 0.4f))
        for (k in 0 until 10) arco(r * 0.55f, giro * 0.25f + k * 36f + 4f, 24f, r * 0.07f, color.copy(alpha = if (k % 2 == 0) 0.35f else 0.6f))
        val nucleo = r * 0.26f * pulso * (1f + nivelSuave * 0.55f)
        drawCircle(
            Brush.radialGradient(listOf(Color.White, color, color.copy(alpha = 0f)), center = c, radius = nucleo * 1.9f),
            radius = nucleo * 1.9f, center = c,
        )
        drawCircle(Color.White.copy(alpha = 0.95f), radius = nucleo * 0.42f, center = c)
    }
}
