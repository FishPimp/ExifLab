package io.github.fishpimp.exiflab.metadata.edit

import io.github.fishpimp.exiflab.metadata.model.Rational
import java.math.BigDecimal
import java.math.BigInteger
import java.time.DateTimeException
import java.time.LocalDateTime

/** How a rational field's value reads best: exposure times as "1/250", everything else as decimals. */
internal enum class NumberStyle { ExposureTime, Decimal }

/** Parsing and normalizing of the numbers, dates and comments the tag editor accepts. */
internal object FieldValues {
    private val fractionPattern = Regex("""^([+-]?\d+)\s*/\s*(\d+)$""")
    private val decimalPattern = Regex("""^[+-]?(\d+(\.\d*)?|\.\d+)$""")
    private val dateTimePattern = Regex("""^(\d{4})[-:/.](\d{1,2})[-:/.](\d{1,2})(?:[ T]+(\d{1,2})[:.](\d{2})(?:[:.](\d{2}))?)?$""")

    /** A parsed number: an exact fraction n/d with d > 0, reduced. */
    class Fraction(val numerator: BigInteger, val denominator: BigInteger) {
        val value: Double get() = BigDecimal(numerator).divide(BigDecimal(denominator), java.math.MathContext.DECIMAL64).toDouble()
    }

    /**
     * Reads "2.8", "f/2.8", "1/250", "1/250 s", "-1/3", "+0.7 EV", "6.9 mm" and decimal commas.
     * Returns null for anything else.
     */
    fun parseNumber(input: String): Fraction? {
        var text = input.trim().lowercase().replace('−', '-').replace(" ", "")
        for (prefix in listOf("f/", "f")) if (text.startsWith(prefix) && text.length > prefix.length && (text[prefix.length].isDigit() || text[prefix.length] == '.')) {
            text = text.removePrefix(prefix)
            break
        }
        for (suffix in listOf("sec", "ev", "mm", "s")) text = text.removeSuffix(suffix)
        if (text.count { it == ',' } == 1 && '.' !in text) text = text.replace(',', '.')
        fractionPattern.matchEntire(text)?.let { match ->
            val denominator = match.groupValues[2].toBigInteger()
            if (denominator.signum() == 0) return null
            return reduce(match.groupValues[1].removePrefix("+").toBigInteger(), denominator)
        }
        if (!decimalPattern.matches(text)) return null
        val decimal = BigDecimal(text.removePrefix("+").let { if (it.endsWith('.')) it.dropLast(1) else it }).stripTrailingZeros()
        return if (decimal.scale() <= 0) {
            Fraction(decimal.toBigIntegerExact(), BigInteger.ONE)
        } else {
            Fraction(decimal.unscaledValue(), BigInteger.TEN.pow(decimal.scale()))
        }
    }

    private fun reduce(numerator: BigInteger, denominator: BigInteger): Fraction {
        val gcd = numerator.gcd(denominator).takeIf { it.signum() != 0 } ?: BigInteger.ONE
        return Fraction(numerator / gcd, denominator / gcd)
    }

    /** The text form [parseNumber] reads back, e.g. "1/250", "2.8", "-1/3", "30". */
    fun formatNumber(number: Fraction, style: NumberStyle): String {
        val reduced = reduce(number.numerator, number.denominator)
        if (reduced.denominator == BigInteger.ONE) return reduced.numerator.toString()
        if (style == NumberStyle.ExposureTime && number.value > 0 && number.value < 1) {
            if (reduced.numerator == BigInteger.ONE) return "1/${reduced.denominator}"
            val inverse = 1 / number.value
            val rounded = Math.round(inverse)
            if (rounded > 0 && kotlin.math.abs(inverse - rounded) <= 1e-9 * inverse) return "1/$rounded"
        }
        // A terminating decimal with few digits reads better than the fraction; thirds stay fractions.
        val exact = runCatching { BigDecimal(reduced.numerator).divide(BigDecimal(reduced.denominator)) }.getOrNull()
        if (exact != null && exact.stripTrailingZeros().scale() <= MAX_DECIMALS) return exact.stripTrailingZeros().toPlainString()
        return "${reduced.numerator}/${reduced.denominator}"
    }

    /** The rational written for normalized text: "1/250" as 1/250, "2.8" as 28/10, "30" as 30/1. */
    fun toRational(normalized: String): Rational {
        fractionPattern.matchEntire(normalized.trim())?.let { return Rational(it.groupValues[1].toLong(), it.groupValues[2].toLong()) }
        val decimal = BigDecimal(normalized.trim()).stripTrailingZeros()
        return if (decimal.scale() <= 0) {
            Rational(decimal.toBigIntegerExact().toLong(), 1)
        } else {
            Rational(decimal.unscaledValue().toLong(), BigInteger.TEN.pow(decimal.scale()).toLong())
        }
    }

    /** Reads "2026-06-14 17:42:07", "2026:06:14 17:42", "2026-06-14T17:42:07" and plain dates (midnight). */
    fun parseDateTime(input: String): LocalDateTime? {
        val match = dateTimePattern.matchEntire(input.trim()) ?: return null
        val g = match.groupValues
        return try {
            LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].ifEmpty { "0" }.toInt(), g[5].ifEmpty { "0" }.toInt(), g[6].ifEmpty { "0" }.toInt())
                .takeIf { it.year >= 1 }
        } catch (_: DateTimeException) {
            null
        }
    }

    fun formatDateTime(local: LocalDateTime): String =
        "%04d-%02d-%02d %02d:%02d:%02d".format(local.year, local.monthValue, local.dayOfMonth, local.hour, local.minute, local.second)

    /**
     * EXIF UserComment bytes: the 8-byte character code then the text, "ASCII" when possible and
     * "UNICODE" (UTF-16, little-endian as metadata-extractor and most writers expect) otherwise.
     */
    fun userComment(text: String): ByteArray =
        if (text.all { it.code < 0x80 }) {
            "ASCII\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + text.toByteArray(Charsets.US_ASCII)
        } else {
            "UNICODE\u0000".toByteArray(Charsets.ISO_8859_1) + text.toByteArray(Charsets.UTF_16LE)
        }

    private const val MAX_DECIMALS = 6
}
