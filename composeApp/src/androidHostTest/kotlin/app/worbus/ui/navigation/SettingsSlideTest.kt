package app.worbus.ui.navigation

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertTrue

/*
 * B-403 (the owner's ruling, 2026-11-13): Settings and its submenus use the regular slide, with no fade.
 * Every Settings entry says it is a Settings page, so going in, between its pages and leaving slide
 * (pageChangeOf); one entry that does not would run the slide and fade.
 */
class SettingsSlideTest {
	private val app = sourceFile("src/commonMain/kotlin/app/worbus/App.kt").readText()

	@Test
	fun everySettingsEntrySaysItIsASettingsPage() {
		val lines = app.lines().filter { "entry<Screen.Settings." in it }
		assertTrue(lines.size >= 15, "the Settings entries are found: ${lines.size}")
		val untagged = lines.filter { "settingsPage" !in it }
		assertTrue(untagged.isEmpty(), "Settings entries without settingsPage:\n${untagged.joinToString("\n")}")
		assertTrue("val settingsPage = pageKindMetadata(PageKind.Settings)" in app)
	}

	@Test
	fun theThreeSpecsAskBothPages() {
		val specs = app.substringAfter("\t\t\t\t\t\t\t\t\ttransitionSpec = {").substringBefore("\n\t\t\t\t\t\t\t\t)")
		val asked = Regex("""pageChangeOf\(initialState\.pageKind\(\), targetState\.pageKind\(\)\)""").findAll(specs).count()
		assertTrue(asked == 3, "push, pop and predictive pop each ask both pages ($asked):\n$specs")
	}
}

/* B-404: the album and artist pages, owned and not, say they are cover pages (pageChangeOf slides between them). */
class CoverPageEntriesTest {
	private val app = sourceFile("src/commonMain/kotlin/app/worbus/App.kt").readText()

	@Test
	fun albumAndArtistEntriesAreCoverPages() {
		for (screen in listOf("CollectionDetail", "ArtistDetail", "ExternalAlbum", "ExternalArtist")) {
			val line = app.lines().single { "entry<Screen.$screen>" in it }
			assertTrue("coverPage" in line, line)
		}
		assertTrue("val coverPage = pageKindMetadata(PageKind.Cover)" in app)
	}
}
