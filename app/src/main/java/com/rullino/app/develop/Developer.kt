package com.rullino.app.develop

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.rullino.app.data.Repository
import com.rullino.app.data.RollEntity
import com.rullino.app.data.RollState
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.random.Random

data class DevelopProgress(val rollId: Long, val done: Int, val total: Int)

/**
 * Sviluppa un rullino: applica l'emulsione a ogni negativo e salva le foto in
 * Immagini/Rullino/<emulsione – data>. Tutto il lavoro OpenGL gira su un thread dedicato.
 */
class Developer(private val context: Context, private val repo: Repository) {

    private val glDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "sviluppo") }.asCoroutineDispatcher()
    private val mutex = Mutex()
    private val _progress = MutableStateFlow<DevelopProgress?>(null)
    val progress: StateFlow<DevelopProgress?> = _progress

    suspend fun develop(rollId: Long) = mutex.withLock {
        withContext(glDispatcher) {
            val db = repo.db
            val start = db.rolls().get(rollId) ?: return@withContext
            if (start.state == RollState.DEVELOPED || start.state == RollState.LOADED) return@withContext
            val emulsion = db.emulsions().get(start.emulsionId)
                ?: throw IllegalStateException("L'emulsione «${start.emulsionName}» non è più installata")
            val preset = repo.loadPreset(emulsion)
            db.rolls().update(start.copy(state = RollState.DEVELOPING))

            val shots = db.shots().forRoll(rollId)
            val folder = folderName(start)
            val renderer = FilmRenderer()
            try {
                shots.forEachIndexed { i, shot ->
                    _progress.value = DevelopProgress(rollId, i, shots.size)
                    if (shot.developedUri != null) return@forEachIndexed
                    val raw = File(shot.rawPath)
                    val src = decodeOriented(raw, renderer.maxSide)?.let { cropTo35mm(it) } ?: return@forEachIndexed
                    val out = renderer.render(src, preset, seed = Random.nextFloat() * 997f)
                    src.recycle()
                    val uri = saveToGallery(out, folder, "${folder}_${"%02d".format(shot.frame)}.jpg")
                    out.recycle()
                    db.shots().update(shot.copy(developedUri = uri.toString()))
                }
            } finally {
                renderer.release()
                _progress.value = null
            }
            val end = db.rolls().get(rollId) ?: return@withContext
            db.rolls().update(end.copy(state = RollState.DEVELOPED, developedAt = System.currentTimeMillis()))
        }
    }

    private fun folderName(roll: RollEntity): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ITALY).format(Date(roll.finishedAt ?: roll.loadedAt))
        val name = roll.emulsionName.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifBlank { "Rullino" }
        return "$name $date #${roll.id}"
    }

    /** Taglio 3:2 del fotogramma 35 mm, centrato (in verticale se lo scatto è verticale). */
    private fun cropTo35mm(bmp: Bitmap): Bitmap {
        val ratio = if (bmp.width >= bmp.height) 1.5f else 1f / 1.5f
        var w = bmp.width
        var h = (w / ratio).toInt()
        if (h > bmp.height) { h = bmp.height; w = (h * ratio).toInt() }
        if (w == bmp.width && h == bmp.height) return bmp
        val out = Bitmap.createBitmap(bmp, (bmp.width - w) / 2, (bmp.height - h) / 2, w, h)
        if (out !== bmp) bmp.recycle()
        return out
    }

    private fun decodeOriented(file: File, maxSide: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null

        val orientation = ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    private fun saveToGallery(bmp: Bitmap, folder: String, fileName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Rullino/$folder")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Impossibile salvare nella galleria")
        resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 94, it) }
            ?: throw IllegalStateException("Impossibile scrivere la foto")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
