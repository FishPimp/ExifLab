package io.github.fishpimp.exiflab.ui.format

/**
 * How a camera is named in the viewer header: the model as the title, with the make above it
 * unless the model already starts with it ("Canon" + "Canon EOS R5" is just "Canon EOS R5").
 *
 * @property make shown above the title, or null.
 * @property title the model, or the make when there is no model.
 */
data class CameraName(val make: String?, val title: String) {
    companion object {
        /** Null when neither make nor model is known. */
        fun of(make: String?, model: String?): CameraName? {
            val cleanMake = make?.trim()?.takeIf { it.isNotEmpty() }
            val cleanModel = model?.trim()?.takeIf { it.isNotEmpty() }
            return when {
                cleanModel == null && cleanMake == null -> null
                cleanModel == null -> CameraName(null, cleanMake!!)
                cleanMake == null -> CameraName(null, cleanModel)
                // "NIKON CORPORATION" + "NIKON Z 6": the make's first word is enough to repeat the brand.
                cleanModel.startsWith(cleanMake.substringBefore(' '), ignoreCase = true) -> CameraName(null, cleanModel)
                else -> CameraName(cleanMake, cleanModel)
            }
        }
    }
}
