package io.github.fishpimp.exiflab.metadata.io

import java.io.IOException

/**
 * Wraps a failure of the underlying [io.github.fishpimp.exiflab.metadata.ImageSource] (file gone,
 * permission revoked) so parsers do not mistake it for damaged data. The public readers rethrow
 * [failure] unchanged.
 */
internal class SourceAccessException(val failure: Exception) : IOException(failure.message, failure)
