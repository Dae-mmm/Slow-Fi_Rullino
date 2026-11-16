package com.rullino.app.ui

import com.rullino.app.R
import androidx.compose.ui.res.stringResource
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
        Section(stringResource(R.string.lab_title)) {
            Text(stringResource(R.string.opt_lab_text), color = Palette.Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabTime.entries.forEach { t ->
                    FilterChip(selected = labTime == t, onClick = { settings.setLabTime(t) }, label = { Text(stringResource(t.label)) })
                }
            }
            Text(stringResource(R.string.opt_lab_note), color = Palette.Muted, fontSize = 12.sp)
        }

        Section(stringResource(R.string.release)) {
            var editing by remember { mutableStateOf(false) }
            var text by remember { mutableStateOf("") }
            Text(
                if (vm.releaseTemplate.isCustom) stringResource(R.string.release_custom) else stringResource(R.string.release_placeholder),
                color = Palette.Muted, fontSize = 13.sp,
            )
            if (editing) {
                Text(stringResource(R.string.placeholders_list, "{{NOME}} {{COGNOME}} {{CONTATTO}} {{FOTOGRAFO}} {{DATA}} {{LUOGO}} {{RULLINO}} {{POSA}} {{USI_ESTERNI}} {{TUTORE}}"), color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp)
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().height(260.dp), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 12.sp, color = Palette.Ink))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.releaseTemplate.save(text); editing = false; vm.say(R.string.release_saved_text) }) { Text(stringResource(R.string.save)) }
                    TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { text = vm.releaseTemplate.text(); editing = true }) { Text(stringResource(R.string.edit_text)) }
                    if (vm.releaseTemplate.isCustom) TextButton(onClick = { vm.releaseTemplate.reset(); vm.say(R.string.placeholder_restored) }) { Text(stringResource(R.string.restore_placeholder)) }
                }
            }
        }

        if (devMode) {
            Section(stringResource(R.string.dev_mode)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.skip_lab), color = Palette.Ink)
                        Text(stringResource(R.string.skip_lab_note), color = Palette.Muted, fontSize = 12.sp)
                    }
                    Switch(
                        checked = skipWait,
                        onCheckedChange = { settings.setSkipLabWait(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Palette.Safelight),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.shop.earn(100); vm.say(R.string.plus100) }) { Text(stringResource(R.string.plus100)) }
                    OutlinedButton(onClick = { vm.shop.resetDaily(); vm.say(R.string.daily_again_msg) }) { Text(stringResource(R.string.daily_again)) }
                }
                TextButton(onClick = { settings.setDeveloperMode(false); taps = 0; vm.say(R.string.dev_off_msg) }) {
                    Text(stringResource(R.string.dev_off), color = Palette.Muted)
                }
            }
        }

        // Come su Android: 7 tocchi sulla versione attivano la modalità sviluppatore.
        // Il conto alla rovescia sta accanto alla scritta, così niente la copre mentre tocchi.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.version, BuildConfig.VERSION_NAME),
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
                devMode -> stringResource(R.string.you_are_dev)
                taps >= 3 -> stringResource(R.string.taps_left, 7 - taps)
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
