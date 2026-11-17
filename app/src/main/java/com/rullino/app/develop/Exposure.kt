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
    /** Stop da dare alla foto del telefono per riportarla a un'esposizione "media" (quando il telefono non ce l'ha fatta). */
    val base: Float = 0f,
)

/**
 * La macchina virtuale. In automatico (A) è una compatta con limiti veri
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
    /** Diaframma fisso quando si usa la ghiera dei tempi. */
    private const val MANUAL_APERTURE = 4.0

    /**
     * [offset] = quanto la foto del telefono è più chiara (+) o più scura (−) di un'esposizione media,
     * in stop (vedi [lumaOffset]). Di notte il telefono arriva al limite di tempo e ISO e la foto resta
     * scura anche se i dati EXIF "sembrano" giusti: la luce vera della scena è EXIF + offset.
     */
    fun errorStops(file: File, filmIso: Int, shutter: Double? = null, offset: Float = 0f): Float? = runCatching {
        val exif = ExifInterface(file.path)
        val t = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0)
        val n = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, -1.0)
        val iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1)
        if (t <= 0 || n <= 0 || iso <= 0) return null
        val ev100 = log2(n * n / t) - log2(iso / 100.0) + offset
        if (shutter != null) manualError(ev100, filmIso, shutter) else errorFor(ev100, filmIso)
    }.getOrNull()

    /** Grigio medio in luce lineare: l'esposimetro porta la media della scena qui. */
    private const val MID_GREY = 0.18

    /**
     * Di quanti stop un'immagine è più chiara o più scura del grigio medio (media pesata al centro,
     * come un esposimetro). Mezzo stop di tolleranza, così le foto normali di giorno non cambiano.
     */
    fun lumaOffset(meanLinear: Double): Float {
        if (meanLinear <= 0) return -4f
        val k = log2(meanLinear / MID_GREY)
        val dead = 0.5
        val v = if (k > 0) maxOf(k - dead, 0.0) else minOf(k + dead, 0.0)
        return v.toFloat().coerceIn(-4f, 1.5f)
    }

    /** Luminanza lineare media pesata al centro di una bitmap (campiona una griglia, non serve tutta). */
    fun meanLinear(bmp: android.graphics.Bitmap): Double {
        val w = bmp.width; val h = bmp.height
        if (w <= 0 || h <= 0) return MID_GREY
        val steps = 40
        var sum = 0.0; var weights = 0.0
        for (j in 0 until steps) for (i in 0 until steps) {
            val x = (i + 0.5) / steps; val y = (j + 0.5) / steps
            val p = bmp.getPixel((x * w).toInt().coerceIn(0, w - 1), (y * h).toInt().coerceIn(0, h - 1))
            val wgt = 1.0 + 1.5 * kotlin.math.exp(-((x - 0.5) * (x - 0.5) + (y - 0.5) * (y - 0.5)) / 0.05)
            sum += wgt * lumLin(android.graphics.Color.red(p), android.graphics.Color.green(p), android.graphics.Color.blue(p))
            weights += wgt
        }
        return sum / weights
    }

    /** Luminanza lineare di un pixel sRGB (0–255 per canale). */
    fun lumLin(r: Int, g: Int, b: Int): Double {
        fun lin(c: Int): Double { val v = c / 255.0; return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
    }

    /** L'offset di una posa, letto da una versione piccola del file. */
    fun lumaOffset(file: File): Float = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 256) sample *= 2
        val bmp = android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@runCatching 0f
        lumaOffset(meanLinear(bmp)).also { bmp.recycle() }
    }.getOrDefault(0f)

    /** Tempo giusto (secondi) a f/4 per una scena di luminosità [ev100] con una pellicola da [filmIso]. */
    fun neededShutter(ev100: Double, filmIso: Int): Double =
        MANUAL_APERTURE * MANUAL_APERTURE / (2.0.pow(ev100) * filmIso.coerceAtLeast(1) / 100.0)

    /** Luminosità della scena (EV a ISO 100) da tempo, diaframma e sensibilità del telefono. */
    fun ev100(exposureSeconds: Double, fNumber: Double, iso: Int): Double =
        log2(fNumber * fNumber / exposureSeconds) - log2(iso / 100.0)

    /** Tempo scelto a mano, diaframma fisso a f/4. */
    fun manualError(ev100: Double, filmIso: Int, shutter: Double): Float {
        val needed = MANUAL_APERTURE * MANUAL_APERTURE / (2.0.pow(ev100) * filmIso.coerceAtLeast(1) / 100.0)
        return log2(shutter / needed).toFloat().coerceIn(-5f, 5f)
    }

    /**
     * Il tempo che usa davvero la compatta in automatico: cerca l'esposizione giusta aprendo prima il
     * diaframma (fino a f/2.8) e poi allungando il tempo, sempre tra 1/500 e 1/30.
     */
    fun autoShutter(ev100: Double, filmIso: Int): Double {
        val needed = 1.0 / (2.0.pow(ev100) * filmIso.coerceAtLeast(1) / 100.0)   // t/N²
        val atF8 = needed * 8.0 * 8.0
        return when {
            atF8 in FASTEST..SLOWEST -> atF8
            atF8 < FASTEST -> FASTEST
            else -> (needed * WIDEST * WIDEST).coerceIn(FASTEST, SLOWEST)
        }
    }

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
