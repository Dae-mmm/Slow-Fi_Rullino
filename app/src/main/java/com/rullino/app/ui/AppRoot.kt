package com.rullino.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        vm.message.value = null
    }

    Scaffold(
        containerColor = Palette.Body,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = Palette.Plate) {
                AppTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Text(t.glyph, fontSize = 20.sp) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Palette.Safelight,
                            selectedTextColor = Palette.Ink,
                            unselectedIconColor = Palette.Muted,
                            unselectedTextColor = Palette.Muted,
                            indicatorColor = Palette.PlateHi,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                AppTab.CAMERA -> CameraScreen(vm, onPickEmulsion = { tab = AppTab.EMULSIONS }, onOpenRolls = { tab = AppTab.ROLLS })
                AppTab.EMULSIONS -> EmulsionsScreen(vm, onLoaded = { tab = AppTab.CAMERA })
                AppTab.ROLLS -> RollsScreen(vm)
            }
        }
    }
}
