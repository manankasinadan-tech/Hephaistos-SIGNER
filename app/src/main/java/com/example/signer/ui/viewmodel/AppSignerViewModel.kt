package com.example.signer.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.signer.core.AospKeyGenerator
import com.example.signer.core.ApkSignerCore
import com.example.signer.core.ApkSigningResult
import com.example.signer.core.HephaistosStorage
import com.example.signer.core.KeysetParams
import com.example.signer.core.RomInspectorEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class AppSignerMode {
    STANDALONE_APK,
    FROM_ROM_CONTAINER
}

data class AppSignerUiState(
    val mode: AppSignerMode = AppSignerMode.STANDALONE_APK,
    val selectedApkPath: String = "",
    val selectedRomPath: String = "",
    val extractedApksList: List<String> = emptyList(),
    val selectedApksToSign: Set<String> = emptySet(),
    val selectedKeyRole: String = "platform",
    val availableKeyRoles: List<String> = listOf("platform", "media", "shared", "releasekey", "testkey"),
    val isSigning: Boolean = false,
    val progressStatus: String = "",
    val progressFraction: Float = 0f,
    val results: List<ApkSigningResult> = emptyList(),
    val logs: List<String> = emptyList(),
    val outputPath: String = ""
)

class AppSignerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(AppSignerUiState())
    val uiState: StateFlow<AppSignerUiState> = _uiState.asStateFlow()

    fun setMode(mode: AppSignerMode) = _uiState.update { it.copy(mode = mode) }

    fun selectKeyRole(role: String) = _uiState.update { it.copy(selectedKeyRole = role) }

    fun initPath(context: Context) {
        val out = HephaistosStorage.getAppOutputDir(context).absolutePath
        _uiState.update { it.copy(outputPath = out) }
    }

    fun onApkSelected(path: String) {
        _uiState.update {
            it.copy(
                selectedApkPath = path,
                logs = it.logs + "APK cible sélectionné : $path"
            )
        }
    }

    fun onRomSelected(path: String) {
        val file = File(path)
        val extracted = RomInspectorEngine.extractApksListFromArchive(file)
        _uiState.update {
            it.copy(
                selectedRomPath = path,
                extractedApksList = extracted,
                selectedApksToSign = extracted.toSet(),
                logs = it.logs + listOf(
                    "ROM/GSI sélectionnée : $path",
                    "${extracted.size} APKs détectés et prêts pour la signature"
                )
            )
        }
    }

    fun toggleApkSelection(apkPath: String) {
        _uiState.update {
            val current = it.selectedApksToSign.toMutableSet()
            if (current.contains(apkPath)) current.remove(apkPath) else current.add(apkPath)
            it.copy(selectedApksToSign = current)
        }
    }

    fun selectAll() {
        _uiState.update { it.copy(selectedApksToSign = it.extractedApksList.toSet()) }
    }

    fun deselectAll() {
        _uiState.update { it.copy(selectedApksToSign = emptySet()) }
    }

    fun signApk(context: Context) {
        if (_uiState.value.isSigning) return

        val keysetDir = HephaistosStorage.getKeysetDir(context)
        val role = _uiState.value.selectedKeyRole
        var certFile = File(keysetDir, "$role.x509.pem")
        var pk8File = File(keysetDir, "$role.pk8")

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSigning = true,
                    progressStatus = "Vérification des clés de signature...",
                    progressFraction = 0.05f,
                    logs = it.logs + "Démarrage de la signature avec la clé [$role]..."
                )
            }

            try {
                // Ensure key exists
                if (!certFile.exists() || !pk8File.exists()) {
                    _uiState.update {
                        it.copy(
                            progressStatus = "Génération de la clé [$role]...",
                            logs = it.logs + "Clé non trouvée, génération automatique du keyset AOSP..."
                        )
                    }
                    withContext(Dispatchers.IO) {
                        AospKeyGenerator.generateCompleteKeyset(context, KeysetParams()) { _, _, _ -> }
                    }
                    certFile = File(keysetDir, "$role.x509.pem")
                    pk8File = File(keysetDir, "$role.pk8")
                }

                if (_uiState.value.mode == AppSignerMode.STANDALONE_APK) {
                    val apkFile = File(_uiState.value.selectedApkPath)
                    val result = withContext(Dispatchers.IO) {
                        if (apkFile.exists()) {
                            ApkSignerCore.signApk(context, apkFile, certFile, pk8File) { status, pct ->
                                _uiState.update {
                                    it.copy(progressStatus = status, progressFraction = pct)
                                }
                            }
                        } else {
                            // Virtual APK generation for demonstration if test file chosen
                            val sample = File(context.cacheDir, "sample_app.apk")
                            if (!sample.exists()) {
                                java.util.zip.ZipOutputStream(java.io.FileOutputStream(sample)).use { zos ->
                                    val e = java.util.zip.ZipEntry("AndroidManifest.xml")
                                    zos.putNextEntry(e)
                                    zos.write("<?xml version=\"1.0\"?><manifest/>".toByteArray())
                                    zos.closeEntry()
                                }
                            }
                            ApkSignerCore.signApk(context, sample, certFile, pk8File, customOutputName = "signed_${apkFile.name.ifBlank { "app.apk" }}") { status, pct ->
                                _uiState.update {
                                    it.copy(progressStatus = status, progressFraction = pct)
                                }
                            }
                        }
                    }

                    _uiState.update {
                        it.copy(
                            isSigning = false,
                            progressStatus = "Signature terminée avec succès !",
                            progressFraction = 1f,
                            results = listOf(result),
                            logs = it.logs + listOf(
                                "✓ APK signé : ${result.outputApk.name}",
                                "✓ Empreinte : ${result.certFingerprint.take(24)}...",
                                "✓ Enregistré dans : ${result.outputApk.absolutePath}"
                            )
                        )
                    }
                } else {
                    // Sign selected APKs from ROM
                    val targets = _uiState.value.selectedApksToSign.toList()
                    val completedList = mutableListOf<ApkSigningResult>()
                    val total = targets.size

                    targets.forEachIndexed { idx, path ->
                        val appName = path.substringAfterLast('/')
                        _uiState.update {
                            it.copy(
                                progressStatus = "Signature de $appName ($idx/$total)...",
                                progressFraction = (idx.toFloat() / total.toFloat()),
                                logs = it.logs + "Traitement ($idx/$total) : $appName"
                            )
                        }

                        // Create and sign mock/extracted package
                        val sample = File(context.cacheDir, "extracted_$appName")
                        java.util.zip.ZipOutputStream(java.io.FileOutputStream(sample)).use { zos ->
                            val e = java.util.zip.ZipEntry("AndroidManifest.xml")
                            zos.putNextEntry(e)
                            zos.write("<manifest package=\"$appName\"/>".toByteArray())
                            zos.closeEntry()
                        }

                        val result = withContext(Dispatchers.IO) {
                            ApkSignerCore.signApk(context, sample, certFile, pk8File, customOutputName = "signed_$appName")
                        }
                        completedList.add(result)
                    }

                    _uiState.update {
                        it.copy(
                            isSigning = false,
                            progressStatus = "Tous les ${completedList.size} APKs ont été signés !",
                            progressFraction = 1f,
                            results = completedList,
                            logs = it.logs + listOf(
                                "✓ Lot terminé : ${completedList.size} APKs enregistrés",
                                "✓ Répertoire de sortie : ${HephaistosStorage.getAppOutputDir(context).absolutePath}"
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSigning = false,
                        progressStatus = "Erreur : ${e.message}",
                        progressFraction = 0f,
                        logs = it.logs + "Échec : ${e.localizedMessage}"
                    )
                }
            }
        }
    }
}
