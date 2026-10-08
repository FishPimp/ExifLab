package io.github.fishpimp.exiflab.engine.container

import io.github.fishpimp.exiflab.engine.io.SeekableSource
import java.io.File

internal object WebpCodec : ContainerCodec {
    override fun scan(source: SeekableSource): ContainerLayout = TODO("WebpCodec.scan")
    override fun readXmp(source: SeekableSource, layout: ContainerLayout): String? = TODO("WebpCodec.readXmp")
    override fun write(source: SeekableSource, layout: ContainerLayout, changes: ContainerChanges, output: File): Unit =
        TODO("WebpCodec.write")
}
