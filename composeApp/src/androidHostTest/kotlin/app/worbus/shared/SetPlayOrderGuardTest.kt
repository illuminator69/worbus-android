package app.worbus.shared

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertTrue

/*
 * W1 review finding 6: COMMAND_SET_PLAY_ORDER was granted to every controller of the session
 * (Android Auto, Bluetooth, the lock screen, any app). It is the app's own: granted to, and
 * accepted from, its own package only; a bad value is still refused.
 */
class SetPlayOrderGuardTest {
	private val player = sourceFile("src/androidMain/kotlin/app/worbus/shared/MediaPlayer.android.kt").readText()

	@Test
	fun onlyTheAppsOwnControllerIsGrantedTheCommand() {
		val onConnect = player.substringAfter("override fun onConnect(").substringBefore("override fun onCustomCommand(")
		val grant = onConnect.substringBefore("COMMAND_SET_PLAY_ORDER").takeLast(300)
		assertTrue("packageName" in grant, "granted only under a package check:\n$onConnect")
	}

	@Test
	fun onlyTheAppsOwnControllerIsObeyed() {
		val branch = player.substringAfter("COMMAND_SET_PLAY_ORDER -> {").substringBefore("\n\t\t\t\t}\n")
		assertTrue("packageName" in branch, "checked again when it arrives:\n$branch")
		assertTrue("ERROR_BAD_VALUE" in branch, branch)
	}
}
