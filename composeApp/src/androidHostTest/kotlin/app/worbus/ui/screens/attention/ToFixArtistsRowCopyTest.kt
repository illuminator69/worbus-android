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
 * B-406 VM walk (2026-11-13, RESULTS-owner-walk-vmcheck-2026-11-13.md item 14): the "To fix" header counts the
 * artists-to-check row (the contract: imports + folders, plus the artists to check while that row shows), and the
 * row itself said "Worth a look in Library Matches. Not counted." The row and the header must agree.
 */
class ToFixArtistsRowCopyTest {
	private val strings = sourceFile("src/commonMain/composeResources/values/strings.xml").readText()

	private fun string(name: String) =
		Regex("""<string name="$name">(.*?)</string>""").find(strings)?.groupValues?.get(1) ?: error("no $name")

	@Test
	fun theArtistsRowDoesNotSayItIsNotCounted() {
		val answer = LbAttention(version = 1, counts = AttentionCounts(imports = 2, folders = 3))
		val page = attentionPage(AttentionRead.Ok(answer), answer, 1L, emptyList(), artistsToCheck = 2) as AttentionPage.Rows
		// The header counts the row's two artists ...
		assertEquals(2 + 3 + 2, toFixSectionCount(page))
		// ... so the row must not say they are not counted.
		val hint = string("attention_artists_to_check_hint")
		assertTrue("not counted" !in hint.lowercase(), "the artists row says: $hint")
	}
}
