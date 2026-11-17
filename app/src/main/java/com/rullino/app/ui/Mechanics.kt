package com.rullino.app.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.offset
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

fun View.tick() = performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
fun View.confirm() = performHapticFeedback(
    if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
)
fun View.reject() = performHapticFeedback(
    if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
)

/* ------------------------------------------------------------------ */
/* Materiali del corpo macchina                                        */
/* ------------------------------------------------------------------ */

/** Metallo spazzolato della calotta superiore. */
fun Modifier.brushedMetal(): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(listOf(Color(0xFFD9D5CF), Color(0xFFB3AEA7), Color(0xFF8E8983))))
    val rnd = Random(7)
    var y = 0f
    while (y < size.height) {
        drawLine(Color.White.copy(alpha = 0.05f + rnd.nextFloat() * 0.06f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += 2f + rnd.nextFloat() * 3f
    }
    drawLine(Color(0xFF5E5A55), Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), strokeWidth = 2f)
}

/** Similpelle del corpo, con la sua grana. */
fun Modifier.leatherette(): Modifier = drawBehind {
    drawRect(Color(0xFF1B1A19))
    val rnd = Random(11)
    val n = (size.width * size.height / 90f).toInt().coerceAtMost(9000)
    repeat(n) {
        val c = if (rnd.nextBoolean()) Color(0xFF242322) else Color(0xFF131212)
        drawCircle(c, radius = 1f + rnd.nextFloat() * 1.6f, center = Offset(rnd.nextFloat() * size.width, rnd.nextFloat() * size.height))
    }
}

/* ------------------------------------------------------------------ */
/* Leva di avanzamento, vista di profilo sulla calotta                 */
/* ------------------------------------------------------------------ */

/** Corsa della leva in gradi: parte puntata a sinistra e ruota verso chi guarda. */
private const val LEVER_DEG = 120f

/** Il pomello si muove più del dito: basta una passata corta per caricare. */
private const val LEVER_GAIN = 1.6f

/**
 * La leva è imperniata a destra e il braccio punta a sinistra. Trascinando il pomello
 * da sinistra verso destra il braccio ruota sul piano della calotta: visto di profilo
 * si accorcia, passa sopra il perno e finisce a destra. A fine corsa la pellicola avanza.
 */
@Composable
fun AdvanceLever(
    enabled: Boolean,
    onAdvanced: () -> Unit,
    onProgress: (Float) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 240.dp,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val lastTick = remember { mutableIntStateOf(0) }
    val tipX = remember { mutableFloatStateOf(Float.NaN) }

    fun geometry(w: Float, h: Float): Triple<Float, Float, Float> {
        val pivotX = w * 0.72f
        val armLen = w * 0.44f
        val pivotY = h * 0.58f
        return Triple(pivotX, armLen, pivotY)
    }

    fun release() {
        scope.launch {
            val complete = progress.value >= 0.95f
            if (complete) {
                view.confirm()
                onAdvanced()
            }
            lastTick.intValue = 0
            tipX.floatValue = Float.NaN
            // La molla riporta la leva a riposo.
            progress.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)) {
                onProgress(if (complete) 0f else value)
            }
            onProgress(0f)
        }
    }

    Canvas(
        modifier
            .size(width, 64.dp)
            .pointerInput(enabled) {
                if (!enabled) {
                    detectHorizontalDragGestures(onDragStart = { view.reject() }) { change, _ -> change.consume() }
                } else detectHorizontalDragGestures(
                    onDragStart = {
                        val (pivotX, armLen, _) = geometry(size.width.toFloat(), size.height.toFloat())
                        tipX.floatValue = pivotX - armLen * cos(Math.toRadians((progress.value * LEVER_DEG).toDouble())).toFloat()
                    },
                    onDragEnd = { release() },
                    onDragCancel = { release() },
                ) { change, dx ->
                    change.consume()
                    val (pivotX, armLen, _) = geometry(size.width.toFloat(), size.height.toFloat())
                    val minCos = cos(Math.toRadians(LEVER_DEG.toDouble())).toFloat()
                    val x = (tipX.floatValue + dx * LEVER_GAIN).coerceIn(pivotX - armLen, pivotX - armLen * minCos)
                    tipX.floatValue = x
                    val theta = acos(((pivotX - x) / armLen).coerceIn(minCos, 1f))
                    val next = (Math.toDegrees(theta.toDouble()).toFloat() / LEVER_DEG).coerceIn(0f, 1f)
                    scope.launch { progress.snapTo(next) }
                    onProgress(next)
                    val t = (next * 10).toInt()
                    if (t > lastTick.intValue) view.tick()
                    lastTick.intValue = t
                }
            }
    ) {
        val (pivotX, armLen, pivotY) = geometry(size.width, size.height)
        val theta = (progress.value * LEVER_DEG) * (PI / 180.0).toFloat()
        val depth = sin(theta) // quanto il braccio viene verso chi guarda
        val tip = Offset(pivotX - armLen * cos(theta), pivotY + depth * 5.dp.toPx())
        val metal = if (enabled) Color(0xFFE4E0DA) else Color(0xFF9A958F)
        val shade = if (enabled) Color(0xFF8F8A84) else Color(0xFF6A6560)

        // Collare del perno appoggiato sulla calotta
        drawOval(Color(0xFF6E6964), Offset(pivotX - 22.dp.toPx(), pivotY - 4.dp.toPx()), Size(44.dp.toPx(), 14.dp.toPx()))
        drawRect(Brush.verticalGradient(listOf(Color(0xFFCFCAC4), Color(0xFF8A857F))), Offset(pivotX - 22.dp.toPx(), pivotY - 12.dp.toPx()), Size(44.dp.toPx(), 10.dp.toPx()))
        drawOval(Color(0xFFE9E5DF), Offset(pivotX - 22.dp.toPx(), pivotY - 17.dp.toPx()), Size(44.dp.toPx(), 10.dp.toPx()))

        // Braccio: più spesso quando ruota verso di noi
        val thick = (5f + depth * 3f).dp.toPx()
        val base = Offset(pivotX, pivotY - 13.dp.toPx())
        val tipTop = Offset(tip.x, tip.y - 13.dp.toPx())
        val arm = Path().apply {
            moveTo(base.x, base.y - thick / 2f)
            lineTo(tipTop.x, tipTop.y - thick / 2f)
            lineTo(tipTop.x, tipTop.y + thick / 2f)
            lineTo(base.x, base.y + thick / 2f)
            close()
        }
        drawPath(arm, Brush.verticalGradient(listOf(metal, shade), startY = base.y - thick, endY = base.y + thick))

        // Pomello in plastica nera all'estremità, con la punta d'accento quando è pronta
        val knobW = 11.dp.toPx()
        val knobH = 20.dp.toPx()
        drawRoundRect(
            Brush.horizontalGradient(listOf(Color(0xFF2B2826), Color(0xFF4A4542), Color(0xFF1C1A19)), startX = tipTop.x - knobW / 2, endX = tipTop.x + knobW / 2),
            Offset(tipTop.x - knobW / 2, tipTop.y - knobH + 4.dp.toPx()),
            Size(knobW, knobH),
            CornerRadius(4.dp.toPx()),
        )
        if (enabled) {
            drawRoundRect(Palette.Safelight, Offset(tipTop.x - knobW / 2, tipTop.y - knobH + 4.dp.toPx()), Size(knobW, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
        }

        // Vite del perno
        drawOval(Color(0xFFB9B4AE), Offset(pivotX - 7.dp.toPx(), pivotY - 16.dp.toPx()), Size(14.dp.toPx(), 5.dp.toPx()))
    }
}

/* ------------------------------------------------------------------ */
/* Pulsante di scatto, di profilo                                      */
/* ------------------------------------------------------------------ */

@Composable
fun ShutterButton(armed: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val press = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Canvas(
        modifier
            .size(54.dp, 56.dp)
            .pointerInput(armed) {
                awaitEachGesture {
                    awaitFirstDown()
                    scope.launch { press.animateTo(1f, tween(60)) }
                    val up = waitForUpOrCancellation()
                    scope.launch { press.animateTo(0f, tween(140)) }
                    if (up != null) onClick()
                }
            }
    ) {
        val cx = size.width / 2f
        val baseY = size.height * 0.80f
        // Collare filettato
        drawOval(Color(0xFF6E6964), Offset(cx - 22.dp.toPx(), baseY - 4.dp.toPx()), Size(44.dp.toPx(), 12.dp.toPx()))
        drawRect(Brush.verticalGradient(listOf(Color(0xFFCFCAC4), Color(0xFF7F7A74))), Offset(cx - 22.dp.toPx(), baseY - 14.dp.toPx()), Size(44.dp.toPx(), 12.dp.toPx()))
        drawOval(Color(0xFFDCD8D2), Offset(cx - 22.dp.toPx(), baseY - 19.dp.toPx()), Size(44.dp.toPx(), 10.dp.toPx()))
        // Pulsante che affonda quando lo premi
        val travel = press.value * 6.dp.toPx()
        val top = baseY - 34.dp.toPx() + travel
        drawRect(
            Brush.horizontalGradient(listOf(Color(0xFF9C978F), Color(0xFFEDE9E3), Color(0xFF8A857F)), startX = cx - 12.dp.toPx(), endX = cx + 12.dp.toPx()),
            Offset(cx - 12.dp.toPx(), top + 4.dp.toPx()),
            Size(24.dp.toPx(), (baseY - 14.dp.toPx()) - (top + 4.dp.toPx())),
        )
        drawOval(if (armed) Palette.Safelight else Color(0xFF7A756F), Offset(cx - 12.dp.toPx(), top), Size(24.dp.toPx(), 8.dp.toPx()))
    }
}

/* ------------------------------------------------------------------ */
/* Manovella di riavvolgimento (a sinistra sulla calotta)              */
/* ------------------------------------------------------------------ */

@Composable
fun RewindKnob(ready: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    Canvas(
        modifier
            .size(64.dp, 56.dp)
            .pointerInput(ready) {
                awaitEachGesture {
                    awaitFirstDown()
                    if (waitForUpOrCancellation() != null) { if (ready) onClick() else view.reject() }
                }
            }
    ) {
        val cx = size.width / 2f
        val baseY = size.height * 0.82f
        drawOval(Color(0xFF6E6964), Offset(cx - 24.dp.toPx(), baseY - 4.dp.toPx()), Size(48.dp.toPx(), 12.dp.toPx()))
        drawRect(Brush.verticalGradient(listOf(Color(0xFF3A3634), Color(0xFF1E1C1B))), Offset(cx - 24.dp.toPx(), baseY - 18.dp.toPx()), Size(48.dp.toPx(), 16.dp.toPx()))
        // Zigrinatura
        var x = cx - 22.dp.toPx()
        while (x < cx + 22.dp.toPx()) {
            drawLine(Color(0xFF55504C), Offset(x, baseY - 17.dp.toPx()), Offset(x, baseY - 3.dp.toPx()), strokeWidth = 1.dp.toPx())
            x += 4.dp.toPx()
        }
        drawOval(Color(0xFF2A2725), Offset(cx - 24.dp.toPx(), baseY - 23.dp.toPx()), Size(48.dp.toPx(), 10.dp.toPx()))
        // Manovella ripiegata: si accende quando il rullino è finito
        val crank = if (ready) Palette.Safelight else Color(0xFF8A857F)
        drawLine(crank, Offset(cx, baseY - 20.dp.toPx()), Offset(cx + 18.dp.toPx(), baseY - 30.dp.toPx()), strokeWidth = 4.dp.toPx())
        drawCircle(crank, radius = 4.dp.toPx(), center = Offset(cx + 18.dp.toPx(), baseY - 30.dp.toPx()))
    }
}

/* ------------------------------------------------------------------ */
/* Contafotogrammi e pellicola                                         */
/* ------------------------------------------------------------------ */

/** Contafotogrammi: "S" a rullino appena caricato, poi il numero del prossimo scatto. */
@Composable
fun FrameCounter(
    number: Int,
    total: Int,
    modifier: Modifier = Modifier,
    rollTo: Int? = null,
    rollProgress: Float = 0f,
) {
    Box(
        modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Color(0xFF0C0B0B))
            .border(3.dp, Color(0xFF8E8983), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (rollTo != null) {
            // Riavvolgimento: il numero segue la manovella, quello attuale sale e il precedente arriva dal basso.
            val p = rollProgress.coerceIn(0f, 1f)
            CounterDigit(number, total, Modifier.offset(y = (-30f * p).dp))
            CounterDigit(rollTo, total, Modifier.offset(y = (30f * (1f - p)).dp))
        } else AnimatedContent(
            targetState = number,
            transitionSpec = { slideInVertically(tween(260)) { it } togetherWith slideOutVertically(tween(260)) { -it } },
            label = "contatore",
        ) { n -> CounterDigit(n, total) }
    }
}

@Composable
private fun CounterDigit(n: Int, total: Int, modifier: Modifier = Modifier) {
    Text(
        text = if (n <= 0) "S" else n.toString(),
        color = if (n >= total) Palette.Safelight else Palette.Ink,
        fontFamily = Mono,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

/** Striscia di pellicola con le perforazioni, che scorre mentre si carica la leva. */
@Composable
fun FilmStrip(progress: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(3.dp))) {
        drawRect(Color(0xFF2B1D14))
        val frameW = 70.dp.toPx()
        val shift = progress * frameW
        val holeW = 5.dp.toPx()
        val holeH = 3.5f.dp.toPx()
        val step = 10.dp.toPx()
        var x = -(shift % step) - step
        while (x < size.width + step) {
            drawRoundRect(Color(0xFF0C0B0B), Offset(x, 2.dp.toPx()), Size(holeW, holeH), CornerRadius(1.dp.toPx()))
            drawRoundRect(Color(0xFF0C0B0B), Offset(x, size.height - 2.dp.toPx() - holeH), Size(holeW, holeH), CornerRadius(1.dp.toPx()))
            x += step
        }
        var fx = -shift - frameW + (size.width / 2f) % frameW
        while (fx < size.width + frameW) {
            drawRect(Color(0xFF4A3424), Offset(fx + 3.dp.toPx(), 7.dp.toPx()), Size(frameW - 6.dp.toPx(), size.height - 14.dp.toPx()))
            fx += frameW
        }
        drawLine(Palette.Safelight, Offset(size.width / 2f, 0f), Offset(size.width / 2f, 3.dp.toPx()), strokeWidth = 2.dp.toPx())
    }
}

/** Una piccola targhetta incisa sul metallo. */
@Composable
fun Engraving(text: String, modifier: Modifier = Modifier, size: Int = 11) {
    Text(text, modifier = modifier, color = Color(0xFF4A4642), fontFamily = Mono, fontSize = size.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
}

/** Stroke riutilizzabile. */
internal fun stroke(px: Float) = Stroke(px)

/* ------------------------------------------------------------------ */
/* Manovella di riavvolgimento manuale e pulsante R                     */
/* ------------------------------------------------------------------ */

/**
 * Manovella da girare col dito in senso orario attorno al pomello.
 * Ogni giro completo chiama [onFullTurn]; ogni quarto di giro dà un piccolo scatto al tatto.
 * Vista frontale, dall'alto: il braccio gira insieme al dito, così il gesto resta comodo.
 */
@Composable
fun RewindCrank(
    enabled: Boolean,
    highlight: Boolean,
    onFullTurn: () -> Unit,
    modifier: Modifier = Modifier,
    resetKey: Any? = null,
    onProgress: (Float) -> Unit = {},
    diameter: Dp = 124.dp,
) {
    val view = LocalView.current
    val angle = remember { mutableFloatStateOf(0f) }   // rotazione visiva totale, in gradi
    val turn = remember(resetKey) { mutableFloatStateOf(0f) }    // gradi accumulati verso il prossimo giro
    val quarter = remember(resetKey) { mutableIntStateOf(0) }
    val progress by rememberUpdatedState(onProgress)
    val fullTurn by rememberUpdatedState(onFullTurn)

    Canvas(
        modifier
            .size(diameter)
            .pointerInput(enabled, resetKey) {
                if (!enabled) {
                    detectDragGestures(onDragStart = { view.reject() }) { change, _ -> change.consume() }
                    return@pointerInput
                }
                val center = Offset(size.width / 2f, size.height / 2f)
                detectDragGestures { change, _ ->
                    change.consume()
                    val prev = change.previousPosition - center
                    val now = change.position - center
                    var d = Math.toDegrees(
                        (kotlin.math.atan2(now.y, now.x) - kotlin.math.atan2(prev.y, prev.x)).toDouble()
                    ).toFloat()
                    if (d > 180f) d -= 360f
                    if (d < -180f) d += 360f
                    if (d <= 0f) return@detectDragGestures // si riavvolge solo in senso orario
                    angle.floatValue += d
                    turn.floatValue += d
                    val q = (turn.floatValue / 90f).toInt()
                    if (q > quarter.intValue) { view.tick(); quarter.intValue = q }
                    if (turn.floatValue >= 360f) {
                        turn.floatValue -= 360f
                        quarter.intValue = 0
                        view.confirm()
                        fullTurn()
                    }
                    progress(turn.floatValue / 360f)
                }
            }
    ) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r0 = size.minDimension / 2f - 2.dp.toPx()
        val a = Math.toRadians(angle.floatValue.toDouble())
        val crankColor = when {
            !enabled -> Color(0xFF6A6560)
            highlight -> Palette.Safelight
            else -> Color(0xFFCFCAC4)
        }
        // Pomello zigrinato visto dall'alto: la zigrinatura gira col braccio.
        drawCircle(Color(0xFF1E1C1B), radius = r0, center = c)
        val ticks = 48
        for (i in 0 until ticks) {
            val t = a + i * 2 * Math.PI / ticks
            val cos = kotlin.math.cos(t).toFloat(); val sin = kotlin.math.sin(t).toFloat()
            drawLine(
                Color(0xFF4A4542),
                Offset(c.x + cos * (r0 - 7.dp.toPx()), c.y + sin * (r0 - 7.dp.toPx())),
                Offset(c.x + cos * (r0 - 1.dp.toPx()), c.y + sin * (r0 - 1.dp.toPx())),
                strokeWidth = 1.5f.dp.toPx(),
            )
        }
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFF55504C), Color(0xFF2A2725)), center = c, radius = r0 * 0.85f),
            radius = r0 - 8.dp.toPx(), center = c,
        )
        // Freccia del verso di rotazione, incisa sul pomello.
        drawArc(
            Color(0x66CFCAC4), startAngle = -150f, sweepAngle = 120f, useCenter = false,
            topLeft = Offset(c.x - r0 * 0.62f, c.y - r0 * 0.62f), size = Size(r0 * 1.24f, r0 * 1.24f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f.dp.toPx()),
        )
        // Braccio e impugnatura.
        val reach = r0 * 0.66f
        val tip = Offset(c.x + (reach * kotlin.math.cos(a)).toFloat(), c.y + (reach * kotlin.math.sin(a)).toFloat())
        drawLine(crankColor, c, tip, strokeWidth = 8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(Color(0xFF0C0B0B), radius = 12.dp.toPx(), center = tip)
        drawCircle(crankColor, radius = 10.dp.toPx(), center = tip)
        drawCircle(Color(0xFFB9B4AE), radius = 7.dp.toPx(), center = c)
        drawCircle(Color(0xFF3A3634), radius = 3.dp.toPx(), center = c)
    }
}

/** Pulsante di sblocco del riavvolgimento ("R"): acceso, la manovella riporta la pellicola nel caricatore. */
@Composable
fun ReleaseButton(on: Boolean, enabled: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    Box(
        modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(if (on) Palette.Safelight else Color(0xFF2A2725))
            .border(2.dp, Color(0xFF8E8983), CircleShape)
            .pointerInput(enabled) {
                awaitEachGesture {
                    awaitFirstDown()
                    if (waitForUpOrCancellation() != null) { if (enabled) { view.tick(); onToggle() } else view.reject() }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("R", color = if (on) Color(0xFF1A1918) else Palette.Ink, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/* ------------------------------------------------------------------ */
/* Ghiera dello zoom, vista di profilo                                 */
/* ------------------------------------------------------------------ */

/** Focali incise sulla ghiera (mm equivalenti al 35 mm). */
val FOCAL_MARKS = listOf(24, 28, 35, 50, 85, 135)

/**
 * Ghiera zigrinata con la scala delle focali: si trascina a destra o a sinistra,
 * la scala scorre sotto la tacca fissa. Scala logaritmica, come sugli obiettivi veri.
 * Al rilascio si aggancia alla focale incisa più vicina, se è a portata.
 */
@Composable
fun ZoomRing(
    focal: Float,
    maxFocal: Float,
    onFocal: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val anim = remember { Animatable(focal) }
    val minF = FOCAL_MARKS.first().toFloat()
    val maxF = maxFocal.coerceAtLeast(minF)
    // Distanza in px tra due focali che raddoppiano (24 → 48): dà la "durezza" della ghiera.
    val pxPerDoubling = with(androidx.compose.ui.platform.LocalDensity.current) { 110.dp.toPx() }
    val lastTickPx = remember { mutableFloatStateOf(0f) }
    val dragPx = remember { mutableFloatStateOf(0f) }

    fun ln(x: Float) = kotlin.math.ln(x.toDouble()).toFloat()
    fun focalAfter(f: Float, dx: Float) =
        kotlin.math.exp((ln(f) - dx / pxPerDoubling * ln(2f)).toDouble()).toFloat().coerceIn(minF, maxF)

    Canvas(
        modifier
            .height(46.dp)
            .pointerInput(enabled, maxF) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                        // Aggancio dolce alla focale incisa più vicina (entro ~12%).
                        val cur = anim.value
                        val near = FOCAL_MARKS.map { it.toFloat() }.filter { it <= maxF }
                            .minByOrNull { kotlin.math.abs(ln(it) - ln(cur)) }
                        if (near != null && kotlin.math.abs(ln(near) - ln(cur)) < 0.12f) {
                            scope.launch {
                                anim.animateTo(near, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)) { onFocal(value) }
                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            }
                        }
                    },
                ) { change, dx ->
                    change.consume()
                    val before = anim.value
                    // Trascinando verso sinistra la scala scorre a sinistra: focali più lunghe.
                    val after = focalAfter(before, dx)
                    scope.launch { anim.snapTo(after) }
                    onFocal(after)
                    // Piccoli scatti al tatto lungo la zigrinatura, uno più deciso sulle focali incise.
                    dragPx.floatValue += kotlin.math.abs(dx)
                    if (dragPx.floatValue - lastTickPx.floatValue > 9f) { view.tick(); lastTickPx.floatValue = dragPx.floatValue }
                    FOCAL_MARKS.forEach { m ->
                        val mf = m.toFloat()
                        if ((before < mf && after >= mf) || (before > mf && after <= mf)) {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        }
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val center = w / 2f
        val cur = anim.value

        // Barilotto: un cilindro visto di profilo (più chiaro al centro, scuro ai bordi).
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFF151413), Color(0xFF3B3836), Color(0xFF1A1918))),
            Offset(0f, 0f), Size(w, h), CornerRadius(6.dp.toPx()),
        )
        // Zigrinatura nella metà bassa: righe che scorrono con la rotazione.
        val ridge = 5.dp.toPx()
        val shift = (ln(cur) / ln(2f) * pxPerDoubling) % ridge
        var x = -shift
        while (x < w) {
            val edge = kotlin.math.abs(x - center) / center   // ai lati le righe si stringono e si scuriscono
            drawLine(
                Color(0xFF0B0A0A).copy(alpha = 0.9f - 0.4f * edge),
                Offset(x, h * 0.58f), Offset(x, h - 3.dp.toPx()),
                strokeWidth = (2.2f - edge).dp.toPx(),
            )
            x += ridge
        }
        // Fascia della scala, in alto.
        drawRect(Color(0xFF232120), Offset(0f, 3.dp.toPx()), Size(w, h * 0.5f))

        // Focali incise: posizione in scala logaritmica rispetto a quella corrente.
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.MONOSPACE
        }
        FOCAL_MARKS.forEach { m ->
            val mx = center + (ln(m.toFloat()) - ln(cur)) / ln(2f) * pxPerDoubling
            if (mx < -20f || mx > w + 20f) return@forEach
            val reachable = m <= maxF + 0.5f
            val d = kotlin.math.abs(mx - center) / center
            paint.color = if (!reachable) android.graphics.Color.rgb(90, 86, 82)
            else if (kotlin.math.abs(mx - center) < 6f) android.graphics.Color.rgb(255, 106, 61)
            else android.graphics.Color.argb((255 * (1f - 0.6f * d)).toInt().coerceIn(60, 255), 235, 230, 224)
            drawContext.canvas.nativeCanvas.drawText(m.toString(), mx, h * 0.38f, paint)
            drawLine(Color(0xFF9C958D), Offset(mx, h * 0.44f), Offset(mx, h * 0.52f), strokeWidth = 1.dp.toPx())
        }
        // Tacca fissa di riferimento.
        val tri = Path().apply {
            moveTo(center - 5.dp.toPx(), 0f); lineTo(center + 5.dp.toPx(), 0f); lineTo(center, 6.dp.toPx()); close()
        }
        drawPath(tri, Palette.Safelight)
        // Ombre ai lati: il cilindro gira via dalla vista.
        drawRect(Brush.horizontalGradient(listOf(Color(0xCC000000), Color.Transparent), startX = 0f, endX = w * 0.18f), Offset.Zero, Size(w * 0.18f, h))
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xCC000000)), startX = w * 0.82f, endX = w), Offset(w * 0.82f, 0f), Size(w * 0.18f, h))
    }
}

/* ------------------------------------------------------------------ */
/* Leva di carica e pulsante di scatto visti dall'alto                 */
/* ------------------------------------------------------------------ */

/**
 * Pulsante di scatto con la leva di carica che gli ruota attorno, visti dall'alto.
 * Tocca il pulsante al centro per scattare; trascina in cerchio (da sinistra verso il basso)
 * per caricare la leva: arrivata a fine corsa avanza la pellicola e la molla la riporta a riposo.
 */
@Composable
fun TopLeverShutter(
    armed: Boolean,
    leverEnabled: Boolean,
    onShoot: () -> Unit,
    onAdvanced: () -> Unit,
    onProgress: (Float) -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 150.dp,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val press = remember { Animatable(0f) }
    val shoot by rememberUpdatedState(onShoot)
    val advanced by rememberUpdatedState(onAdvanced)
    val reportProgress by rememberUpdatedState(onProgress)

    Canvas(
        modifier
            .size(diameter)
            .pointerInput(armed, leverEnabled) {
                val pivot = Offset(this.size.width * 0.6f, this.size.height * 0.42f)
                val shutterR = 26.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if ((down.position - pivot).getDistance() <= shutterR) {
                        scope.launch { press.animateTo(1f, tween(60)) }
                        val up = waitForUpOrCancellation()
                        scope.launch { press.animateTo(0f, tween(140)) }
                        if (up != null) shoot()
                        return@awaitEachGesture
                    }
                    if (!leverEnabled) { view.reject(); return@awaitEachGesture }
                    var last = down.position - pivot
                    var v = progress.value
                    var lastTick = (v * 10).toInt()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        ch.consume()
                        val now = ch.position - pivot
                        var d = Math.toDegrees(
                            (kotlin.math.atan2(now.y, now.x) - kotlin.math.atan2(last.y, last.x)).toDouble()
                        ).toFloat()
                        if (d > 180f) d -= 360f
                        if (d < -180f) d += 360f
                        last = now
                        v = (v - d / LEVER_DEG).coerceIn(0f, 1f)
                        val snap = v
                        scope.launch { progress.snapTo(snap) }
                        reportProgress(v)
                        val t = (v * 10).toInt()
                        if (t > lastTick) view.tick()
                        lastTick = t
                    }
                    val complete = v >= 0.95f
                    scope.launch {
                        if (complete) { view.confirm(); advanced() }
                        progress.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)) {
                            reportProgress(if (complete) 0f else value)
                        }
                        reportProgress(0f)
                    }
                }
            }
    ) {
        val pivot = Offset(this.size.width * 0.6f, this.size.height * 0.42f)
        val armLen = this.size.width * 0.42f
        val deg = 180f - progress.value * LEVER_DEG
        val a = Math.toRadians(deg.toDouble())
        val tip = Offset(pivot.x + (armLen * kotlin.math.cos(a)).toFloat(), pivot.y + (armLen * kotlin.math.sin(a)).toFloat())
        val metal = if (leverEnabled) Color(0xFFE4E0DA) else Color(0xFF9A958F)

        // Guida della corsa, appena accennata quando la leva si può caricare.
        if (leverEnabled) {
            drawArc(
                Color(0x40CFCAC4), startAngle = 180f - LEVER_DEG, sweepAngle = LEVER_DEG, useCenter = false,
                topLeft = Offset(pivot.x - armLen, pivot.y - armLen), size = Size(armLen * 2, armLen * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
            )
        }
        // Braccio della leva, rastremato verso la punta.
        val nx = (-kotlin.math.sin(a)).toFloat(); val ny = kotlin.math.cos(a).toFloat()
        val w0 = 9.dp.toPx(); val w1 = 5.dp.toPx()
        val arm = Path().apply {
            moveTo(pivot.x + nx * w0, pivot.y + ny * w0)
            lineTo(tip.x + nx * w1, tip.y + ny * w1)
            lineTo(tip.x - nx * w1, tip.y - ny * w1)
            lineTo(pivot.x - nx * w0, pivot.y - ny * w0)
            close()
        }
        drawPath(arm, Color(0x55000000), alpha = 1f)
        drawPath(arm, Brush.linearGradient(listOf(metal, Color(0xFF8F8A84)), start = pivot, end = tip))
        // Pomello in plastica alla punta, con l'accento quando è pronta.
        drawCircle(Color(0xFF0C0B0B), radius = 12.dp.toPx(), center = tip)
        drawCircle(Brush.radialGradient(listOf(Color(0xFF4A4542), Color(0xFF1C1A19)), center = tip, radius = 11.dp.toPx()), radius = 10.dp.toPx(), center = tip)
        if (leverEnabled) drawCircle(Palette.Safelight, radius = 3.dp.toPx(), center = tip)

        // Collare del pulsante di scatto.
        drawCircle(Color(0xFF1E1C1B), radius = 27.dp.toPx(), center = pivot)
        drawCircle(Brush.radialGradient(listOf(Color(0xFFE9E5DF), Color(0xFF8A857F)), center = pivot, radius = 26.dp.toPx()), radius = 25.dp.toPx(), center = pivot)
        // Pulsante: affonda e si scurisce quando lo premi.
        val br = (15f - press.value * 1.5f).dp.toPx()
        drawCircle(Color(0xFF5A5550), radius = br + 2.dp.toPx(), center = pivot)
        drawCircle(
            Brush.radialGradient(
                listOf(if (press.value > 0.5f) Color(0xFFB9B4AE) else Color(0xFFF4F1EC), Color(0xFF8F8A84)),
                center = Offset(pivot.x - 4.dp.toPx(), pivot.y - 4.dp.toPx()), radius = br * 1.4f,
            ),
            radius = br, center = pivot,
        )
        if (armed) drawCircle(Palette.Safelight, radius = br + 4.dp.toPx(), center = pivot, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
    }
}

/* ------------------------------------------------------------------ */
/* Ghiera dei tempi, vista di profilo sulla calotta                    */
/* ------------------------------------------------------------------ */

/** Tempi di scatto della ghiera: null = automatico (A), poi da 1/1000 a 1 secondo. */
val SHUTTER_SPEEDS: List<Double?> = listOf(
    null, 1.0 / 1000, 1.0 / 500, 1.0 / 250, 1.0 / 125, 1.0 / 60, 1.0 / 30, 1.0 / 15, 1.0 / 8, 1.0 / 4, 1.0 / 2, 1.0,
)

/** Come è inciso sulla ghiera: "A", "1000" … "2", "1". */
fun shutterMark(s: Double?): String = when {
    s == null -> "A"
    s >= 1.0 -> "1"
    else -> kotlin.math.round(1 / s).toInt().toString()
}

/** Come lo si legge: "A", "1/125", "1 s". */
fun shutterLabel(s: Double?): String = when {
    s == null -> "A"
    s >= 1.0 -> "1 s"
    else -> "1/" + kotlin.math.round(1 / s).toInt()
}

/**
 * Ghiera dei tempi: si trascina a destra o a sinistra e scatta da un tempo all'altro.
 * Il diaframma della macchina virtuale è fisso: con la ghiera decidi tu quanta luce entra.
 */
@Composable
fun ShutterDial(index: Int, onIndex: (Int) -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val step = with(androidx.compose.ui.platform.LocalDensity.current) { 40.dp.toPx() }
    val pos = remember { Animatable(index.toFloat()) }
    LaunchedEffect(index) { if (kotlin.math.abs(pos.value - index) > 0.01f) pos.animateTo(index.toFloat()) }
    val last = SHUTTER_SPEEDS.size - 1

    Canvas(
        modifier
            .height(36.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val target = kotlin.math.round(pos.value).toInt().coerceIn(0, last)
                        scope.launch { pos.animateTo(target.toFloat(), spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)) }
                        onIndex(target)
                    },
                ) { change, dx ->
                    change.consume()
                    val before = kotlin.math.round(pos.value).toInt()
                    val v = (pos.value - dx / step).coerceIn(0f, last.toFloat())
                    scope.launch { pos.snapTo(v) }
                    val now = kotlin.math.round(v).toInt()
                    if (now != before) { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onIndex(now) }
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val center = w / 2f
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFF151413), Color(0xFF3B3836), Color(0xFF1A1918))),
            Offset(0f, 0f), Size(w, h), CornerRadius(5.dp.toPx()),
        )
        // Zigrinatura in basso che scorre con la ghiera.
        val ridge = 5.dp.toPx()
        var x = -((pos.value * step) % ridge)
        while (x < w) {
            drawLine(Color(0xFF0B0A0A).copy(alpha = 0.8f), Offset(x, h * 0.72f), Offset(x, h - 2.dp.toPx()), strokeWidth = 1.8f.dp.toPx())
            x += ridge
        }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        }
        SHUTTER_SPEEDS.forEachIndexed { i, s ->
            val mx = center + (i - pos.value) * step
            if (mx < -20f || mx > w + 20f) return@forEachIndexed
            val d = kotlin.math.abs(mx - center) / center
            paint.color = when {
                kotlin.math.abs(mx - center) < step / 2 -> android.graphics.Color.rgb(255, 106, 61)
                s == null -> android.graphics.Color.argb((255 * (1f - 0.6f * d)).toInt().coerceIn(60, 255), 255, 211, 107)
                else -> android.graphics.Color.argb((255 * (1f - 0.6f * d)).toInt().coerceIn(60, 255), 235, 230, 224)
            }
            drawContext.canvas.nativeCanvas.drawText(shutterMark(s), mx, h * 0.55f, paint)
        }
        val tri = Path().apply {
            moveTo(center - 4.dp.toPx(), 0f); lineTo(center + 4.dp.toPx(), 0f); lineTo(center, 5.dp.toPx()); close()
        }
        drawPath(tri, Palette.Safelight)
        drawRect(Brush.horizontalGradient(listOf(Color(0xCC000000), Color.Transparent), startX = 0f, endX = w * 0.2f), Offset.Zero, Size(w * 0.2f, h))
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xCC000000)), startX = w * 0.8f, endX = w), Offset(w * 0.8f, 0f), Size(w * 0.2f, h))
    }
}
