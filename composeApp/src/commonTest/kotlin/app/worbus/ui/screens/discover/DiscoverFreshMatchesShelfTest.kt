package app.worbus.ui.screens.discover

import app.worbus.domain.manager.LbFreshFeed
import app.worbus.domain.manager.LbFreshRelease
import app.worbus.ui.screens.discover.viewmodels.discoverFreshRows
import app.worbus.ui.screens.home.freshShelf
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * B-287: "which fresh releases" was written four times (the Fresh page's freshOrdered, Home's
 * freshShelf, Discover's artistOwned-in-feed-order, the Discover stack's own filter). Discover's must
 * be the Fresh page's first releases under the same stored filters, as Home's shelf is (and as the
 * desktop's are since R18). Here with the defaults, over a feed lb-bot returned in another order.
 */
class DiscoverFreshMatchesShelfTest {
	private fun row(rg: String, date: String, owned: Boolean = true) = LbFreshRelease(
		releaseName = "R $rg", artist = "A $rg", releaseGroupMbid = rg, releaseDate = date, artistOwned = owned
	)

	private val feed = LbFreshFeed(
		releases = listOf(
			row("rg-old", "2026-10-14"),
			row("rg-new", "2026-11-08"),
			row("rg-unowned", "2026-11-09", owned = false),
			row("rg-mid", "2026-10-28")
		)
	)

	@Test
	fun discoverShowsWhatTheShelfShows() {
		assertEquals(
			freshShelf(feed, limit = 20).map { it.rgid },
			discoverFreshRows(feed, 20).map { it.releaseGroupMbid }
		)
	}
}
