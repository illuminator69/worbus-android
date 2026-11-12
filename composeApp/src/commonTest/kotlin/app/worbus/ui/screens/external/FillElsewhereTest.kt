package app.worbus.ui.screens.external

import app.worbus.domain.manager.LbFillEntry
import app.worbus.domain.manager.LbFillStatus
import app.worbus.ui.components.common.FillProgressKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * B-396, the owner's ruling (2026-11-12): an unowned album's page shows the progress of a download
 * another device started, read from lb-bot's own status for the album; no Downloads row and no
 * notification on this phone for it. The Phase B final check: Silver Club started from the desktop,
 * the Android page showed Find sources while lb-bot downloaded.
 */
class FillElsewhereTest {
	private fun status(
		state: String = "downloading",
		rgid: String = "rg-1",
		updatedAt: Double = 100.0,
		done: Int = 3,
		total: Int = 10
	) = LbFillStatus(
		releaseMbid = "rel-1", rgid = rgid, state = state, artist = "A", album = "Silver Club",
		done = done, total = total, percent = 30, updatedAt = updatedAt, cancellable = true
	)

	private fun input(fill: LbFillEntry? = null, elsewhere: LbFillStatus? = null) = RequestInput(
		support = Support.YES,
		fill = fill,
		indexed = null,
		search = SourceSearch.Idle,
		editionMbid = "ed-1",
		starting = false,
		wishlistAvailable = true,
		now = 1_000L,
		elsewhere = elsewhere
	)

	@Test
	fun aFillStartedElsewhereShowsItsProgressAndOffersNothing() {
		val stage = requestStage(input(elsewhere = status()))
		assertIs<RequestStage.Active>(stage, "the box follows the other device's fill, not Find sources")
		assertTrue(stage.startedElsewhere)
		assertEquals(emptyList(), stage.view.buttons, "nothing to press: Cancel would act on a row this phone does not have")
		assertEquals(FillProgressKind.DETERMINATE, stage.view.progress)
		val now = stage.steps.single { it.status == StepStatus.NOW }
		assertEquals(2, stage.steps.indexOf(now), "at the Downloading step")
	}

	@Test
	fun itIsShownOverASettledFillOfThisDevice() {
		val failedHere = LbFillEntry(
			key = "rg-1", isGap = false, rgid = "rg-1", groupId = "", releaseMbid = "rel-1", artist = "A",
			album = "Silver Club", quality = "", state = "failed", outcome = "failed", reason = "x", percent = 0,
			done = 0, total = 0, mp3WouldHelp = false, failureKind = "no_source", retryable = true, attempts = 1,
			settled = true, startedAt = 0L, finishedAt = 0L
		)
		val stage = requestStage(input(fill = failedHere, elsewhere = status()))
		assertIs<RequestStage.Active>(stage, "a running fill is the truth: another device's retry runs")
		assertTrue(stage.startedElsewhere)
	}

	@Test
	fun thisDevicesRunningFillIsItsOwn() {
		val runningHere = LbFillEntry(
			key = "rg-1", isGap = false, rgid = "rg-1", groupId = "", releaseMbid = "rel-1", artist = "A",
			album = "Silver Club", quality = "", state = "queued", outcome = "running", reason = "", percent = 0,
			done = 0, total = 0, mp3WouldHelp = false, failureKind = "", retryable = true, attempts = 1,
			settled = false, startedAt = 0L, finishedAt = 0L
		)
		val stage = requestStage(input(fill = runningHere, elsewhere = status()))
		assertIs<RequestStage.Active>(stage)
		assertEquals(false, stage.startedElsewhere)
	}

	@Test
	fun theNewestViewDecidesAndOnlyALiveOneIsShown() {
		// A stale frame saying downloading, then lb-bot's read saying the fill is verified: nothing runs.
		assertNull(fillElsewhere("rg-1", listOf(status(updatedAt = 100.0), status(state = "verified", updatedAt = 200.0))))
		// The read is older than the frame that moved it on: the frame.
		assertEquals("placing", fillElsewhere("rg-1", listOf(status(updatedAt = 100.0), status(state = "placing", updatedAt = 150.0)))?.state)
		// Every live state of lb-bot's walk is shown.
		for (state in listOf("searching", "queued", "downloading", "placing", "placed")) {
			assertEquals(state, fillElsewhere("rg-1", listOf(status(state = state)))?.state, state)
		}
		// Nothing is running: unknown (lb-bot's normal answer), failed, cancelled, needs_match.
		for (state in listOf("unknown", "failed", "cancelled", "needs_match", "verified")) {
			assertNull(fillElsewhere("rg-1", listOf(status(state = state))), state)
		}
		// An unknown answer for one pressing says nothing about another pressing's fill.
		assertEquals("downloading", fillElsewhere("rg-1", listOf(status(state = "unknown", updatedAt = 0.0), status()))?.state)
		// Another release-group's fill is not this page's.
		assertNull(fillElsewhere("rg-1", listOf(status(rgid = "rg-2"))))
		assertEquals(emptyList(), listOfNotNull(fillElsewhere("rg-1", emptyList())))
	}

	@Test
	fun aTerminalFillElsewhereLeavesTheBoxIdle() {
		assertIs<RequestStage.Idle>(requestStage(input(elsewhere = null)))
	}
}
