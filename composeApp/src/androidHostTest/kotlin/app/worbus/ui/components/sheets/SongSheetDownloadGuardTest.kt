package app.worbus.ui.components.sheets

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * B-171: the song sheet's Download row was wired by each of its call sites, and three never wired it
 * (Search, the player's More button, Listening stats). The rule: every song sheet offers the song's
 * download state. Either the sheet resolves it itself, or every call site passes the actions.
 */
class SongSheetDownloadGuardTest {
	private val sources = listOf("src/commonMain", "src/androidMain").map(::sourceFile)
		.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

	/** Each `SongSheet(` call's argument text, by file and line. */
	private fun calls(): List<Pair<String, String>> = sources.filter { it.name != "SongSheet.kt" }.flatMap { f ->
		val text = f.readText()
		Regex("""(?<![A-Za-z])SongSheet\(""").findAll(text).map { m ->
			var depth = 0
			var end = m.range.last
			for (i in m.range.last until text.length) {
				if (text[i] == '(') depth++
				if (text[i] == ')') { depth--; if (depth == 0) { end = i; break } }
			}
			"${f.name}:${text.substring(0, m.range.first).count { it == '\n' } + 1}" to text.substring(m.range.first, end)
		}.toList()
	}

	@Test
	fun everySongSheetOffersTheSongsDownloadState() {
		val sheet = sourceFile("src/commonMain/kotlin/app/worbus/ui/components/sheets/SongSheet.kt").readText()
		val resolvesItself = "koinInject<DownloadManager>()" in sheet
		val found = calls()
		assertEquals(true, found.size >= 9, "found the call sites: ${found.map { it.first }}")
		val missing = if (resolvesItself) emptyList() else found.filterNot { "onDownload" in it.second }.map { it.first }
		assertEquals(emptyList(), missing, "song sheets with no Download row")
	}
}
