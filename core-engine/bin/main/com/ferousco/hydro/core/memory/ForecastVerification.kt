package com.ferousco.hydro.core.memory

import com.ferousco.hydro.core.model.EnvironmentalSnapshotVariables
import com.ferousco.hydro.core.model.FieldAvailability
import com.ferousco.hydro.core.model.HistoricalRecordDataType
import com.ferousco.hydro.core.model.SpeedUnit
import com.ferousco.hydro.core.model.StoredEnvironmentalRecord
import com.ferousco.hydro.core.model.TemperatureUnit
import com.ferousco.hydro.core.model.rawValueOrNull
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt

// ============================================================================
// Models
// ============================================================================

data class ForecastVariableError(
    val variable: String,
    val unit: String,
    val sampleCount: Int,
    val meanForecast: Double,
    val meanObserved: Double,
    /** Signed mean error (`forecast - observed`); positive means the forecast ran hot/high. */
    val meanError: Double,
    val meanAbsoluteError: Double,
    val rootMeanSquaredError: Double,
    /** False when there are too few pairs for the metric to be more than indicative. */
    val sufficientForCalibration: Boolean
)

data class ForecastVerificationPair(
    val forecastRecordId: String,
    val observationRecordId: String,
    val validAt: Instant,
    val leadTimeHours: Double,
    val distanceKm: Double
)

/**
 * Descriptive forecast-versus-observation error for records already stored on this device.
 *
 * This is deliberately *not* a calibration model. It reports how the provider's issued forecasts
 * compared with the observations Hydro later recorded, so the assistant can answer "was the
 * forecast accurate?" with arithmetic rather than an opinion.
 */
data class ForecastVerificationReport(
    val lookbackDays: Int,
    val pairedCount: Int,
    val unpairedForecastCount: Int,
    val unpairedObservationCount: Int,
    val pairingToleranceMinutes: Long,
    val maxDistanceKm: Double,
    val leadTimeHoursMin: Double?,
    val leadTimeHoursMean: Double?,
    val leadTimeHoursMax: Double?,
    val variables: List<ForecastVariableError>,
    val pairs: List<ForecastVerificationPair>,
    val caveats: List<String>
)

// ============================================================================
// Calculator
// ============================================================================

object ForecastVerificationCalculator {

    /** Forecast and observation valid times must be within this window to form a pair. */
    const val PAIRING_TOLERANCE_MINUTES = 60L

    /** Below this many pairs, metrics are labelled indicative rather than calibration-grade. */
    const val MIN_CALIBRATION_SAMPLES = 10

    private data class VariableSpec(
        val name: String,
        val unit: String,
        val extract: (EnvironmentalSnapshotVariables) -> Double?
    )

    private val VARIABLES = listOf(
        VariableSpec("air_temperature", "°C") { variables ->
            variables.airTemperature.canonicalCelsius()
        },
        VariableSpec("relative_humidity", "%") { variables ->
            variables.relativeHumidity.rawValueOrNull()
        },
        VariableSpec("wind_speed", "m/s") { variables ->
            variables.windSpeed.canonicalMetersPerSecond()
        },
        VariableSpec("uv_index", "UVI") { variables ->
            variables.uvIndex.rawValueOrNull()
        },
        VariableSpec("solar_radiation", "W/m²") { variables ->
            variables.solarRadiation.rawValueOrNull()
        }
    )

    fun verify(
        records: List<StoredEnvironmentalRecord>,
        lookbackDays: Int,
        maxDistanceKm: Double,
        maxPairs: Int
    ): ForecastVerificationReport {
        require(lookbackDays > 0) { "lookbackDays must be positive." }
        require(maxDistanceKm > 0.0 && maxDistanceKm.isFinite()) { "maxDistanceKm must be positive." }
        require(maxPairs > 0) { "maxPairs must be positive." }

        val forecasts = records
            .filter { it.dataType == HistoricalRecordDataType.ISSUED_FORECAST }
            .sortedByDescending { it.validAt }
        val observations = records.filter { it.dataType.isObservationCompatible }

        val pairs = mutableListOf<Pair<StoredEnvironmentalRecord, StoredEnvironmentalRecord>>()
        val pairMetadata = mutableListOf<ForecastVerificationPair>()

        for (forecast in forecasts) {
            if (pairs.size >= maxPairs) break
            val best = observations
                .asSequence()
                .filter { !forecast.retrievedAt.isAfter(it.validAt) }
                .map { observation ->
                    val timeDifference = abs(
                        Duration.between(observation.validAt, forecast.validAt).toMinutes()
                    )
                    val distance = observation.location.distanceKilometersTo(forecast.location)
                    Triple(observation, timeDifference, distance)
                }
                .filter { (_, timeDifference, distance) ->
                    timeDifference <= PAIRING_TOLERANCE_MINUTES && distance <= maxDistanceKm
                }
                .minWithOrNull(compareBy({ it.second }, { it.third }))
                ?: continue

            val (observation, timeDifference, distance) = best
            pairs.add(forecast to observation)
            pairMetadata.add(
                ForecastVerificationPair(
                    forecastRecordId = forecast.id,
                    observationRecordId = observation.id,
                    validAt = forecast.validAt,
                    leadTimeHours = leadTimeHours(forecast),
                    distanceKm = distance
                )
            )
        }

        val matchedObservationIds = pairMetadata.map { it.observationRecordId }.toSet()
        val variables = VARIABLES.mapNotNull { spec ->
            val samples = pairs.mapNotNull { (forecast, observation) ->
                val expected = spec.extract(forecast.variables) ?: return@mapNotNull null
                val actual = spec.extract(observation.variables) ?: return@mapNotNull null
                expected to actual
            }
            if (samples.isEmpty()) return@mapNotNull null
            val errors = samples.map { (forecast, observed) -> forecast - observed }
            ForecastVariableError(
                variable = spec.name,
                unit = spec.unit,
                sampleCount = samples.size,
                meanForecast = samples.map { it.first }.average(),
                meanObserved = samples.map { it.second }.average(),
                meanError = errors.average(),
                meanAbsoluteError = errors.map { abs(it) }.average(),
                rootMeanSquaredError = sqrt(errors.map { it * it }.average()),
                sufficientForCalibration = samples.size >= MIN_CALIBRATION_SAMPLES
            )
        }

        val leadTimes = pairMetadata.map { it.leadTimeHours }
        val caveats = buildList {
            add(
                "Only forecasts and observations already stored on this device can be paired; " +
                    "missing hours are not backfilled or estimated."
            )
            add(
                "A pair requires the forecast to have been issued at or before the observation's " +
                    "valid time, valid times within ±${PAIRING_TOLERANCE_MINUTES} minutes, and " +
                    "locations within ${maxDistanceKm}km."
            )
            add(
                "Observations may themselves be model-assimilated current conditions, so the error " +
                    "includes provider analysis error, not only forecast error."
            )
            add(
                "These are descriptive error statistics, not a calibration model. Hydro does not " +
                    "retrain the provider or silently bias-correct its output."
            )
            if (pairMetadata.size < MIN_CALIBRATION_SAMPLES) {
                add(
                    "Fewer than ${MIN_CALIBRATION_SAMPLES} pairs were available; treat these metrics " +
                        "as indicative only."
                )
            }
            if (forecasts.isEmpty()) {
                add("No stored issued forecasts were found in the requested window.")
            }
            if (observations.isEmpty()) {
                add("No stored observations were found in the requested window.")
            }
        }

        return ForecastVerificationReport(
            lookbackDays = lookbackDays,
            pairedCount = pairMetadata.size,
            unpairedForecastCount = forecasts.size - pairMetadata.size,
            unpairedObservationCount = observations.count { it.id !in matchedObservationIds },
            pairingToleranceMinutes = PAIRING_TOLERANCE_MINUTES,
            maxDistanceKm = maxDistanceKm,
            leadTimeHoursMin = leadTimes.minOrNull(),
            leadTimeHoursMean = leadTimes.takeIf { it.isNotEmpty() }?.average(),
            leadTimeHoursMax = leadTimes.maxOrNull(),
            variables = variables,
            pairs = pairMetadata,
            caveats = caveats
        )
    }

    private fun leadTimeHours(forecast: StoredEnvironmentalRecord): Double {
        val minutes = Duration.between(forecast.retrievedAt, forecast.validAt).toMinutes()
        return (minutes / 60.0).coerceAtLeast(0.0)
    }

    private fun FieldAvailability<TemperatureUnit>.canonicalCelsius(): Double? =
        quantityOrNull()?.let { it.unit.convert(it.value, TemperatureUnit.CELSIUS) }

    private fun FieldAvailability<SpeedUnit>.canonicalMetersPerSecond(): Double? =
        quantityOrNull()?.let { it.unit.convert(it.value, SpeedUnit.METERS_PER_SECOND) }
}
