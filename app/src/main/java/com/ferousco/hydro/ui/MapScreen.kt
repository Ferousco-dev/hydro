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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.BuildConfig
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
import com.ferousco.hydro.ui.components.SegmentedControl
import com.ferousco.hydro.ui.components.SheetDetent
import com.ferousco.hydro.ui.components.rememberMapSheetState
import com.ferousco.hydro.ui.pages.AlertsPage
import com.ferousco.hydro.ui.pages.AskInputBar
import com.ferousco.hydro.ui.pages.AskPage
import com.ferousco.hydro.ui.pages.NowPage
import com.ferousco.hydro.ui.pages.TrendsPage
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

private enum class SheetPage(val label: String) { Now("Now"), Trends("Trends"), Ask("Ask"), Alerts("Alerts") }

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

@Composable
fun MapScreen(
    state: HydroUiState,
    actions: MapActions
) {
    val sheetState = rememberMapSheetState()
    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(DefaultCamera, 11f)
    }
    var query by remember { mutableStateOf("") }
    var page by rememberSaveable { mutableStateOf(SheetPage.Now) }
    var layersOpen by remember { mutableStateOf(false) }
    var airQualityLayer by remember { mutableStateOf(false) }
    var topOverlayHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val hasMapsKey = BuildConfig.GOOGLE_MAPS_API_KEY.isNotBlank()

    val target = if (state.mapLatitude != null && state.mapLongitude != null) {
        LatLng(state.mapLatitude, state.mapLongitude)
    } else {
        null
    }
    LaunchedEffect(target) {
        if (target != null) {
            cameraState.animate(CameraUpdateFactory.newLatLngZoom(target, FOCUS_ZOOM), durationMs = 700)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(HydroColors.SnowWhite)) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraState,
            contentPadding = PaddingValues(bottom = HydroDimens.SheetPeekHeight),
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
            state.trip?.let { trip ->
                if (trip.points.size >= 2) {
                    trip.points.zipWithNext().forEach { (from, to) ->
                        Polyline(
                            points = listOf(LatLng(from.latitude, from.longitude), LatLng(to.latitude, to.longitude)),
                            color = HydroColors.heat(from.severity),
                            width = 12f
                        )
                    }
                }
                MarkerComposable(
                    state = rememberMarkerState(position = LatLng(trip.destinationLat, trip.destinationLon)),
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                    contentDescription = "Destination"
                ) {
                    MapDot(color = HydroColors.TextPrimary)
                }
            }
            if (target != null) {
                MarkerComposable(
                    keys = arrayOf(state.isCustomLocation),
                    state = rememberMarkerState(position = target),
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                    contentDescription = state.locationLabel
                ) {
                    MapDot(color = if (state.isCustomLocation) HydroColors.Danger else HydroColors.Blue)
                }
            }
        }

        // Top: search, activity chips, optional error.
        Column(
            modifier = Modifier
                .onSizeChanged { topOverlayHeightPx = it.height }
                .statusBarsPadding()
                .padding(vertical = HydroDimens.SpaceSm),
            verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd)
        ) {
            MapSearchBar(
                query = query,
                onQueryChange = { query = it },
                onSearch = { actions.onSearch(query) },
                onClear = {
                    query = ""
                    actions.onDismissSearchError()
                    if (state.isCustomLocation) actions.onUseMyLocation()
                },
                isSearching = state.isSearching,
                modifier = Modifier.padding(horizontal = HydroDimens.ScreenEdge)
            )
            MapCategoryChips(
                categories = ActivityCategories,
                selected = state.activity,
                onSelect = actions.onActivity,
                contentPadding = HydroDimens.ScreenEdge
            )
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
                    following = state.usingDeviceLocation,
                    onClick = actions.onUseMyLocation
                )
            }
        }

        MapBottomSheet(
            state = sheetState,
            modifier = Modifier.fillMaxSize(),
            topInset = with(density) { topOverlayHeightPx.toDp() },
            contentScrollable = page != SheetPage.Ask,
            footer = if (page == SheetPage.Ask && state.assistantEnabled) {
                {
                    AskInputBar(
                        enabled = !state.isAsking,
                        onAsk = actions.onAsk,
                        onFocused = { sheetState.detent = SheetDetent.Full }
                    )
                }
            } else {
                null
            },
            header = {
                SegmentedControl(
                    options = SheetPage.entries.map { it to it.label },
                    selected = page,
                    onSelect = {
                        page = it
                        if (it == SheetPage.Ask && sheetState.detent == SheetDetent.Peek) sheetState.expand()
                    },
                    modifier = Modifier.padding(bottom = HydroDimens.SpaceMd)
                )
            }
        ) {
            when (page) {
                SheetPage.Now -> NowPage(
                    state = state,
                    activityLabel = ActivityCategories.first { it.value == state.activity }.label,
                    onRefresh = actions.onRefresh,
                    onRouteHere = actions.onRouteHere,
                    onTripMode = actions.onTripMode,
                    onClearTrip = actions.onClearTrip
                )
                SheetPage.Trends -> TrendsPage(state)
                SheetPage.Ask -> AskPage(
                    state = state,
                    onAsk = actions.onAsk
                )
                SheetPage.Alerts -> AlertsPage(
                    state = state,
                    onEnabled = actions.onAlertsEnabled,
                    onSeverity = actions.onMinimumSeverity,
                    onQuietHour = actions.onQuietHour
                )
            }
        }
    }
}

/** Every action the screen can trigger, grouped so [MapScreen] stays a pure function of state. */
data class MapActions(
    val onRefresh: () -> Unit,
    val onSearch: (String) -> Unit,
    val onDismissSearchError: () -> Unit,
    val onUseMyLocation: () -> Unit,
    val onActivity: (ActivityCategory) -> Unit,
    val onAsk: (String) -> Unit,
    val onToggleHeatLayer: () -> Unit,
    val onSetDestination: (Double, Double) -> Unit,
    val onRouteHere: () -> Unit,
    val onTripMode: (TripMode) -> Unit,
    val onClearTrip: () -> Unit,
    val onAlertsEnabled: (Boolean) -> Unit,
    val onMinimumSeverity: (NotificationSeverity) -> Unit,
    val onQuietHour: (Boolean, Int) -> Unit
)

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
