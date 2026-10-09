package io.github.fishpimp.exiflab.metadata

import java.io.ByteArrayInputStream
import java.io.InputStream

class ByteArrayImageSource(private val bytes: ByteArray, override val fileName: String? = null) : ImageSource {
    override val length: Long get() = bytes.size.toLong()
    override fun open(): InputStream = ByteArrayInputStream(bytes)
}
