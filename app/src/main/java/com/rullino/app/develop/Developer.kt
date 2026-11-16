package com.rullino.app.develop

import com.rullino.app.R
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import com.rullino.app.data.ExposureEntity
import com.rullino.app.data.FrameEntity
import com.rullino.app.data.Repository
import com.rullino.app.data.RollState
import com.rullino.app.data.Settings
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.random.Random

data class DevelopProgress(val rollId: Long, val done: Int, val total: Int)

/**
 * Sviluppa un rullino in negativi: per ogni fotogramma fonde le sue esposizioni
 * (doppia esposizione), applica l'emulsione e salva il positivo nella memoria privata.
 * In galleria va solo ciò che si stampa dal tavolo luminoso.
 * Tutto il lavoro OpenGL gira su un thread dedicato.
 */
class Developer(private val context: Context, private val repo: Repository, private val settings: Settings) {

    private val glDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "sviluppo") }.asCoroutineDispatcher()
    private val mutex = Mutex()
    private val _progress = MutableStateFlow<DevelopProgress?>(null)
    val progress: StateFlow<DevelopProgress?> = _progress

    suspend fun develop(rollId: Long) = mutex.withLock {
        withContext(glDispatcher) {
            val db = repo.db
            val start = db.rolls().get(rollId) ?: return@withContext
            if (start.state != RollState.FINISHED && start.state != RollState.DEVELOPING) return@withContext
            val emulsion = db.emulsions().get(start.emulsionId)
                ?: throw IllegalStateException(context.getString(R.string.dev_emulsion_gone, start.emulsionName))
            val preset = repo.loadPreset(emulsion)
            db.rolls().update(start.copy(state = RollState.DEVELOPING))

            val byFrame: Map<Int, List<ExposureEntity>> = db.exposures().forRoll(rollId).groupBy { it.frame }
            val already = db.frames().forRoll(rollId).associateBy { it.frame }
            val outDir = File(repo.rollDir(rollId), "dev").apply { mkdirs() }
            val renderer = FilmRenderer()
            try {
                var i = 0
                for ((frame, layers) in byFrame.toSortedMap()) {
                    _progress.value = DevelopProgress(rollId, i++, byFrame.size)
                    if (already[frame]?.let { File(it.developedPath).exists() } == true) continue
                    val merged = mergeExposures(layers.map { File(it.rawPath) }, renderer.maxSide) ?: continue
                    val out = renderer.render(merged, preset, seed = Random.nextFloat() * 997f)
                    merged.recycle()
                    val file = File(outDir, "%02d.jpg".format(frame))
                    file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 94, it) }
                    out.recycle()
                    db.frames().upsert(FrameEntity(rollId = rollId, frame = frame, developedPath = file.absolutePath, exposureCount = layers.size))
                }
            } finally {
                renderer.release()
                _progress.value = null
            }
            val end = db.rolls().get(rollId) ?: return@withContext
            val now = System.currentTimeMillis()
            // Lo sviluppo vero è già fatto: i negativi restano "al laboratorio" fino a readyAt.
            db.rolls().update(end.copy(state = RollState.DEVELOPED, developedAt = now, readyAt = now + settings.currentWaitMillis()))
        }
    }

    /**
     * Fonde le esposizioni di un fotogramma come fa la pellicola: la luce si somma,
     * quindi le parti chiare di uno scatto riempiono le parti scure dell'altro (fusione "screen").
     */
    private fun mergeExposures(files: List<File>, maxSide: Int): Bitmap? {
        val layers = files.mapNotNull { decodeOriented(it, maxSide)?.let { b -> cropTo35mm(b) } }
        if (layers.isEmpty()) return null
        val base = layers.first().copy(Bitmap.Config.ARGB_8888, true)
        if (layers.size > 1) {
            val canvas = Canvas(base)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
            val dst = Rect(0, 0, base.width, base.height)
            for (layer in layers.drop(1)) canvas.drawBitmap(layer, null, dst, paint)
        }
        layers.forEach { it.recycle() }
        return base
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
}
