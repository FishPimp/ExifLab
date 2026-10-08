package io.github.fishpimp.exiflab.engine.write

import io.github.fishpimp.exiflab.engine.EditPlan
import io.github.fishpimp.exiflab.engine.WriteReport
import io.github.fishpimp.exiflab.engine.io.SeekableSource
import java.io.File

/** Executes an [EditPlan] by routing low-level edits to the TIFF updater, XMP/IPTC codecs and container codecs. */
internal object MetadataWriter {
    fun write(source: SeekableSource, plan: EditPlan, output: File): WriteReport = TODO("MetadataWriter.write")

    fun writeSidecar(existing: ByteArray?, plan: EditPlan): ByteArray = TODO("MetadataWriter.writeSidecar")
}
