package com.example.signer.core

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

object CryptoUtils {

    fun sha256(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data)
    }

    fun sha256Hex(data: ByteArray): String {
        return sha256(data).joinToString("") { "%02X".format(it) }
    }

    fun loadCertificate(certFile: File): X509Certificate {
        val text = certFile.readText()
        val cleaned = text
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replace("\\s+".toRegex(), "")
        val bytes = Base64.decode(cleaned, Base64.DEFAULT)
        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }

    fun loadPrivateKey(pk8File: File): PrivateKey {
        val bytes = pk8File.readBytes()
        val keySpec = PKCS8EncodedKeySpec(bytes)
        val kf = KeyFactory.getInstance("RSA")
        return kf.generatePrivate(keySpec)
    }

    fun loadPrivateKeyFromPem(pemFile: File): PrivateKey {
        val text = pemFile.readText()
        val cleaned = text
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s+".toRegex(), "")
        val bytes = Base64.decode(cleaned, Base64.DEFAULT)
        val keySpec = PKCS8EncodedKeySpec(bytes)
        val kf = KeyFactory.getInstance("RSA")
        return kf.generatePrivate(keySpec)
    }
}
