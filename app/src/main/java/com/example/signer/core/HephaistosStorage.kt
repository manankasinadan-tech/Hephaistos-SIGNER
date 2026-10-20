package com.example.signer.core

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages the canonical storage directories demanded by the HephaistOS specification:
 * - sdcard/HephaistOS/Data/Signer  (Pre-analysis reports & diagnostics)
 * - sdcard/HEPHAISTOS/Data/keyset  (AOSP keysets & AVB keys)
 * - sdcard/HEPHAISTOS/SIGNER/APP   (Re-signed individual or extracted APKs)
 * - sdcard/HEPHAISTOS/SIGNER/ROM   (Re-signed ROMs, GSIs, partition images & vbmeta)
 */
object HephaistosStorage {

    private fun getStorageBase(): File {
        val externalStorage = Environment.getExternalStorageDirectory()
        return if (externalStorage != null && externalStorage.canWrite()) {
            externalStorage
        } else {
            // Emulated / primary storage fallback
            File("/storage/emulated/0")
        }
    }

    fun getAnalysisDir(context: Context): File {
        val dir = File(getStorageBase(), "HephaistOS/Data/Signer")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        if (!dir.canWrite()) {
            val fallback = File(context.getExternalFilesDir(null), "HephaistOS/Data/Signer")
            fallback.mkdirs()
            return fallback
        }
        return dir
    }

    fun getKeysetDir(context: Context): File {
        val dir = File(getStorageBase(), "HEPHAISTOS/Data/keyset")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        if (!dir.canWrite()) {
            val fallback = File(context.getExternalFilesDir(null), "HEPHAISTOS/Data/keyset")
            fallback.mkdirs()
            return fallback
        }
        return dir
    }

    fun getAppOutputDir(context: Context): File {
        val dir = File(getStorageBase(), "HEPHAISTOS/SIGNER/APP")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        if (!dir.canWrite()) {
            val fallback = File(context.getExternalFilesDir(null), "HEPHAISTOS/SIGNER/APP")
            fallback.mkdirs()
            return fallback
        }
        return dir
    }

    fun getRomOutputDir(context: Context): File {
        val dir = File(getStorageBase(), "HEPHAISTOS/SIGNER/ROM")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        if (!dir.canWrite()) {
            val fallback = File(context.getExternalFilesDir(null), "HEPHAISTOS/SIGNER/ROM")
            fallback.mkdirs()
            return fallback
        }
        return dir
    }

    fun saveAuditReport(context: Context, reportContent: String): File {
        val dir = getAnalysisDir(context)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "audit_$timestamp.json")
        file.writeText(reportContent)
        return file
    }

    fun getExistingKeysets(context: Context): List<String> {
        val dir = getKeysetDir(context)
        return dir.listFiles { f -> f.isDirectory || f.name.endsWith(".pk8") || f.name.endsWith(".pem") }
            ?.map { it.name }
            ?.distinct()
            ?: emptyList()
    }

    fun countSignedApps(context: Context): Int {
        val dir = getAppOutputDir(context)
        return dir.listFiles { f -> f.name.endsWith(".apk") }?.size ?: 0
    }

    fun countAuditReports(context: Context): Int {
        val dir = getAnalysisDir(context)
        return dir.listFiles { f -> f.name.startsWith("audit_") }?.size ?: 0
    }
}
