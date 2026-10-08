package io.github.fishpimp.exiflab.engine.container

import io.github.fishpimp.exiflab.engine.io.SeekableSource
import java.io.File

internal object JpegCodec : ContainerCodec {
    override fun scan(source: SeekableSource): ContainerLayout = TODO("JpegCodec.scan")
    override fun readXmp(source: SeekableSource, layout: ContainerLayout): String? = TODO("JpegCodec.readXmp")
    override fun write(source: SeekableSource, layout: ContainerLayout, changes: ContainerChanges, output: File): Unit =
        TODO("JpegCodec.write")
}
