package io.github.fishpimp.exiflab.engine.plan

import io.github.fishpimp.exiflab.engine.ParsedValue
import io.github.fishpimp.exiflab.model.ValueKind

/** Parses and validates editor input for a [ValueKind]. Owned by the XMP/planner agent. */
internal object ValueParser {
    fun parse(kind: ValueKind, input: String): ParsedValue = TODO("ValueParser.parse")
}
