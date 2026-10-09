package io.github.fishpimp.exiflab.ui.selection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.ui.components.PhotoGridCells
import io.github.fishpimp.exiflab.ui.components.PhotoTile
import io.github.fishpimp.exiflab.ui.components.ScreenScaffold

/** Several photos picked or shared at once; tapping one opens it. */
@Composable
fun SelectionScreen(
    refs: List<PhotoRef>,
    onBack: () -> Unit,
    onOpenPhoto: (PhotoRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(
        title = pluralStringResource(R.plurals.photo_count, refs.size, refs.size),
        onBack = onBack,
        modifier = modifier,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyVerticalGrid(
                columns = PhotoGridCells,
                modifier = Modifier.widthIn(max = 1200.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(refs, key = { it.uri }) { ref -> PhotoTile(ref = ref, onClick = { onOpenPhoto(ref) }) }
            }
        }
    }
}
