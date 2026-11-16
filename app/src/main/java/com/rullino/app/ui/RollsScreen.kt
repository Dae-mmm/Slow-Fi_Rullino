package com.rullino.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.rullino.app.data.FrameEntity
import com.rullino.app.data.RollEntity
import com.rullino.app.data.RollState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RollsScreen(vm: AppViewModel, onOpenCamera: () -> Unit) {
    val rolls by vm.rolls.collectAsState()
    val active by vm.activeRoll.collectAsState()
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    val open = rolls.firstOrNull { it.id == openId }

    if (open != null && open.state == RollState.DEVELOPED) {
        BackHandler { openId = null }
        LightTable(vm, open, onBack = { openId = null })
        return
    }

    if (rolls.isEmpty()) {
        Text("Ancora nessun rullino. Caricane uno dalla fotocamera.", color = Palette.Muted, modifier = Modifier.padding(20.dp))
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(rolls, key = { it.id }) { r ->
            RollRow(
                r = r,
                cameraFree = active == null || (active?.state == RollState.LOADED && active?.shotCount == 0),
                onOpen = { openId = r.id },
                onReload = { vm.reload(r, onOpenCamera) },
                onDevelop = { vm.developNow(r) },
                onDelete = { vm.deleteRoll(r) },
            )
        }
    }
}

@Composable
private fun RollRow(
    r: RollEntity,
    cameraFree: Boolean,
    onOpen: () -> Unit,
    onReload: () -> Unit,
    onDevelop: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Plate)
            .clickable(enabled = r.state == RollState.DEVELOPED, onClick = onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(r.emulsionName, color = Palette.Ink, fontWeight = FontWeight.SemiBold)
            Text(
                "ISO ${r.iso} · ${r.shotCount}/${r.exposures} · ${date(r.developedAt ?: r.finishedAt ?: r.loadedAt)}",
                color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp,
            )
        }
        Text(stateLabel(r), color = if (r.state == RollState.DEVELOPED) Palette.Ink else Palette.Safelight, fontSize = 12.sp)
        if (r.state == RollState.SHELVED) {
            OutlinedButton(enabled = cameraFree, onClick = onReload) { Text("Ricarica") }
            Button(onClick = onDevelop) { Text("Sviluppa") }
        }
        if (r.state == RollState.SHELVED || r.state == RollState.DEVELOPED) {
            TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                Text(if (confirmDelete) "Conferma" else "Elimina", color = Color(0xFFFF8A80))
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Tavolo luminoso                                                     */
/* ------------------------------------------------------------------ */

/** Negativo a colori: inverte e aggiunge la base arancione della pellicola. */
private val colorNegative = ColorMatrix(
    floatArrayOf(
        -0.80f, 0f, 0f, 0f, 235f,
        0f, -0.70f, 0f, 0f, 150f,
        0f, 0f, -0.55f, 0f, 85f,
        0f, 0f, 0f, 1f, 0f,
    )
)

/** Negativo in bianco e nero: inverte su una base grigio chiaro. */
private val bwNegative = ColorMatrix(
    floatArrayOf(
        -0.27f, -0.53f, -0.10f, 0f, 225f,
        -0.27f, -0.53f, -0.10f, 0f, 225f,
        -0.27f, -0.53f, -0.10f, 0f, 225f,
        0f, 0f, 0f, 1f, 0f,
    )
)

@Composable
private fun LightTable(vm: AppViewModel, roll: RollEntity, onBack: () -> Unit) {
    val frames by remember(roll.id) { vm.frames(roll.id) }.collectAsState(initial = emptyList())
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var loupe by remember { mutableStateOf<FrameEntity?>(null) }
    var confirmCut by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            // Il tavolo: luce bianca diffusa
            .background(Color(0xFFE9E6DF)),
    ) {
        Row(
            Modifier.fillMaxWidth().background(Palette.Plate).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onBack) { Text("‹ Rullini") }
            Column(Modifier.weight(1f)) {
                Text(roll.emulsionName, color = Palette.Ink, fontWeight = FontWeight.SemiBold)
                val printed = frames.count { it.printUri != null }
                Text("${frames.size} negativi · $printed stampati", color = Palette.Muted, fontSize = 11.sp, fontFamily = Mono)
            }
            if (!selecting) {
                OutlinedButton(onClick = { selecting = true; selected = emptySet() }) { Text("Seleziona") }
                TextButton(onClick = { if (confirmDelete) vm.deleteRoll(roll, onBack) else confirmDelete = true }) {
                    Text(if (confirmDelete) "Conferma: elimina tutto" else "Elimina rullino", color = Color(0xFFFF8A80))
                }
            } else {
                Text("${selected.size} scelti", color = Palette.Ink, fontFamily = Mono, fontSize = 12.sp)
                Button(enabled = selected.isNotEmpty(), onClick = {
                    vm.print(roll, frames.filter { it.frame in selected })
                    selecting = false
                }) { Text("Stampa") }
                OutlinedButton(enabled = selected.isNotEmpty(), onClick = { confirmCut = true }) { Text("✂ Tieni solo questi") }
                TextButton(onClick = { selecting = false; selected = emptySet() }) { Text("Annulla") }
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val stripHeight = (maxHeight * 0.62f)
            LazyRow(
                modifier = Modifier.fillMaxWidth().height(stripHeight),
                contentPadding = PaddingValues(horizontal = 40.dp),
            ) {
                items(frames, key = { it.frame }) { f ->
                    NegativeFrame(
                        frame = f,
                        roll = roll,
                        isSelected = f.frame in selected,
                        selecting = selecting,
                        onClick = {
                            if (selecting) selected = if (f.frame in selected) selected - f.frame else selected + f.frame
                            else loupe = f
                        },
                    )
                }
            }
        }
        Text(
            if (selecting) "Tocca i fotogrammi da stampare o da tenere" else "Tocca un fotogramma per guardarlo con il lentino",
            color = Color(0xFF6B655E),
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 10.dp),
        )
    }

    loupe?.let { f ->
        Loupe(
            frame = f,
            onClose = { loupe = null },
            onPrint = { vm.print(roll, listOf(f)); loupe = null },
        )
    }

    if (confirmCut) {
        AlertDialog(
            onDismissRequest = { confirmCut = false },
            containerColor = Palette.Plate,
            title = { Text("Taglio dei negativi") },
            text = {
                Text("Tieni ${selected.size} fotogrammi su ${frames.size}. Gli altri vengono eliminati per sempre, anche le loro stampe in galleria.")
            },
            confirmButton = {
                Button(onClick = {
                    vm.cutKeeping(roll, selected)
                    confirmCut = false; selecting = false; selected = emptySet()
                }) { Text("✂ Taglia") }
            },
            dismissButton = { TextButton(onClick = { confirmCut = false }) { Text("Annulla") } },
        )
    }
}

/** Un fotogramma della striscia: perforazioni sopra e sotto, numero e nome dell'emulsione sul bordo. */
@Composable
private fun NegativeFrame(frame: FrameEntity, roll: RollEntity, isSelected: Boolean, selecting: Boolean, onClick: () -> Unit) {
    val base = if (roll.isBw) Color(0xFF2E2D2B) else Color(0xFF7A3A12)
    Column(
        Modifier
            .fillMaxHeight()
            .aspectRatio(1.5f * 0.72f)
            .background(base)
            .clickable(onClick = onClick),
    ) {
        Sprockets(base, label = "${roll.emulsionName.uppercase()}  ${roll.iso}", number = frame.frame)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = File(frame.developedPath),
                contentDescription = "Negativo ${frame.frame}",
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(if (roll.isBw) bwNegative else colorNegative),
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (isSelected) Modifier.border(3.dp, Palette.Safelight) else Modifier),
            )
            if (frame.printUri != null) {
                Text(
                    "STAMPATA",
                    color = Color.White, fontFamily = Mono, fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color(0x99000000)).padding(horizontal = 4.dp),
                )
            }
            if (frame.exposureCount > 1) {
                Text(
                    "×${frame.exposureCount}",
                    color = Color.White, fontFamily = Mono, fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp).background(Color(0x99000000)).padding(horizontal = 4.dp),
                )
            }
            if (selecting && !isSelected) {
                Box(Modifier.fillMaxSize().background(Color(0x33000000)))
            }
        }
        Sprockets(base, label = null, number = frame.frame)
    }
}

/** Banda con le perforazioni; quella superiore porta le scritte di bordo. */
@Composable
private fun Sprockets(base: Color, label: String?, number: Int) {
    Box(Modifier.fillMaxWidth().height(22.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(base)
            val holeW = 7.dp.toPx()
            val holeH = 5.dp.toPx()
            val step = 13.dp.toPx()
            val y = if (label != null) size.height - holeH - 3.dp.toPx() else 3.dp.toPx()
            var x = 4.dp.toPx()
            while (x < size.width) {
                drawRoundRect(Color(0xFFE9E6DF), Offset(x, y), Size(holeW, holeH), CornerRadius(1.5f.dp.toPx()))
                x += step
            }
        }
        if (label != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, color = Color(0xFFFFB27A), fontFamily = Mono, fontSize = 8.sp, maxLines = 1)
                Text("▸ $number", color = Color(0xFFFFB27A), fontFamily = Mono, fontSize = 8.sp)
            }
        }
    }
}

/** Il lentino: il fotogramma in positivo, grande, con la stampa. */
@Composable
private fun Loupe(frame: FrameEntity, onClose: () -> Unit, onPrint: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color(0xEE0C0B0B)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = File(frame.developedPath),
                    contentDescription = "Positivo ${frame.frame}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Posa ${frame.frame}" + if (frame.exposureCount > 1) " · ${frame.exposureCount} esposizioni" else "", color = Palette.Muted, fontFamily = Mono, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                if (frame.printUri == null) Button(onClick = onPrint) { Text("Stampa") }
                else Text("Già stampata", color = Palette.Ink)
                OutlinedButton(onClick = onClose) { Text("Rimetti sul tavolo") }
            }
        }
    }
}

private fun stateLabel(r: RollEntity) = when (r.state) {
    RollState.LOADED -> "In macchina"
    RollState.SHELVED -> "Messo da parte"
    RollState.FINISHED -> "Da sviluppare"
    RollState.DEVELOPING -> "In sviluppo"
    else -> "Negativi ›"
}

private fun date(ms: Long): String = SimpleDateFormat("d MMM yyyy", Locale.ITALIAN).format(Date(ms))
