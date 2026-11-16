package com.rullino.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.rullino.app.data.CatalogItem
import com.rullino.app.data.EmulsionEntity
import com.rullino.app.data.RollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.rullino.app.data.Shop
import java.io.File
import java.text.NumberFormat
import java.util.Locale

@Composable
fun EmulsionsScreen(vm: AppViewModel, onLoaded: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = page, containerColor = Palette.Body, contentColor = Palette.Ink) {
            Tab(selected = page == 0, onClick = { page = 0 }, text = { Text("Le tue") })
            Tab(selected = page == 1, onClick = { page = 1 }, text = { Text("Negozio") })
        }
        if (page == 0) MyEmulsions(vm, onLoaded) else Store(vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyEmulsions(vm: AppViewModel, onLoaded: () -> Unit) {
    val list by vm.emulsions.collectAsState()
    val inventory by vm.inventory.collectAsState()
    val devMode by vm.settings.developerMode.collectAsState()
    var selected by remember { mutableStateOf<EmulsionEntity?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFrom(uri)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${list.size} emulsioni", color = Palette.Muted, modifier = Modifier.weight(1f))
            if (devMode) OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Importa .emulsion") }
        }
        if (list.isEmpty()) {
            Text(
                "Nessuna emulsione installata.",
                color = Palette.Muted,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(list, key = { it.id }) { e -> EmulsionCard(e, inventory[e.id] ?: 0) { selected = e } }
        }
    }

    selected?.let { e ->
        ModalBottomSheet(onDismissRequest = { selected = null }, containerColor = Palette.Plate) {
            EmulsionDetail(e, vm, onLoaded = { selected = null; onLoaded() }, onDeleted = { selected = null })
        }
    }
}

@Composable
private fun EmulsionCard(e: EmulsionEntity, owned: Int, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Plate)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BoxArt(e, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(e.name, color = Palette.Ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Tag("ISO ${e.iso}")
            Tag(if (e.isBw) "BN" else "COLORE")
            if (owned > 0) Tag("×$owned")
        }
    }
}

@Composable
private fun BoxArt(e: EmulsionEntity, modifier: Modifier) {
    val box = e.boxFile?.let { File(e.dir, it) }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Palette.PlateHi), contentAlignment = Alignment.Center) {
        if (box != null && box.exists()) {
            AsyncImage(model = box, contentDescription = "Scatola di ${e.name}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp))
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(e.iso.toString(), color = Palette.Safelight, fontFamily = Mono, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(e.name.uppercase(), color = Palette.Ink, fontSize = 11.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text,
        color = Palette.Muted,
        fontFamily = Mono,
        fontSize = 10.sp,
        modifier = Modifier.border(1.dp, Palette.Line, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmulsionDetail(e: EmulsionEntity, vm: AppViewModel, onLoaded: () -> Unit, onDeleted: () -> Unit) {
    val active by vm.activeRoll.collectAsState()
    val inventory by vm.inventory.collectAsState()
    val coins by vm.shop.coins.collectAsState()
    val lastDaily by vm.shop.lastDaily.collectAsState()
    val owned = inventory[e.id] ?: 0
    val freeToday = lastDaily != vm.shop.today()
    var exposures by remember(e.id) { mutableIntStateOf(if (e.exposures == 24) 24 else 36) }
    val busyRoll = active?.let { it.shotCount > 0 } == true
    var confirmDelete by remember { mutableStateOf(false) }

    // In orizzontale: a sinistra l'emulsione, a destra le azioni. Tutto scorre se lo schermo è basso.
    Row(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BoxArt(e, Modifier.size(96.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.name, color = Palette.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("ISO ${e.iso} · ${if (e.isBw) "bianco e nero" else "colore"}", color = Palette.Muted, fontFamily = Mono, fontSize = 12.sp)
                    if (e.note.isNotBlank()) Text(e.note, color = Palette.Ink, fontSize = 13.sp)
                }
            }
            val demos = e.demos().map { File(e.dir, it) }.filter { it.exists() }
            if (demos.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(demos) { f ->
                        AsyncImage(
                            model = f,
                            contentDescription = "Foto di esempio",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.height(120.dp).aspectRatio(1.5f).clip(RoundedCornerShape(6.dp)),
                        )
                    }
                }
            }
            TextButton(onClick = {
                if (confirmDelete) { vm.deleteEmulsion(e); onDeleted() } else confirmDelete = true
            }) { Text(if (confirmDelete) "Tocca di nuovo per eliminare" else "Elimina emulsione", color = Color(0xFFFF8A80)) }
        }

        Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (owned > 0) "In magazzino: $owned ${if (owned == 1) "rullino" else "rullini"}" else "Nessun rullino in magazzino",
                color = if (owned > 0) Palette.Ink else Palette.Safelight, fontSize = 14.sp,
            )
            if (owned == 0) {
                // Si può rimediare subito, senza passare dal negozio.
                if (freeToday) {
                    Button(onClick = { vm.claimDaily(e) }, modifier = Modifier.fillMaxWidth()) { Text("Prendilo gratis (rullino di oggi)") }
                }
                OutlinedButton(
                    enabled = coins >= Shop.ROLL_PRICE,
                    onClick = { vm.buy(e) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Compra per ${Shop.ROLL_PRICE} gettoni (ne hai $coins)") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Pose", color = Palette.Muted, modifier = Modifier.padding(end = 4.dp))
                listOf(24, 36).forEach { n ->
                    FilterChip(selected = exposures == n, onClick = { exposures = n }, label = { Text("$n") })
                }
            }
            if (busyRoll) {
                Text("C'è già un rullino in macchina: finiscilo o sgancialo dal dorso.", color = Palette.Muted, fontSize = 13.sp)
            }
            Button(
                enabled = !busyRoll && owned > 0,
                onClick = { vm.loadRoll(e, exposures, onLoaded) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Carica il rullino in macchina") }
        }
    }
}

@Composable
private fun Store(vm: AppViewModel) {
    val state by vm.store.collectAsState()
    val installed by vm.emulsions.collectAsState()
    val downloading by vm.downloading.collectAsState()
    LaunchedEffect(Unit) { if (state !is StoreState.Ready) vm.refreshStore() }

    when (val s = state) {
        StoreState.NotConfigured -> Text(
            "Il negozio non è ancora collegato. Aggiungi supabase.url e supabase.key in local.properties e ricompila l'app.",
            color = Palette.Muted,
            modifier = Modifier.padding(20.dp),
        )
        StoreState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Apro il negozio…", color = Palette.Muted)
        }
        is StoreState.Error -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(s.message, color = Palette.Ink)
            OutlinedButton(onClick = { vm.refreshStore() }) { Text("Riprova") }
        }
        is StoreState.Ready -> {
            if (s.items.isEmpty()) {
                Text("Il negozio è vuoto per ora.", color = Palette.Muted, modifier = Modifier.padding(20.dp))
            }
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(s.items, key = { it.id }) { item ->
                    StoreRow(
                        item = item,
                        isInstalled = installed.any { it.id == item.id },
                        isDownloading = item.id in downloading,
                        onGet = { vm.download(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StoreRow(item: CatalogItem, isInstalled: Boolean, isDownloading: Boolean, onGet: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Plate).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)).background(Palette.PlateHi), contentAlignment = Alignment.Center) {
            if (item.boxUrl != null) {
                AsyncImage(model = item.boxUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
            } else {
                Text(item.iso.toString(), color = Palette.Safelight, fontFamily = Mono, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(item.name, color = Palette.Ink, fontWeight = FontWeight.SemiBold)
            Text(
                "ISO ${item.iso} · ${if (item.type == "bw") "BN" else "colore"}" + if (item.sizeBytes > 0) " · ${item.sizeBytes / 1024} KB" else "",
                color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp,
            )
            if (item.note.isNotBlank()) Text(item.note, color = Palette.Muted, fontSize = 12.sp, maxLines = 2)
        }
        when {
            isInstalled -> Text("Installata", color = Palette.Muted, fontSize = 12.sp)
            isDownloading -> Text("Scarico…", color = Palette.Muted, fontSize = 12.sp)
            item.priceCents > 0 -> Column(horizontalAlignment = Alignment.End) {
                Text(price(item.priceCents), color = Palette.Ink, fontFamily = Mono)
                Text("presto", color = Palette.Muted, fontSize = 11.sp)
            }
            else -> Button(onClick = onGet) { Text("Gratis") }
        }
    }
}

private fun price(cents: Int): String = NumberFormat.getCurrencyInstance(Locale.ITALY).format(cents / 100.0)
