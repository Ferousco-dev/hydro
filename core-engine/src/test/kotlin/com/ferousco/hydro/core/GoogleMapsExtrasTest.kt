package com.ferousco.hydro.core

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.integration.GoogleAreaInsightsProvider
import com.ferousco.hydro.core.integration.GoogleElevationProvider
import com.ferousco.hydro.core.integration.GoogleIsochroneProvider
import com.ferousco.hydro.core.integration.GoogleMapsConfig
import com.ferousco.hydro.core.integration.GooglePlacesProvider
import com.ferousco.hydro.core.integration.GoogleTimeZoneProvider
import com.ferousco.hydro.core.integration.InsightGroup
import com.ferousco.hydro.core.integration.IsochroneMode
import com.ferousco.hydro.core.integration.ReliefKind
import com.ferousco.hydro.core.integration.UnavailableIsochroneProvider
import com.ferousco.hydro.core.json.SafeJsonCodec
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.tools.FindReliefWithinReachTool
import com.ferousco.hydro.core.tools.GetAreaInsightsTool
import com.ferousco.hydro.core.tools.GetElevationProfileTool
import com.ferousco.hydro.core.tools.GetTimeZoneTool
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoogleMapsExtrasTest {
    private val cfg = GoogleMapsConfig("test-key")
    private val origin = GeoLocation.validated(7.3775, 3.9470).getOrNull()!!

    // A square roughly 1 km on a side around the origin (lon, lat order as in GeoJSON).
    private val squareIsochrone = """{"isochrone":{"geoJson":{"type":"MultiPolygon","coordinates":[[[
        [3.9420,7.3730],[3.9520,7.3730],[3.9520,7.3820],[3.9420,7.3820],[3.9420,7.3730]]]]}}}"""

    @Test
    fun `isochrone parses geometry, tests containment and estimates area`() = runBlocking {
        val t = FakeHttpTransport().apply { enqueuePostSuccess(body = squareIsochrone) }
        val iso = (GoogleIsochroneProvider(cfg, t).reachable(origin, IsochroneMode.WALK, 10) as EngineResult.Success).value
        assertTrue(iso.contains(origin))
        assertTrue(!iso.contains(GeoLocation.validated(7.40, 3.90).getOrNull()!!))
        assertTrue(iso.approximateAreaKm2() in 0.7..1.3, "area was ${iso.approximateAreaKm2()}")
        val sent = t.recordedPostRequests.single()
        assertTrue(sent.body.contains("\"600s\"") && sent.body.contains("WALK") && sent.body.contains("FROM"))
        assertEquals("test-key", sent.headers["X-Goog-Api-Key"])
    }

    @Test
    fun `reachable relief keeps only places inside the travel-time area`() = runBlocking {
        val t = FakeHttpTransport().apply {
            enqueuePostSuccess(body = squareIsochrone) // isochrone
            enqueuePostSuccess(
                body = """{"places":[
                  {"displayName":{"text":"Inside Park"},"location":{"latitude":7.378,"longitude":3.948}},
                  {"displayName":{"text":"Across The River"},"location":{"latitude":7.395,"longitude":3.947}}]}"""
            )
        }
        val tool = FindReliefWithinReachTool(GoogleIsochroneProvider(cfg, t), GooglePlacesProvider(cfg, t))
        val params = tool.parseAndValidateArguments(
            (SafeJsonCodec.parseObject("""{"latitude":7.3775,"longitude":3.9470,"kind":"SHADE_AND_PARKS","minutes":10}""") as EngineResult.Success).value
        ).getOrNull()!!
        val result = (tool.executeTyped(params) as EngineResult.Success).value
        assertEquals(listOf("Inside Park"), result.reachable.map { it.name })
        assertEquals(2, result.consideredCount)
        assertNotNull(tool.encodeResult(result).getString("note"))
        Unit
    }

    @Test
    fun `reachable relief rejects bad arguments and reports a missing key`() = runBlocking {
        val tool = FindReliefWithinReachTool(UnavailableIsochroneProvider, GooglePlacesProvider(cfg, FakeHttpTransport()))
        val bad = tool.parseAndValidateArguments(
            (SafeJsonCodec.parseObject("""{"latitude":1,"longitude":1,"kind":"NOPE"}""") as EngineResult.Success).value
        )
        assertTrue(bad is EngineResult.Failure)
        val params = tool.parseAndValidateArguments(
            (SafeJsonCodec.parseObject("""{"latitude":1,"longitude":1,"kind":"MEDICAL"}""") as EngineResult.Success).value
        ).getOrNull()!!
        val r = tool.executeTyped(params)
        assertTrue(r is EngineResult.Failure && r.error is EngineError.UnsupportedCalculation)
    }

    @Test
    fun `area insights parse string counts and treat a missing count as zero`() = runBlocking {
        val t = FakeHttpTransport().apply {
            enqueuePostSuccess(body = """{"count":"12"}""")
            enqueuePostSuccess(body = """{}""")
        }
        val r = (GoogleAreaInsightsProvider(cfg, t)
            .insights(origin, 1000, linkedSetOf(InsightGroup.GREEN_SPACE, InsightGroup.MEDICAL)) as EngineResult.Success).value
        assertEquals(12L, r.counts[InsightGroup.GREEN_SPACE])
        assertEquals(0L, r.counts[InsightGroup.MEDICAL])
        val tool = GetAreaInsightsTool(GoogleAreaInsightsProvider(cfg, FakeHttpTransport()))
        val json = tool.encodeResult(r)
        assertNotNull(json.getArray("groups"))
        Unit
    }

    @Test
    fun `area insights stops at the first group when the API is not enabled`() = runBlocking {
        val t = FakeHttpTransport().apply { enqueuePostSuccess(statusCode = 403, body = """{"error":{"message":"disabled"}}""") }
        val r = GoogleAreaInsightsProvider(cfg, t).insights(origin, 1000, InsightGroup.entries.toSet())
        assertTrue(r is EngineResult.Failure)
        assertEquals(1, t.recordedPostRequests.size, "must not repeat a failing call for every group")
    }

    @Test
    fun `elevation profile computes ascent and descent`() = runBlocking {
        val t = FakeHttpTransport().apply {
            enqueueSuccess(body = """{"status":"OK","results":[
              {"elevation":100.0},{"elevation":110.0},{"elevation":105.0},{"elevation":120.0}]}""")
        }
        val p = (GoogleElevationProvider(cfg, t).profile(listOf(origin, origin), 4) as EngineResult.Success).value
        assertEquals(100.0, p.minMeters); assertEquals(120.0, p.maxMeters)
        assertEquals(25.0, p.totalAscentMeters, 1e-9); assertEquals(5.0, p.totalDescentMeters, 1e-9)
        assertTrue(t.recordedRequests.single().uri.toString().contains("key=test-key"))
    }

    @Test
    fun `elevation surfaces provider status`() = runBlocking {
        val t = FakeHttpTransport().apply { enqueueSuccess(body = """{"status":"REQUEST_DENIED"}""") }
        assertTrue(GoogleElevationProvider(cfg, t).profile(listOf(origin, origin), 4) is EngineResult.Failure)
        val tool = GetElevationProfileTool(GoogleElevationProvider(cfg, FakeHttpTransport()))
        val bad = tool.parseAndValidateArguments(
            (SafeJsonCodec.parseObject("""{"origin_latitude":1,"origin_longitude":1,"destination_latitude":2,"destination_longitude":2,"samples":99}""") as EngineResult.Success).value
        )
        assertTrue(bad is EngineResult.Failure)
    }

    @Test
    fun `time zone resolves an IANA zone with offset`() = runBlocking {
        val t = FakeHttpTransport().apply {
            enqueueSuccess(body = """{"status":"OK","timeZoneId":"Africa/Lagos","timeZoneName":"West Africa Standard Time","rawOffset":3600,"dstOffset":0}""")
        }
        val z = (GoogleTimeZoneProvider(cfg, t).zoneFor(origin, Instant.parse("2026-10-26T12:00:00Z")) as EngineResult.Success).value
        assertEquals("Africa/Lagos", z.zone.id)
        assertEquals(3600, z.utcOffsetSeconds)
        val tool = GetTimeZoneTool(GoogleTimeZoneProvider(cfg, FakeHttpTransport().apply {
            enqueueSuccess(body = """{"status":"OK","timeZoneId":"Not/AZone"}""")
        }))
        val params = tool.parseAndValidateArguments(
            (SafeJsonCodec.parseObject("""{"latitude":1,"longitude":1}""") as EngineResult.Success).value
        ).getOrNull()!!
        assertTrue(tool.executeTyped(params) is EngineResult.Failure, "an unknown zone id must fail, not default")
    }
}
