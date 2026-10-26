package com.ferousco.hydro.core.model

import java.time.Duration
import java.time.Instant

/**
 * Explicitly distinguishes historical/current observations from forecasts that were issued in the past.
 */
enum class HistoricalRecordDataType {
    /** Real-time or near-real-time observation (in-situ or model-assimilated current condition). */
    OBSERVATION,

    /** Historical meteorological reanalysis observation (e.g., ERA5 / IFS archive). */
    HISTORICAL_REANALYSIS_OBSERVATION,

    /** A weather forecast point that was issued/retrieved at [StoredEnvironmentalRecord.retrievedAt]. */
    ISSUED_FORECAST;

    val isObservationCompatible: Boolean
        get() = this == OBSERVATION || this == HISTORICAL_REANALYSIS_OBSERVATION
}

data class StoredEnvironmentalRecord(
    val id: String,
    val dataType: HistoricalRecordDataType,
    val observationKind: ObservationKind?,
    val location: GeoLocation,
    val source: EnvironmentalDataSource,
    val validAt: Instant,
    val retrievedAt: Instant,
    val timeZoneId: String,
    val variables: EnvironmentalSnapshotVariables
) {
    init {
        require(id.isNotBlank()) { "Record id must not be blank." }
        if (dataType == HistoricalRecordDataType.ISSUED_FORECAST) {
            require(observationKind == null) {
                "ISSUED_FORECAST records must not claim an ObservationKind."
            }
        }
    }
}

enum class AssessmentCompletionStatus {
    SUPPORTED,
    INCOMPLETE,
    UNSUPPORTED
}

data class StoredHeatAssessmentRecord(
    val id: String,
    val linkedEnvironmentalRecordId: String?,
    val sourceDataType: HistoricalRecordDataType,
    val location: GeoLocation,
    val source: EnvironmentalDataSource,
    val validAt: Instant,
    val assessedAt: Instant,
    val retrievedAt: Instant,
    val timeZoneId: String,
    val methodId: String,
    val methodVersion: String,
    val completionStatus: AssessmentCompletionStatus,
    val category: NwsHeatIndexCategory?,
    val uvCategory: WhoUvCategory?,
    val heatIndexCelsius: Double?,
    val airTemperatureValue: Double?,
    val airTemperatureUnit: TemperatureUnit?,
    val relativeHumidityPercent: Double?,
    val windSpeedValue: Double?,
    val windSpeedUnit: SpeedUnit?,
    val uvIndexValue: Double?,
    val solarRadiationWm2: Double?,
    val missingVariables: List<String>,
    val explanation: String
)

data class RetentionPolicy(
    val persistenceEnabled: Boolean = true,
    val maxObservationRetention: Duration = Duration.ofDays(90),
    val maxIssuedForecastRetention: Duration = Duration.ofDays(14),
    val maxAssessmentRetention: Duration = Duration.ofDays(90),
    val maxEnvironmentalRecords: Int = 5_000,
    val maxAssessmentRecords: Int = 5_000
) {
    init {
        require(!maxObservationRetention.isNegative && !maxObservationRetention.isZero) {
            "maxObservationRetention must be positive."
        }
        require(!maxIssuedForecastRetention.isNegative && !maxIssuedForecastRetention.isZero) {
            "maxIssuedForecastRetention must be positive."
        }
        require(!maxAssessmentRetention.isNegative && !maxAssessmentRetention.isZero) {
            "maxAssessmentRetention must be positive."
        }
        require(maxEnvironmentalRecords > 0 && maxAssessmentRecords > 0) {
            "Record retention count limits must be positive."
        }
    }
}

data class RetentionPruneSummary(
    val prunedEnvironmentalRecords: Int,
    val prunedAssessmentRecords: Int,
    val evaluatedAt: Instant
)

sealed interface PersistenceWriteResult<out T> {
    data class Stored<T>(val record: T) : PersistenceWriteResult<T>
    data class SkippedByRetentionPolicy(val reason: String) : PersistenceWriteResult<Nothing>
}

enum class ComparisonPairingKind {
    /** Both baseline and target are observations / historical reanalysis records. */
    OBSERVATION_TO_OBSERVATION,

    /** One record is an observation and the other is an issued forecast (explicitly disclosed). */
    OBSERVATION_TO_ISSUED_FORECAST,

    /** Both records are forecasts issued at different times (forecast revision / evolution comparison). */
    PAST_ISSUED_FORECAST_TO_ISSUED_FORECAST
}

data class VariableDelta(
    val variableName: String,
    val canonicalUnitSymbol: String,
    val baselineValue: Double,
    val targetValue: Double,
    val delta: Double
)

data class AssessmentComparisonSummary(
    val methodId: String,
    val baselineCategory: NwsHeatIndexCategory,
    val targetCategory: NwsHeatIndexCategory,
    val baselineHeatIndexCelsius: Double,
    val targetHeatIndexCelsius: Double,
    val heatIndexDeltaCelsius: Double
)

enum class IncompatibleComparisonReason {
    LOCATION_DISTANCE_EXCEEDED,
    BASELINE_IS_FORECAST_NOT_OBSERVATION,
    DIURNAL_TIME_OF_DAY_MISMATCH,
    IDENTICAL_RECORD_TIMESTAMP_AND_TYPE,
    NO_SHARED_COMPARABLE_VARIABLES,
    INCOMPATIBLE_ASSESSMENT_METHODS
}

data class HistoricalComparisonPolicy(
    val maxDistanceKm: Double = 10.0,
    val requireObservedBaseline: Boolean = false,
    val enforceDiurnalAlignmentAcrossDays: Boolean = true,
    val maxLocalTimeOfDayDifferenceMinutes: Long = 90
) {
    init {
        require(maxDistanceKm > 0.0 && maxDistanceKm.isFinite()) {
            "maxDistanceKm must be finite and positive."
        }
        require(maxLocalTimeOfDayDifferenceMinutes in 0..720) {
            "maxLocalTimeOfDayDifferenceMinutes must be within [0, 720]."
        }
    }
}

sealed interface HistoricalComparisonOutcome {
    data class Compatible(
        val baseline: StoredEnvironmentalRecord,
        val target: StoredEnvironmentalRecord,
        val distanceKm: Double,
        val pairingKind: ComparisonPairingKind,
        val variableDeltas: List<VariableDelta>,
        val uncomparableVariables: List<String>,
        val assessmentComparison: AssessmentComparisonSummary?,
        val caveats: List<String>,
        val summary: String
    ) : HistoricalComparisonOutcome

    data class Incompatible(
        val baseline: StoredEnvironmentalRecord,
        val target: StoredEnvironmentalRecord,
        val reason: IncompatibleComparisonReason,
        val explanation: String
    ) : HistoricalComparisonOutcome
}
