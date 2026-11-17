package com.rullino.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rullino.app.R
import com.rullino.app.RullinoApp
import com.rullino.app.data.Contest
import com.rullino.app.data.ContestEntry
import com.rullino.app.data.ContestException
import com.rullino.app.data.ContestScore
import com.rullino.app.data.FrameEntity
import com.rullino.app.data.RollEntity
import com.rullino.app.data.RollState
import com.rullino.app.release.ReleasePdf
import com.rullino.app.release.ReleaseTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Concorsi: elenco, rullino in regalo, invio della foto, giuria, bacheca e premi. */
class ContestsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as RullinoApp
    private val client = app.contests
    private val repo = app.repo
    private val prefs = app.getSharedPreferences("rullino-contests", android.content.Context.MODE_PRIVATE)

    val contests = MutableStateFlow<List<Contest>>(emptyList())
    /** La mia partecipazione per concorso. */
    val mine = MutableStateFlow<Map<String, ContestEntry>>(emptyMap())
    /** Codice del rullino regalato per concorso. */
    val gifts = MutableStateFlow<Map<String, String>>(emptyMap())
    val board = MutableStateFlow<List<Pair<Contest, ContestEntry>>>(emptyList())
    val loading = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)

    /* Giuria */
    val judging = MutableStateFlow<Contest?>(null)
    val judgingEntries = MutableStateFlow<List<ContestEntry>>(emptyList())
    val myVotes = MutableStateFlow<Map<String, ContestScore>>(emptyMap())

    /** I voti ricevuti dalla mia foto, a concorso chiuso. */
    val results = MutableStateFlow<Map<String, List<ContestScore>>>(emptyMap())

    private fun str(@StringRes id: Int, vararg args: Any) = app.getString(id, *args)

    fun refresh() {
        if (!app.account.signedIn.value) { contests.value = emptyList(); return }
        if (loading.value) return
        loading.value = true
        viewModelScope.launch {
            try {
                contests.value = client.list()
                mine.value = client.myEntries().associateBy { it.contestId }
                gifts.value = client.myGifts()
                board.value = client.board()
                error.value = null
                claimRewards()
            } catch (e: ContestException) {
                error.value = explain(e)
            } finally {
                loading.value = false
            }
        }
    }

    /* ---------------- Rullino in regalo ---------------- */

    /**
     * Ritira (sul server) e carica in macchina il rullino del concorso. Se il regalo è già stato
     * ritirato ma il rullino non c'è sul telefono, lo ricarica con lo stesso codice.
     */
    fun takeGiftRoll(c: Contest, onLoaded: () -> Unit) = action {
        val emulsionId = c.requiredEmulsion ?: throw IllegalStateException(str(R.string.ct_gift_no_emulsion))
        val emulsion = repo.emulsion(emulsionId) ?: downloadFromCatalog(emulsionId)
        val ref = gifts.value[c.id] ?: UUID.randomUUID().toString().also {
            client.claimGift(c.id, it)
            gifts.value = gifts.value + (c.id to it)
        }
        val local = repo.db.rolls().allNow().firstOrNull { it.contestRef == ref }
        when {
            local == null -> repo.loadGiftRoll(emulsion, c.giftExposures, c.id, ref)
            local.state == RollState.SHELVED -> repo.reloadRoll(local.id)
            else -> Unit
        }
        onLoaded()
    }

    private suspend fun downloadFromCatalog(id: String) = withContext(Dispatchers.IO) {
        val item = repo.catalog.list().firstOrNull { it.id == id } ?: throw IllegalStateException(str(R.string.ct_emulsion_missing))
        val file = File(app.cacheDir, "download/${item.id}.emulsion")
        repo.catalog.download(item.fileUrl, file)
        file.inputStream().use { repo.installer.install(it, "store", item.version) }.also { file.delete() }
    }

    /** Il rullino regalato è già sul telefono? */
    suspend fun hasGiftRoll(c: Contest): Boolean {
        val ref = gifts.value[c.id] ?: return false
        return repo.db.rolls().allNow().any { it.contestRef == ref }
    }

    /* ---------------- Partecipare ---------------- */

    /** Concorsi aperti a cui questo rullino può mandare una foto (e a cui non ho già partecipato). */
    fun eligible(roll: RollEntity): List<Contest> =
        contests.value.filter { it.phase() == Contest.Phase.OPEN && it.accepts(roll) && it.id !in mine.value }

    fun submit(c: Contest, roll: RollEntity, frame: FrameEntity, hasPeople: Boolean, onDone: () -> Unit) = action {
        val release = if (hasPeople) {
            val r = repo.db.releases().forRoll(roll.id).lastOrNull { it.frame == frame.frame }
                ?: throw IllegalStateException(str(R.string.ct_need_release))
            ReleasePdf.bytes(app, r, ReleaseTemplate(app).fill(r, roll.emulsionName))
        } else null
        val photo = withContext(Dispatchers.Default) { jpeg(File(frame.developedPath)) }
        client.submit(c, roll, frame.frame, photo, release)
        mine.value = mine.value + (c.id to client.myEntries().first { it.contestId == c.id })
        message.value = str(R.string.ct_sent, c.title)
        onDone()
    }

    fun withdraw(e: ContestEntry) = action {
        client.withdraw(e)
        mine.value = mine.value - e.contestId
        message.value = str(R.string.ct_withdrawn)
    }

    /** Il positivo ridotto a 2048 px sul lato lungo: basta per giudicare e per la bacheca. */
    private fun jpeg(file: File): ByteArray {
        val src = BitmapFactory.decodeFile(file.path) ?: throw IllegalStateException(str(R.string.ct_photo_missing))
        val scale = 2048f / maxOf(src.width, src.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
        if (bmp !== src) bmp.recycle()
        src.recycle()
        return out.toByteArray()
    }

    /* ---------------- Giuria ---------------- */

    fun openJudging(c: Contest) = action {
        judging.value = c
        judgingEntries.value = client.entriesForJudging(c.id)
        myVotes.value = client.myScores(c.id)
    }

    fun closeJudging() { judging.value = null; judgingEntries.value = emptyList(); myVotes.value = emptyMap() }

    fun vote(e: ContestEntry, score: Int, note: String) = action {
        client.vote(e.id, score, note)
        myVotes.value = myVotes.value + (e.id to ContestScore(e.id, score, note.ifBlank { null }))
    }

    /** Solo OP. */
    fun close(c: Contest) = action {
        client.close(c.id)
        message.value = str(R.string.ct_closed_msg, c.title)
        closeJudging()
        contests.value = client.list()
        board.value = client.board()
    }

    fun loadResults(e: ContestEntry) = action {
        results.value = results.value + (e.id to client.scoresOf(e.id))
    }

    /* ---------------- Gestione concorsi (OP) ---------------- */

    /** Emulsioni del catalogo, per scegliere quella imposta. */
    val catalog = MutableStateFlow<List<com.rullino.app.data.CatalogItem>>(emptyList())

    fun loadCatalog() = viewModelScope.launch {
        if (catalog.value.isEmpty()) catalog.value = runCatching { repo.catalog.list() }.getOrDefault(emptyList())
    }

    /** Crea o aggiorna. [status]: "draft", "open", oppure null per lasciarlo com'è. */
    fun save(existing: Contest?, d: com.rullino.app.data.ContestDraft, status: String?, onDone: () -> Unit) = action {
        when {
            d.title.isBlank() -> throw IllegalStateException(str(R.string.cm_err_title))
            d.endsAt <= d.startsAt -> throw IllegalStateException(str(R.string.cm_err_dates))
            d.giftRoll && d.requiredEmulsion == null -> throw IllegalStateException(str(R.string.cm_err_gift))
        }
        if (existing == null) client.create(d, publish = status == "open") else client.update(existing.id, d, status)
        message.value = str(if (status == "open") R.string.cm_published else R.string.cm_saved)
        contests.value = client.list()
        onDone()
    }

    fun delete(c: Contest, onDone: () -> Unit) = action {
        client.delete(c.id)
        message.value = str(R.string.cm_deleted)
        contests.value = client.list()
        onDone()
    }

    /** Carica il file .emulsion scelto come premio esclusivo; [onUrl] riceve nome e indirizzo. */
    fun uploadPrize(uri: android.net.Uri, onUrl: (String, String) -> Unit) = action {
        val bytes = withContext(Dispatchers.IO) {
            app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException(str(R.string.open_file_failed))
        }
        val name = app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "premio.emulsion"
        val url = client.uploadExclusive(bytes, name)
        onUrl(name.substringBeforeLast('.'), url)
    }

    /* ---------------- Premi ---------------- */

    private suspend fun claimRewards() {
        val rewards = runCatching { client.claimRewards() }.getOrDefault(emptyList())
        var coins = 0
        for (r in rewards) {
            coins += r.coins
            r.emulsionUrl?.let { addPendingPrize(it) }
            message.value = str(R.string.ct_prize_place, r.place)
        }
        if (coins > 0) {
            app.shop.earn(coins)
            message.value = str(R.string.ct_prize_coins, coins)
        }
        installPendingPrizes()
    }

    // Le emulsioni vinte restano in coda finché non si installano: il premio non si perde se cade la rete.
    private fun addPendingPrize(url: String) {
        prefs.edit().putStringSet("prizes", (prefs.getStringSet("prizes", emptySet()).orEmpty() + url)).apply()
    }

    private suspend fun installPendingPrizes() {
        for (url in prefs.getStringSet("prizes", emptySet()).orEmpty()) {
            val ok = runCatching {
                val file = File(app.cacheDir, "download/prize-${url.hashCode()}.emulsion")
                repo.catalog.download(url, file)
                file.inputStream().use { repo.installer.install(it, "prize") }.also { file.delete() }
            }
            ok.onSuccess { e ->
                prefs.edit().putStringSet("prizes", prefs.getStringSet("prizes", emptySet()).orEmpty() - url).apply()
                message.value = str(R.string.ct_prize_emulsion, e.name)
            }
        }
    }

    /* ---------------- Foto dal bucket privato ---------------- */

    suspend fun image(path: String): File? = runCatching {
        client.image(path, File(app.cacheDir, "contests/" + path.replace('/', '_')))
    }.getOrNull()

    /* ---------------- dettagli ---------------- */

    private fun action(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: ContestException) {
                message.value = explain(e)
            } catch (e: Exception) {
                message.value = e.message ?: str(R.string.ct_err_other)
            } finally {
                busy.value = false
            }
        }
    }

    private fun explain(e: ContestException) = str(
        when (e.kind) {
            ContestException.Kind.NETWORK -> R.string.acc_err_network
            ContestException.Kind.SIGNED_OUT -> R.string.ct_sign_in
            ContestException.Kind.CLOSED -> R.string.ct_err_closed
            ContestException.Kind.NOT_ALLOWED -> R.string.ct_err_rules
            ContestException.Kind.ALREADY -> R.string.ct_err_already
            ContestException.Kind.OTHER -> R.string.ct_err_other
        }
    )
}
