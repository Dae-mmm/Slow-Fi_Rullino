package com.rullino.app.ui

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.IconButton
import com.rullino.app.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rullino.app.data.ReleaseEntity
import com.rullino.app.data.RollEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Elenco delle liberatorie di una posa, con la possibilità di aggiungerne una. */
@Composable
fun ReleasesDialog(vm: AppViewModel, roll: RollEntity, initialFrame: Int, onClose: () -> Unit, maxFrame: Int = 0) {
    var frame by remember(roll.id) { mutableStateOf(initialFrame) }
    val prevLabel = stringResource(R.string.prev_frame)
    val nextLabel = stringResource(R.string.next_frame)
    val all by remember(roll.id) { vm.releases(roll.id) }.collectAsState(initial = emptyList())
    val list = all.filter { it.frame == frame }
    var adding by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.Plate)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.releases_title, frame), color = Palette.Ink, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                if (maxFrame > 1 && !adding) {
                    IconButton(onClick = { frame-- }, enabled = frame > 1) {
                        Text("◀", color = if (frame > 1) Palette.Ink else Palette.Muted, modifier = Modifier.semantics { contentDescription = prevLabel })
                    }
                    IconButton(onClick = { frame++ }, enabled = frame < maxFrame) {
                        Text("▶", color = if (frame < maxFrame) Palette.Ink else Palette.Muted, modifier = Modifier.semantics { contentDescription = nextLabel })
                    }
                }
            }
            if (adding) {
                ReleaseForm(
                    vm = vm,
                    onCancel = { adding = false },
                    onSave = { draft, sig -> vm.saveRelease(roll, draft.copy(rollId = roll.id, frame = frame), sig); adding = false },
                )
            } else {
                if (list.isEmpty()) Text(stringResource(R.string.no_releases), color = Palette.Muted)
                list.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.PlateHi).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${r.firstName} ${r.lastName}" + if (r.isMinor) stringResource(R.string.minor_suffix) else "", color = Palette.Ink)
                            Text(
                                SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(r.signedAt)) +
                                    if (r.externalUse) stringResource(R.string.ext_yes) else stringResource(R.string.ext_no),
                                color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp,
                            )
                        }
                        OutlinedButton(onClick = {
                            vm.exportReleasePdf(roll, r) { uri ->
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "application/pdf")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching { context.startActivity(intent) }
                            }
                        }) { Text("PDF") }
                        TextButton(onClick = { vm.deleteRelease(r) }) { Text(stringResource(R.string.delete), color = Color(0xFFFF8A80)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { adding = true }) { Text(stringResource(R.string.new_release)) }
                    TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
                }
                Text(
                    stringResource(R.string.pdf_note),
                    color = Palette.Muted, fontSize = 12.sp,
                )
            }
        }
    }
}

/** Il modulo da far compilare e firmare col dito alla persona ritratta. */
@Composable
private fun ReleaseForm(vm: AppViewModel, onCancel: () -> Unit, onSave: (ReleaseEntity, Bitmap) -> Unit) {
    var photographer by remember { mutableStateOf(vm.settings.photographerName()) }
    var first by remember { mutableStateOf("") }
    var last by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    var external by remember { mutableStateOf(false) }
    var minor by remember { mutableStateOf(false) }
    var guardian by remember { mutableStateOf("") }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var padSize by remember { mutableStateOf(IntSize.Zero) }

    val valid = first.isNotBlank() && last.isNotBlank() && strokes.isNotEmpty() && (!minor || guardian.isNotBlank())

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(photographer, { photographer = it }, label = { Text(stringResource(R.string.f_photographer)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(first, { first = it }, label = { Text(stringResource(R.string.f_first)) }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(last, { last = it }, label = { Text(stringResource(R.string.f_last)) }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(contact, { contact = it }, label = { Text(stringResource(R.string.f_contact)) }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(place, { place = it }, label = { Text(stringResource(R.string.f_place)) }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(external, { external = it })
            Text(stringResource(R.string.f_external), color = Palette.Ink, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(minor, { minor = it })
            Text(stringResource(R.string.f_minor), color = Palette.Ink, fontSize = 13.sp)
        }
        if (minor) {
            OutlinedTextField(guardian, { guardian = it }, label = { Text(stringResource(R.string.f_guardian)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }

        Text(if (minor) stringResource(R.string.sig_guardian) else stringResource(R.string.sig_finger), color = Palette.Muted, fontSize = 12.sp)
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFF4F1EA))
                .border(1.dp, Palette.Line, RoundedCornerShape(8.dp))
                .onSizeChanged { padSize = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { current = listOf(it) },
                        onDragEnd = { if (current.size > 1) strokes.add(current); current = emptyList() },
                        onDragCancel = { current = emptyList() },
                    ) { change, _ ->
                        change.consume()
                        current = current + change.position
                    }
                },
        ) {
            Canvas(Modifier.matchParentSize()) {
                val ink = Color(0xFF1B2A4A)
                (strokes + listOf(current)).forEach { pts ->
                    if (pts.size < 2) return@forEach
                    val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
                    drawPath(path, ink, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            if (strokes.isEmpty() && current.isEmpty()) {
                Text(stringResource(R.string.sign_here), color = Color(0xFFB0A99E), modifier = Modifier.align(Alignment.Center))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { strokes.clear() }) { Text(stringResource(R.string.clear_sig)) }
            Spacer1()
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            Button(enabled = valid, onClick = {
                vm.settings.setPhotographerName(photographer)
                val bmp = renderSignature(strokes, padSize)
                onSave(
                    ReleaseEntity(
                        rollId = 0, frame = 0, photographer = photographer.trim(),
                        firstName = first.trim(), lastName = last.trim(), contact = contact.trim(), place = place.trim(),
                        signedAt = System.currentTimeMillis(), externalUse = external, isMinor = minor,
                        guardianName = guardian.trim(), signaturePath = "",
                    ),
                    bmp,
                )
            }) { Text(stringResource(R.string.sign_save)) }
        }
    }
}

@Composable
private fun Spacer1() = androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 4.dp))

/** Trasforma i tratti della firma in un PNG con sfondo trasparente. */
private fun renderSignature(strokes: List<List<Offset>>, size: IntSize): Bitmap {
    val w = size.width.coerceAtLeast(1)
    val h = size.height.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = AndroidCanvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(27, 42, 74)
        style = Paint.Style.STROKE
        strokeWidth = (h / 40f).coerceAtLeast(4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    for (pts in strokes) {
        if (pts.size < 2) continue
        val p = android.graphics.Path()
        p.moveTo(pts[0].x, pts[0].y)
        pts.drop(1).forEach { p.lineTo(it.x, it.y) }
        c.drawPath(p, paint)
    }
    return bmp
}
