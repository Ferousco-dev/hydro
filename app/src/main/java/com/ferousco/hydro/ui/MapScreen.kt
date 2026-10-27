package com.ferousco.hydro.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.BuildConfig
import com.ferousco.hydro.ui.components.PhosphorIcon
import kotlinx.coroutines.launch
import com.google.android.gms.maps.model.LatLngBounds
import com.ferousco.hydro.ui.pages.TripPageActions
import com.ferousco.hydro.ui.pages.TripPage
import com.ferousco.hydro.ui.components.PlaceSuggestionList
import com.ferousco.hydro.ui.components.PinDialog
import com.ferousco.hydro.core.trip.TripMode
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import com.ferousco.hydro.core.integration.NotificationSeverity
import com.ferousco.hydro.core.model.ActivityCategory
import com.ferousco.hydro.ui.components.HydroIconButton
import com.ferousco.hydro.ui.components.CurrentLocationButton
import com.ferousco.hydro.ui.components.IndicatorSize
import com.ferousco.hydro.ui.components.AppleActivityIndicator
import com.ferousco.hydro.ui.components.MapBottomSheet
import com.ferousco.hydro.ui.components.MapCategory
import com.ferousco.hydro.ui.components.MapCategoryChips
import com.ferousco.hydro.ui.components.MapControlGroup
import com.ferousco.hydro.ui.components.MapGroupButton
import com.ferousco.hydro.ui.components.MapSearchBar
import com.ferousco.hydro.ui.components.PhIcon
import com.ferousco.hydro.ui.components.SheetDetent
import com.ferousco.hydro.ui.components.rememberMapSheetState
import com.ferousco.hydro.ui.pages.NowPage
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens
import com.ferousco.hydro.ui.theme.HydroShapes
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.TileProvider
import com.google.android.gms.maps.model.UrlTileProvider
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.TileOverlay
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import java.net.URL
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

private val ActivityCategories = listOf(
    MapCategory(ActivityCategory.WALKING, "Walking", PhIcon.Walk),
    MapCategory(ActivityCategory.RUNNING, "Running", PhIcon.Run),
    MapCategory(ActivityCategory.CYCLING, "Cycling", PhIcon.Bike),
    MapCategory(ActivityCategory.OUTDOOR_WORK, "Outdoor work", PhIcon.Work),
    MapCategory(ActivityCategory.COMMUTING, "Commuting", PhIcon.Commute),
    MapCategory(ActivityCategory.GENERAL_OUTDOOR, "Outdoors", PhIcon.Outdoor)
)

/** Quiet map: business and transit clutter off so Hydro's own overlays and marker read clearly. */
private const val QUIET_MAP_STYLE = """[
  {"featureType":"poi.business","stylers":[{"visibility":"off"}]},
  {"featureType":"poi.medical","stylers":[{"visibility":"off"}]},
  {"featureType":"transit","stylers":[{"visibility":"off"}]},
  {"featureType":"water","elementType":"geometry","stylers":[{"color":"#cfe6fb"}]},
  {"featureType":"landscape","elementType":"geometry","stylers":[{"color":"#f4f8fc"}]}
]"""

private val DefaultCamera = LatLng(7.3775, 3.9470)
private const val FOCUS_ZOOM = 12.5f

/**
 * The Map tab: the map, search, activity chips, floating controls and the bottom panel. The panel
 * shows today's conditions normally, and switches to the trip view while you are going somewhere.
 */
@Composable
fun MapTab(
    state: HydroUiState,
    actions: MapActions,
    bottomSpace: Dp
) {
    val sheetState = rememberMapSheetState()
    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(DefaultCamera, 11f)
    }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var layersOpen by remember { mutableStateOf(false) }
    var airQualityLayer by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var searchBlockHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val hasMapsKey = BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank()
    val trip = state.trip

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topOverlayHeight = with(density) { searchBlockHeightPx.toDp() } + statusBarTop

    val target = if (state.mapLatitude != null && state.mapLongitude != null) {
        LatLng(state.mapLatitude, state.mapLongitude)
    } else {
        null
    }

    // Leaving a trip clears the search box so it is ready for the next place.
    LaunchedEffect(trip == null) {
        if (trip == null) {
            query = ""
            actions.onClearSuggestions()
        }
    }
    // Back at the normal view: go to where you are.
    LaunchedEffect(target, trip == null) {
        if (target != null && trip == null) {
            cameraState.animate(CameraUpdateFactory.newLatLngZoom(target, FOCUS_ZOOM), durationMs = 700)
        }
    }
    // A trip: fit the whole route on screen and tuck the panel down so the route is visible.
    LaunchedEffect(trip?.destinationLat, trip?.destinationLon, trip?.mode, trip?.route?.size) {
        if (trip != null) {
            sheetState.detent = SheetDetent.Peek
            val points = trip.route.map { LatLng(it.latitude, it.longitude) } +
                listOfNotNull(target, LatLng(trip.destinationLat, trip.destinationLon))
            val bounds = LatLngBounds.builder().apply { points.forEach(::include) }.build()
            try {
                cameraState.animate(CameraUpdateFactory.newLatLngBounds(bounds, 110), durationMs = 900)
            } catch (_: IllegalStateException) {
                // The map has not been laid out yet; it will frame itself on the next change.
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(HydroColors.SnowWhite)) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraState,
            contentPadding = PaddingValues(top = topOverlayHeight, bottom = HydroDimens.SheetPeekHeight + bottomSpace),
            properties = MapProperties(mapStyleOptions = MapStyleOptions(QUIET_MAP_STYLE)),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                mapToolbarEnabled = false,
                myLocationButtonEnabled = false,
                compassEnabled = true
            ),
            onMapLongClick = { latLng -> actions.onSetDestination(latLng.latitude, latLng.longitude) }
        ) {
            if (airQualityLayer && hasMapsKey) {
                TileOverlay(
                    tileProvider = remember { airQualityTiles(BuildConfig.GOOGLE_MAPS_API_KEY) },
                    transparency = 0.35f
                )
            }
            if (state.heatLayerOn) {
                state.heatLayer?.cells?.forEach { cell ->
                    Circle(
                        center = LatLng(cell.latitude, cell.longitude),
                        radius = state.heatLayer.cellRadiusMeters,
                        fillColor = HydroColors.heat(cell.severity).copy(alpha = 0.38f),
                        strokeColor = Color.Transparent,
                        strokeWidth = 0f
                    )
                }
            }
            // The route: a white outline first so it reads on any map, then segments coloured by heat.
            if (trip != null && trip.route.size >= 2) {
                val latLngs = trip.route.map { LatLng(it.latitude, it.longitude) }
                Polyline(points = latLngs, color = Color.White, width = 24f, zIndex = 1f)
                trip.route.zipWithNext().forEach { (from, to) ->
                    Polyline(
                        points = listOf(LatLng(from.latitude, from.longitude), LatLng(to.latitude, to.longitude)),
                        color = if (from.severity <= 0) HydroColors.Blue else HydroColors.heat(from.severity),
                        width = 14f,
                        zIndex = 2f
                    )
                }
            }
            state.pins.forEach { pin ->
                MarkerComposable(
                    state = rememberMarkerState(position = LatLng(pin.latitude, pin.longitude)),
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                    contentDescription = "Pinned: ${pin.label}",
                    onClick = {
                        actions.onOpenPin(pin)
                        true
                    }
                ) { PinMarker(color = HydroColors.Blue) }
            }
            if (trip != null) {
                MarkerComposable(
                    state = rememberMarkerState(position = LatLng(trip.destinationLat, trip.destinationLon)),
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                    contentDescription = "Destination: ${trip.destinationName}",
                    zIndex = 3f
                ) { PinMarker(color = HydroColors.TextPrimary) }
            }
            if (target != null) {
                MarkerComposable(
                    state = rememberMarkerState(position = target),
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                    contentDescription = state.locationLabel
                ) { MapDot(color = HydroColors.Blue) }
            }
        }

        // Weather: a big see-through sun or moon bleeds in from the top corner, behind the search bar,
        // and rain or snow falls over the whole map.
        val sky = state.sky
        if (state.animationsOn && sky != null) {
            com.ferousco.hydro.ui.components.SkyAtmosphere(sky, animated = true, modifier = Modifier.align(Alignment.TopStart))
            if (sky.isRaining || sky.kind == com.ferousco.hydro.ui.SkyKind.Snow) {
                com.ferousco.hydro.ui.components.PrecipitationOverlay(snow = sky.kind == com.ferousco.hydro.ui.SkyKind.Snow)
            }
        }

        // Top: search, then (when not on a trip) activity and pinned chips, then any message and results.
        Column(modifier = Modifier.statusBarsPadding()) {
            Column(
                modifier = Modifier
                    .onSizeChanged { searchBlockHeightPx = it.height }
                    .padding(vertical = HydroDimens.SpaceSm),
                verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd)
            ) {
                MapSearchBar(
                    query = query,
                    onQueryChange = {
                        query = it
                        actions.onSearchQuery(it)
                    },
                    onSearch = { actions.onSubmitSearch(query) },
                    onClear = {
                        query = ""
                        actions.onClearSuggestions()
                        actions.onDismissSearchError()
                    },
                    isSearching = state.isSearching,
                    placeholder = "Where to?",
                    modifier = Modifier.padding(horizontal = HydroDimens.ScreenEdge)
                )
                if (trip == null) {
                    MapCategoryChips(
                        categories = ActivityCategories,
                        selected = state.activity,
                        onSelect = actions.onActivity,
                        contentPadding = HydroDimens.ScreenEdge
                    )
                    if (state.pins.isNotEmpty()) {
                        MapCategoryChips<PinnedPlaceUi?>(
                            categories = state.pins.map { MapCategory(it, it.label, PhIcon.Pin) },
                            selected = null,
                            onSelect = { pin -> pin?.let(actions.onOpenPin) },
                            contentPadding = HydroDimens.ScreenEdge
                        )
                    }
                }
                state.searchError?.let { message ->
                    Row(
                        modifier = Modifier
                            .padding(horizontal = HydroDimens.ScreenEdge)
                            .fillMaxWidth()
                            .shadow(3.dp, HydroShapes.Control, clip = false)
                            .background(HydroColors.FloatingSurface, HydroShapes.Control)
                            .padding(start = HydroDimens.SpaceLg),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = HydroColors.Danger,
                            modifier = Modifier.weight(1f).padding(vertical = HydroDimens.SpaceMd)
                        )
                        HydroIconButton(PhIcon.Close, "Dismiss message", actions.onDismissSearchError)
                    }
                }
            }
            if (state.suggestions.isNotEmpty()) {
                PlaceSuggestionList(
                    suggestions = state.suggestions,
                    onPick = { place ->
                        query = place.name
                        actions.onPickPlace(place)
                    },
                    modifier = Modifier.padding(horizontal = HydroDimens.ScreenEdge)
                )
            }
        }

        // Floating controls sit just above the sheet and tuck away when it is fully open.
        AnimatedVisibility(
            visible = sheetState.detent != SheetDetent.Full,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd)
        ) {
            Column(
                modifier = Modifier
                    .padding(end = HydroDimens.ScreenEdge)
                    .padding(bottom = minOf(sheetState.visibleHeight, HydroDimens.SheetPeekHeight * 3) + HydroDimens.SpaceMd),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceSm)
            ) {
                if (layersOpen) {
                    LayersMenu(
                        heatOn = state.heatLayerOn,
                        heatLoading = state.heatLayerLoading,
                        heatError = state.heatLayerError,
                        airQualityOn = airQualityLayer,
                        airQualityAvailable = hasMapsKey,
                        onHeat = actions.onToggleHeatLayer,
                        onAirQuality = { airQualityLayer = !airQualityLayer }
                    )
                }
                MapControlGroup {
                    MapGroupButton(
                        icon = PhIcon.Layers,
                        contentDescription = if (layersOpen) "Close map layers" else "Open map layers",
                        onClick = { layersOpen = !layersOpen },
                        selected = layersOpen || state.heatLayerOn || airQualityLayer,
                        showDivider = false
                    )
                }
                CurrentLocationButton(
                    following = state.usingDeviceLocation && trip == null,
                    onClick = {
                        actions.onUseMyLocation()
                        if (target != null) {
                            scope.launch { cameraState.animate(CameraUpdateFactory.newLatLngZoom(target, FOCUS_ZOOM), 600) }
                        }
                    }
                )
            }
        }

        MapBottomSheet(
            state = sheetState,
            modifier = Modifier.fillMaxSize(),
            topInset = topOverlayHeight,
            bottomSpace = bottomSpace,
            glowActive = trip?.aiWorking == true || (trip != null && state.isAsking)
        ) {
            if (trip != null) {
                TripPage(
                    state = state,
                    trip = trip,
                    actions = TripPageActions(
                        onBack = actions.onBackFromTrip,
                        onMode = actions.onTripMode,
                        onRetry = actions.onRetryTrip,
                        onPinClick = { if (trip.pinLabel != null) actions.onUnpinCurrent() else showPinDialog = true },
                        onAsk = actions.onAsk,
                        onStopAsk = actions.onStopAsk,
                        onRetryAsk = actions.onRetryAsk,
                        onRevealed = actions.onRevealed,
                        onOpenChat = actions.onOpenChat
                    )
                )
            } else {
                NowPage(
                    state = state,
                    activityLabel = ActivityCategories.first { it.value == state.activity }.label,
                    onRefresh = actions.onRefresh
                )
            }
        }

        if (showPinDialog && trip != null) {
            PinDialog(
                defaultName = trip.destinationName,
                onConfirm = { name ->
                    showPinDialog = false
                    actions.onPinCurrent(name)
                },
                onDismiss = { showPinDialog = false }
            )
        }
    }
}

/** Every action the screen can trigger, grouped so [MapScreen] stays a pure function of state. */
data class MapActions(
    val onRefresh: () -> Unit,
    val onSearchQuery: (String) -> Unit,
    val onSubmitSearch: (String) -> Unit,
    val onClearSuggestions: () -> Unit,
    val onPickPlace: (PlaceSuggestionUi) -> Unit,
    val onDismissSearchError: () -> Unit,
    val onUseMyLocation: () -> Unit,
    val onActivity: (ActivityCategory) -> Unit,
    val onAsk: (String) -> Unit,
    val onStopAsk: () -> Unit,
    val onRetryAsk: () -> Unit,
    val onNewChat: () -> Unit,
    val onRevealed: (Long) -> Unit,
    val onOpenChat: () -> Unit,
    val onToggleHeatLayer: () -> Unit,
    val onSetDestination: (Double, Double) -> Unit,
    val onTripMode: (TripMode) -> Unit,
    val onBackFromTrip: () -> Unit,
    val onRetryTrip: () -> Unit,
    val onPinCurrent: (String) -> Unit,
    val onUnpinCurrent: () -> Unit,
    val onOpenPin: (PinnedPlaceUi) -> Unit,
    val onConnectHealth: () -> Unit,
    val onDisconnectHealth: () -> Unit,
    val onAssistantMayUseHealth: (Boolean) -> Unit,
    val onRefreshHealth: () -> Unit,
    val onAnimations: (Boolean) -> Unit,
    val onAlertsEnabled: (Boolean) -> Unit,
    val onMinimumSeverity: (NotificationSeverity) -> Unit,
    val onQuietHour: (Boolean, Int) -> Unit
)

/** A round marker with a pin glyph, used for the destination and for pinned places. */
@Composable
private fun PinMarker(color: Color) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .shadow(3.dp, CircleShape)
            .background(Color.White, CircleShape)
            .padding(3.dp)
            .background(color, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        PhosphorIcon(PhIcon.Pin, contentDescription = null, selected = true, size = HydroDimens.IconSmall, tint = Color.White)
    }
}

@Composable
private fun MapDot(color: Color) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .shadow(3.dp, CircleShape)
            .background(Color.White, CircleShape)
            .padding(3.dp)
            .background(color, CircleShape)
    )
}

/** Compact layer picker shown above the controls. Each row says what it does and why it may be off. */
@Composable
private fun LayersMenu(
    heatOn: Boolean,
    heatLoading: Boolean,
    heatError: String?,
    airQualityOn: Boolean,
    airQualityAvailable: Boolean,
    onHeat: () -> Unit,
    onAirQuality: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(232.dp)
            .shadow(3.dp, HydroShapes.Control, clip = false)
            .background(HydroColors.FloatingSurface, HydroShapes.Control)
            .padding(vertical = HydroDimens.SpaceXs)
    ) {
        LayerRow("Heat", "Where it feels hottest", heatOn, heatLoading, onHeat)
        if (heatError != null) {
            Text(
                heatError,
                style = MaterialTheme.typography.bodySmall,
                color = HydroColors.Danger,
                modifier = Modifier.padding(horizontal = HydroDimens.SpaceLg, vertical = HydroDimens.SpaceXs)
            )
        }
        if (airQualityAvailable) {
            LayerRow("Air quality", "How clean the air is", airQualityOn, false, onAirQuality)
        }
    }
}

@Composable
private fun LayerRow(title: String, detail: String, on: Boolean, loading: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch, onClick = onToggle)
            .padding(horizontal = HydroDimens.SpaceLg, vertical = HydroDimens.SpaceMd),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = HydroColors.TextPrimary)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = HydroColors.TextSecondary)
        }
        when {
            loading -> AppleActivityIndicator(size = IndicatorSize.Small, color = HydroColors.TextSecondary)
            on -> Text("On", style = MaterialTheme.typography.labelLarge, color = HydroColors.Blue)
            else -> Text("Off", style = MaterialTheme.typography.labelLarge, color = HydroColors.TextSecondary)
        }
    }
}

/** Google's air-quality heat-map tiles as a map overlay. Needs the Maps key. */
private fun airQualityTiles(apiKey: String): TileProvider = object : UrlTileProvider(256, 256) {
    override fun getTileUrl(x: Int, y: Int, zoom: Int): URL? {
        if (zoom !in 0..16) return null
        return URL("https://airquality.googleapis.com/v1/mapTypes/US_AQI/heatmapTiles/$zoom/$x/$y?key=$apiKey")
    }
}
