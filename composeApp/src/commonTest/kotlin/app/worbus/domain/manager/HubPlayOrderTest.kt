package app.worbus.domain.manager

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * B-105, Android half (W1, DESIGN-w1-play-order §1, §3): the hub stepped a play order of its own for
 * act:next while this player auto-advanced through media3's, and nothing told the hub which order
 * the player held. With a hub that offers `playOrder` this device advertises the cap, publishes its
 * shuffle and order with every queue (also while paused, when it is the active device), adopts the
 * order a directive carries, and shows the session's order as a controller when the hub vouches.
 */
class HubPlayOrderTest {
	private fun frame(text: String) = Json.parseToJsonElement(text).jsonObject
	private val order = listOf(3, 0, 4, 1, 5, 2)

	@Test
	fun helloAdvertisesThePlayOrderCap() {
		assertTrue(HUB_FEATURE_PLAY_ORDER in playOrderCaps())
	}

	@Test
	fun theActiveDevicePublishesItsShuffleAndOrderWithTheQueue() {
		assertEquals(
			mapOf("shuffle" to JsonPrimitive(true), "order" to JsonArray(order.map { JsonPrimitive(it) })),
			playOrderPublishFields(true, true, order, 6)
		)
		assertEquals(mapOf("shuffle" to JsonPrimitive(false), "order" to JsonNull), playOrderPublishFields(true, false, null, 6))
		// Shuffled without an order of these rows: the hub builds one and sends it back.
		assertEquals(mapOf("shuffle" to JsonPrimitive(true), "order" to JsonNull), playOrderPublishFields(true, true, listOf(0, 1), 6))
		// An older hub: today's frame, nothing added.
		assertEquals(emptyMap(), playOrderPublishFields(false, true, order, 6))
	}

	@Test
	fun aShuffleOrOrderChangeIsAPublish() {
		val ids = "a,b,c,d,e,f"
		val plain = publishSignature(ids, false, null, true)
		assertNotEquals(plain, publishSignature(ids, true, order, true), "shuffle on publishes")
		assertNotEquals(publishSignature(ids, true, order, true), publishSignature(ids, true, order.reversed(), true), "a new order publishes")
		assertEquals(ids, publishSignature(ids, true, order, false), "an older hub: the ids alone, as before")
	}

	@Test
	fun theActiveDevicePublishesWhilePausedButAPausedDeviceNeverClaims() {
		assertTrue(publishesWhilePaused(true, activeId = "me", myId = "me"))
		assertFalse(publishesWhilePaused(true, activeId = null, myId = "me"), "B-047: no claim by a paused device")
		assertFalse(publishesWhilePaused(true, activeId = "other", myId = "me"))
		assertFalse(publishesWhilePaused(false, activeId = "me", myId = "me"), "an older hub: as before")
	}

	@Test
	fun aDirectivesShuffleAndOrderAreAdopted() {
		val load = frame("""{"t":"do","cmd":"load","tracks":[],"index":1,"shuffle":true,"order":[2,0,1]}""")
		assertEquals(DirectivePlayOrder(true, listOf(2, 0, 1)), directivePlayOrder(load, true))
		val off = frame("""{"t":"do","cmd":"queueChanged","tracks":[],"index":0,"shuffle":false,"order":null}""")
		assertEquals(DirectivePlayOrder(false, null), directivePlayOrder(off, true))
		val setShuffle = frame("""{"t":"do","cmd":"setShuffle","on":true,"order":[1,0]}""")
		assertEquals(DirectivePlayOrder(true, listOf(1, 0)), directivePlayOrder(setShuffle, true))
		// An older hub, or a frame without the fields: this player keeps its own mode.
		assertEquals(DirectivePlayOrder(null, null), directivePlayOrder(load, false))
		assertEquals(DirectivePlayOrder(null, null), directivePlayOrder(frame("""{"t":"do","cmd":"load","tracks":[]}"""), true))
		// Not a list of ints: no order (the player makes its own, and publishes it).
		assertEquals(DirectivePlayOrder(true, null), directivePlayOrder(frame("""{"t":"do","cmd":"load","shuffle":true,"order":["x"]}"""), true))
	}

	@Test
	fun aControllerShowsTheSessionsOrderOnlyWhenTheHubAndThePlayerVouch() {
		val caps = setOf("receiver", HUB_FEATURE_PLAY_ORDER)
		assertEquals(order, mirrorPlayOrder(true, caps, true, order, 6))
		assertNull(mirrorPlayOrder(true, setOf("receiver"), true, order, 6), "a 0.7.0 player: the hub's own order, not this rule")
		assertNull(mirrorPlayOrder(false, caps, true, order, 6), "an older hub")
		assertNull(mirrorPlayOrder(true, caps, false, order, 6), "not shuffled")
		assertNull(mirrorPlayOrder(true, caps, true, order, 7), "an order of another queue")
		assertNull(mirrorPlayOrder(true, null, true, order, 6), "no active device")
	}

	@Test
	fun anOrphanTakeoverKeepsTheSessionsShuffleAndOrder() {
		// W1 review finding 2: the orphan adopt loaded the queue in this player's own mode and a fresh
		// order; the claim that followed then replaced the session's.
		assertEquals(DirectivePlayOrder(true, listOf(2, 0, 1)), orphanAdoptPlayOrder(true, true, listOf(2, 0, 1), 3))
		assertEquals(DirectivePlayOrder(false, null), orphanAdoptPlayOrder(true, false, null, 3))
		// Not an order of these rows: the mode, and the player makes its order (playing row first).
		assertEquals(DirectivePlayOrder(true, null), orphanAdoptPlayOrder(true, true, listOf(0, 0, 1), 3))
		// An older hub: as before.
		assertEquals(DirectivePlayOrder(null, null), orphanAdoptPlayOrder(false, true, listOf(2, 0, 1), 3))
	}

	@Test
	fun aShuffledQueueWithoutAnOrderOfItsRowsIsNeverPublished() {
		// W1 review finding 3: shuffle:true with order:null made the hub build and send its own order,
		// which crossed this device's next publish; the two then played different orders.
		assertTrue(publishHeldForOrder(true, true, null, 6))
		assertTrue(publishHeldForOrder(true, true, listOf(0, 1), 6))
		assertFalse(publishHeldForOrder(true, true, order, 6))
		assertFalse(publishHeldForOrder(true, false, null, 6))
		assertFalse(publishHeldForOrder(false, true, null, 6), "an older hub: as before")
	}

	@Test
	fun aPublishHeldByTheQuietWindowIsLookedAtAgainWhenItEnds() {
		// W1 walk: a paused active device whose order changed during the 2 s after a directive
		// published nothing for 60 s: a paused player emits no state to publish on.
		assertEquals(5_000L, publishRecheckAt(nowMs = 3_500L, hubDrivenUntilMs = 5_000L))
		assertNull(publishRecheckAt(nowMs = 5_000L, hubDrivenUntilMs = 5_000L), "not held: nothing to look at again")
	}

	@Test
	fun aDeviceJustMadeActiveDoesNotPublishItsStaleQueueWhilePaused() {
		// W1 re-walk: a transfer commits this device as active before its do:load is applied; the
		// player swap in that moment emitted a state, and the paused publish sent the device's
		// PREVIOUS queue and order, which the hub adopted. Until it holds the session's queue (its
		// load applied, or its own publish while playing) a paused device publishes nothing.
		assertFalse(publishesWhilePaused(true, activeId = "me", myId = "me", holdsSessionQueue = false))
		assertTrue(publishesWhilePaused(true, activeId = "me", myId = "me", holdsSessionQueue = true))
	}
}
