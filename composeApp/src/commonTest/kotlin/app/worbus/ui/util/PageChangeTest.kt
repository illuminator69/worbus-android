package app.worbus.ui.util

import app.worbus.domain.models.settings.AnimationStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * B-403 (the owner's walk, 2026-11-13, the phone): page motion was too long on the tabs and leaving
 * Settings. The owner's ruling: Settings and its submenus use the regular slide, with no fade; the
 * tabs faster (or as upstream). B-378's guarantees stay: one back animation for the button, a quick
 * gesture and a predictive drag (OneBackAnimationTest), and the old page is never cut at the first
 * frame.
 */
class PageChangeTest {
	private val styles = AnimationStyle.entries

	@Test
	fun settingsSlidesInBetweenAndOut() {
		assertEquals(PageChange.Slide, pageChangeOf(PageKind.Other, PageKind.Settings), "into Settings")
		assertEquals(PageChange.Slide, pageChangeOf(PageKind.Settings, PageKind.Settings), "between its pages")
		assertEquals(PageChange.Slide, pageChangeOf(PageKind.Settings, PageKind.Other), "leaving Settings")
		assertEquals(PageChange.SharedAxis, pageChangeOf(PageKind.Other, PageKind.Other), "everything else keeps B-378's")
	}

	@Test
	fun anEntrySaysWhatPageItIsAndSilenceIsOther() {
		assertEquals(PageKind.Settings, pageKindOf(pageKindMetadata(PageKind.Settings) + mapOf("x" to 1)))
		assertEquals(PageKind.Other, pageKindOf(mapOf("x" to 1)))
		assertEquals(PageKind.Other, pageKindOf(null))
	}

	@Test
	fun theSlideIsShortAndNeverACut() {
		for (style in styles) {
			val m = pageMotionOf(style)
			assertTrue(m.slideMs in 150..300, "$style: the slide takes ${m.slideMs} ms")
		}
	}

	@Test
	fun aTabSwitchFadesThroughFasterThanAPagePush() {
		for (style in styles) {
			val m = pageMotionOf(style)
			val w = tabFadeWindows(m)
			assertEquals(0, w.exitStartMs, "$style: the old tab fades from the first frame")
			assertTrue(w.exitEndMs <= w.enterStartMs, "$style: the two fades never overlap")
			assertTrue(w.exitEndMs >= 50, "$style: never a cut (${w.exitEndMs} ms)")
			assertTrue(w.enterEndMs <= 300, "$style: a tab switch takes ${w.enterEndMs} ms")
			assertTrue(w.enterEndMs < pageFadeWindows(m).enterEndMs, "$style: shorter than a page push")
		}
	}
}

/*
 * B-404 (the owner's walk, 2026-11-13): the album <-> artist transition faded to white before the
 * colours switched. B-378's fade-through (W1, 87129261) fades the album page out over the first
 * 175 ms and the artist page in only after it, and both pages draw their own cover's colours over the
 * backdrop: between the two fades the backdrop showed. The owner: revert the fade for that change.
 */
class CoverPageChangeTest {
	@Test
	fun albumAndArtistPagesSlideBetweenEachOther() {
		assertEquals(PageChange.Slide, pageChangeOf(PageKind.Cover, PageKind.Cover))
	}

	@Test
	fun aCoverPageAndAnyOtherPageKeepTheSlideAndFade() {
		assertEquals(PageChange.SharedAxis, pageChangeOf(PageKind.Other, PageKind.Cover))
		assertEquals(PageChange.SharedAxis, pageChangeOf(PageKind.Cover, PageKind.Other))
		assertEquals(PageChange.Slide, pageChangeOf(PageKind.Cover, PageKind.Settings), "Settings' rule first")
	}
}

/*
 * B-404 review m4: a mix's page draws its own cover too (mix -> album dipped to the backdrop), and on a
 * tablet a list pane beside a cover page must not slide off with it.
 */
class CoverSceneTest {
	private val cover = pageKindMetadata(PageKind.Cover)
	private val list = mapOf<String, Any>("x" to 1)

	@Test
	fun aOnePaneCoverSceneIsACoverPage() {
		assertEquals(PageKind.Cover, pageKindOfScene(listOf(cover)))
		assertEquals(PageChange.Slide, pageChangeOf(pageKindOfScene(listOf(cover)), pageKindOfScene(listOf(cover))))
	}

	@Test
	fun aListBesideACoverPageKeepsTheSlideAndFade() {
		val twoPane = pageKindOfScene(listOf(list, cover))
		assertEquals(PageKind.Other, twoPane)
		assertEquals(PageChange.SharedAxis, pageChangeOf(twoPane, pageKindOfScene(listOf(cover))))
		assertEquals(PageKind.Settings, pageKindOfScene(listOf(list, pageKindMetadata(PageKind.Settings))), "Settings' two panes still slide")
	}
}
