package app.worbus.ui.screens.collection

import app.worbus.domain.manager.LbFillEntry
import app.worbus.domain.manager.LbGap
import app.worbus.domain.manager.LbGapSource
import app.worbus.domain.manager.LbGapTask
import app.worbus.domain.manager.LbGapTrack
import app.worbus.ui.screens.external.StepStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertIs

/*
 * P4 round 2: which state the album page's gaps box is in. A transfer in flight beats everything; a
 * finished fill shows its result for a few seconds and then the box goes; files waiting on lb-bot's
 * workspace are their own state; the sources view searches, chooses or says nothing was found; a
 * failed fill keeps the box open; otherwise it is the user's fold.
 */
class GapStageTest {
	private fun track(position: Int, state: String) = LbGapTrack(position = position, title = "T$position", state = state)

	private fun gap(
		status: String = "ready",
		tracks: List<LbGapTrack> = listOf(track(1, "present"), track(2, "present"), track(3, "missing"), track(4, "missing")),
		sources: List<LbGapSource> = emptyList(),
		sourceTask: LbGapTask? = null,
		noSourceReason: String = "",
		stalledPlacement: Boolean = false,
		failDetail: String = "",
		mp3WouldHelp: Boolean = false
	) = LbGap(
		id = "g-1", album = "Soft Machinery", present = tracks.count { it.state == "present" }, total = tracks.size,
		status = status, tracks = tracks, sources = sources, sourceTask = sourceTask,
		noSourceReason = noSourceReason, stalledPlacement = stalledPlacement, failDetail = failDetail,
		mp3WouldHelp = mp3WouldHelp
	)

	private fun fill(outcome: String, settled: Boolean = true, finishedAt: Long = 0L, done: Int = 2) = LbFillEntry(
		key = "g-1", isGap = true, rgid = "", groupId = "g-1", releaseMbid = "", artist = "A", album = "Soft Machinery",
		quality = "", state = if (outcome == "done") "complete" else "failed", outcome = outcome,
		reason = "lb-bot's own words", percent = 100, done = done, total = 2, mp3WouldHelp = false,
		failureKind = "", retryable = true, attempts = 1, settled = settled, startedAt = 0L, finishedAt = finishedAt
	)

	private fun input(
		gap: LbGap? = gap(),
		mirrored: Int = 2,
		fill: LbFillEntry? = null,
		view: GapView = GapView.FOLDED,
		committed: Boolean = false,
		searchAsked: Boolean = false,
		now: Long = 100_000L
	) = GapInput(gap, mirrored, fill, view, committed, searchAsked, now)

	private val good = LbGapSource(id = 0, peer = "p", recommended = true, artistVerified = true, albumMatchOk = true)
	private val unverified = LbGapSource(id = 1, peer = "q", recommended = true, artistVerified = false, albumMatchOk = true)

	@Test
	fun beforeLbBotAnswersTheMirrorCountFoldsAndAnOpenBoxWaits() {
		assertEquals(GapStage.Folded(3), gapStage(input(gap = null, mirrored = 3)))
		assertEquals(GapStage.Loading, gapStage(input(gap = null, mirrored = 3, view = GapView.TRACKS)))
		assertEquals(GapStage.Absent, gapStage(input(gap = null, mirrored = 0)))
	}

	@Test
	fun foldedCountsTheTracksStillMissing() {
		assertEquals(GapStage.Folded(2), gapStage(input()))
	}

	@Test
	fun unfoldedListsOnlyTheMissingTracksAgainstTheReleaseLength() {
		val stage = assertIs<GapStage.Tracks>(gapStage(input(view = GapView.TRACKS)))
		assertEquals(listOf(3, 4), stage.missing.map { it.position })
		assertEquals(4, stage.total)
	}

	@Test
	fun aTransferInFlightBeatsTheFoldAndTheSources() {
		val moving = gap(tracks = listOf(track(1, "present"), track(2, "done"), track(3, "downloading")))
		listOf(GapView.FOLDED, GapView.TRACKS, GapView.SOURCES).forEach { view ->
			val stage = assertIs<GapStage.Active>(gapStage(input(gap = moving, view = view)))
			assertEquals(1, stage.done)
			assertEquals(2, stage.wanted)
		}
	}

	@Test
	fun aFetchLbBotAcceptedIsActiveBeforeItsStatusMoves() {
		assertIs<GapStage.Active>(gapStage(input(committed = true, view = GapView.SOURCES)))
	}

	@Test
	fun theStepsFollowTheTracks() {
		fun now(vararg states: String) = gapStage(
			input(gap = gap(status = "downloading", tracks = states.mapIndexed { i, s -> track(i + 1, s) }))
		).let { assertIs<GapStage.Active>(it).steps.indexOfFirst { step -> step.status == StepStatus.NOW } }
		assertEquals(1, now("present", "queued", "queued"))
		assertEquals(2, now("present", "downloaded", "downloading"))
		assertEquals(3, now("present", "downloaded", "downloaded"))
	}

	@Test
	fun aFinishedFillShowsItsResultForAFewSecondsThenTheBoxGoes() {
		val filled = gap(status = "complete", tracks = listOf(track(1, "present"), track(2, "done"), track(3, "done")))
		val done = fill("done", finishedAt = 100_000L)
		assertEquals(GapStage.Done(added = 2, present = 1, total = 3), gapStage(input(gap = filled, fill = done, now = 101_000L)))
		assertEquals(GapStage.Absent, gapStage(input(gap = filled, fill = done, now = 100_000L + GAP_RESULT_HOLD_MS)))
	}

	@Test
	fun aCompleteAlbumHasNoBox() {
		val whole = gap(status = "complete", tracks = listOf(track(1, "present"), track(2, "present")))
		assertEquals(GapStage.Absent, gapStage(input(gap = whole, view = GapView.TRACKS)))
	}

	@Test
	fun aStalledPlacementIsAttentionWhateverTheView() {
		// B-231 cut the pending-match half out of this test: that one still offers the rest (below).
		val stalled = gap(status = "failed", stalledPlacement = true, failDetail = "Reconcile in lb-bot")
		assertEquals(GapStage.Attention("Reconcile in lb-bot"), gapStage(input(gap = stalled, view = GapView.SOURCES)))
	}

	/**
	 * B-231, the shared rule's point 4 (desktop results, "B-231: the shared rule"): the ledger's settle
	 * for a pending match (`failed` at `needs_match`, Q-031) is the match pending, not a failed fill.
	 */
	@Test
	fun aPendingMatchsLedgerSettleIsNotAFailedFill() {
		val awaiting = gap(status = "picking", tracks = listOf(track(1, "present"), track(2, "downloaded"), track(3, "missing")))
		val pending = fill("failed").copy(state = "needs_match")
		assertEquals(GapStage.Tracks(listOf(track(3, "missing")), 3), gapStage(input(gap = awaiting, fill = pending, view = GapView.TRACKS)))
	}

	/**
	 * B-231 round 2 (a), the desktop's point 1: a pending match does not depend on a search. With
	 * another device's search running (`sourceTask` running) the box still counts and lists only the
	 * tracks to fetch, and the ledger's `needs_match` settle is still the match pending. Android used
	 * to drop the pending match while searching: Folded(2), Tracks([2, 3]), and Failed for the settle.
	 */
	@Test
	fun aPendingMatchHoldsWhileASearchIsInFlight() {
		val tracks = listOf(track(1, "present"), track(2, "downloaded"), track(3, "missing"))
		val searchingElsewhere = gap(status = "picking", tracks = tracks, sourceTask = LbGapTask(status = "running"))
		assertEquals(GapStage.Folded(1), gapStage(input(gap = searchingElsewhere)))
		assertEquals(GapStage.Tracks(listOf(track(3, "missing")), 3), gapStage(input(gap = searchingElsewhere, view = GapView.TRACKS)))
		val pending = fill("failed").copy(state = "needs_match")
		assertEquals(
			GapStage.Tracks(listOf(track(3, "missing")), 3),
			gapStage(input(gap = searchingElsewhere, fill = pending, view = GapView.TRACKS))
		)
		assertEquals(GapStage.Searching, gapStage(input(gap = searchingElsewhere, view = GapView.SOURCES)))
		// Attention stays gated on no search in flight (the desktop's point 3).
		val allHere = gap(status = "picking", tracks = listOf(track(1, "present"), track(2, "downloaded")), sourceTask = LbGapTask(status = "running"))
		assertEquals(GapStage.Searching, gapStage(input(gap = allHere, view = GapView.SOURCES)))
	}

	/**
	 * B-231 round 3, the VM walk (`RESULTS-phase-b-walk-2026-11-12.md`): the walk's gap, tracks
	 * `[present, present, downloaded, missing]` at `picking`. The sources view said "For the 2 missing
	 * tracks only" and "Request 2 tracks" (the desktop: 1), and while the one track was fetched the
	 * box said "Getting 2 tracks … 1 of 2". Every count in the box is the tracks to fetch (rule 2).
	 */
	@Test
	fun theBoxCountsOnlyTheTracksToFetchOnTheWalksGap() {
		val walk = gap(status = "picking", tracks = listOf(track(1, "present"), track(2, "present"), track(3, "downloaded"), track(4, "missing")))
		assertEquals(1, gapFetchCount(walk), "the sources view's header and Request button")

		// The fetch that follows: lb-bot says downloading, track 3 still downloaded from before.
		var held = heldForMatch(emptySet(), walk)
		val fetching = gap(status = "downloading", tracks = listOf(track(1, "present"), track(2, "present"), track(3, "downloaded"), track(4, "downloading")))
		held = heldForMatch(held, fetching)
		val running = gapStage(GapInput(fetching, 2, null, GapView.SOURCES, false, false, 100_000L, heldForMatch = held))
		assertIs<GapStage.Active>(running)
		assertEquals(listOf(0, 1), listOf(running.done, running.wanted), "Getting 1 track, 0 of 1")

		// Track 4 arrives; the fill is still settling.
		val arrived = gap(status = "downloading", tracks = listOf(track(1, "present"), track(2, "present"), track(3, "downloaded"), track(4, "downloaded")))
		held = heldForMatch(held, arrived)
		val landing = gapStage(GapInput(arrived, 2, null, GapView.SOURCES, true, false, 100_000L, heldForMatch = held))
		assertIs<GapStage.Active>(landing)
		assertEquals(listOf(1, 1), listOf(landing.done, landing.wanted), "1 of 1")

		// Not a pending match (no downloaded track at picking): every missing track counts, as before.
		assertEquals(2, gapFetchCount(gap(status = "picking")))
		assertEquals(emptySet(), heldForMatch(setOf("0-3"), gap(status = "ready")), "a gap at rest that is no pending match holds nothing")
	}

	/**
	 * Fix round 5, the final VM check: (a) as the fetched track landed, the record read `[present,
	 * present, downloaded, downloaded]` at `picking` with the fetch still committed, `heldForMatch`
	 * read the pending match afresh, took the new track in, and the box said "Getting 2 tracks · 2 of
	 * 2"; (b) a fetch started by Search again from a Failed box (status `failed`, no pending match)
	 * held nothing and counted 2 from the start. What a fetch holds is captured once, as it starts,
	 * and never grows during it.
	 */
	@Test
	fun whatAFetchHoldsIsCapturedAsItStartsAndNeverGrows() {
		fun t(vararg states: String) = states.mapIndexed { i, s -> track(i + 1, s) }
		fun active(g: LbGap, held: Set<String>, committed: Boolean) =
			gapStage(GapInput(g, 2, null, GapView.TRACKS, committed, false, 100_000L, heldForMatch = held)) as GapStage.Active

		// (a) the walk's landing, the fetch committed throughout.
		var held = heldForMatch(emptySet(), gap(status = "picking", tracks = t("present", "present", "downloaded", "missing")))
		val queued = gap(status = "picking", tracks = t("present", "present", "downloaded", "queued"))
		held = heldForMatch(held, queued, fetching = true)
		assertEquals(listOf(0, 1), active(queued, held, committed = true).let { listOf(it.done, it.wanted) })
		val landed = gap(status = "picking", tracks = t("present", "present", "downloaded", "downloaded"))
		held = heldForMatch(held, landed, fetching = true)
		assertEquals(listOf(1, 1), active(landed, held, committed = true).let { listOf(it.done, it.wanted) }, "1 of 1, not 2 of 2")

		// (b) Search again from a Failed box, then Request.
		var fromFailed = heldForMatch(emptySet(), gap(status = "failed", tracks = t("present", "present", "downloaded", "failed")))
		val fetching = gap(status = "downloading", tracks = t("present", "present", "downloaded", "queued"))
		fromFailed = heldForMatch(fromFailed, fetching, fetching = true)
		assertEquals(listOf(0, 1), active(fetching, fromFailed, committed = false).let { listOf(it.done, it.wanted) }, "Getting 1 track")
	}

	/**
	 * Fix round 5, the final VM check: "Pick the best source for me" over only live / remix / cover
	 * folders. lb-bot refuses them all (0 enqueues), the gap stays `picking`, and the reason is in its
	 * source task (`status error`, `error` its sentence). The box went back to "2 tracks missing" with
	 * no reason. The shared rule (desktop results, "The rule: an auto-pick that ended in error (both
	 * clients)"): only the task THIS device started is judged (the id kept on its ledger row); with it
	 * in error and nothing transferring the box is Failed with lb-bot's sentence verbatim, over the
	 * commit hold too; a task this device did not start is never judged.
	 */
	@Test
	fun anAutoPickThatEndedInErrorSaysLbBotsReason() {
		val reason = "3 of 3 source(s) rejected the request (No file in this source matched the missing tracks — closest: '03 - Lantern Three (Cover).flac' (another version of the track))"
		val refused = gap(
			status = "picking",
			tracks = listOf(track(1, "present"), track(2, "present"), track(3, "picked"), track(4, "picked")),
			sourceTask = LbGapTask(id = "t-9", status = "error", error = reason)
		)
		val ours = fill("failed", settled = false).copy(state = "picking", outcome = "running", taskId = "t-9")
		for (view in GapView.entries) {
			assertEquals(GapStage.Failed(reason, false), gapStage(input(gap = refused, fill = ours, view = view)), "$view")
		}
		assertEquals(GapStage.Failed(reason, false), gapStage(input(gap = refused, fill = ours, committed = true)), "over the commit hold")
		// Not judged: no ledger row, or another device's (another id) task.
		assertEquals(GapStage.Folded(2), gapStage(input(gap = refused)))
		assertEquals(GapStage.Folded(2), gapStage(input(gap = refused, fill = ours.copy(taskId = "t-1"))))
		// A real transfer is the truth whatever the task says; an Auto that queued ends `complete`.
		val moving = refused.copy(tracks = listOf(track(1, "present"), track(2, "present"), track(3, "queued"), track(4, "queued")))
		assertIs<GapStage.Active>(gapStage(input(gap = moving, fill = ours)))
		assertEquals(GapStage.Folded(2), gapStage(input(gap = refused.copy(sourceTask = LbGapTask(id = "t-9", status = "complete")), fill = ours)))
	}

	/**
	 * B-231 round 2 (b), the coordinator's ruling for both clients: a plain failed fill (a real
	 * failure, not the `needs_match` settle) on a pending match shows Failed.
	 */
	@Test
	fun aPlainFailedFillOnAPendingMatchIsFailed() {
		val awaiting = gap(status = "picking", tracks = listOf(track(1, "present"), track(2, "downloaded"), track(3, "missing")))
		assertIs<GapStage.Failed>(gapStage(input(gap = awaiting, fill = fill("failed"), view = GapView.TRACKS)))
	}

	@Test
	fun aPendingMatchThatStillOffersTheRestSaysOpenInLbBotAboveIt() {
		val awaiting = gap(status = "picking", tracks = listOf(track(1, "present"), track(2, "downloaded"), track(3, "missing")))
		val stage = gapStage(input(gap = awaiting, view = GapView.TRACKS))
		assertTrue(gapAwaitingMatch(awaiting, stage, downloadsOn = true))
		assertFalse(gapAwaitingMatch(awaiting, stage, downloadsOn = false), "nothing to open while downloading is off")
		val allHere = gap(status = "picking", tracks = listOf(track(1, "present"), track(2, "downloaded")))
		assertFalse(gapAwaitingMatch(allHere, gapStage(input(gap = allHere)), downloadsOn = true), "Attention says it itself")
	}

	/*
	 * B-231, the controller's ruling (2026-11-10, option a): PROTOCOL §15.2 is right. A pending match
	 * (`picking` with a `downloaded` track) makes Open in lb-bot prominent but, unlike a stalled
	 * placement, still offers search, fetch and Auto for the tracks that are not `downloaded`.
	 */
	@Test
	fun aPendingMatchStillOffersSearchFetchAndAutoForTheTracksNotDownloaded() {
		val tracks = listOf(track(1, "present"), track(2, "downloaded"), track(3, "missing"))
		val awaiting = gap(status = "picking", tracks = tracks)
		assertEquals(GapStage.Folded(1), gapStage(input(gap = awaiting)), "the line counts what can still be fetched")
		assertEquals(GapStage.Tracks(listOf(track(3, "missing")), 3), gapStage(input(gap = awaiting, view = GapView.TRACKS)))
		assertEquals(GapStage.Searching, gapStage(input(gap = awaiting, view = GapView.SOURCES)))
		assertEquals(
			GapStage.Choosing(listOf(good), 0),
			gapStage(input(gap = gap(status = "picking", tracks = tracks, sources = listOf(good)), view = GapView.SOURCES))
		)
	}

	@Test
	fun aPendingMatchWithNothingLeftToFetchIsLbBotsToSortOut() {
		val allHere = gap(status = "picking", tracks = listOf(track(1, "present"), track(2, "downloaded")))
		assertIs<GapStage.Attention>(gapStage(input(gap = allHere, view = GapView.SOURCES)))
	}

	@Test
	fun theSourcesViewSearchesThenChoosesPreselectingOnlyASafeSource() {
		assertEquals(GapStage.Searching, gapStage(input(view = GapView.SOURCES)))
		assertEquals(GapStage.Searching, gapStage(input(view = GapView.SOURCES, gap = gap(sourceTask = LbGapTask(status = "running")))))
		assertEquals(
			GapStage.Choosing(listOf(good), 0),
			gapStage(input(view = GapView.SOURCES, gap = gap(status = "picking", sources = listOf(good))))
		)
		assertEquals(
			GapStage.Choosing(listOf(unverified), null),
			gapStage(input(view = GapView.SOURCES, gap = gap(status = "picking", sources = listOf(unverified))))
		)
	}

	@Test
	fun aSearchTapInFlightSearchesEvenOverOldSources() {
		assertEquals(
			GapStage.Searching,
			gapStage(input(view = GapView.SOURCES, searchAsked = true, gap = gap(status = "picking", sources = listOf(good))))
		)
	}

	@Test
	fun aSearchThatEndedWithNothingSaysSoWithLbBotsReason() {
		val none = gap(status = "picking", sourceTask = LbGapTask(status = "complete"), noSourceReason = "No FLAC", mp3WouldHelp = true)
		assertEquals(GapStage.NoSources("No FLAC", true), gapStage(input(view = GapView.SOURCES, gap = none)))
	}

	@Test
	fun aFailedFillKeepsTheBoxOpenUntilTheSourcesAreAskedAgain() {
		val failed = gap(status = "failed", failDetail = "The download itself failed")
		val entry = fill("failed")
		assertEquals(GapStage.Failed("The download itself failed", false), gapStage(input(gap = failed, fill = entry)))
		assertEquals(GapStage.Searching, gapStage(input(gap = failed, fill = entry, view = GapView.SOURCES, searchAsked = true)))
	}

	@Test
	fun aFailedFillShowsOverTheSourcesItWasRequestedFrom() {
		// The poll's summary drops the source rows, and nothing is searching: without this the box
		// sat on "Searching" after the fill failed.
		val failed = gap(status = "failed", failDetail = "The peer went offline")
		assertEquals(
			GapStage.Failed("The peer went offline", false),
			gapStage(input(gap = failed, fill = fill("failed"), view = GapView.SOURCES))
		)
	}

	@Test
	fun aCancelledFillIsJustTheFold() {
		assertEquals(GapStage.Folded(2), gapStage(input(fill = fill("cancelled"))))
	}

	@Test
	fun aFillHasLandedOnceItsRowSettlesDoneOrTheAlbumIsWhole() {
		val moving = gap(status = "downloading", tracks = listOf(track(1, "present"), track(2, "downloading")))
		assertEquals(false, gapFillLanded(moving, fill("done", settled = false)))
		assertEquals(true, gapFillLanded(gap(), fill("done")))
		// Started on another client: no ledger row here, and lb-bot's record says it all.
		assertEquals(true, gapFillLanded(gap(status = "complete"), null))
		assertEquals(true, gapFillLanded(gap(tracks = listOf(track(1, "present"), track(2, "done"))), null))
		assertEquals(false, gapFillLanded(gap(), fill("failed")))
	}

	@Test
	fun heldTracksCountWhatTheLibraryHasBeforeAndAfterTheFill() {
		assertEquals(3, heldTracks(gap(tracks = listOf(track(1, "present"), track(2, "done"), track(3, "done"), track(4, "failed")))))
	}
}
