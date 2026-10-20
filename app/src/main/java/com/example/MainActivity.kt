package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.signer.ui.AppSignerScreen
import com.example.signer.ui.DashboardScreen
import com.example.signer.ui.KeymakerScreen
import com.example.signer.ui.RomSignerScreen
import com.example.signer.ui.viewmodel.AppSignerViewModel
import com.example.signer.ui.viewmodel.KeymakerViewModel
import com.example.signer.ui.viewmodel.RomSignerViewModel
import com.example.signer.ui.viewmodel.SignerDashboardViewModel
import com.example.signer.ui.viewmodel.SignerScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val dashboardViewModel: SignerDashboardViewModel by viewModels()
    private val keymakerViewModel: KeymakerViewModel by viewModels()
    private val appSignerViewModel: AppSignerViewModel by viewModels()
    private val romSignerViewModel: RomSignerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val context = LocalContext.current
                val dashboardState by dashboardViewModel.uiState.collectAsState()
                val keymakerState by keymakerViewModel.uiState.collectAsState()
                val appSignerState by appSignerViewModel.uiState.collectAsState()
                val romSignerState by romSignerViewModel.uiState.collectAsState()

                // Request storage permissions on Android <= 32
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    dashboardViewModel.refreshStats(context)
                }

                LaunchedEffect(Unit) {
                    dashboardViewModel.refreshStats(context)
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                        val readGranted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.READ_EXTERNAL_STORAGE
                        ) == PackageManager.PERMISSION_GRANTED
                        val writeGranted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!readGranted || !writeGranted) {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                                )
                            )
                        }
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    AnimatedContent(
                        targetState = dashboardState.currentScreen,
                        label = "ScreenTransition",
                        transitionSpec = {
                            if (targetState != SignerScreen.DASHBOARD) {
                                (slideInHorizontally { width -> width } + fadeIn())
                                    .togetherWith(slideOutHorizontally { width -> -width } + fadeOut())
                            } else {
                                (slideInHorizontally { width -> -width } + fadeIn())
                                    .togetherWith(slideOutHorizontally { width -> width } + fadeOut())
                            }
                        },
                        modifier = Modifier.padding(innerPadding)
                    ) { screen ->
                        when (screen) {
                            SignerScreen.DASHBOARD -> {
                                DashboardScreen(
                                    uiState = dashboardState,
                                    onNavigate = { target ->
                                        dashboardViewModel.navigateTo(target)
                                    }
                                )
                            }
                            SignerScreen.KEYMAKER -> {
                                KeymakerScreen(
                                    viewModel = keymakerViewModel,
                                    uiState = keymakerState,
                                    onBack = {
                                        dashboardViewModel.navigateBack()
                                        dashboardViewModel.refreshStats(context)
                                    }
                                )
                            }
                            SignerScreen.APP_SIGNER -> {
                                AppSignerScreen(
                                    viewModel = appSignerViewModel,
                                    uiState = appSignerState,
                                    onBack = {
                                        dashboardViewModel.navigateBack()
                                        dashboardViewModel.refreshStats(context)
                                    }
                                )
                            }
                            SignerScreen.ROM_SIGNER -> {
                                RomSignerScreen(
                                    viewModel = romSignerViewModel,
                                    uiState = romSignerState,
                                    onBack = {
                                        dashboardViewModel.navigateBack()
                                        dashboardViewModel.refreshStats(context)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
