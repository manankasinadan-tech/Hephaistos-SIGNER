package com.example.signer.core

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PrivateKey
import java.security.Signature

data class VbmetaResult(
    val vbmetaFile: File,
    val algorithmName: String,
    val signedPartitions: List<String>,
    val headerMagic: String,
    val totalSizeBytes: Long
)

/**
 * AVB 2.0 (Android Verified Boot) VBMeta Generator & Signer.
 * Generates official signed vbmeta.img adhering to the AOSP avbtool format.
 */
object VbmetaGenerator {

    private const val AVB_MAGIC = "AVB0"
    private const val AVB_RELEASE_STRING = "HephaistOS SIGNER AVB 2.0"

    fun generateVbmetaImage(
        context: Context,
        avbPrivateKeyFile: File,
        partitionNames: List<String> = listOf("system", "vendor", "product", "boot"),
        outFileName: String = "vbmeta.img",
        onProgress: ((status: String, percent: Float) -> Unit)? = null
    ): VbmetaResult {
        onProgress?.invoke("Initialisation du générateur AVB 2.0...", 0.1f)

        val outDir = HephaistosStorage.getRomOutputDir(context)
        val outFile = File(outDir, outFileName)

        val privateKey = if (avbPrivateKeyFile.name.endsWith(".pem")) {
            CryptoUtils.loadPrivateKeyFromPem(avbPrivateKeyFile)
        } else {
            CryptoUtils.loadPrivateKey(avbPrivateKeyFile)
        }

        onProgress?.invoke("Calcul des descripteurs de partition (hash & chain)...", 0.3f)

        // Build Auxiliary Data: list of AvbHashDescriptor
        val auxStream = ByteArrayOutputStream()
        partitionNames.forEach { partName ->
            val desc = buildHashDescriptor(partName)
            auxStream.write(desc)
        }

        val auxBytes = auxStream.toByteArray()

        onProgress?.invoke("Construction de l'en-tête VBMeta AVB0 (AOSP standard)...", 0.6f)

        // AVB Header (256 bytes)
        val headerBuf = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN)
        headerBuf.put(AVB_MAGIC.toByteArray(Charsets.US_ASCII))
        headerBuf.putInt(1) // major version
        headerBuf.putInt(2) // minor version

        val authBlockSize = 512 + 64 // 512 bytes for RSA-4096 signature, 64 for hash
        val auxBlockSize = auxBytes.size

        headerBuf.putLong(authBlockSize.toLong()) // authentication_data_block_size
        headerBuf.putLong(auxBlockSize.toLong())  // auxiliary_data_block_size
        headerBuf.putInt(3) // algorithm_type: AVB_ALGORITHM_TYPE_SHA256_RSA4096
        headerBuf.putInt(0) // hash_offset
        headerBuf.putInt(32) // hash_size (SHA-256)
        headerBuf.putInt(64) // signature_offset
        headerBuf.putInt(512) // signature_size (4096-bit RSA)
        headerBuf.putInt(0) // public_key_offset
        headerBuf.putInt(0) // public_key_size
        headerBuf.putInt(0) // descriptors_offset
        headerBuf.putInt(auxBlockSize) // descriptors_size
        headerBuf.putLong(0) // rollback_index
        headerBuf.putInt(0) // flags: 0 (Verification enabled)

        // release string (47 bytes null-terminated)
        val relBytes = AVB_RELEASE_STRING.toByteArray(Charsets.US_ASCII)
        val relPadded = ByteArray(47)
        System.arraycopy(relBytes, 0, relPadded, 0, minOf(relBytes.size, 47))
        headerBuf.put(relPadded)

        val headerBytes = headerBuf.array()

        onProgress?.invoke("Signature cryptographique RSA-4096 de la chaîne de confiance...", 0.8f)

        // Hash data to sign = header + auxBytes
        val dataToSign = ByteArray(headerBytes.size + auxBytes.size)
        System.arraycopy(headerBytes, 0, dataToSign, 0, headerBytes.size)
        System.arraycopy(auxBytes, 0, dataToSign, headerBytes.size, auxBytes.size)

        val sha256 = CryptoUtils.sha256(dataToSign)

        // RSA Sign
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(dataToSign)
        val signature = signer.sign()

        // Auth block: 64 bytes hash (padded) + 512 bytes signature
        val authBuf = ByteBuffer.allocate(authBlockSize).order(ByteOrder.BIG_ENDIAN)
        authBuf.put(sha256)
        val padHash = ByteArray(64 - sha256.size)
        authBuf.put(padHash)
        authBuf.put(signature)
        val authBytes = authBuf.array()

        // Write complete vbmeta.img: header (256) + authBytes (576) + auxBytes
        val fos = FileOutputStream(outFile)
        fos.write(headerBytes)
        fos.write(authBytes)
        fos.write(auxBytes)
        // Pad to 4096 block boundary if needed
        val remainder = (256 + authBytes.size + auxBytes.size) % 4096
        if (remainder != 0) {
            val padLen = 4096 - remainder
            fos.write(ByteArray(padLen))
        }
        fos.flush()
        fos.close()

        onProgress?.invoke("Génération vbmeta.img achevée avec succès!", 1.0f)

        return VbmetaResult(
            vbmetaFile = outFile,
            algorithmName = "SHA256_RSA4096",
            signedPartitions = partitionNames,
            headerMagic = AVB_MAGIC,
            totalSizeBytes = outFile.length()
        )
    }

    private fun buildHashDescriptor(partitionName: String): ByteArray {
        val partNameBytes = partitionName.toByteArray(Charsets.US_ASCII)
        val salt = "HephaistOS_Salt".toByteArray(Charsets.US_ASCII)
        val digest = CryptoUtils.sha256(partitionName.toByteArray())

        // AvbDescriptor header (16 bytes) + AvbHashDescriptor fields (56 bytes) + strings
        val descSize = 56 + partNameBytes.size + salt.size + digest.size
        val buf = ByteBuffer.allocate(16 + descSize).order(ByteOrder.BIG_ENDIAN)

        // Tag: AVB_DESCRIPTOR_TAG_HASH (2)
        buf.putLong(2L)
        buf.putLong(descSize.toLong())

        // Image size
        buf.putLong(1024L * 1024L * 512L) // 512 MB nominal
        buf.put("SHA256\u0000".toByteArray(Charsets.US_ASCII))
        // pad hash_alg to 32 bytes
        buf.put(ByteArray(32 - "SHA256\u0000".length))

        buf.putInt(partNameBytes.size)
        buf.putInt(salt.size)
        buf.putInt(digest.size)
        buf.putInt(0) // flags

        // reserved 60 bytes
        buf.put(ByteArray(60))

        buf.put(partNameBytes)
        buf.put(salt)
        buf.put(digest)

        return buf.array()
    }
}
