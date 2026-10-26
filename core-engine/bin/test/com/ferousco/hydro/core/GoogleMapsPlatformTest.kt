package com.ferousco.hydro.core

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.integration.GoogleAirQualityProvider
import com.ferousco.hydro.core.integration.GoogleGeocoder
import com.ferousco.hydro.core.integration.GoogleMapsConfig
import com.ferousco.hydro.core.integration.GooglePlacesProvider
import com.ferousco.hydro.core.integration.GoogleRoutesProvider
import com.ferousco.hydro.core.integration.HeatMapMetric
import com.ferousco.hydro.core.integration.HeatMapRequest
import com.ferousco.hydro.core.integration.OpenMeteoGridHeatMapProvider
import com.ferousco.hydro.core.integration.ReliefKind
import com.ferousco.hydro.core.integration.RouteRequest
import com.ferousco.hydro.core.integration.TravelMode
import com.ferousco.hydro.core.model.GeoLocation
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoogleMapsPlatformTest {
    private val cfg = GoogleMapsConfig("test-key")
    private val a = GeoLocation.validated(7.3775, 3.9470).getOrNull()!!
    private val b = GeoLocation.validated(7.40, 3.90).getOrNull()!!

    @Test
    fun `polyline decoder matches Google's documented example`() {
        val pts = GoogleRoutesProvider.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", null)
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].latitude, 1e-5); assertEquals(-120.2, pts[0].longitude, 1e-5)
        assertEquals(43.252, pts[2].latitude, 1e-5); assertEquals(-126.453, pts[2].longitude, 1e-5)
    }

    @Test
    fun `routes provider parses a route and keeps the key out of the URL`() = runBlocking {
        val t = FakeHttpTransport()
        t.enqueuePostSuccess(body = """{"routes":[{"distanceMeters":5200,"duration":"3900s","polyline":{"encodedPolyline":"_p~iF~ps|U_ulLnnqC_mqNvxq`@"}}]}""")
        val plan = (GoogleRoutesProvider(cfg, t).planRoute(RouteRequest(a, b, TravelMode.WALKING, Instant.now())) as EngineResult.Success).value
        assertEquals(5.2, plan.distanceKm, 1e-9)
        assertEquals(3900, plan.duration.seconds)
        assertEquals("GOOGLE_ROUTES_WALK", plan.method)
        val sent = t.recordedPostRequests.single()
        assertFalse(sent.uri.toString().contains("test-key"))
        assertEquals("test-key", sent.headers["X-Goog-Api-Key"])
        assertTrue(sent.body.contains("\"WALK\""))
    }

    @Test
    fun `routes provider reports no route and missing key explicitly`() = runBlocking {
        val t = FakeHttpTransport().apply { enqueuePostSuccess(body = """{}""") }
        val none = GoogleRoutesProvider(cfg, t).planRoute(RouteRequest(a, b, TravelMode.DRIVING, Instant.now()))
        assertTrue(none is EngineResult.Failure && none.error is EngineError.InvalidResponse)
        val nokey = GoogleRoutesProvider(GoogleMapsConfig(""), FakeHttpTransport()).planRoute(RouteRequest(a, b, TravelMode.DRIVING, Instant.now()))
        assertTrue(nokey is EngineResult.Failure && nokey.error is EngineError.UnsupportedCalculation)
    }

    @Test
    fun `http 403 mentions enabling the API`() = runBlocking {
        val t = FakeHttpTransport().apply { enqueuePostSuccess(statusCode = 403, body = """{"error":{"message":"API not enabled"}}""") }
        val r = GoogleRoutesProvider(cfg, t).planRoute(RouteRequest(a, b, TravelMode.WALKING, Instant.now()))
        assertTrue(r is EngineResult.Failure && r.error.message.contains("enabled"))
    }

    @Test
    fun `google air quality prefers US EPA index and surfaces health advice`() = runBlocking {
        val t = FakeHttpTransport().apply {
            enqueuePostSuccess(body = """{"dateTime":"2026-10-26T12:00:00Z","indexes":[
              {"code":"uaqi","aqi":62,"dominantPollutant":"pm25"},
              {"code":"usa_epa","aqi":118,"dominantPollutant":"pm25"}],
              "pollutants":[{"code":"pm25","concentration":{"value":41.5,"units":"MICROGRAMS_PER_CUBIC_METER"}}],
              "healthRecommendations":{"generalPopulation":"Reduce outdoor exertion.","children":""}}""")
        }
        val r = (GoogleAirQualityProvider(cfg, t).current(a) as EngineResult.Success).value
        assertEquals(118.0, r.usAqi); assertEquals(62.0, r.universalAqi)
        assertEquals("pm25", r.dominantPollutant)
        assertEquals(setOf("generalPopulation"), r.healthRecommendations.keys)
    }

    @Test
    fun `geocoder maps results and surfaces provider status errors`() = runBlocking {
        val ok = FakeHttpTransport().apply {
            enqueueSuccess(body = """{"status":"OK","results":[{"formatted_address":"Lekki, Lagos, Nigeria",
              "geometry":{"location":{"lat":6.44,"lng":3.47}},
              "address_components":[{"long_name":"Nigeria","types":["country","political"]}]}]}""")
        }
        val places = (GoogleGeocoder(cfg, ok).geocode("Lekki", 3) as EngineResult.Success).value
        assertEquals("Nigeria", places.single().country)
        assertEquals(6.44, places.single().location.latitude)
        assertTrue(ok.recordedRequests.single().uri.toString().contains("key=test-key"))

        val denied = FakeHttpTransport().apply { enqueueSuccess(body = """{"status":"REQUEST_DENIED","error_message":"bad key"}""") }
        assertTrue(GoogleGeocoder(cfg, denied).geocode("x", 1) is EngineResult.Failure)
    }

    @Test
    fun `places sorts by distance and skips entries without a location`() = runBlocking {
        val t = FakeHttpTransport().apply {
            enqueuePostSuccess(body = """{"places":[
              {"displayName":{"text":"Far Park"},"location":{"latitude":7.45,"longitude":3.95}},
              {"displayName":{"text":"Near Park"},"location":{"latitude":7.378,"longitude":3.947},"currentOpeningHours":{"openNow":true}},
              {"displayName":{"text":"No Location"}}]}""")
        }
        val r = (GooglePlacesProvider(cfg, t).nearby(a, ReliefKind.SHADE_AND_PARKS, 2000, 5) as EngineResult.Success).value
        assertEquals(listOf("Near Park", "Far Park"), r.places.map { it.name })
        assertEquals(true, r.places.first().openNow)
    }

    @Test
    fun `heat map grid computes heat index locally and degrades cells with missing humidity`() = runBlocking {
        val cell = """{"current":{"temperature_2m":34.0,"relative_humidity_2m":70.0,"uv_index":8.0}}"""
        val noRh = """{"current":{"temperature_2m":34.0,"uv_index":8.0}}"""
        val t = FakeHttpTransport().apply {
            enqueueSuccess(body = "[" + List(8) { cell }.joinToString(",") + ",$noRh]")
        }
        val layer = (OpenMeteoGridHeatMapProvider(t).heatMap(
            HeatMapRequest(a, 10.0, 10.0, Instant.now(), HeatMapMetric.HEAT_INDEX_CELSIUS)
        ) as EngineResult.Success).value
        assertTrue(layer.cells.size in 1..9)
        assertTrue(layer.cells.all { it.value > 34.0 }, "34C at 70% RH must feel hotter than air temperature")
        assertTrue(layer.limitations.any { it.contains("Coarse") })
    }
}
