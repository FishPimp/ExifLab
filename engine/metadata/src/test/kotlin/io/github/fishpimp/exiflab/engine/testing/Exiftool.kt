package io.github.fishpimp.exiflab.engine.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assumptions
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * exiftool is the reference for every engine test. The command comes from the EXIFTOOL environment variable
 * (e.g. `perl /path/to/exiftool`). Tests call [requireExiftool]; it skips locally when exiftool is missing and
 * fails on CI (CI=true).
 */
object Exiftool {
    private val command: List<String> = (System.getenv("EXIFTOOL") ?: "exiftool").split(" ").filter { it.isNotBlank() }

    val available: Boolean by lazy {
        runCatching { run(listOf("-ver")).trim().isNotEmpty() }.getOrDefault(false)
    }

    fun requireExiftool() {
        if (!available) {
            if (System.getenv("CI") == "true") error("exiftool not found on CI (EXIFTOOL=${System.getenv("EXIFTOOL")})")
            Assumptions.assumeTrue(false, "exiftool not available; set EXIFTOOL")
        }
    }

    /** All tags as "Family0:Family1:Name" -> value (numeric -n, duplicates -a, unknown -u, ImageDataHash SHA-256). */
    fun tags(file: File): Map<String, String> {
        val out = run(
            listOf("-j", "-G0:1", "-a", "-s", "-n", "-u", "-api", "ImageHashType=SHA256", "-all", "-ImageDataHash", file.absolutePath),
        )
        val obj = (Json.parseToJsonElement(out) as JsonArray).first() as JsonObject
        return obj.filterKeys { it != "SourceFile" }.mapValues { it.value.flatten() }
    }

    /** exiftool -validate warnings for [file]. */
    fun warnings(file: File): List<String> = tags(file).filterKeys { it.startsWith("ExifTool:") && (it.endsWith("Warning") || it.endsWith("Error")) }.values.toList()

    fun imageDataHash(file: File): String? = tags(file).entries.firstOrNull { it.key.endsWith(":ImageDataHash") }?.value

    private fun JsonElement.flatten(): String = when (this) {
        is JsonPrimitive -> content
        is JsonArray -> joinToString(", ") { it.flatten() }
        is JsonObject -> entries.joinToString(", ") { "${it.key}=${it.value.flatten()}" }
    }

    fun run(args: List<String>): String {
        val p = ProcessBuilder(command + args).redirectErrorStream(false).start()
        val stdout = p.inputStream.bufferedReader().readText()
        p.errorStream.bufferedReader().readText()
        check(p.waitFor(120, TimeUnit.SECONDS)) { "exiftool timed out" }
        return stdout
    }
}

/** Tags that legitimately change on any rewrite (offsets, sizes, file-system info, derived values). */
fun isVolatileTag(key: String): Boolean {
    val parts = key.split(':')
    val g0 = parts[0]
    val g1 = parts.getOrElse(1) { "" }
    val name = parts.last()
    if (g0 == "Composite" || g0 == "ExifTool") return true
    if (g0 == "File" && (g1 == "System" || name in setOf("FileSize", "CurrentIPTCDigest"))) return true
    if (name in setOf("ExifOffset", "GPSInfo", "InteropOffset", "SubIFD", "SubIFDs", "ImageDataHash", "MediaDataSize", "MediaData", "MediaDataOffset")) return true
    if (name.endsWith("Offset") || name.endsWith("Offsets") || name.endsWith("Start")) return true
    return false
}

/** Test corpus files (see corpus/README.md). */
object Corpus {
    val dir: File by lazy {
        val url = Corpus::class.java.classLoader.getResource("corpus/README.md") ?: error("corpus not on classpath")
        File(url.toURI()).parentFile
    }

    fun file(name: String): File = File(dir, name).also { require(it.exists()) { "missing corpus file $name" } }

    fun all(): List<File> = dir.listFiles { f -> f.isFile && f.extension != "md" }!!.sortedBy { it.name }

    /** Copy into a temp dir so tests never modify the corpus. */
    fun copy(name: String, tempDir: File): File = file(name).copyTo(File(tempDir, name), overwrite = true)
}
