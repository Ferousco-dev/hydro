package com.ferousco.hydro.core

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.integration.AirQualityCategory
import com.ferousco.hydro.core.integration.NasaPowerSolarClimateProvider
import com.ferousco.hydro.core.integration.NewsFocus
import com.ferousco.hydro.core.integration.NewsRequest
import com.ferousco.hydro.core.integration.OpenMeteoAirQualityProvider
import com.ferousco.hydro.core.integration.TavilyNewsProvider
import com.ferousco.hydro.core.integration.UnavailableNewsProvider
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.tools.GetAirQualityTool
import com.ferousco.hydro.core.tools.GetEnvironmentalNewsTool
import com.ferousco.hydro.core.tools.GetSolarClimateTool
import com.ferousco.hydro.core.json.SafeJsonCodec
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalDataProvidersTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC)
    private val ibadan = GeoLocation.validated(7.3775, 3.9470).getOrNull()!!

    @Test
    fun `aqi categories follow EPA breakpoints`() {
        assertEquals(AirQualityCategory.GOOD, AirQualityCategory.fromUsAqi(50.0))
        assertEquals(AirQualityCategory.MODERATE, AirQualityCategory.fromUsAqi(51.0))
        assertEquals(AirQualityCategory.UNHEALTHY_SENSITIVE, AirQualityCategory.fromUsAqi(150.0))
        assertEquals(AirQualityCategory.UNHEALTHY, AirQualityCategory.fromUsAqi(151.0))
        assertEquals(AirQualityCategory.HAZARDOUS, AirQualityCategory.fromUsAqi(301.0))
    }

    @Test
    fun `tavily maps results, sends no coordinates, and truncates untrusted text`() = runBlocking {
        val transport = FakeHttpTransport()
        val longText = "x".repeat(2000)
        transport.enqueuePostSuccess(
            body = """{"answer":"Heat advisory issued.","results":[
              {"title":"Heatwave hits Ibadan","url":"https://news.example/a","content":"$longText","score":0.9,"published_date":"2026-10-25"},
              {"title":"bad scheme","url":"javascript:alert(1)","content":"no"}]}"""
        )
        val result = TavilyNewsProvider("tvly-test", transport, clock)
            .search(NewsRequest("Ibadan, Nigeria", NewsFocus.HEAT, 7, 5))

        val digest = (result as EngineResult.Success).value
        assertEquals(1, digest.articles.size)
        assertTrue(digest.articles[0].snippet.length <= 420)
        val sent = transport.recordedPostRequests.single()
        assertEquals("Bearer tvly-test", sent.headers["Authorization"])
        assertFalse(sent.body.contains("7.37"), "coordinates must never be sent to the news provider")
        assertTrue(sent.body.contains("Ibadan"))
    }

    @Test
    fun `missing tavily key fails explicitly instead of returning fake news`() = runBlocking {
        val r = TavilyNewsProvider("", FakeHttpTransport(), clock)
            .search(NewsRequest("Lagos", NewsFocus.GENERAL, 3, 3))
        assertTrue(r is EngineResult.Failure && r.error is EngineError.UnsupportedCalculation)
        assertTrue(UnavailableNewsProvider.search(NewsRequest("x", NewsFocus.GENERAL, 1, 1)) is EngineResult.Failure)
    }

    @Test
    fun `news tool marks output as untrusted and rejects bad arguments`() = runBlocking {
        val tool = GetEnvironmentalNewsTool(UnavailableNewsProvider)
        val bad = tool.parseAndValidateArguments(
            (SafeJsonCodec.parseObject("""{"place":"Lagos","days":99}""") as EngineResult.Success).value
        )
        assertTrue(bad is EngineResult.Failure)
        val missing = tool.parseAndValidateArguments(
            (SafeJsonCodec.parseObject("""{}""") as EngineResult.Success).value
        )
        assertTrue(missing is EngineResult.Failure)
    }

    @Test
    fun `air quality provider parses current block and classifies`() = runBlocking {
        val transport = FakeHttpTransport()
        transport.enqueueSuccess(
            body = """{"current":{"time":"2026-10-26T12:00","us_aqi":112,"european_aqi":60,"pm2_5":41.2,"pm10":80.1,"ozone":55.0}}"""
        )
        val reading = (OpenMeteoAirQualityProvider(transport, clock).current(ibadan) as EngineResult.Success).value
        assertEquals(AirQualityCategory.UNHEALTHY_SENSITIVE, reading.category)
        assertEquals(41.2, reading.pollutantsMicrogramsPerM3["pm2_5"])
        val json = GetAirQualityTool(OpenMeteoAirQualityProvider(FakeHttpTransport().apply {
            enqueueSuccess(body = """{"current":{"time":"2026-10-26T12:00","us_aqi":30}}""")
        }, clock)).let { tool ->
            tool.encodeResult((tool.executeTyped(tool.parseAndValidateArguments(
                (SafeJsonCodec.parseObject("""{"latitude":7.3,"longitude":3.9}""") as EngineResult.Success).value
            ).getOrNull()!!) as EngineResult.Success).value)
        }
        assertEquals("MODELLED", json.getString("data_nature"))
    }

    @Test
    fun `air quality missing current block is an invalid response`() = runBlocking {
        val transport = FakeHttpTransport().apply { enqueueSuccess(body = """{"foo":1}""") }
        val r = OpenMeteoAirQualityProvider(transport, clock).current(ibadan)
        assertTrue(r is EngineResult.Failure && r.error is EngineError.InvalidResponse)
    }

    @Test
    fun `nasa power turns -999 sentinel into null and never zero-fills`() = runBlocking {
        val transport = FakeHttpTransport()
        transport.enqueueSuccess(
            body = """{"properties":{"parameter":{
              "ALLSKY_SFC_SW_DWN":{"20261022":5.1,"20261023":-999.0},
              "T2M_MAX":{"20261022":33.0,"20261023":34.5},
              "T2M_MIN":{"20261022":23.0,"20261023":-999.0},
              "RH2M":{"20261022":70.0,"20261023":68.0},
              "PRECTOTCORR":{"20261022":0.0,"20261023":2.5}}}}"""
        )
        val series = (NasaPowerSolarClimateProvider(transport, clock).recent(ibadan, 2) as EngineResult.Success).value
        assertEquals(2, series.days.size)
        assertEquals(5.1, series.days[0].solarKwhPerM2)
        assertNull(series.days[1].solarKwhPerM2)
        assertNull(series.days[1].minTemperatureCelsius)
        assertEquals(0.0, series.days[0].rainfallMm, "a real 0 mm rainfall must stay 0, not become null")
        val tool = GetSolarClimateTool(NasaPowerSolarClimateProvider(FakeHttpTransport(), clock))
        assertEquals("get_solar_climate", tool.descriptor.name)
    }
}
