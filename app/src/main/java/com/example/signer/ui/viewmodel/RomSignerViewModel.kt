package com.example.signer.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.signer.core.HephaistosStorage
import com.example.signer.core.RomAuditReport
import com.example.signer.core.RomFileType
import com.example.signer.core.RomInspectorEngine
import com.example.signer.core.RomSignResult
import com.example.signer.core.RomSignerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class RomSignerUiState(
    val selectedFilePath: String = "",
    val detectedFileType: RomFileType = RomFileType.GENERIC_ARCHIVE,
    val isAnalyzing: Boolean = false,
    val isSigning: Boolean = false,
    val progressStatus: String = "",
    val progressFraction: Float = 0f,
    val auditReport: RomAuditReport? = null,
    val lastSignResult: RomSignResult? = null,
    val logs: List<String> = emptyList(),
    val auditOutputPath: String = "",
    val romOutputPath: String = ""
)

class RomSignerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(RomSignerUiState())
    val uiState: StateFlow<RomSignerUiState> = _uiState.asStateFlow()

    fun initPaths(context: Context) {
        val auditDir = HephaistosStorage.getAnalysisDir(context).absolutePath
        val romDir = HephaistosStorage.getRomOutputDir(context).absolutePath
        _uiState.update {
            it.copy(
                auditOutputPath = auditDir,
                romOutputPath = romDir
            )
        }
    }

    fun onFileSelected(path: String) {
        val file = File(path)
        val type = RomInspectorEngine.detectFileType(file)
        _uiState.update {
            it.copy(
                selectedFilePath = path,
                detectedFileType = type,
                logs = it.logs + listOf(
                    "Fichier cible sélectionné : ${file.name}",
                    "Type détecté automatiquement : ${type.label}"
                )
            )
        }
    }

    fun runPreAnalysis(context: Context) {
        if (_uiState.value.isAnalyzing || _uiState.value.isSigning) return

        val file = File(_uiState.value.selectedFilePath)
        _uiState.update {
            it.copy(
                isAnalyzing = true,
                progressStatus = "Démarrage de l'analyse en amont...",
                progressFraction = 0.05f,
                logs = it.logs + "Lancement de l'audit HephaistOS sur ${file.name.ifBlank { "cible" }}..."
            )
        }

        viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    RomInspectorEngine.analyzeRom(context, file) { status, pct ->
                        _uiState.update {
                            it.copy(progressStatus = status, progressFraction = pct)
                        }
                    }
                }

                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        progressStatus = "Audit terminé et enregistré !",
                        progressFraction = 1f,
                        auditReport = report,
                        logs = it.logs + listOf(
                            "✓ Audit sauvegardé : ${report.auditFile.absolutePath}",
                            "✓ Packages système détectés : ${report.platformApksToResign}",
                            "✓ Applications Gapps protégées : ${report.gappsToPreserve}",
                            "✓ Cache odex/vdex à purger : ${report.odexVdexDetectedCount}",
                            "✓ Évaluation du risque : ${report.riskAssessment}"
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        progressStatus = "Erreur d'analyse : ${e.message}",
                        progressFraction = 0f,
                        logs = it.logs + "Échec de l'audit : ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun signRom(context: Context, withVbmeta: Boolean) {
        if (_uiState.value.isSigning || _uiState.value.isAnalyzing) return

        val file = File(_uiState.value.selectedFilePath)
        val modeLabel = if (withVbmeta) "Signature complète avec régénération VBMeta (AVB 2.0)" else "Signature standard"

        _uiState.update {
            it.copy(
                isSigning = true,
                progressStatus = "Initialisation de la signature...",
                progressFraction = 0.05f,
                logs = it.logs + "Mode sélectionné : $modeLabel"
            )
        }

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    RomSignerEngine.signRom(context, file, withVbmeta) { status, pct ->
                        _uiState.update {
                            it.copy(progressStatus = status, progressFraction = pct)
                        }
                    }
                }

                _uiState.update {
                    val newLogs = it.logs.toMutableList()
                    newLogs.add("✓ ROM résignée : ${result.outputRomFile.name}")
                    if (result.vbmetaResult != null) {
                        newLogs.add("✓ VBMeta AVB0 régénéré et signé : ${result.vbmetaResult.vbmetaFile.name} (SHA256_RSA4096)")
                    }
                    newLogs.add("✓ Durée d'exécution : ${result.durationSeconds}s")
                    newLogs.add("✓ Répertoire de sortie : ${HephaistosStorage.getRomOutputDir(context).absolutePath}")

                    it.copy(
                        isSigning = false,
                        progressStatus = if (withVbmeta) "ROM et vbmeta.img générés avec succès !" else "ROM résignée avec succès !",
                        progressFraction = 1f,
                        lastSignResult = result,
                        logs = newLogs
                    )
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
