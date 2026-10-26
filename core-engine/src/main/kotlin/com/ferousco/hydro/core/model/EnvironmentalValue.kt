package com.ferousco.hydro.core.model

import java.time.Instant

/**
 * Distinguishes observed measurements, future/issued model forecasts, historical reanalysis
 * estimates, and deterministically derived indicators.
 */
enum class ValueNature {
    /** Direct in-situ measurement or an assimilated current-conditions estimate. */
    OBSERVED,

    /** Future or issued numerical weather prediction value. */
    FORECAST,

    /** Model-generated historical archive value (e.g., ERA5 / ECMWF IFS reanalysis). */
    HISTORICAL_REANALYSIS,

    /** Deterministically computed indicator such as Heat Index. */
    DERIVED
}

/**
 * Explicit reason why an environmental variable is unavailable.
 */
enum class UnavailableReason {
    NOT_PROVIDED_BY_SOURCE,
    NULL_IN_PROVIDER_RESPONSE,
    FAILED_VALIDATION,
    NOT_REQUESTED,
    UNSUPPORTED_FOR_ENDPOINT
}

/**
 * A single strongly typed environmental quantity preserving its unit, valid timestamp,
 * geographic location, data source, and nature (observed, forecast, or derived).
 */
data class MeasuredQuantity<U : PhysicalUnit>(
    val value: Double,
    val unit: U,
    val validAt: Instant,
    val location: GeoLocation,
    val source: EnvironmentalDataSource,
    val nature: ValueNature
) {
    init {
        require(value.isFinite()) {
            "MeasuredQuantity value must be finite, got $value (${unit.symbol})."
        }
    }
}

/**
 * Explicit availability wrapper preventing missing weather measurements from being substituted
 * with zero or sentinel defaults.
 */
sealed interface FieldAvailability<out U : PhysicalUnit> {
    val isAvailable: Boolean

    data class Available<U : PhysicalUnit>(
        val quantity: MeasuredQuantity<U>
    ) : FieldAvailability<U> {
        override val isAvailable: Boolean = true
    }

    data class Unavailable(
        val reason: UnavailableReason,
        val detail: String? = null
    ) : FieldAvailability<Nothing> {
        override val isAvailable: Boolean = false
    }

    fun quantityOrNull(): MeasuredQuantity<@UnsafeVariance U>? = when (this) {
        is Available -> quantity
        is Unavailable -> null
    }
}

fun FieldAvailability<TemperatureUnit>.inUnitOrNull(target: TemperatureUnit): Double? = when (this) {
    is FieldAvailability.Available -> quantity.unit.convert(quantity.value, target)
    is FieldAvailability.Unavailable -> null
}

fun FieldAvailability<TemperatureUnit>.convertedTo(target: TemperatureUnit): FieldAvailability<TemperatureUnit> =
    when (this) {
        is FieldAvailability.Available -> if (quantity.unit == target) {
            this
        } else {
            FieldAvailability.Available(
                quantity.copy(
                    value = quantity.unit.convert(quantity.value, target),
                    unit = target
                )
            )
        }
        is FieldAvailability.Unavailable -> this
    }

fun FieldAvailability<SpeedUnit>.inUnitOrNull(target: SpeedUnit): Double? = when (this) {
    is FieldAvailability.Available -> quantity.unit.convert(quantity.value, target)
    is FieldAvailability.Unavailable -> null
}

fun FieldAvailability<SpeedUnit>.convertedTo(target: SpeedUnit): FieldAvailability<SpeedUnit> =
    when (this) {
        is FieldAvailability.Available -> if (quantity.unit == target) {
            this
        } else {
            FieldAvailability.Available(
                quantity.copy(
                    value = quantity.unit.convert(quantity.value, target),
                    unit = target
                )
            )
        }
        is FieldAvailability.Unavailable -> this
    }

fun <U : PhysicalUnit> FieldAvailability<U>.rawValueOrNull(): Double? = when (this) {
    is FieldAvailability.Available -> quantity.value
    is FieldAvailability.Unavailable -> null
}
