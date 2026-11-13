package app.worbus.ui.screens.external

import app.worbus.ui.screens.external.components.sourceFolderName
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * B-418 (the owner's re-walk, 2026-11-13): Find sources listed about twenty pages of sources and the owner
 * could not tell which folder was which (Björk – Debut's "Debut Live" folders). Every source card, in an
 * album's sources and in a gap's, is titled with its folder's name: the last path component, and when that
 * is only a disc or format subfolder, the folder above it as well.
 */
class SourceFolderNameTest {
	@Test
	fun aCardIsNamedForItsFolder() {
		assertEquals("Björk - Debut Live (1993)", sourceFolderName("@@peer\\Music\\Björk\\Björk - Debut Live (1993)", "peer"))
		assertEquals("Debut", sourceFolderName("Music/Björk/Debut", "peer"), "forward slashes")
		assertEquals("Debut", sourceFolderName("Debut", "peer"), "a non-admin's: lb-bot sends the last component")
	}

	@Test
	fun aDiscOrFormatSubfolderIsNamedWithTheFolderAboveIt() {
		assertEquals("Debut Live / CD1", sourceFolderName("Music\\Björk\\Debut Live\\CD1", "peer"))
		assertEquals("Debut / Disc 2", sourceFolderName("Music/Björk/Debut/Disc 2", "peer"))
		assertEquals("Debut (Deluxe) / FLAC", sourceFolderName("Music\\Debut (Deluxe)\\FLAC", "peer"))
		assertEquals("CD1", sourceFolderName("CD1", "peer"), "nothing above it to name")
	}

	@Test
	fun noFolderIsThePeerAsBefore() {
		assertEquals("peer", sourceFolderName("", "peer"))
		assertEquals("Björk", sourceFolderName("Music\\Björk\\", "peer"), "a trailing separator is not an empty name")
	}
}
