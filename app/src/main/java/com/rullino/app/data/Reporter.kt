package com.rullino.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import com.rullino.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.max

/**
 * Crash e segnalazioni, senza servizi esterni: vanno nel nostro Supabase.
 * - Un crash viene scritto su file nel momento in cui succede e spedito al prossimo avvio.
 * - Gli errori gravi ma non fatali (es. uno sviluppo fallito) si spediscono subito.
 * - "Segnala un problema": testo, contatto facoltativo e una foto (uno screenshot dalla galleria).
 * Nessun dato personale: un codice casuale del dispositivo, modello, versione di Android e dell'app.
 */
class Reporter(private val context: Context, private val account: Account) {
    private val dir = File(context.filesDir, "crashes").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("rullino", Context.MODE_PRIVATE)

    /** Codice casuale del dispositivo: serve solo a non farsi inondare da un telefono impazzito. */
    val deviceId: String = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("deviceId", it).apply()
    }

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                File(dir, "crash-${System.currentTimeMillis()}.json").writeText(
                    JSONObject()
                        .put("kind", "crash")
                        .put("message", "${e.javaClass.name}: ${e.message ?: ""} [${thread.name}]")
                        .put("stack", stack(e))
                        .put("version", appVersion())
                        .toString()
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Al prossimo avvio: spedisce i crash rimasti in coda. */
    suspend fun flush() = withContext(Dispatchers.IO) {
        for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
            val j = runCatching { JSONObject(f.readText()) }.getOrNull()
            if (j == null) { f.delete(); continue }
            val code = runCatching { send(j.optString("kind", "crash"), j.optString("message"), j.optString("stack"), j.optString("version")) }.getOrDefault(-1)
            // Se il server risponde (anche con un rifiuto) il file ha fatto il suo; senza rete si riprova.
            if (code in 200..499) f.delete() else break
        }
    }

    /** Errore non fatale: lo spedisce subito, senza disturbare se non c'è rete. */
    suspend fun error(where: String, e: Throwable) = withContext(Dispatchers.IO) {
        Log.e("Rullino", where, e)
        runCatching { send("error", "$where · ${e.javaClass.simpleName}: ${e.message ?: ""}", stack(e), appVersion()) }
    }

    /**
     * Segnalazione scritta dall'utente. Ritorna null se è partita, altrimenti il codice dell'errore
     * ("too_many", "empty", "network").
     */
    suspend fun feedback(message: String, contact: String?, image: Uri?): String? = withContext(Dispatchers.IO) {
        var path: String? = null
        if (image != null) {
            val jpeg = runCatching { shrink(image) }.getOrNull()
            if (jpeg != null) {
                val p = "$deviceId/${UUID.randomUUID()}.jpg"
                val c = runCatching { post("/storage/v1/object/feedback/$p", jpeg, "image/jpeg") }.getOrDefault(-1 to "")
                if (c.first in 200..299) path = p
            }
        }
        val body = base().put("message", message.trim()).put("contact", contact?.trim()?.ifBlank { null } ?: JSONObject.NULL)
            .put("screenshot", path ?: JSONObject.NULL)
        val (c, t) = runCatching { post("/rest/v1/rpc/send_feedback", body.toString().toByteArray(), "application/json") }
            .getOrElse { return@withContext "network" }
        when {
            c in 200..299 -> null
            "too_many" in t -> "too_many"
            "empty" in t -> "empty"
            else -> "network"
        }
    }

    /* ---------------- dettagli ---------------- */

    private suspend fun send(kind: String, message: String, stack: String, version: String): Int {
        val body = base().put("kind", kind).put("message", message).put("stack", stack).put("app_version", version)
        return post("/rest/v1/rpc/report_crash", body.toString().toByteArray(), "application/json").first
    }

    private fun base() = JSONObject()
        .put("device", deviceId)
        .put("platform", "android")
        .put("app_version", appVersion())
        .put("os_version", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")

    private fun appVersion() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    private fun stack(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString().take(30000)

    /** Screenshot rimpicciolito a 1600 px, in JPEG: basta per capire il problema e pesa poco. */
    private fun shrink(uri: Uri): ByteArray? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0) return null
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= 1600) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val scale = 1600f / max(bmp.width, bmp.height)
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    private suspend fun post(path: String, body: ByteArray, type: String): Pair<Int, String> {
        val token = runCatching { account.accessToken() }.getOrNull()
        val c = URL(BuildConfig.SUPABASE_URL + path).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 15000; c.readTimeout = 20000
            c.requestMethod = "POST"
            c.setRequestProperty("apikey", BuildConfig.SUPABASE_KEY)
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Content-Type", type)
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            code to text
        } finally {
            c.disconnect()
        }
    }
}
