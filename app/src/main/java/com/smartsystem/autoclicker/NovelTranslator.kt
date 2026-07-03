package com.smartsystem.autoclicker

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * On-device English → Tagalog (Filipino) translation using Google's ML Kit
 * Translate (neural machine translation), shared by NovelReaderActivity and
 * NovelReaderOverlayService so both stay consistent.
 *
 * The Tagalog model (~30 MB) is downloaded once — needs internet for that —
 * then all translation runs fully offline on-device.
 *
 * Output is "Taglish" style, not pure Tagalog: numbers, fantasy/LitRPG game
 * terms, and probable character/place names are kept in English since a
 * literal Tagalog translation of those is usually more confusing to read
 * than helpful (e.g. "9th level" shouldn't become "ikasiyam na antas", and
 * character names shouldn't be phonetically mangled).
 *
 * IMPORTANT: protected terms are NEVER sent through the translator. Earlier
 * versions inserted invisible placeholder tokens inline and asked the
 * translator to leave them alone, but ML Kit's NMT model would sometimes
 * strip/mangle the invisible marker characters and/or lowercase the token,
 * leaking raw "p0"/"p1"/"p2" text into the output. To make this impossible,
 * the text is now split into segments *before* calling the translator:
 * protected spans are kept completely untouched, and only the plain-English
 * segments in between are ever handed to `translator.translate()`.
 */
object NovelTranslator {

    private val translator: Translator by lazy {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TAGALOG)
            .build()
        Translation.getClient(options)
    }

    /** Downloads the Tagalog model if not already present. Returns true if ready to translate. */
    suspend fun ensureModelReady(): Boolean = try {
        suspendCancellableCoroutine { cont ->
            val conditions = DownloadConditions.Builder().build()
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener { if (cont.isActive) cont.resume(true) }
                .addOnFailureListener { if (cont.isActive) cont.resume(false) }
        }
    } catch (_: Exception) { false }

    // ─── Taglish protection ─────────────────────────────────────────────────

    /** Numbers, ordinals, times, percentages — always keep in English/digit form. */
    private val NUMBER_REGEX = Regex(
        """\b\d+(?:[.,]\d+)?(?:st|nd|rd|th)?%?\b""",
        RegexOption.IGNORE_CASE
    )

    /** Common fantasy/LitRPG/gaming terms that read better left in English. */
    private val FANTASY_GLOSSARY = setOf(
        "magic", "magical", "mana", "mp", "hp", "xp", "exp", "level", "levels", "lvl",
        "undead", "zombie", "vampire", "demon", "dungeon", "dungeons", "guild", "guilds",
        "quest", "quests", "skill", "skills", "skillset", "boss", "bosses", "item", "items",
        "inventory", "party", "class", "classes", "stat", "stats", "buff", "buffs",
        "debuff", "debuffs", "cooldown", "tank", "dps", "loot", "spawn", "raid", "npc",
        "system", "status", "rank", "ranks", "tier", "tiers", "achievement", "achievements",
        "hunter", "hunters", "gate", "portal", "artifact", "artifacts", "relic", "relics",
        "elemental", "summon", "summoner", "familiar", "aura", "mp regen", "hp regen",
        "critical", "crit", "combo", "passive", "active skill", "cast", "casting",
        "lich", "liches", "lichdom", "elder lich", "night lich", "overlord", "sorcerer",
        "sorcery", "sorceress", "warrior", "knight", "paladin", "mage", "wizard", "witch",
        "elf", "elves", "half-elf", "dwarf", "dwarves", "orc", "orcs", "goblin", "goblins",
        "titan", "dragon", "dragons", "familiar spirit", "world class", "domain", "throne",
        "kingdom", "empire", "sanctuary"
    )

    /**
     * A small set of overly-formal / Spanish-rooted Tagalog words that ML Kit's
     * translation model tends to output for literary English text, replaced with a
     * more common, easier-to-understand everyday Filipino equivalent. This is a
     * best-effort cleanup pass applied AFTER translation — add more pairs here as
     * specific confusing words are reported.
     */
    private val SIMPLIFY_MAP = linkedMapOf(
        "subalit" to "pero",
        "gayunpaman" to "pero",
        "sapagkat" to "kasi",
        "yamang" to "dahil",
        "bagamat" to "kahit",
        "mapanganib na" to "delikado na",
        "maalamat" to "kilalang-kilala",
        "sapul" to "mula",
        "hinggil sa" to "tungkol sa"
    )

    private fun simplify(text: String): String {
        var result = text
        for ((formal, simple) in SIMPLIFY_MAP) {
            result = Regex("\\b" + Regex.escape(formal) + "\\b", RegexOption.IGNORE_CASE)
                .replace(result) { m ->
                    if (m.value.firstOrNull()?.isUpperCase() == true)
                        simple.replaceFirstChar { it.uppercase() }
                    else simple
                }
        }
        return result
    }

    private data class ProtectedSpan(val start: Int, val end: Int, val text: String)

    /**
     * Finds all spans of [text] that must be kept exactly as-is (numbers, glossary
     * words, likely proper nouns), in priority order — later passes skip anything
     * already covered by an earlier pass so a protected number never gets treated
     * as part of a proper noun, etc.
     */
    private fun findProtectedSpans(text: String): List<ProtectedSpan> {
        val spans = mutableListOf<ProtectedSpan>()
        fun overlaps(s: Int, e: Int) = spans.any { s < it.end && e > it.start }
        fun addIfFree(range: IntRange, value: String) {
            val s = range.first
            val e = range.last + 1
            if (!overlaps(s, e)) spans.add(ProtectedSpan(s, e, value))
        }

        // 1) Numbers/ordinals/percentages
        NUMBER_REGEX.findAll(text).forEach { addIfFree(it.range, it.value) }

        // 2) Fantasy/LitRPG glossary terms (whole word, case-insensitive)
        if (FANTASY_GLOSSARY.isNotEmpty()) {
            val glossaryRegex = Regex(
                "\\b(" + FANTASY_GLOSSARY.sortedByDescending { it.length }
                    .joinToString("|") { Regex.escape(it) } + ")\\b",
                RegexOption.IGNORE_CASE
            )
            glossaryRegex.findAll(text).forEach { addIfFree(it.range, it.value) }
        }

        // 3) Likely proper nouns: a Capitalized word NOT at the start of the text/sentence
        //    (character names, place names). Sentence start = preceded by start-of-string or
        //    [.!?] + whitespace; anything else capitalized mid-sentence is treated as a name.
        val properNounRegex = Regex("""(?<=[a-z,;:’'")\]]\s)([A-Z][a-zA-Z'’-]{1,})""")
        properNounRegex.findAll(text).forEach { addIfFree(it.range, it.value) }

        return spans.sortedBy { it.start }
    }

    /** Translates a single plain-text chunk (no protected terms inside). */
    private suspend fun translateChunk(chunk: String): String? {
        if (chunk.isBlank()) return chunk
        return try {
            suspendCancellableCoroutine<String?> { cont ->
                translator.translate(chunk)
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            }
        } catch (_: Exception) { null }
    }

    /**
     * Translates [text] to Taglish: everyday Tagalog with numbers, fantasy/game terms, and
     * probable proper nouns left in English. Protected spans are sliced out and never sent
     * to the translator at all, so they cannot be corrupted or lost. Returns null only if
     * every translatable segment fails (caller should fall back to original text).
     */
    suspend fun translate(text: String): String? {
        if (text.isBlank()) return text

        val spans = findProtectedSpans(text)
        if (spans.isEmpty()) {
            return translateChunk(text)?.let { simplify(it) }
        }

        val sb = StringBuilder()
        var cursor = 0
        var anySuccess = false

        for (span in spans) {
            if (span.start > cursor) {
                val chunk = text.substring(cursor, span.start)
                if (chunk.isBlank()) {
                    sb.append(chunk)
                } else {
                    val translated = translateChunk(chunk)
                    if (translated != null) anySuccess = true
                    sb.append(simplify(translated ?: chunk))
                }
            }
            sb.append(span.text)
            cursor = span.end
        }
        if (cursor < text.length) {
            val chunk = text.substring(cursor)
            if (chunk.isBlank()) {
                sb.append(chunk)
            } else {
                val translated = translateChunk(chunk)
                if (translated != null) anySuccess = true
                sb.append(simplify(translated ?: chunk))
            }
        }

        return if (anySuccess) sb.toString() else null
    }
}
