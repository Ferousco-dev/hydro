package com.ferousco.hydro

import android.app.Application
import com.ferousco.hydro.core.HydroEngine
import com.ferousco.hydro.data.local.HydroDatabase
import com.ferousco.hydro.data.local.RoomEnvironmentalHistoryStore
import com.ferousco.hydro.logging.AndroidDiagnosticLogger

/**
 * Process-wide composition root for the Android app.
 *
 * Owns the single Room database and [HydroEngine] instance so the UI never constructs
 * network clients, database handles, or assessment engines on its own.
 */
class HydroApplication : Application() {

    val database: HydroDatabase by lazy {
        HydroDatabase.getInstance(this)
    }

    val engine: HydroEngine by lazy {
        HydroEngine.create(
            historyStore = RoomEnvironmentalHistoryStore(database.environmentalHistoryDao()),
            logger = AndroidDiagnosticLogger()
        )
    }
}
