package io.github.fishpimp.exiflab.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.fishpimp.exiflab.core.designsystem.component.EmptyState
import io.github.fishpimp.exiflab.core.designsystem.component.PillButton
import io.github.fishpimp.exiflab.core.designsystem.component.PrivacyBadge
import io.github.fishpimp.exiflab.core.designsystem.component.StatTile
import io.github.fishpimp.exiflab.core.designsystem.icon.ExifIcons
import io.github.fishpimp.exiflab.core.designsystem.theme.Spacing

@Composable
public fun HomeScreen(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        Text("ExifLab", style = MaterialTheme.typography.displayMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            StatTile(label = "Shutter", value = "1/250", modifier = Modifier.weight(1f))
            StatTile(label = "Aperture", value = "f/1.8", modifier = Modifier.weight(1f))
            StatTile(label = "ISO", value = "200", modifier = Modifier.weight(1f))
        }
        PrivacyBadge(label = "Location")
        EmptyState(icon = ExifIcons.PhotoLibrary, title = "No photos yet", body = "Pick photos to inspect their metadata.") {
            PillButton(text = "Choose photos", onClick = {}, icon = ExifIcons.AddPhotoAlternate)
        }
    }
}
