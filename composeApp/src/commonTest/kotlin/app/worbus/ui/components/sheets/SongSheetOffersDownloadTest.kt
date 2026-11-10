package app.worbus.ui.components.sheets

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/* B-171: which songs a sheet offers to download — library songs, not previews or radio stations. */
class SongSheetOffersDownloadTest {
	@Test
	fun aLibrarySongIsOffered() = assertTrue(songSheetOffersDownload("4Ab3x9Kq2"))

	@Test
	fun aPreviewOrARadioStationIsNot() {
		assertFalse(songSheetOffersDownload("ext:deezer:123"))
		assertFalse(songSheetOffersDownload("radio_7"))
		assertFalse(songSheetOffersDownload(""))
	}
}
