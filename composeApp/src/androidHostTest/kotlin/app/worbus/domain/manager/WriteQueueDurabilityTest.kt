package app.worbus.domain.manager

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertFalse

/*
 * B-373: the offline write queue is the only record of stars, ratings, scrobbles and playlist
 * deletes the server has not had yet, so it is not cache. Settings' "Rebuild database"
 * (`DbRepository.removeEverything`) wiped it with the cached library. Source-level: the wipe's
 * body may not reach the queue.
 */
class WriteQueueDurabilityTest {
	private val wipe: String = sourceFile("src/commonMain/kotlin/app/worbus/domain/repositories/DbRepository.kt")
		.readText()
		.substringAfter("suspend fun removeEverything()")
		.substringBefore("\n\t}\n")

	@Test
	fun rebuildingTheCacheLeavesTheWriteQueue() {
		assertFalse("clearAllActions" in wipe, "the cache wipe clears the write queue:\n$wipe")
		assertFalse("syncDao" in wipe || "writeQueue" in wipe, "the cache wipe touches the write queue:\n$wipe")
	}
}
