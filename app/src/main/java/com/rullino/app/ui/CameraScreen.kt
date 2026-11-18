package com.rullino.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import com.rullino.app.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.rullino.app.data.EmulsionEntity
import com.rullino.app.data.RollEntity
import com.rullino.app.data.RollState
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt
import androidx.camera.core.Camera

@Composable
fun CameraScreen(vm: AppViewModel, onGoShop: () -> Unit, onOpenRolls: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val roll by vm.activeRoll.collectAsState()
    val progress by vm.developProgress.collectAsState()

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    val r = roll
    val emulsion by produceState<EmulsionEntity?>(null, r?.emulsionId) {
        value = r?.emulsionId?.let { vm.repo.emulsion(it) }
    }

    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var busy by remember { mutableStateOf(false) }
    var windProgress by remember { mutableFloatStateOf(0f) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var focal by remember { mutableFloatStateOf(24f) }
    val shutterIndex by vm.settings.shutterIndex.collectAsState()
    val shutterCd = stringResource(R.string.shutter_speed)
    var showFocal by remember { mutableStateOf(false) }
    // 24 mm equivalenti = zoom 1× sul principale; il massimo dipende dal telefono (al più 135 mm).
    val maxRatio by produceState(1f, camera) {
        value = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
    }
    val maxFocal = (24f * maxRatio).coerceAtMost(135f)
    LaunchedEffect(focal, camera) {
        camera?.cameraControl?.setZoomRatio((focal / 24f).coerceIn(1f, maxRatio))
    }
    LaunchedEffect(focal) {
        showFocal = true
        kotlinx.coroutines.delay(900)
        showFocal = false
    }
    val blackout = remember { Animatable(0f) }
    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    DisposableEffect(Unit) { onDispose { sound.release() } }

    val loaded = r != null && r.state == RollState.LOADED
    val canWind = r?.canWind == true
    val armed = loaded && r != null && r.wound

    // Dopo ogni scatto compare per qualche secondo una scorciatoia per la liberatoria.
    var lastShot by remember { mutableStateOf<Int?>(null) }
    var lastShotAt by remember { mutableStateOf(0L) }
    LaunchedEffect(lastShotAt) {
        if (lastShotAt > 0) { delay(8000); lastShot = null }
    }

    // Esposimetro: la luce della scena letta dall'anteprima, poi confrontata con pellicola e tempo.
    var ev100 by remember { mutableStateOf<Double?>(null) }

    fun shoot() {
        val current = r ?: return
        val cap = capture
        if (!armed || busy || cap == null) { view.reject(); return }
        busy = true
        sound.play(MediaActionSound.SHUTTER_CLICK)
        val file: File = vm.rawFileFor(current)
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        // Quello che legge l'esposimetro adesso: lo sviluppo partirà da qui.
        val meterEv = ev100
        val chosen = SHUTTER_SPEEDS.getOrNull(shutterIndex)
        scope.launch {
            // Breve buio dello specchio; il tempo scelto agisce sull'esposizione della pellicola (sviluppo),
            // il mirino resta sempre in automatico.
            blackout.snapTo(1f)
            blackout.animateTo(0f, tween(220))
        }
        run {
            fun done() { busy = false }
            cap.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    vm.recordShot(current.id, file, chosen, meterEv)
                    lastShot = current.cursor
                    lastShotAt = System.currentTimeMillis()
                    done()
                }

                override fun onError(exception: ImageCaptureException) {
                    vm.say(R.string.shot_failed)
                    done()
                }
            })
        }
    }

    var releaseFrame by remember { mutableStateOf<Int?>(null) }
    releaseFrame?.let { f -> r?.let { roll -> ReleasesDialog(vm, roll, f, onClose = { releaseFrame = null }, maxFrame = roll.shotCount) } }
    val doubleOnNext by remember(r?.id, r?.cursor) {
        if (r == null) kotlinx.coroutines.flow.flowOf(0) else vm.exposuresOnFrame(r.id, r.cursor)
    }.collectAsState(initial = 0)


    // Scelta del rullino da caricare, tra quelli in magazzino.
    var showPicker by remember { mutableStateOf(false) }
    if (showPicker) {
        RollPickerDialog(vm, onDismiss = { showPicker = false }, onGoShop = { showPicker = false; onGoShop() })
    }

    // Riavvolgimento: con R premuto ogni giro di manovella torna indietro di una posa (doppia esposizione).
    var releaseOn by remember(r?.id) { mutableStateOf(false) }
    val crankLabel = stringResource(R.string.crank)
    // Dopo uno scatto il pulsante R torna su da solo.
    LaunchedEffect(lastShotAt) { if (lastShotAt > 0) releaseOn = false }

    val meter = if (r != null && r.state == RollState.LOADED) ev100?.let { meterReading(it, r.iso, shutterIndex) } else null

    val dbCounter = when {
        r == null -> 0
        r.wound -> r.cursor
        else -> r.cursor - 1
    }
    // Numero mostrato subito a fine giro, finché il database non conferma il riavvolgimento.
    var optimistic by remember(r?.id, r?.cursor, r?.wound) { mutableStateOf<Int?>(null) }
    val counter = optimistic ?: dbCounter
    var crank by remember(r?.id, releaseOn) { mutableFloatStateOf(0f) }
    // Dove sta andando il contatore mentre si gira la manovella (null = fermo).
    val rollPair: Pair<Int, Pair<Int, Float>>? = when {
        r == null || r.shotCount == 0 -> null
        !releaseOn || crank <= 0f -> null
        optimistic == null && r.cursor > 1 && counter != r.cursor - 1 -> Pair(counter, Pair(r.cursor - 1, crank))
        optimistic != null && counter > 1 -> Pair(counter, Pair(counter - 1, crank))
        else -> null
    }

    Column(Modifier.fillMaxSize().background(Palette.Body)) {
        // ---- Calotta (design): ISO e pellicola, targhetta e ghiera dei tempi, contapose ----
        Row(
            Modifier.fillMaxWidth().height(78.dp).darkMetal().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(150.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("ISO", color = Palette.Muted, fontSize = 11.sp, letterSpacing = 1.4.sp, modifier = Modifier.padding(end = 4.dp, bottom = 4.dp))
                    Text(r?.iso?.toString() ?: "—", color = Palette.Ink, fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 25.sp)
                }
                Text(
                    (r?.emulsionName ?: stringResource(R.string.engr_empty)).uppercase(),
                    color = Palette.Muted, fontSize = 10.sp, letterSpacing = 1.2.sp, maxLines = 1,
                )
            }
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                EngravedNote("SHUTTER SPEED", modifier = Modifier.padding(top = 7.dp))
                ShutterDial(
                    index = shutterIndex,
                    onIndex = { vm.settings.setShutterIndex(it) },
                    recommended = meter?.recommendedIndex,
                    modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth(0.95f).padding(top = 3.dp).semantics { contentDescription = shutterCd },
                )
            }
            Row(Modifier.width(150.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.eng_frame), color = Palette.Ink, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.padding(end = 8.dp))
                FrameCounter(
                    number = rollPair?.first ?: counter,
                    total = r?.exposures ?: 36,
                    rollTo = rollPair?.second?.first,
                    rollProgress = rollPair?.second?.second ?: 0f,
                )
            }
        }

        // ---- Corpo: similpelle, finestrella, mirino ----
        Row(Modifier.fillMaxWidth().weight(1f).dottedLeather().padding(horizontal = 15.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.width(150.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            // Manovella di riavvolgimento vista dall'alto, con il pulsante R.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReleaseButton(
                    on = releaseOn,
                    enabled = r != null && r.shotCount > 0 && r.state == RollState.LOADED,
                    onToggle = { releaseOn = !releaseOn },
                )
                EngravedNote("REWIND")
            }
            RewindCrank(
                enabled = r != null && r.shotCount > 0 && releaseOn,
                resetKey = Pair(r?.id, releaseOn),
                onProgress = { crank = it },
                highlight = releaseOn,
                onFullTurn = {
                    val roll = r ?: return@RewindCrank
                    if (roll.cursor > 1) {
                        // Torna alla posa precedente, già pronta: lo scatto successivo ci va sopra.
                        optimistic = roll.cursor - 1
                        vm.rewindFrame(roll)
                    } else view.reject()
                },
                diameter = 84.dp,
                modifier = Modifier.semantics { contentDescription = crankLabel },
            )
            BackWindow(emulsion, r)
            Text(
                when {
                    r == null -> stringResource(R.string.cam_no_roll)
                    releaseOn -> stringResource(R.string.cam_r_hint)
                    r.isAtEnd -> stringResource(R.string.cam_at_end)
                    r.cursor <= r.shotCount -> stringResource(R.string.cam_frame_exposed, r.cursor, r.shotCount, r.exposures)
                    else -> stringResource(R.string.cam_count, r.shotCount, r.exposures)
                },
                color = Palette.Muted, fontSize = 12.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp, textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Spacer(Modifier.weight(1f))
            // Un solo tasto: carica un rullino, o scaricalo (lo ritrovi in Rullini).
            if (r == null) {
                PrimaryButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cam_load)) }
            } else {
                SecondaryButton(onClick = { releaseOn = false; vm.unload(r) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cam_unload)) }
            }
            }

            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .weight(1f)
                        .aspectRatio(1.5f, matchHeightConstraintsFirst = true)
                        .clip(RoundedCornerShape(15.dp))
                        .background(Color(0xFF080808))
                        .padding(10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF090909)),
                    contentAlignment = Alignment.Center,
                ) {
                    ViewfinderContent(
                        vm = vm,
                        r = r,
                        hasPermission = hasPermission,
                        onAskPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        onPickEmulsion = { showPicker = true },
                        progress = progress,
                        onReady = { cap, cam -> capture = cap; camera = cam },
                        onMeter = { ev100 = it },
                        doubleExposure = doubleOnNext > 0,
                    )
                    if (meter != null && r != null && !r.isAtEnd) {
                        ExposureMeter(
                            meter,
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).widthIn(max = 260.dp).fillMaxWidth(0.62f)
                                .clip(RoundedCornerShape(3.dp)).background(Color(0xB30A0908)).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    if (showFocal && camera != null) {
                        ViewfinderBadge(
                            shutterLabel(SHUTTER_SPEEDS.getOrNull(shutterIndex)) + " · ${focal.roundToInt()} MM",
                            Modifier.align(Alignment.TopEnd).padding(8.dp),
                        )
                    }
                    val chipFrame = lastShot
                    if (chipFrame != null && r != null && r.state == RollState.LOADED) {
                        Text(
                            stringResource(R.string.chip_release, chipFrame),
                            color = Palette.Ink, fontSize = 13.sp,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(10.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xCC000000))
                                .clickable { releaseFrame = chipFrame; lastShot = null }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    ViewfinderGlass()
                    Box(Modifier.fillMaxSize().alpha(blackout.value).background(Color.Black))
                }
                Spacer(Modifier.height(4.dp))
                ZoomRing(
                    focal = focal,
                    maxFocal = maxFocal,
                    onFocal = { focal = it },
                    enabled = camera != null,
                    modifier = Modifier.widthIn(max = 350.dp).fillMaxWidth(0.9f),
                )
                EngravedNote("FOCAL LENGTH · MM", modifier = Modifier.padding(top = 3.dp))
            }
            // Leva di carica e pulsante di scatto visti dall'alto, sotto il pollice destro.
            Column(
                Modifier.width(150.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EngravedNote(
                    if (armed && !busy) stringResource(R.string.eng_armed) else stringResource(R.string.eng_wind),
                    color = if (armed && !busy) Palette.Safelight else Color(0xFF756E68),
                )
                TopLeverShutter(
                    armed = armed && !busy,
                    leverEnabled = canWind,
                    onShoot = { shoot() },
                    onAdvanced = { r?.let { vm.wind(it) } },
                    onProgress = { windProgress = it },
                )
                EngravedNote(stringResource(R.string.eng_lever), modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** Vetro del mirino: vignettatura, righe sottili e angoli di messa a fuoco (design). */
@Composable
private fun ViewfinderGlass() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.radialGradient(0.55f to Color.Transparent, 1f to Color(0x88000000), radius = size.maxDimension * 0.62f))
        var y = 0f
        while (y < size.height) { drawRect(Color(0x0B000000), Offset(0f, y + 3f), androidx.compose.ui.geometry.Size(size.width, 1f)); y += 4f }
        val c = Color(0xAAF2EEE9); val l = 20.dp.toPx(); val sw = 1.dp.toPx()
        val tl = Offset(size.width * 0.28f, size.height * 0.28f)
        drawLine(c, tl, tl + Offset(l, 0f), sw); drawLine(c, tl, tl + Offset(0f, l), sw)
        val br = Offset(size.width * 0.72f, size.height * 0.72f)
        drawLine(c, br, br - Offset(l, 0f), sw); drawLine(c, br, br - Offset(0f, l), sw)
    }
}

/** Etichetta nel mirino: mono piccolo, fondo scuro, bordo sottile. */
@Composable
internal fun ViewfinderBadge(text: String, modifier: Modifier = Modifier, color: Color = Color(0xFFF2EEE9), round: Boolean = false) {
    val shape = RoundedCornerShape(if (round) 20.dp else 2.dp)
    Text(
        text, color = color, fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 10.sp,
        modifier = modifier.clip(shape).background(Color(0xAA0C0B0A)).border(1.dp, Color(0x558D847A), shape)
            .padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

@Composable
private fun BoxScope.ViewfinderContent(
    vm: AppViewModel,
    r: RollEntity?,
    hasPermission: Boolean,
    onAskPermission: () -> Unit,
    onPickEmulsion: () -> Unit,
    progress: com.rullino.app.develop.DevelopProgress?,
    onReady: (ImageCapture, Camera) -> Unit,
    onMeter: (Double) -> Unit,
    doubleExposure: Boolean,
) {
    when {
        !hasPermission -> CenterNote(stringResource(R.string.cam_need_permission)) {
            SecondaryButton(onClick = onAskPermission) { Text(stringResource(R.string.grant)) }
        }
        r == null -> CenterNote(stringResource(R.string.cam_empty)) {
            PrimaryButton(onClick = onPickEmulsion) { Text(stringResource(R.string.choose_roll)) }
        }
        r.isAtEnd -> CenterNote(stringResource(R.string.cam_finished_note, r.exposures)) {}
        r.state == RollState.DEVELOPING -> CenterNote(stringResource(R.string.developing)) {
            if (progress != null && progress.rollId == r.id && progress.total > 0) {
                LinearProgressIndicator(progress = { progress.done / progress.total.toFloat() }, modifier = Modifier.width(200.dp))
                Text(stringResource(R.string.x_of_y, progress.done, progress.total), color = Palette.Muted, fontFamily = Mono, fontSize = 12.sp)
            } else {
                LinearProgressIndicator(modifier = Modifier.width(200.dp))
            }
        }
        else -> {
            Viewfinder(onReady = onReady, onMeter = onMeter)
            if (doubleExposure) {
                ViewfinderBadge(
                    stringResource(R.string.double_exposure_badge, r.cursor),
                    Modifier.align(Alignment.TopStart).padding(8.dp),
                    color = Color(0xFFFFC0AA),
                )
            }
            if (!r.wound) {
                ViewfinderBadge(
                    if (r.shotCount == 0) stringResource(R.string.wind_first) else stringResource(R.string.wind),
                    Modifier.align(Alignment.TopCenter).padding(8.dp),
                    round = true,
                )
            }
        }
    }
}

/** I rullini che hai in magazzino: scegline uno da caricare. */
@Composable
private fun RollPickerDialog(vm: AppViewModel, onDismiss: () -> Unit, onGoShop: () -> Unit) {
    val emulsions by vm.emulsions.collectAsState()
    val inventory by vm.inventory.collectAsState()
    val owned = emulsions.filter { (inventory[it.id] ?: 0) > 0 }
    var exposures by remember { mutableIntStateOf(36) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Plate,
        title = { Text(stringResource(R.string.choose_roll)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Prima i rullini scaricati a metà: si riprende dalla posa dove si era rimasti.
                val shelved = vm.rolls.collectAsState().value.filter { it.state == RollState.SHELVED }
                if (shelved.isNotEmpty()) {
                    Text(stringResource(R.string.picker_continue), color = Palette.Muted)
                    shelved.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.PlateHi).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(s.emulsionName, color = Palette.Ink)
                                Text(stringResource(R.string.cam_count, s.shotCount, s.exposures), color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp)
                            }
                            PrimaryButton(onClick = { vm.reload(s, onDismiss) }) { Text(stringResource(R.string.load)) }
                        }
                    }
                    if (owned.isNotEmpty()) Text(stringResource(R.string.picker_new), color = Palette.Muted)
                }
                if (owned.isEmpty() && shelved.isEmpty()) {
                    Text(stringResource(R.string.picker_none), color = Palette.Ink)
                } else if (owned.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.exposures_label), color = Palette.Muted)
                        listOf(24, 36).forEach { n ->
                            FilterChip(selected = exposures == n, onClick = { exposures = n }, label = { Text("$n") })
                        }
                    }
                    owned.forEach { e ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.PlateHi).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(e.name, color = Palette.Ink)
                                Text(stringResource(R.string.iso_owned, e.iso, inventory[e.id] ?: 0), color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp)
                            }
                            PrimaryButton(onClick = { vm.loadRoll(e, exposures, onDismiss) }) { Text(stringResource(R.string.load)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onGoShop) { Text(if (owned.isEmpty()) stringResource(R.string.go_shop) else stringResource(R.string.buy_more)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun CenterNote(text: String, action: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(24.dp)) {
        Text(text, color = Palette.Ink, textAlign = TextAlign.Center)
        action()
    }
}

/** La finestrella sul dorso: mostra il rullino caricato. */
@Composable
private fun BackWindow(emulsion: EmulsionEntity?, roll: RollEntity?) {
    Box(
        Modifier
            .size(width = 120.dp, height = 56.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF211F1D))
            .border(3.dp, Color(0xFF080808), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val canister = emulsion?.canisterFile?.let { File(emulsion.dir, it) }
        when {
            roll == null -> Text(stringResource(R.string.window_empty).uppercase(), color = Color(0xFF625D57), fontSize = 10.sp, fontFamily = Mono)
            canister != null && canister.exists() -> AsyncImage(
                model = canister,
                contentDescription = stringResource(R.string.roll_cd, roll.emulsionName),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            else -> Cartridge(roll.emulsionName, roll.iso, roll.isBw, roll.exposures, scale = 0.62f)
        }
    }
}

/** Mirino CameraX: anteprima "pulita" (come un mirino ottico) e ImageCapture pronta allo scatto. */
@Composable
@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
private fun Viewfinder(onReady: (ImageCapture, Camera) -> Unit, onMeter: (Double) -> Unit) {
    val meterCallback = androidx.compose.runtime.rememberUpdatedState(onMeter)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            // Latenza minima: otturatore pronto come su una macchina vera.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    // L'app è bloccata in verticale: la rotazione dello scatto segue come tieni il telefono.
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                imageCapture.targetRotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = future.get()
            provider = p
            // L'anteprima fa da esposimetro: tempo, diaframma e ISO dei fotogrammi, letti qualche volta al secondo.
            val builder = Preview.Builder()
            var lastRead = 0L
            androidx.camera.camera2.interop.Camera2Interop.Extender(builder).setSessionCaptureCallback(
                object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: android.hardware.camera2.CameraCaptureSession,
                        request: android.hardware.camera2.CaptureRequest,
                        result: android.hardware.camera2.TotalCaptureResult,
                    ) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastRead < 250) return
                        val ns = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
                        val iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: return
                        val n = result.get(android.hardware.camera2.CaptureResult.LENS_APERTURE) ?: 1.8f
                        if (ns <= 0 || iso <= 0) return
                        lastRead = now
                        val ev = com.rullino.app.develop.VirtualCamera.ev100(ns / 1e9, n.toDouble(), iso)
                        // Solo un numero passa al thread dell'interfaccia: nessuna copia dell'immagine.
                        ContextCompat.getMainExecutor(context).execute { meterCallback.value(ev) }
                    }
                }
            )
            val preview = builder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            p.unbindAll()
            val cam = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            onReady(imageCapture, cam)
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll() }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}
