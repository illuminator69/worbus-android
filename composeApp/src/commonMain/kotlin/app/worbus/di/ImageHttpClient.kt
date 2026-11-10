package app.worbus.di

import app.worbus.domain.manager.PreferenceManager
import app.worbus.domain.manager.SessionManager
import app.worbus.util.userAgent
import io.ktor.client.HttpClient
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.api.createClientPlugin
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
	if (headers.isEmpty() || instanceUrl.isBlank()) return emptyMap()
	val request = parsedUrl(requestUrl) ?: return emptyMap()
	val server = parsedUrl(instanceUrl) ?: return emptyMap()
	val sameOrigin = request.protocol.name.equals(server.protocol.name, ignoreCase = true) &&
		request.host.equals(server.host, ignoreCase = true) &&
		request.port == server.port
	val serverPath = server.encodedPath.trimEnd('/')
	val underServer = serverPath.isEmpty() || request.encodedPath == serverPath ||
		request.encodedPath.startsWith("$serverPath/")
	return if (sameOrigin && underServer) headers else emptyMap()
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
	install(createClientPlugin("NavidromeHeaders") {
		onRequest { request, _ ->
			for ((name, value) in headersFor(request.url.buildString())) {
				if (!request.headers.contains(name)) request.headers.append(name, value)
			}
		}
	})
}

/** The process's image client, built on first use. */
val sharedImageHttpClient: HttpClient by lazy { imageHttpClient(::headersFromSession) }
