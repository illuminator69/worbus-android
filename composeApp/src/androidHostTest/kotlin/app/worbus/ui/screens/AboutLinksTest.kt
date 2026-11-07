package app.worbus.ui.screens

import app.worbus.sourceFile
import app.worbus.ui.screens.settings.UPSTREAM_CREDIT_URL
import app.worbus.ui.screens.settings.aboutLinks
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Worbus settings N1: About sends a tester to this fork, never to upstream's repository, Codeberg
 * mirror or Discord. Reads the screen's own source too, so a link typed straight into it is caught.
 * Worbus P5: the one exception is the credit line's link to the app this is a fork of (design §6.8).
 */
class AboutLinksTest {
	private val banned = listOf("ssalggnikool", "codeberg", "discord")

	@Test
	fun noAboutLinkPointsUpstream() {
		assertTrue(aboutLinks.isNotEmpty())
		assertTrue(UPSTREAM_CREDIT_URL == "https://github.com/ssalggnikool/Navic", UPSTREAM_CREDIT_URL)
		for (url in aboutLinks - UPSTREAM_CREDIT_URL) {
			for (word in banned) {
				assertTrue(word !in url.lowercase(), "$url names $word")
			}
		}
	}

	@Test
	fun theScreenSourceHoldsNoLinkOutsideTheList() {
		val source = sourceFile("src/commonMain/kotlin/app/worbus/ui/screens/settings/AboutScreen.kt").readText()
		val typed = Regex("\"(https?://[^\"]+)\"").findAll(source).map { it.groupValues[1] }.toList()
		for (url in typed) {
			assertTrue(url in aboutLinks, "AboutScreen.kt types $url, which is not in aboutLinks")
			if (url == UPSTREAM_CREDIT_URL) continue
			for (word in banned) {
				assertTrue(word !in url.lowercase(), "$url names $word")
			}
		}
	}
}
