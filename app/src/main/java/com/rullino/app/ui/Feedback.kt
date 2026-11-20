package com.rullino.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rullino.app.R

/**
 * "Segnala un problema": cosa è successo, un contatto se vuoi una risposta,
 * e uno screenshot preso dalla galleria. Arriva su Supabase insieme a modello e versione.
 */
@Composable
fun FeedbackDialog(vm: AppViewModel, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var image by remember { mutableStateOf<Uri?>(null) }
    var sending by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { image = it }

    Dialog(onDismissRequest = { if (!sending) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(560.dp).background(Palette.Plate, RoundedCornerShape(6.dp)).verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.fb_title).uppercase(), color = Palette.Ink, fontFamily = Barlow, fontSize = 22.sp, letterSpacing = 1.5.sp)
            Text(stringResource(R.string.fb_intro), color = Palette.Muted, fontSize = 13.sp)
            OutlinedTextField(
                text, { text = it.take(4000) }, Modifier.fillMaxWidth().height(140.dp),
                placeholder = { Text(stringResource(R.string.fb_placeholder), color = Palette.Faint) },
                textStyle = TextStyle(color = Palette.Ink, fontSize = 14.sp),
            )
            OutlinedTextField(
                contact, { contact = it.take(200) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.fb_contact)) },
                textStyle = TextStyle(color = Palette.Ink, fontSize = 14.sp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(stringResource(if (image == null) R.string.fb_attach else R.string.fb_attach_change))
                }
                if (image != null) {
                    Text(stringResource(R.string.fb_attached), color = Palette.Safelight, fontFamily = Mono, fontSize = 12.sp)
                    TextButton(onClick = { image = null }) { Text(stringResource(R.string.fb_remove), color = Palette.Muted) }
                }
            }
            Text(stringResource(R.string.fb_privacy), color = Palette.Faint, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                TextButton(enabled = !sending, onClick = onClose) { Text(stringResource(R.string.cancel)) }
                PrimaryButton(enabled = !sending && text.trim().length >= 3, onClick = {
                    sending = true
                    vm.sendFeedback(text, contact, image) { err ->
                        sending = false
                        when (err) {
                            null -> { vm.say(R.string.fb_sent); onClose() }
                            "too_many" -> vm.say(R.string.fb_too_many)
                            else -> vm.say(R.string.fb_failed)
                        }
                    }
                }) { Text(stringResource(if (sending) R.string.fb_sending else R.string.fb_send)) }
            }
        }
    }
}
