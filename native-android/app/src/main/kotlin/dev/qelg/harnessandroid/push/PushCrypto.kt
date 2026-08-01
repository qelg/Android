package dev.qelg.harnessandroid.push

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Encrypts UnifiedPush content for this installation with a non-exportable Android Keystore key. */
object PushCrypto {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "harness-unifiedpush-v1"
    private const val VERSION = "P-256-HKDF-SHA256-AES-256-GCM"
    private val info = "harness-unifiedpush-v1".toByteArray(StandardCharsets.UTF_8)

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun publicKey(): String {
        check(isSupported()) { "Encrypted UnifiedPush requires Android 12 or newer" }
        val entry = keyStore().getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
        val publicKey = entry?.certificate?.publicKey ?: generateKeyPair().public
        return encode(publicKey.encoded)
    }

    fun decrypt(message: ByteArray, instanceId: String): String? = runCatching {
        val envelope = Json.parseToJsonElement(message.toString(StandardCharsets.UTF_8)).jsonObject
        check(envelope["version"]?.jsonPrimitive?.contentOrNull == VERSION)
        val ephemeral = decodePublicKey(required(envelope, "ephemeral_public_key"))
        val nonce = decode(required(envelope, "nonce"))
        val ciphertext = decode(required(envelope, "ciphertext"))
        require(nonce.size == 12)
        val privateKey =
            (keyStore().getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry)?.privateKey
                ?: return null
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(ephemeral, true)
        val secret = agreement.generateSecret()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(hkdf(secret), "AES"),
            GCMParameterSpec(128, nonce),
        )
        cipher.updateAAD(instanceId.toByteArray(StandardCharsets.UTF_8))
        cipher.doFinal(ciphertext).toString(StandardCharsets.UTF_8)
    }.getOrNull()

    private fun generateKeyPair() =
        KeyPairGenerator.getInstance("EC", KEYSTORE)
            .apply {
                initialize(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                        .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build()
                )
            }
            .generateKeyPair()

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun hkdf(secret: ByteArray): ByteArray {
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(javax.crypto.spec.SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = extract.doFinal(secret)
        val expand = Mac.getInstance("HmacSHA256")
        expand.init(javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256"))
        return expand.doFinal(info + byteArrayOf(1)).copyOf(32)
    }

    private fun required(objectValue: kotlinx.serialization.json.JsonObject, key: String): String =
        requireNotNull(objectValue[key]?.jsonPrimitive?.contentOrNull)

    private fun decodePublicKey(encoded: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decode(encoded)))

    private fun encode(value: ByteArray): String =
        Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
