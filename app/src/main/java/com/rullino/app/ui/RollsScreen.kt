package com.rullino.app.ui

import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.lazy.itemsIndexed
import com.rullino.app.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
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
import androidx.compose.runtime.LaunchedEffect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RollsScreen(vm: AppViewModel, onOpenCamera: () -> Unit, onGoLab: () -> Unit) {
    val rolls by vm.rolls.collectAsState()
    val active by vm.activeRoll.collectAsState()
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    val open = rolls.firstOrNull { it.id == openId }
    val skipWait by vm.settings.skipLabWait.collectAsState()
    val devMode by vm.settings.developerMode.collectAsState()
    // Rullini in viaggio: se qualcuno li ha importati, qui spariscono.
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.syncShared() }
    // L'orologio del laboratorio: si aggiorna ogni 30 secondi.
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000); value = System.currentTimeMillis() }
    }
    fun ready(r: RollEntity) = r.state == RollState.DEVELOPED && ((devMode && skipWait) || (r.readyAt ?: 0L) <= now)

    // Il negoziante riporta i negativi da solo: quando sono pronti si aprono qui.
    if (open != null && ready(open)) {
        BackHandler { openId = null }
        LightTable(vm, open, onBack = { openId = null })
        return
    }

    val toLab = rolls.filter { it.state == RollState.FINISHED && it.shotCount > 0 }
    LazyColumn(
        Modifier.fillMaxSize().dottedPage(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            PageHeading(stringResource(R.string.kick_archive, rolls.size.toString().padStart(2, '0')), stringResource(R.string.rolls_title)) {
                if (toLab.isNotEmpty()) Row(
                    Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF241714))
                        .border(1.dp, Color(0xFF6E3322), RoundedCornerShape(4.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column {
                        Text(
                            if (toLab.size == 1) stringResource(R.string.to_develop_one) else stringResource(R.string.to_develop_many, toLab.size),
                            color = Palette.Ink, fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                        )
                        Text(stringResource(R.string.lab_dont_wait), color = Palette.Muted, fontSize = 12.sp)
                    }
                    PrimaryButton(onClick = onGoLab) { Text(stringResource(R.string.go_lab).uppercase()) }
                }
            }
        }
        if (rolls.isEmpty()) item {
            Text(stringResource(R.string.rolls_empty), color = Palette.Muted, modifier = Modifier.padding(top = 20.dp))
        }
        itemsIndexed(rolls, key = { _, r -> r.id }) { index, r ->
            RollRow(
                r = r,
                number = (index + 1).toString().padStart(2, '0'),
                isReady = ready(r),
                waitLeft = ((r.readyAt ?: 0L) - now).coerceAtLeast(0L),
                cameraFree = active == null || (active?.state == RollState.LOADED && active?.shotCount == 0),
                onOpen = { openId = r.id },
                onReload = { vm.reload(r, onOpenCamera) },
                onDevelop = { vm.rewindOut(r) },
                onShare = { vm.shareRoll(r) },
                onTakeBack = { vm.takeBack(r) },
                onDelete = { vm.deleteRoll(r) },
            )
        }
    }
}

@Composable
private fun RollRow(
    r: RollEntity,
    number: String,
    isReady: Boolean,
    waitLeft: Long,
    cameraFree: Boolean,
    onOpen: () -> Unit,
    onReload: () -> Unit,
    onDevelop: () -> Unit,
    onShare: () -> Unit,
    onTakeBack: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .card()
            .clickable(enabled = isReady, onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Cartridge(r.emulsionName, r.iso, r.isBw, r.exposures, scale = 0.9f)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val status = when {
                isReady -> stringResource(R.string.negatives_arrow)
                r.state == RollState.DEVELOPING -> stringResource(R.string.at_lab_tank)
                r.state == RollState.DEVELOPED -> stringResource(R.string.at_lab_back_in, formatWait(LocalContext.current.resources, waitLeft))
                else -> stateLabel(r)
            }
            Chip(if (isReady) stateLabel(r).trimEnd('›', ' ') else status, active = isReady)
            Text(r.emulsionName, color = Palette.Ink, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, maxLines = 1)
            Text(
                "ISO ${r.iso} · ${r.shotCount}/${r.exposures} · ${date(r.developedAt ?: r.finishedAt ?: r.loadedAt)}",
                color = Palette.Muted, fontSize = 13.sp, letterSpacing = 0.4.sp,
            )
        }
        Text(number, color = Color(0xFF4A4541), fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 28.sp)
        if (isReady) Text(stringResource(R.string.negatives_arrow), color = Palette.Safelight, fontSize = 14.sp)
        // Rullino scaricato: lo ricarichi per continuare, lo mandi in sviluppo così com'è o lo passi a qualcuno.
        if (r.state == RollState.SHELVED) {
            SecondaryButton(enabled = cameraFree, onClick = onReload) { Text(stringResource(R.string.load)) }
            SecondaryButton(onClick = onShare) { Text(stringResource(R.string.share)) }
            PrimaryButton(onClick = onDevelop) { Text(stringResource(R.string.develop_it)) }
        }
        // In viaggio: si può rimandare lo stesso file (stesso codice) o riprenderlo se nessuno l'ha preso.
        if (r.state == RollState.SHARED) {
            SecondaryButton(onClick = onShare) { Text(stringResource(R.string.share_resend)) }
            PrimaryButton(onClick = onTakeBack) { Text(stringResource(R.string.share_take_back)) }
        }
        if (r.state == RollState.SHELVED || r.state == RollState.FINISHED || isReady) {
            TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                Text(if (confirmDelete) stringResource(R.string.confirm) else stringResource(R.string.delete), color = Color(0xFFFF8A80))
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
    val releases by remember(roll.id) { vm.releases(roll.id) }.collectAsState(initial = emptyList())
    var releaseFrame by remember { mutableStateOf<Int?>(null) }
    releaseFrame?.let { f -> ReleasesDialog(vm, roll, f, onClose = { releaseFrame = null }) }
    // Concorsi aperti a cui questo rullino può partecipare.
    val cvm: ContestsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val contests by cvm.contests.collectAsState()
    val mine by cvm.mine.collectAsState()
    val signedIn by vm.account.signedIn.collectAsState()
    LaunchedEffect(signedIn) { if (signedIn && contests.isEmpty()) cvm.refresh() }
    val eligible = remember(contests, mine, roll) { cvm.eligible(roll) }
    var sendFrame by remember { mutableStateOf<FrameEntity?>(null) }
    sendFrame?.let { f ->
        SendToContestDialog(cvm, roll, f, eligible, hasRelease = releases.any { it.frame == f.frame }, onClose = { sendFrame = null })
    }

    Column(
        Modifier
            .fillMaxSize()
            // Il tavolo: luce calda diffusa, puntinata come nel design
            .drawBehind {
                drawRect(Color(0xFFE6DCC6))
                val step = 6.dp.toPx(); var y = 0f
                while (y < size.height) { var x = 0f; while (x < size.width) { drawCircle(Color(0xFFD2C6AC), 0.8f, Offset(x, y)); x += step }; y += step }
            },
    ) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF221F1D)).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.back_rolls).uppercase(), color = Palette.Safelight, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.developed_on, roll.developedAt?.let { date(it) } ?: "—").uppercase(),
                    color = Palette.Muted, fontFamily = Mono, fontSize = 9.sp, letterSpacing = 0.6.sp,
                )
                val printed = frames.count { it.printUri != null }
                Text(
                    roll.emulsionName + " · " + stringResource(R.string.negatives_printed, frames.size, printed),
                    color = Palette.Ink, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, maxLines = 1,
                )
            }
            if (!selecting) {
                SecondaryButton(onClick = { selecting = true; selected = emptySet() }) { Text(stringResource(R.string.select)) }
                TextButton(onClick = { if (confirmDelete) vm.deleteRoll(roll, onBack) else confirmDelete = true }) {
                    Text(if (confirmDelete) stringResource(R.string.delete_all_confirm) else stringResource(R.string.delete_roll2), color = Color(0xFFFF8A80))
                }
            } else {
                Text(stringResource(R.string.n_chosen, selected.size), color = Palette.Ink, fontFamily = Mono, fontSize = 12.sp)
                PrimaryButton(enabled = selected.isNotEmpty(), onClick = {
                    vm.print(roll, frames.filter { it.frame in selected })
                    selecting = false
                }) { Text(stringResource(R.string.print)) }
                SecondaryButton(enabled = selected.isNotEmpty(), onClick = { confirmCut = true }) { Text(stringResource(R.string.keep_only)) }
                TextButton(onClick = { selecting = false; selected = emptySet() }) { Text(stringResource(R.string.cancel)) }
            }
        }

        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp)) {
            Text(stringResource(R.string.contact_sheet, roll.id.toString().padStart(2, '0')).uppercase(),
                color = Color(0xFF2A2522), fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.6.sp, modifier = Modifier.weight(1f))
            Text("${roll.exposures} · " + (if (roll.isBw) "B&W" else "COLOR") + if (roll.push != 0) " · PUSH ${if (roll.push > 0) "+" else ""}${roll.push}" else "",
                color = Color(0xFF2A2522), fontFamily = Mono, fontSize = 10.sp)
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
                        hasRelease = releases.any { it.frame == f.frame },
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
            if (selecting) stringResource(R.string.hint_select) else stringResource(R.string.hint_loupe),
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
            onCrop = { c, saved -> vm.printCrop(roll, f, c, saved) },
            onRelease = { loupe = null; releaseFrame = f.frame },
            onContest = if (eligible.isNotEmpty()) ({ loupe = null; sendFrame = f }) else null,
        )
    }

    if (confirmCut) {
        AlertDialog(
            onDismissRequest = { confirmCut = false },
            containerColor = Palette.Plate,
            title = { Text(stringResource(R.string.cut_title)) },
            text = {
                Text(stringResource(R.string.cut_text, selected.size, frames.size))
            },
            confirmButton = {
                PrimaryButton(onClick = {
                    vm.cutKeeping(roll, selected)
                    confirmCut = false; selecting = false; selected = emptySet()
                }) { Text(stringResource(R.string.cut)) }
            },
            dismissButton = { TextButton(onClick = { confirmCut = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Un fotogramma della striscia: perforazioni sopra e sotto, numero e nome dell'emulsione sul bordo. */
@Composable
private fun NegativeFrame(frame: FrameEntity, roll: RollEntity, hasRelease: Boolean, isSelected: Boolean, selecting: Boolean, onClick: () -> Unit) {
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
            // Sulla pellicola il fotogramma è sempre orizzontale: uno scatto in verticale ci finisce girato.
            val portrait = remember(frame.developedPath) {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(frame.developedPath, o)
                o.outHeight > o.outWidth
            }
            BoxWithConstraints(
                Modifier.fillMaxSize().clipToBounds().then(if (isSelected) Modifier.border(3.dp, Palette.Safelight) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = File(frame.developedPath),
                    contentDescription = stringResource(R.string.negative_n, frame.frame),
                    contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.colorMatrix(if (roll.isBw) bwNegative else colorNegative),
                    modifier = if (portrait) Modifier.requiredSize(maxHeight, maxWidth).graphicsLayer { rotationZ = -90f }
                    else Modifier.fillMaxSize(),
                )
            }
            if (frame.printUri != null) {
                Text(
                    stringResource(R.string.printed_badge),
                    color = Color.White, fontFamily = Mono, fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color(0x99000000)).padding(horizontal = 4.dp),
                )
            }
            if (hasRelease) {
                Text(
                    stringResource(R.string.release_badge),
                    color = Color.White, fontFamily = Mono, fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color(0x99000000)).padding(horizontal = 4.dp),
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

private const val HOLES_PER_FRAME = 8

/** Banda con le perforazioni; quella superiore porta le scritte di bordo. */
@Composable
private fun Sprockets(base: Color, label: String?, number: Int) {
    Box(Modifier.fillMaxWidth().height(22.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(base)
            val holeW = 7.dp.toPx()
            val holeH = 5.dp.toPx()
            // 8 perforazioni per fotogramma, come sul 35 mm vero, distribuite in modo uniforme.
            val step = size.width / HOLES_PER_FRAME
            val y = if (label != null) size.height - holeH - 3.dp.toPx() else 3.dp.toPx()
            repeat(HOLES_PER_FRAME) { i ->
                drawRoundRect(Color(0xFFE9E6DF), Offset(step * i + (step - holeW) / 2, y), Size(holeW, holeH), CornerRadius(1.5f.dp.toPx()))
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
private fun Loupe(frame: FrameEntity, onClose: () -> Unit, onPrint: () -> Unit, onCrop: (CropRect, () -> Unit) -> Unit, onRelease: () -> Unit, onContest: (() -> Unit)? = null) {
    var cropping by remember { mutableStateOf(false) }
    // Dopo il taglio: la stampa tagliata resta un attimo sul tavolo, con l'etichetta del formato.
    var cut by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(cut) { if (cut != null) { delay(2800); cut = null } }
    Dialog(onDismissRequest = { if (cropping) cropping = false else onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        if (cropping) {
            CropEditor(frame.developedPath, onCancel = { cropping = false }, onDone = { c -> cropping = false; onCrop(c) { cut = c.tag.replace('x', ':') } })
            return@Dialog
        }
        Column(
            Modifier.fillMaxSize().background(Color(0xEE0C0B0B)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = File(frame.developedPath),
                    contentDescription = stringResource(R.string.positive_n, frame.frame),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)),
                )
                cut?.let { CutBanner(it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.frame_n, frame.frame) + if (frame.exposureCount > 1) stringResource(R.string.n_exposures_suffix, frame.exposureCount) else "", color = Palette.Muted, fontFamily = Mono, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                if (frame.printUri == null) PrimaryButton(onClick = onPrint) { Text(stringResource(R.string.print)) }
                else Text(stringResource(R.string.already_printed), color = Palette.Ink)
                SecondaryButton(onClick = { cropping = true }) { Text(stringResource(R.string.crop)) }
                SecondaryButton(onClick = onRelease) { Text(stringResource(R.string.release)) }
                if (onContest != null) PrimaryButton(onClick = onContest) { Text(stringResource(R.string.ct_send_short)) }
                SecondaryButton(onClick = onClose) { Text(stringResource(R.string.back_on_table)) }
            }
        }
    }
}

/** Il cartellino "tagliata": forbici, formato e dove è finita. */
@Composable
private fun CutBanner(ratio: String) {
    Column(
        Modifier
            .background(Color(0xE61B0E09), RoundedCornerShape(4.dp))
            .border(1.dp, Palette.Safelight, RoundedCornerShape(4.dp))
            .padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("✂  " + stringResource(R.string.crop_done_title, ratio), color = Palette.Safelight, fontFamily = Mono, fontSize = 16.sp, letterSpacing = 1.5.sp)
        Text(stringResource(R.string.crop_done_where), color = Palette.Ink, fontSize = 12.sp)
    }
}

@Composable
private fun stateLabel(r: RollEntity) = when (r.state) {
    RollState.LOADED -> stringResource(R.string.state_loaded)
    RollState.SHELVED -> stringResource(R.string.state_shelved)
    RollState.SHARED -> stringResource(R.string.state_shared)
    RollState.FINISHED -> stringResource(R.string.state_finished)
    RollState.DEVELOPING -> stringResource(R.string.state_developing)
    else -> stringResource(R.string.negatives_arrow)
}

private fun date(ms: Long): String = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ms))
