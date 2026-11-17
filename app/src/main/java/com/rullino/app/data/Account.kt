package com.rullino.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime

/** Livelli di accesso: utente normale, giudice dei concorsi, OP (admin/moderatore/sviluppatore). */
enum class Role {
    USER, JUDGE, OP;

    companion object {
        fun of(s: String?): Role = when (s) { "op" -> OP; "judge" -> JUDGE; else -> USER }
    }
}

data class Profile(
    val id: String,
    val email: String,
    val username: String?,
    val displayName: String?,
    val role: Role,
)

/** Cosa sblocca l'abbonamento. Per ora niente: vedi [Features.PRO_VISIBLE]. */
data class Entitlements(val pro: Boolean = false)

object Features {
    /**
     * Mostra nell'app ciò che riguarda la versione Pro (offerta, badge, funzioni riservate).
     * Spento finché non decidiamo cosa include l'abbonamento: il dato arriva comunque dal server.
     */
    const val PRO_VISIBLE = false
}

class AccountException(val kind: Kind) : Exception(kind.name) {
    enum class Kind { NETWORK, SEND, CODE, USERNAME_TAKEN, USERNAME_INVALID, OTHER }
}

private data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val userId: String,
    val email: String,
)

/**
 * Account su Supabase Auth con codice via email (niente password, niente link da aprire).
 * Parla direttamente con le API REST: la sessione resta nelle preferenze private dell'app.
 */
class Account(context: Context, private val baseUrl: String, private val key: String) {
    private val prefs = context.getSharedPreferences("rullino-account", Context.MODE_PRIVATE)
    private var session: Session? = loadSession()

    private val _profile = MutableStateFlow<Profile?>(null)
    val profile: StateFlow<Profile?> = _profile
    private val _signedIn = MutableStateFlow(session != null)
    val signedIn: StateFlow<Boolean> = _signedIn
    private val _entitlements = MutableStateFlow(Entitlements())
    val entitlements: StateFlow<Entitlements> = _entitlements

    val isConfigured: Boolean get() = baseUrl.isNotBlank() && key.isNotBlank()
    val email: String? get() = session?.email

    /** Manda il codice a 6 cifre; crea l'utente se non esiste. */
    suspend fun sendCode(email: String) {
        val (code, _) = call("POST", "/auth/v1/otp", JSONObject().put("email", email.trim()).put("create_user", true))
        if (code !in 200..299) throw AccountException(AccountException.Kind.SEND)
    }

    suspend fun verify(email: String, token: String) {
        val body = JSONObject().put("type", "email").put("email", email.trim()).put("token", token.trim())
        val (code, text) = call("POST", "/auth/v1/verify", body)
        if (code !in 200..299) throw AccountException(AccountException.Kind.CODE)
        saveSession(parseSession(JSONObject(text)))
        refresh()
    }

    /** Ricarica profilo e abbonamento (anche all'avvio, se c'è una sessione salvata). */
    suspend fun refresh() {
        val token = validToken() ?: return
        val uid = session?.userId ?: return
        val (c1, t1) = call("GET", "/rest/v1/profiles?id=eq.$uid&select=id,username,display_name,role", null, token)
        if (c1 in 200..299) {
            JSONArray(t1).optJSONObject(0)?.let { p ->
                _profile.value = Profile(
                    id = uid,
                    email = session?.email ?: "",
                    username = p.optStringOrNull("username"),
                    displayName = p.optStringOrNull("display_name"),
                    role = Role.of(p.optStringOrNull("role")),
                )
            }
        }
        val (c2, t2) = call("GET", "/rest/v1/subscriptions?user_id=eq.$uid&select=status,current_period_end", null, token)
        if (c2 in 200..299) {
            val s = JSONArray(t2).optJSONObject(0)
            val active = s != null && s.optString("status") in setOf("active", "trialing", "grace") &&
                (s.optStringOrNull("current_period_end")?.let { OffsetDateTime.parse(it).isAfter(OffsetDateTime.now()) } ?: true)
            _entitlements.value = Entitlements(pro = active)
        }
    }

    suspend fun updateProfile(username: String, displayName: String) {
        val token = validToken() ?: throw AccountException(AccountException.Kind.OTHER)
        val body = JSONObject().put("p_username", username).put("p_display_name", displayName)
        val (code, text) = call("POST", "/rest/v1/rpc/update_my_profile", body, token)
        if (code !in 200..299) {
            throw AccountException(
                when {
                    "23505" in text -> AccountException.Kind.USERNAME_TAKEN
                    "23514" in text -> AccountException.Kind.USERNAME_INVALID
                    else -> AccountException.Kind.OTHER
                }
            )
        }
        refresh()
    }

    suspend fun signOut() {
        session?.let { s -> runCatching { call("POST", "/auth/v1/logout", JSONObject(), s.accessToken) } }
        clear()
    }

    /** Cancella l'account sul server (profilo e abbonamento a cascata). */
    suspend fun deleteAccount() {
        val token = validToken() ?: throw AccountException(AccountException.Kind.OTHER)
        val (code, _) = call("POST", "/functions/v1/delete-account", JSONObject(), token)
        if (code !in 200..299) throw AccountException(AccountException.Kind.OTHER)
        clear()
    }

    /* ---------------- sessione ---------------- */

    private suspend fun validToken(): String? {
        val s = session ?: return null
        if (s.expiresAt - 60_000 > System.currentTimeMillis()) return s.accessToken
        val (code, text) = call("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", s.refreshToken))
        if (code in 200..299) {
            saveSession(parseSession(JSONObject(text)))
            return session?.accessToken
        }
        if (code == 400 || code == 401) clear()   // sessione revocata o scaduta
        return null
    }

    private fun parseSession(j: JSONObject): Session {
        val user = j.getJSONObject("user")
        return Session(
            accessToken = j.getString("access_token"),
            refreshToken = j.getString("refresh_token"),
            expiresAt = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000,
            userId = user.getString("id"),
            email = user.optString("email"),
        )
    }

    private fun saveSession(s: Session) {
        session = s
        _signedIn.value = true
        prefs.edit()
            .putString("access", s.accessToken).putString("refresh", s.refreshToken)
            .putLong("expires", s.expiresAt).putString("uid", s.userId).putString("email", s.email)
            .apply()
    }

    private fun loadSession(): Session? {
        val refresh = prefs.getString("refresh", null) ?: return null
        return Session(
            accessToken = prefs.getString("access", "") ?: "",
            refreshToken = refresh,
            expiresAt = prefs.getLong("expires", 0),
            userId = prefs.getString("uid", "") ?: "",
            email = prefs.getString("email", "") ?: "",
        )
    }

    private fun clear() {
        session = null
        prefs.edit().clear().apply()
        _profile.value = null
        _entitlements.value = Entitlements()
        _signedIn.value = false
    }

    private suspend fun call(method: String, path: String, body: JSONObject?, token: String? = null): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val c = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                c.setRequestProperty("apikey", key)
                c.setRequestProperty("Accept", "application/json")
                if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
            } catch (e: IOException) {
                throw AccountException(AccountException.Kind.NETWORK)
            } finally {
                c.disconnect()
            }
        }
}

private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else optString(k).ifEmpty { null }
