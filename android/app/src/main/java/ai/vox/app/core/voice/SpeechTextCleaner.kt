package ai.vox.app.core.voice

/** Turns model output into text that sounds natural when read by a TTS engine, split into sentence-sized chunks. */
object SpeechTextCleaner {
    private val codeBlock = Regex("```[\\s\\S]*?```")
    private val inlineCode = Regex("`([^`]*)`")
    private val link = Regex("\\[([^\\]]+)]\\((?:[^)]+)\\)")
    private val bareUrl = Regex("https?://\\S+")
    private val heading = Regex("(?m)^\\s{0,3}#{1,6}\\s*")
    private val bullet = Regex("(?m)^\\s*(?:[-*•]|\\d+[.)])\\s+")
    private val emphasis = Regex("(\\*\\*|__|\\*|_)(.+?)\\1")
    private val emoji = Regex("[\\p{So}\\uFE0F\\u200D]")
    private val spaces = Regex("[ \\t]+")

    fun clean(markdown: String): String {
        var t = markdown
        t = codeBlock.replace(t, " (code omitted) ")
        t = inlineCode.replace(t, "$1")
        t = link.replace(t, "$1")
        t = bareUrl.replace(t, "a link")
        t = heading.replace(t, "")
        t = bullet.replace(t, "")
        repeat(2) { t = emphasis.replace(t, "$2") }
        t = emoji.replace(t, "")
        t = t.trim().replace("\r", "").replace(Regex("\\s*\\n\\s*\\n\\s*"), ". ").replace(Regex("\\s*\\n\\s*"), ", ")
        t = spaces.replace(t, " ")
        t = t.replace(Regex("\\.\\s*\\.(?:\\s*\\.)*"), ".").replace(Regex(",\\s*\\."), ".")
        t = t.trim()
        return if (t.none { it.isLetterOrDigit() }) "" else t
    }

    /** Splits on sentence ends, packing sentences up to [maxChars] so the TTS engine gets natural phrasing. */
    fun chunks(markdown: String, maxChars: Int = 240): List<String> {
        val text = clean(markdown)
        if (text.isEmpty()) return emptyList()
        val sentences = Regex("(?<=[.!?])\\s+").split(text).filter { it.isNotBlank() }
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() { if (cur.isNotEmpty()) { out += cur.toString().trim(); cur.clear() } }
        for (s in sentences) {
            if (s.length > maxChars) {
                flush()
                s.split(Regex("(?<=[,;:])\\s+")).forEach { piece ->
                    if (cur.length + piece.length + 1 > maxChars) flush()
                    if (piece.length > maxChars) piece.chunked(maxChars).forEach { out += it } else cur.append(piece).append(' ')
                }
                flush()
            } else {
                if (cur.length + s.length + 1 > maxChars) flush()
                cur.append(s).append(' ')
            }
        }
        flush()
        return out
    }
}
