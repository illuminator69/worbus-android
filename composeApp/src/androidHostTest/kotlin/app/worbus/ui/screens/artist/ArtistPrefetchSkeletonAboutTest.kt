package app.worbus.ui.screens.artist

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * B-446 follow-up (the owner, 2026-11-14), the wiring around ArtistPagePrefetchTest and AboutTeaserTest (the
 * pages need Room, Koin and a NavDisplay):
 * - every cold artist page opened on a spinner: the album page now reads its artist's page ahead, after its
 *   own slide, with the artist page's own read, and a page with nothing to open on shows its own shape;
 * - the album page's About "pops in", the rows below jumping: it folds in, as the gaps box does.
 */
class ArtistPrefetchSkeletonAboutTest {
	private fun src(path: String) = sourceFile("src/commonMain/kotlin/app/worbus/$path").readText()

	@Test
	fun theAlbumPageReadsItsArtistAheadOnlyOnceItsSlideHasEnded() {
		val album = src("ui/screens/collection/CollectionDetailScreen.kt")
		assertTrue("PrefetchArtistPage(" in album, "the album page does not read its artist ahead")
		assertTrue("artistId = (collection as? DomainAlbum)?.artistId" in album, "not the album's artist")
		val prefetch = src("ui/screens/artist/ArtistPagePrefetch.kt")
		assertTrue("OnPageSettled { settled = true }" in prefetch)
		val effect = prefetch.substringAfter("LaunchedEffect(artistId, settled, contentShown) {")
		val gate = effect.indexOf("if (artistId.isNullOrBlank() || !settled || !contentShown) return@LaunchedEffect")
		assertTrue(gate >= 0 && gate < effect.indexOf("prefetcher.warm(artistId)"), "read before the slide ended")
		val di = src("di/ManagerModule.kt")
		assertTrue("ArtistPagePrefetcher(reader::snapshot, get())" in di, "one prefetcher, over the page's own read")
	}

	@Test
	fun theReadAheadIsThePagesOwnRead() {
		val vm = src("ui/screens/artist/viewmodels/ArtistDetailViewModel.kt")
		val read = vm.substringAfter("private fun loadArtistData() {").substringBefore("observeMirror()")
		assertTrue("reader.readRoom(artistId)" in read, "the page reads Room some other way than the read-ahead")
		assertTrue("val next = reader.mbidFor(artist)" in vm)
		assertTrue("reader.readMirror(artistId, _artistMbid.value.effective, leftMbid)" in vm)
		assertTrue("buildDiscographySections(albumDao, albums, releases)" in vm)
		assertTrue("discographyUiFor(mirrored, sections, _discography.value, mirror = true)" in read)
		val reader = src("ui/screens/artist/viewmodels/ArtistPageRead.kt")
		val snapshot = reader.substringAfter("suspend fun snapshot(artistId: String)")
		assertTrue("readRoom(artistId)" in snapshot && "mbidFor(room.artist)" in snapshot)
		assertTrue("readMirror(artistId, mbid.effective, leftMbid = \"\")" in snapshot)
		assertTrue("buildDiscographySections(albumDao, room.albums, it.releases)" in snapshot)
		// What the page's first read starts from: an empty shelf state.
		assertTrue("discographyUiFor(mirrored, built.sections, DiscographyUi(), mirror = true)" in snapshot)
	}

	@Test
	fun aColdArtistPageShowsItsShapeNotASpinner() {
		val page = src("ui/screens/artist/ArtistDetailScreen.kt")
		val loading = page.substringAfter("is UiState.Loading ->").substringBefore("is UiState.Success ->")
		assertTrue("ArtistPageSkeleton(" in loading, loading)
		assertFalse("LoadingIndicator" in page, "a spinner is still on the artist page")
		val skeleton = src("ui/screens/artist/components/ArtistPageSkeleton.kt")
		val heading = src("ui/screens/artist/components/DetailHeading.kt")
		// The hero at the page's own height, so the content lands where the placeholder was.
		assertTrue(".height(artistHeroHeight(maxWidth, contentPadding))" in skeleton)
		assertTrue(".height(artistHeroHeight(maxWidth, innerPadding))" in heading)
		assertTrue("ArtistActionButtons(" in skeleton && "playEnabled = false" in skeleton)
		assertTrue("SongRowDefaults.frostedContainerColor()" in skeleton)
		// The page's colours only (B-404 / B-446: no grey frame).
		for (grey in listOf("Color.Gray", "Color.LightGray", "Color.DarkGray", "Color(0x")) {
			assertFalse(grey in skeleton, "a fixed colour in the skeleton: $grey")
		}
	}

	@Test
	fun theAlbumsAboutFoldsInLikeTheGapsBox() {
		val about = src("ui/screens/collection/components/AboutRow.kt")
		val row = about.substringAfter("fun LazyListScope.collectionDetailScreenAboutRow(")
		assertTrue("item(key = ABOUT_KEY) {\n\t\tFold(visible = text != null) {" in row, "the About slot does not fold in")
		assertFalse("?: return\n" in row.substringBefore("item(key = ABOUT_KEY)"), "the slot is left out of the list until its text")
		assertTrue("Fold(visible = expandable)" in row, "a late \"More\" pops in")
		val album = src("ui/screens/collection/CollectionDetailScreen.kt")
		assertTrue("val aboutText = rememberAboutTeaser(collectionId," in album)
		assertTrue("text = aboutText," in album)
	}
}
