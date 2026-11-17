package com.rullino.app.data

import com.rullino.app.R
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
    private fun s(id: Int, vararg a: Any): String = context.getString(id, *a)

    companion object {
        const val SUPPORTED_FORMAT_VERSION = 1
        const val MIMETYPE = "application/x-emulsion"
        private const val MAX_TOTAL_BYTES = 60L * 1024 * 1024
    }

    suspend fun install(input: InputStream, source: String, version: Int = 1): EmulsionEntity = withContext(Dispatchers.IO) {
        val entries = readZip(input)

        entries["mimetype"]?.let {
            if (String(it, Charsets.UTF_8).trim() != MIMETYPE) throw InvalidEmulsionException(s(R.string.inst_not_emulsion))
        }
        val manifestBytes = entries["manifest.json"] ?: throw InvalidEmulsionException(s(R.string.inst_no_manifest))
        val manifest = runCatching { AppJson.decodeFromString<Manifest>(String(manifestBytes, Charsets.UTF_8)) }
            .getOrElse { throw InvalidEmulsionException(s(R.string.inst_bad_manifest)) }
        if (manifest.format != "emulsion") throw InvalidEmulsionException(s(R.string.inst_not_emulsion))
        if (manifest.formatVersion > SUPPORTED_FORMAT_VERSION) {
            throw InvalidEmulsionException(s(R.string.inst_newer))
        }

        for ((name, expected) in manifest.sha256) {
            if (expected.isNullOrBlank()) continue
            val bytes = entries[name] ?: throw InvalidEmulsionException(s(R.string.inst_missing, name))
            if (!sha256(bytes).equals(expected, ignoreCase = true)) throw InvalidEmulsionException(s(R.string.inst_damaged, name))
        }

        val presetBytes = entries[manifest.files.preset] ?: throw InvalidEmulsionException(s(R.string.inst_no_preset))
        val preset = runCatching { AppJson.decodeFromString<Preset>(String(presetBytes, Charsets.UTF_8)) }
            .getOrElse { throw InvalidEmulsionException(s(R.string.inst_bad_preset)) }

        val id = safeId(manifest.id.ifBlank { preset.id })
        if (id.isBlank()) throw InvalidEmulsionException(s(R.string.inst_no_id))

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
        if (!tmp.renameTo(dir)) throw InvalidEmulsionException(s(R.string.inst_no_space))

        val name = manifest.name.ifBlank { preset.name }.ifBlank { s(R.string.inst_untitled) }
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
            version = version,
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
                    throw InvalidEmulsionException(s(R.string.inst_bad_paths))
                }
                val bos = ByteArrayOutputStream()
                while (true) {
                    val n = zip.read(buffer)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_TOTAL_BYTES) throw InvalidEmulsionException(s(R.string.inst_too_big))
                    bos.write(buffer, 0, n)
                }
                out[name] = bos.toByteArray()
            }
        }
        if (out.isEmpty()) throw InvalidEmulsionException(s(R.string.inst_not_emulsion))
        return out
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun safeId(raw: String): String = raw.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)
}
