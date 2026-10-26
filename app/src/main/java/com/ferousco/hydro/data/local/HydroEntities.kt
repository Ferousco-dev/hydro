package com.ferousco.hydro.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "environmental_records",
    indices = [
        Index(value = ["dataType", "validAtEpochMillis"]),
        Index(value = ["latitude", "longitude", "validAtEpochMillis"]),
        Index(value = ["retrievedAtEpochMillis"])
    ]
)
data class EnvironmentalRecordEntity(
    @PrimaryKey
    val id: String,
    val dataType: String,
    val observationKind: String?,
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double?,
    val locationLabel: String?,
    val timeZoneId: String,
    val providerId: String,
    val providerName: String,
    val endpointOrDataset: String,
    val acquisitionMethod: String,
    val modelInfo: String?,
    val license: String,
    val attribution: String,
    val gridLatitude: Double?,
    val gridLongitude: Double?,
    val gridElevationMeters: Double?,
    val validAtEpochMillis: Long,
    val retrievedAtEpochMillis: Long,
    val airTempAvailable: Boolean,
    val airTempValue: Double?,
    val airTempUnit: String?,
    val airTempNature: String?,
    val airTempUnavailableReason: String?,
    val airTempUnavailableDetail: String?,
    val rhAvailable: Boolean,
    val rhValue: Double?,
    val rhUnit: String?,
    val rhNature: String?,
    val rhUnavailableReason: String?,
    val rhUnavailableDetail: String?,
    val windAvailable: Boolean,
    val windValue: Double?,
    val windUnit: String?,
    val windNature: String?,
    val windUnavailableReason: String?,
    val windUnavailableDetail: String?,
    val feelsLikeAvailable: Boolean,
    val feelsLikeValue: Double?,
    val feelsLikeUnit: String?,
    val feelsLikeNature: String?,
    val feelsLikeUnavailableReason: String?,
    val feelsLikeUnavailableDetail: String?,
    val uvAvailable: Boolean,
    val uvValue: Double?,
    val uvUnit: String?,
    val uvNature: String?,
    val uvUnavailableReason: String?,
    val uvUnavailableDetail: String?,
    val solarAvailable: Boolean,
    val solarValue: Double?,
    val solarUnit: String?,
    val solarNature: String?,
    val solarUnavailableReason: String?,
    val solarUnavailableDetail: String?,
    val isDaylight: Boolean?,
    val wmoWeatherCode: Int?
)

@Entity(
    tableName = "heat_assessment_records",
    indices = [
        Index(value = ["sourceDataType", "validAtEpochMillis"]),
        Index(value = ["linkedEnvironmentalRecordId"]),
        Index(value = ["assessedAtEpochMillis"])
    ]
)
data class HeatAssessmentEntity(
    @PrimaryKey
    val id: String,
    val linkedEnvironmentalRecordId: String?,
    val sourceDataType: String,
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double?,
    val locationLabel: String?,
    val timeZoneId: String,
    val providerId: String,
    val providerName: String,
    val endpointOrDataset: String,
    val acquisitionMethod: String,
    val modelInfo: String?,
    val license: String,
    val attribution: String,
    val validAtEpochMillis: Long,
    val assessedAtEpochMillis: Long,
    val retrievedAtEpochMillis: Long,
    val methodId: String,
    val methodVersion: String,
    val completionStatus: String,
    val category: String?,
    val uvCategory: String?,
    val heatIndexCelsius: Double?,
    val airTemperatureValue: Double?,
    val airTemperatureUnit: String?,
    val relativeHumidityPercent: Double?,
    val windSpeedValue: Double?,
    val windSpeedUnit: String?,
    val uvIndexValue: Double?,
    val solarRadiationWm2: Double?,
    val missingVariablesCsv: String,
    val explanation: String
)

/**
 * Single-row table holding the user's JSON-encoded preferences.
 *
 * The payload is stored as versioned JSON rather than one column per field so a schema addition
 * does not require a migration just to add a preference. [schemaVersion] is duplicated as a column
 * so diagnostics can inspect the stored shape without decoding the payload.
 */
@Entity(tableName = "user_preferences")
data class UserPreferencesEntity(
    @PrimaryKey
    val id: Int,
    val payloadJson: String,
    val schemaVersion: Int,
    val updatedAtEpochMillis: Long
) {
    companion object {
        const val SINGLETON_ROW_ID = 0
    }
}
