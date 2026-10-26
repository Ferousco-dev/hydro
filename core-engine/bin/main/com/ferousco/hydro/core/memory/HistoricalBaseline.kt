package com.ferousco.hydro.core.memory

import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.HistoricalRecordDataType
import com.ferousco.hydro.core.model.SpeedUnit
import com.ferousco.hydro.core.model.StoredEnvironmentalRecord
import com.ferousco.hydro.core.model.TemperatureUnit
import com.ferousco.hydro.core.model.inUnitOrNull
import com.ferousco.hydro.core.model.rawValueOrNull
import java.time.Duration
import java.time.Instant

// ============================================================================
// Baseline selection
// ============================================================================

/**
 * A stored record chosen as the comparison baseline, plus why it was chosen.
 *
 * [offsetFromTarget] is signed: negative means the record is older than the requested clock time.
 */
data class BaselineSelection(
    val baseline: StoredEnvironmentalRecord,
    val offsetFromTarget: Duration,
    val caveats: List<String>
)

/**
 * Selects the record closest to the same clock time one day earlier.
 *
 * This answers "how does today compare with yesterday?" without pretending the answer is a
 * climatological normal. Forecast records are excluded because a forecast is not a measurement, and
 * the search is bounded by tolerance and distance so a record from the wrong hour or a different
 * town is never silently used.
 */
object HistoricalBaselineSelector {

    /** Records closer than this to the target hour are considered "same time". */
    val DEFAULT_TOLERANCE: Duration = Duration.ofMinutes(90)

    /** Baselines farther than this from the target coordinates are rejected. */
    const val DEFAULT_MAX_DISTANCE_KM: Double = 10.0

    fun selectSameTimeYesterday(
        records: List<StoredEnvironmentalRecord>,
        targetValidAt: Instant,
        tolerance: Duration = DEFAULT_TOLERANCE,
        maxDistanceKm: Double = DEFAULT_MAX_DISTANCE_KM,
        targetLocation: GeoLocation? = null
    ): BaselineSelection? {
        require(!tolerance.isNegative && !tolerance.isZero) { "tolerance must be positive." }
        require(maxDistanceKm > 0.0) { "maxDistanceKm must be positive." }

        val desired = targetValidAt.minus(Duration.ofDays(1))
        val candidate = records.asSequence()
            .filter { it.dataType.isObservationCompatible }
            .filter { targetLocation == null || it.location.distanceKilometersTo(targetLocation) <= maxDistanceKm }
            .map { record -> record to signedOffset(desired, record.validAt) }
            .filter { (_, offset) -> absolute(offset) <= tolerance }
            .minWithOrNull(
                compareBy<Pair<StoredEnvironmentalRecord, Duration>> { (_, offset) -> absolute(offset) }
                    .thenBy { (record, _) ->
                        targetLocation?.let { record.location.distanceKilometersTo(it) } ?: 0.0
                    }
            )
            ?: return null

        val (record, offset) = candidate
        val caveats = buildList {
            add(
                "Baseline is a same-clock-time snapshot from about 24 hours earlier, not a " +
                    "climatological normal."
            )
            if (absolute(offset) > Duration.ofMinutes(30)) {
                add("Baseline is ${absolute(offset).toMinutes()} minutes from the same clock time yesterday.")
            }
            if (record.dataType == HistoricalRecordDataType.HISTORICAL_REANALYSIS_OBSERVATION) {
                add("Baseline comes from historical reanalysis, not a direct observation.")
            }
            if (targetLocation != null) {
                val distanceKm = record.location.distanceKilometersTo(targetLocation)
                if (distanceKm > 2.0) {
                    add("Baseline is ${"%.1f".format(distanceKm)} km from the target coordinates.")
                }
            }
        }

        return BaselineSelection(baseline = record, offsetFromTarget = offset, caveats = caveats)
    }

    private fun signedOffset(target: Instant, actual: Instant): Duration =
        Duration.between(target, actual)

    private fun absolute(duration: Duration): Duration =
        if (duration.isNegative) duration.negated() else duration
}

// ============================================================================
// Comparison
// ============================================================================

/** One variable present on both records, expressed in a canonical unit. */
data class VariableDelta(
    val variable: String,
    val unit: String,
    val currentValue: Double,
    val baselineValue: Double
) {
    /** Current minus baseline. Positive means today is higher. */
    val delta: Double get() = currentValue - baselineValue
}

/**
 * Descriptive comparison of a current record against a selected baseline.
 *
 * [deltas] only contains variables available on both sides. A missing variable is absent, never
 * zero-filled, so the caller cannot mistake "not measured" for "no change".
 */
data class HistoricalComparisonSummary(
    val currentRecordId: String,
    val baselineRecordId: String,
    val currentValidAt: Instant,
    val baselineValidAt: Instant,
    val deltas: List<VariableDelta>,
    val caveats: List<String>
) {
    fun deltaFor(variable: String): VariableDelta? =
        deltas.firstOrNull { it.variable == variable }

    val hasComparableVariables: Boolean get() = deltas.isNotEmpty()
}

object HistoricalComparator {
    const val AIR_TEMPERATURE = "air_temperature"
    const val RELATIVE_HUMIDITY = "relative_humidity"
    const val WIND_SPEED = "wind_speed"
    const val UV_INDEX = "uv_index"
    const val SOLAR_RADIATION = "solar_radiation"

    /**
     * Compares two records in canonical units.
     *
     * Temperature is normalized to Celsius and wind to m/s regardless of how each provider stored
     * it, so a Fahrenheit baseline is not compared numerically against a Celsius current value.
     */
    fun compare(
        current: StoredEnvironmentalRecord,
        baseline: StoredEnvironmentalRecord,
        selectionCaveats: List<String> = emptyList()
    ): HistoricalComparisonSummary {
        val deltas = buildList {
            addIfBothAvailable(
                variable = AIR_TEMPERATURE,
                unit = "°C",
                currentValue = current.variables.airTemperature.inUnitOrNull(TemperatureUnit.CELSIUS),
                baselineValue = baseline.variables.airTemperature.inUnitOrNull(TemperatureUnit.CELSIUS)
            )
            addIfBothAvailable(
                variable = RELATIVE_HUMIDITY,
                unit = "%",
                currentValue = current.variables.relativeHumidity.rawValueOrNull(),
                baselineValue = baseline.variables.relativeHumidity.rawValueOrNull()
            )
            addIfBothAvailable(
                variable = WIND_SPEED,
                unit = "m/s",
                currentValue = current.variables.windSpeed.inUnitOrNull(SpeedUnit.METERS_PER_SECOND),
                baselineValue = baseline.variables.windSpeed.inUnitOrNull(SpeedUnit.METERS_PER_SECOND)
            )
            addIfBothAvailable(
                variable = UV_INDEX,
                unit = "UV index",
                currentValue = current.variables.uvIndex.rawValueOrNull(),
                baselineValue = baseline.variables.uvIndex.rawValueOrNull()
            )
            addIfBothAvailable(
                variable = SOLAR_RADIATION,
                unit = "W/m²",
                currentValue = current.variables.solarRadiation.rawValueOrNull(),
                baselineValue = baseline.variables.solarRadiation.rawValueOrNull()
            )
        }

        val caveats = buildList {
            addAll(selectionCaveats)
            if (current.source.providerId != baseline.source.providerId) {
                add(
                    "Current and baseline come from different providers " +
                        "(${current.source.providerId} vs ${baseline.source.providerId}); " +
                        "provider differences can exceed the real day-to-day change."
                )
            } else if (current.source.endpointOrDataset != baseline.source.endpointOrDataset) {
                add(
                    "Current and baseline come from different source datasets " +
                        "(${current.source.endpointOrDataset} vs ${baseline.source.endpointOrDataset}); " +
                        "reanalysis and live nowcasts do not describe conditions identically."
                )
            }
            if (deltas.isEmpty()) {
                add("No environmental variable was available on both records, so no delta is reported.")
            }
            add("Deltas are descriptive; Hydro does not attribute them to a cause or a long-term trend.")
        }

        return HistoricalComparisonSummary(
            currentRecordId = current.id,
            baselineRecordId = baseline.id,
            currentValidAt = current.validAt,
            baselineValidAt = baseline.validAt,
            deltas = deltas,
            caveats = caveats
        )
    }

    /** Convenience: select yesterday's same-time baseline and compare it to [current]. */
    fun compareToYesterday(
        records: List<StoredEnvironmentalRecord>,
        current: StoredEnvironmentalRecord,
        tolerance: Duration = HistoricalBaselineSelector.DEFAULT_TOLERANCE,
        maxDistanceKm: Double = HistoricalBaselineSelector.DEFAULT_MAX_DISTANCE_KM
    ): HistoricalComparisonSummary? {
        val selection = HistoricalBaselineSelector.selectSameTimeYesterday(
            records = records,
            targetValidAt = current.validAt,
            tolerance = tolerance,
            maxDistanceKm = maxDistanceKm,
            targetLocation = current.location
        ) ?: return null
        return compare(current, selection.baseline, selection.caveats)
    }

    private fun MutableList<VariableDelta>.addIfBothAvailable(
        variable: String,
        unit: String,
        currentValue: Double?,
        baselineValue: Double?
    ) {
        if (currentValue == null || baselineValue == null) return
        add(
            VariableDelta(
                variable = variable,
                unit = unit,
                currentValue = currentValue,
                baselineValue = baselineValue
            )
        )
    }
}

// ============================================================================
// Latest-versus-yesterday lookup
// ============================================================================

/** Why a "today vs yesterday" answer could not be produced. */
enum class YesterdayComparisonUnavailableReason {
    /** The caller supplied no observation-compatible record at all. */
    NO_RECENT_OBSERVATION,

    /** A current observation exists, but no record sits near the same clock time yesterday. */
    NO_BASELINE
}

/**
 * The result of asking "how does the latest stored observation compare with yesterday?".
 *
 * [Unavailable] is explicit rather than `null` so a caller can distinguish "I did not ask" from
 * "I asked and there is no honest answer".
 */
sealed interface YesterdayComparison {
    data class Available(val summary: HistoricalComparisonSummary) : YesterdayComparison

    data class Unavailable(
        val reason: YesterdayComparisonUnavailableReason,
        val explanation: String
    ) : YesterdayComparison
}

object HistoricalComparisonService {

    /**
     * Picks the most recent observation in [records] and compares it with the closest record near the
     * same clock time one day earlier.
     *
     * Only observation-compatible records are eligible on either side, so an issued forecast is never
     * presented as yesterday's measured weather.
     */
    fun latestVersusYesterday(
        records: List<StoredEnvironmentalRecord>,
        tolerance: Duration = HistoricalBaselineSelector.DEFAULT_TOLERANCE,
        maxDistanceKm: Double = HistoricalBaselineSelector.DEFAULT_MAX_DISTANCE_KM
    ): YesterdayComparison {
        val latest = records
            .asSequence()
            .filter { it.dataType.isObservationCompatible }
            .maxByOrNull { it.validAt }
            ?: return YesterdayComparison.Unavailable(
                reason = YesterdayComparisonUnavailableReason.NO_RECENT_OBSERVATION,
                explanation = "No observation-compatible record was available, so there is nothing to compare with yesterday."
            )

        val summary = HistoricalComparator.compareToYesterday(
            records = records,
            current = latest,
            tolerance = tolerance,
            maxDistanceKm = maxDistanceKm
        ) ?: return YesterdayComparison.Unavailable(
            reason = YesterdayComparisonUnavailableReason.NO_BASELINE,
            explanation = "No observation within the tolerance of the same clock time yesterday was found near this location."
        )

        return YesterdayComparison.Available(summary)
    }
}
