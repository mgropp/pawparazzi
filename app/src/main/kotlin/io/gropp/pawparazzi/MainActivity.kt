package io.gropp.pawparazzi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import io.gropp.pawparazzi.service.DetectionService
import io.gropp.pawparazzi.state.PreviewSurfaceHolder
import io.gropp.pawparazzi.ui.LicensesScreen
import io.gropp.pawparazzi.ui.MainScreen
import io.gropp.pawparazzi.ui.SettingsScreen
import io.gropp.pawparazzi.ui.SystemStatus
import javax.inject.Inject

private enum class Screen { Main, Settings, Licenses }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var surfaceHolder: PreviewSurfaceHolder

    private val mainViewModel: MainViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private var systemStatus by mutableStateOf(SystemStatus())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var screen by rememberSaveable { mutableStateOf(Screen.Main) }
                val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    refreshSystemStatus()
                }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    val missing = requiredPermissions().filter {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (screen) {
                        Screen.Licenses -> LicensesScreen(onBack = { screen = Screen.Settings })
                        Screen.Settings -> SettingsScreen(
                            settingsViewModel,
                            onBack = { screen = Screen.Main },
                            onOpenLicenses = { screen = Screen.Licenses },
                        )
                        Screen.Main -> MainScreen(
                            viewModel = mainViewModel,
                            surfaceHolder = surfaceHolder,
                            systemStatus = systemStatus,
                            onStart = ::startDetection,
                            onStop = { startService(DetectionService.stopIntent(this@MainActivity)) },
                            onOpenSettings = { screen = Screen.Settings },
                            onOpenAppSettings = ::openAppSettings,
                            onRequestBatteryExemption = ::requestBatteryExemption,
                            onRequestPermissions = {
                                permissionLauncher.launch(requiredPermissions().toTypedArray())
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSystemStatus()
        mainViewModel.refreshMuted()
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun refreshSystemStatus() {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        val power = getSystemService(PowerManager::class.java)
        systemStatus = SystemStatus(
            cameraGranted = granted(Manifest.permission.CAMERA),
            notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                granted(Manifest.permission.POST_NOTIFICATIONS),
            batteryExempt = power.isIgnoringBatteryOptimizations(packageName),
        )
    }

    private fun startDetection() {
        val rotation = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).rotation
        ContextCompat.startForegroundService(this, DetectionService.startIntent(this, rotation))
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun requestBatteryExemption() {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        val intent = if (request.resolveActivity(packageManager) != null) {
            request
        } else {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
        startActivity(intent)
    }
}
