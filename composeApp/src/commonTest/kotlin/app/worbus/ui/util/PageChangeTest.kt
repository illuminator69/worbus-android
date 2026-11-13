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
	fun theSlideIsNeverACutAndNeverLongerThanAPagePush() {
		// B-403 made it 300 ms (Quick 250); I-075 (the owner, 2026-11-13 evening): Quick is that 300 ms, and
		// Expressive and Standard are back to `bd39384d`'s page change, 500 ms.
		for (style in styles) {
			val m = pageMotionOf(style)
			assertTrue(m.slideMs in 150..m.enterSlideMs, "$style: the slide takes ${m.slideMs} ms")
		}
		assertEquals(300, pageMotionOf(AnimationStyle.Quick).slideMs)
		assertEquals(500, pageMotionOf(AnimationStyle.Expressive).slideMs)
	}

	@Test
	fun aTabSwitchFadesThroughFasterThanAPagePush() {
		for (style in styles) {
			val m = pageMotionOf(style)
			val w = tabFadeWindows(m)
			assertEquals(0, w.exitStartMs, "$style: the old tab fades from the first frame")
			assertTrue(w.exitEndMs <= w.enterStartMs, "$style: the two fades never overlap")
			assertTrue(w.exitEndMs >= 50, "$style: never a cut (${w.exitEndMs} ms)")
			assertTrue(w.enterEndMs <= 330, "$style: a tab switch takes ${w.enterEndMs} ms")
			assertTrue(w.enterEndMs < pageFadeWindows(m).enterEndMs, "$style: shorter than a page push")
		}
	}
}

/*
 * B-404 (the owner's walk, 2026-11-13): the album <-> artist transition faded to white before the
 * colours switched (B-378's fade-through left neither page drawn). The re-walk (2026-11-13, `011b738d`):
 * opening an album from the artist page (cover to cover: the regular slide, 300 ms, EaseOutQuart) and
 * from the full discography (a washed page to a cover page: B-378's slide and fade, 500 ms, Emphasized)
 * were two motions. The owner's ruling: everywhere this album slide runs, the artist page's speed with the
 * discography's easing.
 */
class CoverPageChangeTest {
	@Test
	fun everyWayIntoOrOutOfACoverPageIsTheOneAlbumSlide() {
		assertEquals(PageChange.CoverSlide, pageChangeOf(PageKind.Cover, PageKind.Cover), "artist page -> album")
		assertEquals(PageChange.CoverSlide, pageChangeOf(PageKind.Other, PageKind.Cover), "discography, library, search, home -> album")
		assertEquals(PageChange.CoverSlide, pageChangeOf(PageKind.Cover, PageKind.Other), "album -> any other page, and Back")
		assertEquals(PageChange.Slide, pageChangeOf(PageKind.Cover, PageKind.Settings), "Settings' rule first")
		assertEquals(PageChange.SharedAxis, pageChangeOf(PageKind.Other, PageKind.Other), "no cover page: B-378's")
	}

	@Test
	fun theAlbumSlideTakesTheArtistPagesTimeAndTheDiscographysEasing() {
		for (style in AnimationStyle.entries) {
			val m = pageMotionOf(style)
			val timing = coverSlideTiming(m)
			// The artist page's slide (the regular slide's time) ...
			assertEquals(m.slideMs, timing.durationMs, "$style: time")
			// ... on the discography page's curve (the incoming page's easing of B-378's slide and fade).
			assertEquals(m.enterEasing, timing.easing, "$style: easing")
		}
		// I-075: Quick is the 300 ms the owner saw; Expressive is `bd39384d`'s 500 ms page change.
		assertEquals(SlideTiming(500, PageEasing.Emphasized), coverSlideTiming(pageMotionOf(AnimationStyle.Expressive)))
		assertEquals(SlideTiming(300, PageEasing.Emphasized), coverSlideTiming(pageMotionOf(AnimationStyle.Quick)))
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
		assertEquals(PageChange.CoverSlide, pageChangeOf(pageKindOfScene(listOf(cover)), pageKindOfScene(listOf(cover))))
	}

	/*
	 * B-404 review L6: in landscape (two panes on a phone too) the album beside its list -> the artist page ran
	 * the 500 ms slide and fade while artist -> album ran the album slide: the fade-through between album and
	 * artist the owner had ruled out. To and from a one-pane page it is the album slide, as in portrait; two
	 * two-pane scenes keep the slide and fade (the list pane is a list on both sides).
	 */
	@Test
	fun aListBesideACoverPageTakesTheAlbumSlideToAndFromAOnePanePage() {
		val twoPane = pageKindOfScene(listOf(list, cover))
		assertEquals(PageKind.Panes, twoPane)
		assertEquals(PageChange.CoverSlide, pageChangeOf(twoPane, pageKindOfScene(listOf(cover))), "album beside Home -> artist page")
		assertEquals(PageChange.CoverSlide, pageChangeOf(pageKindOfScene(listOf(cover)), twoPane), "and Back")
		assertEquals(PageChange.CoverSlide, pageChangeOf(twoPane, pageKindOfScene(listOf(list))), "album beside Home -> a list page")
		assertEquals(PageChange.SharedAxis, pageChangeOf(twoPane, twoPane), "two two-pane scenes")
		assertEquals(PageChange.Slide, pageChangeOf(twoPane, PageKind.Settings), "Settings' rule first")
		assertEquals(PageKind.Settings, pageKindOfScene(listOf(list, pageKindMetadata(PageKind.Settings))), "Settings' two panes still slide")
	}
}
