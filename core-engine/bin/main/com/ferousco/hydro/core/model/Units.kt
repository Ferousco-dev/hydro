package com.ferousco.hydro.core.model

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult

sealed interface PhysicalUnit {
    val symbol: String
}

enum class TemperatureUnit(override val symbol: String) : PhysicalUnit {
    CELSIUS("°C"),
    FAHRENHEIT("°F"),
    KELVIN("K");

    fun convert(value: Double, target: TemperatureUnit): Double {
        if (this == target) return value
        val celsius = when (this) {
            CELSIUS -> value
            FAHRENHEIT -> (value - 32.0) * (5.0 / 9.0)
            KELVIN -> value - 273.15
        }
        return when (target) {
            CELSIUS -> celsius
            FAHRENHEIT -> celsius * (9.0 / 5.0) + 32.0
            KELVIN -> celsius + 273.15
        }
    }

    companion object {
        const val MIN_AMBIENT_AIR_CELSIUS = -95.0
        const val MAX_AMBIENT_AIR_CELSIUS = 65.0
        const val MIN_THERMAL_INDEX_CELSIUS = -110.0
        const val MAX_THERMAL_INDEX_CELSIUS = 140.0

        fun fromProviderSymbol(raw: String): TemperatureUnit? = when (raw.trim()) {
            "°C", "C", "celsius", "degC" -> CELSIUS
            "°F", "F", "fahrenheit", "degF" -> FAHRENHEIT
            "K", "kelvin" -> KELVIN
            else -> null
        }

        fun validateAmbientAir(value: Double, unit: TemperatureUnit, field: String = "airTemperature"): EngineResult<Double> {
            if (!value.isFinite()) {
                return EngineResult.Failure(
                    EngineError.InvalidInput(field, "non_finite", "$field must be a finite number.")
                )
            }
            val celsius = unit.convert(value, CELSIUS)
            if (celsius !in MIN_AMBIENT_AIR_CELSIUS..MAX_AMBIENT_AIR_CELSIUS) {
                return EngineResult.Failure(
                    EngineError.InvalidInput(
                        field = field,
                        rejectedValueSummary = "$value ${unit.symbol}",
                        message = "$field ($value ${unit.symbol}) is outside plausible terrestrial range [$MIN_AMBIENT_AIR_CELSIUS, $MAX_AMBIENT_AIR_CELSIUS] °C."
                    )
                )
            }
            return EngineResult.Success(value)
        }

        fun validateThermalIndex(value: Double, unit: TemperatureUnit, field: String = "thermalIndex"): EngineResult<Double> {
            if (!value.isFinite()) {
                return EngineResult.Failure(
                    EngineError.InvalidInput(field, "non_finite", "$field must be a finite number.")
                )
            }
            val celsius = unit.convert(value, CELSIUS)
            if (celsius !in MIN_THERMAL_INDEX_CELSIUS..MAX_THERMAL_INDEX_CELSIUS) {
                return EngineResult.Failure(
                    EngineError.InvalidInput(
                        field = field,
                        rejectedValueSummary = "$value ${unit.symbol}",
                        message = "$field ($value ${unit.symbol}) is outside plausible thermal index range [$MIN_THERMAL_INDEX_CELSIUS, $MAX_THERMAL_INDEX_CELSIUS] °C."
                    )
                )
            }
            return EngineResult.Success(value)
        }
    }
}

enum class PercentageUnit(override val symbol: String) : PhysicalUnit {
    PERCENT("%");

    companion object {
        fun fromProviderSymbol(raw: String): PercentageUnit? = when (raw.trim()) {
            "%", "percent", "pct" -> PERCENT
            else -> null
        }

        fun validateRelativeHumidity(value: Double, field: String = "relativeHumidity"): EngineResult<Double> {
            if (!value.isFinite() || value !in 0.0..100.0) {
                return EngineResult.Failure(
                    EngineError.InvalidInput(
                        field = field,
                        rejectedValueSummary = value.toString(),
                        message = "Relative humidity must be finite and within [0.0, 100.0]%, got $value."
                    )
                )
            }
            return EngineResult.Success(value)
        }
    }
}

enum class SpeedUnit(
    override val symbol: String,
    private val metersPerSecondFactor: Double
) : PhysicalUnit {
    METERS_PER_SECOND("m/s", 1.0),
    KILOMETERS_PER_HOUR("km/h", 1.0 / 3.6),
    MILES_PER_HOUR("mph", 0.44704),
    KNOTS("kn", 1852.0 / 3600.0);

    fun convert(value: Double, target: SpeedUnit): Double {
        if (this == target) return value
        val ms = value * metersPerSecondFactor
        return ms / target.metersPerSecondFactor
    }

    companion object {
        const val MAX_SURFACE_WIND_MS = 150.0

        fun fromProviderSymbol(raw: String): SpeedUnit? = when (raw.trim().lowercase()) {
            "m/s", "ms", "mps" -> METERS_PER_SECOND
            "km/h", "kmh", "kph" -> KILOMETERS_PER_HOUR
            "mph", "mp/h" -> MILES_PER_HOUR
            "kn", "kt", "kts", "knots" -> KNOTS
            else -> null
        }

        fun validateWindSpeed(value: Double, unit: SpeedUnit, field: String = "windSpeed"): EngineResult<Double> {
            if (!value.isFinite() || value < 0.0) {
                return EngineResult.Failure(
                    EngineError.InvalidInput(
                        field = field,
                        rejectedValueSummary = "$value ${unit.symbol}",
                        message = "Wind speed must be finite and non-negative, got $value ${unit.symbol}."
                    )
                )
            }
            val ms = unit.convert(value, METERS_PER_SECOND)
            if (ms > MAX_SURFACE_WIND_MS) {
                return EngineResult.Failure(
                    EngineError.InvalidInput(
                        field = field,
                        rejectedValueSummary = "$value ${unit.symbol}",
                        message = "Wind speed ($ms m/s) exceeds plausible surface wind bound ($MAX_SURFACE_WIND_MS m/s)."
                    )
                )
            }
            return EngineResult.Success(value)
        }
    }
}

enum class IrradianceUnit(override val symbol: String) : PhysicalUnit {
    WATTS_PER_SQUARE_METER("W/m²");

    companion object {
        const val MAX_SURFACE_SOLAR_IRRADIANCE_WM2 = 2000.0

        fun fromProviderSymbol(raw: String): IrradianceUnit? = when (raw.trim()) {
            "W/m²", "W/m2", "W m-2", "wm2" -> WATTS_PER_SQUARE_METER
            else -> null
        }

        fun validateSolarIrradiance(value: Double, field: String = "solarRadiation"): EngineResult<Double> {
            if (!value.isFinite() || value !in 0.0..MAX_SURFACE_SOLAR_IRRADIANCE_WM2) {
                return EngineResult.Failure(
                    EngineError.InvalidInput(
                        field = field,
                        rejectedValueSummary = "$value W/m²",
                        message = "Solar irradiance must be finite and within [0.0, $MAX_SURFACE_SOLAR_IRRADIANCE_WM2] W/m², got $value."
                    )
                )
            }
            return EngineResult.Success(value)
        }
    }
}

enum class UvIndexUnit(override val symbol: String) : PhysicalUnit {
    WHO_INDEX("UVI");

    companion object {
        const val MAX_PLAUSIBLE_UV_INDEX = 25.0

        fun fromProviderSymbol(raw: String): UvIndexUnit? = when (raw.trim().lowercase()) {
            "", "uvi", "index", "uv_index" -> WHO_INDEX
            else -> null
        }

        fun validateUvIndex(value: Double, field: String = "uvIndex"): EngineResult<Double> {
            if (!value.isFinite() || value !in 0.0..MAX_PLAUSIBLE_UV_INDEX) {
                return EngineResult.Failure(
                    EngineError.InvalidInput(
                        field = field,
                        rejectedValueSummary = value.toString(),
                        message = "UV index must be finite and within [0.0, $MAX_PLAUSIBLE_UV_INDEX], got $value."
                    )
                )
            }
            return EngineResult.Success(value)
        }
    }
}
