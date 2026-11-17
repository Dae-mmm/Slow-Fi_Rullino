package com.rullino.app.release

import com.rullino.app.R
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.rullino.app.data.ReleaseEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Crea il PDF della liberatoria firmata e lo salva in Download/Rullino. */
object ReleasePdf {

    /** Il PDF in memoria, per mandarlo insieme alla foto di un concorso. */
    suspend fun bytes(context: Context, release: ReleaseEntity, text: String): ByteArray = withContext(Dispatchers.IO) {
        val doc = render(context, release, text)
        val out = java.io.ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        out.toByteArray()
    }

    private fun render(context: Context, release: ReleaseEntity, text: String): PdfDocument {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create()) // A4 in punti
        val c = page.canvas
        val margin = 56f

        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 10.5f }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, body, (595 - 2 * margin).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1.1f)
            .build()
        c.save()
        c.translate(margin, margin)
        layout.draw(c)
        c.restore()

        // Firma
        val sigTop = margin + layout.height + 36f
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 9f }
        c.drawText(context.getString(if (release.isMinor) R.string.sig_guardian else R.string.pdf_sig), margin, sigTop, label)
        BitmapFactory.decodeFile(release.signaturePath)?.let { sig ->
            val h = 90
            val w = (sig.width * h / sig.height.toFloat()).toInt().coerceAtMost(300)
            c.drawBitmap(sig, null, Rect(margin.toInt(), (sigTop + 6).toInt(), margin.toInt() + w, (sigTop + 6 + h).toInt()), null)
            sig.recycle()
        }
        c.drawLine(margin, sigTop + 100f, margin + 300f, sigTop + 100f, Paint().apply { color = Color.GRAY })

        c.drawText(context.getString(R.string.pdf_footer, release.rollId.toString(), release.frame.toString()), margin, 842 - 30f, label)
        doc.finishPage(page)

        return doc
    }

    suspend fun export(context: Context, release: ReleaseEntity, text: String): Uri = withContext(Dispatchers.IO) {
        val doc = render(context, release, text)
        val name = context.getString(R.string.pdf_prefix) + "_${release.lastName.ifBlank { context.getString(R.string.pdf_noname) }}_${release.rollId}-${release.frame}.pdf"
            .replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Rullino")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException(context.getString(R.string.err_pdf_save))
        resolver.openOutputStream(uri)?.use { doc.writeTo(it) } ?: throw IllegalStateException(context.getString(R.string.err_pdf_write))
        doc.close()
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri
    }

    @Suppress("unused")
    private fun File.safeDelete() = runCatching { delete() }
}
