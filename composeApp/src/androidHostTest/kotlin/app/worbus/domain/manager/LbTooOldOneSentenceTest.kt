package app.worbus.domain.manager

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * B-327, the owner's ruling (2026-11-12): "lb-bot" everywhere. "lb-bot too old for signed-in users"
 * was said two ways: the Server screen's `info_library_too_old` ("…library service is too old…") and
 * R26's `error_lbbot_too_old` on every lb surface. One sentence, one string, on both surfaces; and the
 * Server page calls lb-bot by its name in its other line too ("library service" is gone).
 */
class LbTooOldOneSentenceTest {
	private val strings = sourceFile("src/commonMain/composeResources/values/strings.xml").readText()

	@Test
	fun oneStringSaysIt() {
		val names = strings.lines()
			.filter { "too old for signed-in users" in it }
			.map { Regex("""name="([^"]+)"""").find(it)!!.groupValues[1] }
		assertEquals(listOf("error_lbbot_too_old"), names)
	}

	@Test
	fun theServerPageSaysR26sSentence() {
		val screen = sourceFile("src/commonMain/kotlin/app/worbus/ui/screens/settings/ServerScreen.kt").readText()
		assertTrue("Res.string.error_lbbot_too_old" in screen, "the Server page's too-old line is R26's string")
	}

	@Test
	fun noStringSaysLibraryService() {
		val hits = strings.lines().filter { "library service" in it.lowercase() }
		assertEquals(emptyList(), hits, "lb-bot is called lb-bot")
	}
}
