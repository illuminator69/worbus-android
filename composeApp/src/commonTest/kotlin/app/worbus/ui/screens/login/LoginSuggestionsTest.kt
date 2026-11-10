package app.worbus.ui.screens.login

import app.worbus.ui.screens.login.pages.loginSuggestions
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * B-307: the "Did you mean…" chips offered https first for every address, while the login's own
 * scheme rule (normalizeInstanceUrl, B-302) gives a loopback, private or single-label host http.
 * The first chip is the rule's choice; the other scheme comes second.
 */
class LoginSuggestionsTest {
	@Test
	fun aPrivateAddressIsOfferedHttpFirst() {
		assertEquals(listOf("http://192.168.1.10:4533", "https://192.168.1.10:4533"), loginSuggestions("192.168.1.10:4533"))
		assertEquals("http://nas:4533", loginSuggestions("nas:4533").first())
		assertEquals("http://localhost:4533", loginSuggestions("localhost:4533").first())
	}

	@Test
	fun aPublicNameIsOfferedHttpsFirst() {
		assertEquals(listOf("https://music.example.com", "http://music.example.com"), loginSuggestions("music.example.com"))
	}
}
