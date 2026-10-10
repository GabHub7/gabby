package com.gabby.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Nilai rahasia (API key, token) dienkripsi AES-GCM dengan kunci di Android Keystore. Tidak pernah di-log. */
class AndroidKv(ctx: Context) : Kv {
    private val prefs = ctx.getSharedPreferences("gabby", Context.MODE_PRIVATE)
    private val alias = "gabby_secret_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }

    override fun get(k: String): String? = prefs.getString("p_$k", null)
    override fun put(k: String, v: String?) { prefs.edit().apply { if (v == null) remove("p_$k") else putString("p_$k", v) }.apply() }

    override fun getSecret(k: String): String? = try {
        val raw = Base64.decode(prefs.getString("s_$k", null) ?: return null, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        String(c.doFinal(raw, 12, raw.size - 12))
    } catch (e: Exception) { null }

    override fun putSecret(k: String, v: String?) {
        if (v == null) { prefs.edit().remove("s_$k").apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(v.toByteArray())
        prefs.edit().putString("s_$k", Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }
}
