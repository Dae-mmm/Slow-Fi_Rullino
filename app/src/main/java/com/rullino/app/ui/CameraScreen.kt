package com.rullino.app.ui

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
    val blackout = remember { Animatable(0f) }
    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    DisposableEffect(Unit) { onDispose { sound.release() } }

    val loaded = r != null && r.state == RollState.LOADED
    val canWind = r?.canWind == true
    val armed = loaded && r != null && r.wound

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
                busy = false
            }

            override fun onError(exception: ImageCaptureException) {
                vm.say("Lo scatto non è riuscito, riprova")
                busy = false
            }
        })
    }

    var showBack by remember { mutableStateOf(false) }
    var releaseFrame by remember { mutableStateOf<Int?>(null) }
    releaseFrame?.let { f -> r?.let { roll -> ReleasesDialog(vm, roll, f, onClose = { releaseFrame = null }) } }
    val doubleOnNext by remember(r?.id, r?.cursor) {
        if (r == null) kotlinx.coroutines.flow.flowOf(0) else vm.exposuresOnFrame(r.id, r.cursor)
    }.collectAsState(initial = 0)

    if (showBack && r != null) {
        BackDialog(
            roll = r,
            onDismiss = { showBack = false },
            onShelve = { showBack = false; vm.shelve(r) },
            onDelete = { showBack = false; vm.deleteRoll(r) },
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
    val turnsNeeded = maxOf(2, ((r?.shotCount ?: 0) + 5) / 6)

    val counter = when {
        r == null -> 0
        r.wound -> r.cursor
        else -> r.cursor - 1
    }

    Column(Modifier.fillMaxSize().background(Palette.Body)) {
        // ---- Calotta superiore: riavvolgimento, targhetta, contafotogrammi, scatto, leva ----
        Row(
            Modifier.fillMaxWidth().height(72.dp).brushedMetal().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RewindCrank(
                    enabled = r != null && r.shotCount > 0,
                    highlight = releaseOn || r?.isAtEnd == true,
                    onFullTurn = {
                        val roll = r ?: return@RewindCrank
                        if (releaseOn) {
                            unloadTurns++
                            if (unloadTurns >= turnsNeeded) { releaseOn = false; unloadTurns = 0; vm.rewindOut(roll) }
                        } else if (roll.cursor > 1) {
                            vm.rewindFrame(roll)
                        } else view.reject()
                    },
                )
            }
            ReleaseButton(
                on = releaseOn,
                enabled = r != null && r.shotCount > 0,
                onToggle = { releaseOn = !releaseOn; unloadTurns = 0 },
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Column(Modifier.padding(start = 14.dp, bottom = 12.dp)) {
                Engraving("ISO ${r?.iso ?: "—"}", size = 10)
                Engraving(r?.emulsionName?.uppercase() ?: "VUOTA", size = 10)
            }
            Spacer(Modifier.weight(1f))
            Engraving("RULLINO", modifier = Modifier.padding(bottom = 22.dp), size = 15)
            Spacer(Modifier.weight(1f))
            FrameCounter(number = counter, total = r?.exposures ?: 36, modifier = Modifier.padding(bottom = 10.dp))
            Spacer(Modifier.width(14.dp))
            ShutterButton(armed = armed && !busy, onClick = { shoot() })
            AdvanceLever(
                enabled = canWind,
                onAdvanced = { r?.let { vm.wind(it) } },
                onProgress = { windProgress = it },
            )
        }

        // ---- Corpo: similpelle, finestrella, mirino ----
        Row(Modifier.fillMaxWidth().weight(1f).leatherette().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.width(150.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BackWindow(emulsion, r)
                Text(
                    when {
                        r == null -> "Nessun rullino"
                        releaseOn -> "Riavvolgimento ${unloadTurns * 100 / turnsNeeded}%\ngira la manovella"
                        r.isAtEnd -> "Finito · premi R\ne gira la manovella"
                        r.cursor <= r.shotCount -> "Posa ${r.cursor} già esposta\n${r.shotCount} di ${r.exposures} pose"
                        else -> "${r.shotCount} di ${r.exposures} pose"
                    },
                    color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp, textAlign = TextAlign.Center,
                )
                if (r != null && r.state == RollState.LOADED && r.shotCount == 0) {
                    TextButton(onClick = { vm.unloadEmptyRoll(r) }) { Text("Cambia rullino", color = Palette.Muted, fontSize = 12.sp) }
                } else if (r != null && r.state == RollState.LOADED) {
                    OutlinedButton(onClick = { showBack = true }) { Text("Apri il dorso", fontSize = 12.sp) }
                    if (r.cursor > 1) {
                        TextButton(onClick = { releaseFrame = r.cursor - 1 }) {
                            Text("Liberatoria posa ${r.cursor - 1}", color = Palette.Muted, fontSize = 12.sp)
                        }
                    }
                } else if (r == null) {
                    TextButton(onClick = onOpenRolls) { Text("Rullini sviluppati", color = Palette.Muted, fontSize = 12.sp) }
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
                        onReady = { capture = it },
                        doubleExposure = doubleOnNext > 0,
                    )
                    Box(Modifier.fillMaxSize().alpha(blackout.value).background(Color.Black))
                }
                Spacer(Modifier.height(8.dp))
                FilmStrip(progress = windProgress, modifier = Modifier.fillMaxWidth(0.7f))
            }
            Spacer(Modifier.width(150.dp))
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
    onReady: (ImageCapture) -> Unit,
    doubleExposure: Boolean,
) {
    when {
        !hasPermission -> CenterNote("Serve il permesso della fotocamera per scattare.") {
            OutlinedButton(onClick = onAskPermission) { Text("Concedi") }
        }
        r == null -> CenterNote("La macchina è vuota.") {
            Button(onClick = onPickEmulsion) { Text("Scegli un rullino") }
        }
        r.isAtEnd -> CenterNote("Rullino finito: ${r.exposures} pose.\nPremi R e gira la manovella per riavvolgerlo.") {}
        r.state == RollState.DEVELOPING -> CenterNote("Sviluppo in corso…") {
            if (progress != null && progress.rollId == r.id && progress.total > 0) {
                LinearProgressIndicator(progress = { progress.done / progress.total.toFloat() }, modifier = Modifier.width(200.dp))
                Text("${progress.done} di ${progress.total}", color = Palette.Muted, fontFamily = Mono, fontSize = 12.sp)
            } else {
                LinearProgressIndicator(modifier = Modifier.width(200.dp))
            }
        }
        else -> {
            Viewfinder(onReady = onReady)
            if (doubleExposure) {
                Text(
                    "DOPPIA ESPOSIZIONE · POSA ${r.cursor}",
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
                    if (r.shotCount == 0) "Carica la leva per portare avanti la pellicola" else "Carica la leva",
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
) {
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Plate,
        title = { Text("${roll.emulsionName} · ${roll.shotCount} di ${roll.exposures} pose") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onShelve) { Text("Metti da parte per finirlo più avanti") }
                Text(
                    "Per riavvolgere usa la manovella in alto a sinistra:\n" +
                        "• senza R, ogni giro torna indietro di una posa (doppia esposizione);\n" +
                        "• con R acceso, riavvolgi tutto e il rullino esce dalla macchina.",
                    color = Palette.Muted, fontSize = 12.sp,
                )
                TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                    Text(
                        if (confirmDelete) "Tocca di nuovo: elimini tutte le pose" else "Elimina il rullino",
                        color = Color(0xFFFF8A80),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi il dorso") } },
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
        title = { Text("Scegli un rullino") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (owned.isEmpty()) {
                    Text("Non hai rullini in magazzino. Il negozio ne regala uno al giorno.", color = Palette.Ink)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Pose", color = Palette.Muted)
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
                                Text("ISO ${e.iso} · ne hai ${inventory[e.id]}", color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp)
                            }
                            Button(onClick = { vm.loadRoll(e, exposures, onDismiss) }) { Text("Carica") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onGoShop) { Text(if (owned.isEmpty()) "Vai al negozio" else "Compra altri rullini") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
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
            roll == null -> Text("vuota", color = Palette.Muted, fontSize = 12.sp, fontFamily = Mono)
            canister != null && canister.exists() -> AsyncImage(
                model = canister,
                contentDescription = "Rullino ${roll.emulsionName}",
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
private fun Viewfinder(onReady: (ImageCapture) -> Unit) {
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
            p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            onReady(imageCapture)
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll() }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}
