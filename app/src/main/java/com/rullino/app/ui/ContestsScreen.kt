package com.rullino.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.rullino.app.R
import com.rullino.app.data.Contest
import com.rullino.app.data.ContestEntry
import com.rullino.app.data.FrameEntity
import com.rullino.app.data.Role
import com.rullino.app.data.RollEntity
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun ContestsScreen(vm: AppViewModel, cvm: ContestsViewModel, onOpenCamera: () -> Unit, onSignIn: () -> Unit) {
    val signedIn by vm.account.signedIn.collectAsState()
    val profile by vm.account.profile.collectAsState()
    val contests by cvm.contests.collectAsState()
    val mine by cvm.mine.collectAsState()
    val board by cvm.board.collectAsState()
    val loading by cvm.loading.collectAsState()
    val error by cvm.error.collectAsState()
    val judging by cvm.judging.collectAsState()
    val role = profile?.role ?: Role.USER
    val isJudge = role == Role.JUDGE || role == Role.OP

    LaunchedEffect(signedIn) { cvm.refresh() }

    Column(Modifier.fillMaxSize().dottedPage()) {
        PageHeading(stringResource(R.string.ct_kicker), stringResource(R.string.tab_contests), Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp)) {
            if (signedIn) {
                if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = Palette.Safelight, strokeWidth = 2.dp)
                else SecondaryButton(onClick = { cvm.refresh() }) { Text(stringResource(R.string.ct_refresh)) }
            }
        }
        if (!signedIn) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.ct_sign_in), color = Palette.Muted)
                PrimaryButton(onClick = onSignIn) { Text(stringResource(R.string.ct_go_account)) }
            }
            return@Column
        }
        error?.let { Text(it, color = Palette.Safelight, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) }

        val visible = contests.filter { it.phase() != Contest.Phase.DRAFT || role == Role.OP }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (board.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.ct_board)) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(board, key = { it.second.id }) { (c, e) -> BoardCard(cvm, c, e) }
                    }
                }
            }
            if (visible.isEmpty() && !loading) item { Text(stringResource(R.string.ct_none), color = Palette.Muted, modifier = Modifier.padding(8.dp)) }
            val order = listOf(Contest.Phase.OPEN, Contest.Phase.UPCOMING, Contest.Phase.JUDGING, Contest.Phase.CLOSED, Contest.Phase.DRAFT)
            for (ph in order) {
                val group = visible.filter { it.phase() == ph }
                if (group.isEmpty()) continue
                item(key = "h-$ph") { SectionLabel(phaseLabel(ph)) }
                items(group, key = { it.id }) { c ->
                    ContestCard(vm, cvm, c, mine[c.id], isJudge, onOpenCamera)
                }
            }
        }
    }

    judging?.let { c -> JudgingDialog(cvm, c, isOp = role == Role.OP) }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = Palette.Safelight, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.4.sp, modifier = Modifier.padding(start = 8.dp, top = 6.dp))
}

@Composable
private fun phaseLabel(p: Contest.Phase) = stringResource(
    when (p) {
        Contest.Phase.DRAFT -> R.string.ct_phase_draft
        Contest.Phase.UPCOMING -> R.string.ct_phase_upcoming
        Contest.Phase.OPEN -> R.string.ct_phase_open
        Contest.Phase.JUDGING -> R.string.ct_phase_judging
        Contest.Phase.CLOSED -> R.string.ct_phase_closed
    }
)

private fun day(ms: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
private fun dayTime(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContestCard(vm: AppViewModel, cvm: ContestsViewModel, c: Contest, entry: ContestEntry?, isJudge: Boolean, onOpenCamera: () -> Unit) {
    val emulsions by vm.emulsions.collectAsState()
    val gifts by cvm.gifts.collectAsState()
    val busy by cvm.busy.collectAsState()
    val results by cvm.results.collectAsState()
    val phase = c.phase()
    val emulsionName = c.requiredEmulsion?.let { id -> emulsions.firstOrNull { it.id == id }?.name ?: id }
    val hasGift by produceState(false, c.id, gifts) { value = cvm.hasGiftRoll(c) }

    Row(Modifier.fillMaxWidth().card().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(c.title.uppercase(), color = Palette.Ink, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (c.theme.isNotBlank()) Text(stringResource(R.string.ct_theme, c.theme), color = Palette.Ink, fontSize = 15.sp)
            if (c.description.isNotBlank()) Text(c.description, color = Palette.Muted, fontSize = 13.sp)
            Text(stringResource(R.string.ct_dates, dayTime(c.startsAt), dayTime(c.endsAt)), color = Palette.Faint, fontFamily = Mono, fontSize = 11.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(phaseLabel(phase), active = phase == Contest.Phase.OPEN)
                if (c.giftRoll) Chip(stringResource(R.string.ct_chip_gift, c.giftExposures), active = true)
                if (emulsionName != null) Chip(emulsionName)
            }
            // Le regole del concorso, sempre in vista.
            Text(stringResource(R.string.ct_rules_title).uppercase(), color = Palette.Faint, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 4.dp))
            Text("· " + stringResource(if (c.giftRoll) R.string.ct_rule_gift else R.string.ct_rule_during), color = Palette.Muted, fontSize = 13.sp)
            if (emulsionName != null && !c.giftRoll) Text("· " + stringResource(R.string.ct_rule_emulsion, emulsionName), color = Palette.Muted, fontSize = 13.sp)
            Text("· " + stringResource(R.string.ct_rule_one), color = Palette.Muted, fontSize = 13.sp)
            Text("· " + stringResource(R.string.ct_rule_release), color = Palette.Muted, fontSize = 13.sp)
            val coins = c.prizeCoins.filter { it > 0 }
            if (coins.isNotEmpty() || c.prizeEmulsion != null) {
                Text(stringResource(R.string.ct_prizes_title).uppercase(), color = Palette.Faint, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 4.dp))
                Text(
                    buildList {
                        coins.forEachIndexed { i, n -> add(stringResource(R.string.ct_prize_line, i + 1, n)) }
                        if (c.prizeEmulsion != null) add(stringResource(R.string.ct_prize_exclusive))
                        add(stringResource(R.string.ct_prize_board))
                    }.joinToString("\n"),
                    color = Palette.Muted, fontSize = 13.sp,
                )
            }
        }
        Column(Modifier.width(230.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
            when {
                entry != null -> {
                    Text(stringResource(R.string.ct_in_competition, entry.frame), color = Palette.Safelight, fontFamily = Mono, fontSize = 12.sp)
                    ContestPhoto(cvm, entry.photoPath, Modifier.width(200.dp).aspectRatio(1.5f))
                    if (phase == Contest.Phase.OPEN) SecondaryButton(enabled = !busy, onClick = { cvm.withdraw(entry) }) { Text(stringResource(R.string.ct_withdraw)) }
                    if (phase == Contest.Phase.CLOSED) {
                        val r = results[entry.id]
                        if (r == null) SecondaryButton(enabled = !busy, onClick = { cvm.loadResults(entry) }) { Text(stringResource(R.string.ct_see_votes)) }
                        else {
                            entry.place?.let { Text(stringResource(R.string.ct_your_place, it), color = Palette.Safelight, fontWeight = FontWeight.SemiBold) }
                            entry.avgScore?.let { Text(stringResource(R.string.ct_avg, "%.1f".format(it)), color = Palette.Ink, fontFamily = Mono) }
                            if (r.isEmpty()) Text(stringResource(R.string.ct_no_votes), color = Palette.Muted, fontSize = 12.sp)
                            r.forEach { s ->
                                Text("${s.score}/10" + (s.note?.let { " — $it" } ?: ""), color = Palette.Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
                phase == Contest.Phase.OPEN && c.giftRoll && !hasGift -> {
                    PrimaryButton(enabled = !busy, onClick = { cvm.takeGiftRoll(c, onOpenCamera) }) {
                        Text(stringResource(if (gifts[c.id] == null) R.string.ct_take_gift else R.string.ct_reload_gift))
                    }
                    EngravedNote(stringResource(R.string.ct_gift_note))
                }
                phase == Contest.Phase.OPEN -> EngravedNote(stringResource(R.string.ct_how_to_send))
                phase == Contest.Phase.UPCOMING -> EngravedNote(stringResource(R.string.ct_starts, day(c.startsAt)))
            }
            if (isJudge && (phase == Contest.Phase.JUDGING)) {
                PrimaryButton(enabled = !busy, onClick = { cvm.openJudging(c) }) { Text(stringResource(R.string.ct_judge)) }
            } else if (phase == Contest.Phase.JUDGING && entry == null) {
                EngravedNote(stringResource(R.string.ct_jury_working))
            }
        }
    }
}

/** Una foto in gara: si scarica dal bucket privato e resta in cache. */
@Composable
private fun ContestPhoto(cvm: ContestsViewModel, path: String, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val file by produceState<File?>(null, path) { value = cvm.image(path) }
    Box(modifier.clip(RoundedCornerShape(3.dp)).background(Color(0xFF0D0C0C)), contentAlignment = Alignment.Center) {
        val f = file
        if (f != null) AsyncImage(model = f, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        else CircularProgressIndicator(Modifier.size(18.dp), color = Palette.Faint, strokeWidth = 2.dp)
    }
}

@Composable
private fun BoardCard(cvm: ContestsViewModel, c: Contest, e: ContestEntry) {
    Column(Modifier.width(240.dp).card().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ContestPhoto(cvm, e.photoPath, Modifier.fillMaxWidth().aspectRatio(1.5f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${e.place ?: ""}°", color = Palette.Safelight, fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 24.sp)
            Column(Modifier.weight(1f)) {
                Text(e.author ?: stringResource(R.string.ct_anonymous), color = Palette.Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(c.title, color = Palette.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            e.avgScore?.let { Text("%.1f".format(it), color = Palette.Muted, fontFamily = Mono, fontSize = 12.sp) }
        }
    }
}

/* ---------------- Giuria ---------------- */

@Composable
private fun JudgingDialog(cvm: ContestsViewModel, c: Contest, isOp: Boolean) {
    val entries by cvm.judgingEntries.collectAsState()
    val votes by cvm.myVotes.collectAsState()
    val busy by cvm.busy.collectAsState()
    var open by remember { mutableStateOf<ContestEntry?>(null) }
    var confirmClose by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = { cvm.closeJudging() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Palette.Body).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ct_jury).uppercase(), color = Palette.Safelight, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.4.sp)
                    Text(c.title, color = Palette.Ink, fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 26.sp)
                    Text(stringResource(R.string.ct_jury_progress, votes.size, entries.size), color = Palette.Muted, fontFamily = Mono, fontSize = 11.sp)
                }
                if (isOp) PrimaryButton(enabled = !busy && entries.isNotEmpty(), onClick = { confirmClose = true }) { Text(stringResource(R.string.ct_close_award)) }
                SecondaryButton(onClick = { cvm.closeJudging() }) { Text(stringResource(R.string.close)) }
            }
            Text(stringResource(R.string.ct_jury_blind), color = Palette.Faint, fontSize = 12.sp)
            if (entries.isEmpty()) Text(stringResource(if (busy) R.string.ct_loading else R.string.ct_no_entries), color = Palette.Muted)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(200.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(entries, key = { it.id }) { e ->
                    Box(Modifier.clickable { open = e }) {
                        ContestPhoto(cvm, e.photoPath, Modifier.fillMaxWidth().aspectRatio(1.5f))
                        val v = votes[e.id]
                        Text(
                            v?.let { "${it.score}/10" } ?: "—",
                            color = if (v != null) Palette.Safelight else Palette.Muted, fontFamily = Mono, fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                        if (e.hasPeople) Text(
                            stringResource(R.string.release_badge), color = Color.White, fontFamily = Mono, fontSize = 9.sp,
                            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color(0x99000000)).padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }

    open?.let { e -> VoteDialog(cvm, e, votes[e.id]?.score, votes[e.id]?.note, onClose = { open = null }) }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            containerColor = Palette.Plate,
            title = { Text(stringResource(R.string.ct_close_award)) },
            text = { Text(stringResource(R.string.ct_close_text)) },
            confirmButton = { PrimaryButton(onClick = { confirmClose = false; cvm.close(c) }) { Text(stringResource(R.string.ct_close_award)) } },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun VoteDialog(cvm: ContestsViewModel, e: ContestEntry, score: Int?, note: String?, onClose: () -> Unit) {
    var s by remember(e.id) { mutableIntStateOf(score ?: 0) }
    var n by remember(e.id) { mutableStateOf(note ?: "") }
    val busy by cvm.busy.collectAsState()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(Modifier.fillMaxSize().background(Color(0xF20C0B0B)).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ContestPhoto(cvm, e.photoPath, Modifier.weight(1f).fillMaxHeight(), ContentScale.Fit)
            Column(Modifier.width(280.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.ct_your_vote).uppercase(), color = Palette.Safelight, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.4.sp)
                // Dieci tasti come i numeri di una ghiera.
                for (row in listOf(1..5, 6..10)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (v in row) {
                            val on = s == v
                            Text(
                                "$v", color = if (on) Color(0xFF1B0E09) else Palette.Ink, fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                                modifier = Modifier.size(46.dp, 40.dp).clip(RoundedCornerShape(3.dp))
                                    .background(if (on) Palette.Safelight else Palette.Plate)
                                    .border(1.dp, if (on) Color(0xFFFF8A66) else Palette.Line, RoundedCornerShape(3.dp))
                                    .clickable { s = v }
                                    .padding(top = 9.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = n, onValueChange = { n = it.take(500) },
                    label = { Text(stringResource(R.string.ct_note_optional)) },
                    minLines = 3, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(enabled = s in 1..10 && !busy, onClick = { cvm.vote(e, s, n); onClose() }) { Text(stringResource(R.string.ct_vote)) }
                    SecondaryButton(onClick = onClose) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

/* ---------------- Invio dal tavolo luminoso ---------------- */

/** Scelta del concorso e conferma: una sola foto per concorso, con la liberatoria se ci sono persone. */
@Composable
fun SendToContestDialog(cvm: ContestsViewModel, roll: RollEntity, frame: FrameEntity, contests: List<Contest>, hasRelease: Boolean, onClose: () -> Unit) {
    var chosen by remember { mutableStateOf(contests.firstOrNull()) }
    var people by remember { mutableStateOf(hasRelease) }
    val busy by cvm.busy.collectAsState()
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Palette.Plate,
        title = { Text(stringResource(R.string.ct_send_title, frame.frame)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                contests.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().card(selected = chosen?.id == c.id).clickable { chosen = c }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title, color = Palette.Ink, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.ct_until, dayTime(c.endsAt)), color = Palette.Muted, fontSize = 12.sp)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ct_has_people), color = Palette.Ink, modifier = Modifier.weight(1f))
                    Switch(
                        checked = people, onCheckedChange = { people = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Palette.Safelight),
                    )
                }
                if (people && !hasRelease) Text(stringResource(R.string.ct_need_release), color = Palette.Safelight, fontSize = 13.sp)
                Text(stringResource(R.string.ct_send_note), color = Palette.Muted, fontSize = 12.sp)
            }
        },
        confirmButton = {
            PrimaryButton(
                enabled = chosen != null && !busy && (!people || hasRelease),
                onClick = { chosen?.let { cvm.submit(it, roll, frame, people, onClose) } },
            ) { Text(stringResource(if (busy) R.string.ct_sending else R.string.ct_send)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}
