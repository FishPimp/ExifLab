package io.github.fishpimp.exiflab.engine.verify

import io.github.fishpimp.exiflab.engine.EditPlan
import io.github.fishpimp.exiflab.engine.VerificationReport
import io.github.fishpimp.exiflab.engine.io.SeekableSource

/** Re-reads a written file and proves the plan was applied and nothing else changed. */
internal object Verifier {
    fun verify(original: SeekableSource, written: SeekableSource, plan: EditPlan): VerificationReport = TODO("Verifier.verify")

    fun verifySidecar(sidecar: ByteArray, plan: EditPlan): VerificationReport = TODO("Verifier.verifySidecar")
}
