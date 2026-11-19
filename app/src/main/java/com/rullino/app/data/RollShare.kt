package com.rullino.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.room.withTransaction
import com.rullino.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class InvalidRollException(message: String) : Exception(message)

/**
 * Un rullino da passare a qualcuno: file .rullino (ZIP) con roll.json, l'emulsione e le pose
 * ancora da sviluppare. Chi lo apre se lo ritrova in Rullini, può continuare a scattarci sopra
 * (anche doppie esposizioni sulle tue pose) o mandarlo in sviluppo.
 */
object RollShare {
    const val FORMAT = "rullino"
    const val VERSION = 1
    const val MIMETYPE = "application/x-rullino"
    /** Lato lungo delle pose condivise: abbastanza per lo sviluppo, leggero da mandare in chat. */
    private const val MAX_SIDE = 2400
    private const val MAX_TOTAL = 200L * 1024 * 1024

    private val EXIF_TAGS = listOf(
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_ORIENTATION,
    )

    suspend fun export(context: Context, repo: Repository, roll: RollEntity, from: String?, shareId: String): File = withContext(Dispatchers.IO) {
        val emulsion = repo.emulsion(roll.emulsionId) ?: throw InvalidRollException(context.getString(R.string.share_no_emulsion))
        val preset = JSONObject(File(emulsion.dir, "preset.json").readText())
            .put("id", emulsion.id).put("name", emulsion.name).put("iso", emulsion.iso)
            .put("exposures", emulsion.exposures).put("type", emulsion.type).put("note", emulsion.note)
        val shots = repo.db.exposures().forRoll(roll.id)

        val dir = File(context.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val name = (roll.emulsionName.replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').ifBlank { "rullino" }) +
            "-${roll.shotCount}-${roll.exposures}.rullino"
        val out = File(dir, name)
        val list = JSONArray()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            stored(zip, "mimetype", MIMETYPE.toByteArray())
            shots.forEachIndexed { i, s ->
                val file = "shots/%02d_%02d.jpg".format(s.frame, i)
                val bytes = shrink(File(s.rawPath)) ?: return@forEachIndexed
                stored(zip, file, bytes)
                list.put(JSONObject().put("frame", s.frame).put("file", file).put("takenAt", s.takenAt).put("shutter", s.shutter ?: JSONObject.NULL).put("meterEv", s.meterEv ?: JSONObject.NULL))
            }
            val manifest = JSONObject()
                .put("format", FORMAT).put("version", VERSION)
                .put("shareId", shareId)
                .put("from", from ?: JSONObject.NULL)
                // Le emulsioni esclusive (premi) non viaggiano: chi riceve il rullino deve averle già.
                .put("emulsion", if (emulsion.source == "prize") JSONObject.NULL else preset)
                .put("roll", JSONObject()
                    .put("emulsionId", roll.emulsionId).put("emulsionName", roll.emulsionName)
                    .put("iso", roll.iso).put("exposures", roll.exposures).put("isBw", roll.isBw)
                    .put("cursor", roll.cursor).put("shotCount", roll.shotCount).put("push", roll.push)
                    .put("loadedAt", roll.loadedAt))
                .put("shots", list)
            zip.putNextEntry(ZipEntry("roll.json"))
            zip.write(manifest.toString(2).toByteArray())
            zip.closeEntry()
        }
        out
    }

    /** Apre un .rullino: installa l'emulsione se manca e mette il rullino in Rullini, scaricato. */
    /**
     * [claim] blocca il codice sul server ("ok" / "already_claimed" / "unknown"): si chiama dopo aver
     * controllato tutto il file e prima di scrivere il rullino, così un file vale una volta sola.
     */
    suspend fun import(
        context: Context, repo: Repository, input: InputStream,
        claim: suspend (String) -> String,
        catalogInstall: suspend (String) -> Boolean,
    ): RollEntity = withContext(Dispatchers.IO) {
        fun bad(): Nothing = throw InvalidRollException(context.getString(R.string.share_invalid))
        val tmp = File(context.cacheDir, "import-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            var manifest: JSONObject? = null
            var total = 0L
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val n = e.name
                    if (e.isDirectory) continue
                    if (n.startsWith("/") || n.split('/').any { it == ".." }) bad()
                    val bytes = zip.readBytes()
                    total += bytes.size
                    if (total > MAX_TOTAL) bad()
                    when {
                        n == "roll.json" -> manifest = runCatching { JSONObject(String(bytes)) }.getOrNull() ?: bad()
                        n.startsWith("shots/") && n.endsWith(".jpg") -> File(tmp, n.substringAfter('/')).writeBytes(bytes)
                    }
                }
            }
            val m = manifest ?: bad()
            if (m.optString("format") != FORMAT) bad()
            if (m.optInt("version", 1) > VERSION) throw InvalidRollException(context.getString(R.string.share_newer))
            val shareId = m.optString("shareId").ifBlank { throw InvalidRollException(context.getString(R.string.share_old)) }
            val em = m.optJSONObject("emulsion")
            val ro = m.optJSONObject("roll") ?: bad()
            val emulsionId = ro.optString("emulsionId").ifBlank { bad() }

            // Il rullino è mio e in viaggio? Riprenderlo vuol dire bloccare il codice e rimetterlo in Rullini.
            val mine = repo.db.rolls().allNow().firstOrNull { it.shareId == shareId }

            // L'emulsione: se non ce l'hai arriva con il rullino, o dal catalogo; le esclusive vanno già possedute.
            if (mine == null && repo.emulsion(emulsionId) == null) {
                if (em != null) repo.installer.install(ByteArrayInputStream(emulsionFile(emulsionId, em)), "shared")
                else if (!catalogInstall(emulsionId)) throw InvalidRollException(context.getString(R.string.share_need_emulsion))
            }

            when (claim(shareId)) {
                "ok" -> Unit
                "already_claimed" -> throw InvalidRollException(context.getString(R.string.share_already))
                else -> throw InvalidRollException(context.getString(R.string.share_unknown))
            }
            if (mine != null) {
                repo.db.rolls().update(mine.copy(state = RollState.SHELVED, shareId = null))
                return@withContext repo.db.rolls().get(mine.id)!!
            }

            repo.db.withTransaction {
                val id = repo.db.rolls().insert(
                    RollEntity(
                        emulsionId = emulsionId,
                        emulsionName = ro.optString("emulsionName", em.optString("name")),
                        iso = ro.optInt("iso", 400),
                        exposures = ro.optInt("exposures", 36),
                        isBw = ro.optBoolean("isBw"),
                        cursor = ro.optInt("cursor", 1),
                        shotCount = ro.optInt("shotCount", 0),
                        state = RollState.SHELVED,
                        loadedAt = ro.optLong("loadedAt", System.currentTimeMillis()),
                        push = ro.optInt("push", 0),
                    )
                )
                val rawDir = File(repo.rollDir(id), "raw").apply { mkdirs() }
                val shots = m.optJSONArray("shots") ?: JSONArray()
                for (i in 0 until shots.length()) {
                    val s = shots.optJSONObject(i) ?: continue
                    val src = File(tmp, s.optString("file").substringAfter('/'))
                    if (!src.exists()) continue
                    val dest = File(rawDir, src.name)
                    src.copyTo(dest, overwrite = true)
                    repo.db.exposures().insert(
                        ExposureEntity(
                            rollId = id, frame = s.optInt("frame"), rawPath = dest.absolutePath,
                            takenAt = s.optLong("takenAt", System.currentTimeMillis()),
                            shutter = if (s.isNull("shutter")) null else s.optDouble("shutter"),
                            meterEv = if (!s.has("meterEv") || s.isNull("meterEv")) null else s.optDouble("meterEv"),
                        )
                    )
                }
                repo.db.rolls().get(id)!!
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Un .emulsion minimo (mimetype, manifest, preset) costruito dai parametri arrivati col rullino. */
    fun emulsionFile(id: String, preset: JSONObject): ByteArray {
        val manifest = JSONObject()
            .put("format", "emulsion").put("formatVersion", 1).put("id", id)
            .put("name", preset.optString("name")).put("iso", preset.optInt("iso", 400))
            .put("exposures", preset.optInt("exposures", 36)).put("type", preset.optString("type", "color"))
            .put("note", preset.optString("note")).put("files", JSONObject().put("preset", "preset.json"))
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            stored(zip, "mimetype", EmulsionInstaller.MIMETYPE.toByteArray())
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("preset.json")); zip.write(preset.toString().toByteArray()); zip.closeEntry()
        }
        return out.toByteArray()
    }

    /** La posa ridotta per il viaggio, con i dati EXIF che servono allo sviluppo (esposizione e verso). */
    private fun shrink(src: File): ByteArray? {
        if (!src.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        val long = maxOf(bounds.outWidth, bounds.outHeight)
        if (long <= 0) return null
        var sample = 1
        while (long / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
        val tmp = File.createTempFile("shot", ".jpg", src.parentFile)
        try {
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            val from = ExifInterface(src.path)
            val to = ExifInterface(tmp.path)
            EXIF_TAGS.forEach { tag -> from.getAttribute(tag)?.let { to.setAttribute(tag, it) } }
            to.saveAttributes()
            return tmp.readBytes()
        } finally {
            tmp.delete()
            if (bmp !== decoded) bmp.recycle()
            decoded.recycle()
        }
    }

    private fun stored(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val e = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
        zip.putNextEntry(e); zip.write(bytes); zip.closeEntry()
    }
}
