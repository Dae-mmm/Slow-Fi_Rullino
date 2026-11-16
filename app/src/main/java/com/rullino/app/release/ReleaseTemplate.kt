package com.rullino.app.release

import android.content.Context
import com.rullino.app.data.ReleaseEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Testo della liberatoria. Di base è il segnaposto in assets/liberatoria/testo.txt;
 * dalle Opzioni si può sostituire con un testo proprio (salvato sul telefono).
 * Segnaposto disponibili: {{NOME}} {{COGNOME}} {{CONTATTO}} {{FOTOGRAFO}} {{DATA}} {{LUOGO}}
 * {{RULLINO}} {{POSA}} {{USI_ESTERNI}} {{TUTORE}}
 */
class ReleaseTemplate(private val context: Context) {
    private val override = File(context.filesDir, "liberatoria.txt")

    val isCustom: Boolean get() = override.exists()

    fun text(): String =
        if (override.exists()) override.readText()
        else context.assets.open("liberatoria/testo.txt").bufferedReader().use { it.readText() }

    fun save(text: String) = override.writeText(text)
    fun reset() { override.delete() }

    fun fill(r: ReleaseEntity, rollName: String): String {
        val date = SimpleDateFormat("d MMMM yyyy", Locale.ITALIAN).format(Date(r.signedAt))
        return text()
            .replace("{{NOME}}", r.firstName)
            .replace("{{COGNOME}}", r.lastName)
            .replace("{{CONTATTO}}", r.contact.ifBlank { "—" })
            .replace("{{FOTOGRAFO}}", r.photographer.ifBlank { "l'autore della foto" })
            .replace("{{DATA}}", date)
            .replace("{{LUOGO}}", r.place.ifBlank { "—" })
            .replace("{{RULLINO}}", rollName)
            .replace("{{POSA}}", r.frame.toString())
            .replace(
                "{{USI_ESTERNI}}",
                if (r.externalUse) "Autorizzo inoltre l'uso della fotografia fuori dall'app: mostre, stampe e pubblicazioni."
                else "Non autorizzo usi della fotografia fuori dall'app.",
            )
            .replace(
                "{{TUTORE}}",
                if (r.isMinor) "in qualità di genitore o tutore della persona minorenne ritratta ({{TUTORE_NOME}}),".replace("{{TUTORE_NOME}}", r.guardianName.ifBlank { "—" })
                else "",
            )
    }
}
