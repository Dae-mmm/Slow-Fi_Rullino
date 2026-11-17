package com.rullino.app.data

import com.rullino.app.R
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

    val inventory: Flow<List<InventoryEntity>> = db.inventory().all()

    fun frames(rollId: Long): Flow<List<FrameEntity>> = db.frames().forRollFlow(rollId)
    fun releases(rollId: Long): Flow<List<ReleaseEntity>> = db.releases().forRollFlow(rollId)
    fun exposuresOnFrame(rollId: Long, frame: Int): Flow<Int> = db.exposures().countOnFrameFlow(rollId, frame)

    suspend fun emulsion(id: String): EmulsionEntity? = db.emulsions().get(id)
    fun loadPreset(e: EmulsionEntity): Preset = installer.loadPreset(e)

    fun rollDir(rollId: Long) = File(context.filesDir, "rolls/$rollId")

    /* ---------------- Caricare e sganciare ---------------- */

    /* ---------------- Magazzino ---------------- */

    suspend fun addToInventory(emulsionId: String, n: Int) = db.withTransaction {
        val have = db.inventory().get(emulsionId)?.count ?: 0
        db.inventory().upsert(InventoryEntity(emulsionId, (have + n).coerceAtLeast(0)))
    }

    /** In macchina c'è posto se non c'è un rullino, o se quello caricato è ancora vergine (torna in magazzino). */
    private suspend fun makeRoomInCamera() {
        for (current in db.rolls().activeNow()) {
            if (current.shotCount == 0 && current.contestId != null) {
                // Il rullino del concorso non torna in magazzino: resta da parte, intatto.
                db.rolls().update(current.copy(state = RollState.SHELVED, wound = false))
            } else if (current.shotCount == 0) {
                db.rolls().delete(current)
                addToInventory(current.emulsionId, 1)
            } else throw IllegalStateException(context.getString(R.string.err_roll_in_camera))
        }
    }

    /** Carica un rullino preso dal magazzino. */
    suspend fun loadRoll(e: EmulsionEntity, exposures: Int): Long = db.withTransaction {
        makeRoomInCamera()
        val have = db.inventory().get(e.id)?.count ?: 0
        if (have <= 0) throw IllegalStateException(context.getString(R.string.err_no_rolls_of, e.name))
        db.inventory().upsert(InventoryEntity(e.id, have - 1))
        db.rolls().insert(
            RollEntity(
                emulsionId = e.id, emulsionName = e.name, iso = e.iso, exposures = exposures,
                isBw = e.isBw, loadedAt = System.currentTimeMillis(),
            )
        )
    }

    /** Carica in macchina il rullino regalato da un concorso (non passa dal magazzino). */
    suspend fun loadGiftRoll(e: EmulsionEntity, exposures: Int, contestId: String, ref: String): Long = db.withTransaction {
        makeRoomInCamera()
        db.rolls().insert(
            RollEntity(
                emulsionId = e.id, emulsionName = e.name, iso = e.iso, exposures = exposures,
                isBw = e.isBw, loadedAt = System.currentTimeMillis(), contestId = contestId, contestRef = ref,
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

    /** Toglie dalla macchina un rullino su cui non si è ancora scattato: torna in magazzino. */
    suspend fun unloadEmptyRoll(roll: RollEntity) = db.withTransaction {
        val fresh = db.rolls().get(roll.id) ?: return@withTransaction
        if (fresh.shotCount == 0 && fresh.contestId != null) {
            db.rolls().update(fresh.copy(state = RollState.SHELVED, wound = false))
        } else if (fresh.shotCount == 0) {
            db.rolls().delete(fresh)
            addToInventory(fresh.emulsionId, 1)
            rollDir(fresh.id).deleteRecursively()
        }
    }

    /** Sgancia e mette da parte, per finirlo più avanti. */
    suspend fun shelve(rollId: Long) {
        val r = db.rolls().get(rollId) ?: return
        if (r.state == RollState.LOADED && r.shotCount > 0) db.rolls().update(r.copy(state = RollState.SHELVED, wound = false))
    }

    /** Riavvolge e toglie dalla macchina (o da un rullino messo da parte): ora va portato al laboratorio. */
    suspend fun rewindOut(rollId: Long) {
        val r = db.rolls().get(rollId) ?: return
        if ((r.state == RollState.LOADED || r.state == RollState.SHELVED) && r.shotCount > 0) {
            db.rolls().update(r.copy(state = RollState.FINISHED, wound = false, finishedAt = System.currentTimeMillis()))
        }
    }

    /** Ritira i negativi al negozio, se sono pronti. */
    suspend fun pickUp(rollId: Long, ready: (RollEntity) -> Boolean): Boolean {
        val r = db.rolls().get(rollId) ?: return false
        if (r.state != RollState.DEVELOPED || r.pickedUp || !ready(r)) return false
        db.rolls().update(r.copy(pickedUp = true))
        return true
    }

    /**
     * Riavvolge di [frames] pose (o tutto se null): i prossimi scatti cadono su fotogrammi
     * già esposti e diventano doppie esposizioni.
     */
    suspend fun rewind(rollId: Long, frames: Int?) {
        val r = db.rolls().get(rollId) ?: return
        if (r.state != RollState.LOADED) return
        val target = if (frames == null) 1 else (r.cursor - frames).coerceAtLeast(1)
        // Tornati indietro si è già sulla posa: pronta per scattarci sopra (doppia esposizione).
        db.rolls().update(r.copy(cursor = target, wound = frames != null))
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

    suspend fun recordShot(rollId: Long, file: File, shutter: Double? = null) = db.withTransaction {
        val r = db.rolls().get(rollId) ?: return@withTransaction
        if (r.state != RollState.LOADED || !r.wound) return@withTransaction
        val frame = r.cursor
        val now = System.currentTimeMillis()
        db.exposures().insert(ExposureEntity(rollId = rollId, frame = frame, rawPath = file.absolutePath, takenAt = now, shutter = shutter))
        // A fine rullino resta in macchina: va riavvolto con la manovella.
        db.rolls().update(r.copy(cursor = frame + 1, shotCount = maxOf(r.shotCount, frame), wound = false))
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
            ?: throw IllegalStateException(context.getString(R.string.err_gallery_save))
        resolver.openOutputStream(uri)?.use { out -> File(frame.developedPath).inputStream().use { it.copyTo(out) } }
            ?: throw IllegalStateException(context.getString(R.string.err_print_write))
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
            db.releases().deleteRoll(rollId)
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
            db.releases().forRoll(rollId).filter { it.frame == f.frame }.forEach { File(it.signaturePath).delete() }
            db.releases().deleteFrame(rollId, f.frame)
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

    /* ---------------- Liberatorie ---------------- */

    suspend fun saveRelease(release: ReleaseEntity, signature: android.graphics.Bitmap): ReleaseEntity = withContext(Dispatchers.IO) {
        val dir = File(rollDir(release.rollId), "releases").apply { mkdirs() }
        val file = File(dir, "%02d_%d.png".format(release.frame, System.currentTimeMillis()))
        file.outputStream().use { signature.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val withSig = release.copy(signaturePath = file.absolutePath)
        val id = db.releases().insert(withSig)
        withSig.copy(id = id)
    }

    suspend fun deleteRelease(r: ReleaseEntity) {
        db.releases().delete(r)
        File(r.signaturePath).delete()
    }

    suspend fun deleteEmulsion(e: EmulsionEntity) {
        if (db.rolls().openWithEmulsion(e.id) > 0) throw IllegalStateException(context.getString(R.string.err_emulsion_in_use))
        db.emulsions().delete(e.id)
        File(e.dir).deleteRecursively()
    }

    fun folderName(roll: RollEntity): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ITALY).format(Date(roll.finishedAt ?: roll.loadedAt))
        val name = roll.emulsionName.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifBlank { "Rullino" }
        return "$name $date #${roll.id}"
    }
}
