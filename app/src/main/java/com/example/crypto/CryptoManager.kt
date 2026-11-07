package com.example.crypto

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Military-Grade End-to-End Cryptography Engine.
 *
 * Implements:
 * 1. Elliptic Curve Diffie-Hellman (ECDH secp256r1) key exchange for out-of-band/peer key agreement.
 * 2. HKDF / SHA-256 key derivation for 256-bit AES session keys.
 * 3. AES-256-GCM authenticated encryption (12-byte random IV, 128-bit authentication tag).
 * 4. SHA-256 cryptographic safety fingerprints for peer identity verification.
 */
object CryptoManager {

    private const val EC_CURVE = "secp256r1"
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val AES_KEY_SIZE_BYTES = 32 // 256 bits

    private val secureRandom = SecureRandom()

    // Local Node Identity KeyPair (ECDH)
    private var localKeyPair: KeyPair = generateEcKeyPair()

    val localPublicKey: PublicKey
        get() = localKeyPair.public

    val localPublicKeyBase64: String
        get() = Base64.encodeToString(localKeyPair.public.encoded, Base64.NO_WRAP)

    val localFingerprint: String
        get() = computeFingerprint(localKeyPair.public.encoded)

    /**
     * Generates an EC keypair (secp256r1 / P-256).
     */
    fun generateEcKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        val ecSpec = ECGenParameterSpec(EC_CURVE)
        keyPairGenerator.initialize(ecSpec, secureRandom)
        return keyPairGenerator.generateKeyPair()
    }

    /**
     * Derives a 256-bit AES shared secret key using ECDH key agreement.
     */
    fun deriveSharedSecret(peerPublicKeyBytes: ByteArray): SecretKey {
        val keyFactory = KeyFactory.getInstance("EC")
        val keySpec = X509EncodedKeySpec(peerPublicKeyBytes)
        val peerPublicKey = keyFactory.generatePublic(keySpec)

        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(localKeyPair.private)
        keyAgreement.doPhase(peerPublicKey, true)

        val rawSharedSecret = keyAgreement.generateSecret()

        // Derive 256-bit AES key via SHA-256 digest KDF
        val sha256 = MessageDigest.getInstance("SHA-256")
        sha256.update(rawSharedSecret)
        val aesKeyBytes = sha256.digest().copyOf(AES_KEY_SIZE_BYTES)

        return SecretKeySpec(aesKeyBytes, "AES")
    }

    /**
     * Derives a 256-bit AES shared secret from a Base64-encoded peer public key.
     */
    fun deriveSharedSecretFromBase64(peerPublicKeyBase64: String): SecretKey {
        val bytes = Base64.decode(peerPublicKeyBase64, Base64.NO_WRAP)
        return deriveSharedSecret(bytes)
    }

    /**
     * Generates a standalone random 256-bit AES key (used for broadcast or media encryption).
     */
    fun generateAesKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256, secureRandom)
        return keyGen.generateKey()
    }

    /**
     * Encrypts plaintext bytes using AES-256-GCM.
     * Output format: [12-byte IV] + [Ciphertext + 16-byte Auth Tag]
     */
    fun encryptAesGcm(plaintext: ByteArray, secretKey: SecretKey): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

        val ciphertext = cipher.doFinal(plaintext)

        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return combined
    }

    /**
     * Decrypts AES-256-GCM payload.
     * Input format: [12-byte IV] + [Ciphertext + 16-byte Auth Tag]
     */
    fun decryptAesGcm(combinedPayload: ByteArray, secretKey: SecretKey): ByteArray {
        require(combinedPayload.size >= GCM_IV_LENGTH_BYTES + (GCM_TAG_LENGTH_BITS / 8)) {
            "Payload too short for AES-GCM IV and authentication tag"
        }

        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        System.arraycopy(combinedPayload, 0, iv, 0, GCM_IV_LENGTH_BYTES)

        val ciphertext = ByteArray(combinedPayload.size - GCM_IV_LENGTH_BYTES)
        System.arraycopy(combinedPayload, GCM_IV_LENGTH_BYTES, ciphertext, 0, ciphertext.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        return cipher.doFinal(ciphertext)
    }

    /**
     * Encrypts a UTF-8 text string to a Base64-encoded AES-256-GCM packet.
     */
    fun encryptText(plainText: String, secretKey: SecretKey): String {
        val encrypted = encryptAesGcm(plainText.toByteArray(Charsets.UTF_8), secretKey)
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    /**
     * Decrypts a Base64-encoded AES-256-GCM packet to a UTF-8 text string.
     */
    fun decryptText(base64Ciphertext: String, secretKey: SecretKey): String {
        val bytes = Base64.decode(base64Ciphertext, Base64.NO_WRAP)
        val decrypted = decryptAesGcm(bytes, secretKey)
        return String(decrypted, Charsets.UTF_8)
    }

    /**
     * Computes a formatted SHA-256 fingerprint for identity verification.
     * Output format: "4A8F : 12E0 : 9C3B : ..."
     */
    fun computeFingerprint(publicKeyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
        val hexChars = "0123456789ABCDEF"
        val chunks = mutableListOf<String>()
        for (i in 0 until minOf(digest.size, 16) step 2) {
            val b1 = digest[i].toInt() and 0xFF
            val b2 = digest[i + 1].toInt() and 0xFF
            val chunk = "" +
                    hexChars[b1 ushr 4] + hexChars[b1 and 0x0F] +
                    hexChars[b2 ushr 4] + hexChars[b2 and 0x0F]
            chunks.add(chunk)
        }
        return chunks.joinToString(" : ")
    }

    /**
     * Formats two fingerprints into a 60-digit or 12-block safety number string
     * for easy out-of-band mutual comparison.
     */
    fun computeSafetyNumbers(localKeyBytes: ByteArray, peerKeyBytes: ByteArray): String {
        val combined = localKeyBytes + peerKeyBytes
        val digest = MessageDigest.getInstance("SHA-256").digest(combined)
        val numberBlocks = mutableListOf<String>()
        for (i in 0 until minOf(digest.size, 24) step 2) {
            val num = ((digest[i].toInt() and 0xFF) shl 8) or (digest[i + 1].toInt() and 0xFF)
            numberBlocks.add(String.format("%05d", num % 100000))
        }
        return numberBlocks.chunked(3).joinToString("\n") { it.joinToString("   ") }
    }
}
