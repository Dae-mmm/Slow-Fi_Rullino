package com.rullino.app.release

import com.rullino.app.R
import android.content.Context
import com.rullino.app.data.ReleaseEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Testo della liberatoria. Di base è il segnaposto in assets/liberatoria/testo-<lingua>.txt;
 * dalle Opzioni si può sostituire con un testo proprio (salvato sul telefono).
 * Segnaposto disponibili: {{NOME}} {{COGNOME}} {{CONTATTO}} {{FOTOGRAFO}} {{DATA}} {{LUOGO}}
 * {{RULLINO}} {{POSA}} {{USI_ESTERNI}} {{TUTORE}}
 */
class ReleaseTemplate(private val context: Context) {
    private val override = File(context.filesDir, "liberatoria.txt")

    val isCustom: Boolean get() = override.exists()

    fun text(): String =
        if (override.exists()) override.readText()
        else defaultText()

    private fun defaultText(): String {
        val lang = Locale.getDefault().language
        val name = if (lang in setOf("it", "fr", "es", "de")) "testo-$lang.txt" else "testo-en.txt"
        return context.assets.open("liberatoria/$name").bufferedReader().use { it.readText() }
    }

    fun save(text: String) = override.writeText(text)
    fun reset() { override.delete() }

    fun fill(r: ReleaseEntity, rollName: String): String {
        val date = SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(r.signedAt))
        return text()
            .replace("{{NOME}}", r.firstName)
            .replace("{{COGNOME}}", r.lastName)
            .replace("{{CONTATTO}}", r.contact.ifBlank { "—" })
            .replace("{{FOTOGRAFO}}", r.photographer.ifBlank { context.getString(R.string.tpl_author) })
            .replace("{{DATA}}", date)
            .replace("{{LUOGO}}", r.place.ifBlank { "—" })
            .replace("{{RULLINO}}", rollName)
            .replace("{{POSA}}", r.frame.toString())
            .replace(
                "{{USI_ESTERNI}}",
                context.getString(if (r.externalUse) R.string.tpl_ext_yes else R.string.tpl_ext_no),
            )
            .replace(
                "{{TUTORE}}",
                if (r.isMinor) context.getString(R.string.tpl_guardian, r.guardianName.ifBlank { "—" })
                else "",
            )
    }
}
