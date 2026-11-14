package app.worbus.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/*
 * B-427 (ASSESSMENT-b313-artist-merge-2026-11-14 §5): `albumTitleKey` kept `[a-z0-9]` only, so a mixed-script title
 * keyed by its Latin part alone and the artist page paired `花様年華 pt.1` with `화양연화 pt.1` (both `pt1`): the
 * collision Worbus for desktop fixed for titles under B-313 (a letter in any script counts).
 */
class AlbumTitleKeyScriptTest {
	@Test
	fun lettersInAnyScriptCount() {
		assertNotEquals(albumTitleKey("花様年華 pt.1"), albumTitleKey("화양연화 pt.1"))
		assertNotEquals(albumTitleKey("Кино"), albumTitleKey("Кина"))
		assertEquals(albumTitleKey("화양연화 pt.1"), albumTitleKey("화양연화 Pt. 1"))
	}

	@Test
	fun aKanaMarkIsNotAnAccent() {
		// NFD splits ガ into カ and a combining voiced mark; only Latin accents are folded.
		assertNotEquals(albumTitleKey("ガ"), albumTitleKey("カ"))
	}

	@Test
	fun theRulesBeforeAreKept() {
		assertEquals(albumTitleKey("Kid A"), albumTitleKey("Kid A (Remastered)"))
		assertEquals(albumTitleKey("Homogenic"), albumTitleKey("Homogénic"), "Latin accents fold, as before (NFD on both sides)")
		assertEquals("", albumTitleKey("(Live)"), "nothing left keys empty, which callers guard")
		assertNotEquals(albumTitleKey("Hail to the Thief"), albumTitleKey("Hail to the Thief - Live"))
	}
}
