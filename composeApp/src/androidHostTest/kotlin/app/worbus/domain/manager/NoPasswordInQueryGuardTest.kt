package app.worbus.domain.manager

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * B-359: no source puts the Navidrome password in a URL query by name. (Subsonic's own `t`/`s`
 * token pair is a salted hash, not the password, and is out of this guard's scope.)
 */
class NoPasswordInQueryGuardTest {
	@Test
	fun noQueryParameterIsNamedForThePassword() {
		val offenders = listOf("src/commonMain", "src/androidMain").map(::sourceFile)
			.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
			.flatMap { f ->
				f.readLines().mapIndexedNotNull { i, line ->
					val named = Regex("""(parameter|parameters\.append|appendQueryParameter|searchParams\.set)\(\s*"[a-z_]*password""").containsMatchIn(line)
					val pair = Regex("""\("navidrome_password"\s+to\b""").containsMatchIn(line)
					if (named || pair) "${f.invariantSeparatorsPath}:${i + 1}: ${line.trim()}" else null
				}
			}
		assertEquals(emptyList(), offenders, "the password goes in a body or a header, never a query")
	}
}
