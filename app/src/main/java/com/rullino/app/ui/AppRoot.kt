package com.rullino.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
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

enum class AppTab(val label: String, val glyph: String) {
    CAMERA("Fotocamera", "◉"),
    EMULSIONS("Emulsioni", "▥"),
    ROLLS("Rullini", "▦"),
}

@Composable
fun AppRoot(importUri: Uri?, onImportHandled: () -> Unit, vm: AppViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(AppTab.CAMERA) }
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsState()

    LaunchedEffect(importUri) {
        if (importUri != null) {
            vm.importFrom(importUri)
            tab = AppTab.EMULSIONS
            onImportHandled()
        }
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
        Row(Modifier.fillMaxSize().padding(padding)) {
            NavigationRail(containerColor = Palette.Plate) {
                Spacer(Modifier.weight(1f))
                AppTab.entries.forEach { t ->
                    NavigationRailItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Text(t.glyph, fontSize = 20.sp) },
                        label = { Text(t.label, fontSize = 11.sp) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Palette.Safelight,
                            selectedTextColor = Palette.Ink,
                            unselectedIconColor = Palette.Muted,
                            unselectedTextColor = Palette.Muted,
                            indicatorColor = Palette.PlateHi,
                        ),
                    )
                }
                Spacer(Modifier.weight(1f))
            }
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    AppTab.CAMERA -> CameraScreen(vm, onPickEmulsion = { tab = AppTab.EMULSIONS }, onOpenRolls = { tab = AppTab.ROLLS })
                    AppTab.EMULSIONS -> EmulsionsScreen(vm, onLoaded = { tab = AppTab.CAMERA })
                    AppTab.ROLLS -> RollsScreen(vm, onOpenCamera = { tab = AppTab.CAMERA })
                }
            }
        }
    }
}
