package com.rullino.app.ui

import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import com.rullino.app.R
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

enum class AppTab(@StringRes val label: Int, val glyph: String) {
    CAMERA(R.string.tab_camera, "◉"),
    ROLLS(R.string.tab_rolls, "▣"),
    SHOP(R.string.tab_shop, "⌂"),
    EMULSIONS(R.string.tab_emulsions, "▥"),
    OPTIONS(R.string.tab_options, "⚙"),
}

@Composable
fun AppRoot(importUri: Uri?, onImportHandled: () -> Unit, vm: AppViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(AppTab.CAMERA) }
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsState()

    LaunchedEffect(importUri) {
        if (importUri != null) {
            if (vm.settings.developerMode.value) {
                vm.importFrom(importUri)
                tab = AppTab.EMULSIONS
            } else {
                vm.say(R.string.import_needs_dev)
            }
            onImportHandled()
        }
    }
    // Modalità sviluppatore solo per gli account OP: si accende e si spegne col ruolo.
    val profile by vm.account.profile.collectAsState()
    val signedIn by vm.account.signedIn.collectAsState()
    LaunchedEffect(profile, signedIn) {
        val p = profile
        if (!signedIn) vm.settings.setDeveloperMode(false)
        else if (p != null) vm.settings.setDeveloperMode(p.role == com.rullino.app.data.Role.OP)
    }

    val deleteRequest by vm.galleryDeleteRequest.collectAsState()
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.galleryDeleteRequest.value = null
    }
    LaunchedEffect(deleteRequest) {
        deleteRequest?.let { deleteLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }

    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        vm.message.value = null
    }

    Scaffold(
        containerColor = Palette.Body,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // In orizzontale la navigazione sta di lato, come una fascia del corpo macchina.
        Row(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Tiene il menù lontano dal foro della fotocamera e dagli angoli arrotondati dello schermo.
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(end = 8.dp),
        ) {
            NavRail(tab, onTab = { tab = it })
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    AppTab.CAMERA -> CameraScreen(vm, onGoShop = { tab = AppTab.SHOP }, onOpenRolls = { tab = AppTab.ROLLS })
                    AppTab.SHOP -> ShopScreen(vm)
                    AppTab.EMULSIONS -> EmulsionsScreen(vm, onLoaded = { tab = AppTab.CAMERA })
                    AppTab.ROLLS -> RollsScreen(vm, onOpenCamera = { tab = AppTab.CAMERA }, onGoLab = { tab = AppTab.SHOP })
                    AppTab.OPTIONS -> OptionsScreen(vm)
                }
            }
        }
    }
}

/** Fascia di navigazione a sinistra, come nel design: marchio S—F e voci con segno e nome. */
@Composable
private fun NavRail(tab: AppTab, onTab: (AppTab) -> Unit) {
    Column(
        Modifier
            .width(68.dp)
            .fillMaxHeight()
            .background(Palette.Deep)
            .drawBehind { drawLine(Color(0xFF282422), Offset(size.width, 0f), Offset(size.width, size.height), 1f) }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "S—F", color = Palette.Muted, fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 2.sp,
            modifier = Modifier.border(1.dp, Color(0xFF514A46)).padding(horizontal = 6.dp, vertical = 4.dp),
        )
        Spacer(Modifier.weight(1f))
        AppTab.entries.forEach { t ->
            val on = tab == t
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .background(if (on) Brush.horizontalGradient(listOf(Color(0xFF291711), Color.Transparent)) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)))
                    .drawBehind { if (on) drawRect(Palette.Safelight, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height)) }
                    .clickable { onTab(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val c = if (on) Palette.Safelight else Palette.Faint
                Text(t.glyph, color = c, fontFamily = Mono, fontSize = 20.sp, lineHeight = 22.sp)
                Text(stringResource(t.label).uppercase(), color = c, fontSize = 9.sp, letterSpacing = 0.8.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.weight(1f))
    }
}
