package app.worbus.di

import kotlin.test.Test
import kotlin.test.assertEquals

/* B-363: the custom headers go to the user's Navidrome and nowhere else. */
class NavidromeHeadersForTest {
	private val h = mapOf("CF-Access-Client-Id" to "id", "X-Auth" to "secret")

	@Test
	fun aCoverOnTheServerGetsThem() {
		assertEquals(h, navidromeHeadersFor("https://music.example.com/rest/getCoverArt.view?id=al-1&size=300", "https://music.example.com", h))
		assertEquals(h, navidromeHeadersFor("https://MUSIC.example.com:443/rest/getCoverArt.view?id=1", "https://music.example.com/", h))
	}

	@Test
	fun aServerUnderAPathGetsThemOnlyUnderThatPath() {
		val server = "https://home.example.org/navidrome"
		assertEquals(h, navidromeHeadersFor("https://home.example.org/navidrome/rest/getCoverArt.view?id=1", server, h))
		assertEquals(emptyMap(), navidromeHeadersFor("https://home.example.org/navidrome-other/x.jpg", server, h))
		assertEquals(emptyMap(), navidromeHeadersFor("https://home.example.org/other/x.jpg", server, h))
	}

	@Test
	fun anotherHostSchemeOrPortGetsNone() {
		val server = "http://192.168.1.10:4533"
		assertEquals(emptyMap(), navidromeHeadersFor("https://coverartarchive.org/release-group/x/front-250", server, h))
		assertEquals(emptyMap(), navidromeHeadersFor("https://192.168.1.10:4533/rest/getCoverArt.view", server, h))
		assertEquals(emptyMap(), navidromeHeadersFor("http://192.168.1.10:4790/lb/cover", server, h))
		assertEquals(emptyMap(), navidromeHeadersFor("http://192.168.1.100:4533/rest/getCoverArt.view", server, h))
	}

	@Test
	fun noServerOrNoHeadersMeansNone() {
		assertEquals(emptyMap(), navidromeHeadersFor("https://music.example.com/rest/x", "", h))
		assertEquals(emptyMap(), navidromeHeadersFor("https://music.example.com/rest/x", "https://music.example.com", emptyMap()))
		assertEquals(emptyMap(), navidromeHeadersFor("not a url", "https://music.example.com", h))
	}
}
