package io.github.fishpimp.exiflab.engine.container

import io.github.fishpimp.exiflab.engine.io.SeekableSource
import java.io.File

internal object PngCodec : ContainerCodec {
    override fun scan(source: SeekableSource): ContainerLayout = TODO("PngCodec.scan")
    override fun readXmp(source: SeekableSource, layout: ContainerLayout): String? = TODO("PngCodec.readXmp")
    override fun write(source: SeekableSource, layout: ContainerLayout, changes: ContainerChanges, output: File): Unit =
        TODO("PngCodec.write")
}
