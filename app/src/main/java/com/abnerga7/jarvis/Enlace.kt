package com.abnerga7.jarvis

import android.content.Context
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

enum class Conexion { SIN_VINCULAR, BUSCANDO, WIFI, INTERNET, DESCONECTADA }

class RespuestaPc(val codigo: Int, val cuerpo: ByteArray) {
    val json: JSONObject by lazy {
        try { JSONObject(String(cuerpo, Charsets.UTF_8)) } catch (e: Exception) { JSONObject() }
    }
}

/**
 * Conexión con el JARVIS de la PC.
 * 1) Misma WiFi: http://IP:47654 directo (la IP se encuentra sola con un mensaje UDP "JARVIS?").
 * 2) Datos móviles: https://xxxx.trycloudflare.com (la PC publica la dirección cifrada en ntfy.sh).
 */
class Enlace(contexto: Context) {
    private val prefs = contexto.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    var alCambiar: (Conexion) -> Unit = {}

    @Volatile var conexion: Conexion = if (vinculado) Conexion.DESCONECTADA else Conexion.SIN_VINCULAR
        private set(valor) {
            field = valor
            alCambiar(valor)
        }

    @Volatile private var base: String? = null

    val vinculado: Boolean get() = !prefs.getString("token", null).isNullOrEmpty()
    val nombrePc: String get() = prefs.getString("pc", null) ?: "tu PC"
    val trato: String get() = prefs.getString("trato", null) ?: "señor"
    private val token: String? get() = prefs.getString("token", null)

    // ------------------------------------------------------------------ red
    private fun http(
        metodo: String, base: String, ruta: String, cuerpo: JSONObject? = null,
        espera: Int = 60_000, conToken: Boolean = true,
    ): RespuestaPc {
        val c = URL(base + ruta).openConnection() as HttpURLConnection
        try {
            c.requestMethod = metodo
            c.connectTimeout = 5_000
            c.readTimeout = espera
            c.setRequestProperty("Accept", "application/json")
            if (conToken) token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (cuerpo != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(cuerpo.toString().toByteArray(Charsets.UTF_8)) }
            }
            val codigo = c.responseCode
            val flujo = if (codigo < 400) c.inputStream else c.errorStream
            val datos = flujo?.use { it.readBytes() } ?: ByteArray(0)
            return RespuestaPc(codigo, datos)
        } finally {
            c.disconnect()
        }
    }

    private fun responde(b: String, espera: Int = 4_000): Int = try {
        http("GET", b, "/api/estado", espera = espera).codigo
    } catch (e: Exception) {
        -1
    }

    /** Busca la PC en la red WiFi actual. Devuelve "http://ip:puerto" o null. */
    private fun descubrirEnWifi(): String? = try {
        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = 1_200
            val datos = "JARVIS?".toByteArray()
            s.send(DatagramPacket(datos, datos.size, InetAddress.getByName("255.255.255.255"), 47655))
            val buf = ByteArray(1024)
            val paquete = DatagramPacket(buf, buf.size)
            s.receive(paquete)
            val info = JSONObject(String(paquete.data, 0, paquete.length, Charsets.UTF_8))
            "http://${paquete.address.hostAddress}:${info.optInt("puerto", 47654)}"
        }
    } catch (e: Exception) {
        null
    }

    /** Lee el último mensaje cifrado de un buzón de ntfy.sh. */
    private fun leerBuzon(tema: String, clave: ByteArray, desde: String): JSONObject? = try {
        val r = http("GET", "https://ntfy.sh", "/$tema/json?poll=1&since=$desde", espera = 8_000, conToken = false)
        var ultimo: JSONObject? = null
        if (r.codigo == 200) {
            String(r.cuerpo, Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.forEach { linea ->
                val j = JSONObject(linea)
                if (j.optString("event") == "message") {
                    try { ultimo = Cripto.descifrar(j.getString("message"), clave) } catch (_: Exception) { }
                }
            }
        }
        ultimo
    } catch (e: Exception) {
        null
    }

    private fun urlDelCanal(): String? {
        val id = prefs.getString("canal_id", null) ?: return null
        val clave = prefs.getString("canal_clave", null) ?: return null
        val datos = leerBuzon(Cripto.temaDe(id), Base64.decode(clave, Base64.DEFAULT), "12h") ?: return null
        return datos.optString("url").takeIf { it.startsWith("https://") }
    }

    // ------------------------------------------------------------------ conectar
    suspend fun conectar(): Conexion = withContext(Dispatchers.IO) {
        if (!vinculado) {
            conexion = Conexion.SIN_VINCULAR
            return@withContext conexion
        }
        conexion = Conexion.BUSCANDO
        val candidatos = mutableListOf<Pair<String, Conexion>>()
        prefs.getString("ip", null)?.let { candidatos += "http://$it:${prefs.getInt("puerto", 47654)}" to Conexion.WIFI }
        prefs.getString("url", null)?.let { candidatos += it to Conexion.INTERNET }
        for ((b, tipo) in candidatos) {
            val codigo = responde(b, if (tipo == Conexion.WIFI) 2_000 else 6_000)
            if (codigo == 200) return@withContext listo(b, tipo)
            if (codigo == 401) return@withContext olvidar()
        }
        descubrirEnWifi()?.let { b ->
            if (responde(b) == 200) {
                prefs.edit().putString("ip", URL(b).host).putInt("puerto", URL(b).port).apply()
                return@withContext listo(b, Conexion.WIFI)
            }
        }
        urlDelCanal()?.let { url ->
            prefs.edit().putString("url", url).apply()
            if (responde(url, 8_000) == 200) return@withContext listo(url, Conexion.INTERNET)
        }
        base = null
        conexion = Conexion.DESCONECTADA
        conexion
    }

    private fun listo(b: String, tipo: Conexion): Conexion {
        base = b
        conexion = tipo
        return tipo
    }

    private fun olvidar(): Conexion {
        prefs.edit().remove("token").apply()
        base = null
        conexion = Conexion.SIN_VINCULAR
        return conexion
    }

    fun desvincular() {
        prefs.edit().remove("token").remove("ip").remove("url").remove("canal_id").remove("canal_clave").apply()
        base = null
        conexion = Conexion.SIN_VINCULAR
    }

    // ------------------------------------------------------------------ vincular
    /** Devuelve null si todo salió bien, o el mensaje de error. */
    suspend fun vincular(codigoEscrito: String): String? = withContext(Dispatchers.IO) {
        val codigo = Cripto.normalizarCodigo(codigoEscrito)
        if (codigo.length != 8) return@withContext "El código tiene 8 letras y números (ej. K7P2-QX9M)."
        conexion = Conexion.BUSCANDO
        val candidatos = mutableListOf<String>()
        descubrirEnWifi()?.let { candidatos += it }
        leerBuzon(Cripto.temaDe(codigo), Cripto.claveDe(codigo), "15m")?.let { j ->
            j.optString("ip").takeIf { it.isNotEmpty() && it != "null" }?.let {
                candidatos += "http://$it:${j.optInt("puerto", 47654)}"
            }
            j.optString("url").takeIf { it.startsWith("https://") }?.let { candidatos += it }
        }
        if (candidatos.isEmpty()) {
            conexion = Conexion.SIN_VINCULAR
            return@withContext "No encuentro tu PC. Revisa que JARVIS esté abierto mostrando el código y que tengas internet."
        }
        var error = "No pude conectar con la PC."
        val pedido = JSONObject().put("pin", codigo).put("dispositivo", "${Build.MANUFACTURER} ${Build.MODEL}")
        for (b in candidatos.distinct()) {
            try {
                val r = http("POST", b, "/api/emparejar", pedido, espera = 12_000, conToken = false)
                if (r.codigo == 200) {
                    guardar(r.json, b)
                    conexion = if (b.startsWith("https://")) Conexion.INTERNET else Conexion.WIFI
                    return@withContext null
                }
                error = r.json.optString("error", error)
                if (r.codigo == 403 || r.codigo == 429) break
            } catch (e: Exception) {
                // se prueba la siguiente forma de llegar a la PC
            }
        }
        conexion = Conexion.SIN_VINCULAR
        error
    }

    private fun guardar(j: JSONObject, b: String) {
        val e = prefs.edit()
            .putString("token", j.getString("token"))
            .putString("pc", j.optString("pc", "tu PC"))
            .putString("trato", j.optString("trato", "señor"))
            .putInt("puerto", j.optInt("puerto", 47654))
        j.optString("ip").takeIf { it.isNotEmpty() && it != "null" }?.let { e.putString("ip", it) }
        j.optString("url").takeIf { it.startsWith("https://") }?.let { e.putString("url", it) }
        j.optString("canal_id").takeIf { it.length > 8 }?.let { e.putString("canal_id", it) }
        j.optString("canal_clave").takeIf { it.length > 8 }?.let { e.putString("canal_clave", it) }
        j.optString("groq_api_key").takeIf { it.length > 20 && it != "null" }?.let {
            if (prefs.getString("groq", "").isNullOrEmpty()) e.putString("groq", it)
        }
        e.apply()
        base = b
    }

    // ------------------------------------------------------------------ pedidos
    /** Hace un pedido a la PC; si la conexión se cayó, reconecta una vez (WiFi ↔ internet). */
    suspend fun pedir(metodo: String, ruta: String, cuerpo: JSONObject? = null, espera: Int = 90_000): RespuestaPc =
        withContext(Dispatchers.IO) {
            val actual = base ?: run { conectar(); base } ?: throw IOException("No encuentro tu PC")
            try {
                val r = http(metodo, actual, ruta, cuerpo, espera)
                if (r.codigo == 401) olvidar()
                r
            } catch (e: IOException) {
                base = null
                conectar()
                val nueva = base ?: throw e
                http(metodo, nueva, ruta, cuerpo, espera)
            }
        }
}
