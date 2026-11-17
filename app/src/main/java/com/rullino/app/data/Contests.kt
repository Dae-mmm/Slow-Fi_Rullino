package com.rullino.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime

/** Un concorso. Le date arrivano dal server in ISO 8601 e qui diventano millisecondi. */
data class Contest(
    val id: String,
    val title: String,
    val theme: String,
    val description: String,
    val startsAt: Long,
    val endsAt: Long,
    val requiredEmulsion: String?,
    val giftRoll: Boolean,
    val giftExposures: Int,
    val prizeCoins: List<Int>,
    val prizeEmulsion: String?,
    val status: String,
    val boardUntil: Long?,
) {
    enum class Phase { DRAFT, UPCOMING, OPEN, JUDGING, CLOSED }

    fun phase(now: Long = System.currentTimeMillis()): Phase = when {
        status == "draft" -> Phase.DRAFT
        status == "closed" -> Phase.CLOSED
        now < startsAt -> Phase.UPCOMING
        now <= endsAt -> Phase.OPEN
        else -> Phase.JUDGING
    }

    /** Il rullino può partecipare? Le stesse regole che il server ricontrolla all'invio. */
    fun accepts(roll: RollEntity): Boolean {
        if (giftRoll) return roll.contestId == id && roll.contestRef != null
        if (requiredEmulsion != null && roll.emulsionId != requiredEmulsion) return false
        return roll.loadedAt >= startsAt
    }
}

data class ContestEntry(
    val id: String,
    val contestId: String,
    val userId: String,
    val frame: Int,
    val rollRef: String,
    val hasPeople: Boolean,
    val photoPath: String,
    val releasePath: String?,
    val place: Int?,
    val avgScore: Double?,
    val author: String?,
)

data class ContestScore(val entryId: String, val score: Int, val note: String?)

data class ContestReward(val contestId: String, val place: Int, val coins: Int, val emulsionId: String?, val emulsionUrl: String?)

class ContestException(val kind: Kind) : Exception(kind.name) {
    enum class Kind { NETWORK, SIGNED_OUT, CLOSED, NOT_ALLOWED, ALREADY, OTHER }
}

/** Concorsi su Supabase: tabelle protette da RLS e foto nel bucket privato contest-entries. */
class ContestsClient(private val baseUrl: String, private val key: String, private val account: Account) {

    private suspend fun token(): String = account.accessToken() ?: throw ContestException(ContestException.Kind.SIGNED_OUT)
    private fun uid(): String = account.userId ?: throw ContestException(ContestException.Kind.SIGNED_OUT)

    suspend fun list(): List<Contest> {
        val (c, t) = call("GET", "/rest/v1/contests?select=*&order=ends_at.desc", token = token())
        ok(c, t)
        return JSONArray(t).objects().map(::contest)
    }

    suspend fun myEntries(): List<ContestEntry> {
        val (c, t) = call("GET", "/rest/v1/contest_entries?user_id=eq.${uid()}&select=*", token = token())
        ok(c, t)
        return JSONArray(t).objects().map(::entry)
    }

    /** Codici dei rullini regalati che ho già ritirato, per concorso. */
    suspend fun myGifts(): Map<String, String> {
        val (c, t) = call("GET", "/rest/v1/contest_gifts?user_id=eq.${uid()}&select=contest_id,roll_ref", token = token())
        ok(c, t)
        return JSONArray(t).objects().associate { it.getString("contest_id") to it.getString("roll_ref") }
    }

    /** Ritira il rullino in regalo: uno a testa per concorso. */
    suspend fun claimGift(contestId: String, ref: String) {
        val body = JSONObject().put("contest_id", contestId).put("user_id", uid()).put("roll_ref", ref)
        val (c, t) = call("POST", "/rest/v1/contest_gifts", body.toString().toByteArray(), "application/json", token())
        ok(c, t)
    }

    /** Manda la foto (e la liberatoria se ci sono persone), poi registra la partecipazione. */
    suspend fun submit(contest: Contest, roll: RollEntity, frame: Int, photo: ByteArray, release: ByteArray?) {
        val me = uid()
        val tok = token()
        val photoPath = "${contest.id}/$me/photo.jpg"
        upload(photoPath, photo, "image/jpeg", tok)
        val releasePath = release?.let { "${contest.id}/$me/release.pdf".also { p -> upload(p, it, "application/pdf", tok) } }
        val body = JSONObject()
            .put("contest_id", contest.id).put("user_id", me)
            .put("roll_ref", roll.contestRef ?: "local-${roll.id}")
            .put("frame", frame).put("emulsion_id", roll.emulsionId)
            .put("loaded_at", iso(roll.loadedAt))
            .put("has_people", release != null)
            .put("photo_path", photoPath)
            .put("release_path", releasePath ?: JSONObject.NULL)
        val (c, t) = call("POST", "/rest/v1/contest_entries", body.toString().toByteArray(), "application/json", tok)
        ok(c, t)
    }

    suspend fun withdraw(entry: ContestEntry) {
        val tok = token()
        val (c, t) = call("DELETE", "/rest/v1/contest_entries?id=eq.${entry.id}", token = tok)
        ok(c, t)
        // Le foto restano solo se il concorso è nel frattempo chiuso: allora la RLS le protegge comunque.
        runCatching { call("DELETE", "/storage/v1/object/contest-entries/${entry.photoPath}", token = tok) }
        entry.releasePath?.let { p -> runCatching { call("DELETE", "/storage/v1/object/contest-entries/$p", token = tok) } }
    }

    /** Le foto in gara, per la giuria. Senza autore: si vota alla cieca. */
    suspend fun entriesForJudging(contestId: String): List<ContestEntry> {
        val (c, t) = call("GET", "/rest/v1/contest_entries?contest_id=eq.$contestId&select=*&order=created_at.asc", token = token())
        ok(c, t)
        return JSONArray(t).objects().map { entry(it).copy(author = null) }
    }

    suspend fun myScores(contestId: String): Map<String, ContestScore> {
        val (c, t) = call(
            "GET",
            "/rest/v1/contest_scores?judge_id=eq.${uid()}&select=entry_id,score,note,contest_entries!inner(contest_id)&contest_entries.contest_id=eq.$contestId",
            token = token(),
        )
        ok(c, t)
        return JSONArray(t).objects().map(::score).associateBy { it.entryId }
    }

    /** Voto 1–10 con nota facoltativa; votando di nuovo si corregge. */
    suspend fun vote(entryId: String, score: Int, note: String?) {
        val body = JSONObject().put("entry_id", entryId).put("judge_id", uid()).put("score", score.coerceIn(1, 10))
            .put("note", note?.trim()?.take(500)?.ifEmpty { null } ?: JSONObject.NULL)
        val (c, t) = call(
            "POST", "/rest/v1/contest_scores?on_conflict=entry_id,judge_id", body.toString().toByteArray(), "application/json", token(),
            prefer = "resolution=merge-duplicates",
        )
        ok(c, t)
    }

    /** I voti alla mia foto, visibili quando il concorso è chiuso. */
    suspend fun scoresOf(entryId: String): List<ContestScore> {
        val (c, t) = call("GET", "/rest/v1/contest_scores?entry_id=eq.$entryId&select=entry_id,score,note", token = token())
        ok(c, t)
        return JSONArray(t).objects().map(::score)
    }

    /** Solo OP: chiude, calcola la classifica, assegna i premi e accende la bacheca. */
    suspend fun close(contestId: String) {
        val body = JSONObject().put("c", contestId)
        val (c, t) = call("POST", "/rest/v1/rpc/close_contest", body.toString().toByteArray(), "application/json", token())
        ok(c, t)
    }

    /** Le foto premiate dei concorsi chiusi da meno di una settimana. */
    suspend fun board(): List<Pair<Contest, ContestEntry>> {
        val (c, t) = call(
            "GET",
            "/rest/v1/contest_entries?place=not.is.null&select=*,profiles(username,display_name),contests(*)&order=place.asc",
            token = token(),
        )
        ok(c, t)
        val now = System.currentTimeMillis()
        return JSONArray(t).objects().mapNotNull { j ->
            val k = contest(j.getJSONObject("contests"))
            if ((k.boardUntil ?: 0) > now) k to entry(j) else null
        }.sortedWith(compareByDescending<Pair<Contest, ContestEntry>> { it.first.endsAt }.thenBy { it.second.place })
    }

    /** Ritira in un colpo i premi non ancora ritirati. */
    suspend fun claimRewards(): List<ContestReward> {
        val (c, t) = call("POST", "/rest/v1/rpc/claim_rewards", "{}".toByteArray(), "application/json", token())
        ok(c, t)
        return JSONArray(t).objects().map {
            ContestReward(
                contestId = it.getString("contest_id"), place = it.optInt("place"), coins = it.optInt("coins"),
                emulsionId = it.str("emulsion_id"), emulsionUrl = it.str("emulsion_url"),
            )
        }
    }

    /** Scarica una foto del bucket privato in [dest] (cache), con il token dell'utente. */
    suspend fun image(path: String, dest: File): File = withContext(Dispatchers.IO) {
        if (dest.exists() && dest.length() > 0) return@withContext dest
        val c = open("GET", "/storage/v1/object/authenticated/contest-entries/$path", token())
        try {
            if (c.responseCode !in 200..299) throw ContestException(ContestException.Kind.NOT_ALLOWED)
            dest.parentFile?.mkdirs()
            val tmp = File(dest.path + ".part")
            c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            tmp.renameTo(dest)
            dest
        } catch (e: IOException) {
            throw ContestException(ContestException.Kind.NETWORK)
        } finally {
            c.disconnect()
        }
    }

    /* ---------------- dettagli ---------------- */

    private suspend fun upload(path: String, bytes: ByteArray, type: String, tok: String) {
        val (c, t) = call("POST", "/storage/v1/object/contest-entries/$path", bytes, type, tok, upsert = true)
        ok(c, t)
    }

    private fun ok(code: Int, text: String) {
        if (code in 200..299) return
        throw ContestException(
            when {
                code == 401 -> ContestException.Kind.SIGNED_OUT
                "23505" in text || code == 409 -> ContestException.Kind.ALREADY
                "42501" in text || code == 403 -> ContestException.Kind.NOT_ALLOWED
                else -> ContestException.Kind.OTHER
            }
        )
    }

    private fun open(method: String, path: String, token: String?): HttpURLConnection =
        (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("apikey", key)
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }

    private suspend fun call(
        method: String, path: String, body: ByteArray? = null, type: String? = null, token: String? = null,
        prefer: String? = null, upsert: Boolean = false,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val c = open(method, path, token)
        try {
            c.setRequestProperty("Accept", "application/json")
            if (prefer != null) c.setRequestProperty("Prefer", prefer)
            if (upsert) c.setRequestProperty("x-upsert", "true")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", type ?: "application/json")
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } catch (e: IOException) {
            throw ContestException(ContestException.Kind.NETWORK)
        } finally {
            c.disconnect()
        }
    }

    private fun contest(j: JSONObject) = Contest(
        id = j.getString("id"),
        title = j.optString("title"),
        theme = j.str("theme") ?: "",
        description = j.str("description") ?: "",
        startsAt = ms(j.str("starts_at")) ?: 0,
        endsAt = ms(j.str("ends_at")) ?: 0,
        requiredEmulsion = j.str("required_emulsion"),
        giftRoll = j.optBoolean("gift_roll"),
        giftExposures = j.optInt("gift_exposures", 24),
        prizeCoins = j.optJSONArray("prize_coins")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList(),
        prizeEmulsion = j.str("prize_emulsion"),
        status = j.optString("status"),
        boardUntil = ms(j.str("board_until")),
    )

    private fun entry(j: JSONObject) = ContestEntry(
        id = j.getString("id"),
        contestId = j.getString("contest_id"),
        userId = j.getString("user_id"),
        frame = j.optInt("frame"),
        rollRef = j.optString("roll_ref"),
        hasPeople = j.optBoolean("has_people"),
        photoPath = j.optString("photo_path"),
        releasePath = j.str("release_path"),
        place = if (j.isNull("place")) null else j.optInt("place"),
        avgScore = if (j.isNull("avg_score")) null else j.optDouble("avg_score"),
        author = j.optJSONObject("profiles")?.let { it.str("display_name") ?: it.str("username") },
    )

    private fun score(j: JSONObject) = ContestScore(j.getString("entry_id"), j.optInt("score"), j.str("note"))

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONObject.str(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).ifEmpty { null }
    private fun ms(s: String?): Long? = s?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
    private fun iso(ms: Long): String = java.time.Instant.ofEpochMilli(ms).toString()
}
