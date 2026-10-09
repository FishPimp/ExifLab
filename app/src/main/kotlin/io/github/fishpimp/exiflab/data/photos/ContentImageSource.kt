package io.github.fishpimp.exiflab.data.photos

import android.content.ContentResolver
import android.net.Uri
import io.github.fishpimp.exiflab.metadata.ImageSource
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * An [ImageSource] backed by a content URI. Every [open] asks the provider for a fresh stream,
 * so the metadata engine can re-read the file as often as it needs.
 */
class ContentImageSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val fileName: String?,
    override val length: Long?,
) : ImageSource {
    override fun open(): InputStream =
        resolver.openInputStream(uri) ?: throw FileNotFoundException("Provider returned no stream for $uri")
}
