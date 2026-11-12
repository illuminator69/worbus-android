package app.worbus.di

import app.worbus.domain.manager.PreferenceManager
import app.worbus.domain.manager.SessionManager
import app.worbus.util.userAgent
import io.ktor.client.HttpClient
import io.ktor.client.plugins.UserAgent
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.api.SendingRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.util.AttributeKey
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import org.koin.mp.KoinPlatform

/**
 * The custom headers a request to [requestUrl] carries (B-363): the user's own, and only toward the
 * Navidrome they were set for, i.e. the same scheme, host and port as [instanceUrl] and a path under
 * its own. Those headers are where a reverse proxy's auth or a Cloudflare Access token lives, so a
 * cover on another host (Cover Art Archive, Deezer, a preview's image) gets none.
 */
fun navidromeHeadersFor(requestUrl: String, instanceUrl: String, headers: Map<String, String>): Map<String, String> {
	if (headers.isEmpty()) return emptyMap()
	return if (isNavidromeUrl(requestUrl, instanceUrl)) headers else emptyMap()
}

/** Is [requestUrl] the Navidrome at [instanceUrl]: the same scheme, host and port, and a path under its own. */
fun isNavidromeUrl(requestUrl: String, instanceUrl: String): Boolean {
	if (instanceUrl.isBlank()) return false
	val request = parsedUrl(requestUrl) ?: return false
	val server = parsedUrl(instanceUrl) ?: return false
	val sameOrigin = request.protocol.name.equals(server.protocol.name, ignoreCase = true) &&
		request.host.equals(server.host, ignoreCase = true) &&
		request.port == server.port
	val serverPath = server.encodedPath.trimEnd('/')
	val underServer = serverPath.isEmpty() || request.encodedPath == serverPath ||
		request.encodedPath.startsWith("$serverPath/")
	return sameOrigin && underServer
}

private fun parsedUrl(raw: String): Url? = try {
	URLBuilder(raw.trim()).build().takeIf { it.host.isNotBlank() }
} catch (_: Exception) {
	null
}

/**
 * Where an image request finds the session's Navidrome and the user's headers, read at the moment
 * of each request (a headers change in Settings applies to the next cover, not the next launch).
 * Through Koin's global context: the loaders are built by Coil's singleton factory, which knows no
 * DI. Before Koin starts, nothing.
 */
private fun headersFromSession(url: String): Map<String, String> {
	val koin = KoinPlatform.getKoinOrNull() ?: return emptyMap()
	val prefs = koin.getOrNull<PreferenceManager>() ?: return emptyMap()
	val session = koin.getOrNull<SessionManager>() ?: return emptyMap()
	return navidromeHeadersFor(url, session.instanceUrl(), prefs.customHeadersMap())
}

/**
 * ONE HTTP client for every image this app loads (B-336, B-363): the cover loader, the wash loader
 * and the media notification's artwork. The user agent and the custom headers are set here, once,
 * instead of per request: per request, only `CoverArt` ever set the headers, and nothing set the
 * user agent (Coil's bare client sent `ktor-client`, the notification `Dalvik/2.1.0`).
 */
fun imageHttpClient(headersFor: (String) -> Map<String, String>): HttpClient = HttpClient {
	install(UserAgent) { agent = userAgent() }
	navidromeHeaders(headersFor)
}

/** Which custom headers this plugin put on a request, carried with it onto a redirect's copy. */
private val AddedNavidromeHeaders = AttributeKey<Map<String, String>>("AddedNavidromeHeaders")

/**
 * The user's custom headers, decided on EVERY hop of a request (review minor 4): added where
 * [headersFor] answers for the URL (the session's Navidrome), and taken off again on a hop that
 * leaves it. Ktor's redirect follower re-sends a copy of the request, headers included, and strips
 * only `Authorization` on a cross-host hop, so headers chosen once per request followed a 302 from
 * an auth proxy to its portal, or from Navidrome's proxy to a CDN, credential and all. Each hop
 * goes through the send pipeline, which is where this runs. A header the request set itself is
 * never touched.
 */
fun HttpClientConfig<*>.navidromeHeaders(headersFor: (String) -> Map<String, String>) {
	install(createClientPlugin("NavidromeHeaders") {
		on(SendingRequest) { request, _ ->
			val added = request.attributes.getOrNull(AddedNavidromeHeaders).orEmpty()
			val wanted = headersFor(request.url.buildString())
			for ((name, value) in added) {
				if (wanted[name] != value && request.headers.getAll(name) == listOf(value)) request.headers.remove(name)
			}
			val nowAdded = HashMap<String, String>()
			for ((name, value) in wanted) {
				when {
					!request.headers.contains(name) -> {
						request.headers.append(name, value)
						nowAdded[name] = value
					}
					added[name] == value -> nowAdded[name] = value
				}
			}
			request.attributes.put(AddedNavidromeHeaders, nowAdded)
		}
	})
}

/**
 * The session's custom headers toward [instanceUrl] only, for a client that talks to the session's
 * Navidrome (the Subsonic client, the streaming client): read at each request.
 */
fun navidromeHeadersSource(instanceUrl: () -> String, headers: () -> Map<String, String>): (String) -> Map<String, String> =
	{ url -> navidromeHeadersFor(url, instanceUrl(), headers()) }

/** The process's image client, built on first use. */
val sharedImageHttpClient: HttpClient by lazy { imageHttpClient(::headersFromSession) }
