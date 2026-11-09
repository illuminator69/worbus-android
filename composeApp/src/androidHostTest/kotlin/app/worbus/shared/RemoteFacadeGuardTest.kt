package app.worbus.shared

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertTrue

/*
 * B-148: while another device plays, the session's player is RemoteSessionPlayer, and the app's own
 * MediaController reports the REMOTE session's playlist, index and play state. Nothing the local
 * player does may act on them. These read the source: the controller and the session are media3
 * objects that need a device.
 */
class RemoteFacadeGuardTest {
	private val player = sourceFile("src/androidMain/kotlin/app/worbus/shared/MediaPlayer.android.kt").readText()

	@Test
	fun theDelayedAvailabilitySkipNeverActsThroughTheFacade() {
		// Review finding 3: the skip is launched at a local transition and fires 2 s later. If the
		// facade was swapped in meanwhile, both devices play the same session queue, so "still on
		// the same song" can hold, and seekToNextMediaItem()/pause() then skip or pause the OTHER
		// device's playback through the facade.
		val delayed = player.substringAfter("delay(AVAILABILITY_GRACE_MS)").substringBefore("seekToNextMediaItem()")
		assertTrue(delayed.length < 2_000, "found the delayed skip:\n$delayed")
		assertTrue("showsRemote()" in delayed, "the delayed skip looks at the facade again before it acts:\n$delayed")
	}

	@Test
	fun theAppsControllerIsGrantedTheFacadesMark() {
		// Review finding 4: B-148 depends on the mark reaching the app's own controller as
		// playlistMetadata. media3 blanks playlistMetadata for a controller without
		// COMMAND_GET_METADATA (PlayerInfo.filterByAvailableCommands, 1.11.0), so an onConnect that
		// trims the player commands, or a facade that stops advertising it, would bring B-148 back
		// silently: the facade's index and "playing" written into the local state again.
		val onConnect = player.substringAfter("override fun onConnect(").substringBefore("override fun onCustomCommand(")
		assertTrue(
			"DEFAULT_PLAYER_COMMANDS" in onConnect || "COMMAND_GET_METADATA" in onConnect,
			"the session grants its controllers COMMAND_GET_METADATA:\n$onConnect"
		)
		val facade = sourceFile("src/androidMain/kotlin/app/worbus/shared/RemoteSessionPlayer.kt").readText()
		val commands = facade.substringAfter("private val availableCommands = Player.Commands.Builder()").substringBefore(".build()")
		assertTrue("Player.COMMAND_GET_METADATA" in commands, "the facade advertises COMMAND_GET_METADATA:\n$commands")
		assertTrue(".setPlaylistMetadata(remoteFacadePlaylistMetadata)" in facade, "the facade's state carries the mark")
	}
}
