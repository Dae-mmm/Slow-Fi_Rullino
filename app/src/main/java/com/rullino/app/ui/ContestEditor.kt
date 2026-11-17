package com.rullino.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rullino.app.R
import com.rullino.app.data.Contest
import com.rullino.app.data.ContestDraft
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** Modulo per creare o modificare un concorso (solo OP). [existing] null = concorso nuovo. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContestEditor(cvm: ContestsViewModel, existing: Contest?, onClose: () -> Unit) {
    var d by remember(existing?.id) { mutableStateOf(existing?.let { ContestDraft.from(it) } ?: ContestDraft()) }
    val catalog by cvm.catalog.collectAsState()
    val busy by cvm.busy.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(Unit) { cvm.loadCatalog() }

    val prizePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) cvm.uploadPrize(uri) { name, url -> d = d.copy(prizeEmulsion = d.prizeEmulsion?.ifBlank { null } ?: name, prizeEmulsionUrl = url) }
    }

    fun pick(ms: Long, onPicked: (Long) -> Unit) {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        DatePickerDialog(context, { _, y, m, day ->
            TimePickerDialog(context, { _, h, min ->
                cal.set(y, m, day, h, min, 0)
                onPicked(cal.timeInMillis)
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }
    val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Palette.Body).padding(16.dp)) {
            // Testata con le azioni sempre in vista.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.cm_kicker).uppercase(), color = Palette.Safelight, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.4.sp)
                    Text(
                        stringResource(if (existing == null) R.string.cm_new else R.string.cm_edit),
                        color = Palette.Ink, fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 26.sp,
                    )
                }
                if (existing != null) TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete), color = Color(0xFFFF8A80)) }
                SecondaryButton(onClick = onClose) { Text(stringResource(R.string.cancel)) }
                if (existing == null || existing.status == "draft") {
                    SecondaryButton(enabled = !busy, onClick = { cvm.save(existing, d, "draft", onClose) }) { Text(stringResource(R.string.cm_save_draft)) }
                    PrimaryButton(enabled = !busy, onClick = { cvm.save(existing, d, "open", onClose) }) { Text(stringResource(R.string.cm_publish)) }
                } else {
                    PrimaryButton(enabled = !busy, onClick = { cvm.save(existing, d, null, onClose) }) { Text(stringResource(R.string.cm_save)) }
                }
            }
            Row(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Colonna sinistra: cosa e quando.
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Section(stringResource(R.string.cm_sec_what))
                    OutlinedTextField(d.title, { d = d.copy(title = it.take(80)) }, label = { Text(stringResource(R.string.cm_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(d.theme, { d = d.copy(theme = it.take(80)) }, label = { Text(stringResource(R.string.cm_theme)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(d.description, { d = d.copy(description = it.take(600)) }, label = { Text(stringResource(R.string.cm_description)) }, minLines = 3, modifier = Modifier.fillMaxWidth())

                    Section(stringResource(R.string.cm_sec_when))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.cm_starts), color = Palette.Muted, modifier = Modifier.width(56.dp))
                        SecondaryButton(onClick = { pick(d.startsAt) { s -> d = d.copy(startsAt = s, endsAt = s + (d.endsAt - d.startsAt)) } }) { Text(fmt.format(Date(d.startsAt))) }
                        TextButton(onClick = { val now = System.currentTimeMillis(); d = d.copy(startsAt = now, endsAt = now + (d.endsAt - d.startsAt)) }) { Text(stringResource(R.string.cm_now), color = Palette.Safelight) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.cm_ends), color = Palette.Muted, modifier = Modifier.width(56.dp))
                        SecondaryButton(onClick = { pick(d.endsAt) { e -> d = d.copy(endsAt = e) } }) { Text(fmt.format(Date(d.endsAt))) }
                    }
                    // Durate pronte: si parte dall'inizio scelto.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(3 to R.string.cm_3d, 7 to R.string.cm_1w, 14 to R.string.cm_2w, 30 to R.string.cm_1m).forEach { (days, label) ->
                            val on = d.endsAt - d.startsAt == days * 86_400_000L
                            Pill(stringResource(label), on) { d = d.copy(endsAt = d.startsAt + days * 86_400_000L) }
                        }
                    }
                    Text(stringResource(R.string.cm_when_note), color = Palette.Faint, fontSize = 12.sp)
                }
                // Colonna destra: pellicola e premi.
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Section(stringResource(R.string.cm_sec_film))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(stringResource(R.string.cm_any_film), d.requiredEmulsion == null) { d = d.copy(requiredEmulsion = null, giftRoll = false) }
                        catalog.forEach { item -> Pill("${item.name} · ${item.iso}", d.requiredEmulsion == item.id) { d = d.copy(requiredEmulsion = item.id) } }
                    }
                    if (catalog.isEmpty()) Text(stringResource(R.string.ct_loading), color = Palette.Faint, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.cm_gift), color = Palette.Ink)
                            Text(stringResource(R.string.cm_gift_note), color = Palette.Faint, fontSize = 12.sp)
                        }
                        Switch(
                            checked = d.giftRoll, enabled = d.requiredEmulsion != null,
                            onCheckedChange = { d = d.copy(giftRoll = it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Safelight),
                        )
                    }
                    if (d.giftRoll) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.cm_exposures), color = Palette.Muted)
                        listOf(12, 24, 36).forEach { n -> Pill("$n", d.giftExposures == n) { d = d.copy(giftExposures = n) } }
                    }

                    Section(stringResource(R.string.ct_prizes_title))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (0..2).forEach { i ->
                            OutlinedTextField(
                                value = d.prizeCoins.getOrElse(i) { 0 }.toString(),
                                onValueChange = { v ->
                                    val n = v.filter(Char::isDigit).take(5).toIntOrNull() ?: 0
                                    d = d.copy(prizeCoins = List(3) { k -> if (k == i) n else d.prizeCoins.getOrElse(k) { 0 } })
                                },
                                label = { Text(stringResource(R.string.cm_place_coins, i + 1)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.ct_prize_exclusive), color = Palette.Ink)
                            Text(
                                if (d.prizeEmulsionUrl != null) stringResource(R.string.cm_prize_loaded, d.prizeEmulsion ?: "") else stringResource(R.string.cm_prize_none),
                                color = if (d.prizeEmulsionUrl != null) Palette.Safelight else Palette.Faint, fontSize = 12.sp,
                            )
                        }
                        if (d.prizeEmulsionUrl != null) TextButton(onClick = { d = d.copy(prizeEmulsion = null, prizeEmulsionUrl = null) }) { Text(stringResource(R.string.cm_remove), color = Palette.Muted) }
                        SecondaryButton(enabled = !busy, onClick = { prizePicker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.cm_choose_file)) }
                    }
                    if (d.prizeEmulsionUrl != null) {
                        OutlinedTextField(d.prizeEmulsion ?: "", { d = d.copy(prizeEmulsion = it.take(60)) }, label = { Text(stringResource(R.string.cm_prize_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.padding(4.dp))
                    Text(stringResource(R.string.cm_rules_note), color = Palette.Faint, fontSize = 12.sp)
                }
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Palette.Plate,
            title = { Text(stringResource(R.string.cm_delete_title, existing.title)) },
            text = { Text(stringResource(R.string.cm_delete_text)) },
            confirmButton = { PrimaryButton(onClick = { confirmDelete = false; cvm.delete(existing, onClose) }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Section(text: String) {
    Text(text.uppercase(), color = Palette.Safelight, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.4.sp, modifier = Modifier.padding(top = 4.dp))
}

/** Scelta a pillola, accesa quando è selezionata. */
@Composable
private fun Pill(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (on) Palette.Safelight else Palette.Ink, fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (on) Palette.SafelightDark else Color.Transparent)
            .border(1.dp, if (on) Color(0xFFA6472C) else Palette.Line, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
