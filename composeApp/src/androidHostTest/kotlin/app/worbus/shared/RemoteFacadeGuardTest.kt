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
}
