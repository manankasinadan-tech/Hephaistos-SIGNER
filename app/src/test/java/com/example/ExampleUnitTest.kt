package com.example

import com.example.signer.core.AospKeyGenerator
import com.example.signer.core.CryptoUtils
import com.example.signer.core.KeysetParams
import com.example.signer.core.RomFileType
import com.example.signer.core.RomInspectorEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.SecureRandom

class ExampleUnitTest {

    @Test
    fun testSha256Hashing() {
        val input = "HephaistOS SIGNER Core Test".toByteArray(Charsets.UTF_8)
        val hash = CryptoUtils.sha256Hex(input)
        assertNotNull(hash)
        assertEquals(64, hash.length)
    }

    @Test
    fun testRomFileTypeDetection() {
        val zipFile = File("fake_rom.zip")
        val type = RomInspectorEngine.detectFileType(zipFile)
        assertNotNull(type)
    }

    @Test
    fun testAvbPublicKeyStructure() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()

        val avbBytes = AospKeyGenerator.generateAvbPublicKeyBytes(kp.public, 2048)
        assertTrue(avbBytes.isNotEmpty())
        // AVB RSA public key structure starts with 4-byte key_num_bits
        assertTrue(avbBytes.size >= 8)
    }
}
