package com.abnerga7.jarvis

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Mismo cifrado que tunel.py en la PC (AES-256-GCM).
 * Formato de cada mensaje: base64( nonce[12] + texto_cifrado + etiqueta[16] ).
 */
object Cripto {
    private const val ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun sha256(texto: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(texto.toByteArray(Charsets.UTF_8))

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    /** Clave derivada de un secreto (por ejemplo, el código de vinculación). */
    fun claveDe(texto: String): ByteArray = sha256("JARVIS|$texto")

    /** Nombre del buzón público en ntfy.sh; no revela el secreto. */
    fun temaDe(texto: String): String = "jarvis-" + sha256("TEMA|$texto").hex().take(24)

    /** "k7p2-qx9m " -> "K7P2QX9M" */
    fun normalizarCodigo(codigo: String): String = codigo.uppercase().filter { it in ALFABETO }

    fun descifrar(mensaje: String, clave: ByteArray): JSONObject {
        val crudo = Base64.decode(mensaje.trim(), Base64.DEFAULT)
        val cifrador = Cipher.getInstance("AES/GCM/NoPadding")
        cifrador.init(Cipher.DECRYPT_MODE, SecretKeySpec(clave, "AES"), GCMParameterSpec(128, crudo, 0, 12))
        val plano = cifrador.doFinal(crudo, 12, crudo.size - 12)
        return JSONObject(String(plano, Charsets.UTF_8))
    }

    /** Valores de referencia calculados en la PC (tunel.py) para comprobar que ambos lados coinciden. */
    fun autoprueba(): Boolean =
        claveDe("K7P2QX9M").hex() == "5585a78de72876f6484450f4ceb097131d2985d3c71f84b2b17fc8a6d6484858" &&
            temaDe("K7P2QX9M") == "jarvis-ddd89cc327341d01007e4795"
}
