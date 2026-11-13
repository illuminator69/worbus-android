package app.worbus.ui.screens.artist

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertTrue

/*
 * B-405 (the owner's walk, 2026-11-13): opening the full discography from an artist page stuttered. The
 * page was one scrolling Column holding every group's first 40 rows (DiscographyBrowser -> ReleaseRows),
 * all composed and measured, twice with the shared-transition lookahead, in the first frame of the
 * page's transition: one 300 to 850 ms frame on the VM AVD's release build (gfxinfo framestats, the UI
 * thread's own time; simpleperf put ReleaseRow and the lookahead measure under it). The page is a lazy
 * list now, so only the rows on screen are composed.
 */
class DiscographyLazyTest {
	private val page = sourceFile("src/commonMain/kotlin/app/worbus/ui/screens/artist/ArtistDiscographyScreen.kt").readText()
	private val rows = sourceFile("src/commonMain/kotlin/app/worbus/ui/screens/external/components/ReleaseRows.kt").readText()

	@Test
	fun theDiscographyPageIsALazyList() {
		assertTrue("LazyColumn(" in page && "discographyItems(" in page, "the page lists its rows lazily")
		assertTrue("verticalScroll(" !in page, "no scrolling Column composing every row")
		assertTrue("DiscographyBrowser(" !in page, "not the eager browser")
	}

	@Test
	fun lazyRowsAreItemsKeyedPerSection() {
		val lazy = rows.substringAfter("fun LazyListScope.releaseRowItems(")
		assertTrue("itemsIndexed(" in lazy, "each row is its own lazy item")
		assertTrue("key = { _, entry -> \"release:\${section.type}:\${entry.key}\" }" in lazy, "rows keyed by section and release")
	}
}
