package app.worbus.domain.manager

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * B-327: "lb-bot too old for signed-in users" was said two ways: the Server screen's
 * `info_library_too_old` ("…library service is too old…") and R26's `error_lbbot_too_old`
 * everywhere else. One sentence, one string.
 */
class LbTooOldOneSentenceTest {
	@Test
	fun oneStringSaysIt() {
		val strings = sourceFile("src/commonMain/composeResources/values/strings.xml").readLines()
			.filter { "too old for signed-in users" in it }
			.map { Regex("""name="([^"]+)"""").find(it)!!.groupValues[1] }
		assertEquals(listOf("error_lbbot_too_old"), strings)
	}
}
