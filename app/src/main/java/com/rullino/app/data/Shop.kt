package com.rullino.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Il negozio: gettoni e magazzino stanno sul server, che decide prezzi e saldo.
 *
 * Sul telefono ci sono:
 * - la "tasca": fino a 3 rullini già tolti dal magazzino, caricabili anche offline;
 * - una coda di operazioni da mandare al server quando torna la rete (rullino usato, rullino restituito);
 * - il rullino di prova per chi non ha l'account;
 * - una copia del magazzino (tabella locale inventory) solo per mostrarlo: magazzino del server + tasca.
 */
class Shop(context: Context, private val db: AppDb, private val client: ContestsClient) {
    private val prefs = context.getSharedPreferences("rullino-shop", Context.MODE_PRIVATE)
    private val lock = Mutex()

    companion object {
        /** Prezzo mostrato quando il server non ne dà uno (le emulsioni del catalogo hanno il loro). */
        const val ROLL_PRICE = 10
        /** Emulsione del rullino di prova per chi non ha l'account. */
        const val TRIAL_EMULSION = "bundled-neutra-100"
        const val TRIAL = "trial"
    }

    private data class Pocket(val id: String, val emulsionId: String)

    private val _coins = MutableStateFlow(prefs.getInt("coins", 0))
    val coins: StateFlow<Int> = _coins

    private val _dailyAvailable = MutableStateFlow(false)
    val dailyAvailable: StateFlow<Boolean> = _dailyAvailable

    private var serverInventory: Map<String, Int> = readMap("inventory")

    /** L'emulsione del giorno: ruota ogni giorno tra quelle installate. */
    fun dailyEmulsion(installed: List<EmulsionEntity>): EmulsionEntity? {
        if (installed.isEmpty()) return null
        val sorted = installed.sortedBy { it.id }
        val day = LocalDate.now().toEpochDay()
        return sorted[(day % sorted.size).toInt()]
    }

    /* ---------------- con il server ---------------- */

    /** Manda le operazioni in coda, riempie la tasca e aggiorna saldo e magazzino. Senza rete non fa nulla. */
    suspend fun refresh() = lock.withLock {
        if (!client.signedIn()) { mirror(); return@withLock }
        runCatching {
            flush()
            apply(JSONObject(client.rpc("pocket_fill")))
        }
        mirror()
    }

    suspend fun buy(emulsionId: String) = lock.withLock {
        apply(JSONObject(client.rpc("buy_roll", JSONObject().put("e", emulsionId))))
        runCatching { apply(JSONObject(client.rpc("pocket_fill"))) }
        mirror()
    }

    suspend fun claimDaily(emulsionId: String) = lock.withLock {
        apply(JSONObject(client.rpc("claim_daily", JSONObject().put("e", emulsionId))))
        runCatching { apply(JSONObject(client.rpc("pocket_fill"))) }
        mirror()
    }

    suspend fun opGrant(n: Int) = lock.withLock { apply(JSONObject(client.rpc("op_grant_coins", JSONObject().put("n", n)))); mirror() }
    suspend fun opResetDaily() = lock.withLock { apply(JSONObject(client.rpc("op_reset_daily"))); mirror() }

    /**
     * Prende un rullino per caricarlo in macchina e ne restituisce il codice.
     * Prima dalla tasca (va anche offline), poi dal magazzino sul server; senza account, il rullino di prova.
     */
    suspend fun take(emulsionId: String): String = lock.withLock {
        val pocket = readPocket()
        pocket.firstOrNull { it.emulsionId == emulsionId }?.let { p ->
            writePocket(pocket - p)
            queue("use", p.id)
            runCatching { flush() }
            mirror()
            return@withLock p.id
        }
        if (!client.signedIn()) {
            if (emulsionId == TRIAL_EMULSION && prefs.getBoolean("trial", true)) {
                prefs.edit().putBoolean("trial", false).apply()
                mirror()
                return@withLock TRIAL
            }
            throw EconomyException("not_signed_in")
        }
        val id = client.rpc("pocket_take", JSONObject().put("e", emulsionId)).trim().trim('"')
        client.rpc("pocket_use", JSONObject().put("p", id))
        serverInventory = serverInventory + (emulsionId to ((serverInventory[emulsionId] ?: 1) - 1).coerceAtLeast(0))
        saveMap("inventory", serverInventory)
        mirror()
        id
    }

    /** Un rullino mai scattato torna in magazzino (il codice va restituito al server). */
    suspend fun giveBack(pocketId: String?, emulsionId: String) = lock.withLock {
        when (pocketId) {
            null -> return@withLock          // rullino di prima del magazzino sul server: non torna in magazzino
            TRIAL -> prefs.edit().putBoolean("trial", true).apply()
            else -> {
                queue("return", pocketId)
                serverInventory = serverInventory + (emulsionId to (serverInventory[emulsionId] ?: 0) + 1)
                saveMap("inventory", serverInventory)
                runCatching { flush() }
            }
        }
        mirror()
    }

    /** Uscendo dall'account: saldo, magazzino e tasca restano sul server, qui si azzerano. */
    suspend fun forget() = lock.withLock {
        _coins.value = 0
        _dailyAvailable.value = false
        serverInventory = emptyMap()
        prefs.edit().remove("coins").remove("inventory").remove("pocket").remove("queue").apply()
        mirror()
    }

    /* ---------------- dettagli ---------------- */

    private fun apply(state: JSONObject) {
        _coins.value = state.optInt("coins")
        prefs.edit().putInt("coins", _coins.value).apply()
        _dailyAvailable.value = state.optBoolean("daily_available")
        val inv = state.optJSONObject("inventory") ?: JSONObject()
        serverInventory = inv.keys().asSequence().associateWith { inv.optInt(it) }
        saveMap("inventory", serverInventory)
        val p = state.optJSONArray("pocket") ?: JSONArray()
        writePocket((0 until p.length()).mapNotNull { p.optJSONObject(it) }.map { Pocket(it.getString("id"), it.getString("emulsion_id")) })
    }

    /** Il magazzino che si vede nell'app: quello del server più la tasca (o il rullino di prova). */
    private suspend fun mirror() {
        val counts = HashMap<String, Int>()
        if (client.signedIn()) {
            serverInventory.forEach { (k, v) -> counts[k] = (counts[k] ?: 0) + v }
            readPocket().forEach { counts[it.emulsionId] = (counts[it.emulsionId] ?: 0) + 1 }
        } else {
            readPocket().forEach { counts[it.emulsionId] = (counts[it.emulsionId] ?: 0) + 1 }
            if (prefs.getBoolean("trial", true)) counts[TRIAL_EMULSION] = (counts[TRIAL_EMULSION] ?: 0) + 1
        }
        db.inventory().clear()
        counts.forEach { (k, v) -> if (v > 0) db.inventory().upsert(InventoryEntity(k, v)) }
    }

    private suspend fun flush() {
        val q = readQueue()
        val left = q.toMutableList()
        for (op in q) {
            client.rpc(if (op.first == "use") "pocket_use" else "pocket_return", JSONObject().put("p", op.second))
            left.remove(op)
            writeQueue(left)
        }
    }

    private fun queue(kind: String, id: String) = writeQueue(readQueue() + (kind to id))

    private fun readQueue(): List<Pair<String, String>> = runCatching {
        val a = JSONArray(prefs.getString("queue", "[]"))
        (0 until a.length()).map { a.getJSONArray(it).let { x -> x.getString(0) to x.getString(1) } }
    }.getOrDefault(emptyList())

    private fun writeQueue(q: List<Pair<String, String>>) =
        prefs.edit().putString("queue", JSONArray(q.map { JSONArray(listOf(it.first, it.second)) }).toString()).apply()

    private fun readPocket(): List<Pocket> = runCatching {
        val a = JSONArray(prefs.getString("pocket", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Pocket(o.getString("id"), o.getString("e")) } }
    }.getOrDefault(emptyList())

    private fun writePocket(p: List<Pocket>) =
        prefs.edit().putString("pocket", JSONArray(p.map { JSONObject().put("id", it.id).put("e", it.emulsionId) }).toString()).apply()

    private fun readMap(key: String): Map<String, Int> = runCatching {
        val o = JSONObject(prefs.getString(key, "{}")!!)
        o.keys().asSequence().associateWith { o.optInt(it) }
    }.getOrDefault(emptyMap())

    private fun saveMap(key: String, m: Map<String, Int>) =
        prefs.edit().putString(key, JSONObject(m as Map<*, *>).toString()).apply()
}
