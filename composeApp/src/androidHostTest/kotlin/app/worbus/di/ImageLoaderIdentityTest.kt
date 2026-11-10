package app.worbus.di

import coil3.ImageLoader
import coil3.request.ImageRequest
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertTrue

/*
 * B-336 / B-363: the app's two image loaders (the singleton every cover, MiniPlayer and palette fetch
 * uses, and the static one behind the washes) are driven for real against a local HTTP server,
 * with an ImageRequest that sets NO headers of its own, as MiniPlayer's and BlendBackground's do.
 * Whatever reaches the server is what Navidrome (or a reverse proxy in front of it) sees.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImageLoaderIdentityTest {
	private val seen = ConcurrentLinkedQueue<Map<String, String>>()
	private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
		createContext("/") { exchange ->
			seen += exchange.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString(",") }
			exchange.sendResponseHeaders(404, -1)
			exchange.close()
		}
		start()
	}
	private val base = "http://127.0.0.1:${server.address.port}"

	@After
	fun stop() = server.stop(0)

	private fun load(loader: ImageLoader, url: String) = runBlocking {
		loader.execute(ImageRequest.Builder(RuntimeEnvironment.getApplication()).data(url).build())
		Unit
	}

	private fun userAgentOf(loader: ImageLoader): String? {
		load(loader, "$base/rest/getCoverArt.view?id=al-1&size=300")
		return seen.lastOrNull()?.get("user-agent")
	}

	@Test
	fun theCoverLoaderSendsTheWorbusUserAgent() {
		val ua = userAgentOf(initializeSingletonImageLoader(RuntimeEnvironment.getApplication()))
		assertTrue(ua != null && ua.startsWith("Worbus-Android/"), "cover requests carry the app's user agent, not $ua")
	}

	@Test
	fun theWashLoaderSendsTheWorbusUserAgent() {
		val ua = userAgentOf(getStaticImageLoader(RuntimeEnvironment.getApplication()))
		assertTrue(ua != null && ua.startsWith("Worbus-Android/"), "wash requests carry the app's user agent, not $ua")
	}
}
