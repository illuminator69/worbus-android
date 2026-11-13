package app.worbus.ui.navigation

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertTrue

/*
 * B-404 (the owner's re-walk, 2026-11-13): the album page's colours on its first frame. A wiring check: the
 * openers that hold the album (or playlist) they open pass its cover with the key, so the page reads the
 * seed store in composition before Room has given it the album. The behaviour is CoverBridgeTest's.
 */
class AlbumOpenersCarryTheCoverTest {
	private val openers = listOf(
		"ui/screens/album/components/GridItem.kt",
		"ui/screens/album/components/ListItem.kt",
		"ui/screens/artist/ArtistDetailScreen.kt",
		"ui/screens/collection/components/MoreByArtistRow.kt",
		"ui/screens/stats/StatisticsScreen.kt",
		"ui/screens/playlist/components/GridItem.kt",
		"ui/screens/playlist/components/ListItem.kt",
		"ui/screens/library/LibraryScreen.kt",
		"ui/screens/nowPlaying/components/controls/MoreButton.kt"
	)

	@Test
	fun everyOpenerThatHoldsTheAlbumPassesItsCover() {
		for (path in openers) {
			val text = sourceFile("src/commonMain/kotlin/app/worbus/$path").readText()
			val calls = text.lines().filter { "Screen.CollectionDetail(" in it }
			assertTrue(calls.isNotEmpty(), "$path opens an album")
			val bare = calls.filter { "coverArtId =" !in it }
			assertTrue(bare.isEmpty(), "$path opens an album without its cover:\n${bare.joinToString("\n")}")
		}
		val discography = sourceFile("src/commonMain/kotlin/app/worbus/ui/screens/artist/DiscographyOpen.kt").readText()
		assertTrue("coverArtId = entry.album.coverArtId" in discography, "the full discography's rows")
		val search = sourceFile("src/commonMain/kotlin/app/worbus/ui/screens/search/SearchScreen.kt").readText()
		assertTrue("onAlbum = { backStack.add(Screen.CollectionDetail(it.id, \"search\", coverArtId = it.coverArtId)) }" in search, "search's albums")
	}

	@Test
	fun theAlbumEntryHandsTheCoverToThePage() {
		val app = sourceFile("src/commonMain/kotlin/app/worbus/App.kt").readText()
		assertTrue("CollectionDetailScreen(key.collectionId, key.tab, key.openGaps, key.coverArtId)" in app)
	}

	@Test
	fun everyWashedPageSaysWhatItWearsForTheNextPage() {
		val ambient = sourceFile("src/commonMain/kotlin/app/worbus/ui/components/common/CoverAmbientBackground.kt").readText()
		val browsing = ambient.substringAfter("fun BrowsingAmbient(").substringBefore("\n}\n")
		assertTrue("ambientHolder.publish(" in browsing, "BrowsingAmbient publishes its wash")
	}
}
