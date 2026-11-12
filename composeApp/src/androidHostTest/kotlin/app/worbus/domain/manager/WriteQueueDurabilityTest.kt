package app.worbus.domain.manager

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

	@Test
	fun theQueueIsKeptInTheDatabaseThatIsNeverDropped() {
		val writes = sourceFile("src/commonMain/kotlin/app/worbus/data/database/WriteQueueDatabase.kt").readText()
		assertTrue("PendingWriteEntity::class" in writes, "the queue's table is in writes.db")
		val module = sourceFile("src/commonMain/kotlin/app/worbus/di/DatabaseModule.kt").readText()
		assertTrue("get<WriteQueueDatabase>().pendingWriteDao()" in module, "and its DAO is read from there")
	}

	@Test
	fun everyPullThatWritesServerRowsAppliesThePendingWrites() {
		// Rule 6: an upsert of what the server said replaces Room's row, star and rating included.
		val sites = listOf(
			"src/commonMain/kotlin/app/worbus/domain/repositories/DbRepository.kt",
			"src/commonMain/kotlin/app/worbus/domain/repositories/CollectionRepository.kt"
		).flatMap { path ->
			sourceFile(path).readLines().mapIndexedNotNull { i, line ->
				val write = Regex("""(albumDao\.insertAlbums?|songDao\.insertSongs|songDao\.updateSongsByAlbumId|artistDao\.insertArtists)\(""")
				if (write.containsMatchIn(line)) "$path:${i + 1}: ${line.trim()}" else null
			}
		}
		assertTrue(sites.size >= 8, "found the pull write sites: $sites")
		val bare = sites.filterNot { "overlay" in it }
		assertEquals(emptyList(), bare, "a pull writes server rows without the pending-write overlay")
	}
}
