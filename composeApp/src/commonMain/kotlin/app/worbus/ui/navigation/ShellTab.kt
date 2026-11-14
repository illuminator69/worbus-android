package app.worbus.ui.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.title_home
import worbus.composeapp.generated.resources.title_library
import worbus.composeapp.generated.resources.title_new
import worbus.composeapp.generated.resources.title_search
import org.jetbrains.compose.resources.StringResource
import app.worbus.domain.manager.ShellController
import app.worbus.icons.Icons
import app.worbus.icons.filled.Home
import app.worbus.icons.filled.LibraryMusic
import app.worbus.icons.outlined.Add
import app.worbus.icons.outlined.Home
import app.worbus.icons.outlined.LibraryMusic
import app.worbus.icons.outlined.Search
import app.worbus.ui.viewmodel.RootViewModel

/**
 * navi-connect P1: the bar's four fixed buttons, in the order they are drawn, on the phone's bar and
 * the tablet's rail alike. It replaces the user-arranged list of [NavigationTab]s, which the
 * Library home now reaches (Albums, Playlists, Artists) and the avatar menu holds the rare tools of.
 *
 * [New] is not a page: it has no [destination], is never selected and opens a sheet.
 */
enum class ShellTab(
	val destination: Screen?,
	val iconFilled: ImageVector,
	val iconOutlined: ImageVector,
	val label: StringResource
) {
	Home(Screen.Home, Icons.Filled.Home, Icons.Outlined.Home, Res.string.title_home),
	Search(Screen.Search(), Icons.Outlined.Search, Icons.Outlined.Search, Res.string.title_search),
	Library(Screen.Library(), Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic, Res.string.title_library),
	New(null, Icons.Outlined.Add, Icons.Outlined.Add, Res.string.title_new)
}

/** Whether [top], the page on top of the back stack, is this tab's own page. A pushed page selects none. */
fun ShellTab.isSelected(top: NavKey?): Boolean = destination != null && destination == top

/** What a tap on a button does. */
sealed interface ShellTap {
	/** The tab is already showing: take its list back to the top. */
	data object ScrollToTop : ShellTap

	/** Go to the tab's page, with Home under it ([stackAfterTap]). */
	data class Navigate(val destination: Screen) : ShellTap

	/** Open the New sheet over whatever is showing; the stack and the selected tab stay as they are. */
	data object OpenNew : ShellTap
}

fun shellTap(tab: ShellTab, top: NavKey?): ShellTap {
	val destination = tab.destination ?: return ShellTap.OpenNew
	return if (tab.isSelected(top)) ShellTap.ScrollToTop else ShellTap.Navigate(destination)
}

/**
 * The stack a tab tap leaves (P6, the owner's ruling of 2026-10-21: Back from Search or Library goes
 * Home, and only Home leaves the app). Home is `[Home]`; Search and Library are `[Home, tab]`, so the
 * system's own pop (and its predictive animation) takes Back from them to Home, and Home keeps its
 * place underneath. A tap from a page pushed over a tab is the tab's own page again. Null for New,
 * which never touches the stack.
 */
fun stackAfterTap(current: List<NavKey>, tab: ShellTab): List<NavKey>? {
	val destination = tab.destination ?: return null
	return if (destination == Screen.Home) listOf(Screen.Home) else listOf(Screen.Home, destination)
}

/**
 * The stack the Library's magnifier leaves: the Search tab's tap, `[Home, Search]`, not a push over
 * the Library (`[Home, Library, Search]` landed Back on the Library: audit L2-04, W-02). Back from
 * Search goes Home, the owner's ruling for every way into it.
 */
fun searchEntryStack(current: List<NavKey>): List<NavKey> = stackAfterTap(current, ShellTab.Search)!!

/**
 * A stack saved before P6 (a tab tap left the tab alone) or by anything that still does: one whose
 * first entry is Search's or the Library's own page gets Home put under it, once, so Back from it goes
 * Home too. Any other stack (Home's, a login, a shared link's) is unchanged.
 */
fun normalizedStack(stack: List<NavKey>): List<NavKey> {
	val first = stack.firstOrNull() ?: return stack
	val tabFirst = first != Screen.Home && ShellTab.entries.any { it.destination == first }
	// A Home further up means the stack was built with one already: giving it another at the bottom is
	// the `[Home, Search, Home]` shape of B-180.
	return if (tabFirst && Screen.Home !in stack) listOf<NavKey>(Screen.Home) + stack else stack
}

/**
 * Whether [top] is a tab's own page (Home, Search, the Library): a root for the page transitions. A
 * pushed page is not, and neither is a nested Search (`nested = true` is not the tab's page). It
 * replaces "the stack holds one entry", which stopped meaning "a root page" when Home stayed under
 * Search and the Library.
 */
fun isRootPage(top: NavKey?): Boolean = top != null && ShellTab.entries.any { it.destination == top }

/**
 * Makes [backStack] equal [target] by the smallest edits: the entries both share at the start stay
 * (Home's among them, with its saved state, its arrival gate and its ViewModel), the rest is removed
 * from the top down and the remainder added. Never `clear()` then re-add.
 */
fun MutableList<NavKey>.becomeStack(target: List<NavKey>) {
	var keep = 0
	while (keep < size && keep < target.size && this[keep] == target[keep]) keep++
	while (size > keep) removeAt(lastIndex)
	target.drop(keep).forEach { add(it) }
}

/**
 * The Library chip request ([ShellController.libraryChip]) a tab tap leaves: only the Library's tap
 * keeps it. The custom radio's Done arms it and lands on `[Home, Library, MixDetail]`; leaving by Home
 * or Search no longer leaves it armed for the next Library visit (audit L2-07). New opens a sheet over
 * the page and goes nowhere, so it keeps the request too.
 */
fun chipRequestAfterTap(pending: String?, tab: ShellTab): String? =
	if (tab == ShellTab.Library || tab == ShellTab.New) pending else null

/**
 * The one tap handler for the bar and the rail, so they cannot disagree. A tab switch leaves Home
 * under Search and the Library ([stackAfterTap]); every root page's ViewModel that must outlive a
 * switch still opts out of the entry's store (CLAUDE.md §2 rule 14).
 */
fun onShellTabTapped(
	tab: ShellTab,
	backStack: NavBackStack<NavKey>,
	rootViewModel: RootViewModel,
	shellController: ShellController,
	// B-434: this activity's ([LocalTabTapMotion]).
	tabTaps: TabTapMotion
) {
	shellController.onTabTapped(tab)
	when (shellTap(tab, backStack.lastOrNull())) {
		ShellTap.ScrollToTop -> rootViewModel.requestScrollToTop()
		is ShellTap.Navigate -> stackAfterTap(backStack, tab)?.let {
			// B-434: the page change this makes is a tab switch, whichever way NavDisplay reads it.
			tabTaps.tapped(it)
			backStack.becomeStack(it)
		}
		ShellTap.OpenNew -> shellController.openNew()
	}
}

/**
 * B-434 (motion lane, 2026-11-14): a tap on the bar is a tab switch, and it fades through (Ruling 7) whatever page
 * it leaves. NavDisplay takes a push's motion from the page it goes to and a pop's from the page it leaves, and the
 * tab fade-through was the tab pages' own (`navtabMetadata` in `App.kt`). A tap that only POPS, Home from an album
 * opened from Home (`[Home, Album]` to `[Home]`), left a page with no such motion and ran the album slide, while
 * Search from the same page (a push onto Search's page) faded through: one gesture, two motions.
 *
 * So the tap marks the stack it makes ([tapped]), `App.kt`'s NavDisplay runs the fade-through for a change to
 * exactly that stack ([isTabTap]), and the mark goes with the next change that is not the tap's ([stackChanged]):
 * Back to the same stack later is the ordinary pop again.
 */
@Stable
class TabTapMotion {
	// Snapshot state, so App.kt can wait out the tap's transition from the moment it is marked (review L5).
	private var tapped by mutableStateOf<List<NavKey>?>(null)

	/** The stack the last tap made, while its page change is still the tap's; null otherwise. */
	val mark: List<NavKey>? get() = tapped

	fun tapped(stack: List<NavKey>) {
		tapped = stack.toList()
	}

	/** Whether the page change to [stack] is the bar tap's. */
	fun isTabTap(stack: List<NavKey>): Boolean = tapped.let { it != null && it == stack.toList() }

	/** The stack changed to [stack]; a change that is not the tap's ends the mark. */
	fun stackChanged(stack: List<NavKey>) {
		if (tapped != stack.toList()) tapped = null
	}

	/**
	 * Motion lane review L5 (2026-11-14): the tap to [stack] has had its transition (`awaitTabTapTransition`), so the
	 * mark ends. It lived until the stack changed again, and NavDisplay also re-runs its transition on the SAME stack
	 * when the scene changes (a multi-window resize, a fold, a rotation the activity handles): that read the mark and
	 * faded through instead of running the page or pane motion. A newer tap's mark is left alone.
	 */
	fun settled(stack: List<NavKey>) {
		if (tapped == stack) tapped = null
	}
}

/**
 * The activity's [TabTapMotion] (review L5: one per activity, provided by `App.kt`, where it was the
 * [ShellController] single's and two live activities, the B-181 shape, shared one mark). Outside `App` a fresh one,
 * which no transition reads.
 */
val LocalTabTapMotion = staticCompositionLocalOf { TabTapMotion() }
