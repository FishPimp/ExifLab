package exiflab.spike

import com.adobe.internal.xmp.XMPConst
import com.adobe.internal.xmp.XMPMetaFactory
import com.adobe.internal.xmp.options.PropertyOptions
import com.adobe.internal.xmp.options.SerializeOptions
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.xmp.XmpDirectory
import java.io.File
import kotlin.math.abs

/**
 * Scenario 1 - "Share clean, GPS only": remove the GPS IFD with the append-only engine and prove
 * that (a) no GPS tag is readable, (b) nothing else changed, (c) the original latitude bytes no
 * longer exist anywhere in the file.
 */
internal fun stripGpsReport(files: List<File>, baseline: Map<File, Map<String, String>>, outDir: File): String {
    val sb = StringBuilder()
    sb.appendLine("## Scenario: strip GPS (append-only engine)")
    sb.appendLine()
    sb.appendLine("| File | Result | GPS tags after | pixels | lost (non-GPS) | changed | original GPSLatitude bytes still in file |")
    sb.appendLine("|---|---|---|---|---|---|---|")
    for (f in files) {
        val before = baseline.getValue(f)
        if (before.keys.none { it.startsWith("EXIF:GPS:GPSLatitude") }) continue
        val kind = kindOf(before)
        val c = container(kind) ?: continue
        try {
            val bytes = f.readBytes()
            val tiff = c.readTiff(bytes) ?: continue
            val u = TiffAppendUpdater(tiff)
            val latBytes = u.gpsValueBytes(0x0002)!!
            val outBytes = c.writeTiff(bytes, u.apply(TiffAppendUpdater.Edits(gps = emptyList())))
            val out = File(outDir, "strip-gps__${f.name}").apply { writeBytes(outBytes) }
            val after = Exiftool.tags(out)
            val gpsAfter = after.keys.count { it.contains(":GPS:") || it.endsWith(":GPSPosition") || it.endsWith(":GPSLatitude") }
            val hash = { m: Map<String, String> -> m.entries.firstOrNull { it.key.endsWith(":ImageDataHash") }?.value }
            val pixels = hash(before) != null && hash(before) == hash(after)
            val lost = before.keys.filter { !isVolatile(it) && it !in after }
            val changed = before.keys.filter { !isVolatile(it) && it in after && after[it] != before[it] }
            val residue = outBytes.indexOf(latBytes) >= 0
            val ok = gpsAfter == 0 && pixels && lost.isEmpty() && changed.isEmpty() && !residue
            sb.appendLine("| ${f.name} | ${if (ok) "PASS" else "FAIL"} | $gpsAfter | ${yn(pixels)} | ${lost.size} | ${changed.size} | ${if (residue) "YES" else "no"} |")
        } catch (t: Throwable) {
            sb.appendLine("| ${f.name} | refused/failed: ${t.javaClass.simpleName}: ${t.message?.take(80)} | | | | | |")
        }
    }
    return sb.toString()
}

private fun ByteArray.indexOf(needle: ByteArray): Int {
    outer@ for (i in 0..size - needle.size) {
        for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}

/**
 * Scenario 2 - RAW edits go to an XMP sidecar written with Adobe XMP Core.
 * exiftool reads the sidecar back; the RAW file itself is never opened for writing.
 */
internal fun sidecarReport(files: List<File>, baseline: Map<File, Map<String, String>>, outDir: File): String {
    val sb = StringBuilder()
    sb.appendLine("## Scenario: XMP sidecar for RAW (Adobe XMP Core)")
    sb.appendLine()
    sb.appendLine("| RAW file | sidecar | values read back by exiftool |")
    sb.appendLine("|---|---|---|")
    val rawTypes = setOf("CR2", "CR3", "NEF", "RAF", "RW2", "ARW", "ORF", "PEF", "SRW", "DNG")
    for (f in files) {
        val type = baseline.getValue(f).entries.firstOrNull { it.key.endsWith(":FileType") }?.value ?: continue
        if (type !in rawTypes) continue
        val meta = XMPMetaFactory.create()
        meta.appendArrayItem(XMPConst.NS_DC, "creator", PropertyOptions().setArrayOrdered(true), ARTIST, null)
        meta.setProperty(XMPConst.NS_EXIF, "DateTimeOriginal", "2001-02-03T04:05:06")
        meta.setProperty(XMPConst.NS_EXIF, "GPSVersionID", "2.3.0.0")
        meta.setProperty(XMPConst.NS_EXIF, "GPSLatitude", xmpCoord(LAT, 'N', 'S'))
        meta.setProperty(XMPConst.NS_EXIF, "GPSLongitude", xmpCoord(LON, 'E', 'W'))
        val xml = XMPMetaFactory.serializeToBuffer(meta, SerializeOptions().setOmitPacketWrapper(false).setPadding(2048))
        val sidecar = File(outDir, f.nameWithoutExtension + ".xmp").apply { writeBytes(xml) }
        val t = Exiftool.tags(sidecar)
        val lat = t.entries.firstOrNull { it.key.endsWith(":GPSLatitude") }?.value?.toDoubleOrNull()
        val ok = t["XMP:XMP-dc:Creator"] == ARTIST &&
            t["XMP:XMP-exif:DateTimeOriginal"]?.startsWith("2001:02:03 04:05:06") == true &&
            lat != null && abs(lat - LAT) < 1e-4
        sb.appendLine("| ${f.name} | ${sidecar.name} (${sidecar.length()} B) | ${if (ok) "PASS" else "FAIL: $t"} |")
    }
    return sb.toString()
}

private fun xmpCoord(v: Double, pos: Char, neg: Char): String {
    val a = abs(v); val d = a.toInt(); val m = (a - d) * 60
    return "%d,%.6f%c".format(java.util.Locale.ROOT, d, m, if (v >= 0) pos else neg)
}

/** Scenario 3 - every embedded XMP packet in the corpus parses with XMP Core (via metadata-extractor). */
internal fun xmpParseReport(files: List<File>): String {
    val sb = StringBuilder()
    sb.appendLine("## Scenario: XMP Core parse + re-serialize of embedded packets")
    sb.appendLine()
    sb.appendLine("| File | properties | re-serialized | notes |")
    sb.appendLine("|---|---|---|---|")
    for (f in files) {
        val dirs = try { ImageMetadataReader.readMetadata(f).getDirectoriesOfType(XmpDirectory::class.java).toList() } catch (_: Throwable) { emptyList() }
        if (dirs.isEmpty()) continue
        for (d in dirs) {
            val m = d.xmpMeta
            val reser = try { XMPMetaFactory.serializeToBuffer(m, SerializeOptions().setOmitPacketWrapper(true)).size.toString() + " B" } catch (t: Throwable) { "FAILED ${t.javaClass.simpleName}" }
            sb.appendLine("| ${f.name} | ${d.xmpProperties.size} | $reser | ${if (d.hasErrors()) d.errors.joinToString("; ").take(80) else ""} |")
        }
    }
    return sb.toString()
}
