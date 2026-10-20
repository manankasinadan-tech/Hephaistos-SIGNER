package com.example.signer.core

import android.content.Context
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Keyset parameters for AOSP Rom & App signing.
 */
data class KeysetParams(
    val commonName: String = "HephaistOS Platform",
    val organization: String = "HephaistOS Dev Group",
    val organizationalUnit: String = "Security Core",
    val locality: String = "Android",
    val state: String = "Custom",
    val country: String = "FR",
    val validityYears: Int = 25,
    val keySize: Int = 4096
)

data class GeneratedKeyResult(
    val keyName: String,
    val certFile: File,
    val pk8File: File,
    val pemFile: File,
    val fingerprintSha256: String
)

/**
 * Generates official AOSP keyset:
 * - platform (system core & framework-res)
 * - media (media provider, download provider)
 * - shared (home, contacts, shared UID apps)
 * - releasekey (default fallback for other unbundled apps)
 * - networkstack (networking stack)
 * - testkey (compatibility test suite fallback)
 * - avb (AVB 2.0 / vbmeta key pair)
 */
object AospKeyGenerator {

    val AOSP_KEY_ROLES = listOf(
        "platform",
        "media",
        "shared",
        "releasekey",
        "networkstack",
        "testkey",
        "avb"
    )

    fun generateCompleteKeyset(
        context: Context,
        params: KeysetParams,
        onProgress: (keyName: String, current: Int, total: Int) -> Unit
    ): List<GeneratedKeyResult> {
        val keysetDir = HephaistosStorage.getKeysetDir(context)
        val kpg = KeyPairGenerator.getInstance("RSA")
        val random = SecureRandom()
        val results = mutableListOf<GeneratedKeyResult>()

        val total = AOSP_KEY_ROLES.size
        AOSP_KEY_ROLES.forEachIndexed { index, role ->
            val size = if (role == "avb") 4096 else params.keySize
            onProgress(role, index + 1, total)

            kpg.initialize(size, random)
            val keyPair = kpg.generateKeyPair()

            val roleParams = params.copy(
                commonName = "${params.commonName} ($role)"
            )

            // Generate X.509 self-signed Certificate DER
            val certDer = generateSelfSignedX509Der(keyPair, roleParams)
            val certPem = toPem("CERTIFICATE", certDer)

            // Private key in PKCS#8 DER (Standard format returned by KeyPair in Java)
            val pk8Der = keyPair.private.encoded
            val privatePem = toPem("RSA PRIVATE KEY", pk8Der)

            // Write files
            val certFile = File(keysetDir, "$role.x509.pem")
            certFile.writeText(certPem)

            val pk8File = File(keysetDir, "$role.pk8")
            pk8File.writeBytes(pk8Der)

            val pemFile = File(keysetDir, "$role.pem")
            pemFile.writeText(privatePem)

            // If AVB, also output AVB public key binary descriptor
            if (role == "avb") {
                val avbPubBin = File(keysetDir, "avb_pk.bin")
                avbPubBin.writeBytes(generateAvbPublicKeyBytes(keyPair.public, size))
            }

            val sha256 = CryptoUtils.sha256Hex(certDer).chunked(2).joinToString(":")

            results.add(
                GeneratedKeyResult(
                    keyName = role,
                    certFile = certFile,
                    pk8File = pk8File,
                    pemFile = pemFile,
                    fingerprintSha256 = sha256
                )
            )
        }

        return results
    }

    private fun generateSelfSignedX509Der(keyPair: KeyPair, params: KeysetParams): ByteArray {
        val now = System.currentTimeMillis()
        val validityMillis = params.validityYears.toLong() * 365L * 24L * 3600L * 1000L
        val notBefore = Date(now - 60_000L) // 1 minute back for clock skew
        val notAfter = Date(now + validityMillis)

        val dateFormat = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val serialNumber = BigInteger(64, SecureRandom()).abs()

        val subjectDnDer = encodeDn(params)

        // TBSCertificate assembly
        val tbsOut = ByteArrayOutputStream()

        // Version: [0] EXPLICIT INTEGER 2 (v3)
        tbsOut.write(byteArrayOf(0xA0.toByte(), 0x03, 0x02, 0x01, 0x02))

        // Serial Number: INTEGER
        writeDerInteger(tbsOut, serialNumber)

        // Signature Algorithm Identifier: 1.2.840.113549.1.1.11 (SHA256withRSA)
        writeSha256WithRsaAlgorithmIdentifier(tbsOut)

        // Issuer: Name
        tbsOut.write(subjectDnDer)

        // Validity: SEQUENCE { notBefore UTCTime, notAfter UTCTime }
        val validityOut = ByteArrayOutputStream()
        writeUtcTime(validityOut, dateFormat.format(notBefore))
        writeUtcTime(validityOut, dateFormat.format(notAfter))
        writeDerSequence(tbsOut, validityOut.toByteArray())

        // Subject: Name
        tbsOut.write(subjectDnDer)

        // SubjectPublicKeyInfo (already DER encoded in public key)
        tbsOut.write(keyPair.public.encoded)

        val tbsBytes = encodeDerSequence(tbsOut.toByteArray())

        // Sign the TBS
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(tbsBytes)
        val signatureValue = signer.sign()

        // Assembly of Certificate: SEQUENCE { TBS, Algorithm, BIT STRING signature }
        val certOut = ByteArrayOutputStream()
        certOut.write(tbsBytes)
        writeSha256WithRsaAlgorithmIdentifier(certOut)

        // Bit string of signature (0 unused bits)
        val bitStringOut = ByteArrayOutputStream()
        bitStringOut.write(0x00) // unused bits
        bitStringOut.write(signatureValue)
        writeDer(certOut, 0x03, bitStringOut.toByteArray())

        return encodeDerSequence(certOut.toByteArray())
    }

    private fun encodeDn(params: KeysetParams): ByteArray {
        val dnOut = ByteArrayOutputStream()
        writeRdn(dnOut, "2.5.4.6", params.country, 0x13) // C: PrintableString
        writeRdn(dnOut, "2.5.4.8", params.state, 0x0C)   // ST: UTF8String
        writeRdn(dnOut, "2.5.4.7", params.locality, 0x0C)// L: UTF8String
        writeRdn(dnOut, "2.5.4.10", params.organization, 0x0C) // O: UTF8String
        writeRdn(dnOut, "2.5.4.11", params.organizationalUnit, 0x0C) // OU: UTF8String
        writeRdn(dnOut, "2.5.4.3", params.commonName, 0x0C)   // CN: UTF8String
        return encodeDerSequence(dnOut.toByteArray())
    }

    private fun writeRdn(out: ByteArrayOutputStream, oid: String, value: String, stringTag: Int) {
        val attrOut = ByteArrayOutputStream()
        // OID
        val oidBytes = encodeOid(oid)
        writeDer(attrOut, 0x06, oidBytes)
        // String value
        writeDer(attrOut, stringTag, value.toByteArray(Charsets.UTF_8))
        val seqBytes = encodeDerSequence(attrOut.toByteArray())
        // SET of AttributeTypeAndValue
        writeDer(out, 0x31, seqBytes)
    }

    private fun encodeOid(oid: String): ByteArray {
        val parts = oid.split(".").map { it.toInt() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]))
        for (i in 2 until parts.size) {
            var v = parts[i]
            val stack = mutableListOf<Int>()
            stack.add(v and 0x7F)
            v = v ushr 7
            while (v > 0) {
                stack.add((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            for (j in stack.size - 1 downTo 0) {
                out.write(stack[j])
            }
        }
        return out.toByteArray()
    }

    private fun writeSha256WithRsaAlgorithmIdentifier(out: ByteArrayOutputStream) {
        // SEQUENCE { OID 1.2.840.113549.1.1.11, NULL }
        val algOut = ByteArrayOutputStream()
        val oid = encodeOid("1.2.840.113549.1.1.11")
        writeDer(algOut, 0x06, oid)
        algOut.write(byteArrayOf(0x05, 0x00)) // NULL
        writeDerSequence(out, algOut.toByteArray())
    }

    private fun writeUtcTime(out: ByteArrayOutputStream, timeStr: String) {
        writeDer(out, 0x17, timeStr.toByteArray(Charsets.US_ASCII))
    }

    private fun writeDerInteger(out: ByteArrayOutputStream, value: BigInteger) {
        writeDer(out, 0x02, value.toByteArray())
    }

    private fun writeDerSequence(out: ByteArrayOutputStream, content: ByteArray) {
        writeDer(out, 0x30, content)
    }

    private fun encodeDerSequence(content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeDerSequence(out, content)
        return out.toByteArray()
    }

    private fun writeDer(out: ByteArrayOutputStream, tag: Int, content: ByteArray) {
        out.write(tag)
        val len = content.size
        if (len < 128) {
            out.write(len)
        } else if (len < 256) {
            out.write(0x81)
            out.write(len)
        } else if (len < 65536) {
            out.write(0x82)
            out.write((len ushr 8) and 0xFF)
            out.write(len and 0xFF)
        } else {
            out.write(0x83)
            out.write((len ushr 16) and 0xFF)
            out.write((len ushr 8) and 0xFF)
            out.write(len and 0xFF)
        }
        out.write(content)
    }

    fun toPem(type: String, data: ByteArray): String {
        val b64 = Base64.encodeToString(data, Base64.NO_WRAP)
        val lines = b64.chunked(64).joinToString("\n")
        return "-----BEGIN $type-----\n$lines\n-----END $type-----\n"
    }

    /**
     * Creates an AVB 2.0 public key binary structure for vbmeta.img.
     */
    fun generateAvbPublicKeyBytes(publicKey: PublicKey, keyNumBits: Int): ByteArray {
        val rsaPub = publicKey as? java.security.interfaces.RSAPublicKey ?: return ByteArray(0)
        val n = rsaPub.modulus
        val out = ByteArrayOutputStream()
        val dataOut = DataOutputStream(out)

        // key_num_bits (big-endian 32-bit int in AVB)
        dataOut.writeInt(keyNumBits)
        // n0inv (modular inverse -1/n0 mod 2^32)
        val n0 = n.remainder(BigInteger.valueOf(1L shl 32)).toLong()
        val n0inv = modInverse32(n0)
        dataOut.writeInt(n0inv.toInt())

        // Modulus n bytes
        val nBytes = n.toByteArray()
        val expectedLen = keyNumBits / 8
        val paddedN = ByteArray(expectedLen)
        val srcPos = if (nBytes.size > expectedLen) nBytes.size - expectedLen else 0
        val destPos = if (expectedLen > nBytes.size) expectedLen - nBytes.size else 0
        val copyLen = minOf(nBytes.size, expectedLen)
        System.arraycopy(nBytes, srcPos, paddedN, destPos, copyLen)
        dataOut.write(paddedN)

        dataOut.flush()
        return out.toByteArray()
    }

    private fun modInverse32(n0: Long): Long {
        var inv = 1L
        for (i in 0 until 31) {
            inv = (inv * (2 - n0 * inv)) and 0xFFFFFFFFL
        }
        return (-inv) and 0xFFFFFFFFL
    }
}
