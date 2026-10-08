package io.github.fishpimp.exiflab.engine.plan

import io.github.fishpimp.exiflab.model.ExifIfd
import io.github.fishpimp.exiflab.model.Rational
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A tag in one of the standard IFDs. */
internal data class IfdTag(val ifd: ExifIfd, val tag: Int)

/** TIFF field types (TIFF 6.0 + EXIF). */
internal object TiffType {
    const val BYTE = 1
    const val ASCII = 2
    const val SHORT = 3
    const val LONG = 4
    const val RATIONAL = 5
    const val SBYTE = 6
    const val UNDEFINED = 7
    const val SSHORT = 8
    const val SLONG = 9
    const val SRATIONAL = 10
    const val FLOAT = 11
    const val DOUBLE = 12
    const val IFD = 13

    fun size(type: Int): Int = when (type) {
        BYTE, ASCII, SBYTE, UNDEFINED -> 1
        SHORT, SSHORT -> 2
        LONG, SLONG, FLOAT, IFD -> 4
        RATIONAL, SRATIONAL, DOUBLE -> 8
        else -> 1
    }
}

/** A typed TIFF value ready to be encoded. */
internal sealed interface ExifValue {
    val type: Int
    val count: Int
    fun encode(order: ByteOrder): ByteArray

    /** ASCII with a terminating NUL (added on encode). Non-ASCII characters are rejected by the planner. */
    data class Ascii(val text: String) : ExifValue {
        override val type: Int get() = TiffType.ASCII
        override val count: Int get() = text.toByteArray(Charsets.ISO_8859_1).size + 1
        override fun encode(order: ByteOrder): ByteArray = text.toByteArray(Charsets.ISO_8859_1) + 0
    }

    /** BYTE, SBYTE or UNDEFINED. */
    class Bytes(override val type: Int, val bytes: ByteArray) : ExifValue {
        override val count: Int get() = bytes.size
        override fun encode(order: ByteOrder): ByteArray = bytes.copyOf()
        override fun equals(other: Any?): Boolean = other is Bytes && other.type == type && other.bytes.contentEquals(bytes)
        override fun hashCode(): Int = type * 31 + bytes.contentHashCode()
        override fun toString(): String = "Bytes(type=$type, ${bytes.size} bytes)"
    }

    /** SHORT, SSHORT, LONG or SLONG. */
    data class Integers(override val type: Int, val values: List<Long>) : ExifValue {
        override val count: Int get() = values.size
        override fun encode(order: ByteOrder): ByteArray {
            val b = ByteBuffer.allocate(values.size * TiffType.size(type)).order(order)
            for (v in values) if (TiffType.size(type) == 2) b.putShort(v.toInt().toShort()) else b.putInt(v.toInt())
            return b.array()
        }
    }

    /** RATIONAL or SRATIONAL. */
    data class Rationals(override val type: Int, val values: List<Rational>) : ExifValue {
        override val count: Int get() = values.size
        override fun encode(order: ByteOrder): ByteArray {
            val b = ByteBuffer.allocate(values.size * 8).order(order)
            for (r in values) b.putInt(r.numerator.toInt()).putInt(r.denominator.toInt())
            return b.array()
        }
    }

    /** FLOAT or DOUBLE. */
    data class Floats(override val type: Int, val values: List<Double>) : ExifValue {
        override val count: Int get() = values.size
        override fun encode(order: ByteOrder): ByteArray {
            val b = ByteBuffer.allocate(values.size * TiffType.size(type)).order(order)
            for (v in values) if (type == TiffType.FLOAT) b.putFloat(v.toFloat()) else b.putDouble(v)
            return b.array()
        }
    }
}

/** Changes to a TIFF/EXIF structure. Applied by the append-only updater. */
internal data class TiffEdits(
    val set: Map<IfdTag, ExifValue> = emptyMap(),
    val remove: Set<IfdTag> = emptySet(),
    /** Whole IFDs to drop: [ExifIfd.GPS], [ExifIfd.INTEROP], [ExifIfd.IFD1] (thumbnail incl. its image data). */
    val removeIfds: Set<ExifIfd> = emptySet(),
    val removeMakerNote: Boolean = false,
    /**
     * When non-null, every IFD0/ExifIFD tag not in this set is removed (structural tags such as strip/tile offsets
     * and IFD pointers are always kept). Used by the "everything" strip.
     */
    val keepOnly: Set<IfdTag>? = null,
) {
    fun isEmpty(): Boolean = set.isEmpty() && remove.isEmpty() && removeIfds.isEmpty() && !removeMakerNote && keepOnly == null
}

internal enum class XmpArrayType { BAG, SEQ, ALT, LANG_ALT }

internal sealed interface XmpEdit {
    /** Simple property (or a fully-qualified path such as `exif:Flash/exif:Fired`). */
    data class SetSimple(val namespace: String, val path: String, val value: String) : XmpEdit
    data class SetArray(val namespace: String, val path: String, val values: List<String>, val arrayType: XmpArrayType) : XmpEdit
    data class Remove(val namespace: String, val path: String) : XmpEdit
    data class RemoveNamespace(val namespace: String) : XmpEdit
}

internal data class XmpEdits(
    val edits: List<XmpEdit> = emptyList(),
    val removeAll: Boolean = false,
    /** Create an XMP packet if none exists (otherwise edits to a missing packet are skipped). */
    val createIfMissing: Boolean = false,
) {
    fun isEmpty(): Boolean = edits.isEmpty() && !removeAll
}

/** IPTC-IIM record 2 edits, keyed by dataset number. Repeatable datasets (keywords) take several values. */
internal data class IptcEdits(
    val set: Map<Int, List<String>> = emptyMap(),
    val remove: Set<Int> = emptySet(),
    val removeAll: Boolean = false,
) {
    fun isEmpty(): Boolean = set.isEmpty() && remove.isEmpty() && !removeAll
}

internal data class LowLevelEdits(
    val exif: TiffEdits = TiffEdits(),
    val xmp: XmpEdits = XmpEdits(),
    val iptc: IptcEdits = IptcEdits(),
    /** Drop JPEG COM segments / PNG text comments. */
    val dropComments: Boolean = false,
    /** "Everything" strip: drop every metadata block that is not needed to render the image. */
    val stripEverything: Boolean = false,
) {
    fun isEmpty(): Boolean = exif.isEmpty() && xmp.isEmpty() && iptc.isEmpty() && !dropComments && !stripEverything
}
