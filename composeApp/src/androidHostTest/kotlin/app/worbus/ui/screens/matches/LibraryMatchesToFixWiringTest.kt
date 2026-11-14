package app.worbus.ui.screens.matches

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * B-406 review M3, M4, L8: how Library Matches holds its To fix section (the rules are LibraryMatchesTest's
 * and AttentionTest's).
 */
class LibraryMatchesToFixWiringTest {
	private val screen = sourceFile("src/commonMain/kotlin/app/worbus/ui/screens/matches/LibraryMatchesScreen.kt").readText()

	@Test
	fun theFirstChoiceIsKeptForTheVisit() {
		assertTrue("if (chosen == null && firstChoiceFinal) chosen = section" in screen, "M3")
	}

	@Test
	fun theChipAndThePagesChoiceReadTheBadgesNumber() {
		assertTrue("toFixNumber(it.page, attentionBadge)" in screen, "M4: one number, the badge's before the first read")
		assertTrue("MatchesSection.TO_FIX -> toFixNumber" in screen)
		assertFalse("toFixChipCount" in screen)
	}

	/** B-406 (second re-walk): the dot stands for both menu badges, as the desktop's sidebar shows both. */
	@Test
	fun theDotAndTheMenuReadBothBadges() {
		val button = sourceFile("src/commonMain/kotlin/app/worbus/ui/components/common/AvatarButton.kt").readText()
		// B-457: both read Source history's badge through the one rule that drops it while downloads are off.
		assertTrue("badge + sourceHistoryMenuBadge(sourceHistory, lbBot.isConfigured, downloads)" in button, "the dot ignores this phone's own failures")
		val menu = sourceFile("src/commonMain/kotlin/app/worbus/ui/components/sheets/AvatarMenu.kt").readText()
		assertTrue("sourceHistoryCount = sourceHistoryMenuBadge(sourceHistory, lbConfigured, downloads)" in menu, "Source history has no badge")
	}

	@Test
	fun nothingOfToFixIsReadForANonAdmin() {
		assertTrue("val toFix = if (roles.admin) rememberToFixUi() else null" in screen, "L8")
	}
}
