package com.rullino.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class InvalidEmulsionException(message: String) : Exception(message)

/**
 * Legge un file .emulsion (ZIP con mimetype, manifest.json, preset.json e immagini),
 * ne verifica l'integrità e lo installa in filesDir/emulsions/<id>/.
 */
class EmulsionInstaller(private val context: Context, private val db: AppDb) {

    companion object {
        const val SUPPORTED_FORMAT_VERSION = 1
        const val MIMETYPE = "application/x-emulsion"
        private const val MAX_TOTAL_BYTES = 60L * 1024 * 1024
    }

    suspend fun install(input: InputStream, source: String): EmulsionEntity = withContext(Dispatchers.IO) {
        val entries = readZip(input)

        entries["mimetype"]?.let {
            if (String(it, Charsets.UTF_8).trim() != MIMETYPE) throw InvalidEmulsionException("Questo file non è un'emulsione")
        }
        val manifestBytes = entries["manifest.json"] ?: throw InvalidEmulsionException("Il file non contiene manifest.json")
        val manifest = runCatching { AppJson.decodeFromString<Manifest>(String(manifestBytes, Charsets.UTF_8)) }
            .getOrElse { throw InvalidEmulsionException("manifest.json non è leggibile") }
        if (manifest.format != "emulsion") throw InvalidEmulsionException("Questo file non è un'emulsione")
        if (manifest.formatVersion > SUPPORTED_FORMAT_VERSION) {
            throw InvalidEmulsionException("Questa emulsione richiede una versione più recente dell'app")
        }

        for ((name, expected) in manifest.sha256) {
            if (expected.isNullOrBlank()) continue
            val bytes = entries[name] ?: throw InvalidEmulsionException("Manca il file $name")
            if (!sha256(bytes).equals(expected, ignoreCase = true)) throw InvalidEmulsionException("Il file $name è danneggiato")
        }

        val presetBytes = entries[manifest.files.preset] ?: throw InvalidEmulsionException("Manca il preset")
        val preset = runCatching { AppJson.decodeFromString<Preset>(String(presetBytes, Charsets.UTF_8)) }
            .getOrElse { throw InvalidEmulsionException("Il preset non è leggibile") }

        val id = safeId(manifest.id.ifBlank { preset.id })
        if (id.isBlank()) throw InvalidEmulsionException("L'emulsione non ha un identificativo")

        val root = File(context.filesDir, "emulsions").apply { mkdirs() }
        val dir = File(root, id)
        val tmp = File(root, "$id.tmp").apply { deleteRecursively(); mkdirs() }

        fun keep(name: String?): String? {
            if (name.isNullOrBlank()) return null
            val bytes = entries[name] ?: return null
            val out = File(tmp, name)
            if (!out.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) return null
            out.parentFile?.mkdirs()
            out.writeBytes(bytes)
            return name
        }

        File(tmp, "preset.json").writeBytes(presetBytes)
        val box = keep(manifest.files.box)
        val canister = keep(manifest.files.canister)
        val demos = manifest.files.demos.mapNotNull { keep(it) }
        keep(manifest.files.lut)

        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) throw InvalidEmulsionException("Spazio insufficiente per installare l'emulsione")

        val name = manifest.name.ifBlank { preset.name }.ifBlank { "Senza nome" }
        val entity = EmulsionEntity(
            id = id,
            name = name,
            iso = manifest.iso.takeIf { it > 0 } ?: preset.iso,
            exposures = manifest.exposures.takeIf { it > 0 } ?: preset.exposures,
            type = if (preset.params.mono.enabled || manifest.type == "bw") "bw" else "color",
            note = manifest.note.ifBlank { preset.note },
            dir = dir.absolutePath,
            boxFile = box,
            canisterFile = canister,
            demoFiles = demos.joinToString("|"),
            source = source,
            version = manifest.formatVersion,
            installedAt = System.currentTimeMillis(),
        )
        db.emulsions().upsert(entity)
        entity
    }

    fun loadPreset(e: EmulsionEntity): Preset =
        AppJson.decodeFromString(File(e.dir, "preset.json").readText())

    private fun readZip(input: InputStream): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory) continue
                if (name.startsWith("/") || name.split('/').any { it == ".." }) {
                    throw InvalidEmulsionException("Il file contiene percorsi non validi")
                }
                val bos = ByteArrayOutputStream()
                while (true) {
                    val n = zip.read(buffer)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_TOTAL_BYTES) throw InvalidEmulsionException("Il file è troppo grande")
                    bos.write(buffer, 0, n)
                }
                out[name] = bos.toByteArray()
            }
        }
        if (out.isEmpty()) throw InvalidEmulsionException("Questo file non è un'emulsione")
        return out
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun safeId(raw: String): String = raw.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)
}
