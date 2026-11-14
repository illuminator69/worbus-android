package app.worbus.ui.components.sheets

import app.worbus.domain.manager.LbDownloads
import app.worbus.domain.manager.attentionDotShown
import app.worbus.domain.manager.sourceHistoryMenuBadge
import app.worbus.ui.screens.wishlist.sourceHistoryShowsWishlist
import app.worbus.ui.navigation.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * P1, then P6: the avatar menu holds what the four-button bar does not, in the board's order: the
 * groups, then Settings, the server's Shares where the user may make them, and Log out last. Since P6
 * lb-bot's operator half is Library tools: Needs attention (with lb-bot's count), Source history,
 * Library Matches, then the rare tools that open lb-bot's web UI, drawn only when the hub says where
 * it is. Nothing that needs lb-bot is drawn without it, with no gap.
 */
class AvatarMenuItemsTest {

	private fun menu(lb: Boolean = true, share: Boolean = true, attention: Int = 0, webUrl: String = "", downloadsOn: Boolean = true) =
		avatarMenuItems(lbConfigured = lb, canShare = share, attentionCount = attention, webUrl = webUrl, downloadsOn = downloadsOn)

	// ---- Worbus P1: downloading off ------------------------------------------ //

	@Test
	fun avatar_menu_hides_source_rules_when_off() {
		val tools = menu(webUrl = "http://lb:8899", downloadsOn = false).group(AvatarMenuIds.LIBRARY_TOOLS).entries.ids()
		assertFalse(AvatarMenuIds.SOURCE_RULES in tools)
		// Source history is requests and transfers, and its name says "source": it goes too.
		assertFalse(AvatarMenuIds.SOURCE_HISTORY in tools)
		// What needs no downloading stays, in its order, with no gap.
		assertEquals(
			listOf(
				AvatarMenuIds.LIBRARY_MATCHES, AvatarMenuIds.WISHLIST,
				AvatarMenuIds.DUPLICATES, AvatarMenuIds.LOGS
			),
			tools
		)
	}

	@Test
	fun avatar_menu_hides_source_rules_when_off_unchanged_when_on() {
		val tools = menu(webUrl = "http://lb:8899", downloadsOn = true).group(AvatarMenuIds.LIBRARY_TOOLS).entries.ids()
		assertEquals(
			listOf(
				AvatarMenuIds.LIBRARY_MATCHES, AvatarMenuIds.SOURCE_HISTORY, AvatarMenuIds.WISHLIST,
				AvatarMenuIds.DUPLICATES, AvatarMenuIds.SOURCE_RULES, AvatarMenuIds.LOGS
			),
			tools
		)
	}

	private fun List<AvatarMenuEntry>.flat(): List<AvatarMenuEntry> =
		flatMap { if (it is AvatarMenuEntry.Group) listOf(it) + it.entries.flat() else listOf(it) }

	private fun List<AvatarMenuEntry>.ids() = map { it.id }

	private fun List<AvatarMenuEntry>.group(id: String) =
		filterIsInstance<AvatarMenuEntry.Group>().first { it.id == id }

	private fun List<AvatarMenuEntry>.link(id: String) =
		flat().filterIsInstance<AvatarMenuEntry.Link>().first { it.id == id }

	private fun List<AvatarMenuEntry>.external(id: String) =
		flat().filterIsInstance<AvatarMenuEntry.External>().first { it.id == id }

	@Test
	fun wishlist_in_avatar_menu_both_modes() {
		// Worbus P1: the wishlist has its own row beside the library tools, whatever the mode, and opens
		// its own page (it was reachable only from Source history, a download surface).
		for (on in listOf(true, false)) {
			val link = menu(downloadsOn = on).link(AvatarMenuIds.WISHLIST)
			assertEquals(Screen.Wishlist, link.destination)
			assertTrue(AvatarMenuIds.WISHLIST in menu(downloadsOn = on).group(AvatarMenuIds.LIBRARY_TOOLS).entries.ids())
		}
		// Like every lb-bot row, not without lb-bot.
		assertFalse(AvatarMenuIds.WISHLIST in menu(lb = false).flat().ids())
	}

	@Test
	fun withoutLbBotThereIsNoLibraryToolsAtAll() {
		val all = menu(lb = false, attention = 4, webUrl = "http://lb:8899").flat().ids()
		listOf(
			AvatarMenuIds.ATTENTION, AvatarMenuIds.SOURCE_HISTORY, AvatarMenuIds.LIBRARY_TOOLS,
			AvatarMenuIds.LIBRARY_MATCHES, AvatarMenuIds.DUPLICATES, AvatarMenuIds.SOURCE_RULES,
			AvatarMenuIds.LOGS, AvatarMenuIds.WISHLIST
		).forEach { assertFalse(it in all, "$it should be absent without lb-bot") }
		// ...and what is left has no gap: the groups and rows that need nothing are all still there.
		assertEquals(
			listOf(
				AvatarMenuIds.LISTENING, AvatarMenuIds.APP, AvatarMenuIds.SETTINGS,
				AvatarMenuIds.SHARES, AvatarMenuIds.LOG_OUT
			),
			menu(lb = false).ids()
		)
	}

	@Test
	fun attentionAndSourceHistoryLeaveTheTopForLibraryTools() {
		val top = menu().ids()
		assertFalse(AvatarMenuIds.ATTENTION in top)
		assertFalse(AvatarMenuIds.SOURCE_HISTORY in top)
		assertEquals(
			listOf(AvatarMenuIds.LIBRARY_MATCHES, AvatarMenuIds.SOURCE_HISTORY, AvatarMenuIds.WISHLIST),
			menu().group(AvatarMenuIds.LIBRARY_TOOLS).entries.ids()
		)
	}

	@Test
	fun theRareToolsAreThereOnlyWithAWebUrlInOrder() {
		assertEquals(
			listOf(
				AvatarMenuIds.LIBRARY_MATCHES, AvatarMenuIds.SOURCE_HISTORY, AvatarMenuIds.WISHLIST,
				AvatarMenuIds.DUPLICATES, AvatarMenuIds.SOURCE_RULES, AvatarMenuIds.LOGS
			),
			menu(webUrl = "http://lb:8899").group(AvatarMenuIds.LIBRARY_TOOLS).entries.ids()
		)
		assertEquals(3, menu(webUrl = "  ").group(AvatarMenuIds.LIBRARY_TOOLS).entries.size)
		val items = menu(webUrl = "http://lb:8899")
		assertEquals("library/cleanup", items.external(AvatarMenuIds.DUPLICATES).path)
		assertEquals("system/sources", items.external(AvatarMenuIds.SOURCE_RULES).path)
		assertEquals("system/logs", items.external(AvatarMenuIds.LOGS).path)
	}

	/*
	 * B-406 (the owner's re-walk ruling, 2026-11-13): "Needs attention" felt like a redundant menu; its list is
	 * Library Matches' To fix chip. Library Matches takes its place at the top of Library tools, with its count.
	 */
	@Test
	fun needsAttentionIsNoMenuOfItsOwnLibraryMatchesCarriesItsCount() {
		assertFalse(AvatarMenuIds.ATTENTION in menu(attention = 20).flat().ids(), "no Needs attention row")
		assertEquals(20, menu(attention = 20).link(AvatarMenuIds.LIBRARY_MATCHES).badge)
		assertEquals(1, menu(attention = 1).link(AvatarMenuIds.LIBRARY_MATCHES).badge)
		assertEquals(AvatarMenuIds.LIBRARY_MATCHES, menu().group(AvatarMenuIds.LIBRARY_TOOLS).entries.first().id)
	}

	/*
	 * B-406, the owner's second re-walk (2026-11-13 evening): count as the desktop does. Library Matches'
	 * badge is the desktop's to-fix number (imports + folders); this phone's own failures and picks are
	 * Source history's badge, as they are the desktop's Activity badge.
	 */
	@Test
	fun sourceHistoryCarriesThisPhonesOwnFailuresAsTheDesktopsActivity() {
		val items = avatarMenuItems(lbConfigured = true, canShare = true, attentionCount = 41, sourceHistoryCount = 3)
		assertEquals(41, items.link(AvatarMenuIds.LIBRARY_MATCHES).badge)
		assertEquals(3, items.link(AvatarMenuIds.SOURCE_HISTORY).badge)
		// B-457 (the owner's ruling, 2026-11-14): off, Source history's page is the Wishlist (B-450 moved the failed
		// albums to Need you, which it does not show), so the row and its badge go, badged or not. Review L3 had kept it.
		for (count in listOf(0, 3)) {
			val off = avatarMenuItems(lbConfigured = true, canShare = true, attentionCount = 0, downloadsOn = false, sourceHistoryCount = count)
			assertFalse(AvatarMenuIds.SOURCE_HISTORY in off.flat().ids(), "off with $count badged: no Source history row")
		}
	}

	/** B-457: the avatar's dot stands for the menu's badges, so a Source history row that is not drawn lights nothing. */
	@Test
	fun b457_theDotDoesNotCountAHiddenSourceHistory() {
		assertEquals(3, sourceHistoryMenuBadge(3, configured = true, downloads = LbDownloads.ON))
		assertEquals(3, sourceHistoryMenuBadge(3, configured = true, downloads = LbDownloads.UNKNOWN))
		assertEquals(0, sourceHistoryMenuBadge(3, configured = true, downloads = LbDownloads.OFF))
		assertEquals(0, sourceHistoryMenuBadge(3, configured = false, downloads = LbDownloads.ON))
		assertFalse(attentionDotShown(0 + sourceHistoryMenuBadge(2, true, LbDownloads.OFF), preference = true, configured = true))
		// The rule the menu's row follows is the page's: hidden exactly while Source history would be the Wishlist.
		for (d in LbDownloads.entries) assertEquals(sourceHistoryShowsWishlist(d), sourceHistoryMenuBadge(1, true, d) == 0, "$d")
	}

	@Test
	fun nothingNeedingAttentionShowsNoBadgeRatherThanAZero() {
		assertNull(menu(attention = 0).link(AvatarMenuIds.LIBRARY_MATCHES).badge)
		assertNull(menu(attention = 0).link(AvatarMenuIds.SOURCE_HISTORY).badge)
	}

	@Test
	fun sharesOnlyWhenAllowed() {
		assertTrue(AvatarMenuIds.SHARES in menu(share = true).ids())
		assertFalse(AvatarMenuIds.SHARES in menu(share = false).ids())
		assertFalse(AvatarMenuIds.SHARES in menu(lb = false, share = false).flat().ids())
	}

	@Test
	fun theGroupsAreInTheBoardsOrder() {
		assertEquals(
			listOf(
				AvatarMenuIds.LISTENING, AvatarMenuIds.LIBRARY_TOOLS, AvatarMenuIds.APP,
				AvatarMenuIds.SETTINGS, AvatarMenuIds.SHARES, AvatarMenuIds.LOG_OUT
			),
			menu().ids()
		)
		val items = menu()
		assertEquals(
			listOf(AvatarMenuIds.STATISTICS, AvatarMenuIds.SAVED_QUEUES),
			items.group(AvatarMenuIds.LISTENING).entries.ids()
		)
		assertEquals(
			listOf(
				AvatarMenuIds.AUTOPLAY, AvatarMenuIds.DOWNLOADS, AvatarMenuIds.APPEARANCE,
				AvatarMenuIds.ATTENTION_DOT, AvatarMenuIds.ABOUT
			),
			items.group(AvatarMenuIds.APP).entries.ids()
		)
	}

	@Test
	fun logOutIsLast() {
		assertEquals(AvatarMenuIds.LOG_OUT, menu(lb = true, share = true).last().id)
		assertEquals(AvatarMenuIds.LOG_OUT, menu(lb = false, share = false).last().id)
		assertIs<AvatarMenuEntry.Action>(menu().last())
	}

	@Test
	fun everyRowGoesWhereTheBoardSays() {
		val items = menu()
		assertEquals(Screen.SourceHistory(), items.link(AvatarMenuIds.SOURCE_HISTORY).destination)
		assertEquals(Screen.Statistics(nested = true), items.link(AvatarMenuIds.STATISTICS).destination)
		assertEquals(Screen.SavedQueues, items.link(AvatarMenuIds.SAVED_QUEUES).destination)
		assertEquals(Screen.LibraryMatches, items.link(AvatarMenuIds.LIBRARY_MATCHES).destination)
		assertEquals(Screen.Downloads(), items.link(AvatarMenuIds.DOWNLOADS).destination)
		assertEquals(Screen.Settings.Appearance, items.link(AvatarMenuIds.APPEARANCE).destination)
		assertEquals(Screen.Settings.About, items.link(AvatarMenuIds.ABOUT).destination)
		assertEquals(Screen.Settings.Root, items.link(AvatarMenuIds.SETTINGS).destination)
		assertEquals(Screen.ShareList, items.link(AvatarMenuIds.SHARES).destination)
	}

	@Test
	fun theTwoSwitchesAreToggles() {
		val app = menu().group(AvatarMenuIds.APP).entries
		assertIs<AvatarMenuEntry.Toggle>(app.first { it.id == AvatarMenuIds.AUTOPLAY })
		assertIs<AvatarMenuEntry.Toggle>(app.first { it.id == AvatarMenuIds.ATTENTION_DOT })
	}

	@Test
	fun listeningNeverFoldsLibraryToolsStartOpenAndAppStartsClosed() {
		val items = menu()
		assertEquals(GroupFold.Never, items.group(AvatarMenuIds.LISTENING).fold)
		assertEquals(GroupFold.StartsOpen, items.group(AvatarMenuIds.LIBRARY_TOOLS).fold)
		assertEquals(GroupFold.StartsClosed, items.group(AvatarMenuIds.APP).fold)
	}

	@Test
	fun everyIdIsUnique() {
		val all = menu(webUrl = "http://lb:8899").flat().ids()
		assertEquals(all.size, all.toSet().size)
	}
}
