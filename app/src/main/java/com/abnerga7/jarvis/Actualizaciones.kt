package com.abnerga7.jarvis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Actualizacion(val version: String, val url: String, val pagina: String)

/** Busca en GitHub Releases si hay una versión más nueva de la app. */
object Actualizaciones {
    private const val API = "https://api.github.com/repos/AbnerGA7/JARVIS-Android/releases/latest"

    private fun numero(v: String): List<Int> = Regex("\\d+").findAll(v).map { it.value.toInt() }.take(3).toList()

    fun esMasNueva(remota: String, local: String): Boolean {
        val a = numero(remota)
        val b = numero(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    suspend fun buscar(): Actualizacion? = withContext(Dispatchers.IO) {
        try {
            val c = URL(API).openConnection() as HttpURLConnection
            c.connectTimeout = 6_000
            c.readTimeout = 8_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            val texto = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            c.disconnect()
            val j = JSONObject(texto)
            val version = j.optString("tag_name").trimStart('v', 'V')
            if (!esMasNueva(version, BuildConfig.VERSION_NAME)) return@withContext null
            val activos = j.optJSONArray("assets")
            var apk = ""
            if (activos != null) for (i in 0 until activos.length()) {
                val a = activos.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url")
            }
            Actualizacion(version, apk.ifEmpty { j.optString("html_url") }, j.optString("html_url"))
        } catch (e: Exception) {
            null
        }
    }
}
