package io.github.fishpimp.exiflab.engine

import io.github.fishpimp.exiflab.engine.io.SeekableSource
import io.github.fishpimp.exiflab.engine.plan.EditPlanner
import io.github.fishpimp.exiflab.engine.plan.ValueParser
import io.github.fishpimp.exiflab.engine.preview.PreviewExtractor
import io.github.fishpimp.exiflab.engine.read.DocumentReader
import io.github.fishpimp.exiflab.engine.read.FormatDetector
import io.github.fishpimp.exiflab.engine.verify.ImageDataHasher
import io.github.fishpimp.exiflab.engine.verify.Verifier
import io.github.fishpimp.exiflab.engine.write.MetadataWriter
import io.github.fishpimp.exiflab.model.EditOperation
import io.github.fishpimp.exiflab.model.ImageFormat
import io.github.fishpimp.exiflab.model.MetadataDocument
import io.github.fishpimp.exiflab.model.StripPreset
import io.github.fishpimp.exiflab.model.ValueKind
import io.github.fishpimp.exiflab.model.WriteDestination
import io.github.fishpimp.exiflab.model.WriteSupport
import java.io.File

/**
 * Public entry point of the metadata engine. All functions are blocking and must be called off the main thread.
 * The engine is stateless and thread-safe; sources are not shared between threads.
 */
public object MetadataEngine {
    public fun detectFormat(source: SeekableSource): ImageFormat = FormatDetector.detect(source)

    public fun read(source: SeekableSource, options: ReadOptions = ReadOptions()): MetadataDocument =
        DocumentReader.read(source, detectFormat(source), options)

    public fun plan(
        document: MetadataDocument,
        operations: List<EditOperation>,
        options: PlanOptions = PlanOptions(),
    ): EditPlan = EditPlanner.plan(document, operations, options)

    /**
     * Writes a new version of [source] with [plan] applied into [output] (a temp file the caller owns).
     * Only valid for [WriteDestination.IN_FILE] plans.
     */
    public fun write(source: SeekableSource, plan: EditPlan, output: File): WriteReport {
        if (plan.destination != WriteDestination.IN_FILE) {
            throw EngineException(EngineException.Reason.NOT_WRITABLE_IN_FILE, "${plan.format} plans are written to a sidecar")
        }
        return MetadataWriter.write(source, plan, output)
    }

    /** New or merged XMP sidecar contents for a [WriteDestination.SIDECAR] plan. */
    public fun writeSidecar(existingSidecar: ByteArray?, plan: EditPlan): ByteArray =
        MetadataWriter.writeSidecar(existingSidecar, plan)

    public fun verify(original: SeekableSource, written: SeekableSource, plan: EditPlan): VerificationReport =
        Verifier.verify(original, written, plan)

    public fun verifySidecar(sidecar: ByteArray, plan: EditPlan): VerificationReport = Verifier.verifySidecar(sidecar, plan)

    /** Plans a share-clean strip for [document]; write it with [write]. */
    public fun planStrip(document: MetadataDocument, preset: StripPreset): EditPlan {
        if (document.format.writeSupport != WriteSupport.IN_FILE) {
            throw EngineException(EngineException.Reason.NOT_WRITABLE_IN_FILE, "Cannot strip ${document.format} files")
        }
        return plan(document, listOf(EditOperation.RemoveCategories(preset.categories)))
    }

    public fun imageDataHash(source: SeekableSource): String? = ImageDataHasher.hash(source, detectFormat(source))

    /** Largest embedded JPEG preview up to [maxBytes], or null. Used for RAW thumbnails. */
    public fun extractPreview(source: SeekableSource, maxBytes: Int = 16 * 1024 * 1024): ByteArray? =
        PreviewExtractor.extract(source, detectFormat(source), maxBytes)

    /** Parses editor input. */
    public fun parseValue(kind: ValueKind, input: String): ParsedValue = ValueParser.parse(kind, input)
}
