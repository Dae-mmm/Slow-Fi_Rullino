package com.rullino.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rullino.app.BuildConfig
import com.rullino.app.data.LabTime

@Composable
fun OptionsScreen(vm: AppViewModel) {
    val settings = vm.settings
    val labTime by settings.labTime.collectAsState()
    val devMode by settings.developerMode.collectAsState()
    val skipWait by settings.skipLabWait.collectAsState()
    val view = LocalView.current
    var taps by remember { mutableIntStateOf(0) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section("Laboratorio") {
            Text("Quanto aspetti i negativi dopo aver consegnato un rullino.", color = Palette.Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabTime.entries.forEach { t ->
                    FilterChip(selected = labTime == t, onClick = { settings.setLabTime(t) }, label = { Text(t.label) })
                }
            }
            Text("Vale per i prossimi rullini: quelli già al laboratorio mantengono il loro tempo.", color = Palette.Muted, fontSize = 12.sp)
        }

        Section("Liberatoria") {
            var editing by remember { mutableStateOf(false) }
            var text by remember { mutableStateOf("") }
            Text(
                if (vm.releaseTemplate.isCustom) "Stai usando un testo tuo." else "Stai usando il testo segnaposto: sostituiscilo con quello rivisto da un legale.",
                color = Palette.Muted, fontSize = 13.sp,
            )
            if (editing) {
                Text("Segnaposto: {{NOME}} {{COGNOME}} {{CONTATTO}} {{FOTOGRAFO}} {{DATA}} {{LUOGO}} {{RULLINO}} {{POSA}} {{USI_ESTERNI}} {{TUTORE}}", color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp)
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().height(260.dp), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 12.sp, color = Palette.Ink))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.releaseTemplate.save(text); editing = false; vm.say("Testo della liberatoria salvato") }) { Text("Salva") }
                    TextButton(onClick = { editing = false }) { Text("Annulla") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { text = vm.releaseTemplate.text(); editing = true }) { Text("Modifica il testo") }
                    if (vm.releaseTemplate.isCustom) TextButton(onClick = { vm.releaseTemplate.reset(); vm.say("Ripristinato il testo segnaposto") }) { Text("Ripristina segnaposto") }
                }
            }
        }

        if (devMode) {
            Section("Modalità sviluppatore") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Salta i tempi del laboratorio", color = Palette.Ink)
                        Text("I negativi sono pronti subito, anche quelli già consegnati.", color = Palette.Muted, fontSize = 12.sp)
                    }
                    Switch(
                        checked = skipWait,
                        onCheckedChange = { settings.setSkipLabWait(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Palette.Safelight),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.shop.earn(100); vm.say("+100 gettoni") }) { Text("+100 gettoni") }
                    OutlinedButton(onClick = { vm.shop.resetDaily(); vm.say("Rullino del giorno di nuovo disponibile") }) { Text("Rullino del giorno di nuovo") }
                }
                TextButton(onClick = { settings.setDeveloperMode(false); taps = 0; vm.say("Modalità sviluppatore disattivata") }) {
                    Text("Disattiva la modalità sviluppatore", color = Palette.Muted)
                }
            }
        }

        // Come su Android: 7 tocchi sulla versione attivano la modalità sviluppatore.
        // Il conto alla rovescia sta accanto alla scritta, così niente la copre mentre tocchi.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Rullino · versione ${BuildConfig.VERSION_NAME}",
                color = Palette.Muted,
                fontFamily = Mono,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        if (devMode) return@clickable
                        taps++
                        view.tick()
                        if (taps >= 7) { settings.setDeveloperMode(true); view.confirm() }
                    }
                    .padding(horizontal = 6.dp, vertical = 10.dp),
            )
            val hint = when {
                devMode -> "Sei uno sviluppatore"
                taps >= 3 -> "Ancora ${7 - taps}"
                else -> null
            }
            if (hint != null) Text(hint, color = Palette.Safelight, fontFamily = Mono, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Plate)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title.uppercase(), color = Palette.Ink, fontWeight = FontWeight.Bold, fontFamily = Mono, fontSize = 12.sp, letterSpacing = 2.sp)
        content()
    }
}
