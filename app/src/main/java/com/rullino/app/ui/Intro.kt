package com.rullino.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rullino.app.R
import kotlinx.coroutines.launch

private data class IntroPage(val title: Int, val text: Int, val art: DrawScope.(Float) -> Unit)

/**
 * Il primo avvio: quattro cartoncini che spiegano il gioco lento.
 * Carica un rullino, scatta senza guardare, porta a sviluppare e aspetta, stampa.
 */
@Composable
fun IntroScreen(onDone: () -> Unit) {
    val pages = listOf(
        IntroPage(R.string.intro1_title, R.string.intro1_text) { t -> drawCanister(t) },
        IntroPage(R.string.intro2_title, R.string.intro2_text) { t -> drawViewfinder(t) },
        IntroPage(R.string.intro3_title, R.string.intro3_text) { t -> drawLab(t) },
        IntroPage(R.string.intro4_title, R.string.intro4_text) { t -> drawPrint(t) },
    )
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val pulse by rememberInfiniteTransition(label = "intro").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "pulse",
    )

    Box(Modifier.fillMaxSize().background(Palette.Deep)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
            val p = pages[i]
            Row(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.weight(1f).fillMaxHeight()) { p.art(this, pulse) }
                Spacer(Modifier.width(40.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("%02d / %02d".format(i + 1, pages.size), color = Palette.Safelight, fontFamily = Mono, fontSize = 12.sp, letterSpacing = 2.sp)
                    Text(stringResource(p.title).uppercase(), color = Palette.Ink, fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, letterSpacing = 1.5.sp, lineHeight = 32.sp)
                    Text(stringResource(p.text), color = Palette.Muted, fontSize = 15.sp, lineHeight = 21.sp)
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(horizontal = 40.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(pages.size) { i ->
                    Box(Modifier.size(if (i == pager.currentPage) 8.dp else 6.dp).background(if (i == pager.currentPage) Palette.Safelight else Palette.Line, CircleShape))
                }
            }
            Spacer(Modifier.width(8.dp))
            val last = pager.currentPage == pages.size - 1
            if (!last) TextButton(onClick = onDone) { Text(stringResource(R.string.intro_skip), color = Palette.Muted) }
            PrimaryButton(onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
                Text(stringResource(if (last) R.string.intro_start else R.string.intro_next))
            }
        }
    }
}

/* ---------------- disegni ---------------- */

private val Chrome = Color(0xFFC9C4BE)
private val Film = Color(0xFF7A3A12)

/** Un rullino con la coda di pellicola che esce. */
private fun DrawScope.drawCanister(t: Float) {
    val h = size.minDimension * 0.62f
    val w = h * 0.62f
    val left = size.width / 2 - w * 0.9f
    val top = (size.height - h) / 2
    // coda di pellicola
    val tail = w * (1.2f + 0.15f * t)
    drawRect(Film, Offset(left + w * 0.8f, top + h * 0.3f), Size(tail, h * 0.4f))
    var x = left + w
    while (x < left + w * 0.8f + tail - 8f) {
        drawRoundRect(Palette.Deep, Offset(x, top + h * 0.32f), Size(8f, 6f), CornerRadius(1.5f))
        drawRoundRect(Palette.Deep, Offset(x, top + h * 0.7f - 12f), Size(8f, 6f), CornerRadius(1.5f))
        x += 18f
    }
    // corpo
    drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF2A2624), Color(0xFF4A4440), Color(0xFF2A2624)), left, left + w),
        Offset(left, top), Size(w, h), CornerRadius(10f))
    drawRect(Chrome, Offset(left - 4f, top - 10f), Size(w + 8f, 14f))
    drawRect(Chrome, Offset(left - 4f, top + h - 4f), Size(w + 8f, 14f))
    drawRect(Chrome, Offset(left + w * 0.35f, top - 26f), Size(w * 0.3f, 18f))
    drawRect(Palette.Safelight, Offset(left + 10f, top + h * 0.42f), Size(w - 20f, h * 0.16f))
}

/** Il mirino: niente anteprima, solo il contapose. */
private fun DrawScope.drawViewfinder(t: Float) {
    val w = size.minDimension * 0.95f
    val h = w * 0.66f
    val l = (size.width - w) / 2
    val tp = (size.height - h) / 2
    val k = w * 0.1f
    val stroke = 4f
    for ((x, y, dx, dy) in listOf(listOf(l, tp, 1f, 1f), listOf(l + w, tp, -1f, 1f), listOf(l, tp + h, 1f, -1f), listOf(l + w, tp + h, -1f, -1f))) {
        drawLine(Palette.Ink, Offset(x, y), Offset(x + k * dx, y), stroke)
        drawLine(Palette.Ink, Offset(x, y), Offset(x, y + k * dy), stroke)
    }
    drawCircle(Palette.Ink.copy(alpha = 0.5f), w * 0.07f, Offset(l + w / 2, tp + h / 2), style = Stroke(2f))
    // lampo dello scatto
    drawRect(Color.White.copy(alpha = 0.06f + 0.12f * t), Offset(l, tp), Size(w, h))
    // contapose
    val cx = l + w - w * 0.12f
    drawCircle(Palette.Safelight, w * 0.06f, Offset(cx, tp + h + w * 0.1f))
}

/** Il laboratorio: la vaschetta sotto la luce rossa, e il tempo che passa. */
private fun DrawScope.drawLab(t: Float) {
    val cx = size.width / 2
    val cy = size.height / 2
    val r = size.minDimension * 0.42f
    drawCircle(Brush.radialGradient(listOf(Palette.Safelight.copy(alpha = 0.35f + 0.2f * t), Color.Transparent), Offset(cx, cy), r * 1.3f), r * 1.3f, Offset(cx, cy))
    drawRoundRect(Color(0xFF1F1C1A), Offset(cx - r, cy), Size(r * 2, r * 0.5f), CornerRadius(12f))
    drawRect(Palette.SafelightDark, Offset(cx - r + 12f, cy + 10f), Size(r * 2 - 24f, r * 0.3f))
    // orologio
    val oc = Offset(cx, cy - r * 0.45f)
    val or = r * 0.32f
    drawCircle(Palette.Ink, or, oc, style = Stroke(4f))
    val a = Math.toRadians((t * 300.0) - 90)
    drawLine(Palette.Safelight, oc, Offset(oc.x + (or * 0.75f * Math.cos(a)).toFloat(), oc.y + (or * 0.75f * Math.sin(a)).toFloat()), 4f)
    drawLine(Palette.Ink, oc, Offset(oc.x, oc.y - or * 0.5f), 4f)
}

/** La stampa, con la cornicetta del taglio. */
private fun DrawScope.drawPrint(t: Float) {
    val w = size.minDimension * 0.9f
    val h = w * 0.72f
    val l = (size.width - w) / 2
    val tp = (size.height - h) / 2
    drawRect(Color(0xFFF2EEE6), Offset(l, tp), Size(w, h))
    val m = w * 0.05f
    drawRect(Brush.verticalGradient(listOf(Color(0xFF6FA3C8), Color(0xFFF0C38A)), tp + m, tp + h * 0.6f), Offset(l + m, tp + m), Size(w - 2 * m, h - 2 * m))
    drawCircle(Color(0xFFFFD27A), w * 0.06f, Offset(l + w * 0.68f, tp + h * 0.32f))
    drawRect(Color(0xFF3F5B3A), Offset(l + m, tp + h * 0.62f), Size(w - 2 * m, h * 0.38f - m))
    val cw = (w - 2 * m) * (0.55f + 0.1f * t)
    val ch = cw
    drawRect(
        Color.White, Offset(l + w / 2 - cw / 2, tp + h / 2 - ch / 2), Size(cw, ch.coerceAtMost(h - 2 * m)),
        style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 8f))),
    )
}

