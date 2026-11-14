package app.worbus.ui.screens.sourcehistory

import app.worbus.domain.manager.AttentionAlbum
import app.worbus.domain.manager.HistoryOpen
import app.worbus.domain.manager.LbBotManager
import app.worbus.domain.manager.LbFillEntry
import app.worbus.domain.manager.asHistoryRow
import app.worbus.domain.manager.historyOpenTarget
import app.worbus.domain.manager.needYouItems
import app.worbus.ui.components.common.FillButton
import app.worbus.ui.components.common.FillText
import app.worbus.ui.components.common.PillTone
import app.worbus.ui.components.common.fillRowButtons
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.attention_missing_off
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/*
 * Stage 2 review M3 and M4 (B-450 moved lb-bot's failed albums and this phone's failures from Library Matches' To fix
 * to Source history's Need you). Two rules of the To fix rows did not move with them:
 * - M3: while lb-bot's downloads are off, a row offers nothing that fetches (Open album, Wishlist, Dismiss), with one
 *   plain "Missing" pill. Need you offered Retry / Try another source / Allow MP3, which lb-bot refuses, and a
 *   downloads-off refusal raises no snackbar, so the button silently did nothing. (Ported from DownloadsOffSurfacesTest's
 *   `attention_album_rows_offer_nothing_that_fetches_when_off`, deleted with the To fix rows.)
 * - M4: lb-bot's failed gap opened the LIBRARY album with its gaps box (a gap is an album the library partly holds),
 *   showed the library's cover and "N tracks missing". In Need you a gap row with no release group had no button.
 */
class NeedYouRowsTest {
	private fun fillEntry(key: String) = LbFillEntry(
		key = key, isGap = false, rgid = "rg-$key", groupId = "", releaseMbid = "rel-$key",
		artist = "Artist", album = "Album", quality = "", state = "failed",
		outcome = LbBotManager.OUTCOME_FAILED, reason = "no peers had it", percent = 0, done = 0, total = 5,
		mp3WouldHelp = true, failureKind = "no_source", retryable = true, attempts = 3, settled = true,
		startedAt = 0L, finishedAt = 0L
	)

	private val today = listOf(FillButton.ALLOW_MP3, FillButton.RETRY, FillButton.TRY_ANOTHER, FillButton.WISHLIST, FillButton.DISMISS)

	@Test
	fun m3_aRowOffersNothingThatFetchesWhileDownloadsAreOff() {
		// This device's row (its buttons are the vocabulary's): Open album, Wishlist, Dismiss.
		assertEquals(
			listOf(FillButton.OPEN_ALBUM, FillButton.WISHLIST, FillButton.DISMISS),
			fillRowButtons(today, lbBotOnly = false, canOpen = true, downloadsOn = false, wishlistAvailable = true, rgid = "rg-1")
		)
		// Already wishlisted, or no release group to wishlist: no Wishlist.
		assertEquals(
			listOf(FillButton.OPEN_ALBUM, FillButton.DISMISS),
			fillRowButtons(today, lbBotOnly = false, canOpen = true, downloadsOn = false, wishlistAvailable = false, rgid = "rg-1")
		)
		// A row only lb-bot knows: Open album, in both modes.
		assertEquals(listOf(FillButton.OPEN_ALBUM), fillRowButtons(today, true, canOpen = true, downloadsOn = false, wishlistAvailable = true, rgid = "rg-1"))
		// One plain pill, no failure of something nobody can retry.
		val pill = assertNotNull(historyPill(fillEntry("1"), downloadsOn = false))
		assertEquals(FillText.Res(Res.string.attention_missing_off), pill.label)
		assertEquals(PillTone.NEUTRAL, pill.tone)
	}

	@Test
	fun m3_unchangedWhileDownloadsAreOn() {
		assertEquals(today, fillRowButtons(today, lbBotOnly = false, canOpen = true, downloadsOn = true, wishlistAvailable = true, rgid = "rg-1"))
		assertEquals(PillTone.BAD, historyPill(fillEntry("1"), downloadsOn = true)?.tone)
	}

	private val failedGap = AttentionAlbum(
		kind = "gap", key = "g-1", groupId = "g-1", albumId = "al-1", artist = "Artist", album = "Partly Held",
		coverUrl = "http://lb/cover/g-1.jpg", state = "failed", missingCount = 3, total = 12, updatedAt = 100.0
	)

	@Test
	fun m4_aFailedGapOpensTheLibraryAlbumWithItsGaps() {
		val row = needYouItems(null, listOf(failedGap), emptyList(), emptyList()).single().asHistoryRow()
		assertEquals(HistoryOpen.Library("al-1", gaps = true), historyOpenTarget(row))
		assertEquals("al-1", row.libraryAlbum.albumId, "its cover is the library album's")
		assertEquals(3, row.libraryAlbum.missingCount)
		// Its one button leads there, though it has no release group.
		assertEquals(
			listOf(FillButton.OPEN_ALBUM),
			fillRowButtons(emptyList(), lbBotOnly = true, canOpen = historyOpenTarget(row) != null, downloadsOn = true, wishlistAvailable = false, rgid = row.rgid)
		)
		// With a release group too, a gap still opens the library's album (To fix's order).
		val withRg = needYouItems(null, listOf(failedGap.copy(rgid = "rg-9")), emptyList(), emptyList()).single().asHistoryRow()
		assertEquals(HistoryOpen.Library("al-1", gaps = true), historyOpenTarget(withRg))
	}

	@Test
	fun m4_aFailedRequestOpensItsReleaseGroupOrItsLibraryAlbum() {
		val request = failedGap.copy(kind = "request", key = "rel-1", groupId = "", releaseMbid = "rel-1", rgid = "rg-1", albumId = "", missingCount = 0)
		assertEquals(HistoryOpen.External("rg-1"), historyOpenTarget(needYouItems(null, listOf(request), emptyList(), emptyList()).single().asHistoryRow()))
		val held = request.copy(rgid = "", albumId = "al-2")
		assertEquals(HistoryOpen.Library("al-2", gaps = false), historyOpenTarget(needYouItems(null, listOf(held), emptyList(), emptyList()).single().asHistoryRow()))
	}
}
