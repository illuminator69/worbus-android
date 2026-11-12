package app.worbus.domain.manager

import app.worbus.data.database.entities.SyncActionType
import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/*
 * The B-373 walk (`RESULTS-phase-b-walk-2026-11-12.md`): Rebuild database's confirmation still said it
 * deletes "the changes still waiting to be sent", which Rebuild keeps since B-373 (watched: writes.db
 * kept both rows); and Storage's row said "2 items waiting for network" for two writes the server had
 * REFUSED with the network fine.
 */
class StorageWordsTest {
	private val strings by lazy { sourceFile("src/commonMain/composeResources/values/strings.xml").readText() }

	private fun english(key: String) =
		Regex("""<string name="$key"[^>]*>([^<]*)</string>""").find(strings)?.groupValues?.get(1) ?: error("no $key")

	@Test
	fun rebuildSaysTheWaitingChangesAreKept() {
		val rebuild = english("info_danger_rebuild")
		assertFalse("and the changes still waiting to be sent" in rebuild, rebuild)
		assertTrue("kept" in rebuild, "it says the unsent changes are kept: $rebuild")
	}

	@Test
	fun aRefusedWriteIsNotWaitingForTheNetwork() {
		val t = Instant.fromEpochMilliseconds(1)
		val writes = listOf(
			QueuedWrite(1, SyncActionType.STAR, "a", t, refusals = 2, lastError = "GENERIC: refused"),
			QueuedWrite(2, SyncActionType.STAR_2, "b", t, refusals = 1, lastError = "GENERIC: refused"),
			QueuedWrite(3, SyncActionType.SCROBBLE, "c", t)
		)
		assertEquals(PendingLine(waiting = 1, refused = 2), pendingLineOf(writes))
		assertEquals(PendingLine(waiting = 0, refused = 0), pendingLineOf(emptyList()))
	}
}
