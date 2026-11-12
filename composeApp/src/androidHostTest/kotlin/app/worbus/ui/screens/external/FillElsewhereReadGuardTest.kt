package app.worbus.ui.screens.external

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertTrue

/*
 * B-396: the page reads lb-bot's status for its release-group's pressings and lays it on the box, and
 * makes no ledger row for it: a watch is what puts a row on Downloads and what settles into a
 * notification (LbBotManager.settle), so the page must never start one for a fill it did not start.
 * The source is read because the view model needs Koin, a hub and lb-bot to run.
 */
class FillElsewhereReadGuardTest {
	private val vm = sourceFile("src/commonMain/kotlin/app/worbus/ui/screens/external/viewmodels/ExternalAlbumViewModel.kt").readText()
	private val box = sourceFile("src/commonMain/kotlin/app/worbus/ui/screens/external/components/RequestBox.kt").readText()

	private val follow = vm.substringAfter("private fun followElsewhere(", "").substringBefore("\n\t}\n")

	@Test
	fun thePageReadsLbBotsStatusForItsReleasesWithoutAWatch() {
		assertTrue(follow.isNotBlank(), "ExternalAlbumViewModel.followElsewhere exists")
		assertTrue("lbBotManager.fills(" in follow, "reads /lb/fills for the group's pressings:\n$follow")
		assertTrue("lbBotManager.fills.value" in follow || "lbBotManager.fills\n" in follow || "lbBotManager.fills." in follow,
			"and follows the hub's fill frames lb-bot pushes for it:\n$follow")
		for (writer in listOf("startAlbumFill", "startGapWatch", "saveWatches", "adopt")) {
			assertTrue(writer !in follow, "no ledger row for a fill this device did not start ($writer):\n$follow")
		}
		assertTrue("followElsewhere(" in vm.substringAfter("fun load()").substringBefore("private fun followEdition"), "load() starts it")
	}

	@Test
	fun theBoxIsGivenIt() {
		assertTrue("elsewhere = request.elsewhere" in box, "RequestBox passes lb-bot's status to requestStage")
		assertTrue("stage.startedElsewhere" in box, "and says the fill is another device's")
	}

	@Test
	fun aSecondRequestIsNotOfferedOverIt() {
		val request = vm.substringAfter("fun request(source: LbGapSource?").substringBefore("updateRequest { it.copy(starting = true")
		assertTrue("elsewhere" in request, "request() starts nothing while another device's fill runs:\n$request")
	}
}
