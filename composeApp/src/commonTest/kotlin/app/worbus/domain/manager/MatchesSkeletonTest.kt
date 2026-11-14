package app.worbus.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/*
 * Stage 2 review M1 (B-444 follow-up): B-444 made the first choice final only on known numbers, but kept drawing a
 * section while the choice could still move (`matchesFirstChoiceShown`). An admin who opened Library Matches while
 * `/lb/attention` was slow saw To check drawn (the list arrived first), then a cross-fade to To fix when attention
 * answered with something: the list moved under a finger that may already have been on a To check row. The owner's
 * rules: a slow lookup gets a skeleton; a list does not swap by itself. So until the choice is final, the rows'
 * skeleton, and the first section drawn is the one kept.
 */
class MatchesSkeletonTest {
	private val withFix = listOf(MatchesSection.TO_FIX, MatchesSection.TO_CHECK, MatchesSection.ARTISTS)

	/** One moment of the page, as the screen reads it, and what it draws (null: the skeleton). */
	private data class Moment(val toFixCount: Int, val toCheckRows: Int, val toCheckRead: Boolean, val toFixRead: Boolean, val rolesKnown: Boolean = true)

	private fun drawn(moments: List<Moment>): List<MatchesSection?> {
		var chosen: MatchesSection? = null
		return moments.map { m ->
			val offered = if (m.rolesKnown) withFix else withFix - MatchesSection.TO_FIX
			val section = matchesSectionToShow(chosen, offered, m.toCheckRows, m.toFixCount)
			val shown = matchesPageSection(section, chosen, offered, m.toFixCount, m.toCheckRead, m.toFixRead, m.rolesKnown)
			// The screen's LaunchedEffect: a final first choice is kept for the visit.
			if (chosen == null && matchesFirstChoiceFinal(offered, m.toFixCount, m.toCheckRead, m.toFixRead, m.rolesKnown)) chosen = section
			shown
		}
	}

	@Test
	fun aSlowToFixAnswerIsASkeletonNotToCheckThenASwap() {
		val seen = drawn(
			listOf(
				Moment(toFixCount = 0, toCheckRows = 0, toCheckRead = false, toFixRead = false),
				// To check arrives first; attention is still out.
				Moment(toFixCount = 0, toCheckRows = 5, toCheckRead = true, toFixRead = false),
				// Attention answers: 41 to fix.
				Moment(toFixCount = 41, toCheckRows = 5, toCheckRead = true, toFixRead = true)
			)
		)
		assertEquals(listOf(null, null, MatchesSection.TO_FIX), seen, "the skeleton, then the one section kept")
	}

	@Test
	fun aKnownAnswerOfNothingToFixDrawsToCheckOnce() {
		val seen = drawn(
			listOf(
				Moment(toFixCount = 0, toCheckRows = 5, toCheckRead = true, toFixRead = false),
				Moment(toFixCount = 0, toCheckRows = 5, toCheckRead = true, toFixRead = true),
				// A Keep on the last To check row: the section stays (chosen).
				Moment(toFixCount = 0, toCheckRows = 0, toCheckRead = true, toFixRead = true)
			)
		)
		assertEquals(listOf(null, MatchesSection.TO_CHECK, MatchesSection.TO_CHECK), seen)
	}

	@Test
	fun aChipThePersonPickedIsDrawnAtOnce() {
		assertEquals(
			MatchesSection.ARTISTS,
			matchesPageSection(MatchesSection.ARTISTS, MatchesSection.ARTISTS, withFix, 0, toCheckRead = false, toFixRead = false, rolesKnown = true)
		)
		assertNull(matchesPageSection(MatchesSection.TO_CHECK, null, withFix, 0, toCheckRead = true, toFixRead = false, rolesKnown = true))
	}
}
