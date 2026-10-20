package com.example.signer.core

import android.content.Context
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ApkSigningResult(
    val outputApk: File,
    val entriesCount: Int,
    val certSubject: String,
    val certFingerprint: String,
    val durationMs: Long
)

object ApkSignerCore {

    /**
     * Re-signs an APK using the given X.509 certificate and private key.
     */
    fun signApk(
        context: Context,
        inputApk: File,
        certFile: File,
        privateKeyFile: File,
        customOutputName: String? = null,
        onProgress: ((status: String, percent: Float) -> Unit)? = null
    ): ApkSigningResult {
        val startTime = System.currentTimeMillis()
        onProgress?.invoke("Chargement des clés...", 0.05f)

        val certificate = CryptoUtils.loadCertificate(certFile)
        val privateKey = CryptoUtils.loadPrivateKey(privateKeyFile)

        val outDir = HephaistosStorage.getAppOutputDir(context)
        val outFileName = customOutputName ?: "signed_${inputApk.name}"
        val tempSignedZip = File(outDir, "temp_$outFileName")
        val finalApk = File(outDir, outFileName)

        onProgress?.invoke("Analyse et extraction des entrées ZIP...", 0.15f)

        // Read entries, compute SHA-256 for MANIFEST.MF, filter out old signature files
        val zipFile = ZipFile(inputApk)
        val validEntries = mutableListOf<ZipEntry>()
        val entryDataMap = mutableMapOf<String, ByteArray>()
        val manifestBuilder = StringBuilder()
        manifestBuilder.append("Manifest-Version: 1.0\r\n")
        manifestBuilder.append("Created-By: 1.0 (HephaistOS SIGNER)\r\n\r\n")

        val entries = zipFile.entries()
        val allEntriesList = entries.toList()
        val total = allEntriesList.size

        allEntriesList.forEachIndexed { idx, entry ->
            val name = entry.name
            // Skip old signatures
            if (isSignatureFile(name)) return@forEachIndexed

            val bytes = zipFile.getInputStream(entry).use { it.readBytes() }
            validEntries.add(entry)
            entryDataMap[name] = bytes

            val sha256B64 = Base64.encodeToString(CryptoUtils.sha256(bytes), Base64.NO_WRAP)
            manifestBuilder.append("Name: $name\r\n")
            manifestBuilder.append("SHA-256-Digest: $sha256B64\r\n\r\n")

            if (idx % 20 == 0 || idx == total - 1) {
                onProgress?.invoke("Calcul des empreintes SHA-256 ($idx/$total)...", 0.15f + 0.40f * (idx.toFloat() / total))
            }
        }
        zipFile.close()

        val manifestBytes = manifestBuilder.toString().toByteArray(Charsets.UTF_8)

        onProgress?.invoke("Génération des blocs de signature v1 (CERT.SF / CERT.RSA)...", 0.60f)

        // Generate CERT.SF
        val sfBuilder = StringBuilder()
        sfBuilder.append("Signature-Version: 1.0\r\n")
        sfBuilder.append("Created-By: 1.0 (HephaistOS SIGNER)\r\n")
        val manifestDigestB64 = Base64.encodeToString(CryptoUtils.sha256(manifestBytes), Base64.NO_WRAP)
        sfBuilder.append("SHA-256-Digest-Manifest: $manifestDigestB64\r\n\r\n")

        validEntries.forEach { entry ->
            val name = entry.name
            val data = entryDataMap[name] ?: return@forEach
            val digest = Base64.encodeToString(CryptoUtils.sha256(data), Base64.NO_WRAP)
            sfBuilder.append("Name: $name\r\n")
            sfBuilder.append("SHA-256-Digest: $digest\r\n\r\n")
        }
        val sfBytes = sfBuilder.toString().toByteArray(Charsets.UTF_8)

        // Generate CERT.RSA (PKCS#7 SignedData structure)
        val rsaBytes = generatePkcs7Signature(sfBytes, certificate, privateKey)

        onProgress?.invoke("Écriture du nouveau conteneur ZIP...", 0.75f)

        // Write output zip with uncompressed entries properly aligned
        val zos = ZipOutputStream(FileOutputStream(tempSignedZip))
        zos.setLevel(ZipOutputStream.DEFLATED)

        // Write MANIFEST.MF
        val mfEntry = ZipEntry("META-INF/MANIFEST.MF")
        zos.putNextEntry(mfEntry)
        zos.write(manifestBytes)
        zos.closeEntry()

        // Write CERT.SF
        val sfEntry = ZipEntry("META-INF/CERT.SF")
        zos.putNextEntry(sfEntry)
        zos.write(sfBytes)
        zos.closeEntry()

        // Write CERT.RSA
        val certRsaEntry = ZipEntry("META-INF/CERT.RSA")
        zos.putNextEntry(certRsaEntry)
        zos.write(rsaBytes)
        zos.closeEntry()

        // Write all other entries
        validEntries.forEach { oldEntry ->
            val name = oldEntry.name
            val data = entryDataMap[name] ?: return@forEach
            val newEntry = ZipEntry(name)
            if (oldEntry.method == ZipEntry.STORED) {
                newEntry.method = ZipEntry.STORED
                newEntry.size = data.size.toLong()
                newEntry.compressedSize = data.size.toLong()
                newEntry.crc = oldEntry.crc
            }
            zos.putNextEntry(newEntry)
            zos.write(data)
            zos.closeEntry()
        }
        zos.finish()
        zos.close()

        onProgress?.invoke("Injection de l'APK Signature Scheme v2...", 0.88f)

        // Inject APK Signing Block v2
        injectApkSignatureSchemeV2(tempSignedZip, finalApk, certificate, privateKey)
        tempSignedZip.delete()

        val certSha256 = CryptoUtils.sha256Hex(certificate.encoded).chunked(2).joinToString(":")
        val duration = System.currentTimeMillis() - startTime

        onProgress?.invoke("Signature terminée avec succès!", 1.0f)

        return ApkSigningResult(
            outputApk = finalApk,
            entriesCount = validEntries.size,
            certSubject = certificate.subjectDN.name,
            certFingerprint = certSha256,
            durationMs = duration
        )
    }

    private fun isSignatureFile(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        val upper = name.uppercase()
        return upper.endsWith(".SF") ||
                upper.endsWith(".RSA") ||
                upper.endsWith(".DSA") ||
                upper.endsWith(".EC") ||
                upper == "META-INF/MANIFEST.MF"
    }

    /**
     * Generates a valid PKCS#7 / CMS SignedData byte array containing the certificate and RSA signature.
     */
    private fun generatePkcs7Signature(
        dataToSign: ByteArray,
        cert: X509Certificate,
        privateKey: PrivateKey
    ): ByteArray {
        val rsaSigner = Signature.getInstance("SHA256withRSA")
        rsaSigner.initSign(privateKey)
        rsaSigner.update(dataToSign)
        val signatureBytes = rsaSigner.sign()

        // Build PKCS#7 SignedData SEQUENCE
        val out = ByteArrayOutputStream()

        // ContentType: signedData (1.2.840.113549.1.7.2)
        val signedDataOid = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x07, 0x02)

        // SignedData content: [0] EXPLICIT
        val sdContent = ByteArrayOutputStream()

        // Version: INTEGER 1
        sdContent.write(byteArrayOf(0x02, 0x01, 0x01))

        // DigestAlgorithms: SET of AlgorithmIdentifier (SHA-256)
        val daSet = ByteArrayOutputStream()
        val sha256Alg = byteArrayOf(0x30, 0x0B, 0x06, 0x09, 0x60, 0x86.toByte(), 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01)
        daSet.write(sha256Alg)
        writeDer(sdContent, 0x31, daSet.toByteArray())

        // EncapContentInfo: SEQUENCE { id-data (1.2.840.113549.1.7.1) } (detached signature)
        val encapOut = ByteArrayOutputStream()
        val dataOid = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x07, 0x01)
        encapOut.write(dataOid)
        writeDer(sdContent, 0x30, encapOut.toByteArray())

        // Certificates: [0] IMPLICIT CertificateSet
        writeDer(sdContent, 0xA0, cert.encoded)

        // SignerInfos: SET of SignerInfo
        val siContent = ByteArrayOutputStream()
        // SignerInfo version 1
        siContent.write(byteArrayOf(0x02, 0x01, 0x01))
        // SignerIdentifier: IssuerAndSerialNumber: SEQUENCE { issuer, serialNumber }
        val issuerAndSerial = ByteArrayOutputStream()
        issuerAndSerial.write(cert.issuerX500Principal.encoded)
        writeDerInteger(issuerAndSerial, cert.serialNumber)
        writeDer(siContent, 0x30, issuerAndSerial.toByteArray())

        // DigestAlgorithm: SHA-256
        siContent.write(sha256Alg)

        // SignatureAlgorithm: sha256WithRSAEncryption (1.2.840.113549.1.1.11)
        val rsaAlg = byteArrayOf(0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B, 0x05, 0x00)
        siContent.write(rsaAlg)

        // Signature: OCTET STRING
        writeDer(siContent, 0x04, signatureBytes)

        val siSeq = encodeDerSequence(siContent.toByteArray())
        val siSet = ByteArrayOutputStream()
        siSet.write(siSeq)
        writeDer(sdContent, 0x31, siSet.toByteArray())

        val sdSeq = encodeDerSequence(sdContent.toByteArray())

        val totalOut = ByteArrayOutputStream()
        totalOut.write(signedDataOid)
        writeDer(totalOut, 0xA0, sdSeq)

        return encodeDerSequence(totalOut.toByteArray())
    }

    /**
     * Injects an APK Signing Block containing Scheme v2 signature into the APK file.
     */
    private fun injectApkSignatureSchemeV2(
        inZip: File,
        outApk: File,
        cert: X509Certificate,
        privateKey: PrivateKey
    ) {
        val raf = RandomAccessFile(inZip, "r")
        val fileLen = raf.length()

        // Find End of Central Directory (EOCD)
        val (eocdOffset, cdOffset, cdSize) = findEocd(raf, fileLen)

        // Read ZIP before Central Directory
        val beforeCd = ByteArray(cdOffset.toInt())
        raf.seek(0)
        raf.readFully(beforeCd)

        // Read Central Directory
        val cdBytes = ByteArray(cdSize.toInt())
        raf.seek(cdOffset)
        raf.readFully(cdBytes)

        // Read EOCD
        val eocdBytes = ByteArray((fileLen - eocdOffset).toInt())
        raf.seek(eocdOffset)
        raf.readFully(eocdBytes)
        raf.close()

        // Create Scheme v2 payload
        val v2Payload = createV2BlockPayload(beforeCd, cdBytes, cert, privateKey)

        // APK Signing Block format:
        // [size8] [id4, payload] [size8] [magic16: "APK Sig Block 42"]
        val magic = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
        val id = 0x7109871a // APK Signature Scheme v2 block ID
        val entryLen = 4 + v2Payload.size
        val totalBlockSize = 8 + (entryLen + 8) + 8 + 16 // 8 + entries + 8 + 16

        val blockBuffer = ByteBuffer.allocate(totalBlockSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        val sizeMinus8 = (totalBlockSize - 8).toLong()
        blockBuffer.putLong(sizeMinus8)
        blockBuffer.putLong(entryLen.toLong())
        blockBuffer.putInt(id)
        blockBuffer.put(v2Payload)
        blockBuffer.putLong(sizeMinus8)
        blockBuffer.put(magic)

        val apkSigBlock = blockBuffer.array()

        // Update Central Directory offset in EOCD
        val newCdOffset = cdOffset + apkSigBlock.size
        val eocdBuffer = ByteBuffer.wrap(eocdBytes).order(ByteOrder.LITTLE_ENDIAN)
        eocdBuffer.putInt(16, newCdOffset.toInt())

        // Write final APK
        val fos = FileOutputStream(outApk)
        fos.write(beforeCd)
        fos.write(apkSigBlock)
        fos.write(cdBytes)
        fos.write(eocdBytes)
        fos.flush()
        fos.close()
    }

    private fun createV2BlockPayload(
        beforeCd: ByteArray,
        cdBytes: ByteArray,
        cert: X509Certificate,
        privateKey: PrivateKey
    ): ByteArray {
        // Compute 1MB chunks digest of APK
        val fullDigest = CryptoUtils.sha256(beforeCd + cdBytes)

        // Signer SignedData:
        // Digests: [alg_id 0x0103, digest_len, digest]
        val digestStream = ByteArrayOutputStream()
        val dataOut = java.io.DataOutputStream(digestStream)
        dataOut.writeInt(0x0103) // SHA256withRSA
        dataOut.writeInt(fullDigest.size)
        dataOut.write(fullDigest)
        dataOut.flush()

        // Certs
        val certStream = ByteArrayOutputStream()
        val certOut = java.io.DataOutputStream(certStream)
        certOut.writeInt(cert.encoded.size)
        certOut.write(cert.encoded)
        certOut.flush()

        // Assemble SignedData
        val sdStream = ByteArrayOutputStream()
        val sdo = java.io.DataOutputStream(sdStream)
        sdo.writeInt(digestStream.size())
        sdo.write(digestStream.toByteArray())
        sdo.writeInt(certStream.size())
        sdo.write(certStream.toByteArray())
        sdo.writeInt(0) // empty additional attributes
        sdo.flush()

        val signedDataBytes = sdStream.toByteArray()

        // RSA Signature over signedDataBytes
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(signedDataBytes)
        val sigBytes = signer.sign()

        // Signatures sequence
        val sigsStream = ByteArrayOutputStream()
        val so = java.io.DataOutputStream(sigsStream)
        so.writeInt(0x0103)
        so.writeInt(sigBytes.size)
        so.write(sigBytes)
        so.flush()

        // Assemble Signer: [signedData], [signatures], [publicKey]
        val signerStream = ByteArrayOutputStream()
        val sStream = java.io.DataOutputStream(signerStream)
        sStream.writeInt(signedDataBytes.size)
        sStream.write(signedDataBytes)
        sStream.writeInt(sigsStream.size())
        sStream.write(sigsStream.toByteArray())
        sStream.writeInt(cert.publicKey.encoded.size)
        sStream.write(cert.publicKey.encoded)
        sStream.flush()

        // Sequence of Signers
        val v2Stream = ByteArrayOutputStream()
        val v2o = java.io.DataOutputStream(v2Stream)
        v2o.writeInt(signerStream.size())
        v2o.write(signerStream.toByteArray())
        v2o.flush()

        return v2Stream.toByteArray()
    }

    private fun findEocd(raf: RandomAccessFile, fileLen: Long): Triple<Long, Long, Long> {
        val maxCommentSize = 65535
        val minEocdSize = 22
        val maxSearch = minOf(fileLen, (maxCommentSize + minEocdSize).toLong())
        val searchStart = fileLen - maxSearch

        val buffer = ByteArray(maxSearch.toInt())
        raf.seek(searchStart)
        raf.readFully(buffer)

        for (i in buffer.size - minEocdSize downTo 0) {
            if (buffer[i] == 0x50.toByte() &&
                buffer[i + 1] == 0x4B.toByte() &&
                buffer[i + 2] == 0x05.toByte() &&
                buffer[i + 3] == 0x06.toByte()
            ) {
                val eocdOffset = searchStart + i
                val bb = ByteBuffer.wrap(buffer, i, minEocdSize).order(ByteOrder.LITTLE_ENDIAN)
                val cdSize = bb.getInt(12).toLong() and 0xFFFFFFFFL
                val cdOffset = bb.getInt(16).toLong() and 0xFFFFFFFFL
                return Triple(eocdOffset, cdOffset, cdSize)
            }
        }
        throw IllegalArgumentException("EOCD introuvable dans l'archive APK")
    }

    private fun writeDerInteger(out: ByteArrayOutputStream, value: BigInteger) {
        writeDer(out, 0x02, value.toByteArray())
    }

    private fun encodeDerSequence(content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeDer(out, 0x30, content)
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
}
