package app.worbus.ui.screens.genre.components

import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.Modifier
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.info_no_genres
import org.jetbrains.compose.resources.stringResource
import app.worbus.domain.models.DomainGenre
import app.worbus.icons.Icons
import app.worbus.icons.outlined.Genre
import app.worbus.ui.components.common.ContentUnavailable
import app.worbus.ui.core.UiState

fun LazyGridScope.genreListScreenContent(
	state: UiState<List<DomainGenre>>
) {
	val data = state.data.orEmpty()
	if (data.isNotEmpty()) {
		items(data, { it.name }) { genre ->
			GenreListScreenCard(
				modifier = Modifier.animateItem(),
				genre = genre
			)
		}
	} else {
		when (state) {
			is UiState.Loading -> items(10) {
				GenreListScreenCardPlaceholder()
			}

			else -> {
				item(span = { GridItemSpan(maxLineSpan) }) {
					ContentUnavailable(
						icon = Icons.Outlined.Genre,
						label = stringResource(Res.string.info_no_genres)
					)
				}
			}
		}
	}
}
