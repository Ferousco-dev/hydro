package com.ferousco.hydro

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ferousco.hydro.location.DeviceLocationProvider
import com.ferousco.hydro.ui.HydroViewModel
import com.ferousco.hydro.ui.MapScreen
import com.ferousco.hydro.ui.theme.HydroTheme
import com.ferousco.hydro.ui.theme.LocalReducedMotion

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val context = LocalContext.current
            // Animation scale 0 means the user asked for reduced motion.
            val reducedMotion = remember {
                Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            }
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
                HydroTheme {
                    HydroApp()
                }
            }
        }
    }
}

@Composable
private fun HydroApp(viewModel: HydroViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val locationProvider = remember { DeviceLocationProvider(context) }

    // Alerts are silently rejected on Android 13+ unless this is granted, so ask for it up front.
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refresh()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        if (!locationProvider.hasLocationPermission()) {
            locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    MapScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onSearch = viewModel::search,
        onDismissSearchError = viewModel::clearSearchError,
        onUseMyLocation = {
            if (locationProvider.hasLocationPermission()) {
                viewModel.useMyLocation()
            } else {
                locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        },
        onActivity = viewModel::setActivity,
        onAsk = viewModel::ask
    )
}
