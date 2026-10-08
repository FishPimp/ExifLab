package io.github.fishpimp.exiflab.engine.plan

import io.github.fishpimp.exiflab.engine.EditPlan
import io.github.fishpimp.exiflab.engine.PlanOptions
import io.github.fishpimp.exiflab.model.EditOperation
import io.github.fishpimp.exiflab.model.MetadataDocument

/**
 * Turns user-level [EditOperation]s into [LowLevelEdits] and a [io.github.fishpimp.exiflab.model.PlannedChange]
 * diff, keeping EXIF, XMP and IPTC in sync (MWG). Operations are applied in order; later ones see the effect of
 * earlier ones. Owned by the XMP/planner agent.
 */
internal object EditPlanner {
    fun plan(document: MetadataDocument, operations: List<EditOperation>, options: PlanOptions): EditPlan = TODO("EditPlanner.plan")
}
