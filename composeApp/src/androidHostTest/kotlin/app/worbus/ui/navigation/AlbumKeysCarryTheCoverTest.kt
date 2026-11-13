package app.worbus.ui.navigation

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * B-404 review M2 (2026-11-13): about half the album openers built `Screen.CollectionDetail` with no cover,
 * so from another cover page the album slid in wearing that page's colour and recoloured when Room
 * answered (the player's album name, the queue, the song menus, similar albums, Discover, Fresh, mood and
 * mix pages, Browse, My Listening, Library Matches, To fix, search's MusicBrainz rows, the unowned pages'
 * owned rows, the pending-sync discography rows). The old test listed nine files and could not see a tenth.
 *
 * This one finds EVERY construction of the album key in the app's source, and each must name its cover:
 * what the opener holds, or Room's through `AlbumPageOpener` (`coverArtId = null` is then the request
 * to read it, and is allowed only where the key goes through the opener or the deep link's lookup).
 */
class AlbumKeysCarryTheCoverTest {
	private val root = "src/commonMain/kotlin/app/worbus"

	/** `coverArtId = null` on purpose: each of these keys is opened through `AlbumPageOpener.openKey` / `coverFor`. */
	private val readByTheOpener = mapOf(
		"ui/navigation/AppDeepLink.kt" to "App.kt reads Room's cover before it pushes a link's album",
		"ui/screens/fresh/FreshTarget.kt" to "Fresh and Home open it through rememberKeyOpener",
		"ui/screens/search/SearchScreen.kt" to "a MusicBrainz candidate the library holds, through rememberKeyOpener",
		"ui/screens/external/ExternalArtistScreen.kt" to "an owned discography row, through rememberKeyOpener",
		"ui/components/sheets/AlbumMatchSheet.kt" to "the album a re-match landed on, through rememberKeyOpener"
	)

	private data class Construction(val path: String, val line: Int, val args: String)

	private fun constructions(): List<Construction> {
		val start = Regex("""(?<![\w.])(?:Screen\.)?CollectionDetail\(""")
		val notACall = Regex("""(class|fun|is|as\?|as)\s*(Screen\.)?$""")
		return sourceFile(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
			val text = file.readText()
			start.findAll(text).mapNotNull { m ->
				if (notACall.containsMatchIn(text.substring(maxOf(0, m.range.first - 12), m.range.first))) return@mapNotNull null
				var i = m.range.last + 1
				var depth = 1
				while (depth > 0) {
					when (text[i]) { '(' -> depth++; ')' -> depth-- }
					i++
				}
				Construction(
					file.relativeTo(sourceFile(root)).invariantSeparatorsPath,
					text.substring(0, m.range.first).count { it == '\n' } + 1,
					text.substring(m.range.last + 1, i - 1)
				)
			}.toList()
		}.toList()
	}

	@Test
	fun everyAlbumKeyNamesItsCover() {
		val all = constructions()
		assertTrue(all.size >= 30, "the walk found the album openers (found ${all.size})")
		val bare = all.filter { "coverArtId" !in it.args }
		assertEquals(emptyList(), bare.map { "${it.path}:${it.line} CollectionDetail(${it.args.trim()})" }, "an album key without its cover")
	}

	@Test
	fun noCoverOnlyWhereTheOpenerReadsRoomsCover() {
		val unread = constructions().filter { Regex("""coverArtId\s*=\s*null\b""").containsMatchIn(it.args) && it.path !in readByTheOpener }
		assertEquals(emptyList(), unread.map { "${it.path}:${it.line}" }, "a key that names no cover and does not go through AlbumPageOpener")
		val opener = sourceFile("$root/ui/navigation/AlbumPageOpener.kt").readText()
		assertTrue("key is Screen.CollectionDetail && key.coverArtId == null" in opener, "openKey reads Room's cover for such a key")
		val app = sourceFile("$root/App.kt").readText()
		assertTrue("deepLinkOpener.coverFor(it.collectionId)" in app, "a link's album gets Room's cover")
	}

	@Test
	fun theAlbumEntryHandsTheCoverToThePage() {
		val app = sourceFile("$root/App.kt").readText()
		assertTrue("CollectionDetailScreen(key.collectionId, key.tab, key.openGaps, key.coverArtId)" in app)
		assertTrue("ArtistDetailScreen(key.artist, key.coverArtId)" in app, "review L7: the artist's image too")
	}

	@Test
	fun everyWashedPageSaysWhatItWearsForTheNextPage() {
		val ambient = sourceFile("$root/ui/components/common/CoverAmbientBackground.kt").readText()
		val browsing = ambient.substringAfter("fun BrowsingAmbient(").substringBefore("\n}\n")
		assertTrue("ambientHolder.publish(" in browsing, "BrowsingAmbient publishes its wash")
	}
}
