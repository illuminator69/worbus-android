package app.worbus.ui.screens.attention

import worbus.composeapp.generated.resources.attention_section_to_file
import androidx.compose.foundation.lazy.items
import kotlinx.coroutines.Job
import androidx.compose.runtime.Stable
import app.worbus.ui.components.common.ROW_PLACEHOLDERS
import app.worbus.ui.components.common.RowPlaceholder
import org.jetbrains.compose.resources.StringResource
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.action_open_album
import worbus.composeapp.generated.resources.action_open_in_lbbot
import worbus.composeapp.generated.resources.attention_artists_to_check
import worbus.composeapp.generated.resources.attention_artists_to_check_hint
import worbus.composeapp.generated.resources.attention_choose_source
import worbus.composeapp.generated.resources.attention_empty
import worbus.composeapp.generated.resources.attention_failed
import worbus.composeapp.generated.resources.attention_files
import worbus.composeapp.generated.resources.attention_folder_hint
import worbus.composeapp.generated.resources.attention_imports_home_only
import worbus.composeapp.generated.resources.attention_imports_line
import worbus.composeapp.generated.resources.attention_imports_need_you
import worbus.composeapp.generated.resources.attention_lbbot_only_hint
import worbus.composeapp.generated.resources.attention_missing_off
import worbus.composeapp.generated.resources.request_downloads_off
import worbus.composeapp.generated.resources.attention_matched
import worbus.composeapp.generated.resources.attention_needs_pick
import worbus.composeapp.generated.resources.attention_nothing_to_fix
import worbus.composeapp.generated.resources.attention_notice_older
import worbus.composeapp.generated.resources.attention_notice_truncated
import worbus.composeapp.generated.resources.attention_notice_unreachable
import worbus.composeapp.generated.resources.attention_notice_unreachable_never
import worbus.composeapp.generated.resources.attention_device_only
import worbus.composeapp.generated.resources.attention_section_albums
import worbus.composeapp.generated.resources.attention_section_to_fix
import worbus.composeapp.generated.resources.attention_to_fix
import worbus.composeapp.generated.resources.attention_to_fix_summary
import worbus.composeapp.generated.resources.attention_to_fix_imports
import worbus.composeapp.generated.resources.attention_to_fix_folders
import worbus.composeapp.generated.resources.attention_not_counted
import worbus.composeapp.generated.resources.attention_tracks_missing
import worbus.composeapp.generated.resources.attention_unidentified_folder
import worbus.composeapp.generated.resources.title_needs_attention
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import app.worbus.di.LocalBottomBarScrollManager
import app.worbus.di.LocalNavStack
import app.worbus.di.LocalPlatformContext
import app.worbus.di.isLandscape
import app.worbus.domain.manager.AttentionFolder
import app.worbus.domain.manager.AttentionNotice
import app.worbus.domain.manager.AttentionPage
import app.worbus.domain.manager.AttentionRow
import app.worbus.domain.manager.ImportsRow
import app.worbus.domain.manager.LbFillEntry
import app.worbus.domain.manager.importsRow
import app.worbus.domain.manager.isPrivateHubAddress
import app.worbus.domain.manager.lbPageLink
import app.worbus.domain.manager.toFixSectionCount
import app.worbus.domain.manager.toFixTotal
import app.worbus.domain.manager.PreferenceManager
import app.worbus.domain.manager.asLedgerRow
import app.worbus.domain.manager.attentionEmptyShown
import app.worbus.domain.manager.folderDetail
import app.worbus.domain.manager.folderLine
import app.worbus.domain.manager.folderTitle
import app.worbus.domain.manager.formatListenedAt
import app.worbus.domain.models.settings.BottomBarVisibilityMode
import app.worbus.icons.Icons
import app.worbus.icons.brand.Musicbrainz
import app.worbus.icons.outlined.Album
import app.worbus.icons.outlined.Check
import app.worbus.icons.outlined.Download
import app.worbus.icons.outlined.Link
import app.worbus.ui.components.common.ContentUnavailable
import app.worbus.ui.components.common.FillButton
import app.worbus.ui.components.common.GroupHeader
import app.worbus.ui.components.common.LibraryRowCover
import app.worbus.ui.components.common.LibraryRowDefaults
import app.worbus.ui.components.common.Pill
import app.worbus.ui.components.common.PillTone
import app.worbus.ui.components.common.RemoteCoverArt
import app.worbus.ui.components.common.SegmentedListItem
import app.worbus.ui.components.common.SegmentedListItemDefaults
import app.worbus.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import app.worbus.ui.components.common.SongRowDefaults
import app.worbus.ui.components.common.StatePill
import app.worbus.ui.components.common.FillText
import app.worbus.ui.components.common.describeFill
import app.worbus.ui.components.common.fillRowColors
import app.worbus.ui.components.common.label
import app.worbus.ui.components.common.rememberFillClock
import app.worbus.ui.components.common.resolve
import app.worbus.ui.components.layouts.NestedTopBar
import app.worbus.ui.components.layouts.PullToRefreshBox
import app.worbus.ui.components.layouts.RootBottomBar
import app.worbus.ui.navigation.Screen
import app.worbus.ui.screens.downloads.viewmodels.DownloadCenterViewModel
import app.worbus.ui.util.Fold
import app.worbus.ui.util.withoutTop
import app.worbus.ui.components.sheets.lbUnavailableRes
import app.worbus.ui.components.sheets.rememberLbTooOld

/**
 * The **To fix** section of Library Matches (B-406, the owner's ruling of 2026-11-13). It was the page
 * "Needs attention" (P6), a menu entry of its own, which the owner found redundant: lb-bot's own "to fix",
 * the count behind the avatar's dot, now a chip of Library Matches beside To check.
 *
 * What it lists, as the page did: lb-bot's headline ("9 to fix") and notices; *Albums*, failed gaps and
 * failed whole-album requests, then the albums waiting on a pick (a tap unfolds a row into the fill
 * vocabulary's words; a row this device's ledger holds carries the vocabulary's buttons through the
 * Downloads page's handlers, a row only lb-bot knows Open album alone); *Downloaded, to file*: the imports
 * lb-bot could not finish and the folders waiting to be filed, each with "Open in lb-bot" only, since
 * filing moves and retags files; this phone's own rows lb-bot no longer lists; and the link to lb-bot's
 * own list. The artists to check are not repeated here: they are the page's To check chip. Nothing here
 * writes anything new to lb-bot.
 */
@Stable
class ToFixUi(
	val page: AttentionPage,
	val now: State<Long>,
	val open: Set<String>,
	val onToggle: (String) -> Unit,
	val wishlistAvailable: (AttentionRow) -> Boolean,
	val downloadsOn: Boolean,
	val webUrl: String,
	val activeAddress: String?,
	val onAction: (FillButton, LbFillEntry, AttentionRow) -> Unit,
	val onOpenAlbum: (AttentionRow) -> Unit,
	val onOpenUri: (String) -> Unit,
	/** Read lb-bot's to-fix again (on a pull). */
	val refresh: () -> Job
)

/**
 * The section's state, gathered once by the page that holds it: lb-bot's to-fix (read on opening), the
 * row actions (the Downloads page's own, so they run to completion if the page is left), and which rows
 * are unfolded (kept across a refresh and a rotation). Action messages go to [snackbar].
 */
@Composable
fun rememberToFixUi(snackbar: SnackbarHostState): ToFixUi {
	val viewModel = koinViewModel<AttentionViewModel>()
	val actions = koinViewModel<DownloadCenterViewModel>()
	val page by viewModel.page.collectAsStateWithLifecycle()
	val webUrl by viewModel.webUrl.collectAsStateWithLifecycle()
	val activeAddress by viewModel.activeAddress.collectAsStateWithLifecycle()
	val wishlisted by actions.wishlisted.collectAsStateWithLifecycle()
	val downloadsOn by viewModel.downloadsOn.collectAsStateWithLifecycle()
	val backStack = LocalNavStack.current
	val uriHandler = LocalUriHandler.current
	val now = rememberFillClock()
	var open by rememberSaveable { mutableStateOf(setOf<String>()) }

	LaunchedEffect(Unit) { actions.actionMessages.collect { snackbar.showSnackbar(it) } }
	LaunchedEffect(Unit) { viewModel.refresh() }

	// B-404 review M2: lb-bot's rows name the album, not its cover: Room's, read by the opener.
	val openLibraryAlbum = app.worbus.ui.navigation.rememberAlbumPageOpener()
	val openAlbum: (AttentionRow) -> Unit = { row ->
		val a = row.album
		when {
			// A gap is an album the library partly has: its own page, with the gaps box open.
			a.kind == "gap" && a.albumId.isNotBlank() -> openLibraryAlbum(a.albumId, "attention", true)
			a.rgid.isNotBlank() ->
				backStack.add(Screen.ExternalAlbum(rgid = a.rgid, artistName = a.artist, title = a.album))
			a.albumId.isNotBlank() -> openLibraryAlbum(a.albumId, "attention", false)
			else -> Unit
		}
	}
	return ToFixUi(
		page = page,
		now = now,
		open = open,
		onToggle = { id -> open = if (id in open) open - id else open + id },
		wishlistAvailable = { row -> actions.wishlistSupported && row.entry?.key !in wishlisted },
		downloadsOn = downloadsOn,
		webUrl = webUrl,
		activeAddress = activeAddress,
		onAction = { button, entry, row ->
			when (button) {
				FillButton.RETRY -> actions.retryFill(entry.key)
				FillButton.TRY_ANOTHER -> actions.retryAnotherSource(entry.key)
				FillButton.CANCEL -> actions.cancelFill(entry.key)
				FillButton.ALLOW_MP3 -> actions.allowMp3AndRetry(entry)
				FillButton.WISHLIST -> actions.addToWishlist(entry)
				FillButton.DISMISS -> actions.dismissFill(entry.key)
				FillButton.OPEN_ALBUM -> openAlbum(row)
			}
		},
		onOpenAlbum = openAlbum,
		onOpenUri = { uri -> runCatching { uriHandler.openUri(uri) } },
		refresh = { viewModel.refresh() }
	)
}

/**
 * The To fix number the chip says and the page chooses its section by: the section's own ([toFixTotal], the
 * desktop's number, which the headline says too), and while nothing is read yet Library Matches' menu badge
 * ([badge]), which is the same rule over the same state: one number, never one per surface. Null (no number
 * on the chip) where lb-bot has not answered, or before the first read with no badge to go on.
 */
fun toFixNumber(page: AttentionPage, badge: Int): Int? = when (page) {
	// Review L1 (second re-walk): never "To fix (0)" over rows the section lists.
	is AttentionPage.Rows -> toFixTotal(page)?.takeUnless { it == 0 && !toFixHeadlineNothingShown(page) }
	AttentionPage.Loading -> badge.takeIf { it > 0 }
}

/**
 * Whether the To fix headline may say "Nothing to fix" under a 0 (and the chip "To fix (0)"): only when the
 * section lists no row at all. Since the number is the desktop's (imports and folders), a failed album or a
 * pick is listed and not counted, and "Nothing to fix" above it would be false; review L1: nor over folders
 * listed while lb-bot's `counts` say none (an lb-bot whose counts lack `folders`).
 */
fun toFixHeadlineNothingShown(page: AttentionPage.Rows): Boolean =
	page.toFix == 0 && page.albums.isEmpty() && page.deviceOnly.isEmpty() && page.folders.isEmpty()

/**
 * Review L2 (second re-walk): what the To fix number is made of, as Worbus for desktop's first line says it
 * ("41 to fix: 16 imports, 25 downloaded folders to file"): lb-bot's imports, and the folders counted (the
 * number less the imports, so the parts always add up to it). A part that is 0 is not said.
 */
data class ToFixParts(val total: Int, val imports: Int, val folders: Int)

fun toFixParts(page: AttentionPage.Rows): ToFixParts? {
	val total = toFixTotal(page) ?: return null
	val imports = page.imports.coerceIn(0, total)
	return ToFixParts(total, imports, total - imports)
}

/** The section's rows, as items of the holding page's list. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun LazyListScope.toFixItems(ui: ToFixUi) {
	val openLb = { path: String -> if (ui.webUrl.isNotBlank()) ui.onOpenUri("${ui.webUrl}/#/$path") }
	when (val p = ui.page) {
		// Waiting rows under the title's place, then lb-bot's rows (audit L1-05).
		AttentionPage.Loading -> items(ROW_PLACEHOLDERS, key = { "fix:placeholder:$it" }) {
			RowPlaceholder(Modifier.padding(bottom = SegmentedListItemDefaults.SegmentedGap))
		}
		is AttentionPage.Rows -> {
			item(key = "fix:head") {
				AttentionHead(p, Modifier.animateItem())
			}
			// Re-walk NM2: only of a list lb-bot answered; a failed read says its one notice alone.
			if (attentionEmptyShown(p)) {
				item(key = "fix:empty") {
					ContentUnavailable(
						modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp).animateItem(),
						icon = Icons.Outlined.Check,
						label = stringResource(Res.string.attention_empty)
					)
				}
			}
			// Review L2 (second re-walk): a group the headline's number does not count says so under its header,
			// so "41 to fix" over "Albums (10)" never reads as 41 of those.
			val uncountedNote = (toFixTotal(p) ?: 0) > 0
			val albumRows: LazyListScope.(String, StringResource, List<AttentionRow>) -> Unit = { key, title, rows ->
				if (rows.isNotEmpty()) {
					item(key = "fix:$key-header") {
						GroupHeader(stringResource(title), count = rows.size, modifier = Modifier.animateItem())
					}
					if (uncountedNote) {
						item(key = "fix:$key-uncounted") {
							Text(
								stringResource(Res.string.attention_not_counted),
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								modifier = Modifier.padding(start = 4.dp, bottom = 8.dp).animateItem()
							)
						}
					}
					itemsIndexed(rows, key = { _, row -> "fix:${row.id}" }) { index, row ->
						AlbumRow(
							row = row,
							now = ui.now,
							shapes = segmentedShapes(index, rows.size),
							open = row.id in ui.open,
							onToggle = { ui.onToggle(row.id) },
							wishlistAvailable = ui.wishlistAvailable(row),
							downloadsOn = ui.downloadsOn,
							onAction = { button, entry -> ui.onAction(button, entry, row) },
							onOpenAlbum = { ui.onOpenAlbum(row) },
							modifier = Modifier
								.padding(bottom = SegmentedListItemDefaults.SegmentedGap)
								.animateItem()
						)
					}
				}
			}
			albumRows("albums", Res.string.attention_section_albums, p.albums)
			// Worbus P4: lb-bot's failed imports, one row linking to lb-bot's own list (design §6.5).
			val imports = importsRow(p, lbPageLink(ui.webUrl, ui.activeAddress, ::isPrivateHubAddress))
			val importRows = if (imports != null) 1 else 0
			val fileRows = importRows + p.folders.size
			if (fileRows > 0) {
				item(key = "fix:files-header") {
					GroupHeader(
						stringResource(Res.string.attention_section_to_file),
						// B-406: what the rows stand for (lb-bot's imports and folders), not the rows.
						count = toFixSectionCount(p),
						modifier = Modifier.animateItem()
					)
				}
				if (imports != null) {
					item(key = "fix:imports") {
						ImportsRowItem(
							row = imports,
							shapes = segmentedShapes(0, fileRows),
							onOpen = ui.onOpenUri,
							modifier = Modifier
								.padding(bottom = SegmentedListItemDefaults.SegmentedGap)
								.animateItem()
						)
					}
				}
				itemsIndexed(p.folders, key = { _, f -> "fix:folder:${f.id}" }) { index, folder ->
					val id = "folder:${folder.id}"
					FolderRow(
						folder = folder,
						shapes = segmentedShapes(importRows + index, fileRows),
						open = id in ui.open,
						onToggle = { ui.onToggle(id) },
						canOpenInLbBot = ui.webUrl.isNotBlank(),
						onOpenInLbBot = { openLb("place/${folder.id}") },
						modifier = Modifier
							.padding(bottom = SegmentedListItemDefaults.SegmentedGap)
							.animateItem()
					)
				}
			}
			// L4b-02: this phone's own rows lb-bot no longer lists, apart from lb-bot's and its count.
			albumRows("device-only", Res.string.attention_device_only, p.deviceOnly)
			if (ui.webUrl.isNotBlank()) {
				item(key = "fix:open-lb") {
					TextButton(
						onClick = { openLb("needs/fix") },
						modifier = Modifier.padding(top = 8.dp).animateItem()
					) {
						Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp))
						Text(stringResource(Res.string.action_open_in_lbbot), modifier = Modifier.padding(start = 8.dp))
					}
				}
			}
		}
	}
}

/** The title line ("20 to fix") and the one muted line of notices under it. */
@Composable
private fun AttentionHead(page: AttentionPage.Rows, modifier: Modifier = Modifier) {
	val unreachable = page.notices.any { it is AttentionNotice.Unreachable }
	Column(modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 4.dp)) {
		// The chip's and the badge's number (`toFixTotal`, the desktop's: imports + folders to file), or
		// none without an answer from lb-bot.
		val count = toFixTotal(page)
		val parts = toFixParts(page)
		val headline = when {
			count == null -> null
			// Review L2: what the number is made of, as the desktop's line: "41 to fix: 16 imports, 25
			// downloaded folders to file". It is the "Downloaded, to file" group's number, not the page's rows.
			count > 0 && parts != null -> stringResource(
				Res.string.attention_to_fix_summary,
				count,
				listOfNotNull(
					parts.imports.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.attention_to_fix_imports, it, it) },
					parts.folders.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.attention_to_fix_folders, it, it) }
				).joinToString(", ")
			)
			count > 0 -> stringResource(Res.string.attention_to_fix, count)
			// "Nothing to fix" is said only for an answer lb-bot just gave (a number from an older answer is
			// still said, with the notice saying when it was read), and never over rows the section lists
			// but the number does not count (failed albums, picks, this phone's own rows).
			unreachable || !toFixHeadlineNothingShown(page) -> null
			else -> stringResource(Res.string.attention_nothing_to_fix)
		}
		if (headline != null) {
			Text(headline, style = MaterialTheme.typography.titleMedium)
		}
		val zone = remember { TimeZone.currentSystemDefault() }
		// NB2: while the hub calls lb-bot too old for this user, that is what the notice says (R26).
		val tooOld = rememberLbTooOld()
		val lines = page.notices.map { notice ->
			when (notice) {
				AttentionNotice.Older -> stringResource(Res.string.attention_notice_older)
				is AttentionNotice.Unreachable -> if (tooOld) stringResource(lbUnavailableRes(true, Res.string.attention_notice_unreachable_never)) else notice.since?.let {
					stringResource(Res.string.attention_notice_unreachable, formatListenedAt(it / 1000.0, zone))
				} ?: stringResource(Res.string.attention_notice_unreachable_never)
				is AttentionNotice.Truncated ->
					stringResource(Res.string.attention_notice_truncated, notice.albums + notice.folders)
			}
		}
		if (lines.isNotEmpty()) {
			Text(
				lines.joinToString(" "),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
		}
	}
}

/**
 * One album: art, name, artist, a pill. Unfolds in place into the vocabulary's words and buttons:
 * every button for a row the ledger holds, Open album alone for one only lb-bot knows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlbumRow(
	row: AttentionRow,
	now: State<Long>,
	shapes: ListItemShapes,
	open: Boolean,
	onToggle: () -> Unit,
	wishlistAvailable: Boolean,
	downloadsOn: Boolean,
	onAction: (FillButton, LbFillEntry) -> Unit,
	onOpenAlbum: () -> Unit,
	modifier: Modifier = Modifier
) {
	val a = row.album
	val preferenceManager = koinInject<PreferenceManager>()
	val entry = row.entry
	val view = describeFill(entry ?: a.asLedgerRow(), now.value, wishlistAvailable, tooOld = rememberLbTooOld())
	// Dismiss only where it removes the row: one only this phone holds (L4b-09). Worbus P1: while
	// downloading is off, nothing that fetches ([attentionButtons]).
	val buttons = attentionButtons(row, view.buttons, wishlistAvailable, downloadsOn)
	val pill = attentionPill(row, downloadsOn)
	val stateWords = pill.label.resolve()
	SegmentedListItem(
		onClick = onToggle,
		modifier = modifier.semantics { stateDescription = stateWords },
		shapes = shapes,
		colors = fillRowColors(),
		contentPadding = SongRowDefaults.ContentPadding,
		leadingContent = {
			if (a.albumId.isNotBlank()) {
				LibraryRowCover(coverArtId = a.albumId, name = a.album, fallbackAlbumId = a.albumId)
			} else {
				RemoteCoverArt(
					url = a.coverUrl.ifBlank { null },
					modifier = Modifier.size(LibraryRowDefaults.ArtSize),
					shape = preferenceManager.coverArtShape.decreasedShape
				)
			}
		},
		trailingContent = { StatePill(pill) },
		supportingContent = {
			Column {
				val missing = if (a.kind == "gap" && a.missingCount > 0)
					pluralStringResource(Res.plurals.attention_tracks_missing, a.missingCount, a.missingCount) else null
				Text(
					listOfNotNull(a.artist.ifBlank { null }, missing).joinToString(" · "),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
				Fold(visible = open) {
					Column(Modifier.padding(top = 6.dp)) {
						if (!downloadsOn) {
							// Worbus P1: the fill's own words are a download's (its failure, lb-bot's
							// reason, how often it looked): one plain line instead, offering nothing.
							Text(
								stringResource(Res.string.request_downloads_off),
								color = MaterialTheme.colorScheme.onSurfaceVariant
							)
						} else {
							Text(
								view.headline.resolve(),
								color = if (a.picking) MaterialTheme.colorScheme.onSurfaceVariant
								else MaterialTheme.colorScheme.error
							)
							view.sublines.forEach { line ->
								Text(line.resolve(), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
							}
							view.explain?.let { Text(it.resolve(), style = MaterialTheme.typography.bodySmall) }
						}
						if (entry == null && downloadsOn) {
							Text(
								stringResource(Res.string.attention_lbbot_only_hint),
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant
							)
						}
						if (buttons.isNotEmpty()) {
							FlowRow(
								modifier = Modifier.padding(top = 4.dp),
								horizontalArrangement = Arrangement.spacedBy(4.dp)
							) {
								buttons.forEach { button ->
									TextButton(onClick = {
										if (entry != null) onAction(button, entry) else onOpenAlbum()
									}) {
										Text(stringResource(if (entry == null) Res.string.action_open_album else button.label))
									}
								}
							}
						}
					}
				}
			}
		}
	) {
		Text(a.album.ifBlank { a.artist }.ifBlank { a.key }, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}

/**
 * Worbus P1: an album row's buttons. While lb-bot can download, today's: the vocabulary's for a row the
 * ledger holds (Dismiss only where it removes the row, L4b-09), Open album alone for one only lb-bot
 * knows. While it cannot, nothing that fetches: Open album, Wishlist (a ledger row with a release
 * group, where the wishlist is offered), Dismiss (where it removes the row).
 */
internal fun attentionButtons(
	row: AttentionRow,
	fillButtons: List<FillButton>,
	wishlistAvailable: Boolean,
	downloadsOn: Boolean
): List<FillButton> {
	val a = row.album
	val canOpen = a.rgid.isNotBlank() || a.albumId.isNotBlank()
	val entry = row.entry ?: return listOf(FillButton.OPEN_ALBUM).filter { canOpen }
	if (downloadsOn) return fillButtons.filter { it != FillButton.DISMISS || row.dismissable }
	return buildList {
		if (canOpen) add(FillButton.OPEN_ALBUM)
		if (wishlistAvailable && entry.rgid.isNotBlank()) add(FillButton.WISHLIST)
		if (row.dismissable) add(FillButton.DISMISS)
	}
}

/**
 * An album row's pill: "Choose a source" or "Failed" while lb-bot can download; while it cannot, one
 * plain "Missing" (Worbus P1: no source, no download, no failure of something nobody can retry).
 */
internal fun attentionPill(row: AttentionRow, downloadsOn: Boolean): Pill = when {
	!downloadsOn -> Pill(FillText.Res(Res.string.attention_missing_off), PillTone.NEUTRAL)
	row.album.picking -> Pill(FillText.Res(Res.string.attention_choose_source), PillTone.WARN)
	else -> Pill(FillText.Res(Res.string.attention_failed), PillTone.BAD)
}

/**
 * Worbus P4: "N imports need you" with lb-bot's line, and **Open in lb-bot** to its list when there is a
 * link ([ImportsRow.link]); with none (away from home, no `LBBOT_WEB_URL`), the line says where to fix
 * them and there is no button. Nothing here acts on an import.
 */
@Composable
private fun ImportsRowItem(
	row: ImportsRow,
	shapes: ListItemShapes,
	onOpen: (String) -> Unit,
	modifier: Modifier = Modifier
) {
	val link = row.link
	val leading = @Composable {
		Box(Modifier.size(LibraryRowDefaults.ArtSize), contentAlignment = Alignment.Center) {
			Icon(Icons.Outlined.Album, contentDescription = null)
		}
	}
	val headline = @Composable {
		Text(pluralStringResource(Res.plurals.attention_imports_need_you, row.count, row.count))
	}
	if (link == null) {
		// Review M3: with nowhere to go the row is its line, not a control that does nothing.
		SegmentedListItem(
			modifier = modifier,
			shapes = shapes,
			colors = fillRowColors(),
			contentPadding = SongRowDefaults.ContentPadding,
			leadingContent = leading,
			supportingContent = { Text(stringResource(Res.string.attention_imports_home_only)) },
			content = headline
		)
		return
	}
	SegmentedListItem(
		onClick = { onOpen(link) },
		modifier = modifier,
		shapes = shapes,
		colors = fillRowColors(),
		contentPadding = SongRowDefaults.ContentPadding,
		leadingContent = leading,
		supportingContent = {
			Column {
				Text(stringResource(Res.string.attention_imports_line))
				TextButton(onClick = { onOpen(link) }) {
					Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp))
					Text(stringResource(Res.string.action_open_in_lbbot), modifier = Modifier.padding(start = 8.dp))
				}
			}
		},
		content = headline
	)
}

/** A downloaded folder waiting to be filed: what it was identified as and how many files, nothing else. */
@Composable
private fun FolderRow(
	folder: AttentionFolder,
	shapes: ListItemShapes,
	open: Boolean,
	onToggle: () -> Unit,
	canOpenInLbBot: Boolean,
	onOpenInLbBot: () -> Unit,
	modifier: Modifier = Modifier
) {
	val unidentified = stringResource(Res.string.attention_unidentified_folder)
	val filesWord = @Composable { n: Int -> pluralStringResource(Res.plurals.attention_files, n, n) }
	val files = filesWord(folder.fileCount)
	val spoken = folderLine(folder, unidentified) { files }
	val matched = folder.identified && folder.matchLabel.isNotBlank()
	val pill = if (matched) Pill(FillText.Res(Res.string.attention_matched), PillTone.NEUTRAL)
	else Pill(FillText.Res(Res.string.attention_needs_pick), PillTone.WARN)
	// Its pill is said too: the row's description replaces what its children would say (L4b-11).
	val stateWords = pill.label.resolve()
	SegmentedListItem(
		onClick = onToggle,
		modifier = modifier.semantics {
			contentDescription = spoken
			stateDescription = stateWords
		},
		shapes = shapes,
		colors = fillRowColors(),
		contentPadding = SongRowDefaults.ContentPadding,
		leadingContent = {
			Box(Modifier.size(LibraryRowDefaults.ArtSize), contentAlignment = Alignment.Center) {
				Icon(Icons.Outlined.Download, contentDescription = null)
			}
		},
		trailingContent = { StatePill(pill) },
		supportingContent = {
			Column {
				Text(folderDetail(folder) { files }, maxLines = 1, overflow = TextOverflow.Ellipsis)
				Fold(visible = open) {
					Column(Modifier.padding(top = 6.dp)) {
						Text(stringResource(Res.string.attention_folder_hint), style = MaterialTheme.typography.bodySmall)
						if (canOpenInLbBot) {
							TextButton(onClick = onOpenInLbBot, modifier = Modifier.semantics { }) {
								Text(stringResource(Res.string.action_open_in_lbbot))
							}
						}
					}
				}
			}
		}
	) {
		Text(folderTitle(folder, unidentified), maxLines = 2, overflow = TextOverflow.Ellipsis)
	}
}
