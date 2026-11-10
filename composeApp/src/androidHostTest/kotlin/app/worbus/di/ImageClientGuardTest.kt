package app.worbus.di

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * B-336 / B-363: the user agent and custom headers are set once, on the image client. A per-request
 * `httpHeaders`, a bare `KtorNetworkFetcherFactory()` (Coil's own client) or a DataSourceBitmapLoader
 * left on its default data source is the copy that drifts.
 */
class ImageClientGuardTest {
	@Test
	fun everyImageFetchGoesThroughTheOneClient() {
		val offenders = listOf("src/commonMain", "src/androidMain").map(::sourceFile)
			.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
			.flatMap { f ->
				val text = f.readText()
				buildList {
					f.readLines().forEachIndexed { i, line ->
						val code = line.substringBefore("//")
						if (Regex("""\.httpHeaders\(""").containsMatchIn(code)) add("${f.name}:${i + 1}: per-request headers")
						if (Regex("""KtorNetworkFetcherFactory\(\s*\)""").containsMatchIn(code)) add("${f.name}:${i + 1}: Coil's bare client")
					}
					if ("DataSourceBitmapLoader.Builder(" in text && "setDataSourceFactory(" !in text) add("${f.name}: bitmap loader on its default source")
				}
			}
		assertEquals(emptyList(), offenders)
	}
}
