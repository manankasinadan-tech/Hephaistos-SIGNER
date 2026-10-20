package com.example.signer.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.signer.core.AospKeyGenerator
import com.example.signer.core.GeneratedKeyResult
import com.example.signer.core.HephaistosStorage
import com.example.signer.core.KeysetParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class KeymakerUiState(
    val commonName: String = "HephaistOS Platform",
    val organization: String = "HephaistOS Dev Group",
    val organizationalUnit: String = "Security Core",
    val locality: String = "Android",
    val state: String = "Custom",
    val country: String = "FR",
    val validityYears: Int = 25,
    val keySize: Int = 4096,
    val isGenerating: Boolean = false,
    val progressStatus: String = "",
    val progressFraction: Float = 0f,
    val generatedKeys: List<GeneratedKeyResult> = emptyList(),
    val logs: List<String> = emptyList(),
    val keysetPath: String = ""
)

class KeymakerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(KeymakerUiState())
    val uiState: StateFlow<KeymakerUiState> = _uiState.asStateFlow()

    fun updateCommonName(v: String) = _uiState.update { it.copy(commonName = v) }
    fun updateOrganization(v: String) = _uiState.update { it.copy(organization = v) }
    fun updateOrgUnit(v: String) = _uiState.update { it.copy(organizationalUnit = v) }
    fun updateLocality(v: String) = _uiState.update { it.copy(locality = v) }
    fun updateState(v: String) = _uiState.update { it.copy(state = v) }
    fun updateCountry(v: String) = _uiState.update { it.copy(country = v) }
    fun updateValidityYears(v: Int) = _uiState.update { it.copy(validityYears = v) }
    fun updateKeySize(v: Int) = _uiState.update { it.copy(keySize = v) }

    fun initPath(context: Context) {
        val path = HephaistosStorage.getKeysetDir(context).absolutePath
        _uiState.update { it.copy(keysetPath = path) }
    }

    fun generateKeyset(context: Context) {
        if (_uiState.value.isGenerating) return

        val params = KeysetParams(
            commonName = _uiState.value.commonName,
            organization = _uiState.value.organization,
            organizationalUnit = _uiState.value.organizationalUnit,
            locality = _uiState.value.locality,
            state = _uiState.value.state,
            country = _uiState.value.country,
            validityYears = _uiState.value.validityYears,
            keySize = _uiState.value.keySize
        )

        _uiState.update {
            it.copy(
                isGenerating = true,
                progressStatus = "Démarrage de la génération du keyset AOSP...",
                progressFraction = 0.05f,
                logs = listOf("Initialisation du générateur RSA ${params.keySize}-bit...")
            )
        }

        viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    AospKeyGenerator.generateCompleteKeyset(context, params) { keyName, cur, tot ->
                        val pct = cur.toFloat() / tot.toFloat()
                        _uiState.update { state ->
                            state.copy(
                                progressStatus = "Génération de la clé [$keyName] ($cur/$tot)...",
                                progressFraction = pct,
                                logs = state.logs + "Clé générée : $keyName ($cur/$tot) - RSA ${if (keyName == "avb") 4096 else params.keySize}-bit"
                            )
                        }
                    }
                }

                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        progressStatus = "Keyset AOSP complet généré dans sdcard/HEPHAISTOS/Data/keyset !",
                        progressFraction = 1f,
                        generatedKeys = results,
                        logs = it.logs + listOf(
                            "✓ Succès : 7 clés AOSP et AVB écrites (.pk8, .x509.pem, .pem, avb_pk.bin)",
                            "Dossier de sortie : ${HephaistosStorage.getKeysetDir(context).absolutePath}"
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        progressStatus = "Erreur : ${e.message}",
                        progressFraction = 0f,
                        logs = it.logs + "Échec : ${e.localizedMessage}"
                    )
                }
            }
        }
    }
}
