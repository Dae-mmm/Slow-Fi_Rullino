package com.rullino.app.ui

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
import com.rullino.app.data.FrameEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
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

    fun loadRoll(e: EmulsionEntity, exposures: Int, onDone: () -> Unit) = viewModelScope.launch {
        runCatching { repo.loadRoll(e, exposures) }
            .onSuccess { onDone() }
            .onFailure { say(it.message ?: "Non riesco a caricare il rullino") }
    }

    fun unloadEmptyRoll(r: RollEntity) = viewModelScope.launch { repo.unloadEmptyRoll(r) }

    fun wind(r: RollEntity) = viewModelScope.launch { repo.wind(r.id) }

    fun rawFileFor(r: RollEntity): File = repo.rawFileFor(r)

    /* ---- Sgancio del rullino ---- */

    fun shelve(r: RollEntity) = viewModelScope.launch { repo.shelve(r.id); say("Rullino messo da parte: lo trovi in Rullini") }

    fun reload(r: RollEntity, onDone: () -> Unit) = viewModelScope.launch {
        runCatching { repo.reloadRoll(r.id) }.onSuccess { onDone() }.onFailure { say(it.message ?: "Non riesco a ricaricarlo") }
    }

    fun rewind(r: RollEntity, frames: Int?) = viewModelScope.launch {
        repo.rewind(r.id, frames)
        say(if (frames == null) "Riavvolto fino all'inizio: i prossimi scatti si sovrappongono" else "Riavvolto di $frames: i prossimi scatti si sovrappongono")
    }

    /** Sviluppa subito con le pose fatte (anche da un rullino messo da parte). */
    fun developNow(r: RollEntity) = app.appScope.launch {
        runCatching {
            repo.finishNow(r.id)
            developer.develop(r.id)
        }.onSuccess { sayLab() }.onFailure { say(it.message ?: "Sviluppo non riuscito") }
    }

    fun deleteRoll(r: RollEntity, onDone: () -> Unit = {}) = viewModelScope.launch {
        runCatching { repo.deleteRoll(r.id) }
            .onSuccess { askGalleryConfirm(it); say("Rullino eliminato"); onDone() }
            .onFailure { say(it.message ?: "Non riesco a eliminarlo") }
    }

    /* ---- Tavolo luminoso ---- */

    fun print(r: RollEntity, frames: List<FrameEntity>) = viewModelScope.launch {
        var n = 0
        for (f in frames) {
            if (f.printUri != null) continue
            runCatching { repo.print(r, f) }.onSuccess { n++ }.onFailure { say(it.message ?: "Stampa non riuscita") }
        }
        if (n > 0) say(if (n == 1) "Stampa pronta in galleria" else "$n stampe pronte in galleria")
    }

    fun cutKeeping(r: RollEntity, keep: Set<Int>) = viewModelScope.launch {
        runCatching { repo.cutKeeping(r.id, keep) }
            .onSuccess { askGalleryConfirm(it); say("Negativi tagliati: tenuti ${keep.size}") }
            .onFailure { say(it.message ?: "Taglio non riuscito") }
    }

    private fun sayLab() {
        val wait = settings.currentWaitMillis()
        say(if (wait == 0L) "Negativi pronti: li trovi in Rullini" else "Rullino al laboratorio: pronto tra ${formatWait(wait)}")
    }

    private fun askGalleryConfirm(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 30) {
            galleryDeleteRequest.value = MediaStore.createDeleteRequest(app.contentResolver, uris).intentSender
        } else {
            say("Alcune stampe vanno cancellate a mano dalla galleria")
        }
    }

    fun recordShot(rollId: Long, file: File) = app.appScope.launch { repo.recordShot(rollId, file) }

    /** Lo sviluppo continua anche se si cambia schermata. */
    fun develop(r: RollEntity) = app.appScope.launch {
        runCatching { developer.develop(r.id) }.onSuccess { sayLab() }.onFailure { say(it.message ?: "Sviluppo non riuscito") }
    }

    fun importFrom(uri: Uri, onDone: (EmulsionEntity) -> Unit = {}) = viewModelScope.launch {
        runCatching {
            val input = app.contentResolver.openInputStream(uri) ?: error("Non riesco ad aprire il file")
            input.use { repo.installer.install(it, "import") }
        }.onSuccess { say("«${it.name}» aggiunta alle tue emulsioni"); onDone(it) }
            .onFailure { say(it.message ?: "File non valido") }
    }

    fun deleteEmulsion(e: EmulsionEntity) = viewModelScope.launch {
        runCatching { repo.deleteEmulsion(e) }
            .onSuccess { say("«${e.name}» eliminata") }
            .onFailure { say(it.message ?: "Non riesco a eliminarla") }
    }

    fun refreshStore() {
        if (!repo.catalog.isConfigured) { store.value = StoreState.NotConfigured; return }
        store.value = StoreState.Loading
        viewModelScope.launch {
            store.value = runCatching { StoreState.Ready(repo.catalog.list()) }
                .getOrElse { StoreState.Error(it.message ?: "Il negozio non risponde") }
        }
    }

    fun download(item: CatalogItem) {
        if (item.id in downloading.value) return
        downloading.value = downloading.value + item.id
        app.appScope.launch {
            runCatching {
                val file = File(app.cacheDir, "download/${item.id}.emulsion")
                repo.catalog.download(item.fileUrl, file)
                file.inputStream().use { repo.installer.install(it, "store") }.also { file.delete() }
            }.onSuccess { say("«${it.name}» scaricata") }
                .onFailure { say(it.message ?: "Download non riuscito") }
            downloading.value = downloading.value - item.id
        }
    }
}

/** "1 giorno", "3 h 20 min", "12 min"… */
fun formatWait(ms: Long): String {
    val min = (ms + 59_999) / 60_000
    val h = min / 60
    val m = min % 60
    return when {
        h >= 24 && h % 24 == 0L && m == 0L -> if (h == 24L) "1 giorno" else "${h / 24} giorni"
        h > 0 && m > 0 -> "$h h $m min"
        h > 0 -> if (h == 1L) "1 ora" else "$h ore"
        else -> "$m min"
    }
}
