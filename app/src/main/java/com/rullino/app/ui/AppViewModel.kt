package com.rullino.app.ui

import com.rullino.app.data.Role
import com.rullino.app.data.AccountException
import com.rullino.app.R
import androidx.annotation.StringRes
import android.content.res.Resources
import android.app.Application
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rullino.app.RullinoApp
import com.rullino.app.data.CatalogItem
import com.rullino.app.data.EmulsionEntity
import com.rullino.app.data.RollEntity
import com.rullino.app.data.RollState
import com.rullino.app.data.FrameEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import android.graphics.Bitmap
import com.rullino.app.data.ReleaseEntity
import com.rullino.app.release.ReleasePdf
import com.rullino.app.release.ReleaseTemplate
import kotlinx.coroutines.launch
import java.io.File

sealed interface StoreState {
    data object NotConfigured : StoreState
    data object Loading : StoreState
    data class Ready(val items: List<CatalogItem>) : StoreState
    data class Error(val message: String) : StoreState
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RullinoApp
    val repo = app.repo
    private val developer = app.developer
    val settings = app.settings
    val shop = app.shop
    val releaseTemplate = ReleaseTemplate(application)
    val account = app.account
    val accountBusy = MutableStateFlow(false)
    /** Email a cui è stato mandato il codice (null = si chiede l'email). */
    val pendingEmail = MutableStateFlow<String?>(null)

    val inventory: StateFlow<Map<String, Int>> = repo.inventory
        .map { list -> list.associate { it.emulsionId to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Cosa dice il negoziante in questo momento. */
    val shopkeeperLine = MutableStateFlow<String?>(null)

    val emulsions: StateFlow<List<EmulsionEntity>> =
        repo.emulsions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activeRoll: StateFlow<RollEntity?> =
        repo.activeRoll.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val rolls: StateFlow<List<RollEntity>> =
        repo.rolls.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val developProgress = developer.progress

    val message = MutableStateFlow<String?>(null)
    val store = MutableStateFlow<StoreState>(if (repo.catalog.isConfigured) StoreState.Loading else StoreState.NotConfigured)
    val downloading = MutableStateFlow<Set<String>>(emptySet())

    /** Richiesta di conferma di Android per cancellare stampe non create da questa installazione. */
    val galleryDeleteRequest = MutableStateFlow<IntentSender?>(null)

    fun frames(rollId: Long): Flow<List<FrameEntity>> = repo.frames(rollId)
    fun exposuresOnFrame(rollId: Long, frame: Int): Flow<Int> = repo.exposuresOnFrame(rollId, frame)

    fun say(text: String) { message.value = text }
    fun str(@StringRes id: Int, vararg args: Any): String = app.getString(id, *args)
    fun say(@StringRes id: Int, vararg args: Any) { message.value = str(id, *args) }

    fun loadRoll(e: EmulsionEntity, exposures: Int, onDone: () -> Unit) = viewModelScope.launch {
        runCatching { repo.loadRoll(e, exposures) }
            .onSuccess { onDone() }
            .onFailure { say(it.message ?: str(R.string.load_failed)) }
    }

    fun unloadEmptyRoll(r: RollEntity) = viewModelScope.launch { repo.unloadEmptyRoll(r) }

    fun wind(r: RollEntity) = viewModelScope.launch { repo.wind(r.id) }

    fun rawFileFor(r: RollEntity): File = repo.rawFileFor(r)

    /* ---- Sgancio del rullino ---- */

    fun shelve(r: RollEntity) = viewModelScope.launch { repo.shelve(r.id); say(R.string.shelved_msg) }

    /** Scarica il rullino dalla macchina così com'è: se è vergine torna in magazzino, altrimenti va in Rullini. */
    fun unload(r: RollEntity) = viewModelScope.launch {
        if (r.shotCount == 0) { repo.unloadEmptyRoll(r); say(R.string.unloaded_empty) }
        else { repo.shelve(r.id); say(R.string.shelved_msg) }
    }

    fun reload(r: RollEntity, onDone: () -> Unit) = viewModelScope.launch {
        runCatching { repo.reloadRoll(r.id) }.onSuccess { onDone() }.onFailure { say(it.message ?: str(R.string.reload_failed)) }
    }

    /** Un giro di manovella: torna indietro di una posa. */
    fun rewindFrame(r: RollEntity) = viewModelScope.launch { repo.rewind(r.id, 1) }

    /** Riavvolge e toglie il rullino: va portato al negozio per lo sviluppo. */
    fun rewindOut(r: RollEntity) = viewModelScope.launch {
        repo.rewindOut(r.id)
        say(R.string.rewound_out)
    }

    /* ---- Negozio ---- */

    /** Consegna in un colpo tutti i rullini scaricati (con almeno una posa). */
    /** Push/pull chiesto al negoziante per un rullino da sviluppare. */
    fun setPush(r: RollEntity, push: Int) = viewModelScope.launch {
        val fresh = repo.db.rolls().get(r.id) ?: return@launch
        if (fresh.state != RollState.FINISHED) return@launch
        repo.db.rolls().update(fresh.copy(push = push.coerceIn(-1, 2)))
        shopkeeperLine.value = when {
            push > 0 -> str(R.string.sk_push, push)
            push < 0 -> str(R.string.sk_pull)
            else -> str(R.string.sk_normal)
        }
    }

    fun deliverAll(rolls: List<RollEntity>) = app.appScope.launch {
        val list = rolls.filter { it.state == RollState.FINISHED && it.shotCount > 0 }
        if (list.isEmpty()) { shopkeeperLine.value = str(R.string.sk_nothing); return@launch }
        shopkeeperLine.value = if (list.size == 1) str(R.string.sk_look) else str(R.string.sk_many, list.size)
        var last = System.currentTimeMillis()
        for (r in list) {
            runCatching { developer.develop(r.id) }.onFailure { say(it.message ?: str(R.string.develop_failed)) }
            last = maxOf(last, repo.db.rolls().get(r.id)?.readyAt ?: last)
        }
        shopkeeperLine.value = if (settings.currentWaitMillis() == 0L) str(R.string.sk_done_now)
        else str(R.string.sk_bring_back, formatPickup(app.resources, last))
    }

    fun claimDaily(e: EmulsionEntity) = viewModelScope.launch {
        if (!shop.dailyAvailable()) { shopkeeperLine.value = str(R.string.sk_daily_taken); return@launch }
        repo.addToInventory(e.id, 1)
        shop.markDailyClaimed()
        shopkeeperLine.value = str(R.string.sk_daily_given, e.name)
    }

    fun buy(e: EmulsionEntity) = viewModelScope.launch {
        if (shop.spend(com.rullino.app.data.Shop.ROLL_PRICE)) {
            repo.addToInventory(e.id, 1)
            shopkeeperLine.value = str(R.string.sk_bought, e.name)
        } else shopkeeperLine.value = str(R.string.sk_no_coins)
    }

    /* ---- Liberatorie ---- */

    fun releases(rollId: Long) = repo.releases(rollId)

    fun saveRelease(roll: RollEntity, draft: ReleaseEntity, signature: Bitmap) = viewModelScope.launch {
        runCatching { repo.saveRelease(draft, signature) }
            .onSuccess { say(R.string.release_saved, draft.frame) }
            .onFailure { say(it.message ?: str(R.string.release_save_failed)) }
    }

    fun exportReleasePdf(roll: RollEntity, r: ReleaseEntity, onDone: (Uri) -> Unit) = viewModelScope.launch {
        runCatching { ReleasePdf.export(app, r, releaseTemplate.fill(r, roll.emulsionName)) }
            .onSuccess { say(R.string.pdf_saved); onDone(it) }
            .onFailure { say(it.message ?: str(R.string.pdf_failed)) }
    }

    fun deleteRelease(r: ReleaseEntity) = viewModelScope.launch { repo.deleteRelease(r); say(R.string.release_deleted) }

    fun deleteRoll(r: RollEntity, onDone: () -> Unit = {}) = viewModelScope.launch {
        runCatching { repo.deleteRoll(r.id) }
            .onSuccess { askGalleryConfirm(it); say(R.string.roll_deleted); onDone() }
            .onFailure { say(it.message ?: str(R.string.delete_failed)) }
    }

    /* ---- Tavolo luminoso ---- */

    fun print(r: RollEntity, frames: List<FrameEntity>) = viewModelScope.launch {
        var n = 0
        for (f in frames) {
            if (f.printUri != null) continue
            runCatching { repo.print(r, f) }.onSuccess { n++ }.onFailure { say(it.message ?: str(R.string.print_failed)) }
        }
        if (n > 0) say(if (n == 1) str(R.string.print_one) else str(R.string.print_many, n))
    }

    fun cutKeeping(r: RollEntity, keep: Set<Int>) = viewModelScope.launch {
        runCatching { repo.cutKeeping(r.id, keep) }
            .onSuccess { askGalleryConfirm(it); say(R.string.cut_done, keep.size) }
            .onFailure { say(it.message ?: str(R.string.cut_failed)) }
    }

    private fun askGalleryConfirm(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 30) {
            galleryDeleteRequest.value = MediaStore.createDeleteRequest(app.contentResolver, uris).intentSender
        } else {
            say(R.string.gallery_manual)
        }
    }

    fun recordShot(rollId: Long, file: File, shutter: Double? = null, meterEv: Double? = null) =
        app.appScope.launch { repo.recordShot(rollId, file, shutter, meterEv) }



    /* ---- Rullini condivisi ---- */

    /** Prepara il file .rullino e apre la scelta di Android per mandarlo (chat, email, Drive…). */
    /**
     * Prepara il file .rullino e apre la scelta di Android per mandarlo. Il codice registrato su Supabase
     * rende il file importabile una volta sola; intanto il rullino resta "in viaggio" qui.
     */
    fun shareRoll(r: RollEntity) = viewModelScope.launch {
        if (!account.signedIn.value) { say(R.string.share_sign_in); return@launch }
        say(R.string.share_preparing)
        runCatching {
            val from = account.profile.value?.let { it.displayName ?: it.username }
            val id = r.shareId ?: app.contests.createShare(r.emulsionId, r.shotCount)
            if (r.shareId == null) repo.db.rolls().update(r.copy(state = RollState.SHARED, shareId = id))
            com.rullino.app.data.RollShare.export(app, repo, r, from, id)
        }.onSuccess { file ->
            val uri = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".files", file)
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = com.rullino.app.data.RollShare.MIMETYPE
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_TEXT, str(R.string.share_text, r.emulsionName))
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            app.startActivity(
                android.content.Intent.createChooser(send, str(R.string.share_title)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { say(if (it is com.rullino.app.data.ContestException) str(R.string.share_failed) else it.message ?: str(R.string.share_failed)) }
    }

    /** Riprende un rullino "in viaggio": funziona solo se nessuno l'ha ancora importato. */
    fun takeBack(r: RollEntity) = viewModelScope.launch {
        val id = r.shareId ?: return@launch
        runCatching { app.contests.claimShare(id) }
            .onSuccess { res ->
                if (res == "ok") { repo.db.rolls().update(r.copy(state = RollState.SHELVED, shareId = null)); say(R.string.share_taken_back) }
                else { repo.deleteRoll(r.id); say(R.string.share_delivered, r.emulsionName) }
            }
            .onFailure { say(R.string.share_failed) }
    }

    /** I rullini in viaggio già importati da qualcuno spariscono da qui (sono passati di mano). */
    fun syncShared() = viewModelScope.launch {
        if (!account.signedIn.value) return@launch
        val shared = repo.db.rolls().allNow().filter { it.state == RollState.SHARED && it.shareId != null }
        if (shared.isEmpty()) return@launch
        val claims = runCatching { app.contests.shareClaims(shared.mapNotNull { it.shareId }) }.getOrNull() ?: return@launch
        val me = app.contests.me()
        for (r in shared) {
            val by = claims[r.shareId] ?: continue
            if (by == me) repo.db.rolls().update(r.copy(state = RollState.SHELVED, shareId = null))
            else { repo.deleteRoll(r.id); say(R.string.share_delivered, r.emulsionName) }
        }
    }

    /** È un rullino condiviso? Dal nome o, se manca, guardando dentro lo ZIP. */
    private suspend fun isRollFile(uri: Uri): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val name = runCatching {
            app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: ""
        if (name.endsWith(".rullino", ignoreCase = true)) return@withContext true
        if (name.endsWith(".emulsion", ignoreCase = true)) return@withContext false
        runCatching {
            app.contentResolver.openInputStream(uri)?.use { s ->
                java.util.zip.ZipInputStream(s).use { z ->
                    var found = false
                    while (true) { val e = z.nextEntry ?: break; if (e.name == "roll.json") { found = true; break } }
                    found
                }
            } ?: false
        }.getOrDefault(false)
    }

    /** Un file aperto da fuori: rullino condiviso (per tutti) o emulsione (solo modalità sviluppatore). */
    fun openFile(uri: Uri, onRoll: () -> Unit, onEmulsion: () -> Unit) = viewModelScope.launch {
        if (isRollFile(uri)) {
            runCatching {
                val input = app.contentResolver.openInputStream(uri) ?: error(str(R.string.open_file_failed))
                if (!account.signedIn.value) error(str(R.string.share_sign_in))
                input.use {
                    com.rullino.app.data.RollShare.import(
                        app, repo, it,
                        claim = { id -> app.contests.claimShare(id) },
                        catalogInstall = { id ->
                            runCatching {
                                val item = repo.catalog.list().first { c -> c.id == id }
                                val f = File(app.cacheDir, "download/${item.id}.emulsion")
                                repo.catalog.download(item.fileUrl, f)
                                f.inputStream().use { s -> repo.installer.install(s, "store", item.version) }
                                f.delete()
                                true
                            }.getOrDefault(false)
                        },
                    )
                }
            }.onSuccess { say(R.string.share_received, it.emulsionName, it.shotCount); onRoll() }
                .onFailure { say(it.message ?: str(R.string.share_invalid)) }
        } else if (settings.developerMode.value) {
            importFrom(uri); onEmulsion()
        } else say(R.string.import_needs_dev)
    }

    fun importFrom(uri: Uri, onDone: (EmulsionEntity) -> Unit = {}) = viewModelScope.launch {
        runCatching {
            val input = app.contentResolver.openInputStream(uri) ?: error(str(R.string.open_file_failed))
            input.use { repo.installer.install(it, "import") }
        }.onSuccess { say(R.string.emulsion_added, it.name); onDone(it) }
            .onFailure { say(it.message ?: str(R.string.invalid_file)) }
    }

    fun deleteEmulsion(e: EmulsionEntity) = viewModelScope.launch {
        runCatching { repo.deleteEmulsion(e) }
            .onSuccess { say(R.string.emulsion_deleted, e.name) }
            .onFailure { say(it.message ?: str(R.string.delete_failed)) }
    }

    fun refreshStore() {
        if (!repo.catalog.isConfigured) { store.value = StoreState.NotConfigured; return }
        store.value = StoreState.Loading
        viewModelScope.launch {
            store.value = runCatching { StoreState.Ready(repo.catalog.list()) }
                .getOrElse { StoreState.Error(str(R.string.store_unreachable)) }
        }
    }

    /* ---------------- Account ---------------- */

    private fun accountAction(block: suspend () -> Unit) {
        if (accountBusy.value) return
        accountBusy.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: AccountException) {
                say(
                    when (e.kind) {
                        AccountException.Kind.NETWORK -> R.string.acc_err_network
                        AccountException.Kind.SEND -> R.string.acc_err_send
                        AccountException.Kind.CODE -> R.string.acc_err_code
                        AccountException.Kind.USERNAME_TAKEN -> R.string.acc_username_taken
                        AccountException.Kind.USERNAME_INVALID -> R.string.acc_username_invalid
                        AccountException.Kind.OTHER -> R.string.acc_err_network
                    }
                )
            } finally {
                accountBusy.value = false
            }
        }
    }

    fun sendCode(email: String) = accountAction {
        account.sendCode(email)
        pendingEmail.value = email.trim()
    }

    fun verifyCode(code: String) = accountAction {
        val email = pendingEmail.value ?: return@accountAction
        account.verify(email, code)
        pendingEmail.value = null
        // Gli OP hanno sempre gli strumenti da sviluppatore.
        if (account.profile.value?.role == Role.OP) settings.setDeveloperMode(true)
    }

    fun cancelCode() { pendingEmail.value = null }

    fun saveProfile(username: String, displayName: String) = accountAction {
        account.updateProfile(username, displayName)
        say(R.string.acc_saved)
    }

    fun signOut() = accountAction { account.signOut(); say(R.string.acc_signed_out) }

    fun deleteAccount() = accountAction { account.deleteAccount(); say(R.string.acc_deleted) }

    fun download(item: CatalogItem) {
        if (item.id in downloading.value) return
        downloading.value = downloading.value + item.id
        app.appScope.launch {
            runCatching {
                val file = File(app.cacheDir, "download/${item.id}.emulsion")
                repo.catalog.download(item.fileUrl, file)
                file.inputStream().use { repo.installer.install(it, "store", item.version) }.also { file.delete() }
            }.onSuccess { say(R.string.downloaded, it.name) }
                .onFailure { say(str(R.string.download_failed)) }
            downloading.value = downloading.value - item.id
        }
    }
}

/** "1 giorno", "3 h 20 min", "12 min"… nella lingua del telefono. */
fun formatWait(res: Resources, ms: Long): String {
    val min = (ms + 59_999) / 60_000
    val h = min / 60
    val m = min % 60
    return when {
        h >= 24 && h % 24 == 0L && m == 0L -> if (h == 24L) res.getString(R.string.wait_day1) else res.getString(R.string.wait_days, h / 24)
        h > 0 && m > 0 -> res.getString(R.string.wait_hm, h, m)
        h > 0 -> if (h == 1L) res.getString(R.string.wait_h1) else res.getString(R.string.wait_h, h)
        else -> res.getString(R.string.wait_m, m)
    }
}

/** "oggi alle 18:30", "domani alle 9:05", "lunedì alle 10:00" nella lingua del telefono. */
fun formatPickup(res: Resources, at: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val t = java.time.Instant.ofEpochMilli(at).atZone(zone)
    val today = java.time.LocalDate.now(zone)
    val time = "%d:%02d".format(t.hour, t.minute)
    return when (t.toLocalDate()) {
        today -> res.getString(R.string.pickup_today, time)
        today.plusDays(1) -> res.getString(R.string.pickup_tomorrow, time)
        else -> res.getString(
            R.string.pickup_day,
            t.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, res.configuration.locales[0]),
            time,
        )
    }
}
