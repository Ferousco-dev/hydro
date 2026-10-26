package com.ferousco.hydro.core.assessment

import com.ferousco.hydro.core.logging.DiagnosticLogger
import com.ferousco.hydro.core.logging.NoOpDiagnosticLogger
import com.ferousco.hydro.core.model.AssessedInputSnapshot
import com.ferousco.hydro.core.model.CalculationMethodSpec
import com.ferousco.hydro.core.model.ContributingFactor
import com.ferousco.hydro.core.model.DataQualityAndFreshness
import com.ferousco.hydro.core.model.EnvironmentalDataSource
import com.ferousco.hydro.core.model.EnvironmentalObservation
import com.ferousco.hydro.core.model.EnvironmentalSnapshotVariables
import com.ferousco.hydro.core.model.FactorInfluence
import com.ferousco.hydro.core.model.FieldAvailability
import com.ferousco.hydro.core.model.FreshnessState
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.HeatAssessment
import com.ferousco.hydro.core.model.HourlyForecastPoint
import com.ferousco.hydro.core.model.IrradianceUnit
import com.ferousco.hydro.core.model.MeasuredQuantity
import com.ferousco.hydro.core.model.MissingVariableNotice
import com.ferousco.hydro.core.model.MissingVariableRole
import com.ferousco.hydro.core.model.NwsHeatIndexCategory
import com.ferousco.hydro.core.model.ObservationKind
import com.ferousco.hydro.core.model.PercentageUnit
import com.ferousco.hydro.core.model.QualityFlag
import com.ferousco.hydro.core.model.SpeedUnit
import com.ferousco.hydro.core.model.TemperatureUnit
import com.ferousco.hydro.core.model.UnsupportedAssessmentReason
import com.ferousco.hydro.core.model.UvIndexUnit
import com.ferousco.hydro.core.model.ValueNature
import com.ferousco.hydro.core.model.WhoUvCategory
import java.time.Clock
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Pure, deterministic environmental heat assessment engine.
 *
 * Implements:
 * 1. Official NOAA / NWS Heat Index calculation (Steadman simple formula below 80°F and
 *    Rothfusz 1990 SR 90-23 polynomial regression with low-humidity and high-humidity NWS adjustments)
 *    paired with official NWS Heat Index caution tiers.
 * 2. Official WHO / WMO Global Solar UV Index categories when `uvIndex` is available.
 *
 * Never invents composite equations, never substitutes zero for missing variables, and never
 * delegates numerical computation to a language model.
 */
class HeatAssessmentEngine(
    private val clock: Clock = Clock.systemUTC(),
    private val logger: DiagnosticLogger = NoOpDiagnosticLogger
) {

    fun assessObservation(
        observation: EnvironmentalObservation,
        allowStaleInput: Boolean = false
    ): HeatAssessment {
        val inputNature = when (observation.observationKind) {
            ObservationKind.HISTORICAL_REANALYSIS_OBSERVATION -> ValueNature.HISTORICAL_REANALYSIS
            ObservationKind.IN_SITU_OBSERVATION,
            ObservationKind.MODEL_ASSIMILATED_CURRENT -> ValueNature.OBSERVED
        }
        return assessSnapshot(
            location = observation.effectiveLocation,
            validAt = observation.observedAt,
            inputNature = inputNature,
            inputSource = observation.source,
            variables = observation.variables,
            quality = observation.quality,
            allowStaleInput = allowStaleInput
        )
    }

    fun assessForecastPoint(
        point: HourlyForecastPoint,
        forecastQuality: DataQualityAndFreshness,
        allowStaleInput: Boolean = false
    ): HeatAssessment {
        return assessSnapshot(
            location = point.location,
            validAt = point.validAt,
            inputNature = ValueNature.FORECAST,
            inputSource = point.source,
            variables = point.variables,
            quality = forecastQuality.copy(referenceDataTime = point.validAt),
            allowStaleInput = allowStaleInput
        )
    }

    fun assessSnapshot(
        location: GeoLocation,
        validAt: Instant,
        inputNature: ValueNature,
        inputSource: EnvironmentalDataSource,
        variables: EnvironmentalSnapshotVariables,
        quality: DataQualityAndFreshness,
        allowStaleInput: Boolean = false
    ): HeatAssessment {
        val assessedAt = clock.instant()
        val inputSnapshot = AssessedInputSnapshot(
            airTemperature = variables.airTemperature,
            relativeHumidity = variables.relativeHumidity,
            windSpeed = variables.windSpeed,
            feelsLikeTemperature = variables.feelsLikeTemperature,
            uvIndex = variables.uvIndex,
            solarRadiation = variables.solarRadiation,
            isDaylight = variables.isDaylight
        )
        val primaryMethod = CalculationMethodSpec.NOAA_NWS_HEAT_INDEX_SR_90_23
        val missingNotices = collectMissingVariableNotices(variables)
        val baseLimitations = buildStandardLimitations(inputNature, quality)

        if (inputNature != ValueNature.OBSERVED &&
            inputNature != ValueNature.FORECAST &&
            inputNature != ValueNature.HISTORICAL_REANALYSIS
        ) {
            return HeatAssessment.Unsupported(
                assessedAt = assessedAt,
                validAt = validAt,
                location = location,
                inputNature = inputNature,
                inputSource = inputSource,
                inputs = inputSnapshot,
                primaryMethod = primaryMethod,
                reason = UnsupportedAssessmentReason.MIXED_INCOMPATIBLE_PROVENANCE,
                quality = quality,
                missingInformation = missingNotices,
                explanation = "Heat assessment requires OBSERVED, FORECAST, or HISTORICAL_REANALYSIS environmental inputs; received $inputNature.",
                limitations = baseLimitations
            )
        }

        if (!allAvailableNaturesMatch(variables, inputNature)) {
            return HeatAssessment.Unsupported(
                assessedAt = assessedAt,
                validAt = validAt,
                location = location,
                inputNature = inputNature,
                inputSource = inputSource,
                inputs = inputSnapshot,
                primaryMethod = primaryMethod,
                reason = UnsupportedAssessmentReason.MIXED_INCOMPATIBLE_PROVENANCE,
                quality = quality,
                missingInformation = missingNotices,
                explanation = "Environmental variables mix incompatible provenance (observed vs forecast) within a single assessment input.",
                limitations = baseLimitations
            )
        }

        val validationFailure = validateAvailableInputs(variables)
        if (validationFailure != null) {
            logger.warn(
                COMPONENT,
                "Rejected invalid environmental input in heat assessment",
                mapOf("region" to location.redactedSummary(), "detail" to validationFailure)
            )
            return HeatAssessment.Unsupported(
                assessedAt = assessedAt,
                validAt = validAt,
                location = location,
                inputNature = inputNature,
                inputSource = inputSource,
                inputs = inputSnapshot,
                primaryMethod = primaryMethod,
                reason = UnsupportedAssessmentReason.INVALID_INPUT_VALUES,
                quality = quality,
                missingInformation = missingNotices,
                explanation = "Heat assessment aborted because one or more environmental values failed validation: $validationFailure",
                limitations = baseLimitations
            )
        }

        if (quality.freshnessState == FreshnessState.STALE && !allowStaleInput) {
            logger.info(
                COMPONENT,
                "Refused heat assessment on stale input under strict policy",
                mapOf("region" to location.redactedSummary(), "ageMinutes" to quality.age.toMinutes().toString())
            )
            return HeatAssessment.Unsupported(
                assessedAt = assessedAt,
                validAt = validAt,
                location = location,
                inputNature = inputNature,
                inputSource = inputSource,
                inputs = inputSnapshot,
                primaryMethod = primaryMethod,
                reason = UnsupportedAssessmentReason.STALE_INPUT_DATA,
                quality = quality,
                missingInformation = missingNotices,
                explanation = "Environmental input data is stale (retrieved at ${quality.retrievedAt}, age ${quality.age.toMinutes()} minutes) and stale assessment is disabled.",
                limitations = baseLimitations
            )
        }

        val tempAvailable = variables.airTemperature as? FieldAvailability.Available
        val rhAvailable = variables.relativeHumidity as? FieldAvailability.Available

        if (tempAvailable == null || rhAvailable == null) {
            val missingRequiredNames = missingNotices
                .filter { it.role == MissingVariableRole.REQUIRED_FOR_PRIMARY_INDEX }
                .map { it.variableName }
            return HeatAssessment.Incomplete(
                assessedAt = assessedAt,
                validAt = validAt,
                location = location,
                inputNature = inputNature,
                inputSource = inputSource,
                inputs = inputSnapshot,
                primaryMethod = primaryMethod,
                quality = quality,
                missingInformation = missingNotices,
                explanation = "Cannot compute NOAA/NWS Heat Index because required input(s) are unavailable: ${missingRequiredNames.joinToString(", ")}.",
                limitations = baseLimitations
            )
        }

        val tempCelsius = tempAvailable.quantity.unit.convert(tempAvailable.quantity.value, TemperatureUnit.CELSIUS)
        val rhPercent = rhAvailable.quantity.value

        if (tempCelsius !in MIN_SUPPORTED_HEAT_INDEX_AIR_CELSIUS..MAX_SUPPORTED_HEAT_INDEX_AIR_CELSIUS) {
            return HeatAssessment.Unsupported(
                assessedAt = assessedAt,
                validAt = validAt,
                location = location,
                inputNature = inputNature,
                inputSource = inputSource,
                inputs = inputSnapshot,
                primaryMethod = primaryMethod,
                reason = UnsupportedAssessmentReason.OUTSIDE_METHOD_VALIDITY_RANGE,
                quality = quality,
                missingInformation = missingNotices,
                explanation = String.format(
                    Locale.US,
                    "Air temperature (%.1f °C) lies outside the supported Steadman/Rothfusz empirical envelope [%.1f, %.1f] °C.",
                    tempCelsius,
                    MIN_SUPPORTED_HEAT_INDEX_AIR_CELSIUS,
                    MAX_SUPPORTED_HEAT_INDEX_AIR_CELSIUS
                ),
                limitations = baseLimitations
            )
        }

        val tempFahrenheit = TemperatureUnit.CELSIUS.convert(tempCelsius, TemperatureUnit.FAHRENHEIT)
        val calcResult = computeNwsHeatIndexFahrenheit(tempFahrenheit, rhPercent)
        val hiFahrenheit = calcResult.heatIndexFahrenheit
        val hiCelsius = TemperatureUnit.FAHRENHEIT.convert(hiFahrenheit, TemperatureUnit.CELSIUS)
        val category = NwsHeatIndexCategory.fromHeatIndexFahrenheit(hiFahrenheit)

        val uvQuantity = (variables.uvIndex as? FieldAvailability.Available)?.quantity
        val uvCategory = uvQuantity?.let { WhoUvCategory.fromUvIndex(it.value) }
        val supplementaryMethods = buildList {
            if (uvCategory != null) {
                add(CalculationMethodSpec.WHO_WMO_UV_INDEX_2002)
            }
        }

        val derivedHeatIndex = MeasuredQuantity(
            value = hiCelsius,
            unit = TemperatureUnit.CELSIUS,
            validAt = validAt,
            location = location,
            source = EnvironmentalDataSource.HYDRO_DERIVED_ENGINE_SOURCE,
            nature = ValueNature.DERIVED
        )

        val factors = buildContributingFactors(
            tempCelsius = tempCelsius,
            rhPercent = rhPercent,
            hiCelsius = hiCelsius,
            variables = variables,
            uvCategory = uvCategory
        )

        val explanation = buildExplanation(
            inputNature = inputNature,
            tempCelsius = tempCelsius,
            rhPercent = rhPercent,
            hiCelsius = hiCelsius,
            hiFahrenheit = hiFahrenheit,
            category = category,
            uvCategory = uvCategory,
            uvValue = uvQuantity?.value,
            quality = quality
        )

        return HeatAssessment.Supported(
            assessedAt = assessedAt,
            validAt = validAt,
            location = location,
            inputNature = inputNature,
            inputSource = inputSource,
            inputs = inputSnapshot,
            primaryMethod = primaryMethod,
            supplementaryMethods = supplementaryMethods,
            heatIndexCelsius = derivedHeatIndex,
            heatIndexFahrenheit = hiFahrenheit,
            category = category,
            uvCategory = uvCategory,
            formulaBranchUsed = calcResult.branchName,
            contributingFactors = factors,
            quality = quality,
            missingInformation = missingNotices,
            explanation = explanation,
            limitations = baseLimitations
        )
    }

    data class NwsFormulaComputation(
        val heatIndexFahrenheit: Double,
        val branchName: String
    )

    companion object {
        private const val COMPONENT = "HeatAssessmentEngine"

        const val MIN_SUPPORTED_HEAT_INDEX_AIR_CELSIUS = -50.0
        const val MAX_SUPPORTED_HEAT_INDEX_AIR_CELSIUS = 56.7 // ~134.06°F terrestrial upper limit

        const val BRANCH_STEADMAN_SIMPLE = "STEADMAN_SIMPLE_BELOW_80F"
        const val BRANCH_ROTHFUSZ_FULL = "ROTHFUSZ_FULL_REGRESSION"
        const val BRANCH_ROTHFUSZ_LOW_RH_ADJ = "ROTHFUSZ_LOW_HUMIDITY_ADJUSTED"
        const val BRANCH_ROTHFUSZ_HIGH_RH_ADJ = "ROTHFUSZ_HIGH_HUMIDITY_ADJUSTED"

        /**
         * Official NOAA Weather Prediction Center (WPC) Heat Index algorithm (SR 90-23).
         * Operates in degrees Fahrenheit (`tempF`) and relative humidity percent (`rhPercent` in 0..100).
         */
        fun computeNwsHeatIndexFahrenheit(tempF: Double, rhPercent: Double): NwsFormulaComputation {
            // Step 1: Steadman simple approximation averaged with dry-bulb temperature
            val simpleHi = 0.5 * (tempF + 61.0 + ((tempF - 68.0) * 1.2) + (rhPercent * 0.094))
            if (simpleHi < 80.0) {
                return NwsFormulaComputation(simpleHi, BRANCH_STEADMAN_SIMPLE)
            }

            // Step 2: Full Rothfusz 1990 regression polynomial
            val t2 = tempF * tempF
            val r2 = rhPercent * rhPercent
            var hi = -42.379 +
                2.04901523 * tempF +
                10.14333127 * rhPercent -
                0.22475541 * tempF * rhPercent -
                0.00683783 * t2 -
                0.05481717 * r2 +
                0.00122874 * t2 * rhPercent +
                0.00085282 * tempF * r2 -
                0.00000199 * t2 * r2

            // Step 3: Official NWS edge adjustments
            if (rhPercent < 13.0 && tempF in 80.0..112.0) {
                val radicand = max(0.0, (17.0 - abs(tempF - 95.0)) / 17.0)
                val adjustment = ((13.0 - rhPercent) / 4.0) * sqrt(radicand)
                hi -= adjustment
                return NwsFormulaComputation(hi, BRANCH_ROTHFUSZ_LOW_RH_ADJ)
            }

            if (rhPercent > 85.0 && tempF in 80.0..87.0) {
                val adjustment = ((rhPercent - 85.0) / 10.0) * ((87.0 - tempF) / 5.0)
                hi += adjustment
                return NwsFormulaComputation(hi, BRANCH_ROTHFUSZ_HIGH_RH_ADJ)
            }

            return NwsFormulaComputation(hi, BRANCH_ROTHFUSZ_FULL)
        }

        private fun allAvailableNaturesMatch(
            variables: EnvironmentalSnapshotVariables,
            expected: ValueNature
        ): Boolean {
            val natures = listOfNotNull(
                variables.airTemperature.quantityOrNull()?.nature,
                variables.relativeHumidity.quantityOrNull()?.nature,
                variables.windSpeed.quantityOrNull()?.nature,
                variables.feelsLikeTemperature.quantityOrNull()?.nature,
                variables.uvIndex.quantityOrNull()?.nature,
                variables.solarRadiation.quantityOrNull()?.nature
            )
            return natures.all { it == expected }
        }

        private fun validateAvailableInputs(variables: EnvironmentalSnapshotVariables): String? {
            variables.airTemperature.quantityOrNull()?.let { q ->
                TemperatureUnit.validateAmbientAir(q.value, q.unit, "airTemperature").errorOrNull()?.let { return it.message }
            }
            variables.relativeHumidity.quantityOrNull()?.let { q ->
                PercentageUnit.validateRelativeHumidity(q.value, "relativeHumidity").errorOrNull()?.let { return it.message }
            }
            variables.windSpeed.quantityOrNull()?.let { q ->
                SpeedUnit.validateWindSpeed(q.value, q.unit, "windSpeed").errorOrNull()?.let { return it.message }
            }
            variables.feelsLikeTemperature.quantityOrNull()?.let { q ->
                TemperatureUnit.validateThermalIndex(q.value, q.unit, "feelsLikeTemperature").errorOrNull()?.let { return it.message }
            }
            variables.uvIndex.quantityOrNull()?.let { q ->
                UvIndexUnit.validateUvIndex(q.value, "uvIndex").errorOrNull()?.let { return it.message }
            }
            variables.solarRadiation.quantityOrNull()?.let { q ->
                IrradianceUnit.validateSolarIrradiance(q.value, "solarRadiation").errorOrNull()?.let { return it.message }
            }
            return null
        }

        private fun collectMissingVariableNotices(
            variables: EnvironmentalSnapshotVariables
        ): List<MissingVariableNotice> = buildList {
            when (val t = variables.airTemperature) {
                is FieldAvailability.Unavailable -> add(
                    MissingVariableNotice(
                        variableName = "air_temperature",
                        role = MissingVariableRole.REQUIRED_FOR_PRIMARY_INDEX,
                        reason = t.reason,
                        impactOnAssessment = "NOAA/NWS Heat Index cannot be computed without ambient dry-bulb air temperature."
                    )
                )
                is FieldAvailability.Available -> Unit
            }
            when (val rh = variables.relativeHumidity) {
                is FieldAvailability.Unavailable -> add(
                    MissingVariableNotice(
                        variableName = "relative_humidity",
                        role = MissingVariableRole.REQUIRED_FOR_PRIMARY_INDEX,
                        reason = rh.reason,
                        impactOnAssessment = "NOAA/NWS Heat Index cannot be computed without relative humidity."
                    )
                )
                is FieldAvailability.Available -> Unit
            }
            when (val wind = variables.windSpeed) {
                is FieldAvailability.Unavailable -> add(
                    MissingVariableNotice(
                        variableName = "wind_speed",
                        role = MissingVariableRole.OPTIONAL_CONTEXTUAL_VARIABLE,
                        reason = wind.reason,
                        impactOnAssessment = "Convective airflow context is unavailable; Heat Index assumes a reference light breeze (~2.5 m/s)."
                    )
                )
                is FieldAvailability.Available -> Unit
            }
            when (val uv = variables.uvIndex) {
                is FieldAvailability.Unavailable -> add(
                    MissingVariableNotice(
                        variableName = "uv_index",
                        role = MissingVariableRole.OPTIONAL_CONTEXTUAL_VARIABLE,
                        reason = uv.reason,
                        impactOnAssessment = "WHO/WMO UV exposure classification is omitted because UV index was not available."
                    )
                )
                is FieldAvailability.Available -> Unit
            }
            when (val solar = variables.solarRadiation) {
                is FieldAvailability.Unavailable -> add(
                    MissingVariableNotice(
                        variableName = "solar_radiation",
                        role = MissingVariableRole.OPTIONAL_CONTEXTUAL_VARIABLE,
                        reason = solar.reason,
                        impactOnAssessment = "Direct/shortwave solar irradiance context is omitted; NWS Heat Index reflects shaded conditions."
                    )
                )
                is FieldAvailability.Available -> Unit
            }
            when (val feels = variables.feelsLikeTemperature) {
                is FieldAvailability.Unavailable -> add(
                    MissingVariableNotice(
                        variableName = "feels_like_temperature",
                        role = MissingVariableRole.OPTIONAL_CONTEXTUAL_VARIABLE,
                        reason = feels.reason,
                        impactOnAssessment = "Provider-modeled apparent temperature comparison is omitted."
                    )
                )
                is FieldAvailability.Available -> Unit
            }
        }

        private fun buildContributingFactors(
            tempCelsius: Double,
            rhPercent: Double,
            hiCelsius: Double,
            variables: EnvironmentalSnapshotVariables,
            uvCategory: WhoUvCategory?
        ): List<ContributingFactor> = buildList {
            add(
                ContributingFactor(
                    variableName = "air_temperature",
                    measuredSummary = String.format(Locale.US, "%.1f °C", tempCelsius),
                    influence = if (tempCelsius >= 27.0) {
                        FactorInfluence.INCREASES_THERMAL_OR_RADIATIVE_LOAD
                    } else {
                        FactorInfluence.CONTEXTUAL_NOTE
                    },
                    explanation = if (tempCelsius >= 32.0) {
                        "High ambient air temperature narrows convective heat loss from skin."
                    } else if (tempCelsius >= 27.0) {
                        "Warm ambient air temperature enters the NWS Heat Index caution domain."
                    } else {
                        "Ambient air temperature is below the 26.7°C (80°F) Rothfusz regression threshold."
                    }
                )
            )

            add(
                ContributingFactor(
                    variableName = "relative_humidity",
                    measuredSummary = String.format(Locale.US, "%.0f%%", rhPercent),
                    influence = when {
                        tempCelsius >= 26.7 && rhPercent >= 60.0 -> FactorInfluence.INCREASES_THERMAL_OR_RADIATIVE_LOAD
                        tempCelsius >= 26.7 && rhPercent < 25.0 -> FactorInfluence.MODERATES_HEAT_LOAD
                        else -> FactorInfluence.CONTEXTUAL_NOTE
                    },
                    explanation = when {
                        tempCelsius >= 26.7 && rhPercent >= 60.0 ->
                            String.format(
                                Locale.US,
                                "Elevated humidity slows sweat evaporation, raising Heat Index to %.1f °C (%.1f °C above dry-bulb temperature).",
                                hiCelsius,
                                hiCelsius - tempCelsius
                            )
                        tempCelsius >= 26.7 && rhPercent < 25.0 ->
                            "Low relative humidity permits faster sweat evaporation, though rapid fluid loss remains a concern in dry heat."
                        else ->
                            "Relative humidity has a moderate effect on perceived thermal load at this air temperature."
                    }
                )
            )

            variables.solarRadiation.quantityOrNull()?.let { solar ->
                if (solar.value >= 500.0) {
                    add(
                        ContributingFactor(
                            variableName = "solar_radiation",
                            measuredSummary = String.format(Locale.US, "%.0f W/m²", solar.value),
                            influence = FactorInfluence.INCREASES_THERMAL_OR_RADIATIVE_LOAD,
                            explanation = "Strong shortwave solar irradiance increases radiant heat load beyond shade-based NWS Heat Index values (NWS notes full sun can add up to ~8°C / 15°F to perceived heat index)."
                        )
                    )
                }
            }

            variables.uvIndex.quantityOrNull()?.let { uv ->
                if (uvCategory != null && uvCategory.severityRank >= WhoUvCategory.HIGH.severityRank) {
                    add(
                        ContributingFactor(
                            variableName = "uv_index",
                            measuredSummary = String.format(Locale.US, "%.1f (%s)", uv.value, uvCategory.displayName),
                            influence = FactorInfluence.INCREASES_THERMAL_OR_RADIATIVE_LOAD,
                            explanation = uvCategory.guidanceSummary
                        )
                    )
                }
            }

            variables.windSpeed.quantityOrNull()?.let { wind ->
                val ms = wind.unit.convert(wind.value, SpeedUnit.METERS_PER_SECOND)
                when {
                    tempCelsius >= 30.0 && ms < 1.2 -> add(
                        ContributingFactor(
                            variableName = "wind_speed",
                            measuredSummary = String.format(Locale.US, "%.1f m/s", ms),
                            influence = FactorInfluence.INCREASES_THERMAL_OR_RADIATIVE_LOAD,
                            explanation = "Very light airflow (<1.2 m/s) reduces convective and evaporative cooling below Steadman's ~2.5 m/s reference breeze."
                        )
                    )
                    tempCelsius >= 38.0 && rhPercent < 20.0 && ms >= 6.0 -> add(
                        ContributingFactor(
                            variableName = "wind_speed",
                            measuredSummary = String.format(Locale.US, "%.1f m/s", ms),
                            influence = FactorInfluence.INCREASES_THERMAL_OR_RADIATIVE_LOAD,
                            explanation = "NWS guidance notes that strong winds in very hot, dry air can accelerate convective heat gain and dehydration."
                        )
                    )
                    tempCelsius in 27.0..35.0 && ms >= 3.5 -> add(
                        ContributingFactor(
                            variableName = "wind_speed",
                            measuredSummary = String.format(Locale.US, "%.1f m/s", ms),
                            influence = FactorInfluence.MODERATES_HEAT_LOAD,
                            explanation = "Moderate airflow assists skin convective and evaporative heat dissipation."
                        )
                    )
                }
            }
        }

        private fun buildExplanation(
            inputNature: ValueNature,
            tempCelsius: Double,
            rhPercent: Double,
            hiCelsius: Double,
            hiFahrenheit: Double,
            category: NwsHeatIndexCategory,
            uvCategory: WhoUvCategory?,
            uvValue: Double?,
            quality: DataQualityAndFreshness
        ): String {
            val natureLabel = when (inputNature) {
                ValueNature.FORECAST -> "Forecast"
                ValueNature.HISTORICAL_REANALYSIS -> "Historical reanalysis"
                else -> "Observed"
            }
            val cachePrefix = if (quality.flags.contains(QualityFlag.SERVED_FROM_CACHE)) {
                "[Cached data retrieved at ${quality.retrievedAt}] "
            } else if (quality.freshnessState == FreshnessState.STALE) {
                "[Stale data retrieved at ${quality.retrievedAt}] "
            } else {
                ""
            }
            val uvClause = if (uvCategory != null && uvValue != null) {
                String.format(Locale.US, " WHO UV Index is %.1f (%s).", uvValue, uvCategory.displayName)
            } else {
                ""
            }
            return String.format(
                Locale.US,
                "%s%s air temperature of %.1f °C with %.0f%% relative humidity yields a NOAA/NWS Heat Index of %.1f °C (%.1f °F), classified as '%s'. %s%s",
                cachePrefix,
                natureLabel,
                tempCelsius,
                rhPercent,
                hiCelsius,
                hiFahrenheit,
                category.displayName,
                category.nwsPublicHealthSummary,
                uvClause
            )
        }

        private fun buildStandardLimitations(
            inputNature: ValueNature,
            quality: DataQualityAndFreshness
        ): List<String> = buildList {
            add("NOAA/NWS Heat Index (Steadman/Rothfusz SR 90-23) assumes shaded conditions and a light breeze (~2.5 m/s) for a clothed reference adult walking at ~1.4 m/s; exposure to full sunshine can increase Heat Index values by up to ~8.3°C (15°F).")
            add("Environmental assessments are informational only and do not account for individual acclimatization, hydration status, age, medications, or medical conditions, nor do they constitute medical advice.")
            if (inputNature == ValueNature.FORECAST) {
                add("Based on numerical weather forecast data rather than an on-site measurement; actual local microclimate conditions may differ.")
            }
            if (inputNature == ValueNature.HISTORICAL_REANALYSIS) {
                add("Based on historical reanalysis (ERA5 / ECMWF IFS), a model-derived archive estimate rather than an on-site station measurement.")
            }
            if (QualityFlag.MODEL_DERIVED_CURRENT_CONDITION in quality.flags) {
                add("Current weather input originates from gridded weather-model assimilation rather than an on-site thermometer.")
            }
            if (QualityFlag.SERVED_FROM_CACHE in quality.flags) {
                add("Input data was served from local cache (originally retrieved at ${quality.retrievedAt}) and is not a live network reading.")
            }
            if (quality.freshnessState == FreshnessState.STALE) {
                add("Input data exceeds operational freshness thresholds (state=STALE); current outdoor conditions may have changed substantially.")
            }
        }
    }
}
