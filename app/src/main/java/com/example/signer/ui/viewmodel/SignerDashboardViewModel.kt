package com.example.signer.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.signer.core.HephaistosStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SignerScreen {
    DASHBOARD,
    KEYMAKER,
    APP_SIGNER,
    ROM_SIGNER
}

data class DashboardUiState(
    val currentScreen: SignerScreen = SignerScreen.DASHBOARD,
    val keysetCount: Int = 0,
    val signedApksCount: Int = 0,
    val auditReportsCount: Int = 0,
    val storageReady: Boolean = true
)

class SignerDashboardViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    fun navigateTo(screen: SignerScreen) {
        _uiState.update { it.copy(currentScreen = screen) }
    }

    fun navigateBack(): Boolean {
        if (_uiState.value.currentScreen != SignerScreen.DASHBOARD) {
            _uiState.update { it.copy(currentScreen = SignerScreen.DASHBOARD) }
            return true
        }
        return false
    }

    fun refreshStats(context: Context) {
        viewModelScope.launch {
            val keys = HephaistosStorage.getExistingKeysets(context).size
            val apps = HephaistosStorage.countSignedApps(context)
            val audits = HephaistosStorage.countAuditReports(context)
            _uiState.update {
                it.copy(
                    keysetCount = keys,
                    signedApksCount = apps,
                    auditReportsCount = audits
                )
            }
        }
    }
}
