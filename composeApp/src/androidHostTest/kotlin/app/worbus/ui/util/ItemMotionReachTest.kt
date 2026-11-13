package app.worbus.ui.util

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Follow-up 2026-11-14 (red at `fdc5cb5e`, `Followup14RedSourceTest`, not committed): every lazy list's item
 * motion goes through the one speed-aware helper (`animatedItem`, `ItemMotion.kt`), so no site is left on
 * Compose's unscaled `animateItem` defaults; `NavicTheme` provides the speed; and the two finger-driven reads
 * that lb-bot's activity answer feeds are wired (Source history's badge is read on menu open and on an advert).
 */
class ItemMotionReachTest {
	private val root = sourceFile("src/commonMain/kotlin/app/worbus")
	private fun src(p: String) = sourceFile("src/commonMain/kotlin/app/worbus/$p").readText()

	@Test
	fun noListSiteAnimatesAtComposesUnscaledDefaults() {
		val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "ItemMotion.kt" }
			.flatMap { f ->
				f.readLines().withIndex()
					.filter { (_, l) -> ".animateItem(" in l && !l.trim().let { t -> t.startsWith("*") || t.startsWith("//") } }
					.map { (i, _) -> "${f.relativeTo(root)}:${i + 1}" }
			}.toList()
		assertEquals(emptyList(), offenders)
		val sites = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
			.sumOf { f -> Regex("""\banimatedItem\(""").findAll(f.readText()).count() }
		assertTrue(sites >= 79, "only $sites animatedItem sites")
	}

	@Test
	fun theThemeProvidesTheSpeed() {
		assertTrue("LocalItemMotion provides" in src("ui/theme/Theme.kt"))
	}

	@Test
	fun sourceHistorysBadgeReadsLbBotWithoutThePageOpen() {
		assertTrue("lbBot.refreshActivity()" in src("ui/components/sheets/AvatarMenu.kt"), "on menu open")
		val m = src("domain/manager/LbBotManager.kt")
		assertTrue("if (available) requestActivityRefresh()" in m, "on a hub advert")
	}
}
