package com.ferousco.hydro

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import com.ferousco.hydro.wearables.HealthConnectWearableDataSource
import com.ferousco.hydro.ui.WearableLinkUi
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import android.net.Uri
import android.content.Intent
import android.content.ActivityNotFoundException
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
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ferousco.hydro.location.DeviceLocationProvider
import com.ferousco.hydro.ui.HydroViewModel
import com.ferousco.hydro.ui.MapActions
import com.ferousco.hydro.ui.HydroScreen
import com.ferousco.hydro.ui.theme.HydroTheme
import com.ferousco.hydro.ui.theme.LocalReducedMotion
import androidx.compose.runtime.getValue

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
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refresh()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        if (!locationProvider.hasLocationPermission()) {
            locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Health Connect shows its own screen where the user allows each metric.
    val healthLauncher = rememberLauncherForActivityResult(
        contract = HealthConnectWearableDataSource.permissionContract()
    ) { viewModel.refreshWearables() }

    // Coming back from Health Connect or Google Play: re-check what is connected.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshWearables() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val actions = remember(viewModel) {
        MapActions(
            onRefresh = viewModel::refresh,
            onSearchQuery = viewModel::searchPlaces,
            onSubmitSearch = viewModel::submitSearch,
            onClearSuggestions = viewModel::clearSuggestions,
            onPickPlace = viewModel::selectPlace,
            onDismissSearchError = viewModel::clearSearchError,
            onUseMyLocation = {
                if (locationProvider.hasLocationPermission()) {
                    viewModel.useMyLocation()
                } else {
                    locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            },
            onActivity = viewModel::setActivity,
            onAsk = viewModel::ask,
            onStopAsk = viewModel::stopAsking,
            onRetryAsk = viewModel::retryAsk,
            onNewChat = viewModel::newChat,
            onRevealed = viewModel::markRevealed,
            onOpenChat = {},
            onToggleHeatLayer = viewModel::toggleHeatLayer,
            onSetDestination = viewModel::setDestination,
            onTripMode = viewModel::setTripMode,
            onBackFromTrip = viewModel::clearTrip,
            onRetryTrip = viewModel::retryTrip,
            onPinCurrent = viewModel::pinCurrent,
            onUnpinCurrent = viewModel::unpinCurrent,
            onOpenPin = viewModel::openPin,
            onAlertsEnabled = viewModel::setAlertsEnabled,
            onMinimumSeverity = viewModel::setMinimumSeverity,
            onQuietHour = viewModel::shiftQuietHour,
            onConnectHealth = {
                if (viewModel.state.value.wearables.link == WearableLinkUi.NeedsInstall) {
                    openHealthConnectInStore(context)
                } else {
                    healthLauncher.launch(HealthConnectWearableDataSource.REQUIRED_PERMISSIONS)
                }
            },
            onDisconnectHealth = viewModel::disconnectWearables,
            onAssistantMayUseHealth = viewModel::setAssistantMayUseHealthData,
            onRefreshHealth = viewModel::refreshWearables,
            onAnimations = viewModel::setAnimationsOn,
            onFollow = viewModel::setFollowing,
            onLanguage = viewModel::setLanguage
        )
    }

    HydroScreen(state = state, actions = actions)
}

/** Opens the Health Connect listing in Google Play (the user asked for it by tapping the button). */
private fun openHealthConnectInStore(context: android.content.Context) {
    val id = "com.google.android.apps.healthdata"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id"))
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id")))
    }
}
