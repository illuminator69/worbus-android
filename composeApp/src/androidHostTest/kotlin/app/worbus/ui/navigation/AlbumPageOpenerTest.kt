package app.worbus.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * B-404 review M2: an opener that holds only an album's id (an lb-bot row, a Fresh tile, a pending-sync
 * discography row, a re-match, the widget's link) asks Room for the cover before it pushes the page, so
 * the page reads the seed store in its first frame.
 */
class AlbumPageOpenerTest {
	@Test
	fun anIdAloneOpensWithRoomsCover() = runBlocking {
		val pushed = mutableListOf<NavKey>()
		val opener = AlbumPageOpener(coverOf = { "al-$it" }, scope = this)
		opener.open("x", "attention", openGaps = true) { pushed += it }
		delay(50)
		assertEquals(listOf<NavKey>(Screen.CollectionDetail("x", "attention", openGaps = true, coverArtId = "al-x")), pushed)
	}

	@Test
	fun noCoverInRoomIsNoCoverAndAHungReadStillOpensThePage() = runBlocking {
		val pushed = mutableListOf<NavKey>()
		AlbumPageOpener(coverOf = { null }, scope = this).open("x", "", false) { pushed += it }
		AlbumPageOpener(coverOf = { awaitCancellation() }, scope = this).open("y", "", false) { pushed += it }
		delay(AlbumPageOpener.LOOKUP_MS + 200)
		assertEquals(listOf<NavKey>(Screen.CollectionDetail("x", "", coverArtId = null), Screen.CollectionDetail("y", "", coverArtId = null)), pushed)
	}

	@Test
	fun aSecondTapWhileTheFirstLooksIsTheSameOpen() = runBlocking {
		val pushed = mutableListOf<NavKey>()
		val opener = AlbumPageOpener(coverOf = { delay(30); "c" }, scope = this)
		opener.open("x", "", false) { pushed += it }
		opener.open("x", "", false) { pushed += it }
		delay(100)
		assertEquals(1, pushed.size)
		opener.open("x", "", false) { pushed += it }
		delay(100)
		assertEquals(2, pushed.size, "a later tap opens again")
	}

	@Test
	fun aKeyThatNamesItsCoverIsPushedAsItIs() = runBlocking {
		val pushed = mutableListOf<NavKey>()
		val opener = AlbumPageOpener(coverOf = { "room" }, scope = this)
		opener.openKey(Screen.CollectionDetail("x", "", coverArtId = "tile")) { pushed += it }
		opener.openKey(Screen.ArtistDetail("a")) { pushed += it }
		opener.openKey(Screen.CollectionDetail("y", "", coverArtId = null)) { pushed += it }
		delay(50)
		assertEquals(
			listOf<NavKey>(Screen.CollectionDetail("x", "", coverArtId = "tile"), Screen.ArtistDetail("a"), Screen.CollectionDetail("y", "", coverArtId = "room")),
			pushed
		)
	}
}
