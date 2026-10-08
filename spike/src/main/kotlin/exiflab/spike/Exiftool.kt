package exiflab.spike

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around the exiftool CLI, used as the external reference ("oracle")
 * for every read and write result in the spike.
 *
 * The command is taken from the EXIFTOOL environment variable (for example
 * `perl /path/to/exiftool`) and defaults to `exiftool` on the PATH.
 */
object Exiftool {
    private val command: List<String> =
        (System.getenv("EXIFTOOL") ?: "exiftool").split(" ").filter { it.isNotBlank() }

    val version: String by lazy { run(listOf("-ver")).trim() }

    /** All tags as "Family0:Family1:Name" -> value string, numeric (-n), duplicates kept (-a), unknown tags included (-u). */
    fun tags(file: File): Map<String, String> {
        val out = run(
            listOf(
                "-j", "-G0:1", "-a", "-s", "-n", "-u",
                "-api", "ImageHashType=SHA256", "-all", "-ImageDataHash",
                file.absolutePath,
            ),
        )
        val root = Json.parseToJsonElement(out) as JsonArray
        val obj = root.first() as JsonObject
        val result = LinkedHashMap<String, String>()
        for ((k, v) in obj) {
            if (k == "SourceFile") continue
            result[k] = v.flatten()
        }
        return result
    }

    private fun JsonElement.flatten(): String = when (this) {
        is JsonPrimitive -> content
        is JsonArray -> joinToString(", ") { it.flatten() }
        is JsonObject -> entries.joinToString(", ") { "${it.key}=${it.value.flatten()}" }
    }

    private fun run(args: List<String>): String {
        val p = ProcessBuilder(command + args).redirectErrorStream(false).start()
        val stdout = p.inputStream.bufferedReader().readText()
        p.errorStream.bufferedReader().readText()
        p.waitFor(120, TimeUnit.SECONDS)
        return stdout
    }
}

/** Group a tag map by exiftool family-0 group (EXIF, MakerNotes, XMP, IPTC, ICC_Profile, ...). */
fun Map<String, String>.countByFamily0(): Map<String, Int> =
    keys.groupingBy { it.substringBefore(':') }.eachCount()
