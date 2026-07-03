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
 * Output style is deliberately **Taglish**, matching how real Filipinos
 * actually speak/write casually, not a stiff "pure Tagalog" literary
 * translation. Two real linguistic patterns drive the design:
 *
 * 1. Content words that are technical, foreign-branded, or have no snappy
 *    native equivalent (numbers, game/fantasy terms, names) are normally kept
 *    in English by fluent Taglish speakers rather than force-translated —
 *    "communicative efficiency": the English word is simply clearer/shorter.
 * 2. Formal/literary/Spanish-rooted Tagalog vocabulary that a machine
 *    translator loves to produce ("subalit", "gayunpaman", "sapagkat"...)
 *    is basically never used in everyday spoken Taglish — people use short
 *    native connectors ("pero", "kasi", "kahit") or just keep the English
 *    word. A post-translation pass simplifies these.
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

    /** Numeric digits, ordinals, times, percentages — always kept exactly as written. */
    private val NUMBER_REGEX = Regex(
        """\b\d+(?:[.,]\d+)?(?:st|nd|rd|th)?%?\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Numbers spelled out as English words ("seven", "sixth", "twenty-one",
     * "hundred") — kept exactly as English text too, never converted to digits
     * and never translated to Tagalog ("pito", "anim"...), since mixed usage
     * inside one sentence is confusing to read.
     */
    private val NUMBER_WORDS = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
        "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy",
        "eighty", "ninety", "hundred", "thousand", "million", "billion", "trillion",
        "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth",
        "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth",
        "seventeenth", "eighteenth", "nineteenth", "twentieth", "thirtieth", "half", "dozen"
    )

    /** Common fantasy/LitRPG/gaming terms that read better left in English (Taglish habit #1). */
    private val FANTASY_GLOSSARY = setOf(
        "magic", "magical", "mana", "mp", "hp", "xp", "exp", "level", "levels", "lvl",
        "undead", "zombie", "vampire", "demon", "demons", "dungeon", "dungeons", "guild", "guilds",
        "quest", "quests", "skill", "skills", "skillset", "boss", "bosses", "item", "items",
        "inventory", "party", "class", "classes", "stat", "stats", "buff", "buffs",
        "debuff", "debuffs", "cooldown", "tank", "dps", "loot", "spawn", "raid", "npc",
        "system", "status", "rank", "ranks", "tier", "tiers", "achievement", "achievements",
        "hunter", "hunters", "gate", "gates", "portal", "portals", "artifact", "artifacts",
        "relic", "relics", "elemental", "elementals", "summon", "summoner", "summoning",
        "familiar", "familiars", "aura", "auras", "critical", "crit", "combo", "passive",
        "active skill", "cast", "casting", "spell", "spells",
        "lich", "liches", "lichdom", "elder lich", "night lich", "overlord", "overlords",
        "sorcerer", "sorcerers", "sorcery", "sorceress", "warrior", "warriors", "knight",
        "knights", "paladin", "paladins", "mage", "mages", "wizard", "wizards", "witch",
        "witches", "elf", "elves", "half-elf", "dwarf", "dwarves", "orc", "orcs", "goblin",
        "goblins", "titan", "titans", "dragon", "dragons", "familiar spirit", "world class",
        "domain", "throne", "kingdom", "kingdoms", "empire", "empires", "sanctuary",
        "guardian", "guardians", "monarch", "monarchs", "beast", "beasts", "monster",
        "monsters", "grade", "rating", "clan", "clans", "faction", "factions"
    )

    /**
     * Overly-formal / literary / Spanish-rooted Tagalog words that ML Kit's
     * translation model tends to output for literary English text — replaced
     * with the short native connector or English word real Taglish speakers
     * actually use in casual conversation. Best-effort cleanup applied AFTER
     * translation; extend this map whenever a new confusing word is reported.
     */
    private val SIMPLIFY_MAP = linkedMapOf(
        // discourse connectors — the classic "translator sounds like a textbook" words
        "subalit" to "pero",
        "ngunit" to "pero",
        "datapwat" to "pero",
        "gayunpaman" to "pero",
        "gayunman" to "pero",
        "sapagkat" to "kasi",
        "sapagka't" to "kasi",
        "yamang" to "dahil",
        "sapul" to "mula",
        "bagamat" to "kahit",
        "bagaman" to "kahit",
        "kung gayon" to "kaya",
        "samakatuwid" to "kaya",
        "kung kaya't" to "kaya",
        "hinggil sa" to "tungkol sa",
        "kaugnay ng" to "tungkol sa",
        "may kinalaman sa" to "may kaugnayan sa",
        "kaalinsabay" to "kasabay",
        "waring" to "para bang",
        "wari" to "parang",
        "kung saan" to "diyan",
        "yaon" to "'yon",
        "yaong" to "'yong",
        "noon pa man" to "dati pa",
        "malimit" to "madalas",
        "pawang" to "puro",
        "hangga't" to "hanggang",
        "tigib" to "puno",
        "taglay" to "meron",
        "nagbabadya" to "parang",
        "nasasaad" to "nakasulat",
        // formal/literary adjectives & verbs
        "maalamat" to "sikat na sikat",
        "mapanganib" to "delikado",
        "napakatalim" to "matalas",
        "kaakit-akit" to "ang ganda",
        "kagila-gilalas" to "grabe",
        "kahanga-hanga" to "grabe ang galing",
        "kagimbal-gimbal" to "nakakatakot",
        "labis na" to "sobrang",
        "totoong" to "talagang",
        "buong" to "sobrang",
        "walang-kapantay" to "walang kapareha",
        "walang-katulad" to "walang kapareha",
        "napakalaki ng" to "sobrang laki ng",
        "napakalakas" to "sobrang lakas",
        "nagpapasiklab" to "sumasabog",
        "pumipigil" to "humaharang",
        "gumuguho" to "gumigiba",
        "yumayanig" to "umaalog",
        "nag-aalinlangan" to "nagdadalawang-isip",
        "nangamba" to "natakot",
        "nasindak" to "natakot",
        "namangha" to "namangha", // keep, common
        "lumingap" to "tumingin",
        "sumibol" to "lumitaw",
        "kumislap" to "kumurap",
        "nag-alab" to "sumiklab",
        "pinaslang" to "pinatay",
        "pumaslang" to "pumatay",
        "sawimpalad" to "malas",
        "kapahamakan" to "problema",
        "kapalaran" to "tadhana"
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
     * Finds all spans of [text] that must be kept exactly as-is (numbers,
     * number-words, glossary terms, likely proper-noun phrases), in priority
     * order — later passes skip anything already covered by an earlier pass.
     */
    private fun findProtectedSpans(text: String): List<ProtectedSpan> {
        val spans = mutableListOf<ProtectedSpan>()
        fun overlaps(s: Int, e: Int) = spans.any { s < it.end && e > it.start }
        fun addIfFree(range: IntRange, value: String) {
            val s = range.first
            val e = range.last + 1
            if (!overlaps(s, e)) spans.add(ProtectedSpan(s, e, value))
        }

        // 1) Numeric digits/ordinals/percentages
        NUMBER_REGEX.findAll(text).forEach { addIfFree(it.range, it.value) }

        // 2) Spelled-out number words ("seven", "twenty-one", "sixth")
        if (NUMBER_WORDS.isNotEmpty()) {
            val numberWordRegex = Regex(
                "\\b(" + NUMBER_WORDS.sortedByDescending { it.length }
                    .joinToString("|") { Regex.escape(it) } + ")" +
                    "(?:-(" + NUMBER_WORDS.joinToString("|") { Regex.escape(it) } + "))?\\b",
                RegexOption.IGNORE_CASE
            )
            numberWordRegex.findAll(text).forEach { addIfFree(it.range, it.value) }
        }

        // 3) Fantasy/LitRPG glossary terms (whole word/phrase, case-insensitive)
        if (FANTASY_GLOSSARY.isNotEmpty()) {
            val glossaryRegex = Regex(
                "\\b(" + FANTASY_GLOSSARY.sortedByDescending { it.length }
                    .joinToString("|") { Regex.escape(it) } + ")\\b",
                RegexOption.IGNORE_CASE
            )
            glossaryRegex.findAll(text).forEach { addIfFree(it.range, it.value) }
        }

        // 4) Likely proper-noun phrases: one or more consecutive Capitalized words
        //    not at the very start of a sentence (character/place/title names, e.g.
        //    "Night Lich", "Elder Titan Overlord"). Matched as a run so a multi-word
        //    name never gets half-protected/half-translated.
        val properNounRegex = Regex(
            """(?<=[a-z,;:’'")\]]\s)([A-Z][a-zA-Z'’-]*(?:\s+[A-Z][a-zA-Z'’-]*)*)"""
        )
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
     * probable proper-noun phrases left in English. Protected spans are sliced out and never
     * sent to the translator at all, so they cannot be corrupted or lost. A post-translation
     * pass simplifies overly-formal/literary Tagalog vocabulary into common everyday words.
     * Returns null only if every translatable segment fails (caller should fall back to the
     * original text).
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

        suspend fun appendTranslated(chunk: String) {
            if (chunk.isBlank()) {
                sb.append(chunk)
                return
            }
            // Translators (both ML Kit and free web APIs) commonly trim/collapse
            // leading/trailing whitespace on whatever text they're given. Since chunks
            // sit right up against protected spans, that trimming used to glue words
            // together across a chunk/span boundary (e.g. "sateneksakto" instead of
            // "sa ten eksakto"). Strip the whitespace out ourselves before translating
            // and re-attach it verbatim afterward so spacing is always preserved.
            val leadingWs = chunk.takeWhile { it.isWhitespace() }
            val trailingWs = chunk.takeLastWhile { it.isWhitespace() }
            val trimmed = chunk.trim()
            if (trimmed.isEmpty()) {
                sb.append(chunk)
                return
            }
            val translated = translateChunk(trimmed)
            if (translated != null) anySuccess = true
            sb.append(leadingWs)
            sb.append(simplify(translated ?: trimmed))
            sb.append(trailingWs)
        }

        for (span in spans) {
            if (span.start > cursor) appendTranslated(text.substring(cursor, span.start))
            sb.append(span.text)
            cursor = span.end
        }
        if (cursor < text.length) appendTranslated(text.substring(cursor))

        return if (anySuccess) sb.toString() else null
    }
}
