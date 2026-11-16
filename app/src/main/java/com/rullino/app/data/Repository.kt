package com.rullino.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.room.withTransaction
import com.rullino.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Repository(private val context: Context, val db: AppDb) {

    val installer = EmulsionInstaller(context, db)
    val catalog = CatalogClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)

    val emulsions: Flow<List<EmulsionEntity>> = db.emulsions().all()
    val activeRoll: Flow<RollEntity?> = db.rolls().active()
    val rolls: Flow<List<RollEntity>> = db.rolls().all()

    fun frames(rollId: Long): Flow<List<FrameEntity>> = db.frames().forRollFlow(rollId)
    fun exposuresOnFrame(rollId: Long, frame: Int): Flow<Int> = db.exposures().countOnFrameFlow(rollId, frame)

    suspend fun emulsion(id: String): EmulsionEntity? = db.emulsions().get(id)
    fun loadPreset(e: EmulsionEntity): Preset = installer.loadPreset(e)

    fun rollDir(rollId: Long) = File(context.filesDir, "rolls/$rollId")

    /* ---------------- Caricare e sganciare ---------------- */

    /** In macchina c'è posto se non c'è un rullino, o se quello caricato è ancora vergine (lo toglie). */
    private suspend fun makeRoomInCamera() {
        for (current in db.rolls().activeNow()) {
            if (current.state == RollState.LOADED && current.shotCount == 0) db.rolls().delete(current)
            else throw IllegalStateException("C'è già un rullino in macchina: sgancialo prima")
        }
    }

    suspend fun loadRoll(e: EmulsionEntity, exposures: Int): Long = db.withTransaction {
        makeRoomInCamera()
        db.rolls().insert(
            RollEntity(
                emulsionId = e.id, emulsionName = e.name, iso = e.iso, exposures = exposures,
                isBw = e.isBw, loadedAt = System.currentTimeMillis(),
            )
        )
    }

    /** Ricarica un rullino messo da parte: riprende dal fotogramma dove era rimasto. */
    suspend fun reloadRoll(rollId: Long) = db.withTransaction {
        val r = db.rolls().get(rollId) ?: return@withTransaction
        if (r.state != RollState.SHELVED) return@withTransaction
        makeRoomInCamera()
        db.rolls().update(r.copy(state = RollState.LOADED, wound = false))
    }

    /** Toglie dalla macchina un rullino su cui non si è ancora scattato. */
    suspend fun unloadEmptyRoll(roll: RollEntity) {
        val fresh = db.rolls().get(roll.id) ?: return
        if (fresh.shotCount == 0) { db.rolls().delete(fresh); rollDir(fresh.id).deleteRecursively() }
    }

    /** Sgancia e mette da parte, per finirlo più avanti. */
    suspend fun shelve(rollId: Long) {
        val r = db.rolls().get(rollId) ?: return
        if (r.state == RollState.LOADED && r.shotCount > 0) db.rolls().update(r.copy(state = RollState.SHELVED, wound = false))
    }

    /** Sgancia per sviluppare subito con le pose fatte. */
    suspend fun finishNow(rollId: Long) {
        val r = db.rolls().get(rollId) ?: return
        if ((r.state == RollState.LOADED || r.state == RollState.SHELVED) && r.shotCount > 0) {
            db.rolls().update(r.copy(state = RollState.FINISHED, wound = false, finishedAt = System.currentTimeMillis()))
        }
    }

    /**
     * Riavvolge di [frames] pose (o tutto se null): i prossimi scatti cadono su fotogrammi
     * già esposti e diventano doppie esposizioni.
     */
    suspend fun rewind(rollId: Long, frames: Int?) {
        val r = db.rolls().get(rollId) ?: return
        if (r.state != RollState.LOADED) return
        val target = if (frames == null) 1 else (r.cursor - frames).coerceAtLeast(1)
        db.rolls().update(r.copy(cursor = target, wound = false))
    }

    suspend fun wind(rollId: Long) {
        val r = db.rolls().get(rollId) ?: return
        if (r.canWind) db.rolls().update(r.copy(wound = true))
    }

    /* ---------------- Scattare ---------------- */

    fun rawFileFor(roll: RollEntity): File {
        val dir = File(rollDir(roll.id), "raw").apply { mkdirs() }
        return File(dir, "%02d_%d.jpg".format(roll.cursor, System.currentTimeMillis()))
    }

    suspend fun recordShot(rollId: Long, file: File) = db.withTransaction {
        val r = db.rolls().get(rollId) ?: return@withTransaction
        if (r.state != RollState.LOADED || !r.wound) return@withTransaction
        val frame = r.cursor
        val now = System.currentTimeMillis()
        db.exposures().insert(ExposureEntity(rollId = rollId, frame = frame, rawPath = file.absolutePath, takenAt = now))
        val next = frame + 1
        val finished = next > r.exposures
        db.rolls().update(
            r.copy(
                cursor = next,
                shotCount = maxOf(r.shotCount, frame),
                wound = false,
                state = if (finished) RollState.FINISHED else RollState.LOADED,
                finishedAt = if (finished) now else null,
            )
        )
    }

    /* ---------------- Stampa e cancellazione ---------------- */

    /** Stampa: copia il positivo in galleria (Immagini/Rullino/…). */
    suspend fun print(roll: RollEntity, frame: FrameEntity): Uri = withContext(Dispatchers.IO) {
        val folder = folderName(roll)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${folder}_${"%02d".format(frame.frame)}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Rullino/$folder")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Impossibile salvare nella galleria")
        resolver.openOutputStream(uri)?.use { out -> File(frame.developedPath).inputStream().use { it.copyTo(out) } }
            ?: throw IllegalStateException("Impossibile scrivere la stampa")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        db.frames().upsert(frame.copy(printUri = uri.toString()))
        uri
    }

    /**
     * Cancella un intero rullino: negativi, pose e stampe in galleria.
     * Restituisce le stampe che Android non ci lascia cancellare senza conferma.
     */
    suspend fun deleteRoll(rollId: Long): List<Uri> = withContext(Dispatchers.IO) {
        val r = db.rolls().get(rollId) ?: return@withContext emptyList()
        val prints = db.frames().forRoll(rollId).mapNotNull { it.printUri?.let(Uri::parse) }
        db.withTransaction {
            db.frames().deleteRoll(rollId)
            db.exposures().deleteRoll(rollId)
            db.rolls().delete(r)
        }
        rollDir(rollId).deleteRecursively()
        deleteFromGallery(prints)
    }

    /**
     * Taglio dei negativi: tiene solo i fotogrammi in [keep], il resto del rullino
     * (negativi, pose e stampe) viene eliminato.
     */
    suspend fun cutKeeping(rollId: Long, keep: Set<Int>): List<Uri> = withContext(Dispatchers.IO) {
        val frames = db.frames().forRoll(rollId)
        val drop = frames.filter { it.frame !in keep }
        if (drop.size == frames.size) return@withContext deleteRoll(rollId)
        val exposures = db.exposures().forRoll(rollId)
        val prints = drop.mapNotNull { it.printUri?.let(Uri::parse) }
        for (f in drop) {
            File(f.developedPath).delete()
            exposures.filter { it.frame == f.frame }.forEach { File(it.rawPath).delete() }
            db.frames().deleteFrame(rollId, f.frame)
            db.exposures().deleteFrame(rollId, f.frame)
        }
        deleteFromGallery(prints)
    }

    /** Prova a cancellare dalla galleria; restituisce quelle che richiedono la conferma dell'utente. */
    private fun deleteFromGallery(uris: List<Uri>): List<Uri> {
        val needConfirm = ArrayList<Uri>()
        for (u in uris) {
            try {
                context.contentResolver.delete(u, null, null)
            } catch (e: SecurityException) {
                needConfirm += u
            }
        }
        return needConfirm
    }

    suspend fun deleteEmulsion(e: EmulsionEntity) {
        if (db.rolls().openWithEmulsion(e.id) > 0) throw IllegalStateException("C'è un rullino ancora da sviluppare con questa emulsione")
        db.emulsions().delete(e.id)
        File(e.dir).deleteRecursively()
    }

    fun folderName(roll: RollEntity): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ITALY).format(Date(roll.finishedAt ?: roll.loadedAt))
        val name = roll.emulsionName.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifBlank { "Rullino" }
        return "$name $date #${roll.id}"
    }
}
