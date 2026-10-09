package io.github.fishpimp.exiflab.metadata.summary

import com.drew.lang.KeyValuePair
import com.drew.lang.Rational
import com.drew.metadata.Directory
import com.drew.metadata.StringValue
import com.drew.metadata.png.PngDirectory
import io.github.fishpimp.exiflab.metadata.mapping.ValueText

// Lenient typed reads of metadata-extractor values: files store the same tag in many shapes.

/** Sanitized, trimmed text, or null when absent or blank. */
internal fun Directory.text(tag: Int): String? = when (val value = getObject(tag)) {
    null, is ByteArray -> null
    is String, is StringValue -> ValueText.sanitize(value.toString()).trim().ifEmpty { null }
    else -> getString(tag)?.let { ValueText.sanitize(it).trim().ifEmpty { null } }
}

/** An integer from a number, the first element of an array, or numeric text. */
internal fun Directory.int(tag: Int): Int? = when (val value = getObject(tag)) {
    is Rational -> if (value.denominator == 0L) null else value.toInt()
    is Number -> value.toInt()
    is IntArray -> value.firstOrNull()
    is ShortArray -> value.firstOrNull()?.toInt()
    is LongArray -> value.firstOrNull()?.toInt()
    is Array<*> -> (value.firstOrNull() as? Rational)?.takeIf { it.denominator != 0L }?.toInt()
    is String, is StringValue -> value.toString().trim().toIntOrNull()
    else -> null
}

/** All integers of an integer array tag (or a single integer as a one-element array). */
internal fun Directory.ints(tag: Int): IntArray? = when (val value = getObject(tag)) {
    is IntArray -> value
    is ShortArray -> IntArray(value.size) { value[it].toInt() }
    is LongArray -> IntArray(value.size) { value[it].toInt() }
    is Array<*> -> value.filterIsInstance<Rational>().map { it.toInt() }.toIntArray().takeIf { it.size == value.size }
    else -> int(tag)?.let { intArrayOf(it) }
}

/** A rational value, or the first of a rational array. */
internal fun Directory.rational(tag: Int): Rational? = when (val value = getObject(tag)) {
    is Rational -> value
    is Array<*> -> value.firstOrNull() as? Rational
    else -> null
}

/** All values of a rational array tag. */
internal fun Directory.rationals(tag: Int): List<Rational>? = when (val value = getObject(tag)) {
    is Rational -> listOf(value)
    is Array<*> -> value.filterIsInstance<Rational>().takeIf { it.size == value.size }
    else -> null
}

/** A finite double from a rational (zero denominators rejected), a number or numeric text. */
internal fun Directory.double(tag: Int): Double? = when (val value = getObject(tag)) {
    is Rational -> value.finiteValue()
    is Array<*> -> (value.firstOrNull() as? Rational)?.finiteValue()
    is Number -> value.toDouble().takeIf { it.isFinite() }
    is String, is StringValue -> value.toString().trim().toDoubleOrNull()?.takeIf { it.isFinite() }
    else -> null
}

/** Raw bytes of an undefined-type tag. */
internal fun Directory.bytes(tag: Int): ByteArray? = getObject(tag) as? ByteArray

/** Keyword and text of the tEXt, zTXt and iTXt entries a PNG text directory holds. */
internal fun PngDirectory.textEntries(): List<Pair<String, String>> =
    (getObject(PngDirectory.TAG_TEXTUAL_DATA) as? List<*>).orEmpty()
        .filterIsInstance<KeyValuePair>()
        .map { it.key to it.value.toString() }

internal fun Rational.finiteValue(): Double? = if (denominator == 0L) null else toDouble().takeIf { it.isFinite() }
