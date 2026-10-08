package exiflab.spike

import java.io.File
import kotlin.math.abs

internal val EXPECTED = setOf("EXIF:IFD0:Artist", "EXIF:ExifIFD:DateTimeOriginal")
internal val OFFSET_NAMES = setOf("ExifOffset", "GPSInfo", "InteropOffset", "SubIFD", "SubIFDs")

internal fun isVolatile(key: String): Boolean {
    val parts = key.split(':')
    val g0 = parts[0]; val g1 = parts.getOrElse(1) { "" }; val name = parts.last()
    if (g0 == "Composite" || g0 == "ExifTool") return true
    if (g0 == "File" && g1 == "System") return true
    if (g0 == "File" && name in setOf("FileSize", "ExifByteOrder", "CurrentIPTCDigest")) return true
    if (g1 == "GPS") return true
    if (name in OFFSET_NAMES || name.endsWith("Offset") || name.endsWith("Offsets") || name.endsWith("Start")) return true
    if (name == "ImageDataHash") return true
    // HEIF: exiftool reports size/content of the *last* mdat box; a relocated Exif item adds one.
    if (g0 == "QuickTime" && (name == "MediaDataSize" || name == "MediaData")) return true
    return false
}

internal class WriteOutcome(
    val ok: Boolean,
    val valuesOk: Boolean = false,
    val pixelsOk: Boolean = false,
    val lost: List<String> = emptyList(),
    val changed: List<String> = emptyList(),
    val newWarnings: List<String> = emptyList(),
    val makerBefore: Int = 0,
    val makerAfter: Int = 0,
    val sizeDelta: Long = 0,
    val error: String? = null,
)

internal fun verify(before: Map<String, String>, out: File, inSize: Long): WriteOutcome {
    val after = Exiftool.tags(out)
    val lat = after["Composite::GPSLatitude"] ?: after.entries.firstOrNull { it.key.endsWith(":GPSLatitude") }?.value
    val lon = after["Composite::GPSLongitude"] ?: after.entries.firstOrNull { it.key.endsWith(":GPSLongitude") }?.value
    val valuesOk = after["EXIF:IFD0:Artist"] == ARTIST &&
        after["EXIF:ExifIFD:DateTimeOriginal"] == DTO &&
        lat?.toDoubleOrNull()?.let { abs(it - LAT) < 1e-4 } == true &&
        lon?.toDoubleOrNull()?.let { abs(it - LON) < 1e-4 } == true
    val hashKey = { m: Map<String, String> -> m.entries.firstOrNull { it.key.endsWith(":ImageDataHash") }?.value }
    val pixelsOk = hashKey(before) != null && hashKey(before) == hashKey(after)
    val lost = before.keys.filter { !isVolatile(it) && it !in EXPECTED && it !in after }
    val changed = before.keys.filter { !isVolatile(it) && it !in EXPECTED && it in after && after[it] != before[it] }
        .map { "$it: '${before[it]!!.take(30)}' -> '${after[it]!!.take(30)}'" }
    val warnBefore = before.filterKeys { it.startsWith("ExifTool:") && (it.endsWith("Warning") || it.endsWith("Error")) }.values.toSet()
    val newWarnings = after.filterKeys { it.startsWith("ExifTool:") && (it.endsWith("Warning") || it.endsWith("Error")) }
        .values.filter { it !in warnBefore }
    return WriteOutcome(
        ok = true, valuesOk = valuesOk, pixelsOk = pixelsOk, lost = lost, changed = changed, newWarnings = newWarnings,
        makerBefore = before.keys.count { it.startsWith("MakerNotes:") },
        makerAfter = after.keys.count { it.startsWith("MakerNotes:") },
        sizeDelta = out.length() - inSize,
    )
}

internal fun WriteOutcome.verdict(): String = when {
    !ok -> "refused/failed: $error"
    valuesOk && pixelsOk && lost.isEmpty() && changed.isEmpty() && newWarnings.isEmpty() -> "PASS"
    valuesOk && pixelsOk -> "PARTIAL"
    else -> "FAIL"
}

internal fun yn(b: Boolean) = if (b) "yes" else "NO"
