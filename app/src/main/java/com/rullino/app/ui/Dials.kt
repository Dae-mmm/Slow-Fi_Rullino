package com.rullino.app.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rullino.app.develop.VirtualCamera
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin

/* ------------------------------------------------------------------ */
/* Ghiere a cilindro: i numeri sono stampati sul metallo e girano via   */
/* ai lati come su un anello vero, senza sovrapporsi.                    */
/* ------------------------------------------------------------------ */

/** Un segno sulla ghiera: posizione lungo la circonferenza (px dal centro) e scritta. */
private class Mark(val arc: Float, val text: String, val faded: Boolean = false, val dot: Boolean = false)

/**
 * Disegna un anello zigrinato visto di profilo. [scroll] è quanto è girato (px di circonferenza):
 * zigrinatura e numeri seguono la curvatura del cilindro (si stringono e scuriscono verso i bordi).
 */
private fun DrawScope.cylinderDial(scroll: Float, marks: List<Mark>, tf: Typeface?) {
    val w = size.width
    val h = size.height
    val c = w / 2f
    val r = w / 2f   // raggio del cilindro: i bordi della ghiera sono a 90°
    fun proj(arc: Float): Float? {
        val a = arc / r
        if (abs(a) >= Math.PI / 2) return null
        return c + r * sin(a)
    }

    val cr = CornerRadius(7.dp.toPx())
    drawRoundRect(Color(0xFF0A0909), Offset.Zero, Size(w, h), cr)
    clipRect {
        // Zigrinatura: righe fitte sopra e sotto, in prospettiva.
        val pitch = 4.dp.toPx()
        val band = h * 0.24f
        var s = -(scroll % pitch) - pitch * floor(r * 1.6f / pitch)
        while (s < r * 1.6f) {
            val x = proj(s)
            if (x != null) {
                val k = cos(s / r)
                val lw = (pitch * 0.45f * k).coerceAtLeast(0.6f)
                val shade = Color(0xFF3A3532).copy(alpha = 0.25f + 0.75f * k)
                drawRect(shade, Offset(x - lw / 2, 1f), Size(lw, band))
                drawRect(shade, Offset(x - lw / 2, h - band - 1f), Size(lw, band))
            }
            s += pitch
        }
        // Fascia liscia in mezzo, dove sono stampati i numeri.
        drawRect(
            Brush.verticalGradient(listOf(Color(0xFF1E1C1B), Color(0xFF161514), Color(0xFF121110))),
            Offset(0f, band + 1f), Size(w, h - 2 * band - 2f),
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12.sp.toPx()
            textAlign = Paint.Align.CENTER
            typeface = tf
        }
        val baseline = h / 2f + paint.textSize * 0.36f
        val nc = drawContext.canvas.nativeCanvas
        marks.forEach { m ->
            val a = (m.arc - scroll) / r
            if (abs(a) >= Math.PI / 2 * 0.97) return@forEach
            val x = c + r * sin(a)
            val k = cos(a).toFloat()
            // Stampato: crema opaco, nessun colore sul valore scelto; i lontani si perdono nel buio.
            val alpha = ((if (m.faded) 0.35f else 1f) * (0.15f + 0.85f * k * k) * 255).toInt().coerceIn(0, 255)
            paint.color = android.graphics.Color.argb(alpha, 226, 219, 208)
            nc.save()
            nc.scale(k.coerceAtLeast(0.05f), 1f, x, baseline)
            nc.drawText(m.text, x, baseline, paint)
            nc.restore()
            if (m.dot) drawCircle(Palette.Safelight.copy(alpha = k), 1.6f.dp.toPx(), Offset(x, h - band - 3.dp.toPx()))
        }
    }
    // Luce in alto, ombra in basso, e i bordi del cilindro che girano via.
    drawLine(Color(0xFF6A645F), Offset(4f, 1.5f), Offset(w - 4f, 1.5f), 1.5f)
    drawLine(Color(0xFF050505), Offset(4f, h - 1.5f), Offset(w - 4f, h - 1.5f), 3f)
    drawRect(Brush.horizontalGradient(listOf(Color(0xEE000000), Color.Transparent), 0f, w * 0.16f), Offset.Zero, Size(w * 0.16f, h))
    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xEE000000)), w * 0.84f, w), Offset(w * 0.84f, 0f), Size(w * 0.16f, h))
    // Indice fisso: una barretta sottile.
    val bw = 2.dp.toPx()
    drawRect(Palette.Safelight, Offset(c - bw / 2, 0f), Size(bw, h * 0.3f))
}

/* ---------------- Ghiera dei tempi ---------------- */

/**
 * Ghiera dei tempi con lo scatto elastico: trascinandola resta "attaccata" al valore,
 * poi salta al successivo; al rilascio torna in posizione con un piccolo rimbalzo.
 * [recommended] è l'indice del tempo che consiglia l'esposimetro (un puntino sotto il numero).
 */
@Composable
fun ShutterDial(index: Int, onIndex: (Int) -> Unit, modifier: Modifier = Modifier, recommended: Int? = null) {
    val view = LocalView.current
    val tf = monoTypeface
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 46.dp.toPx() }
    val last = SHUTTER_SPEEDS.size - 1
    val shown = remember { Animatable(index.toFloat()) }
    val raw = remember { mutableFloatStateOf(index.toFloat()) }
    LaunchedEffect(index) {
        if (abs(shown.value - index) > 0.01f && abs(raw.floatValue - index) > 0.5f) {
            raw.floatValue = index.toFloat()
            shown.animateTo(index.toFloat(), spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow))
        }
    }

    /** La posizione vista: vicino a un valore è calamitata, a metà strada scatta. */
    fun magnet(p: Float): Float {
        val n = round(p)
        val d = p - n
        return n + 0.5f * sign(d) * (2f * abs(d)).pow(2.6f)
    }

    Canvas(
        modifier
            .height(40.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { raw.floatValue = shown.value },
                    onDragEnd = {
                        val target = round(raw.floatValue).toInt().coerceIn(0, last)
                        raw.floatValue = target.toFloat()
                        onIndex(target)
                        scope.launch { shown.animateTo(target.toFloat(), spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMedium)) }
                    },
                ) { change, dx ->
                    change.consume()
                    val before = round(raw.floatValue).toInt()
                    raw.floatValue = (raw.floatValue - dx / step).coerceIn(-0.35f, last + 0.35f)
                    scope.launch { shown.snapTo(magnet(raw.floatValue)) }
                    val now = round(raw.floatValue).toInt().coerceIn(0, last)
                    if (now != before) { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onIndex(now) }
                }
            }
    ) {
        val marks = SHUTTER_SPEEDS.mapIndexed { i, s -> Mark(i * step, shutterMark(s), dot = i == recommended && i != index) }
        cylinderDial(shown.value * step, marks, tf)
    }
}

/* ---------------- Ghiera dello zoom ---------------- */

/** Ghiera delle focali, in scala logaritmica come sugli obiettivi; si aggancia alla focale incisa vicina. */
@Composable
fun ZoomRing(
    focal: Float,
    maxFocal: Float,
    onFocal: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val view = LocalView.current
    val tf = monoTypeface
    val scope = rememberCoroutineScope()
    val anim = remember { Animatable(focal) }
    val minF = FOCAL_MARKS.first().toFloat()
    val maxF = maxFocal.coerceAtLeast(minF)
    val pxPerDoubling = with(LocalDensity.current) { 110.dp.toPx() }
    val travel = remember { mutableFloatStateOf(0f) }
    val lastTick = remember { mutableFloatStateOf(0f) }
    fun lnf(x: Float) = ln(x.toDouble()).toFloat()
    fun arcOf(f: Float) = lnf(f) / lnf(2f) * pxPerDoubling

    Canvas(
        modifier
            .height(36.dp)
            .pointerInput(enabled, maxF) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val cur = anim.value
                        val near = FOCAL_MARKS.map { it.toFloat() }.filter { it <= maxF }.minByOrNull { abs(lnf(it) - lnf(cur)) }
                        if (near != null && abs(lnf(near) - lnf(cur)) < 0.12f) scope.launch {
                            anim.animateTo(near, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)) { onFocal(value) }
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        }
                    },
                ) { change, dx ->
                    change.consume()
                    val before = anim.value
                    val after = kotlin.math.exp((lnf(before) - dx / pxPerDoubling * lnf(2f)).toDouble()).toFloat().coerceIn(minF, maxF)
                    scope.launch { anim.snapTo(after) }
                    onFocal(after)
                    travel.floatValue += abs(dx)
                    if (travel.floatValue - lastTick.floatValue > 9f) { view.tick(); lastTick.floatValue = travel.floatValue }
                    FOCAL_MARKS.forEach { m ->
                        val mf = m.toFloat()
                        if ((before < mf && after >= mf) || (before > mf && after <= mf)) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                }
            }
    ) {
        val marks = FOCAL_MARKS.map { Mark(arcOf(it.toFloat()), it.toString(), faded = it > maxF + 0.5f) }
        cylinderDial(arcOf(anim.value), marks, tf)
    }
}

/* ---------------- Esposimetro ---------------- */

/**
 * Lettura dell'esposimetro per il tempo da usare.
 * [error] in stop (positivo = sovraesposto), [recommendedIndex] il tempo della ghiera più vicino a quello giusto.
 */
data class MeterReading(val error: Float, val recommendedIndex: Int?, val baseIndex: Int?)

/** Dalla luce della scena ([ev100]) e dalla pellicola: cosa dice l'esposimetro con il tempo scelto. */
fun meterReading(ev100: Double, filmIso: Int, shutterIndex: Int): MeterReading {
    val needed = VirtualCamera.neededShutter(ev100, filmIso)
    // Il tempo inciso più vicino a quello giusto (in stop).
    val rec = SHUTTER_SPEEDS.indices.filter { SHUTTER_SPEEDS[it] != null }
        .minByOrNull { abs(ln(SHUTTER_SPEEDS[it]!! / needed)) }
    val chosen = SHUTTER_SPEEDS.getOrNull(shutterIndex)
    return if (chosen == null) {
        // In automatico la compatta sceglie da sola: si vede solo dove sbaglia, centrato sul tempo consigliato.
        MeterReading(VirtualCamera.errorFor(ev100, filmIso), rec, rec)
    } else {
        MeterReading(VirtualCamera.manualError(ev100, filmIso, chosen), rec, shutterIndex)
    }
}

/**
 * Esposimetro a barretta, come nei mirini reflex: scala da −2 a +2 stop a terzi.
 * Sopra ogni stop intero è stampato il tempo che lo corregge, così la barretta indica direttamente
 * a quale tempo girare la ghiera. Oltre ±2 la barretta si ferma in fondo e lampeggia la freccia.
 */
@Composable
fun ExposureMeter(reading: MeterReading?, modifier: Modifier = Modifier) {
    val tf = monoTypeface
    Canvas(modifier.height(30.dp)) {
        val w = size.width
        val h = size.height
        val pad = 14.dp.toPx()
        val span = w - 2 * pad
        fun x(stop: Float) = pad + (stop + 2f) / 4f * span
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9.sp.toPx(); textAlign = Paint.Align.CENTER; typeface = tf
        }
        val nc = drawContext.canvas.nativeCanvas
        val tickTop = h * 0.48f
        // Scala: tacche a terzi di stop, lunghe sugli stop interi, più spessa sullo zero.
        for (i in -6..6) {
            val s = i / 3f
            val whole = i % 3 == 0
            val len = if (i == 0) h * 0.36f else if (whole) h * 0.26f else h * 0.14f
            drawLine(
                if (i == 0) Color(0xFFEBE6E0) else Color(0xAAEBE6E0),
                Offset(x(s), tickTop), Offset(x(s), tickTop + len),
                if (whole) 1.5f.dp.toPx() else 1f.dp.toPx(),
            )
        }
        // Sopra gli stop interi: il tempo che porta l'esposizione a zero da lì.
        val base = reading?.baseIndex
        for (k in -2..2) {
            val label = when {
                reading == null -> if (k == 0) "0" else (if (k > 0) "+$k" else "$k")
                base == null -> ""
                else -> SHUTTER_SPEEDS.getOrNull(base - k)?.let { shutterMark(it) } ?: ""
            }
            // Indice più basso = tempo più veloce: sovraesposto di k stop → k posizioni più veloce.
            paint.color = if (k == 0) android.graphics.Color.rgb(235, 230, 224) else android.graphics.Color.argb(170, 235, 230, 224)
            if (label.isNotEmpty() && label != "A") nc.drawText(label, x(k.toFloat()), h * 0.38f, paint)
        }
        // Segni − e + alle estremità.
        paint.color = android.graphics.Color.argb(150, 235, 230, 224)
        nc.drawText("−", pad * 0.35f, tickTop + h * 0.3f, paint)
        nc.drawText("+", w - pad * 0.35f, tickTop + h * 0.3f, paint)
        // La barretta.
        if (reading != null) {
            val e = reading.error.coerceIn(-2.15f, 2.15f)
            val bx = x(e)
            val bw = 3.dp.toPx()
            val over = abs(reading.error) > 2.1f
            val blink = !over || (System.currentTimeMillis() / 350) % 2 == 0L
            if (blink) drawRect(Palette.Safelight, Offset(bx - bw / 2, tickTop - 2.dp.toPx()), Size(bw, h - tickTop + 2.dp.toPx()))
        }
    }
}
