package io.github.fishpimp.exiflab.data.backup

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Size and SHA-256 of some content. */
data class ContentHash(val size: Long, val sha256: String) {
    companion object {
        /** The hash of no content at all; a missing file hashes to this. */
        val Empty = ContentHash(0, Sha256.EMPTY)
    }
}

/** SHA-256 helpers. Hashes are lowercase hex. */
object Sha256 {
    /** SHA-256 of zero bytes. */
    const val EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    private const val BUFFER_BYTES = 64 * 1024

    /** Reads [input] to the end (without closing it) and returns its size and hash. */
    fun of(input: InputStream): ContentHash {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            total += read
        }
        return ContentHash(total, digest.digest().toHex())
    }

    fun of(file: File): ContentHash = file.inputStream().use(::of)

    fun of(bytes: ByteArray): ContentHash = of(bytes.inputStream())

    /** Copies [input] to [output] (closing neither) and returns the size and hash of what was copied. */
    fun copy(input: InputStream, output: OutputStream): ContentHash {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            total += read
        }
        return ContentHash(total, digest.digest().toHex())
    }

    private fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            chars[index * 2] = HEX[value ushr 4]
            chars[index * 2 + 1] = HEX[value and 0x0F]
        }
        return String(chars)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
