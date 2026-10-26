package com.ferousco.hydro.core.model

/**
 * Describes how an environmental dataset or measurement was produced.
 */
enum class DataAcquisitionMethod {
    /** Direct measurement from an on-site weather station or sensor. */
    STATION_MEASUREMENT,

    /** Current/near-real-time gridded model assimilation (e.g., Open-Meteo 15-min model current conditions). */
    GRIDDED_MODEL_NOWCAST,

    /** Numerical weather prediction model forecast for a target validity window. */
    NUMERICAL_WEATHER_FORECAST,

    /** Historical meteorological reanalysis assimilating past observations onto a grid (e.g., ERA5 / IFS). */
    HISTORICAL_REANALYSIS,

    /** Deterministically derived inside Hydro's local analysis engine from validated inputs. */
    DETERMINISTIC_ENGINE_CALCULATION
}

/**
 * Provenance metadata describing the origin, dataset, model, and licensing of environmental data.
 */
data class EnvironmentalDataSource(
    val providerId: String,
    val providerName: String,
    val endpointOrDataset: String,
    val acquisitionMethod: DataAcquisitionMethod,
    val modelInfo: String? = null,
    val license: String,
    val attribution: String,
    val resolvedGridLocation: GeoLocation? = null
) {
    init {
        require(providerId.isNotBlank()) { "providerId must not be blank." }
        require(providerName.isNotBlank()) { "providerName must not be blank." }
        require(endpointOrDataset.isNotBlank()) { "endpointOrDataset must not be blank." }
    }

    companion object {
        const val OPEN_METEO_PROVIDER_ID = "open-meteo"
        const val OPEN_METEO_PROVIDER_NAME = "Open-Meteo"
        const val OPEN_METEO_CC_BY_LICENSE =
            "CC-BY 4.0 (Open-Meteo Free API for non-commercial use; commercial deployment requires a paid Open-Meteo subscription)"
        const val OPEN_METEO_ATTRIBUTION = "Weather data by Open-Meteo.com (CC-BY 4.0)"

        val HYDRO_DERIVED_ENGINE_SOURCE = EnvironmentalDataSource(
            providerId = "hydro-core-engine",
            providerName = "Hydro Deterministic Environmental Engine",
            endpointOrDataset = "local-deterministic-calculation",
            acquisitionMethod = DataAcquisitionMethod.DETERMINISTIC_ENGINE_CALCULATION,
            modelInfo = "NOAA/NWS SR 90-23 & WHO/WMO UVI",
            license = "Internal application computation",
            attribution = "Calculated locally by Hydro Core Engine"
        )
    }
}
