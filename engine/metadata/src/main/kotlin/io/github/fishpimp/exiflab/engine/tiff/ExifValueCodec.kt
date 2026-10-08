package io.github.fishpimp.exiflab.engine.tiff

import io.github.fishpimp.exiflab.engine.plan.ExifValue

/** Decodes stored values (used by the verifier and planner tests). Owned by the TIFF agent. */
internal object ExifValueCodec {
    fun decode(buffer: TiffBuffer, structure: TiffStructure, entry: TiffEntry): ExifValue = TODO("ExifValueCodec.decode")
}
