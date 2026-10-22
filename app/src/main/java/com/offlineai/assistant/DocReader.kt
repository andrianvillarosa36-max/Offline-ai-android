package com.offlineai.assistant

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

class ExtractedDoc(val name: String, val text: String, val pages: Int)

/** Pulls plain text out of PDF, TXT and MD files. */
object DocReader {

    fun read(context: Context, uri: Uri, onProgress: (String) -> Unit): ExtractedDoc {
        val resolver = context.contentResolver
        val name = displayName(context, uri)
        val lower = name.lowercase()
        val mime = resolver.getType(uri) ?: ""

        val isPdf = lower.endsWith(".pdf") || mime == "application/pdf"
        val isText = lower.endsWith(".txt") || lower.endsWith(".md") || mime.startsWith("text/")

        val input = resolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not open that file.")

        return when {
            isPdf -> input.use { readPdf(context, it, name, onProgress) }
            isText -> input.use {
                ExtractedDoc(name, it.bufferedReader(Charsets.UTF_8).readText(), 0)
            }
            else -> {
                input.close()
                throw IllegalArgumentException("Only PDF, TXT and MD files are supported for now.")
            }
        }
    }

    private fun readPdf(
        context: Context,
        stream: java.io.InputStream,
        name: String,
        onProgress: (String) -> Unit
    ): ExtractedDoc {
        PDFBoxResourceLoader.init(context.applicationContext)
        val document = PDDocument.load(stream)
        return document.use { doc ->
            val pages = doc.numberOfPages
            val stripper = PDFTextStripper()
            val sb = StringBuilder()
            for (p in 1..pages) {
                onProgress("Reading page $p of $pages...")
                stripper.startPage = p
                stripper.endPage = p
                sb.append(stripper.getText(doc)).append("\n")
            }
            ExtractedDoc(name, sb.toString(), pages)
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        var name = "document"
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0) c.getString(col)?.let { name = it }
            }
        }
        return name
    }
}
