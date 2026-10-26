package com.ferousco.hydro.data.local

import com.ferousco.hydro.core.error.EngineError
import com.ferousco.hydro.core.error.EngineResult
import com.ferousco.hydro.core.integration.NotificationSeverity
import com.ferousco.hydro.core.preferences.AlertPreferences
import com.ferousco.hydro.core.preferences.TemperatureUnitPreference
import com.ferousco.hydro.core.preferences.UserPreferences
import com.ferousco.hydro.core.preferences.UserPreferencesCodec
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoomUserPreferencesStoreTest {

    private class FakeDao : UserPreferencesDao {
        var failWithRuntimeError: Boolean = false
        var row: UserPreferencesEntity? = null
        var upsertCount: Int = 0

        private fun checkFailure() {
            if (failWithRuntimeError) throw IllegalStateException("Simulated SQLite disk I/O error")
        }

        override suspend fun getById(id: Int): UserPreferencesEntity? {
            checkFailure()
            return row?.takeIf { it.id == id }
        }

        override suspend fun upsert(entity: UserPreferencesEntity) {
            checkFailure()
            upsertCount++
            row = entity
        }

        override suspend fun clear() {
            checkFailure()
            row = null
        }
    }

    private class FixedClock(private val instant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = instant
    }

    private val now = Instant.parse("2026-10-26T12:00:00Z")

    @Test
    fun `missing row yields defaults without an error`() = runBlocking {
        val store = RoomUserPreferencesStore(FakeDao(), FixedClock(now))
        val loaded = store.load()
        assertTrue(loaded is EngineResult.Success)
        assertEquals(UserPreferences(), loaded.value)
    }

    @Test
    fun `saved preferences survive a reload with a stamped timestamp`() = runBlocking {
        val dao = FakeDao()
        val store = RoomUserPreferencesStore(dao, FixedClock(now))
        val preferences = UserPreferences(
            temperatureUnit = TemperatureUnitPreference.FAHRENHEIT,
            alerts = AlertPreferences(minimumSeverity = NotificationSeverity.WARNING)
        )

        val saved = store.save(preferences).getOrNull()
        assertNotNull(saved)
        assertEquals(now, saved.updatedAt)
        assertEquals(UserPreferences.CURRENT_SCHEMA_VERSION, saved.schemaVersion)
        assertEquals(1, dao.upsertCount)
        assertEquals(UserPreferencesEntity.SINGLETON_ROW_ID, dao.row?.id)

        val reloaded = store.load().getOrNull()
        assertEquals(saved, reloaded)
    }

    @Test
    fun `corrupt stored payload is reported instead of silently defaulting`() = runBlocking {
        val dao = FakeDao().apply {
            row = UserPreferencesEntity(
                id = UserPreferencesEntity.SINGLETON_ROW_ID,
                payloadJson = """{"units":""",
                schemaVersion = UserPreferences.CURRENT_SCHEMA_VERSION,
                updatedAtEpochMillis = now.toEpochMilli()
            )
        }
        val store = RoomUserPreferencesStore(dao, FixedClock(now))

        val loaded = store.load()
        assertTrue(loaded is EngineResult.Failure)
        assertTrue(loaded.error is EngineError.PersistenceFailure)
    }

    @Test
    fun `a payload from a newer app version is rejected, not downgraded`() = runBlocking {
        val payload = UserPreferencesCodec.encodeToString(UserPreferences())
            .replace("\"schema_version\":1", "\"schema_version\":99")
        val dao = FakeDao().apply {
            row = UserPreferencesEntity(
                id = UserPreferencesEntity.SINGLETON_ROW_ID,
                payloadJson = payload,
                schemaVersion = 99,
                updatedAtEpochMillis = now.toEpochMilli()
            )
        }
        val store = RoomUserPreferencesStore(dao, FixedClock(now))

        val loaded = store.load()
        assertTrue(loaded is EngineResult.Failure)
        assertTrue(loaded.error is EngineError.PersistenceFailure)
    }

    @Test
    fun `database failures surface as persistence failures`() = runBlocking {
        val dao = FakeDao().apply { failWithRuntimeError = true }
        val store = RoomUserPreferencesStore(dao, FixedClock(now))

        val loaded = store.load()
        assertTrue(loaded is EngineResult.Failure)
        assertTrue(loaded.error is EngineError.PersistenceFailure)

        val saved = store.save(UserPreferences())
        assertTrue(saved is EngineResult.Failure)
        assertTrue(saved.error is EngineError.PersistenceFailure)
    }
}
