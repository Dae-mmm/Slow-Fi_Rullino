package com.rullino.app.develop

import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.ln
import kotlin.math.pow

/** Come sviluppare un fotogramma oltre al preset: errore di esposizione, push/pull, tipo di pellicola. */
data class DevelopLook(
    val exposureError: Float = 0f,
    val push: Int = 0,
    val slide: Boolean = false,
)

/**
 * La compatta virtuale: esposizione automatica, ma con limiti veri
 * (diaframma f/2.8–f/16, tempi 1/30–1/500). Il telefono fa da esposimetro: dai dati EXIF
 * dello scatto ricava la luce della scena e calcola di quanti stop sbaglia la compatta
 * con la sensibilità della pellicola caricata.
 */
object VirtualCamera {
    private const val WIDEST = 2.8
    private const val NARROWEST = 16.0
    private const val SLOWEST = 1.0 / 30
    private const val FASTEST = 1.0 / 500

    private fun log2(x: Double) = ln(x) / ln(2.0)

    /** Errore in stop (positivo = sovraesposto), o null se lo scatto non ha i dati. */
    fun errorStops(file: File, filmIso: Int): Float? = runCatching {
        val exif = ExifInterface(file.path)
        val t = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0)
        val n = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, -1.0)
        val iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1)
        if (t <= 0 || n <= 0 || iso <= 0) return null
        val ev100 = log2(n * n / t) - log2(iso / 100.0)
        errorFor(ev100, filmIso)
    }.getOrNull()

    /** Errore della compatta per una scena di luminosità [ev100] con una pellicola da [filmIso]. */
    fun errorFor(ev100: Double, filmIso: Int): Float {
        // Esposizione richiesta, espressa come t/N² (secondi per f-stop²).
        val needed = 1.0 / (2.0.pow(ev100) * filmIso.coerceAtLeast(1) / 100.0)
        val most = SLOWEST / (WIDEST * WIDEST)
        val least = FASTEST / (NARROWEST * NARROWEST)
        val used = needed.coerceIn(least, most)
        return log2(used / needed).toFloat().coerceIn(-5f, 5f)
    }

    /** Diapositiva: dal campo `process` del preset o, per le emulsioni vecchie, dal nome. */
    fun isSlide(process: String, id: String, name: String): Boolean = when (process.lowercase()) {
        "slide", "reversal", "positive" -> true
        "negative" -> false
        else -> listOf(id, name).any { s -> s.lowercase().let { "diapositiva" in it || "slide" in it || "chrome" in it } }
    }
}
