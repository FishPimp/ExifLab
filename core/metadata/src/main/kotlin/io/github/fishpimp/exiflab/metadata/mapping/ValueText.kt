package io.github.fishpimp.exiflab.metadata.mapping

import com.drew.lang.KeyValuePair
import com.drew.lang.Rational
import com.drew.metadata.StringValue
import java.time.format.DateTimeFormatter
import java.util.Date

/** Turns stored metadata values into display-safe text. */
internal object ValueText {
    /** Byte arrays show at most this many bytes as hex. */
    const val MAX_HEX_BYTES = 64

    /** Arrays show at most this many items. */
    const val MAX_ARRAY_ITEMS = 64

    /** Text longer than this is cut, so a stray multi-megabyte value cannot stall the UI. */
    const val MAX_TEXT_LENGTH = 4096

    /**
     * The raw value as stored: rationals as "n/d", arrays space-separated, bytes as hex
     * (truncated after [MAX_HEX_BYTES] with the total size), text sanitized.
     */
    fun raw(value: Any?): String = when (value) {
        null -> ""
        is Rational -> "${value.numerator}/${value.denominator}"
        is StringValue -> sanitize(value.toString())
        is String -> sanitize(value)
        is ByteArray -> hex(value)
        is IntArray -> items(value.size) { value[it].toString() }
        is ShortArray -> items(value.size) { value[it].toString() }
        is LongArray -> items(value.size) { value[it].toString() }
        is FloatArray -> items(value.size) { value[it].toString() }
        is DoubleArray -> items(value.size) { value[it].toString() }
        is BooleanArray -> items(value.size) { value[it].toString() }
        is Array<*> -> items(value.size) { raw(value[it]) }
        is KeyValuePair -> sanitize("${value.key}: ${value.value}")
        is Iterable<*> -> sanitize(value.joinToString("\n") { raw(it) })
        is Date -> DateTimeFormatter.ISO_INSTANT.format(value.toInstant())
        else -> sanitize(value.toString())
    }

    /** Hex bytes ("4A 46 49 46"), truncated after [MAX_HEX_BYTES] with " ... (N bytes)". */
    fun hex(bytes: ByteArray): String {
        val shown = bytes.take(MAX_HEX_BYTES).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        return if (bytes.size > MAX_HEX_BYTES) "$shown ... (${bytes.size} bytes)" else shown
    }

    /**
     * Makes text safe to show: trailing NULs and whitespace are dropped, line breaks normalized,
     * other control characters escaped as `\xHH`, and very long text truncated.
     */
    fun sanitize(text: String): String {
        val trimmed = text.trimEnd { it == '\u0000' || it.isWhitespace() }
        val out = StringBuilder(minOf(trimmed.length, MAX_TEXT_LENGTH + 32))
        var i = 0
        while (i < trimmed.length && out.length < MAX_TEXT_LENGTH) {
            val c = trimmed[i]
            when {
                c == '\r' -> {
                    out.append('\n')
                    if (trimmed.getOrNull(i + 1) == '\n') i++
                }
                c == '\n' || c == '\t' -> out.append(c)
                c.isControlCharacter() -> out.append("\\x%02X".format(c.code))
                else -> out.append(c)
            }
            i++
        }
        if (i < trimmed.length) out.append(" ... (${trimmed.length} characters)")
        return out.toString()
    }

    private fun Char.isControlCharacter() = code < 0x20 || code in 0x7F..0x9F

    private fun items(size: Int, item: (Int) -> String): String {
        val shown = (0 until minOf(size, MAX_ARRAY_ITEMS)).joinToString(" ") { item(it) }
        return if (size > MAX_ARRAY_ITEMS) "$shown ... ($size values)" else shown
    }
}
