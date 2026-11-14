package app.worbus.domain.manager

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * B-444 (seen on the VM AVD, 2026-11-14): Library Matches opened before lb-bot's role and to-fix number are known kept
 * Artists for the whole visit, though To fix (41) was then offered: the first choice was made final as soon as To check
 * was read, reading an unknown role (To fix not offered yet) or an unread to-fix number as "nothing to fix".
 */
class MatchesFirstChoiceUnknownTest {
	private val withFix = listOf(MatchesSection.TO_FIX, MatchesSection.TO_CHECK, MatchesSection.ARTISTS)
	private val withoutFix = listOf(MatchesSection.TO_CHECK, MatchesSection.ARTISTS)

	@Test
	fun anUnknownRoleIsNotNoToFix() {
		// The hub has not said who this user is: To fix is not offered yet, and may be in a moment.
		assertFalse(matchesFirstChoiceFinal(withoutFix, toFixCount = 0, toCheckRead = true, toFixRead = false, rolesKnown = false))
		// A non-admin, known: To check and Artists are all there is.
		assertTrue(matchesFirstChoiceFinal(withoutFix, toFixCount = 0, toCheckRead = true, toFixRead = false, rolesKnown = true))
	}

	@Test
	fun anUnreadToFixNumberIsNotZero() {
		// An admin, `/lb/attention` not answered yet (held 15 s on the VM): To check alone decides nothing.
		assertFalse(matchesFirstChoiceFinal(withFix, toFixCount = 0, toCheckRead = true, toFixRead = false, rolesKnown = true))
		// Answered, nothing to fix: final.
		assertTrue(matchesFirstChoiceFinal(withFix, toFixCount = 0, toCheckRead = true, toFixRead = true, rolesKnown = true))
		// Something to fix, known: To fix wins whatever To check says.
		assertTrue(matchesFirstChoiceFinal(withFix, toFixCount = 41, toCheckRead = false, toFixRead = true, rolesKnown = true))
	}

	// Stage 2 review M1: no section is drawn before the choice is final (`matchesFirstChoiceShown` is gone): the rows'
	// skeleton, MatchesSkeletonTest.

	@Test
	fun aHubOutOfReachSettlesAnUnknownRole() {
		// N4: no welcome is on its way while dialling fails, so an unknown role does not hold the page's skeleton.
		assertTrue(matchesRolesSettled(rolesKnown = false, hubUnreachable = true))
		assertFalse(matchesRolesSettled(rolesKnown = false, hubUnreachable = false))
		assertTrue(matchesRolesSettled(rolesKnown = true, hubUnreachable = false))
	}
}
