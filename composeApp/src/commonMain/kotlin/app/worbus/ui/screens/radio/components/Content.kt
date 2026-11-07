package app.worbus.ui.screens.radio.components

import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.ui.Modifier
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.info_no_radios
import org.jetbrains.compose.resources.stringResource
import app.worbus.domain.models.DomainRadio
import app.worbus.icons.Icons
import app.worbus.icons.outlined.Radio
import app.worbus.ui.components.common.ContentUnavailable
import app.worbus.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import app.worbus.ui.core.UiState

fun LazyGridScope.radioListScreenContent(
	state: UiState<List<DomainRadio>>,
	onRadioClick: (DomainRadio) -> Unit,
	onRadioMenu: (DomainRadio) -> Unit
) {
	val data = state.data.orEmpty()

	if (data.isNotEmpty()) {
		itemsIndexed(data, key = { _, it -> it.id }) { index, radio ->
			RadioRow(
				modifier = Modifier.animateItem(),
				radio = radio,
				shapes = segmentedShapes(index, data.size),
				onPlay = { onRadioClick(radio) },
				onMenu = { onRadioMenu(radio) }
			)
		}
	} else if (state !is UiState.Loading) {
		item(span = { GridItemSpan(maxLineSpan) }) {
			ContentUnavailable(
				icon = Icons.Outlined.Radio,
				label = stringResource(Res.string.info_no_radios)
			)
		}
	}
}
