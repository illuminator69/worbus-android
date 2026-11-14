package app.worbus.ui.screens.collection.components

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.action_more
import org.jetbrains.compose.resources.stringResource
import app.worbus.domain.manager.LbMeta
import app.worbus.ui.util.Fold
import app.worbus.ui.util.teaserText

/** Three lines of prose under the album buttons; the rest lives in the sheet. */
private const val TEASER_LIMIT = 260

/**
 * A short "About" teaser on the album page, opening the full sheet.
 *
 * The album page has never shown a description. What it shows today is the ID3
 * `comment` tag, which is not one and is usually absent; `getAlbumInfo2.notes`
 * has been loaded on every album page since forever and read by nothing.
 *
 * Two sources, and `notes` comes FIRST on an album the library owns. Not because
 * it is better text — often it is not — but because it arrives with the album
 * detail this page already waits for, while lb-bot's lands a second or more
 * later behind a MusicBrainz hop. Preferring lb-bot meant the teaser you had
 * started reading was replaced by a different one. The same rule as the artist
 * header: the slot belongs to whatever the page already blocks on, and lb-bot
 * fills it only when there is nothing there at all.
 *
 * Since the Apple Music agent joined Navidrome's chain, `notes` is real editorial
 * prose rather than the empty field it used to be.
 *
 * Either way the text is put through [teaserText] rather than shown raw: notes
 * are HTML and would otherwise render their markup on screen, which is exactly
 * the bug the artist header had.
 *
 * Nothing renders when there is neither (§7).
 *
 * **It arrives late, so it rolls in** (the owner, 2026-11-14: "the about section pops in", the rows below
 * jumping, where the gaps box's arrival "is not jarring"). `notes` is a network read (`getAlbumInfo2`) made
 * after Room has given the page its album, and lb-bot's text comes after that, so neither can be known by the
 * page's first frame; the slot is always in the list (keyed `about`, as the gaps box's `gap`) and its content
 * comes in through the same [Fold], at the animation style's pace and speed, the rows below following its
 * height. Once a text is shown it stays ([aboutTeaserText]); only "More" can be added, and it rolls in too.
 */
fun LazyListScope.collectionDetailScreenAboutRow(
	/** What the slot shows ([rememberAboutTeaser]); null while there is nothing. */
	text: String?,
	meta: LbMeta?,
	onOpen: () -> Unit
) {
	val fromMeta = metaTeaser(meta)
	// The sheet is worth opening when it holds more than these three lines:
	// lb-bot has an article, credits or links, or the teaser itself is cut off
	// and the rest of it is in there. Note this can flip false→true when lb-bot
	// answers, which only ever ADDS an affordance — the text above does not move.
	val expandable = text != null && (fromMeta != null ||
		meta?.credits?.isNotEmpty() == true ||
		meta?.links?.isNotEmpty() == true ||
		teaserText(text, TEASER_LIMIT).endsWith("\u2026"))
	item(key = ABOUT_KEY) {
		Fold(visible = text != null) {
			// Held through the fold's exit (a page whose album lost its text).
			val shown = text ?: return@Fold
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp, vertical = 8.dp)
					.then(if (expandable) Modifier.clickable(onClick = onOpen) else Modifier)
			) {
				Text(
					"About",
					style = MaterialTheme.typography.titleMediumEmphasized,
					fontWeight = FontWeight(600),
					color = MaterialTheme.colorScheme.primary,
					modifier = Modifier.padding(bottom = 4.dp)
				)
				Text(
					teaserText(shown, TEASER_LIMIT),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 3,
					overflow = TextOverflow.Ellipsis
				)
				// Its own line, never appended to the teaser: that Text is
				// `maxLines = 3` + Ellipsis and the teaser already fills it, so an
				// appended affordance is laid out and then clipped — present in the
				// string and invisible on the device. Same trap as the artist header.
				// It can be added after the text (lb-bot answering), so it rolls in too.
				Fold(visible = expandable) {
					Text(
						stringResource(Res.string.action_more),
						style = MaterialTheme.typography.bodySmall,
						fontWeight = FontWeight(600),
						color = MaterialTheme.colorScheme.primary,
						modifier = Modifier.padding(top = 2.dp)
					)
				}
			}
		}
	}
}

/** The About slot's list key: the slot is always in the list, its content folds in. */
internal const val ABOUT_KEY = "about"

private fun metaTeaser(meta: LbMeta?): String? =
	meta?.summary?.ifBlank { null } ?: meta?.wikidataDescription?.ifBlank { null }

/**
 * The text the About slot shows: the one it already shows ([shown]) whatever has arrived since, else
 * Navidrome's [notes] (they come with the album detail this page reads first), else lb-bot's text. A text
 * is never swapped for another under the reader (navi-connect CLAUDE.md §5).
 */
internal fun aboutTeaserText(shown: String?, notes: String?, meta: LbMeta?): String? =
	shown ?: notes?.ifBlank { null } ?: metaTeaser(meta)

/** [aboutTeaserText] kept for the page ([pageKey]): the first text shown stays for the visit. */
@Composable
fun rememberAboutTeaser(pageKey: String, notes: String?, meta: LbMeta?): String? {
	val shown = remember(pageKey) { mutableStateOf<String?>(null) }
	val text = aboutTeaserText(shown.value, notes, meta)
	SideEffect { shown.value = text }
	return text
}
