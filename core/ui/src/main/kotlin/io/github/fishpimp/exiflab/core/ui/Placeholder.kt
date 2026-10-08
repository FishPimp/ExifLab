package io.github.fishpimp.exiflab.core.ui

import io.github.fishpimp.exiflab.model.ImageFormat

/** Shared UI helpers live here. */
public fun ImageFormat.isEditableInFile(): Boolean = writeSupport == io.github.fishpimp.exiflab.model.WriteSupport.IN_FILE
