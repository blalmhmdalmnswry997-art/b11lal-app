package com.example

import com.example.crypto.CryptoManager
import com.example.mesh.MeshPacket
import com.example.mesh.PacketType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CipherMeshCryptoAndMeshTest {

    @Test
    fun testAesGcmEncryptionDecryptionRoundTrip() {
        val secretKey = CryptoManager.generateAesKey()
        val originalText = "Top-Secret Offline Tactical Mesh Packet 2026"

        val encryptedBase64 = CryptoManager.encryptText(originalText, secretKey)
        assertNotNull(encryptedBase64)
        assertNotEquals(originalText, encryptedBase64)

        val decryptedText = CryptoManager.decryptText(encryptedBase64, secretKey)
        assertEquals(originalText, decryptedText)
    }

    @Test
    fun testEcdhKeyAgreementDerivesMatchingKey() {
        // Node 1 & Node 2 generate independent key pairs
        val node1KeyPair = CryptoManager.generateEcKeyPair()
        val node2KeyPair = CryptoManager.generateEcKeyPair()

        // Node 1 and Node 2 exchange public keys
        val keyAgreement1 = javax.crypto.KeyAgreement.getInstance("ECDH")
        keyAgreement1.init(node1KeyPair.private)
        keyAgreement1.doPhase(node2KeyPair.public, true)
        val secret1 = keyAgreement1.generateSecret()

        val keyAgreement2 = javax.crypto.KeyAgreement.getInstance("ECDH")
        keyAgreement2.init(node2KeyPair.private)
        keyAgreement2.doPhase(node1KeyPair.public, true)
        val secret2 = keyAgreement2.generateSecret()

        // Both nodes compute the identical shared secret
        assertTrue(secret1.contentEquals(secret2))
    }

    @Test
    fun testMeshPacketSerializationAndDeserialization() {
        val packetId = UUID.randomUUID().toString()
        val originalPacket = MeshPacket(
            packetId = packetId,
            sourceNodeId = "node-alpha",
            sourceNodeName = "Ghost-A",
            destinationNodeId = "node-bravo",
            ttl = 5,
            hopCount = 1,
            relayedBy = listOf("node-relay-1"),
            packetType = PacketType.TEXT_MESSAGE,
            payloadCiphertext = "dGVzdC1jaXBoZXJ0ZXh0",
            extraMeta = "E2EE-GCM"
        )

        val bytes = originalPacket.toByteArray()
        assertTrue(bytes.isNotEmpty())

        val deserialized = MeshPacket.fromByteArray(bytes)
        assertNotNull(deserialized)
        assertEquals(originalPacket.packetId, deserialized!!.packetId)
        assertEquals(originalPacket.sourceNodeId, deserialized.sourceNodeId)
        assertEquals(originalPacket.sourceNodeName, deserialized.sourceNodeName)
        assertEquals(originalPacket.destinationNodeId, deserialized.destinationNodeId)
        assertEquals(originalPacket.ttl, deserialized.ttl)
        assertEquals(originalPacket.hopCount, deserialized.hopCount)
        assertEquals(originalPacket.relayedBy, deserialized.relayedBy)
        assertEquals(originalPacket.packetType, deserialized.packetType)
        assertEquals(originalPacket.payloadCiphertext, deserialized.payloadCiphertext)
        assertEquals(originalPacket.extraMeta, deserialized.extraMeta)
    }

    @Test
    fun testCryptographicFingerprintFormat() {
        val keyPair = CryptoManager.generateEcKeyPair()
        val fingerprint = CryptoManager.computeFingerprint(keyPair.public.encoded)
        assertNotNull(fingerprint)
        assertTrue(fingerprint.contains(" : "))
        assertEquals(8, fingerprint.split(" : ").size)
    }
}
