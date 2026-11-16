package com.rullino.app.ui

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

    fun shoot() {
        val current = r ?: return
        val cap = capture
        if (!armed || busy || cap == null) { view.reject(); return }
        busy = true
        sound.play(MediaActionSound.SHUTTER_CLICK)
        scope.launch {
            blackout.snapTo(1f)
            blackout.animateTo(0f, tween(220))
        }
        val file: File = vm.rawFileFor(current)
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        cap.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                vm.recordShot(current.id, file)
                lastShot = current.cursor
                lastShotAt = System.currentTimeMillis()
                busy = false
            }

            override fun onError(exception: ImageCaptureException) {
                vm.say(R.string.shot_failed)
                busy = false
            }
        })
    }

    var showBack by remember { mutableStateOf(false) }
    var releaseFrame by remember { mutableStateOf<Int?>(null) }
    releaseFrame?.let { f -> r?.let { roll -> ReleasesDialog(vm, roll, f, onClose = { releaseFrame = null }, maxFrame = roll.shotCount) } }
    val doubleOnNext by remember(r?.id, r?.cursor) {
        if (r == null) kotlinx.coroutines.flow.flowOf(0) else vm.exposuresOnFrame(r.id, r.cursor)
    }.collectAsState(initial = 0)

    if (showBack && r != null) {
        BackDialog(
            roll = r,
            onDismiss = { showBack = false },
            onShelve = { showBack = false; vm.shelve(r) },
            onDelete = { showBack = false; vm.deleteRoll(r) },
            onRewindOut = { showBack = false; vm.rewindOut(r) },
        )
    }

    // Scelta del rullino da caricare, tra quelli in magazzino.
    var showPicker by remember { mutableStateOf(false) }
    if (showPicker) {
        RollPickerDialog(vm, onDismiss = { showPicker = false }, onGoShop = { showPicker = false; onGoShop() })
    }

    // Riavvolgimento: con R spento ogni giro di manovella torna indietro di una posa (doppia esposizione);
    // con R acceso la manovella riporta tutta la pellicola nel caricatore e il rullino esce dalla macchina.
    var releaseOn by remember(r?.id) { mutableStateOf(false) }
    var unloadTurns by remember(r?.id) { mutableIntStateOf(0) }
    val crankLabel = stringResource(R.string.crank)
    // Dopo uno scatto il pulsante R torna su da solo.
    LaunchedEffect(lastShotAt) { if (lastShotAt > 0) { releaseOn = false; unloadTurns = 0 } }
    val turnsNeeded = maxOf(2, ((r?.shotCount ?: 0) + 5) / 6)

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
        releaseOn && r.isAtEnd -> {
            val t = ((unloadTurns + crank) / turnsNeeded).coerceIn(0f, 1f)
            val v = dbCounter * (1f - t)
            val from = kotlin.math.ceil(v).toInt()
            if (from <= 0) null else Pair(from, Pair(from - 1, from - v))
        }
        !releaseOn || crank <= 0f -> null
        optimistic == null && r.cursor > 1 && counter != r.cursor - 1 -> Pair(counter, Pair(r.cursor - 1, crank))
        optimistic != null && counter > 1 -> Pair(counter, Pair(counter - 1, crank))
        else -> null
    }

    Column(Modifier.fillMaxSize().background(Palette.Body)) {
        // ---- Calotta superiore: riavvolgimento, targhetta, contafotogrammi, scatto, leva ----
        Row(
            Modifier.fillMaxWidth().height(72.dp).brushedMetal().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.padding(start = 14.dp, bottom = 12.dp)) {
                Engraving("ISO ${r?.iso ?: "—"}", size = 10)
                Engraving(r?.emulsionName?.uppercase() ?: stringResource(R.string.engr_empty), size = 10)
            }
            Spacer(Modifier.weight(1f))
            Engraving("RULLINO", modifier = Modifier.padding(bottom = 22.dp), size = 15)
            Spacer(Modifier.weight(1f))
            FrameCounter(
                number = rollPair?.first ?: counter,
                total = r?.exposures ?: 36,
                modifier = Modifier.padding(bottom = 10.dp),
                rollTo = rollPair?.second?.first,
                rollProgress = rollPair?.second?.second ?: 0f,
            )
            Spacer(Modifier.width(14.dp))
            ShutterButton(armed = armed && !busy, onClick = { shoot() })
            AdvanceLever(
                enabled = canWind,
                onAdvanced = { r?.let { vm.wind(it) } },
                onProgress = { windProgress = it },
            )
            // Margine a destra: il dito non deve arrivare al bordo dello schermo per finire la corsa.
            Spacer(Modifier.width(48.dp))
        }

        // ---- Corpo: similpelle, finestrella, mirino ----
        Row(Modifier.fillMaxWidth().weight(1f).leatherette().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.width(150.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BackWindow(emulsion, r)
                Text(
                    when {
                        r == null -> stringResource(R.string.cam_no_roll)
                        releaseOn && r.isAtEnd -> stringResource(R.string.cam_rewinding, unloadTurns * 100 / turnsNeeded)
                        releaseOn -> stringResource(R.string.cam_r_hint)
                        r.isAtEnd -> stringResource(R.string.cam_at_end)
                        r.cursor <= r.shotCount -> stringResource(R.string.cam_frame_exposed, r.cursor, r.shotCount, r.exposures)
                        else -> stringResource(R.string.cam_count, r.shotCount, r.exposures)
                    },
                    color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp, textAlign = TextAlign.Center,
                )
                if (r != null && r.state == RollState.LOADED && r.shotCount == 0) {
                    TextButton(onClick = { vm.unloadEmptyRoll(r) }) { Text(stringResource(R.string.cam_change_roll), color = Palette.Muted, fontSize = 12.sp) }
                } else if (r != null && r.state == RollState.LOADED) {
                    OutlinedButton(onClick = { showBack = true }) { Text(stringResource(R.string.cam_open_back), fontSize = 12.sp) }
                    if (r.shotCount > 0) {
                        OutlinedButton(onClick = { releaseFrame = (lastShot ?: (r.cursor - 1)).coerceIn(1, r.shotCount) }) {
                            Text(stringResource(R.string.cam_releases), fontSize = 12.sp)
                        }
                    }
                } else if (r == null) {
                    TextButton(onClick = onOpenRolls) { Text(stringResource(R.string.cam_developed_rolls), color = Palette.Muted, fontSize = 12.sp) }
                }
            }

            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .weight(1f)
                        .aspectRatio(1.5f, matchHeightConstraintsFirst = true)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black)
                        .border(3.dp, Color(0xFF0B0A0A), RoundedCornerShape(6.dp)),
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
                        doubleExposure = doubleOnNext > 0,
                    )
                    if (showFocal && camera != null) {
                        Text(
                            "${focal.roundToInt()} mm",
                            color = Palette.Ink, fontFamily = Mono, fontSize = 14.sp,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(10.dp)
                                .background(Color(0xAA000000), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
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
                    Box(Modifier.fillMaxSize().alpha(blackout.value).background(Color.Black))
                }
                Spacer(Modifier.height(8.dp))
                ZoomRing(
                    focal = focal,
                    maxFocal = maxFocal,
                    onFocal = { focal = it },
                    enabled = camera != null,
                    modifier = Modifier.fillMaxWidth(0.8f),
                )
            }
            // Manovella di riavvolgimento vista di fronte, a portata di pollice, con il pulsante R.
            Column(
                Modifier.width(150.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReleaseButton(
                        on = releaseOn,
                        enabled = r != null && r.shotCount > 0,
                        onToggle = { releaseOn = !releaseOn; unloadTurns = 0 },
                    )
                    Engraving("REWIND", size = 10)
                }
                RewindCrank(
                    enabled = r != null && r.shotCount > 0 && releaseOn,
                    resetKey = Pair(r?.id, releaseOn),
                    onProgress = { crank = it },
                    highlight = releaseOn,
                    onFullTurn = {
                        val roll = r ?: return@RewindCrank
                        if (roll.isAtEnd) {
                            unloadTurns++
                            if (unloadTurns >= turnsNeeded) { optimistic = 0; releaseOn = false; unloadTurns = 0; vm.rewindOut(roll) }
                        } else if (roll.cursor > 1) {
                            // Torna alla posa precedente, già pronta: lo scatto successivo ci va sopra.
                            optimistic = roll.cursor - 1
                            vm.rewindFrame(roll)
                        } else view.reject()
                    },
                    modifier = Modifier.semantics { contentDescription = crankLabel },
                )
            }
        }
    }
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
    doubleExposure: Boolean,
) {
    when {
        !hasPermission -> CenterNote(stringResource(R.string.cam_need_permission)) {
            OutlinedButton(onClick = onAskPermission) { Text(stringResource(R.string.grant)) }
        }
        r == null -> CenterNote(stringResource(R.string.cam_empty)) {
            Button(onClick = onPickEmulsion) { Text(stringResource(R.string.choose_roll)) }
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
            Viewfinder(onReady = onReady)
            if (doubleExposure) {
                Text(
                    stringResource(R.string.double_exposure_badge, r.cursor),
                    color = Palette.Safelight,
                    fontFamily = Mono,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(10.dp)
                        .background(Color(0xAA000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            if (!r.wound) {
                Text(
                    if (r.shotCount == 0) stringResource(R.string.wind_first) else stringResource(R.string.wind),
                    color = Palette.Ink,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(10.dp)
                        .background(Color(0xAA000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

/** Il dorso aperto: cosa fare del rullino in macchina. */
@Composable
private fun BackDialog(
    roll: RollEntity,
    onDismiss: () -> Unit,
    onShelve: () -> Unit,
    onDelete: () -> Unit,
    onRewindOut: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Plate,
        title = { Text(roll.emulsionName + " · " + stringResource(R.string.cam_count, roll.shotCount, roll.exposures)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onRewindOut) { Text(stringResource(R.string.rewind_all_out)) }
                OutlinedButton(onClick = onShelve) { Text(stringResource(R.string.shelve_long)) }
                Text(
                    stringResource(R.string.back_help),
                    color = Palette.Muted, fontSize = 12.sp,
                )
                TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                    Text(
                        if (confirmDelete) stringResource(R.string.delete_roll_confirm) else stringResource(R.string.delete_roll),
                        color = Color(0xFFFF8A80),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close_back)) } },
    )
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
                if (owned.isEmpty()) {
                    Text(stringResource(R.string.picker_none), color = Palette.Ink)
                } else {
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
                            Button(onClick = { vm.loadRoll(e, exposures, onDismiss) }) { Text(stringResource(R.string.load)) }
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
            .size(width = 132.dp, height = 70.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0C0B0B))
            .border(2.dp, Palette.Line, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val canister = emulsion?.canisterFile?.let { File(emulsion.dir, it) }
        when {
            roll == null -> Text(stringResource(R.string.window_empty), color = Palette.Muted, fontSize = 12.sp, fontFamily = Mono)
            canister != null && canister.exists() -> AsyncImage(
                model = canister,
                contentDescription = stringResource(R.string.roll_cd, roll.emulsionName),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(roll.emulsionName.uppercase(), color = Palette.Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("ISO ${roll.iso} · ${roll.exposures}", color = Palette.Safelight, fontSize = 10.sp, fontFamily = Mono)
            }
        }
    }
}

/** Mirino CameraX: anteprima "pulita" (come un mirino ottico) e ImageCapture pronta allo scatto. */
@Composable
private fun Viewfinder(onReady: (ImageCapture, Camera) -> Unit) {
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
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
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
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            p.unbindAll()
            val cam = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            onReady(imageCapture, cam)
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll() }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}
