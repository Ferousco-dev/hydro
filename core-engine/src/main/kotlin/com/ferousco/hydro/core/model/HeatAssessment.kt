package com.ferousco.hydro.core.model

import java.time.Instant

/**
 * Official NOAA / US National Weather Service (NWS) Heat Index caution classifications.
 *
 * Reference thresholds (in °F, evaluated continuously before display rounding):
 * - Below Caution: < 80.0 °F (< 26.67 °C)
 * - Caution: 80.0 °F to < 90.0 °F (26.67 °C to < 32.22 °C)
 * - Extreme Caution: 90.0 °F to < 103.0 °F (32.22 °C to < 39.44 °C)
 * - Danger: 103.0 °F to < 125.0 °F (39.44 °C to < 51.67 °C)
 * - Extreme Danger: >= 125.0 °F (>= 51.67 °C)
 *
 * Source: NOAA / NWS Heat Safety & AMA Heat Index Classification (https://www.weather.gov/ama/heatindex).
 */
enum class NwsHeatIndexCategory(
    val severityRank: Int,
    val displayName: String,
    val lowerBoundFahrenheit: Double?,
    val upperBoundExclusiveFahrenheit: Double?,
    val nwsPublicHealthSummary: String
) {
    BELOW_CAUTION_THRESHOLD(
        severityRank = 0,
        displayName = "Below NWS Caution Threshold",
        lowerBoundFahrenheit = null,
        upperBoundExclusiveFahrenheit = 80.0,
        nwsPublicHealthSummary = "Heat Index is below the 80°F (26.7°C) NWS Heat Index caution threshold."
    ),
    CAUTION(
        severityRank = 1,
        displayName = "Caution",
        lowerBoundFahrenheit = 80.0,
        upperBoundExclusiveFahrenheit = 90.0,
        nwsPublicHealthSummary = "Fatigue possible with prolonged exposure and/or physical activity."
    ),
    EXTREME_CAUTION(
        severityRank = 2,
        displayName = "Extreme Caution",
        lowerBoundFahrenheit = 90.0,
        upperBoundExclusiveFahrenheit = 103.0,
        nwsPublicHealthSummary = "Heat stroke, heat cramps, or heat exhaustion possible with prolonged exposure and/or physical activity."
    ),
    DANGER(
        severityRank = 3,
        displayName = "Danger",
        lowerBoundFahrenheit = 103.0,
        upperBoundExclusiveFahrenheit = 125.0,
        nwsPublicHealthSummary = "Heat cramps or heat exhaustion likely, and heat stroke possible with prolonged exposure and/or physical activity."
    ),
    EXTREME_DANGER(
        severityRank = 4,
        displayName = "Extreme Danger",
        lowerBoundFahrenheit = 125.0,
        upperBoundExclusiveFahrenheit = null,
        nwsPublicHealthSummary = "Heat stroke highly likely with continued exposure."
    );

    companion object {
        fun fromHeatIndexFahrenheit(hiFahrenheit: Double): NwsHeatIndexCategory = when {
            hiFahrenheit < 80.0 -> BELOW_CAUTION_THRESHOLD
            hiFahrenheit < 90.0 -> CAUTION
            hiFahrenheit < 103.0 -> EXTREME_CAUTION
            hiFahrenheit < 125.0 -> DANGER
            else -> EXTREME_DANGER
        }
    }
}

/**
 * World Health Organization (WHO) / World Meteorological Organization (WMO) Global Solar UV Index categories.
 *
 * Source: WHO / WMO / UNEP / ICNIRP (2002), Global Solar UV Index: A Practical Guide.
 */
enum class WhoUvCategory(
    val severityRank: Int,
    val displayName: String,
    val guidanceSummary: String
) {
    LOW(0, "Low (0–2)", "Minimal UV protection required for normal brief exposure."),
    MODERATE(1, "Moderate (3–5)", "Seek shade near midday; sun protection recommended for extended exposure."),
    HIGH(2, "High (6–7)", "Reduce sun exposure during midday hours; protective clothing, hat, and sunscreen advised."),
    VERY_HIGH(3, "Very High (8–10)", "Extra UV protection needed; minimize direct midday sun exposure."),
    EXTREME(4, "Extreme (11+)", "Unprotected skin and eyes can burn rapidly; avoid direct midday sun where possible.");

    companion object {
        fun fromUvIndex(uvi: Double): WhoUvCategory = when {
            uvi < 3.0 -> LOW
            uvi < 6.0 -> MODERATE
            uvi < 8.0 -> HIGH
            uvi < 11.0 -> VERY_HIGH
            else -> EXTREME
        }
    }
}

data class CalculationMethodSpec(
    val methodId: String,
    val name: String,
    val authority: String,
    val citation: String,
    val version: String = "1.0"
) {
    companion object {
        val NOAA_NWS_HEAT_INDEX_SR_90_23 = CalculationMethodSpec(
            methodId = "NOAA_NWS_ROTHFUSZ_SR_90_23",
            name = "NOAA / NWS Heat Index (Steadman / Rothfusz Regression)",
            authority = "US National Oceanic and Atmospheric Administration (NOAA) / National Weather Service (NWS)",
            citation = "Rothfusz, L. P. (1990), NWS Technical Attachment SR 90-23; NOAA WPC Heat Index Equation & NWS Heat Safety Thresholds; Steadman, R. G. (1979), J. Appl. Meteorol. 18(7), 861-873.",
            version = "1990-WPC"
        )

        val WHO_WMO_UV_INDEX_2002 = CalculationMethodSpec(
            methodId = "WHO_WMO_GLOBAL_SOLAR_UVI_2002",
            name = "Global Solar UV Index Classification",
            authority = "WHO / WMO / UNEP / ICNIRP",
            citation = "World Health Organization (2002), Global Solar UV Index: A Practical Guide.",
            version = "2002"
        )
    }
}

enum class FactorInfluence {
    INCREASES_THERMAL_OR_RADIATIVE_LOAD,
    MODERATES_HEAT_LOAD,
    CONTEXTUAL_NOTE
}

data class ContributingFactor(
    val variableName: String,
    val measuredSummary: String,
    val influence: FactorInfluence,
    val explanation: String
)

enum class MissingVariableRole {
    REQUIRED_FOR_PRIMARY_INDEX,
    OPTIONAL_CONTEXTUAL_VARIABLE
}

data class MissingVariableNotice(
    val variableName: String,
    val role: MissingVariableRole,
    val reason: UnavailableReason,
    val impactOnAssessment: String
)

/**
 * Snapshot of the exact validated inputs and units supplied to an assessment.
 */
data class AssessedInputSnapshot(
    val airTemperature: FieldAvailability<TemperatureUnit>,
    val relativeHumidity: FieldAvailability<PercentageUnit>,
    val windSpeed: FieldAvailability<SpeedUnit>,
    val feelsLikeTemperature: FieldAvailability<TemperatureUnit>,
    val uvIndex: FieldAvailability<UvIndexUnit>,
    val solarRadiation: FieldAvailability<IrradianceUnit>,
    val isDaylight: Boolean? = null
)

enum class UnsupportedAssessmentReason {
    STALE_INPUT_DATA,
    INVALID_INPUT_VALUES,
    OUTSIDE_METHOD_VALIDITY_RANGE,
    MIXED_INCOMPATIBLE_PROVENANCE
}

/**
 * Deterministic environmental heat assessment outcome.
 *
 * When required inputs are missing or outside the method's empirical domain, Hydro returns
 * [Incomplete] or [Unsupported] rather than fabricating a Heat Index or risk category.
 */
sealed interface HeatAssessment {
    val assessedAt: Instant
    val validAt: Instant
    val location: GeoLocation
    val inputNature: ValueNature
    val inputSource: EnvironmentalDataSource
    val inputs: AssessedInputSnapshot
    val primaryMethod: CalculationMethodSpec
    val quality: DataQualityAndFreshness
    val missingInformation: List<MissingVariableNotice>
    val explanation: String
    val limitations: List<String>

    data class Supported(
        override val assessedAt: Instant,
        override val validAt: Instant,
        override val location: GeoLocation,
        override val inputNature: ValueNature,
        override val inputSource: EnvironmentalDataSource,
        override val inputs: AssessedInputSnapshot,
        override val primaryMethod: CalculationMethodSpec,
        val supplementaryMethods: List<CalculationMethodSpec>,
        val heatIndexCelsius: MeasuredQuantity<TemperatureUnit>,
        val heatIndexFahrenheit: Double,
        val category: NwsHeatIndexCategory,
        val uvCategory: WhoUvCategory?,
        val formulaBranchUsed: String,
        val contributingFactors: List<ContributingFactor>,
        override val quality: DataQualityAndFreshness,
        override val missingInformation: List<MissingVariableNotice>,
        override val explanation: String,
        override val limitations: List<String>
    ) : HeatAssessment {
        init {
            require(heatIndexCelsius.nature == ValueNature.DERIVED) {
                "Computed Heat Index must carry ValueNature.DERIVED."
            }
            require(heatIndexCelsius.unit == TemperatureUnit.CELSIUS) {
                "Canonical heatIndexCelsius quantity must be stored in CELSIUS."
            }
            require(inputNature == ValueNature.OBSERVED || inputNature == ValueNature.FORECAST) {
                "HeatAssessment inputNature must be OBSERVED or FORECAST, got $inputNature."
            }
        }
    }

    data class Incomplete(
        override val assessedAt: Instant,
        override val validAt: Instant,
        override val location: GeoLocation,
        override val inputNature: ValueNature,
        override val inputSource: EnvironmentalDataSource,
        override val inputs: AssessedInputSnapshot,
        override val primaryMethod: CalculationMethodSpec,
        override val quality: DataQualityAndFreshness,
        override val missingInformation: List<MissingVariableNotice>,
        override val explanation: String,
        override val limitations: List<String>
    ) : HeatAssessment

    data class Unsupported(
        override val assessedAt: Instant,
        override val validAt: Instant,
        override val location: GeoLocation,
        override val inputNature: ValueNature,
        override val inputSource: EnvironmentalDataSource,
        override val inputs: AssessedInputSnapshot,
        override val primaryMethod: CalculationMethodSpec,
        val reason: UnsupportedAssessmentReason,
        override val quality: DataQualityAndFreshness,
        override val missingInformation: List<MissingVariableNotice>,
        override val explanation: String,
        override val limitations: List<String>
    ) : HeatAssessment
}
