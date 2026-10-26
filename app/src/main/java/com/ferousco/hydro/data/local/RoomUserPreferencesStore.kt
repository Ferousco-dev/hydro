package com.ferousco.hydro.data.local

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.error.map
import com.ferousco.hydro.core.preferences.UserPreferences
import com.ferousco.hydro.core.preferences.UserPreferencesCodec
import com.ferousco.hydro.core.preferences.UserPreferencesStore
import kotlinx.coroutines.CancellationException
import java.time.Clock

/**
 * Room-backed [UserPreferencesStore].
 *
 * Preferences survive process death, which matters because the deterministic notification policy
 * reads them on every alert. A missing row means "the user has not customised anything yet" and
 * yields defaults; a row that cannot be decoded is reported as a persistence failure instead of
 * being silently replaced with defaults, so a corrupt write is visible rather than papered over.
 */
class RoomUserPreferencesStore(
    private val dao: UserPreferencesDao,
    private val clock: Clock = Clock.systemUTC()
) : UserPreferencesStore {

    override suspend fun load(): EngineResult<UserPreferences> {
        val entity = runRoomSafely("load_user_preferences") {
            dao.getById(UserPreferencesEntity.SINGLETON_ROW_ID)
        }
        return when (entity) {
            is EngineResult.Failure -> entity
            is EngineResult.Success -> {
                val row = entity.value ?: return EngineResult.Success(UserPreferences())
                UserPreferencesCodec.decodeFromString(row.payloadJson)
            }
        }
    }

    override suspend fun save(preferences: UserPreferences): EngineResult<UserPreferences> {
        val stamped = preferences.copy(
            updatedAt = clock.instant(),
            schemaVersion = UserPreferences.CURRENT_SCHEMA_VERSION
        )
        val entity = UserPreferencesEntity(
            id = UserPreferencesEntity.SINGLETON_ROW_ID,
            payloadJson = UserPreferencesCodec.encodeToString(stamped),
            schemaVersion = stamped.schemaVersion,
            updatedAtEpochMillis = stamped.updatedAt?.toEpochMilli() ?: clock.millis()
        )
        return runRoomSafely("save_user_preferences") {
            dao.upsert(entity)
        }.map { stamped }
    }

    private suspend inline fun <T> runRoomSafely(
        operation: String,
        block: () -> T
    ): EngineResult<T> = try {
        EngineResult.Success(block())
    } catch (ce: CancellationException) {
        throw ce
    } catch (ex: Exception) {
        EngineResult.Failure(
            EngineError.PersistenceFailure(
                operation = operation,
                message = "Room database operation '$operation' failed: ${ex.message}",
                causeSummary = ex::class.simpleName
            )
        )
    }
}