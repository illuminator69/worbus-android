package app.worbus.ui.screens.collection

import app.worbus.domain.manager.LbMeta
import app.worbus.ui.screens.collection.components.aboutTeaserText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/*
 * The owner, 2026-11-14: the album page's About "pops in", the rows below jumping. It now folds in (the gaps
 * box's motion), and the text it shows first stays for the visit: §5's rule, nothing arrives late into an
 * occupied slot.
 */
class AboutTeaserTest {
	@Test
	fun navidromesNotesComeFirstThenLbBots() {
		assertNull(aboutTeaserText(shown = null, notes = null, meta = null), "nothing: no slot content")
		assertEquals("Notes.", aboutTeaserText(null, "Notes.", LbMeta(summary = "Article.")))
		assertEquals("Article.", aboutTeaserText(null, " ", LbMeta(summary = "Article.")))
		assertEquals("One-liner.", aboutTeaserText(null, null, LbMeta(wikidataDescription = "One-liner.")))
	}

	@Test
	fun aTextShownIsNeverSwappedForALaterOne() {
		// lb-bot's text was shown (no notes then); notes arriving later (a refresh) do not replace it.
		assertEquals("Article.", aboutTeaserText(shown = "Article.", notes = "Notes.", meta = LbMeta(summary = "Article.")))
		// lb-bot answering after the notes does not either.
		assertEquals("Notes.", aboutTeaserText(shown = "Notes.", notes = "Notes.", meta = LbMeta(summary = "Article.")))
	}
}
