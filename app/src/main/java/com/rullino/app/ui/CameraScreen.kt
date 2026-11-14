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
fun CameraScreen(vm: AppViewModel, onPickEmulsion: () -> Unit, onOpenRolls: () -> Unit) {
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
    val canWind = loaded && r != null && !r.wound && r.shotCount < r.exposures
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

    Column(Modifier.fillMaxSize().background(Palette.Body).padding(horizontal = 16.dp)) {
        // ---- Calotta superiore: contafotogrammi e finestrella ----
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackWindow(emulsion, r)
            Spacer(Modifier.weight(1f))
            FrameCounter(
                number = when {
                    r == null -> 0
                    r.wound -> r.shotCount + 1
                    else -> r.shotCount
                },
                total = r?.exposures ?: 36,
            )
        }

        // ---- Mirino ----
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
                .border(1.dp, Palette.Line, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                !hasPermission -> CenterNote("Serve il permesso della fotocamera per scattare.") {
                    OutlinedButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Concedi") }
                }
                r == null -> CenterNote("La macchina è vuota.") {
                    Button(onClick = onPickEmulsion) { Text("Carica un rullino") }
                }
                r.state == RollState.FINISHED -> CenterNote("Rullino finito: ${r.exposures} pose.\nRiavvolgilo per svilupparlo.") {
                    Button(onClick = { view.confirm(); vm.develop(r) }) { Text("Riavvolgi e sviluppa") }
                }
                r.state == RollState.DEVELOPING -> {
                    val p = progress
                    CenterNote("Sviluppo in corso…") {
                        if (p != null && p.rollId == r.id && p.total > 0) {
                            LinearProgressIndicator(progress = { p.done / p.total.toFloat() }, modifier = Modifier.width(200.dp))
                            Text("${p.done} di ${p.total}", color = Palette.Muted, fontFamily = Mono, fontSize = 12.sp)
                        } else {
                            LinearProgressIndicator(modifier = Modifier.width(200.dp))
                        }
                    }
                }
                else -> {
                    Viewfinder(onReady = { capture = it })
                    if (!r.wound) {
                        Text(
                            if (r.shotCount == 0) "Carica la leva per portare avanti la pellicola" else "Carica la leva",
                            color = Palette.Ink,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(12.dp)
                                .background(Color(0xAA000000), RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
            Box(Modifier.fillMaxSize().alpha(blackout.value).background(Color.Black))
        }

        Spacer(Modifier.height(10.dp))
        FilmStrip(progress = windProgress)

        // ---- Comandi: leva e otturatore ----
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            AdvanceLever(
                enabled = canWind,
                onAdvanced = { r?.let { vm.wind(it) } },
                onProgress = { windProgress = it },
            )
            ShutterButton(armed = armed && !busy, onClick = { shoot() })
        }

        if (r != null && r.state == RollState.LOADED && r.shotCount == 0) {
            TextButton(onClick = { vm.unloadEmptyRoll(r) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Cambia emulsione", color = Palette.Muted)
            }
        } else if (r == null) {
            TextButton(onClick = onOpenRolls, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Vedi i rullini sviluppati", color = Palette.Muted)
            }
        } else {
            Spacer(Modifier.height(48.dp))
        }
    }
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
            .size(width = 132.dp, height = 64.dp)
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

@Composable
private fun ShutterButton(armed: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(84.dp)
            .clip(CircleShape)
            .background(Palette.PlateHi)
            .border(3.dp, if (armed) Palette.Chrome else Palette.Line, CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(if (armed) Palette.Safelight else Palette.Line)
        )
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
