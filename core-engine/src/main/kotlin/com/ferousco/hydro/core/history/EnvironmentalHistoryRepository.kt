package com.ferousco.hydro.core.history

import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.error.flatMap
import com.ferousco.hydro.core.error.map
import com.ferousco.hydro.core.logging.DiagnosticLogger
import com.ferousco.hydro.core.logging.NoOpDiagnosticLogger
import com.ferousco.hydro.core.model.AssessmentCompletionStatus
import com.ferousco.hydro.core.model.AssessmentComparisonSummary
import com.ferousco.hydro.core.model.ComparisonPairingKind
import com.ferousco.hydro.core.model.EnvironmentalObservation
import com.ferousco.hydro.core.model.GeoLocation
import com.ferousco.hydro.core.model.HeatAssessment
import com.ferousco.hydro.core.model.HistoricalComparisonOutcome
import com.ferousco.hydro.core.model.HistoricalComparisonPolicy
import com.ferousco.hydro.core.model.HistoricalRecordDataType
import com.ferousco.hydro.core.model.HourlyForecastPoint
import com.ferousco.hydro.core.model.IncompatibleComparisonReason
import com.ferousco.hydro.core.model.ObservationKind
import com.ferousco.hydro.core.model.PersistenceWriteResult
import com.ferousco.hydro.core.model.RetentionPolicy
import com.ferousco.hydro.core.model.RetentionPruneSummary
import com.ferousco.hydro.core.model.SpeedUnit
import com.ferousco.hydro.core.model.StoredEnvironmentalRecord
import com.ferousco.hydro.core.model.StoredHeatAssessmentRecord
import com.ferousco.hydro.core.model.TemperatureUnit
import com.ferousco.hydro.core.model.ValueNature
import com.ferousco.hydro.core.model.VariableDelta
import com.ferousco.hydro.core.model.inUnitOrNull
import com.ferousco.hydro.core.model.rawValueOrNull
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * Domain repository managing permitted local environmental memory, retention & deletion controls,
 * and compatibility-checked historical comparisons.
 *
 * Guarantees:
 * - Forecasts stored in the past retain [HistoricalRecordDataType.ISSUED_FORECAST] permanently and
 *   are NEVER relabeled or silently compared as actual observations.
 * - Comparisons validate geographic proximity, diurnal alignment across calendar days, variable
 *   unit normalization, and assessment method compatibility.
 */
class EnvironmentalHistoryRepository(
    private val store: EnvironmentalHistoryStore,
    initialRetentionPolicy: RetentionPolicy = RetentionPolicy(),
    private val clock: Clock = Clock.systemUTC(),
    private val logger: DiagnosticLogger = NoOpDiagnosticLogger
) {
    @Volatile
    private var retentionPolicy: RetentionPolicy = initialRetentionPolicy

    fun getRetentionPolicy(): RetentionPolicy = retentionPolicy

    fun updateRetentionPolicy(policy: RetentionPolicy) {
        retentionPolicy = policy
        logger.info(
            COMPONENT,
            "Updated history retention policy",
            mapOf("persistenceEnabled" to policy.persistenceEnabled.toString())
        )
    }

    suspend fun recordObservation(
        observation: EnvironmentalObservation,
        recordId: String = deterministicObservationId(observation)
    ): EngineResult<PersistenceWriteResult<StoredEnvironmentalRecord>> {
        if (!retentionPolicy.persistenceEnabled) {
            return EngineResult.Success(
                PersistenceWriteResult.SkippedByRetentionPolicy("Local history persistence is disabled by user policy.")
            )
        }
        val dataType = when (observation.observationKind) {
            ObservationKind.HISTORICAL_REANALYSIS_OBSERVATION ->
                HistoricalRecordDataType.HISTORICAL_REANALYSIS_OBSERVATION
            ObservationKind.IN_SITU_OBSERVATION,
            ObservationKind.MODEL_ASSIMILATED_CURRENT ->
                HistoricalRecordDataType.OBSERVATION
        }
        val stored = StoredEnvironmentalRecord(
            id = recordId,
            dataType = dataType,
            observationKind = observation.observationKind,
            location = observation.effectiveLocation,
            source = observation.source,
            validAt = observation.observedAt,
            retrievedAt = observation.retrievedAt,
            timeZoneId = observation.timeZone.id,
            variables = observation.variables
        )
        return store.upsertEnvironmentalRecord(stored)
            .map { PersistenceWriteResult.Stored(stored) }
    }

    suspend fun recordIssuedForecastPoint(
        point: HourlyForecastPoint,
        timeZone: ZoneId = point.localDateTime.zone,
        recordId: String = deterministicForecastPointId(point)
    ): EngineResult<PersistenceWriteResult<StoredEnvironmentalRecord>> {
        if (!retentionPolicy.persistenceEnabled) {
            return EngineResult.Success(
                PersistenceWriteResult.SkippedByRetentionPolicy("Local history persistence is disabled by user policy.")
            )
        }
        val stored = StoredEnvironmentalRecord(
            id = recordId,
            dataType = HistoricalRecordDataType.ISSUED_FORECAST,
            observationKind = null,
            location = point.location,
            source = point.source,
            validAt = point.validAt,
            retrievedAt = point.issuedOrRetrievedAt,
            timeZoneId = timeZone.id,
            variables = point.variables
        )
        return store.upsertEnvironmentalRecord(stored)
            .map { PersistenceWriteResult.Stored(stored) }
    }

    suspend fun recordHeatAssessment(
        assessment: HeatAssessment,
        timeZoneId: String = assessment.location.timeZone?.id ?: "UTC",
        linkedEnvironmentalRecordId: String? = null,
        isHistoricalReanalysis: Boolean = false,
        recordId: String = UUID.randomUUID().toString()
    ): EngineResult<PersistenceWriteResult<StoredHeatAssessmentRecord>> {
        if (!retentionPolicy.persistenceEnabled) {
            return EngineResult.Success(
                PersistenceWriteResult.SkippedByRetentionPolicy("Local history persistence is disabled by user policy.")
            )
        }
        val sourceType = when {
            assessment.inputNature == ValueNature.FORECAST -> HistoricalRecordDataType.ISSUED_FORECAST
            isHistoricalReanalysis -> HistoricalRecordDataType.HISTORICAL_REANALYSIS_OBSERVATION
            else -> HistoricalRecordDataType.OBSERVATION
        }
        val status = when (assessment) {
            is HeatAssessment.Supported -> AssessmentCompletionStatus.SUPPORTED
            is HeatAssessment.Incomplete -> AssessmentCompletionStatus.INCOMPLETE
            is HeatAssessment.Unsupported -> AssessmentCompletionStatus.UNSUPPORTED
        }
        val airTempQ = assessment.inputs.airTemperature.quantityOrNull()
        val windQ = assessment.inputs.windSpeed.quantityOrNull()

        val stored = StoredHeatAssessmentRecord(
            id = recordId,
            linkedEnvironmentalRecordId = linkedEnvironmentalRecordId,
            sourceDataType = sourceType,
            location = assessment.location,
            source = assessment.inputSource,
            validAt = assessment.validAt,
            assessedAt = assessment.assessedAt,
            retrievedAt = assessment.quality.retrievedAt,
            timeZoneId = timeZoneId,
            methodId = assessment.primaryMethod.methodId,
            methodVersion = assessment.primaryMethod.version,
            completionStatus = status,
            category = (assessment as? HeatAssessment.Supported)?.category,
            uvCategory = (assessment as? HeatAssessment.Supported)?.uvCategory,
            heatIndexCelsius = (assessment as? HeatAssessment.Supported)?.heatIndexCelsius?.value,
            airTemperatureValue = airTempQ?.value,
            airTemperatureUnit = airTempQ?.unit,
            relativeHumidityPercent = assessment.inputs.relativeHumidity.rawValueOrNull(),
            windSpeedValue = windQ?.value,
            windSpeedUnit = windQ?.unit,
            uvIndexValue = assessment.inputs.uvIndex.rawValueOrNull(),
            solarRadiationWm2 = assessment.inputs.solarRadiation.rawValueOrNull(),
            missingVariables = assessment.missingInformation.map { it.variableName },
            explanation = assessment.explanation
        )
        return store.upsertAssessmentRecord(stored)
            .map { PersistenceWriteResult.Stored(stored) }
    }

    suspend fun queryRecentRecords(
        location: GeoLocation? = null,
        maxDistanceKm: Double = 10.0,
        dataTypes: Set<HistoricalRecordDataType> = HistoricalRecordDataType.entries.toSet(),
        validFromInclusive: Instant? = null,
        validToInclusive: Instant? = null,
        limit: Int = 50
    ): EngineResult<List<StoredEnvironmentalRecord>> {
        val fetchCap = if (location != null) (limit * 6).coerceAtLeast(100) else limit
        return store.queryEnvironmentalRecords(
            dataTypes = dataTypes,
            validFromInclusive = validFromInclusive,
            validToInclusive = validToInclusive,
            limit = fetchCap
        ).map { list ->
            val filtered = if (location == null) {
                list
            } else {
                list.filter { it.location.distanceKilometersTo(location) <= maxDistanceKm }
            }
            filtered.take(limit.coerceAtLeast(0))
        }
    }

    suspend fun queryRecentAssessments(
        location: GeoLocation? = null,
        maxDistanceKm: Double = 10.0,
        dataTypes: Set<HistoricalRecordDataType> = HistoricalRecordDataType.entries.toSet(),
        limit: Int = 50
    ): EngineResult<List<StoredHeatAssessmentRecord>> {
        val fetchCap = if (location != null) (limit * 6).coerceAtLeast(100) else limit
        return store.queryRecentAssessments(dataTypes = dataTypes, limit = fetchCap).map { list ->
            val filtered = if (location == null) {
                list
            } else {
                list.filter { it.location.distanceKilometersTo(location) <= maxDistanceKm }
            }
            filtered.take(limit.coerceAtLeast(0))
        }
    }

    /**
     * Compares [baseline] (typically an earlier historical record) against [target]
     * under strict geographic, temporal, data-type, unit, and assessment-method rules.
     */
    fun compareRecords(
        baseline: StoredEnvironmentalRecord,
        target: StoredEnvironmentalRecord,
        policy: HistoricalComparisonPolicy = HistoricalComparisonPolicy(),
        baselineAssessment: StoredHeatAssessmentRecord? = null,
        targetAssessment: StoredHeatAssessmentRecord? = null
    ): HistoricalComparisonOutcome {
        if (baseline.id == target.id ||
            (baseline.validAt == target.validAt &&
                baseline.dataType == target.dataType &&
                baseline.retrievedAt == target.retrievedAt)
        ) {
            return HistoricalComparisonOutcome.Incompatible(
                baseline = baseline,
                target = target,
                reason = IncompatibleComparisonReason.IDENTICAL_RECORD_TIMESTAMP_AND_TYPE,
                explanation = "Baseline and target refer to the same environmental timestamp, retrieval time, and data type."
            )
        }

        val distanceKm = baseline.location.distanceKilometersTo(target.location)
        if (distanceKm > policy.maxDistanceKm) {
            return HistoricalComparisonOutcome.Incompatible(
                baseline = baseline,
                target = target,
                reason = IncompatibleComparisonReason.LOCATION_DISTANCE_EXCEEDED,
                explanation = String.format(
                    Locale.US,
                    "Records are %.1f km apart, exceeding maximum comparable distance of %.1f km.",
                    distanceKm,
                    policy.maxDistanceKm
                )
            )
        }

        if (policy.requireObservedBaseline && !baseline.dataType.isObservationCompatible) {
            return HistoricalComparisonOutcome.Incompatible(
                baseline = baseline,
                target = target,
                reason = IncompatibleComparisonReason.BASELINE_IS_FORECAST_NOT_OBSERVATION,
                explanation = "Baseline record (${baseline.id}) is an ISSUED_FORECAST retrieved at ${baseline.retrievedAt}, not an observed historical record."
            )
        }

        val zone = resolveSafeZone(target.timeZoneId)
        val baseLocal = baseline.validAt.atZone(zone)
        val targetLocal = target.validAt.atZone(zone)

        if (policy.enforceDiurnalAlignmentAcrossDays && baseLocal.toLocalDate() != targetLocal.toLocalDate()) {
            val baseMinuteOfDay = baseLocal.hour * 60L + baseLocal.minute
            val targetMinuteOfDay = targetLocal.hour * 60L + targetLocal.minute
            val rawDiff = abs(baseMinuteOfDay - targetMinuteOfDay)
            val circularDiffMinutes = minOf(rawDiff, 1440L - rawDiff)
            if (circularDiffMinutes > policy.maxLocalTimeOfDayDifferenceMinutes) {
                return HistoricalComparisonOutcome.Incompatible(
                    baseline = baseline,
                    target = target,
                    reason = IncompatibleComparisonReason.DIURNAL_TIME_OF_DAY_MISMATCH,
                    explanation = "Records come from incompatible times of day (${baseLocal.toLocalTime()} vs ${targetLocal.toLocalTime()}, difference $circularDiffMinutes minutes > allowed ${policy.maxLocalTimeOfDayDifferenceMinutes} minutes)."
                )
            }
        }

        var assessmentSummary: AssessmentComparisonSummary? = null
        if (baselineAssessment != null && targetAssessment != null &&
            baselineAssessment.completionStatus == AssessmentCompletionStatus.SUPPORTED &&
            targetAssessment.completionStatus == AssessmentCompletionStatus.SUPPORTED &&
            baselineAssessment.heatIndexCelsius != null &&
            targetAssessment.heatIndexCelsius != null &&
            baselineAssessment.category != null &&
            targetAssessment.category != null
        ) {
            if (baselineAssessment.methodId != targetAssessment.methodId) {
                return HistoricalComparisonOutcome.Incompatible(
                    baseline = baseline,
                    target = target,
                    reason = IncompatibleComparisonReason.INCOMPATIBLE_ASSESSMENT_METHODS,
                    explanation = "Cannot compare heat assessments computed with different methods (${baselineAssessment.methodId} vs ${targetAssessment.methodId})."
                )
            }
            assessmentSummary = AssessmentComparisonSummary(
                methodId = baselineAssessment.methodId,
                baselineCategory = baselineAssessment.category,
                targetCategory = targetAssessment.category,
                baselineHeatIndexCelsius = baselineAssessment.heatIndexCelsius,
                targetHeatIndexCelsius = targetAssessment.heatIndexCelsius,
                heatIndexDeltaCelsius = targetAssessment.heatIndexCelsius - baselineAssessment.heatIndexCelsius
            )
        }

        val deltas = ArrayList<VariableDelta>()
        val uncomparable = ArrayList<String>()

        fun compareMetric(
            name: String,
            unitSymbol: String,
            baseVal: Double?,
            targetVal: Double?
        ) {
            if (baseVal != null && targetVal != null) {
                deltas.add(
                    VariableDelta(
                        variableName = name,
                        canonicalUnitSymbol = unitSymbol,
                        baselineValue = baseVal,
                        targetValue = targetVal,
                        delta = targetVal - baseVal
                    )
                )
            } else {
                uncomparable.add(name)
            }
        }

        compareMetric(
            "air_temperature",
            TemperatureUnit.CELSIUS.symbol,
            baseline.variables.airTemperature.inUnitOrNull(TemperatureUnit.CELSIUS),
            target.variables.airTemperature.inUnitOrNull(TemperatureUnit.CELSIUS)
        )
        compareMetric(
            "relative_humidity",
            "%",
            baseline.variables.relativeHumidity.rawValueOrNull(),
            target.variables.relativeHumidity.rawValueOrNull()
        )
        compareMetric(
            "wind_speed",
            SpeedUnit.METERS_PER_SECOND.symbol,
            baseline.variables.windSpeed.inUnitOrNull(SpeedUnit.METERS_PER_SECOND),
            target.variables.windSpeed.inUnitOrNull(SpeedUnit.METERS_PER_SECOND)
        )
        compareMetric(
            "feels_like_temperature",
            TemperatureUnit.CELSIUS.symbol,
            baseline.variables.feelsLikeTemperature.inUnitOrNull(TemperatureUnit.CELSIUS),
            target.variables.feelsLikeTemperature.inUnitOrNull(TemperatureUnit.CELSIUS)
        )
        compareMetric(
            "uv_index",
            "UVI",
            baseline.variables.uvIndex.rawValueOrNull(),
            target.variables.uvIndex.rawValueOrNull()
        )
        compareMetric(
            "solar_radiation",
            "W/m²",
            baseline.variables.solarRadiation.rawValueOrNull(),
            target.variables.solarRadiation.rawValueOrNull()
        )

        if (deltas.isEmpty()) {
            return HistoricalComparisonOutcome.Incompatible(
                baseline = baseline,
                target = target,
                reason = IncompatibleComparisonReason.NO_SHARED_COMPARABLE_VARIABLES,
                explanation = "Baseline and target share zero available comparable weather variables."
            )
        }

        val pairingKind = when {
            baseline.dataType.isObservationCompatible && target.dataType.isObservationCompatible ->
                ComparisonPairingKind.OBSERVATION_TO_OBSERVATION
            baseline.dataType == HistoricalRecordDataType.ISSUED_FORECAST &&
                target.dataType == HistoricalRecordDataType.ISSUED_FORECAST ->
                ComparisonPairingKind.PAST_ISSUED_FORECAST_TO_ISSUED_FORECAST
            else ->
                ComparisonPairingKind.OBSERVATION_TO_ISSUED_FORECAST
        }

        val caveats = buildList {
            when (pairingKind) {
                ComparisonPairingKind.OBSERVATION_TO_OBSERVATION -> {
                    if (baseline.dataType == HistoricalRecordDataType.HISTORICAL_REANALYSIS_OBSERVATION ||
                        target.dataType == HistoricalRecordDataType.HISTORICAL_REANALYSIS_OBSERVATION
                    ) {
                        add("Comparison includes gridded meteorological reanalysis data alongside observations.")
                    }
                }
                ComparisonPairingKind.OBSERVATION_TO_ISSUED_FORECAST -> {
                    add("Comparison pairs an observed record (${if (baseline.dataType.isObservationCompatible) "baseline" else "target"}) with an issued forecast (${if (!baseline.dataType.isObservationCompatible) "baseline" else "target"}); forecast uncertainty applies.")
                }
                ComparisonPairingKind.PAST_ISSUED_FORECAST_TO_ISSUED_FORECAST -> {
                    add("Both baseline (issued ${baseline.retrievedAt}) and target (issued ${target.retrievedAt}) are weather forecasts rather than observed measurements.")
                }
            }
            if (uncomparable.isNotEmpty()) {
                add("Omitted unavailable variable(s) from delta calculation: ${uncomparable.joinToString(", ")}.")
            }
        }

        val tempDeltaText = deltas.firstOrNull { it.variableName == "air_temperature" }?.let {
            String.format(Locale.US, "air temperature %+.1f °C", it.delta)
        }
        val rhDeltaText = deltas.firstOrNull { it.variableName == "relative_humidity" }?.let {
            String.format(Locale.US, "relative humidity %+.0f%%", it.delta)
        }
        val hiDeltaText = assessmentSummary?.let {
            String.format(
                Locale.US,
                "Heat Index %+.1f °C (%s -> %s)",
                it.heatIndexDeltaCelsius,
                it.baselineCategory.displayName,
                it.targetCategory.displayName
            )
        }
        val summaryMetrics = listOfNotNull(tempDeltaText, rhDeltaText, hiDeltaText).joinToString(", ")
        val summary = "Compared ${baseline.dataType} (${baseline.validAt}) with ${target.dataType} (${target.validAt}): $summaryMetrics."

        return HistoricalComparisonOutcome.Compatible(
            baseline = baseline,
            target = target,
            distanceKm = distanceKm,
            pairingKind = pairingKind,
            variableDeltas = deltas,
            uncomparableVariables = uncomparable,
            assessmentComparison = assessmentSummary,
            caveats = caveats,
            summary = summary
        )
    }

    suspend fun deleteRecordById(id: String): EngineResult<Boolean> =
        store.deleteEnvironmentalRecordById(id)

    suspend fun deleteAssessmentById(id: String): EngineResult<Boolean> =
        store.deleteAssessmentRecordById(id)

    suspend fun deleteRecordsNearLocation(
        location: GeoLocation,
        radiusKm: Double
    ): EngineResult<Int> {
        return store.listAllEnvironmentalRecords().flatMap { allEnv ->
            val envIdsToDelete = allEnv
                .filter { it.location.distanceKilometersTo(location) <= radiusKm }
                .map { it.id }
                .toSet()

            store.listAllAssessmentRecords().flatMap { allAssess ->
                val assessIdsToDelete = allAssess
                    .filter {
                        it.location.distanceKilometersTo(location) <= radiusKm ||
                            (it.linkedEnvironmentalRecordId != null && it.linkedEnvironmentalRecordId in envIdsToDelete)
                    }
                    .map { it.id }
                    .toSet()

                store.deleteAssessmentRecordsByIds(assessIdsToDelete).flatMap {
                    store.deleteEnvironmentalRecordsByIds(envIdsToDelete)
                }
            }
        }
    }

    suspend fun deleteAllHistory(): EngineResult<Unit> = store.deleteAll()

    suspend fun enforceRetentionPolicy(now: Instant = clock.instant()): EngineResult<RetentionPruneSummary> {
        val policy = retentionPolicy
        val obsCutoff = now.minus(policy.maxObservationRetention)
        val forecastCutoff = now.minus(policy.maxIssuedForecastRetention)
        val assessCutoff = now.minus(policy.maxAssessmentRetention)

        return store.listAllEnvironmentalRecords().flatMap { allEnv ->
            val expiredIds = allEnv.filter { rec ->
                val cutoff = if (rec.dataType == HistoricalRecordDataType.ISSUED_FORECAST) {
                    forecastCutoff
                } else {
                    obsCutoff
                }
                rec.retrievedAt.isBefore(cutoff)
            }.map { it.id }.toMutableSet()

            val remainingEnv = allEnv
                .filter { it.id !in expiredIds }
                .sortedByDescending { it.retrievedAt }
            if (remainingEnv.size > policy.maxEnvironmentalRecords) {
                remainingEnv.drop(policy.maxEnvironmentalRecords).forEach { expiredIds.add(it.id) }
            }

            store.deleteEnvironmentalRecordsByIds(expiredIds).flatMap { prunedEnvCount ->
                store.listAllAssessmentRecords().flatMap { allAssess ->
                    val expiredAssessIds = allAssess.filter { rec ->
                        rec.assessedAt.isBefore(assessCutoff) ||
                            (rec.linkedEnvironmentalRecordId != null && rec.linkedEnvironmentalRecordId in expiredIds)
                    }.map { it.id }.toMutableSet()

                    val remainingAssess = allAssess
                        .filter { it.id !in expiredAssessIds }
                        .sortedByDescending { it.assessedAt }
                    if (remainingAssess.size > policy.maxAssessmentRecords) {
                        remainingAssess.drop(policy.maxAssessmentRecords).forEach { expiredAssessIds.add(it.id) }
                    }

                    store.deleteAssessmentRecordsByIds(expiredAssessIds).map { prunedAssessCount ->
                        RetentionPruneSummary(
                            prunedEnvironmentalRecords = prunedEnvCount,
                            prunedAssessmentRecords = prunedAssessCount,
                            evaluatedAt = now
                        )
                    }
                }
            }
        }
    }

    private fun resolveSafeZone(tzId: String): ZoneId = try {
        ZoneId.of(tzId)
    } catch (_: Exception) {
        ZoneOffset.UTC
    }

    companion object {
        private const val COMPONENT = "EnvironmentalHistoryRepository"

        fun deterministicObservationId(obs: EnvironmentalObservation): String =
            "obs:${obs.source.providerId}:${obs.observationKind.name}:${obs.effectiveLocation.cacheCellKey(3)}:${obs.observedAt.epochSecond}"

        fun deterministicForecastPointId(point: HourlyForecastPoint): String =
            "fcst:${point.source.providerId}:${point.location.cacheCellKey(3)}:${point.validAt.epochSecond}:issued:${point.issuedOrRetrievedAt.epochSecond}"
    }
}
