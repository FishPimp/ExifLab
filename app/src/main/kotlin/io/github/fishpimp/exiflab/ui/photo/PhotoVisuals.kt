package io.github.fishpimp.exiflab.ui.photo

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.graphics.shapes.RoundedPolygon
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.metadata.model.DirectoryGroup
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory

/** Icon of a privacy category, shown next to its label (never color alone). */
val SensitivityCategory.icon: ImageVector
    get() = when (this) {
        SensitivityCategory.Location -> Icons.Rounded.LocationOn
        SensitivityCategory.SerialNumber -> Icons.Rounded.Numbers
        SensitivityCategory.PersonName -> Icons.Rounded.Badge
        SensitivityCategory.DeviceIdentifier -> Icons.Rounded.Fingerprint
        SensitivityCategory.People -> Icons.Rounded.Face
    }

/** Short plural label of a privacy category, e.g. "Serial numbers". */
@get:StringRes
val SensitivityCategory.labelRes: Int
    get() = when (this) {
        SensitivityCategory.Location -> R.string.privacy_category_location
        SensitivityCategory.SerialNumber -> R.string.privacy_category_serial_number
        SensitivityCategory.PersonName -> R.string.privacy_category_person_name
        SensitivityCategory.DeviceIdentifier -> R.string.privacy_category_device_id
        SensitivityCategory.People -> R.string.privacy_category_people
    }

/** Look of a section header badge: an icon on an expressive shape in a tonal color pair. */
@Immutable
class SectionBadge(val icon: ImageVector, val shape: RoundedPolygon, val container: Color, val content: Color)

/** Badge for a directory group, or for the warnings section when [group] is null. */
@Composable
fun sectionBadge(group: DirectoryGroup?): SectionBadge {
    val colors = MaterialTheme.colorScheme
    val sensitive = ExifLabTheme.extendedColors
    return when (group) {
        DirectoryGroup.Exif -> SectionBadge(Icons.Rounded.PhotoCamera, MaterialShapes.Cookie9Sided, colors.primaryContainer, colors.onPrimaryContainer)
        DirectoryGroup.Gps -> SectionBadge(
            Icons.Rounded.LocationOn,
            MaterialShapes.Cookie6Sided,
            sensitive.sensitiveContainer,
            sensitive.onSensitiveContainer,
        )
        DirectoryGroup.MakerNote -> SectionBadge(Icons.Rounded.Memory, MaterialShapes.Cookie4Sided, colors.secondaryContainer, colors.onSecondaryContainer)
        DirectoryGroup.Xmp -> SectionBadge(Icons.Rounded.Code, MaterialShapes.Clover4Leaf, colors.tertiaryContainer, colors.onTertiaryContainer)
        DirectoryGroup.Iptc -> SectionBadge(Icons.AutoMirrored.Rounded.Article, MaterialShapes.Pentagon, colors.secondaryContainer, colors.onSecondaryContainer)
        DirectoryGroup.Icc -> SectionBadge(Icons.Rounded.Palette, MaterialShapes.Flower, colors.tertiaryContainer, colors.onTertiaryContainer)
        DirectoryGroup.Container -> SectionBadge(Icons.Rounded.Inventory2, MaterialShapes.Square, colors.surfaceContainerHighest, colors.onSurfaceVariant)
        DirectoryGroup.File -> SectionBadge(
            Icons.AutoMirrored.Rounded.InsertDriveFile,
            MaterialShapes.Arch,
            colors.surfaceContainerHighest,
            colors.onSurfaceVariant,
        )
        DirectoryGroup.Other -> SectionBadge(Icons.Rounded.Category, MaterialShapes.Gem, colors.surfaceContainerHighest, colors.onSurfaceVariant)
        null -> SectionBadge(Icons.Rounded.WarningAmber, MaterialShapes.Burst, colors.errorContainer, colors.onErrorContainer)
    }
}
