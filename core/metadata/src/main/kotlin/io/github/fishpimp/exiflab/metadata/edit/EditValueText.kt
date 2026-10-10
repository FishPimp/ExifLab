package io.github.fishpimp.exiflab.metadata.edit

import com.drew.lang.Rational as DrewRational
import com.drew.metadata.Directory
import com.drew.metadata.StringValue
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifInteropDirectory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.ExifThumbnailDirectory
import com.drew.metadata.exif.GpsDirectory
import com.drew.metadata.iptc.IptcDirectory
import io.github.fishpimp.exiflab.metadata.mapping.ValueText
import io.github.fishpimp.exiflab.metadata.write.ExifIfd
import io.github.fishpimp.exiflab.metadata.write.ExifValue

/**
 * Text forms of planned EXIF values that match what the viewer shows for values read from a file:
 * [raw] mirrors the report's raw value (so unchanged values compare equal), [describe] runs the
 * same metadata-extractor descriptors the reader uses ("1/250 sec", "f/2.8", "Rotate 90 CW").
 */
internal object ExifText {
    fun raw(value: ExifValue): String = when (value) {
        is ExifValue.Ascii -> ValueText.sanitize(value.text)
        is ExifValue.Bytes -> value.values.joinToString(" ")
        is ExifValue.Shorts -> value.values.joinToString(" ")
        is ExifValue.Longs -> value.values.joinToString(" ")
        is ExifValue.SignedLongs -> value.values.joinToString(" ")
        is ExifValue.Rationals -> value.values.joinToString(" ") { "${it.numerator}/${it.denominator}" }
        is ExifValue.SignedRationals -> value.values.joinToString(" ") { "${it.numerator}/${it.denominator}" }
        is ExifValue.Undefined -> ValueText.hex(value.bytes)
    }

    /** True when [raw] is a shortened form that cannot tell two values apart. */
    fun isTruncated(raw: String): Boolean = raw.endsWith(" bytes)") || raw.endsWith(" values)") || raw.endsWith(" characters)")

    /** English tag name as the viewer shows it, e.g. "Date/Time Original" or "GPS Latitude". */
    fun tagName(ifd: ExifIfd, tag: Int): String = directoryFor(ifd).getTagName(tag)

    /** Human-readable values for [values] of one IFD; the whole IFD is described together so related tags resolve. */
    fun describe(ifd: ExifIfd, values: Map<Int, ExifValue>): Map<Int, String> {
        val directory = directoryFor(ifd)
        for ((tag, value) in values) directory.setObject(tag, objectOf(value))
        return values.mapValues { (tag, value) ->
            runCatching { directory.getDescription(tag) }.getOrNull()?.let(ValueText::sanitize)?.takeIf { it.isNotBlank() } ?: fallback(value)
        }
    }

    private fun fallback(value: ExifValue): String = when (value) {
        is ExifValue.Ascii -> ValueText.sanitize(value.text)
        is ExifValue.Undefined -> {
            val text = String(value.bytes, Charsets.ISO_8859_1).trimEnd('\u0000')
            if (text.isNotEmpty() && text.all { it.code in 0x20..0x7E }) text else ValueText.hex(value.bytes)
        }
        else -> raw(value)
    }

    /** The value in the shape metadata-extractor's TIFF reader stores it. */
    private fun objectOf(value: ExifValue): Any = when (value) {
        is ExifValue.Ascii -> StringValue(value.text.toByteArray(Charsets.UTF_8), Charsets.UTF_8)
        is ExifValue.Bytes -> if (value.values.size == 1) value.values[0] else ShortArray(value.values.size) { value.values[it].toShort() }
        is ExifValue.Shorts -> if (value.values.size == 1) value.values[0] else value.values.toIntArray()
        is ExifValue.Longs -> if (value.values.size == 1) value.values[0] else value.values.toLongArray()
        is ExifValue.SignedLongs -> if (value.values.size == 1) value.values[0] else value.values.toIntArray()
        is ExifValue.Rationals -> rationals(value.values)
        is ExifValue.SignedRationals -> rationals(value.values)
        is ExifValue.Undefined -> value.bytes.copyOf()
    }

    private fun rationals(values: List<io.github.fishpimp.exiflab.metadata.model.Rational>): Any {
        val converted = values.map { DrewRational(it.numerator, it.denominator) }
        return if (converted.size == 1) converted[0] else converted.toTypedArray()
    }

    private fun directoryFor(ifd: ExifIfd): Directory = when (ifd) {
        ExifIfd.Primary -> ExifIFD0Directory()
        ExifIfd.Exif -> ExifSubIFDDirectory()
        ExifIfd.Gps -> GpsDirectory()
        ExifIfd.Interoperability -> ExifInteropDirectory()
        ExifIfd.Thumbnail -> ExifThumbnailDirectory(0)
    }
}

/** Names and descriptions of IPTC datasets as the viewer shows them. */
internal object IptcText {
    fun tagName(record: Int, dataset: Int): String = IptcDirectory().getTagName((record shl 8) or dataset)

    fun describe(record: Int, dataset: Int, values: List<String>): String {
        val tag = (record shl 8) or dataset
        val directory = IptcDirectory()
        val encoded = values.map { StringValue(it.toByteArray(Charsets.UTF_8), Charsets.UTF_8) }
        if (encoded.size == 1) directory.setStringValue(tag, encoded[0]) else directory.setStringValueArray(tag, encoded.toTypedArray())
        return runCatching { directory.getDescription(tag) }.getOrNull()?.let(ValueText::sanitize)?.takeIf { it.isNotBlank() }
            ?: values.joinToString("; ")
    }
}
