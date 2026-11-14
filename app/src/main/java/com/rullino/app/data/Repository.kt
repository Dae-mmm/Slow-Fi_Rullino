package com.rullino.app.data

import android.content.Context
import androidx.room.withTransaction
import com.rullino.app.BuildConfig
import kotlinx.coroutines.flow.Flow
import java.io.File

class Repository(private val context: Context, val db: AppDb) {

    val installer = EmulsionInstaller(context, db)
    val catalog = CatalogClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)

    val emulsions: Flow<List<EmulsionEntity>> = db.emulsions().all()
    val activeRoll: Flow<RollEntity?> = db.rolls().active()
    val rolls: Flow<List<RollEntity>> = db.rolls().all()

    fun shots(rollId: Long): Flow<List<ShotEntity>> = db.shots().forRollFlow(rollId)

    suspend fun emulsion(id: String): EmulsionEntity? = db.emulsions().get(id)

    fun loadPreset(e: EmulsionEntity): Preset = installer.loadPreset(e)

    /** Carica un rullino nuovo. Si può sostituire solo un rullino ancora vuoto. */
    suspend fun loadRoll(e: EmulsionEntity, exposures: Int): Long = db.withTransaction {
        for (current in db.rolls().activeNow()) {
            // Un rullino caricato ma mai usato si può sostituire; uno con delle pose no.
            if (current.shotCount == 0 && current.state == RollState.LOADED) db.rolls().delete(current)
            else throw IllegalStateException("C'è già un rullino in macchina")
        }
        db.rolls().insert(
            RollEntity(
                emulsionId = e.id,
                emulsionName = e.name,
                iso = e.iso,
                exposures = exposures,
                loadedAt = System.currentTimeMillis(),
            )
        )
    }

    /** Toglie dalla macchina un rullino su cui non si è ancora scattato. */
    suspend fun unloadEmptyRoll(roll: RollEntity) {
        val fresh = db.rolls().get(roll.id) ?: return
        if (fresh.shotCount == 0) db.rolls().delete(fresh)
    }

    suspend fun wind(rollId: Long) {
        val r = db.rolls().get(rollId) ?: return
        if (r.state == RollState.LOADED && !r.wound && r.shotCount < r.exposures) {
            db.rolls().update(r.copy(wound = true))
        }
    }

    fun rawFileFor(rollId: Long, frame: Int): File =
        File(context.filesDir, "rolls/$rollId/${"%02d".format(frame)}.jpg").apply { parentFile?.mkdirs() }

    suspend fun recordShot(rollId: Long, file: File) = db.withTransaction {
        val r = db.rolls().get(rollId) ?: return@withTransaction
        if (r.state != RollState.LOADED) return@withTransaction
        val frame = r.shotCount + 1
        val now = System.currentTimeMillis()
        db.shots().insert(ShotEntity(rollId = rollId, frame = frame, rawPath = file.absolutePath, takenAt = now))
        val finished = frame >= r.exposures
        db.rolls().update(
            r.copy(
                shotCount = frame,
                wound = false,
                state = if (finished) RollState.FINISHED else RollState.LOADED,
                finishedAt = if (finished) now else null,
            )
        )
    }

    suspend fun deleteEmulsion(e: EmulsionEntity) {
        db.emulsions().delete(e.id)
        File(e.dir).deleteRecursively()
    }
}
