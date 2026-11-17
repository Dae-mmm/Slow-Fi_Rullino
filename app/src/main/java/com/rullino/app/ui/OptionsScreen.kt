package com.rullino.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.rullino.app.data.Role
import com.rullino.app.data.Features
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
    val profile by vm.account.profile.collectAsState()
    // La modalità sviluppatore la vedono solo gli account OP.
    val isOp = profile?.role == Role.OP

    Column(
        Modifier.fillMaxSize().dottedPage().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageHeading(stringResource(R.string.kick_system), stringResource(R.string.tab_options)) {
            Text(stringResource(R.string.version, BuildConfig.VERSION_NAME).uppercase(), color = Palette.Muted, fontFamily = Mono, fontSize = 10.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AccountSection(vm)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        Section(stringResource(R.string.lab_title), "02") {
            Text(stringResource(R.string.opt_lab_text), color = Palette.Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabTime.entries.forEach { t ->
                    FilterChip(selected = labTime == t, onClick = { settings.setLabTime(t) }, label = { Text(stringResource(t.label)) })
                }
            }
            Text(stringResource(R.string.opt_lab_note), color = Palette.Muted, fontSize = 12.sp)
        }

        Section(stringResource(R.string.release), "03") {
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
                    PrimaryButton(onClick = { vm.releaseTemplate.save(text); editing = false; vm.say(R.string.release_saved_text) }) { Text(stringResource(R.string.save)) }
                    TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(onClick = { text = vm.releaseTemplate.text(); editing = true }) { Text(stringResource(R.string.edit_text)) }
                    if (vm.releaseTemplate.isCustom) TextButton(onClick = { vm.releaseTemplate.reset(); vm.say(R.string.placeholder_restored) }) { Text(stringResource(R.string.restore_placeholder)) }
                }
            }
        }

        if (isOp) {
            Section(stringResource(R.string.dev_mode), "04") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.skip_lab), color = Palette.Ink)
                        Text(stringResource(R.string.skip_lab_note), color = Palette.Muted, fontSize = 12.sp)
                    }
                    Switch(
                        enabled = devMode,
                        checked = skipWait,
                        onCheckedChange = { settings.setSkipLabWait(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Palette.Safelight),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(onClick = { vm.shop.earn(100); vm.say(R.string.plus100) }) { Text(stringResource(R.string.plus100)) }
                    SecondaryButton(onClick = { vm.shop.resetDaily(); vm.say(R.string.daily_again_msg) }) { Text(stringResource(R.string.daily_again)) }
                }
            }
        }
        }
        }

    }
}

@Composable
private fun AccountSection(vm: AppViewModel) {
    val signedIn by vm.account.signedIn.collectAsState()
    val profile by vm.account.profile.collectAsState()
    val entitlements by vm.account.entitlements.collectAsState()
    val busy by vm.accountBusy.collectAsState()
    val pending by vm.pendingEmail.collectAsState()
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    Section(stringResource(R.string.account), "01") {
        if (!signedIn) {
            Text(stringResource(R.string.acc_intro), color = Palette.Muted, fontSize = 13.sp)
            if (pending == null) {
                OutlinedTextField(
                    value = email, onValueChange = { email = it }, singleLine = true,
                    label = { Text(stringResource(R.string.acc_email)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                )
                PrimaryButton(onClick = { vm.sendCode(email) }, enabled = !busy && "@" in email) {
                    Text(stringResource(R.string.acc_send_code))
                }
            } else {
                Text(stringResource(R.string.acc_code_sent, pending!!), color = Palette.Ink, fontSize = 13.sp)
                OutlinedTextField(
                    value = code, onValueChange = { code = it.filter(Char::isDigit).take(8) }, singleLine = true,
                    label = { Text(stringResource(R.string.acc_code)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.widthIn(max = 240.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(onClick = { vm.verifyCode(code) }, enabled = !busy && code.length >= 6) { Text(stringResource(R.string.acc_sign_in)) }
                    TextButton(onClick = { code = ""; vm.cancelCode() }) { Text(stringResource(R.string.acc_change_email)) }
                }
            }
        } else {
            val p = profile
            Text(stringResource(R.string.acc_signed_in_as, vm.account.email ?: ""), color = Palette.Ink)
            if (p != null) {
                val roleName = stringResource(
                    when (p.role) { Role.OP -> R.string.role_op; Role.JUDGE -> R.string.role_judge; Role.USER -> R.string.role_user }
                )
                Text(
                    stringResource(R.string.acc_role, roleName) + if (Features.PRO_VISIBLE && entitlements.pro) " · PRO" else "",
                    color = if (p.role == Role.USER) Palette.Muted else Palette.Safelight, fontSize = 13.sp,
                )
                var username by remember(p.username) { mutableStateOf(p.username ?: "") }
                var shown by remember(p.displayName) { mutableStateOf(p.displayName ?: "") }
                OutlinedTextField(
                    value = username, onValueChange = { username = it.take(24) }, singleLine = true,
                    label = { Text(stringResource(R.string.acc_username)) },
                    supportingText = { Text(stringResource(R.string.acc_username_invalid), fontSize = 11.sp) },
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                )
                OutlinedTextField(
                    value = shown, onValueChange = { shown = it.take(60) }, singleLine = true,
                    label = { Text(stringResource(R.string.acc_display_name)) },
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                )
                PrimaryButton(onClick = { vm.saveProfile(username, shown) }, enabled = !busy) { Text(stringResource(R.string.save)) }
                if (p.role != Role.USER) Text(stringResource(R.string.acc_judge_area), color = Palette.Muted, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(onClick = { vm.signOut() }, enabled = !busy) { Text(stringResource(R.string.acc_sign_out)) }
                TextButton(onClick = { confirmDelete = true }, enabled = !busy) {
                    Text(stringResource(R.string.acc_delete), color = Color(0xFFFF8A80))
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.acc_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteAccount() }) {
                    Text(stringResource(R.string.acc_delete), color = Color(0xFFFF8A80))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Section(title: String, number: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().card().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(number, color = Color(0xFF5A544F), fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 22.sp)
            Text(title, color = Palette.Ink, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
        content()
    }
}
