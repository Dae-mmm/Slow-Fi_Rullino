package com.rullino.app.ui

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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Corsa della leva in gradi. */
private const val LEVER_TRAVEL = 120f

fun View.tick() = performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
fun View.confirm() = performHapticFeedback(
    if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
)
fun View.reject() = performHapticFeedback(
    if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
)

/**
 * Leva di avanzamento: si trascina verso destra fino a fine corsa per far avanzare la pellicola
 * e armare l'otturatore. [onProgress] riceve 0…1 durante il trascinamento (per far scorrere la pellicola).
 */
@Composable
fun AdvanceLever(
    enabled: Boolean,
    onAdvanced: () -> Unit,
    onProgress: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val angle = remember { Animatable(0f) }
    val lastTick = remember { mutableIntStateOf(0) }

    fun release() {
        scope.launch {
            val complete = angle.value >= LEVER_TRAVEL * 0.92f
            if (complete) {
                view.confirm()
                onAdvanced()
            }
            lastTick.intValue = 0
            angle.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) {
                onProgress(if (complete) 0f else value / LEVER_TRAVEL)
            }
            onProgress(0f)
        }
    }

    Canvas(
        modifier
            .size(170.dp, 110.dp)
            .pointerInput(enabled) {
                if (!enabled) {
                    detectHorizontalDragGestures(onDragStart = { view.reject() }) { change, _ -> change.consume() }
                } else detectHorizontalDragGestures(
                    onDragEnd = { release() },
                    onDragCancel = { release() },
                ) { change, dx ->
                    change.consume()
                    val next = (angle.value + dx * 0.55f).coerceIn(0f, LEVER_TRAVEL)
                    scope.launch { angle.snapTo(next) }
                    onProgress(next / LEVER_TRAVEL)
                    val t = (next / 12f).toInt()
                    if (t > lastTick.intValue) view.tick()
                    lastTick.intValue = t
                }
            }
    ) {
        val pivot = Offset(size.width - 34.dp.toPx(), size.height - 30.dp.toPx())
        val armLen = size.width - 52.dp.toPx()
        val armColor = if (enabled) Palette.Chrome else Palette.Line

        // Base del perno
        drawCircle(Palette.PlateHi, radius = 24.dp.toPx(), center = pivot)
        drawCircle(Palette.Line, radius = 24.dp.toPx(), center = pivot, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))

        rotate(degrees = angle.value, pivot = pivot) {
            drawLine(
                brush = Brush.verticalGradient(listOf(armColor, armColor.copy(alpha = 0.7f))),
                start = pivot,
                end = Offset(pivot.x - armLen, pivot.y),
                strokeWidth = 14.dp.toPx(),
                cap = StrokeCap.Round,
            )
            // Pomello in punta, con l'accento quando la leva è pronta
            drawCircle(if (enabled) Palette.Safelight else Palette.Line, radius = 9.dp.toPx(), center = Offset(pivot.x - armLen, pivot.y))
        }
        drawCircle(Palette.Chrome, radius = 7.dp.toPx(), center = pivot)
    }
}

/** Contafotogrammi: "S" a rullino appena caricato, poi il numero del prossimo scatto. */
@Composable
fun FrameCounter(number: Int, total: Int, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Color(0xFF0C0B0B))
                .border(3.dp, Palette.Chrome, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = number,
                transitionSpec = {
                    (slideInVertically(tween(260)) { it } togetherWith slideOutVertically(tween(260)) { -it })
                },
                label = "contatore",
            ) { n ->
                Text(
                    text = if (n <= 0) "S" else n.toString(),
                    color = if (n >= total) Palette.Safelight else Palette.Ink,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Text("di $total", color = Palette.Muted, fontSize = 11.sp, fontFamily = Mono)
    }
}

/** Striscia di pellicola con le perforazioni, che scorre mentre si carica la leva. */
@Composable
fun FilmStrip(progress: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(4.dp))) {
        val base = Color(0xFF2B1D14)
        drawRect(base)
        val frameW = 90.dp.toPx()
        val shift = progress * frameW
        val holeW = 7.dp.toPx()
        val holeH = 5.dp.toPx()
        val step = 14.dp.toPx()
        var x = -(shift % step) - step
        while (x < size.width + step) {
            drawRoundRect(Color(0xFF0C0B0B), Offset(x, 3.dp.toPx()), Size(holeW, holeH), CornerRadius(1.5f.dp.toPx()))
            drawRoundRect(Color(0xFF0C0B0B), Offset(x, size.height - 3.dp.toPx() - holeH), Size(holeW, holeH), CornerRadius(1.5f.dp.toPx()))
            x += step
        }
        // Fotogrammi
        var fx = -shift - frameW + (size.width / 2f) % frameW
        while (fx < size.width + frameW) {
            drawRect(Color(0xFF4A3424), Offset(fx + 4.dp.toPx(), 10.dp.toPx()), Size(frameW - 8.dp.toPx(), size.height - 20.dp.toPx()))
            fx += frameW
        }
        // Tacca di riferimento al centro
        drawLine(Palette.Safelight, Offset(size.width / 2f, 0f), Offset(size.width / 2f, 4.dp.toPx()), strokeWidth = 2.dp.toPx())
    }
}
