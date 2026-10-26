package com.ferousco.hydro.core

import com.ferousco.hydro.core.assessment.HeatAssessmentEngine
import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.history.EnvironmentalHistoryRepository
import com.ferousco.hydro.core.history.InMemoryEnvironmentalHistoryStore
import com.ferousco.hydro.core.history.JsonFileEnvironmentalHistoryStore
import com.ferousco.hydro.core.model.ComparisonPairingKind
import com.ferousco.hydro.core.model.EnvironmentalSnapshotVariables
import com.ferousco.hydro.core.model.FieldAvailability
import com.ferousco.hydro.core.model.HistoricalComparisonOutcome
import com.ferousco.hydro.core.model.HistoricalComparisonPolicy
import com.ferousco.hydro.core.model.HistoricalRecordDataType
import com.ferousco.hydro.core.model.IncompatibleComparisonReason
import com.ferousco.hydro.core.model.MeasuredQuantity
import com.ferousco.hydro.core.model.PersistenceWriteResult
import com.ferousco.hydro.core.model.PercentageUnit
import com.ferousco.hydro.core.model.RetentionPolicy
import com.ferousco.hydro.core.model.SpeedUnit
import com.ferousco.hydro.core.model.StoredEnvironmentalRecord
import com.ferousco.hydro.core.model.TemperatureUnit
import com.ferousco.hydro.core.model.ValueNature
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalHistoryAndComparisonTest {

    private val now = Instant.parse("2026-10-26T14:00:00Z")
    private val clock = MutableClock(now)
    private val assessmentEngine = HeatAssessmentEngine(clock = clock)

    @Test
    fun `JsonFileEnvironmentalHistoryStore persists and reloads records, assessments, units, and detects corruption`() = runBlocking {
        val tempDir = Files.createTempDirectory("hydro_history_test")
        val dbFile = tempDir.resolve("history.json")
        try {
            val store1 = JsonFileEnvironmentalHistoryStore(dbFile)
            val repo1 = EnvironmentalHistoryRepository(store1, clock = clock)

            val obsYesterday = TestFixtures.buildObservation(
                observedAt = Instant.parse("2026-10-25T14:00:00Z"),
                retrievedAt = Instant.parse("2026-10-25T14:05:00Z"),
                airTempC = 31.0,
                rhPercent = 65.0,
                uvIndex = null
            )
            val savedObs = (repo1.recordObservation(obsYesterday).getOrNull() as PersistenceWriteResult.Stored).record
            val assessment = assessmentEngine.assessObservation(obsYesterday, allowStaleInput = true)
            repo1.recordHeatAssessment(assessment, linkedEnvironmentalRecordId = savedObs.id).getOrNull()!!

            val forecastSeries = TestFixtures.buildHourlyForecast(
                startHour = now,
                hourlySpecs = listOf(Triple(34.5, 68.0, 8.0))
            )
            repo1.recordIssuedForecastPoint(forecastSeries.hourly.single()).getOrNull()!!

            // Re-open with a brand-new store instance pointing to the same file
            val store2 = JsonFileEnvironmentalHistoryStore(dbFile)
            val repo2 = EnvironmentalHistoryRepository(store2, clock = clock)

            val loadedRecords = repo2.queryRecentRecords(location = TestFixtures.IBADAN).getOrNull()!!
            assertEquals(2, loadedRecords.size)
            val loadedForecast = loadedRecords.first { it.dataType == HistoricalRecordDataType.ISSUED_FORECAST }
            val loadedObs = loadedRecords.first { it.dataType == HistoricalRecordDataType.OBSERVATION }
            assertEquals(null, loadedForecast.observationKind)
            assertTrue(!loadedObs.variables.uvIndex.isAvailable, "Unavailable UV must survive disk persistence")

            val linkedAssessment = store2.getAssessmentForEnvironmentalRecord(savedObs.id).getOrNull()
            assertNotNull(linkedAssessment)
            assertEquals("NOAA_NWS_ROTHFUSZ_SR_90_23", linkedAssessment.methodId)

            // Corrupt the file and verify PersistenceFailure is returned rather than silent empty list
            Files.writeString(dbFile, "{ corrupted json !!!")
            val corruptedRead = repo2.queryRecentRecords()
            assertTrue(corruptedRead.errorOrNull() is EngineError.PersistenceFailure)
        } finally {
            Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `historical comparison enforces location proximity, diurnal alignment, unit normalization, and forecast vs observation distinction`() = runBlocking {
        val repo = EnvironmentalHistoryRepository(InMemoryEnvironmentalHistoryStore(), clock = clock)

        // Baseline: Yesterday 14:00Z in Fahrenheit (86°F = 30.0°C) & km/h (18 km/h = 5.0 m/s)
        val tYesterday = Instant.parse("2026-10-25T14:00:00Z")
        val baseVarsFahrenheit = EnvironmentalSnapshotVariables(
            airTemperature = FieldAvailability.Available(
                MeasuredQuantity(86.0, TemperatureUnit.FAHRENHEIT, tYesterday, TestFixtures.IBADAN, TestFixtures.TEST_OBSERVATION_SOURCE, ValueNature.OBSERVED)
            ),
            relativeHumidity = FieldAvailability.Available(
                MeasuredQuantity(60.0, PercentageUnit.PERCENT, tYesterday, TestFixtures.IBADAN, TestFixtures.TEST_OBSERVATION_SOURCE, ValueNature.OBSERVED)
            ),
            windSpeed = FieldAvailability.Available(
                MeasuredQuantity(18.0, SpeedUnit.KILOMETERS_PER_HOUR, tYesterday, TestFixtures.IBADAN, TestFixtures.TEST_OBSERVATION_SOURCE, ValueNature.OBSERVED)
            )
        )
        val baselineObs = StoredEnvironmentalRecord(
            id = "obs-yesterday",
            dataType = HistoricalRecordDataType.OBSERVATION,
            observationKind = com.ferousco.hydro.core.model.ObservationKind.IN_SITU_OBSERVATION,
            location = TestFixtures.IBADAN,
            source = TestFixtures.TEST_OBSERVATION_SOURCE,
            validAt = tYesterday,
            retrievedAt = tYesterday,
            timeZoneId = "Africa/Lagos",
            variables = baseVarsFahrenheit
        )

        // Target: Today 14:30Z in Celsius (33.5°C) & m/s (3.0 m/s)
        val tToday = Instant.parse("2026-10-26T14:30:00Z")
        val targetObs = StoredEnvironmentalRecord(
            id = "obs-today",
            dataType = HistoricalRecordDataType.OBSERVATION,
            observationKind = com.ferousco.hydro.core.model.ObservationKind.IN_SITU_OBSERVATION,
            location = TestFixtures.IBADAN,
            source = TestFixtures.TEST_OBSERVATION_SOURCE,
            validAt = tToday,
            retrievedAt = tToday,
            timeZoneId = "Africa/Lagos",
            variables = TestFixtures.buildVariables(
                validAt = tToday,
                airTempC = 33.5,
                rhPercent = 70.0,
                windMs = 3.0,
                uvIndex = 8.0
            )
        )

        val compatible = repo.compareRecords(baselineObs, targetObs)
        assertTrue(compatible is HistoricalComparisonOutcome.Compatible)
        assertEquals(ComparisonPairingKind.OBSERVATION_TO_OBSERVATION, compatible.pairingKind)
        val tempDelta = compatible.variableDeltas.first { it.variableName == "air_temperature" }
        assertEquals(3.5, tempDelta.delta, 1e-6) // 33.5°C - 30.0°C
        val windDelta = compatible.variableDeltas.first { it.variableName == "wind_speed" }
        assertEquals(-2.0, windDelta.delta, 1e-6) // 3.0 m/s - 5.0 m/s
        // uv_index was unavailable in baseline -> must be in uncomparableVariables, not delta against 0!
        assertTrue("uv_index" in compatible.uncomparableVariables)

        // Past-issued forecast MUST be rejected when requireObservedBaseline = true
        val pastIssuedForecast = baselineObs.copy(
            id = "fcst-yesterday",
            dataType = HistoricalRecordDataType.ISSUED_FORECAST,
            observationKind = null,
            retrievedAt = tYesterday.minus(Duration.ofHours(12))
        )
        val rejectedForecastBaseline = repo.compareRecords(
            baseline = pastIssuedForecast,
            target = targetObs,
            policy = HistoricalComparisonPolicy(requireObservedBaseline = true)
        )
        assertTrue(rejectedForecastBaseline is HistoricalComparisonOutcome.Incompatible)
        assertEquals(IncompatibleComparisonReason.BASELINE_IS_FORECAST_NOT_OBSERVATION, rejectedForecastBaseline.reason)

        // When allowed, forecast-vs-observation is explicitly labeled OBSERVATION_TO_ISSUED_FORECAST
        val allowedMixed = repo.compareRecords(
            baseline = pastIssuedForecast,
            target = targetObs,
            policy = HistoricalComparisonPolicy(requireObservedBaseline = false)
        ) as HistoricalComparisonOutcome.Compatible
        assertEquals(ComparisonPairingKind.OBSERVATION_TO_ISSUED_FORECAST, allowedMixed.pairingKind)
        assertTrue(allowedMixed.caveats.any { it.contains("forecast", ignoreCase = true) })

        // Distant location (Lagos ~114 km from Ibadan) -> rejected!
        val lagosRecord = targetObs.copy(id = "obs-lagos", location = TestFixtures.LAGOS)
        val distantRes = repo.compareRecords(baselineObs, lagosRecord)
        assertTrue(distantRes is HistoricalComparisonOutcome.Incompatible)
        assertEquals(IncompatibleComparisonReason.LOCATION_DISTANCE_EXCEEDED, distantRes.reason)

        // Diurnal mismatch: Yesterday 02:00Z vs Today 14:30Z -> rejected!
        val nightBaseline = baselineObs.copy(
            id = "obs-night",
            validAt = Instant.parse("2026-10-25T02:00:00Z")
        )
        val diurnalRes = repo.compareRecords(nightBaseline, targetObs)
        assertTrue(diurnalRes is HistoricalComparisonOutcome.Incompatible)
        assertEquals(IncompatibleComparisonReason.DIURNAL_TIME_OF_DAY_MISMATCH, diurnalRes.reason)

        // Midnight wraparound: 22:45Z (23:45 WAT) vs next night 23:15Z (00:15 WAT) -> 30 min apart -> compatible!
        val lateNight1 = baselineObs.copy(id = "ln1", validAt = Instant.parse("2026-10-24T22:45:00Z"))
        val lateNight2 = targetObs.copy(id = "ln2", validAt = Instant.parse("2026-10-25T23:15:00Z"))
        assertTrue(repo.compareRecords(lateNight1, lateNight2) is HistoricalComparisonOutcome.Compatible)
    }

    @Test
    fun `retention pruning, opt-out policy, and deletion controls work accurately`() = runBlocking {
        val store = InMemoryEnvironmentalHistoryStore()
        val repo = EnvironmentalHistoryRepository(
            store = store,
            initialRetentionPolicy = RetentionPolicy(
                persistenceEnabled = true,
                maxObservationRetention = Duration.ofDays(30),
                maxIssuedForecastRetention = Duration.ofDays(3),
                maxEnvironmentalRecords = 10
            ),
            clock = clock
        )

        // Old observation (45 days old -> expired)
        val oldObs = TestFixtures.buildObservation(
            observedAt = now.minus(Duration.ofDays(45)),
            retrievedAt = now.minus(Duration.ofDays(45))
        )
        // Old issued forecast (5 days old -> expired beyond 3-day forecast retention)
        val oldFcst = TestFixtures.buildHourlyForecast(
            startHour = now.minus(Duration.ofDays(5)),
            hourlySpecs = listOf(Triple(30.0, 60.0, 5.0)),
            retrievedAt = now.minus(Duration.ofDays(5))
        ).hourly.single()
        // Recent observation in Ibadan (1 hour old -> kept)
        val recentIbadan = TestFixtures.buildObservation(
            observedAt = now.minus(Duration.ofHours(1)),
            retrievedAt = now.minus(Duration.ofHours(1))
        )
        // Recent observation in Lagos (1 hour old -> kept until location delete)
        val recentLagos = TestFixtures.buildObservation(
            location = TestFixtures.LAGOS,
            observedAt = now.minus(Duration.ofHours(1)),
            retrievedAt = now.minus(Duration.ofHours(1))
        )

        repo.recordObservation(oldObs, recordId = "old-obs")
        repo.recordIssuedForecastPoint(oldFcst, recordId = "old-fcst")
        repo.recordObservation(recentIbadan, recordId = "recent-ibadan")
        repo.recordObservation(recentLagos, recordId = "recent-lagos")

        val pruneSummary = repo.enforceRetentionPolicy(now).getOrNull()!!
        assertEquals(2, pruneSummary.prunedEnvironmentalRecords)

        val remainingAfterPrune = repo.queryRecentRecords().getOrNull()!!.map { it.id }.toSet()
        assertEquals(setOf("recent-ibadan", "recent-lagos"), remainingAfterPrune)

        // Delete records near Lagos only
        val deletedLagos = repo.deleteRecordsNearLocation(TestFixtures.LAGOS, radiusKm = 15.0).getOrNull()!!
        assertEquals(1, deletedLagos)
        assertEquals(listOf("recent-ibadan"), repo.queryRecentRecords().getOrNull()!!.map { it.id })

        // Opt-out policy skips writes cleanly
        repo.updateRetentionPolicy(RetentionPolicy(persistenceEnabled = false))
        val skipped = repo.recordObservation(recentLagos, recordId = "should-not-store").getOrNull()!!
        assertTrue(skipped is PersistenceWriteResult.SkippedByRetentionPolicy)
        assertEquals(1, repo.queryRecentRecords().getOrNull()!!.size)

        // Delete all history
        repo.deleteAllHistory()
        assertTrue(repo.queryRecentRecords().getOrNull()!!.isEmpty())
    }
}
