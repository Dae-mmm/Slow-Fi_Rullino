package com.rullino.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.rullino.app.data.RollEntity
import com.rullino.app.data.RollState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RollsScreen(vm: AppViewModel) {
    val rolls by vm.rolls.collectAsState()
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    val open = rolls.firstOrNull { it.id == openId }

    if (open != null) {
        BackHandler { openId = null }
        ContactSheet(vm, open, onBack = { openId = null })
        return
    }

    if (rolls.isEmpty()) {
        Text("Ancora nessun rullino. Caricane uno dalla fotocamera.", color = Palette.Muted, modifier = Modifier.padding(20.dp))
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(rolls, key = { it.id }) { r ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.Plate)
                    .clickable(enabled = r.state == RollState.DEVELOPED) { openId = r.id }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(r.emulsionName, color = Palette.Ink, fontWeight = FontWeight.SemiBold)
                    Text(
                        "ISO ${r.iso} · ${r.shotCount}/${r.exposures} · ${date(r.developedAt ?: r.finishedAt ?: r.loadedAt)}",
                        color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp,
                    )
                }
                Text(stateLabel(r), color = if (r.state == RollState.DEVELOPED) Palette.Ink else Palette.Safelight, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ContactSheet(vm: AppViewModel, roll: RollEntity, onBack: () -> Unit) {
    val context = LocalContext.current
    val shots by remember(roll.id) { vm.shots(roll.id) }.collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Rullini") }
            Column(Modifier.weight(1f)) {
                Text(roll.emulsionName, color = Palette.Ink, fontWeight = FontWeight.SemiBold)
                Text("Provino · ${shots.size} pose", color = Palette.Muted, fontSize = 11.sp, fontFamily = Mono)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.background(Palette.Body),
        ) {
            items(shots, key = { it.id }) { s ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AsyncImage(
                        model = s.developedUri?.let(Uri::parse),
                        contentDescription = "Posa ${s.frame}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Palette.PlateHi)
                            .clickable(enabled = s.developedUri != null) {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(Uri.parse(s.developedUri), "image/jpeg")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching { context.startActivity(intent) }
                            },
                    )
                    Text("${s.frame}", color = Palette.Safelight, fontFamily = Mono, fontSize = 10.sp)
                }
            }
        }
    }
}

private fun stateLabel(r: RollEntity) = when (r.state) {
    RollState.LOADED -> "In macchina"
    RollState.FINISHED -> "Da sviluppare"
    RollState.DEVELOPING -> "In sviluppo"
    else -> "Sviluppato ›"
}

private fun date(ms: Long): String = SimpleDateFormat("d MMM yyyy", Locale.ITALIAN).format(Date(ms))
