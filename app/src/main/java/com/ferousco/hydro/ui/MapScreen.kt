package com.ferousco.hydro.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ferousco.hydro.BuildConfig
import com.ferousco.hydro.core.model.ActivityCategory
import com.ferousco.hydro.ui.components.ActionRow
import com.ferousco.hydro.ui.components.AppleActivityIndicator
import com.ferousco.hydro.ui.components.ChatMessageRow
import com.ferousco.hydro.ui.components.CurrentLocationButton
import com.ferousco.hydro.ui.components.HydroIconButton
import com.ferousco.hydro.ui.components.HydroTextButton
import com.ferousco.hydro.ui.components.IndicatorSize
import com.ferousco.hydro.ui.components.MapBottomSheet
import com.ferousco.hydro.ui.components.MapCategory
import com.ferousco.hydro.ui.components.MapCategoryChips
import com.ferousco.hydro.ui.components.MapControlGroup
import com.ferousco.hydro.ui.components.MapGroupButton
import com.ferousco.hydro.ui.components.MapSearchBar
import com.ferousco.hydro.ui.components.MetricRow
import com.ferousco.hydro.ui.components.PhIcon
import com.ferousco.hydro.ui.components.PhosphorIcon
import com.ferousco.hydro.ui.components.PlaceDetailsHeader
import com.ferousco.hydro.ui.components.SheetDetent
import com.ferousco.hydro.ui.components.SheetSectionTitle
import com.ferousco.hydro.ui.components.SkeletonPlaceDetails
import com.ferousco.hydro.ui.components.SkeletonPlaceRows
import com.ferousco.hydro.ui.components.StatusPill
import com.ferousco.hydro.ui.components.HydroPrimaryButton
import com.ferousco.hydro.ui.components.rememberMapSheetState
import com.ferousco.hydro.ui.theme.HydroColors
import com.ferousco.hydro.ui.theme.HydroDimens
import com.ferousco.hydro.ui.theme.HydroShapes
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import com.google.android.gms.maps.model.UrlTileProvider
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.TileOverlay
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import java.net.URL
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    onRefresh: () -> Unit,
    onSearch: (String) -> Unit,
    onDismissSearchError: () -> Unit,
    onUseMyLocation: () -> Unit,
    onActivity: (ActivityCategory) -> Unit,
    onAsk: (String) -> Unit
) {
    val sheetState = rememberMapSheetState()
    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(DefaultCamera, 11f)
    }
    var query by remember { mutableStateOf("") }
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
            cameraState.animate(
                com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(target, FOCUS_ZOOM),
                durationMs = 700
            )
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
            )
        ) {
            if (airQualityLayer && hasMapsKey) {
                TileOverlay(
                    tileProvider = remember { airQualityTiles(BuildConfig.GOOGLE_MAPS_API_KEY) },
                    transparency = 0.35f
                )
            }
            if (target != null) {
                MarkerComposable(
                    keys = arrayOf(state.isCustomLocation),
                    state = rememberMarkerState(position = target),
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                    contentDescription = state.locationLabel
                ) {
                    LocationDot(searched = state.isCustomLocation)
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
                onSearch = { onSearch(query) },
                onClear = {
                    query = ""
                    onDismissSearchError()
                    if (state.isCustomLocation) onUseMyLocation()
                },
                isSearching = state.isSearching,
                modifier = Modifier.padding(horizontal = HydroDimens.ScreenEdge)
            )
            MapCategoryChips(
                categories = ActivityCategories,
                selected = state.activity,
                onSelect = onActivity,
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
                    HydroIconButton(PhIcon.Close, "Dismiss message", onDismissSearchError)
                }
            }
        }

        // Floating map controls sit just above the sheet and tuck away when it is fully open.
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
                if (hasMapsKey) {
                    MapControlGroup {
                        MapGroupButton(
                            icon = PhIcon.Layers,
                            contentDescription = if (airQualityLayer) "Hide air quality layer" else "Show air quality layer",
                            onClick = { airQualityLayer = !airQualityLayer },
                            selected = airQualityLayer,
                            showDivider = false
                        )
                    }
                }
                CurrentLocationButton(
                    following = state.usingDeviceLocation,
                    onClick = onUseMyLocation
                )
            }
        }

        MapBottomSheet(
            state = sheetState,
            modifier = Modifier.fillMaxSize(),
            topInset = with(density) { topOverlayHeightPx.toDp() }
        ) {
            PlaceSheetContent(
                state = state,
                onRefresh = onRefresh,
                onAsk = onAsk,
                onChatFocused = { sheetState.detent = SheetDetent.Full }
            )
        }
    }
}

@Composable
private fun LocationDot(searched: Boolean) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .shadow(3.dp, CircleShape)
            .background(Color.White, CircleShape)
            .padding(3.dp)
            .background(if (searched) HydroColors.Danger else HydroColors.Blue, CircleShape)
    )
}

/** Google's air-quality heat-map tiles as a map overlay. Needs the Maps key. */
private fun airQualityTiles(apiKey: String): TileProvider = object : UrlTileProvider(256, 256) {
    override fun getTileUrl(x: Int, y: Int, zoom: Int): URL? {
        if (zoom !in 0..16) return null
        return URL("https://airquality.googleapis.com/v1/mapTypes/US_AQI/heatmapTiles/$zoom/$x/$y?key=$apiKey")
    }
}

private fun urgencyColors(urgency: String): Pair<Color, Color> = when (urgency) {
    "CRITICAL", "HIGH" -> HydroColors.Danger to HydroColors.DangerContainer
    "MODERATE" -> HydroColors.Warning to HydroColors.WarningContainer
    "LOW" -> HydroColors.Positive to HydroColors.IceBlue
    else -> HydroColors.TextSecondary to HydroColors.IceBlue
}

@Composable
private fun PlaceSheetContent(
    state: HydroUiState,
    onRefresh: () -> Unit,
    onAsk: (String) -> Unit,
    onChatFocused: () -> Unit
) {
    val hasData = state.airTemperatureCelsius != null || state.heatIndexCelsius != null
    val activityLabel = ActivityCategories.first { it.value == state.activity }.label

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = HydroDimens.SpaceXl)) {
        if (!hasData && state.errorMessage == null) {
            SkeletonPlaceDetails()
            Column(modifier = Modifier.padding(top = HydroDimens.SpaceXl)) { SkeletonPlaceRows() }
            return@Column
        }

        PlaceDetailsHeader(
            title = state.locationLabel,
            subtitle = listOfNotNull(
                activityLabel,
                state.freshnessLabel?.lowercase(Locale.getDefault())?.replaceFirstChar { it.uppercase() },
                state.observationTime?.let { formatTime(it) }?.let { "Observed $it" }
            ).joinToString(" · ").ifBlank { null },
            trailing = {
                if (state.isLoading) {
                    Box(Modifier.size(HydroDimens.TouchTarget), contentAlignment = Alignment.Center) {
                        AppleActivityIndicator(size = IndicatorSize.Medium, color = HydroColors.TextSecondary)
                    }
                } else {
                    HydroIconButton(PhIcon.Refresh, "Refresh conditions", onRefresh)
                }
            }
        )

        state.errorMessage?.let { message ->
            Text(
                text = "Couldn't load conditions. $message",
                style = MaterialTheme.typography.bodyMedium,
                color = HydroColors.Danger,
                modifier = Modifier.padding(top = HydroDimens.SpaceMd)
            )
            HydroTextButton("Try again", onRefresh)
        }

        if (hasData) {
            Row(
                modifier = Modifier.padding(top = HydroDimens.SpaceSm),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd)
            ) {
                Text(
                    text = "${state.airTemperatureCelsius.formatOrDash(0)}°",
                    style = MaterialTheme.typography.displaySmall,
                    color = HydroColors.TextPrimary
                )
                state.feelsLikeCelsius?.let {
                    Text(
                        text = "Feels like ${it.formatOrDash(0)}°",
                        style = MaterialTheme.typography.bodyLarge,
                        color = HydroColors.TextSecondary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }

            state.recommendation?.let { rec ->
                val (fg, bg) = urgencyColors(rec.urgency)
                StatusPill(
                    text = rec.urgency.lowercase().replaceFirstChar { it.uppercase() } + " risk",
                    foreground = fg,
                    background = bg,
                    modifier = Modifier.padding(top = HydroDimens.SpaceMd)
                )
                Text(
                    text = rec.headline,
                    style = MaterialTheme.typography.bodyLarge,
                    color = HydroColors.TextPrimary,
                    modifier = Modifier.padding(top = HydroDimens.SpaceMd)
                )
                Text(
                    text = rec.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = HydroColors.TextSecondary,
                    modifier = Modifier.padding(top = HydroDimens.SpaceXs)
                )
                rec.bestWindow?.let {
                    Text(
                        text = "Coolest window · $it",
                        style = MaterialTheme.typography.labelLarge,
                        color = HydroColors.Blue,
                        modifier = Modifier.padding(top = HydroDimens.SpaceSm)
                    )
                }
                if (rec.actions.isNotEmpty()) {
                    SheetSectionTitle("What to do")
                    rec.actions.forEach { (title, detail) -> ActionRow(title, detail) }
                }
                if (!rec.isCurrentData) {
                    Text(
                        "Based on aging data. Refresh before relying on this.",
                        style = MaterialTheme.typography.bodySmall,
                        color = HydroColors.Warning,
                        modifier = Modifier.padding(top = HydroDimens.SpaceSm)
                    )
                }
            }
            state.recommendationError?.let {
                Text(
                    "Advice unavailable: $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HydroColors.Warning,
                    modifier = Modifier.padding(top = HydroDimens.SpaceMd)
                )
            }

            SheetSectionTitle("Conditions")
            if (state.heatIndexCelsius != null) {
                MetricRow(
                    "Heat index",
                    "${state.heatIndexCelsius.formatOrDash(0)}° · ${shortCategory(state.heatCategory)}",
                    icon = PhIcon.Thermometer
                )
            }
            MetricRow("Humidity", state.relativeHumidityPercent.formatOrDash(0).withUnit("%"), icon = PhIcon.Droplet)
            MetricRow("Wind", state.windSpeedMetersPerSecond.formatOrDash(1).withUnit("m/s"), icon = PhIcon.Wind)
            MetricRow(
                "UV index",
                state.uvIndex.formatOrDash(1) + (state.uvCategory?.let { " · $it" } ?: ""),
                icon = PhIcon.Outdoor
            )
            if (state.heatIndexCelsius == null && state.missingVariables.isNotEmpty()) {
                Text(
                    "Missing: ${state.missingVariables.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = HydroColors.Warning,
                    modifier = Modifier.padding(top = HydroDimens.SpaceSm)
                )
            }

            if (!state.isLiveAndFresh && state.deliveryOriginLabel != null) {
                Text(
                    "This reading is not a fresh live measurement. Treat it as cached or aging data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = HydroColors.Warning,
                    modifier = Modifier.padding(top = HydroDimens.SpaceMd)
                )
            }
        }

        AssistantSection(state = state, onAsk = onAsk, onFocused = onChatFocused)

        Text(
            text = "Weather: Open-Meteo (CC-BY 4.0). Hydro is informational and not medical advice.",
            style = MaterialTheme.typography.bodySmall,
            color = HydroColors.TextSecondary,
            modifier = Modifier.padding(top = HydroDimens.SpaceXl)
        )
    }
}

@Composable
private fun AssistantSection(
    state: HydroUiState,
    onAsk: (String) -> Unit,
    onFocused: () -> Unit
) {
    SheetSectionTitle("Ask Hydro")
    if (!state.assistantEnabled) {
        Text(
            "Add GEMINI_API_KEY to local.properties and rebuild to turn on the assistant.",
            style = MaterialTheme.typography.bodyMedium,
            color = HydroColors.TextSecondary
        )
        return
    }
    var input by remember { mutableStateOf("") }
    fun submit() {
        if (input.isNotBlank() && !state.isAsking) {
            onAsk(input)
            input = ""
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(HydroDimens.SpaceMd)) {
        state.chat.forEach { ChatMessageRow(it.fromUser, it.text, it.note) }
        if (state.isAsking) {
            AppleActivityIndicator(size = IndicatorSize.Medium, color = HydroColors.TextSecondary, contentDescription = "Hydro is thinking")
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(HydroColors.IceBlue, HydroShapes.Search),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = HydroDimens.SpaceLg, top = HydroDimens.SpaceMd, bottom = HydroDimens.SpaceMd),
                contentAlignment = Alignment.CenterStart
            ) {
                if (input.isEmpty()) {
                    Text(
                        "Should I walk 12 km this afternoon?",
                        style = MaterialTheme.typography.bodyLarge,
                        color = HydroColors.TextSecondary
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = HydroColors.TextPrimary),
                    cursorBrush = SolidColor(HydroColors.Blue),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { android.util.Log.d("HydroFocus", "chat focus=${it.isFocused}"); if (it.isFocused) onFocused() }
                )
            }
            HydroIconButton(
                icon = PhIcon.Send,
                contentDescription = "Send question",
                onClick = ::submit,
                enabled = input.isNotBlank() && !state.isAsking,
                tint = HydroColors.Blue
            )
        }
    }
}

/** NWS names are long; "Below NWS Caution Threshold" reads better as "No heat risk". */
private fun shortCategory(name: String?): String = when {
    name == null -> "—"
    name.startsWith("Below NWS Caution") -> "No heat risk"
    else -> name.removePrefix("NWS ")
}

private fun String.withUnit(unit: String) = if (this == "—") this else "$this $unit"

private fun Double?.formatOrDash(decimals: Int): String =
    this?.let { String.format(Locale.getDefault(), "%.${decimals}f", it) } ?: "—"

private fun formatTime(instant: java.time.Instant): String =
    DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(instant)
