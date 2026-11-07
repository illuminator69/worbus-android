package app.worbus.ui.screens.song.components

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.collections.immutable.persistentListOf
import app.worbus.domain.models.DomainFilter
import app.worbus.domain.models.DomainSongListType
import app.worbus.icons.Icons
import app.worbus.icons.outlined.Sort
import app.worbus.ui.components.layouts.TopBarButton
import app.worbus.ui.components.sheets.SortSheet
import app.worbus.ui.util.label

@Composable
fun SongListScreenSortButton(
	nested: Boolean,
	selectedSorting: DomainSongListType,
	onSetSorting: (listType: DomainSongListType) -> Unit,
	selectedReversed: Boolean,
	onSetReversed: (Boolean) -> Unit,
	selectedFilters: Set<DomainFilter>,
	onToggleFilter: (DomainFilter) -> Unit
) {
	val entries = remember {
		persistentListOf(
			DomainSongListType.FrequentlyPlayed,
			DomainSongListType.Newest,
			DomainSongListType.Random,
			DomainSongListType.Rating,
			DomainSongListType.Year
		)
	}
	var expanded by remember { mutableStateOf(false) }
	if (!nested) {
		IconButton(onClick = {
			expanded = true
		}) {
			Icon(
				imageVector = Icons.Outlined.Sort,
				contentDescription = null
			)
		}
	} else {
		TopBarButton(onClick = { expanded = true }) {
			Icon(
				imageVector = Icons.Outlined.Sort,
				contentDescription = null
			)
		}
	}
	if (expanded) {
		SortSheet(
			entries = entries,
			onDismissRequest = { expanded = false },
			selectedSorting = selectedSorting,
			onSetSorting = onSetSorting,
			selectedReversed = selectedReversed,
			label = { it.label() },
			onSetReversed = onSetReversed,
			selectedFilters = selectedFilters,
			onToggleFilter = onToggleFilter
		)
	}
}
