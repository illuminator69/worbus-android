package app.worbus.ui.screens.attention

import app.worbus.domain.manager.AttentionCounts
import app.worbus.domain.manager.AttentionPage
import app.worbus.domain.manager.AttentionRead
import app.worbus.domain.manager.LbAttention
import app.worbus.domain.manager.attentionPage
import app.worbus.domain.manager.toFixSectionCount
import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * B-406 (the owner's re-walk ruling, 2026-11-13): "To fix" is a chip of Library Matches, and the separate
 * Needs attention page and its menu entry are gone. Nothing reachable before is lost:
 * - the folders and imports to file, the failed albums and the albums waiting on a pick, and this phone's
 *   own rows are the To fix chip's items (`toFixItems`);
 * - the artists to check are Library Matches' own To check chip, so the To fix section no longer repeats
 *   them as a row, and its "Downloaded, to file" count no longer counts them;
 * - the old Needs attention key (a saved back stack, a link) opens Library Matches on To fix.
 */
class ToFixArtistsRowCopyTest {
	private val section = sourceFile("src/commonMain/kotlin/app/worbus/ui/screens/attention/AttentionScreen.kt").readText()
	private val app = sourceFile("src/commonMain/kotlin/app/worbus/App.kt").readText()
	private val matches = sourceFile("src/commonMain/kotlin/app/worbus/ui/screens/matches/LibraryMatchesScreen.kt").readText()

	@Test
	fun theFilesGroupCountsImportsAndFoldersOnly() {
		val answer = LbAttention(version = 1, counts = AttentionCounts(imports = 2, folders = 3))
		val page = attentionPage(AttentionRead.Ok(answer), answer, 1L, artistsToCheck = 2) as AttentionPage.Rows
		assertEquals(2 + 3, toFixSectionCount(page))
	}

	@Test
	fun theSectionListsWhatThePageListedButTheArtists() {
		val items = section.substringAfter("fun LazyListScope.toFixItems(").substringBefore("\n}\n")
		for (part in listOf("AttentionHead(", "ImportsRowItem(", "FolderRow(", "openLb(\"needs/fix\")")) {
			assertTrue(part in items, "the To fix section lists $part")
		}
		// B-450: failed albums, picks and this phone's failures are Source history's.
		assertTrue("albumRows(" !in items && "AlbumRow(" !in section, "no album rows in To fix")
		assertTrue("artists-to-check" !in items && "Screen.LibraryMatches" !in items, "the artists to check are the To check chip")
		assertTrue("fun AttentionScreen(" !in section, "no page of its own")
	}

	@Test
	fun libraryMatchesHoldsItAndTheOldKeyOpensIt() {
		assertTrue("MatchesSection.TO_FIX -> toFix?.let { toFixItems(it) }" in matches)
		assertTrue("Washed { LibraryMatchesScreen(opening = MatchesSection.TO_FIX) }" in app)
	}
}
