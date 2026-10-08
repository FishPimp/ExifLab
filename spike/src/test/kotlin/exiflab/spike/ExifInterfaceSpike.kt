package exiflab.spike

import android.media.ExifInterface
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.io.File

/**
 * Evaluates the platform android.media.ExifInterface (Android 16 sources, run under Robolectric).
 * androidx.exifinterface is the unbundled copy of the same implementation.
 *
 * Caveat: HEIF parsing in ExifInterface goes through MediaMetadataRetriever, which Robolectric
 * shadows with a stub, so HEIF rows here say nothing about real devices.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ExifInterfaceSpike {

    private val tagNames: List<String> = ExifInterface::class.java.fields
        .filter { it.name.startsWith("TAG_") && it.type == String::class.java }
        .map { it.get(null) as String }
        .distinct()

    @Test
    fun evaluate() {
        val samples = File(System.getProperty("spike.samples"))
        val outDir = File(System.getProperty("spike.out")).apply { mkdirs() }
        val results = File(System.getProperty("spike.results", "results")).apply { mkdirs() }
        val files = samples.listFiles { f -> f.isFile && !f.name.startsWith(".") && f.extension.lowercase() != "md" }!!.sortedBy { it.name }

        val sb = StringBuilder()
        sb.appendLine("# android.media.ExifInterface results")
        sb.appendLine()
        sb.appendLine("Platform ExifInterface from Android 16 (`android-all-instrumented:16`), executed with Robolectric 4.17 on the JVM.")
        sb.appendLine("androidx.exifinterface ships the same implementation. HEIF rows are not meaningful here (MediaMetadataRetriever is a Robolectric stub).")
        sb.appendLine("Write = set Artist, DateTimeOriginal, GPS lat/lon rationals + refs, then `saveAttributes()`; verified with exiftool ${Exiftool.version}.")
        sb.appendLine()
        sb.appendLine("| File | EXIF tags (exiftool) | attributes read (ExifInterface) | lat/long | write result | values | pixels | lost | changed | MakerNotes | new warnings |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        val details = StringBuilder()
        for (f in files) {
            val before = Exiftool.tags(f)
            val etExif = before.keys.count { it.startsWith("EXIF:") }
            val read = try {
                val e = ExifInterface(f.absolutePath)
                val n = tagNames.count { e.getAttribute(it) != null }
                val arr = FloatArray(2)
                val ll = if (e.getLatLong(arr)) "%.4f, %.4f".format(arr[0], arr[1]) else "-"
                "$n" to ll
            } catch (t: Throwable) {
                "FAILED: ${t.javaClass.simpleName}" to "-"
            }
            val outcome = try {
                val out = File(outDir, "exifinterface__${f.name}")
                f.copyTo(out, overwrite = true)
                val e = ExifInterface(out.absolutePath)
                e.setAttribute(ExifInterface.TAG_ARTIST, ARTIST)
                e.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, DTO)
                // Platform API has no setLatLong (androidx does); write the rationals directly.
                e.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "57/1,42/1,31932/1000")
                e.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                e.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "11/1,58/1,28416/1000")
                e.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
                e.saveAttributes()
                verify(before, out, f.length())
            } catch (t: Throwable) {
                WriteOutcome(ok = false, error = "${t.javaClass.simpleName}: ${t.message?.lineSequence()?.first()?.take(80)}")
            }
            sb.append("| ${f.name} | $etExif | ${read.first} | ${read.second} | ${outcome.verdict()} | ")
            if (outcome.ok) {
                sb.appendLine(
                    "${yn(outcome.valuesOk)} | ${yn(outcome.pixelsOk)} | ${outcome.lost.size} | ${outcome.changed.size} | " +
                        "${outcome.makerBefore} -> ${outcome.makerAfter} | ${outcome.newWarnings.joinToString("; ").take(70)} |",
                )
            } else {
                sb.appendLine(" |  |  |  |  |  |")
            }
            if (outcome.lost.isNotEmpty() || outcome.changed.isNotEmpty()) {
                details.appendLine("### ${f.name}")
                if (outcome.lost.isNotEmpty()) details.appendLine("* lost (${outcome.lost.size}): " + outcome.lost.take(20).joinToString(", ") { "`$it`" } + if (outcome.lost.size > 20) " ..." else "")
                if (outcome.changed.isNotEmpty()) details.appendLine("* changed: " + outcome.changed.take(8).joinToString("; ") { "`$it`" } + if (outcome.changed.size > 8) " ..." else "")
                details.appendLine()
            }
        }
        sb.appendLine()
        sb.appendLine("## Details of lost / changed tags")
        sb.appendLine()
        sb.append(details)
        File(results, "exifinterface-results.md").writeText(sb.toString())
    }
}
