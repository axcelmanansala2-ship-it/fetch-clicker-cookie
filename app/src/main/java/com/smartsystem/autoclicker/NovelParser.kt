package com.smartsystem.autoclicker

import android.content.ContentResolver
import android.net.Uri
import java.util.zip.ZipInputStream

/**
 * Shared file-loading & paragraph-parsing logic for the Novel Reader,
 * used by both the full-screen NovelReaderActivity and the floating
 * NovelReaderOverlayService so the two stay in sync.
 */
object NovelParser {

    fun readContent(resolver: ContentResolver, uri: Uri): String? {
        val mime = resolver.getType(uri) ?: ""
        val name = uri.lastPathSegment ?: ""
        return when {
            mime.contains("epub") || name.endsWith(".epub", true) -> readEpub(resolver, uri)
            else -> readText(resolver, uri)
        }
    }

    private fun readText(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
    } catch (_: Exception) { null }

    private fun readEpub(resolver: ContentResolver, uri: Uri): String? = try {
        val sb = StringBuilder()
        val entries = mutableListOf<Pair<String, String>>()
        resolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val n = entry.name.lowercase()
                    if ((n.endsWith(".html") || n.endsWith(".xhtml") || n.endsWith(".htm"))
                        && !n.contains("nav") && !n.contains("toc") && !n.contains("cover")) {
                        val html = zip.bufferedReader().readText()
                        val text = stripHtml(html)
                        if (text.length > 100) entries += entry.name to text
                    }
                    zip.closeEntry(); entry = zip.nextEntry
                }
            }
        }
        entries.sortBy { it.first }
        entries.forEach { sb.append(it.second).append("\n\n") }
        sb.toString().takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    private fun stripHtml(html: String): String = html
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<p[^>]*>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<h[1-6][^>]*>", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("</h[1-6]>", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("&#[0-9]+;"), "")
        .replace(Regex("\n{3,}"), "\n\n").trim()

    fun parseParagraphs(raw: String): List<String> {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        val byDouble = text.split(Regex("\n{2,}"))
        val paras: List<String> = if (byDouble.size > 3) {
            byDouble.map { it.replace('\n', ' ').replace(Regex("  +"), " ").trim() }
        } else {
            text.lines().map { it.trim() }
        }
        return paras.filter { it.length >= 10 }
    }
}
