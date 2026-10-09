package io.github.fishpimp.exiflab.metadata.summary

import com.drew.metadata.Directory
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.makernotes.CanonMakernoteDirectory
import com.drew.metadata.exif.makernotes.NikonType2MakernoteDirectory
import com.drew.metadata.exif.makernotes.OlympusEquipmentMakernoteDirectory
import com.drew.metadata.exif.makernotes.PanasonicMakernoteDirectory
import com.drew.metadata.exif.makernotes.SamsungType2MakernoteDirectory
import com.drew.metadata.exif.makernotes.SonyType1MakernoteDirectory
import com.drew.metadata.icc.IccDirectory
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.mapping.ValueText
import io.github.fishpimp.exiflab.metadata.model.PhotoSummary
import io.github.fishpimp.exiflab.metadata.model.Rational
import kotlin.math.pow
import kotlin.math.roundToLong

/** Builds the viewer header facts from Exif, falling back to MakerNotes and XMP. */
internal class SummaryBuilder(private val lookup: MetadataLookup, private val format: ImageFormat) {
    fun build(capture: CaptureTime?): PhotoSummary {
        val size = ImageDimensions.of(lookup, format)
        return PhotoSummary(
            make = exifText(MAKE) ?: lookup.xmp("tiff:Make"),
            model = exifText(MODEL) ?: lookup.xmp("tiff:Model"),
            lens = lens(),
            fNumber = fNumber(),
            exposureTime = exposureTime(),
            iso = iso(),
            focalLengthMm = exifDouble(FOCAL_LENGTH)?.takeIf { it > 0 } ?: xmpNumber("exif:FocalLength")?.takeIf { it > 0 },
            focalLength35mm = exifInt(FOCAL_LENGTH_35MM)?.takeIf { it > 0 }
                ?: lookup.xmp("exif:FocalLengthIn35mmFilm")?.toIntOrNull()?.takeIf { it > 0 },
            exposureBiasEv = exifDouble(EXPOSURE_BIAS) ?: xmpNumber("exif:ExposureBiasValue"),
            flashFired = exifInt(FLASH)?.let { (it and 1) == 1 } ?: lookup.xmp("exif:Flash/exif:Fired")?.lowercase()?.toBooleanStrictOrNull(),
            width = size?.first,
            height = size?.second,
            orientation = (lookup.all<ExifIFD0Directory>().firstNotNullOfOrNull { it.int(ORIENTATION) }
                ?: lookup.xmp("tiff:Orientation")?.toIntOrNull())?.takeIf { it in 1..8 },
            capturedAt = capture?.local,
            utcOffset = capture?.offset,
            utcOffsetSource = capture?.offsetSource,
            software = exifText(SOFTWARE) ?: lookup.xmp("xmp:CreatorTool"),
            colorProfile = lookup.all<IccDirectory>().firstNotNullOfOrNull { it.descriptionOf(ICC_DESCRIPTION) }
                ?.let(::cleanIccDescription)?.takeIf { it.isNotBlank() },
        )
    }

    /** LensModel, then MakerNote lens names, then XMP aux:Lens / exifEX:LensModel. */
    private fun lens(): String? =
        exifText(LENS_MODEL)?.takeIf(::isMeaningfulLens)
            ?: makerNoteLens()
            ?: listOf("aux:Lens", "exifEX:LensModel").firstNotNullOfOrNull { path -> lookup.xmp(path)?.takeIf(::isMeaningfulLens) }

    private fun makerNoteLens(): String? = listOfNotNull(
        lookup.all<CanonMakernoteDirectory>().firstNotNullOfOrNull { it.text(CanonMakernoteDirectory.TAG_LENS_MODEL) },
        lookup.all<OlympusEquipmentMakernoteDirectory>().firstNotNullOfOrNull { it.text(OlympusEquipmentMakernoteDirectory.TAG_LENS_MODEL) },
        lookup.all<PanasonicMakernoteDirectory>().firstNotNullOfOrNull { it.text(PanasonicMakernoteDirectory.TAG_LENS_TYPE) },
        lookup.all<SonyType1MakernoteDirectory>().firstNotNullOfOrNull { it.descriptionOf(SonyType1MakernoteDirectory.TAG_LENS_ID) },
        lookup.all<SamsungType2MakernoteDirectory>().firstNotNullOfOrNull { it.descriptionOf(SamsungType2MakernoteDirectory.TagLensType) },
        lookup.all<NikonType2MakernoteDirectory>().firstNotNullOfOrNull { it.descriptionOf(NikonType2MakernoteDirectory.TAG_LENS) },
    ).firstOrNull(::isMeaningfulLens)

    private fun isMeaningfulLens(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.any { it.isLetterOrDigit() } && trimmed != "0" && !trimmed.startsWith("Unknown", ignoreCase = true)
    }

    /** FNumber, else ApertureValue (APEX: f = 2^(Av/2)), else XMP. */
    private fun fNumber(): Double? =
        exifDouble(F_NUMBER)?.takeIf { it > 0 }
            ?: exifDouble(APERTURE_VALUE)?.let { roundTo(2.0.pow(it / 2), 1) }?.takeIf { it > 0 }
            ?: xmpNumber("exif:FNumber")?.takeIf { it > 0 }

    /** ExposureTime as stored, else ShutterSpeedValue (APEX: t = 2^-Tv) as 1/n or n/1, else XMP. */
    private fun exposureTime(): Rational? {
        lookup.exif(EXPOSURE_TIME)?.rational(EXPOSURE_TIME)
            ?.takeIf { it.numerator > 0 && it.denominator > 0 }
            ?.let { return Rational(it.numerator, it.denominator) }
        lookup.exif(SHUTTER_SPEED)?.double(SHUTTER_SPEED)?.let { tv ->
            val seconds = 2.0.pow(-tv)
            if (seconds.isFinite() && seconds > 0) {
                return if (seconds < 1) Rational(1, (1 / seconds).roundToLong()) else Rational(seconds.roundToLong(), 1)
            }
        }
        return lookup.xmp("exif:ExposureTime")?.let(::parseRational)
    }

    /** ISOSpeedRatings (65535 means "too large to fit"), else ISOSpeed, else XMP. */
    private fun iso(): Int? {
        val ratings = exifInt(ISO_SPEED_RATINGS)?.takeIf { it in 1 until 65535 }
        return ratings
            ?: exifInt(ISO_SPEED)?.takeIf { it > 0 }
            ?: listOf("exif:ISOSpeedRatings[1]", "exifEX:PhotographicSensitivity", "exifEX:ISOSpeed")
                .firstNotNullOfOrNull { lookup.xmp(it)?.toIntOrNull()?.takeIf { iso -> iso > 0 } }
            ?: exifInt(ISO_SPEED_RATINGS)?.takeIf { it > 0 }
    }

    private fun exifText(tag: Int): String? = lookup.exif(tag)?.text(tag)

    private fun exifInt(tag: Int): Int? = lookup.exif(tag)?.int(tag)

    private fun exifDouble(tag: Int): Double? = lookup.exif(tag)?.double(tag)

    private fun xmpNumber(path: String): Double? = lookup.xmp(path)?.let(::parseRational)?.value?.takeIf { it.isFinite() }

    private fun parseRational(text: String): Rational? {
        val parts = text.trim().split('/')
        val numerator = parts[0].trim().toLongOrNull()
        val denominator = if (parts.size == 2) parts[1].trim().toLongOrNull() else 1L
        if (parts.size > 2 || numerator == null || denominator == null || denominator == 0L) {
            // Decimal XMP values such as "2.8".
            val decimal = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
            return Rational((decimal * 1000).roundToLong(), 1000)
        }
        return Rational(numerator, denominator)
    }

    private fun Directory.descriptionOf(tag: Int): String? =
        if (!containsTag(tag)) null else runCatching { getDescription(tag) }.getOrNull()?.let { ValueText.sanitize(it).trim().ifEmpty { null } }

    private fun roundTo(value: Double, decimals: Int): Double {
        val factor = 10.0.pow(decimals)
        return (value * factor).roundToLong() / factor
    }

    private companion object {
        const val MAKE = 0x010F
        const val MODEL = 0x0110
        const val ORIENTATION = 0x0112
        const val SOFTWARE = 0x0131
        const val EXPOSURE_TIME = 0x829A
        const val F_NUMBER = 0x829D
        const val ISO_SPEED_RATINGS = 0x8827
        const val ISO_SPEED = 0x8833
        const val SHUTTER_SPEED = 0x9201
        const val APERTURE_VALUE = 0x9202
        const val EXPOSURE_BIAS = 0x9204
        const val FLASH = 0x9209
        const val FOCAL_LENGTH = 0x920A
        const val FOCAL_LENGTH_35MM = 0xA405
        const val LENS_MODEL = 0xA434
        const val ICC_DESCRIPTION = IccDirectory.TAG_TAG_desc
    }
}

/** Matches metadata-extractor's rendering of a multi-localized ICC text: `1 enUS(sRGB IEC61966-2.1)`. */
private val LocalizedIccText = Regex("""^\d+\s+[A-Za-z]{4}\((.*)\)$""", RegexOption.DOT_MATCHES_ALL)

/** Reduces an ICC profile description to its plain text, without locale prefixes or NUL padding. */
internal fun cleanIccDescription(raw: String): String {
    val text = raw.replace("\\x00", "").replace("\u0000", "").trim()
    return (LocalizedIccText.matchEntire(text)?.groupValues?.get(1) ?: text).trim()
}
