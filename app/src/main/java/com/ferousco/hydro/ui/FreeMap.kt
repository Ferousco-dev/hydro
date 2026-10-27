package com.ferousco.hydro.ui

import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ferousco.hydro.ui.theme.HydroColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The key-free map: OpenStreetMap-based data in Esri's light gray style, so it matches Hydro's pale
 * look. [MapTab] shows it only while Google Maps is not usable (no key, or the key is not enabled
 * yet) and switches to Google by itself once the key works.
 */

private const val ESRI_BASE = "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas"
private const val MAP_CREDIT = "Esri, HERE, Garmin, © OpenStreetMap contributors"

/** Esri's light gray canvas: key-free, pale, and close to Hydro's own look. Drawn as base plus labels. */
private fun esriTiles(name: String, layer: String) = object : OnlineTileSourceBase(
    name, 0, 16, 256, "", arrayOf("$ESRI_BASE/$layer/MapServer/tile/"), MAP_CREDIT
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) +
            "/" + MapTileIndex.getX(pMapTileIndex)
}

private val LightTiles = esriTiles("EsriLightGray", "World_Light_Gray_Base")
private val LabelTiles = esriTiles("EsriLightGrayLabels", "World_Light_Gray_Reference")

private const val ESRI_SATELLITE_CREDIT = "Esri, Maxar, Earthstar Geographics, © OpenStreetMap contributors"

/** Satellite photos: Esri's world imagery, with place names drawn on top. */
private fun esriImagery(name: String, path: String, maxZoom: Int) = object : OnlineTileSourceBase(
    name, 0, maxZoom, 256, "", arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/$path/MapServer/tile/"), ESRI_SATELLITE_CREDIT
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) +
            "/" + MapTileIndex.getX(pMapTileIndex)
}

private val SatelliteTiles = esriImagery("EsriImagery", "World_Imagery", 19)
private val SatelliteLabelTiles = esriImagery("EsriImageryLabels", "Reference/World_Boundaries_and_Places", 19)

/** Everything the overlays are drawn from. Compared by value so the map is only rebuilt on a real change. */
private data class FreeScene(
    val pins: List<PinnedPlaceUi>,
    val route: List<TripPoint>,
    val destination: GeoPoint?,
    val target: GeoPoint?,
    val heat: HeatLayerUi?,
    val satellite: Boolean
)

@Composable
fun FreeMapLayer(
    pins: List<PinnedPlaceUi>,
    trip: TripUi?,
    target: GeoPoint?,
    heat: HeatLayerUi?,
    satellite: Boolean,
    bottomInset: Dp,
    onLongPress: (Double, Double) -> Unit,
    onPinClick: (PinnedPlaceUi) -> Unit,
    onReady: (MapView) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val currentLongPress by rememberUpdatedState(onLongPress)
    val currentPinClick by rememberUpdatedState(onPinClick)
    var mapView by remember { mutableStateOf<MapView?>(null) }
    fun labelOverlay(source: OnlineTileSourceBase) =
        TilesOverlay(MapTileProviderBasic(context, source), context).apply {
            loadingBackgroundColor = AndroidColor.TRANSPARENT
            loadingLineColor = AndroidColor.TRANSPARENT
        }
    val labels = remember { labelOverlay(LabelTiles) }
    val satelliteLabels = remember { labelOverlay(SatelliteLabelTiles) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle, mapView) {
        val view = mapView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            view?.onPause()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { ctx ->
                Configuration.getInstance().apply {
                    userAgentValue = ctx.packageName
                    osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
                    osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
                }
                MapView(ctx).apply {
                    setTileSource(LightTiles)
                    setMultiTouchControls(true)
                    isTilesScaledToDpi = true
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    minZoomLevel = 3.0
                    maxZoomLevel = 17.0
                    controller.setZoom(11.0)
                    controller.setCenter(GeoPoint(7.3775, 3.9470))
                    mapView = this
                    onReady(this)
                }
            },
            update = { map ->
                val scene = FreeScene(
                    pins = pins,
                    route = trip?.route.orEmpty(),
                    destination = trip?.let { GeoPoint(it.destinationLat, it.destinationLon) },
                    target = target,
                    heat = heat,
                    satellite = satellite
                )
                if (map.tag == scene) return@AndroidView
                map.tag = scene
                map.setTileSource(if (scene.satellite) SatelliteTiles else LightTiles)
                map.maxZoomLevel = if (scene.satellite) 19.0 else 17.0
                map.overlays.clear()
                map.overlays.add(
                    MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint): Boolean = false
                        override fun longPressHelper(p: GeoPoint): Boolean {
                            currentLongPress(p.latitude, p.longitude)
                            return true
                        }
                    })
                )
                map.overlays.add(if (scene.satellite) satelliteLabels else labels)
                scene.heat?.cells?.forEach { cell ->
                    map.overlays.add(
                        Polygon(map).apply {
                            points = Polygon.pointsAsCircle(GeoPoint(cell.latitude, cell.longitude), scene.heat.cellRadiusMeters)
                            fillPaint.color = HydroColors.heat(cell.severity).copy(alpha = 0.38f).toArgb()
                            outlinePaint.color = AndroidColor.TRANSPARENT
                            outlinePaint.strokeWidth = 0f
                        }
                    )
                }
                if (scene.route.size >= 2) {
                    // A white outline first so the route reads on any map, then segments coloured by heat.
                    map.overlays.add(
                        Polyline(map).apply {
                            setPoints(scene.route.map { GeoPoint(it.latitude, it.longitude) })
                            outlinePaint.color = AndroidColor.WHITE
                            outlinePaint.strokeWidth = 8f * density
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            outlinePaint.strokeJoin = Paint.Join.ROUND
                        }
                    )
                    scene.route.zipWithNext().forEach { (from, to) ->
                        map.overlays.add(
                            Polyline(map).apply {
                                setPoints(listOf(GeoPoint(from.latitude, from.longitude), GeoPoint(to.latitude, to.longitude)))
                                val color = if (from.severity <= 0) HydroColors.Blue else HydroColors.heat(from.severity)
                                outlinePaint.color = color.toArgb()
                                outlinePaint.strokeWidth = 5f * density
                                outlinePaint.strokeCap = Paint.Cap.ROUND
                            }
                        )
                    }
                }
                scene.pins.forEach { pin ->
                    map.overlays.add(
                        dotMarker(map, GeoPoint(pin.latitude, pin.longitude), HydroColors.Blue.toArgb(), 22, density).apply {
                            setOnMarkerClickListener { _, _ ->
                                currentPinClick(pin)
                                true
                            }
                        }
                    )
                }
                scene.destination?.let {
                    map.overlays.add(dotMarker(map, it, HydroColors.TextPrimary.toArgb(), 26, density))
                }
                scene.target?.let {
                    map.overlays.add(dotMarker(map, it, HydroColors.Blue.toArgb(), 18, density))
                }
                map.invalidate()
            }
        )
        Text(
            text = if (satellite) ESRI_SATELLITE_CREDIT else MAP_CREDIT,
            style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = HydroColors.TextSecondary),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = bottomInset + 4.dp)
                .background(HydroColors.SnowWhite.copy(alpha = 0.8f))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

private fun dotMarker(map: MapView, at: GeoPoint, color: Int, sizeDp: Int, density: Float): Marker =
    Marker(map).apply {
        position = at
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        setInfoWindow(null)
        icon = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((3 * density).toInt(), AndroidColor.WHITE)
            setSize((sizeDp * density).toInt(), (sizeDp * density).toInt())
        }
    }

fun MapView.flyTo(lat: Double, lon: Double, zoom: Double, durationMs: Long = 700L) {
    controller.animateTo(GeoPoint(lat, lon), zoom, durationMs)
}

/** Frames every point on screen, with [borderPx] of breathing room. */
fun MapView.fitPoints(points: List<GeoPoint>, borderPx: Int) {
    if (points.size < 2) return
    post { zoomToBoundingBox(BoundingBox.fromGeoPoints(points), true, borderPx) }
}

// ----------------------------------------------------------------------------
// Is the Google key actually usable? Decides which map to draw.
// ----------------------------------------------------------------------------

private var cachedGoogleUsable: Boolean? = null

/**
 * `null` while checking, then true when the Google key answers a real request, false otherwise
 * (no key, billing off, API not enabled). Only definite answers are remembered for the session, so
 * being offline at launch does not lock the app into the free map.
 */
@Composable
fun rememberGoogleMapsUsable(apiKey: String): Boolean? {
    if (apiKey.isBlank()) return false
    val result by produceState(cachedGoogleUsable, apiKey) {
        if (value == null) {
            val answer = withContext(Dispatchers.IO) { probeGoogleKey(apiKey) }
            if (answer != null) cachedGoogleUsable = answer
            value = answer ?: false
        }
    }
    return result
}

private fun probeGoogleKey(apiKey: String): Boolean? = try {
    val url = URL("https://maps.googleapis.com/maps/api/geocode/json?address=Lagos&key=$apiKey")
    val conn = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 6000
        readTimeout = 6000
    }
    try {
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        Regex("\"status\"\\s*:\\s*\"(\\w+)\"").find(body)?.groupValues?.get(1).let { it == "OK" || it == "ZERO_RESULTS" }
    } finally {
        conn.disconnect()
    }
} catch (_: Exception) {
    null
}
