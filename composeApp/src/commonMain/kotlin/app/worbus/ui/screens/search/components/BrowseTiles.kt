package app.worbus.ui.screens.search.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.search_browse
import worbus.composeapp.generated.resources.search_tile_charts
import worbus.composeapp.generated.resources.search_tile_charts_line
import worbus.composeapp.generated.resources.search_tile_discover_line
import worbus.composeapp.generated.resources.search_tile_fresh_line
import worbus.composeapp.generated.resources.search_tile_genres_line
import worbus.composeapp.generated.resources.search_tile_picks
import worbus.composeapp.generated.resources.search_tile_picks_line
import worbus.composeapp.generated.resources.title_discover
import worbus.composeapp.generated.resources.title_fresh
import worbus.composeapp.generated.resources.title_genres
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import app.worbus.ui.screens.search.BrowseTile
import app.worbus.ui.screens.search.PlacedTile
import app.worbus.ui.util.ArrivalGate
import app.worbus.ui.util.arrive

private fun BrowseTile.words(): Pair<StringResource, StringResource> = when (this) {
	BrowseTile.GENRES -> Res.string.title_genres to Res.string.search_tile_genres_line
	BrowseTile.CHARTS -> Res.string.search_tile_charts to Res.string.search_tile_charts_line
	BrowseTile.PICKS -> Res.string.search_tile_picks to Res.string.search_tile_picks_line
	BrowseTile.FRESH -> Res.string.title_fresh to Res.string.search_tile_fresh_line
	BrowseTile.DISCOVER -> Res.string.title_discover to Res.string.search_tile_discover_line
}

/**
 * "Browse" under an empty field (P3): a two-column grid of cards, in the order and with the gates
 * [app.worbus.ui.screens.search.browseTiles] decides. The cards arrive once per visit.
 */
fun LazyGridScope.browseSection(
	tiles: List<PlacedTile>,
	gate: ArrivalGate,
	onOpen: (BrowseTile) -> Unit
) {
	if (tiles.isEmpty()) return
	item(span = { GridItemSpan(maxLineSpan) }, key = "browse-header", contentType = "header") {
		SearchSectionHeader(
			title = stringResource(Res.string.search_browse),
			modifier = Modifier.animateItem()
		)
	}
	var column = 0
	tiles.forEachIndexed { index, placed ->
		val left = placed.fullWidth || column == 0
		val right = placed.fullWidth || column == 1
		column = if (placed.fullWidth) 0 else (column + 1) % 2
		item(
			span = { GridItemSpan(if (placed.fullWidth) maxLineSpan else 1) },
			key = "browse-${placed.tile.name}",
			contentType = "tile"
		) {
			val (title, line) = placed.tile.words()
			BrowseTileCard(
				title = stringResource(title),
				line = stringResource(line),
				onClick = { onOpen(placed.tile) },
				modifier = Modifier
					.animateItem()
					.padding(start = if (left) 16.dp else 6.dp, end = if (right) 16.dp else 6.dp, top = 6.dp, bottom = 6.dp)
					.arrive(gate, "browse-${placed.tile.name}", index)
			)
		}
	}
}
