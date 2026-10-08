package io.github.fishpimp.exiflab.engine.container

import io.github.fishpimp.exiflab.engine.io.SeekableSource
import java.io.File

internal object HeifCodec : ContainerCodec {
    override fun scan(source: SeekableSource): ContainerLayout = TODO("HeifCodec.scan")
    override fun readXmp(source: SeekableSource, layout: ContainerLayout): String? = TODO("HeifCodec.readXmp")
    override fun write(source: SeekableSource, layout: ContainerLayout, changes: ContainerChanges, output: File): Unit =
        TODO("HeifCodec.write")
}
