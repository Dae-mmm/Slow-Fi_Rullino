package com.rullino.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rullino.app.RullinoApp
import com.rullino.app.data.CatalogItem
import com.rullino.app.data.EmulsionEntity
import com.rullino.app.data.RollEntity
import com.rullino.app.data.ShotEntity
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

    fun shots(rollId: Long): Flow<List<ShotEntity>> = repo.shots(rollId)

    fun say(text: String) { message.value = text }

    fun loadRoll(e: EmulsionEntity, exposures: Int, onDone: () -> Unit) = viewModelScope.launch {
        runCatching { repo.loadRoll(e, exposures) }
            .onSuccess { onDone() }
            .onFailure { say(it.message ?: "Non riesco a caricare il rullino") }
    }

    fun unloadEmptyRoll(r: RollEntity) = viewModelScope.launch { repo.unloadEmptyRoll(r) }

    fun wind(r: RollEntity) = viewModelScope.launch { repo.wind(r.id) }

    fun rawFileFor(r: RollEntity): File = repo.rawFileFor(r.id, r.shotCount + 1)

    fun recordShot(rollId: Long, file: File) = app.appScope.launch { repo.recordShot(rollId, file) }

    /** Lo sviluppo continua anche se si cambia schermata. */
    fun develop(r: RollEntity) = app.appScope.launch {
        runCatching { developer.develop(r.id) }.onFailure { say(it.message ?: "Sviluppo non riuscito") }
    }

    fun importFrom(uri: Uri, onDone: (EmulsionEntity) -> Unit = {}) = viewModelScope.launch {
        runCatching {
            val input = app.contentResolver.openInputStream(uri) ?: error("Non riesco ad aprire il file")
            input.use { repo.installer.install(it, "import") }
        }.onSuccess { say("«${it.name}» aggiunta alle tue emulsioni"); onDone(it) }
            .onFailure { say(it.message ?: "File non valido") }
    }

    fun deleteEmulsion(e: EmulsionEntity) = viewModelScope.launch {
        if (activeRoll.value?.emulsionId == e.id) {
            say("Questa emulsione è nel rullino in macchina")
            return@launch
        }
        repo.deleteEmulsion(e)
        say("«${e.name}» eliminata")
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
