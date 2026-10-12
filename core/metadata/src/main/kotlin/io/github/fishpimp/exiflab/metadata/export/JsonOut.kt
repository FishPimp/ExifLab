package io.github.fishpimp.exiflab.metadata.export

/**
 * A minimal streaming JSON writer with two-space indentation. Callers keep the structure valid;
 * it only tracks commas and indentation. Strings are escaped per RFC 8259 (plus U+2028 and U+2029,
 * so the output is also safe inside JavaScript); non-finite numbers become null.
 */
internal class JsonOut(private val out: Appendable) {
    /** Per open container: whether it has an element yet. */
    private val hasElements = ArrayDeque<Boolean>()
    private var afterKey = false

    fun beginObject(): JsonOut = open('{')
    fun endObject(): JsonOut = close('}')
    fun beginArray(): JsonOut = open('[')
    fun endArray(): JsonOut = close(']')

    fun name(name: String): JsonOut {
        separate()
        writeString(name)
        out.append(": ")
        afterKey = true
        return this
    }

    fun value(value: String?): JsonOut = raw(if (value == null) null else quoted(value))
    fun value(value: Boolean?): JsonOut = raw(value?.toString())
    fun value(value: Long?): JsonOut = raw(value?.toString())
    fun value(value: Int?): JsonOut = raw(value?.toString())
    fun value(value: Double?): JsonOut = raw(value?.takeIf { it.isFinite() }?.let(::number))

    fun field(name: String, value: String?): JsonOut = name(name).value(value)
    fun field(name: String, value: Boolean?): JsonOut = name(name).value(value)
    fun field(name: String, value: Long?): JsonOut = name(name).value(value)
    fun field(name: String, value: Int?): JsonOut = name(name).value(value)
    fun field(name: String, value: Double?): JsonOut = name(name).value(value)
    fun nullField(name: String): JsonOut = name(name).raw(null)

    /** Ends the document with a newline. */
    fun finish() {
        check(hasElements.isEmpty()) { "Unclosed JSON container" }
        out.append('\n')
    }

    private fun open(bracket: Char): JsonOut {
        separate()
        out.append(bracket)
        hasElements.addLast(false)
        return this
    }

    private fun close(bracket: Char): JsonOut {
        val had = hasElements.removeLast()
        if (had) newline()
        out.append(bracket)
        return this
    }

    private fun raw(text: String?): JsonOut {
        separate()
        out.append(text ?: "null")
        return this
    }

    /** Comma and line break before a new element, unless it is the value right after its key. */
    private fun separate() {
        if (afterKey) {
            afterKey = false
            return
        }
        if (hasElements.isEmpty()) return
        if (hasElements.last()) out.append(',')
        hasElements[hasElements.lastIndex] = true
        newline()
    }

    private fun newline() {
        out.append('\n')
        repeat(hasElements.size) { out.append("  ") }
    }

    private fun writeString(value: String) {
        out.append(quoted(value))
    }

    companion object {
        fun quoted(value: String): String {
            val builder = StringBuilder(value.length + 2)
            builder.append('"')
            for (char in value) {
                when (char) {
                    '"' -> builder.append("\\\"")
                    '\\' -> builder.append("\\\\")
                    '\n' -> builder.append("\\n")
                    '\r' -> builder.append("\\r")
                    '\t' -> builder.append("\\t")
                    '\b' -> builder.append("\\b")
                    '\u000C' -> builder.append("\\f")
                    ' ', ' ' -> builder.append(unicodeEscape(char))
                    else -> if (char < ' ' || char == '\u007F') builder.append(unicodeEscape(char)) else builder.append(char)
                }
            }
            builder.append('"')
            return builder.toString()
        }

        private fun unicodeEscape(char: Char): String = String.format(java.util.Locale.ROOT, "\\u%04x", char.code)

        /** Shortest round-tripping form; whole numbers without a fraction ("12" rather than "12.0"). */
        fun number(value: Double): String {
            if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) return value.toLong().toString()
            return value.toString()
        }
    }
}
