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
        "critical", "crit", "combo", "passive", "active skill", "cast", "casting"
    )

    /**
     * Replaces protected terms (numbers + glossary words + likely proper nouns) with
     * placeholder tokens before translation, so ML Kit never sees/mangles them, then restores
     * the original English term after translation. Returns the placeholder text and the lookup
     * map needed to restore it.
     */
    private fun protect(text: String): Pair<String, List<String>> {
        val restore = mutableListOf<String>()

        fun placeholder(original: String): String {
            val token = "\u2060P${restore.size}\u2060" // word-joiner wrapped, unlikely to be altered/split
            restore.add(original)
            return token
        }

        var working = text

        // 1) Numbers/ordinals/percentages
        working = NUMBER_REGEX.replace(working) { m -> placeholder(m.value) }

        // 2) Fantasy/LitRPG glossary terms (whole word, case-insensitive)
        if (FANTASY_GLOSSARY.isNotEmpty()) {
            val glossaryRegex = Regex(
                "\\b(" + FANTASY_GLOSSARY.sortedByDescending { it.length }
                    .joinToString("|") { Regex.escape(it) } + ")\\b",
                RegexOption.IGNORE_CASE
            )
            working = glossaryRegex.replace(working) { m -> placeholder(m.value) }
        }

        // 3) Likely proper nouns: a Capitalized word NOT at the start of the text/sentence
        //    (character names, place names). Sentence start = preceded by start-of-string or
        //    [.!?] + whitespace; anything else capitalized mid-sentence is treated as a name.
        val properNounRegex = Regex("""(?<=[a-z,;:’'")\]]\s)([A-Z][a-zA-Z'’-]{1,})""")
        working = properNounRegex.replace(working) { m -> placeholder(m.value) }

        return working to restore
    }

    private fun restore(translated: String, restoreList: List<String>): String {
        var result = translated
        restoreList.forEachIndexed { i, original ->
            val token = "\u2060P$i\u2060"
            result = result.replace(token, original)
            // Fallback in case whitespace was inserted inside the token by the translator.
            result = result.replace(Regex("\u2060\\s*P\\s*$i\\s*\u2060"), original)
        }
        return result
    }

    /**
     * Translates [text] to Taglish: everyday Tagalog with numbers, fantasy/game terms, and
     * probable proper nouns left in English. Returns null on failure (caller should fall back
     * to original text).
     */
    suspend fun translate(text: String): String? {
        if (text.isBlank()) return text
        val (protectedText, restoreList) = protect(text)
        val result = try {
            suspendCancellableCoroutine<String?> { cont ->
                translator.translate(protectedText)
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            }
        } catch (_: Exception) { null }
        return result?.let { restore(it, restoreList) }
    }
}
