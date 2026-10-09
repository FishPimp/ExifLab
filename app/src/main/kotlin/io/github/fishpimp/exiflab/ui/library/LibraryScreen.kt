package io.github.fishpimp.exiflab.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.designsystem.component.EmptyState
import io.github.fishpimp.exiflab.ui.components.ScreenScaffold

@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
    ScreenScaffold(title = stringResource(R.string.nav_library), modifier = modifier) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                icon = Icons.Rounded.PhotoLibrary,
                shape = MaterialShapes.Sunny,
                title = stringResource(R.string.library_empty_title),
                body = stringResource(R.string.library_empty_body),
            )
        }
    }
}
