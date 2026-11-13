package app.worbus.ui.util

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * B-404 review M1, M2, L5, L7: the wiring of the colour bridge into the cover pages, which the composable
 * engine's rules (CoverBridgeTest) cannot see.
 */
class CoverPagesWearWhatTheyShowTest {
	private fun src(path: String) = sourceFile("src/commonMain/kotlin/app/worbus/$path").readText()

	@Test
	fun aFailedFetchEndsTheBridge() {
		val engine = src("ui/util/CoverColorScheme.kt")
		assertTrue("fetchFailed = paletteKey != null && fetchFailedFor == paletteKey" in engine, "M1")
		assertTrue("wears = hasSeedSource" in engine, "L5: what the page wears is said")
	}

	@Test
	fun everyCoverPagePublishesOnlyWhatItWears() {
		for (page in listOf(
			"ui/screens/collection/CollectionDetailScreen.kt",
			"ui/screens/artist/ArtistDetailScreen.kt",
			"ui/screens/mixes/MixDetailScreen.kt",
			"ui/screens/external/ExternalAlbumScreen.kt"
		)) {
			val text = src(page)
			assertTrue("ambientHolder.publish(coverColors.publishedSeed(), coverColors.isDark)" in text, "$page publishes publishedSeed()")
			assertFalse("ambientHolder.publish(coverColors.seed" in text, "$page publishes a seed it may not wear")
		}
		// M1: the unowned album draws its wash only while it wears a colour; failed art is the app's surface.
		assertFalse("coverColors.themed" in src("ui/screens/external/ExternalAlbumScreen.kt"))
	}

	@Test
	fun anAlbumKeyWithNoCoverTakesNoBridgeAndAnArtistKeyWithOneNeither() {
		val album = src("ui/screens/collection/CollectionDetailScreen.kt")
		val albumCall = album.substringAfter("val coverColors = rememberCoverColorScheme(").substringBefore("\n\t)")
		assertFalse("coverPending" in albumCall, "M2: every opener names the cover; none named is none known")
		val artist = src("ui/screens/artist/ArtistDetailScreen.kt")
		assertTrue("coverPending = !artistRead && openedCoverArtId == null" in artist, "L7")
		assertTrue("pageCoverArtId(artistRead, artistState.data?.artist?.coverArtId, openedCoverArtId)" in artist, "L7")
	}
}
