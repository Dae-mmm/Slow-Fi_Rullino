package com.rullino.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rullino.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** I formati di stampa: larghezza × altezza del lato corto e lungo, come i fogli in negozio. */
data class CropRatio(val label: String, val a: Int, val b: Int)

val CROP_RATIOS = listOf(
    CropRatio("1:1", 1, 1),
    CropRatio("4:5", 4, 5),
    CropRatio("3:4", 3, 4),
    CropRatio("5:7", 5, 7),
    CropRatio("2:3", 2, 3),
    CropRatio("9:16", 9, 16),
)

/** Il taglio scelto, in pixel dell'immagine originale. */
data class CropRect(val left: Int, val top: Int, val width: Int, val height: Int, val tag: String)

/**
 * La taglierina: sopra la stampa una cornice bianca tratteggiata che sposti col dito,
 * allarghi o stringi con due dita (o tirando gli angoli), nel formato che scegli.
 */
@Composable
fun CropEditor(path: String, onCancel: () -> Unit, onDone: (CropRect) -> Unit) {
    val loaded by produceState<Pair<Bitmap, Size>?>(null, path) {
        value = withContext(Dispatchers.IO) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            if (o.outWidth <= 0) return@withContext null
            var sample = 1
            while (max(o.outWidth, o.outHeight) / sample > 2048) sample *= 2
            val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext null
            bmp to Size(o.outWidth.toFloat(), o.outHeight.toFloat())
        }
    }

    var ratio by remember { mutableStateOf(CROP_RATIOS[1]) }
    var portrait by remember { mutableStateOf<Boolean?>(null) }
    // Cornice: centro e larghezza in pixel dell'originale; l'altezza viene dal formato.
    var cx by remember { mutableFloatStateOf(-1f) }
    var cy by remember { mutableFloatStateOf(-1f) }
    var w by remember { mutableFloatStateOf(-1f) }

    Column(
        Modifier.fillMaxSize().background(Color(0xF20C0B0B)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.crop_title).uppercase(), color = Palette.Faint, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 2.sp)

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val pair = loaded
            if (pair == null) return@BoxWithConstraints
            val (bmp, img) = pair
            val density = LocalDensity.current
            val boxW = with(density) { maxWidth.toPx() }
            val boxH = with(density) { maxHeight.toPx() }
            val s = min(boxW / img.width, boxH / img.height)
            val ox = (boxW - img.width * s) / 2
            val oy = (boxH - img.height * s) / 2

            val vertical = portrait ?: (img.height >= img.width)
            val r = if (vertical) ratio.a.toFloat() / ratio.b else ratio.b.toFloat() / ratio.a // larghezza / altezza
            val maxW = min(img.width, img.height * r)
            val minW = maxW * 0.15f
            fun clamp() {
                w = w.coerceIn(minW, maxW)
                val h = w / r
                cx = cx.coerceIn(w / 2, img.width - w / 2)
                cy = cy.coerceIn(h / 2, img.height - h / 2)
            }
            if (w < 0) { cx = img.width / 2; cy = img.height / 2; w = maxW * 0.9f }
            clamp()
            val h = w / r

            Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())

            // Spostare con un dito, zoomare con due.
            Canvas(
                Modifier.fillMaxSize().pointerInput(r, s) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        w *= zoom // le dita si allontanano: la cornice si allarga
                        cx += pan.x / s; cy += pan.y / s
                        clamp()
                    }
                }
            ) {
                val l = ox + (cx - w / 2) * s
                val t = oy + (cy - h / 2) * s
                val rw = w * s
                val rh = h * s
                // Terzi, appena accennati.
                for (i in 1..2) {
                    drawLine(Color(0x40FFFFFF), Offset(l + rw * i / 3, t), Offset(l + rw * i / 3, t + rh), 1f)
                    drawLine(Color(0x40FFFFFF), Offset(l, t + rh * i / 3), Offset(l + rw, t + rh * i / 3), 1f)
                }
                // La cornice luminosa: alone morbido sotto, tratteggio bianco sopra.
                drawRect(Color(0x33FFFFFF), Offset(l, t), Size(rw, rh), style = Stroke(10.dp.toPx()))
                drawRect(Color(0x66FFFFFF), Offset(l, t), Size(rw, rh), style = Stroke(4.dp.toPx()))
                drawRect(
                    Color.White, Offset(l, t), Size(rw, rh),
                    style = Stroke(1.5f.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()))),
                )
                // Angoli pieni, da tirare.
                val k = 18.dp.toPx(); val sw = 3.dp.toPx()
                for ((x, y, dx, dy) in listOf(
                    listOf(l, t, 1f, 1f), listOf(l + rw, t, -1f, 1f),
                    listOf(l, t + rh, 1f, -1f), listOf(l + rw, t + rh, -1f, -1f),
                )) {
                    drawLine(Color.White, Offset(x, y), Offset(x + k * dx, y), sw)
                    drawLine(Color.White, Offset(x, y), Offset(x, y + k * dy), sw)
                }
            }

            // Maniglie sugli angoli: tirandole la cornice cresce o si stringe attorno al centro.
            val handle = 44.dp
            val hp = with(density) { handle.toPx() }
            for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
                val px = ox + (cx + sx * w / 2) * s - hp / 2
                val py = oy + (cy + sy * h / 2) * s - hp / 2
                Box(
                    Modifier.align(Alignment.TopStart)
                        .offset { IntOffset(px.roundToInt(), py.roundToInt()) }
                        .size(handle)
                        .pointerInput(sx, sy, r, s) {
                            detectDragGestures { ch, d ->
                                ch.consume()
                                val grow = (d.x * sx + d.y * sy * r) / s
                                w += grow
                                clamp()
                            }
                        }
                )
            }
        }

        Text(stringResource(R.string.crop_hint), color = Color(0xFF6B655E), fontSize = 12.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(CROP_RATIOS) { c ->
                val on = c == ratio
                Text(
                    c.label, fontFamily = Mono, fontSize = 13.sp,
                    color = if (on) Palette.Safelight else Palette.Ink,
                    modifier = Modifier
                        .border(1.dp, if (on) Palette.Safelight else Color(0xFF4A4541), RoundedCornerShape(3.dp))
                        .background(if (on) Color(0xFF2D1710) else Color(0xFF161514), RoundedCornerShape(3.dp))
                        .clickable { ratio = c }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val img = loaded?.second
            val vertical = portrait ?: (img != null && img.height >= img.width)
            SecondaryButton(onClick = { portrait = !vertical }) {
                Text(stringResource(if (vertical) R.string.crop_portrait else R.string.crop_landscape))
            }
            Spacer(Modifier.width(4.dp))
            SecondaryButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            PrimaryButton(onClick = {
                if (img == null || w <= 0) return@PrimaryButton
                val r = if (vertical) ratio.a.toFloat() / ratio.b else ratio.b.toFloat() / ratio.a
                val ww = w.roundToInt(); val hh = (w / r).roundToInt()
                val l = (cx - w / 2).roundToInt().coerceIn(0, img.width.toInt() - ww)
                val t = (cy - w / r / 2).roundToInt().coerceIn(0, max(0, img.height.toInt() - hh))
                val tag = if (vertical) "${ratio.a}x${ratio.b}" else "${ratio.b}x${ratio.a}"
                onDone(CropRect(l, t, min(ww, img.width.toInt()), min(hh, img.height.toInt()), tag))
            }) { Text(stringResource(R.string.crop_cut)) }
        }
    }
}
