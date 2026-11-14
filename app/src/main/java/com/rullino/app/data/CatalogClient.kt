package com.rullino.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Legge il catalogo del negozio dalla REST API di Supabase e scarica i file .emulsion. */
class CatalogClient(private val baseUrl: String, private val key: String) {

    val isConfigured: Boolean get() = baseUrl.isNotBlank() && key.isNotBlank()

    suspend fun list(): List<CatalogItem> = withContext(Dispatchers.IO) {
        val url = URL("${baseUrl.trimEnd('/')}/rest/v1/emulsions?select=*&published=eq.true&order=sort.asc,name.asc")
        val c = url.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("apikey", key)
            // Le vecchie chiavi anon sono JWT e vanno anche come Bearer; le nuove sb_publishable_ no.
            if (key.startsWith("eyJ")) c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode !in 200..299) throw IOException("Il negozio non risponde (errore ${c.responseCode})")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            AppJson.decodeFromString<List<CatalogItem>>(body)
        } finally {
            c.disconnect()
        }
    }

    /** Scarica in [dest] e restituisce il file. */
    suspend fun download(fileUrl: String, dest: File): File = withContext(Dispatchers.IO) {
        val c = URL(fileUrl).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            if (c.responseCode !in 200..299) throw IOException("Download non riuscito (errore ${c.responseCode})")
            dest.parentFile?.mkdirs()
            c.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            dest
        } finally {
            c.disconnect()
        }
    }
}
