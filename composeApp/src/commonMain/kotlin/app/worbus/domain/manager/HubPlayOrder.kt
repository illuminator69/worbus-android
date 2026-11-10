package app.worbus.domain.manager

import app.worbus.shared.PlayOrder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/*
 * W1 (navi-connect DESIGN-w1-play-order-2026-11-10 §1, §3; B-105, Android half): the playing device
 * owns play order under shuffle and publishes it with its queue; the hub steps that order and
 * patches it for a controller's edits, and the device adopts what the hub sends. Every rule
 * HubManager applies to it is a function here. All of it only between a hub that lists `playOrder`
 * in `welcome.features` and this device, which lists it in its hello caps; an older hub gets
 * today's frames and sends none of the fields.
 */

/** `welcome.features` and the hello cap of a device that publishes and adopts play order. */
const val HUB_FEATURE_PLAY_ORDER = "playOrder"

/** The caps this device adds to its hello for play order. */
fun playOrderCaps(): List<String> = listOf(HUB_FEATURE_PLAY_ORDER)

/**
 * What a publish is deduped by: the queue's ids, and with a `playOrder` hub its shuffle mode and
 * order too, so turning shuffle on or off, or a new order, is a publish of its own.
 */
fun publishSignature(idsSig: String, shuffle: Boolean, order: List<Int>?, hubOffersPlayOrder: Boolean): String =
	if (!hubOffersPlayOrder) idsSig
	else "$idsSig|${if (shuffle) 1 else 0}|${if (shuffle) order?.joinToString(",").orEmpty() else ""}"

/**
 * The fields the playing device's `act:setQueue` adds: `shuffle`, and `order`, the queue's rows in
 * play order while shuffled, else null. A shuffled state with no order of its rows is not published
 * at all ([publishHeldForOrder]); `order: null` with shuffle on is left for an outside caller.
 */
fun playOrderPublishFields(hubOffersPlayOrder: Boolean, shuffle: Boolean, order: List<Int>?, queueSize: Int): Map<String, JsonElement> {
	if (!hubOffersPlayOrder) return emptyMap()
	val rows = order?.takeIf { shuffle && PlayOrder.isPermutation(it, queueSize) }
	return mapOf(
		"shuffle" to JsonPrimitive(shuffle),
		"order" to (rows?.let { r -> JsonArray(r.map { JsonPrimitive(it) }) } ?: JsonNull)
	)
}

/**
 * Whether the active device publishes its queue while paused: with a `playOrder` hub, so the hub's
 * copy of the queue and its order stays current through a paused edit (a remote Next then steps
 * what this device will play). Never to claim an empty slot (B-047).
 */
fun publishesWhilePaused(hubOffersPlayOrder: Boolean, activeId: String?, myId: String?): Boolean =
	hubOffersPlayOrder && myId != null && activeId == myId

/**
 * The shuffle mode and play order a hub directive (`load`, `queueChanged`, `setShuffle`) hands
 * this device, to adopt verbatim. Null shuffle: the frame says nothing (an older hub), and this
 * player keeps its own mode. A null order with shuffle on: the player makes its own (playing row
 * first) and publishes it.
 */
data class DirectivePlayOrder(val shuffle: Boolean?, val order: List<Int>?)

fun directivePlayOrder(msg: JsonObject, hubOffersPlayOrder: Boolean): DirectivePlayOrder {
	if (!hubOffersPlayOrder) return DirectivePlayOrder(null, null)
	val shuffle = (msg["shuffle"] as? JsonPrimitive)?.booleanOrNull
		?: (msg["on"] as? JsonPrimitive)?.booleanOrNull?.takeIf { msg["cmd"]?.let { (it as? JsonPrimitive)?.content } == "setShuffle" }
		?: return DirectivePlayOrder(null, null)
	val raw = msg["order"] as? JsonArray
	val order = raw?.map { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.intOrNull ?: return DirectivePlayOrder(shuffle, null) }
	return DirectivePlayOrder(shuffle, order.takeIf { shuffle })
}

/**
 * The shuffle mode and order an orphaned session's queue is adopted with (no device playing it,
 * the hub in custody; this device may take it over by playing here, and its claim publishes what
 * it adopted). The session's mode, and its order when it is one of exactly these rows; else the
 * player makes its own (playing row first). An older hub: this player's own mode, as before
 * (W1 review finding 2; design §3 "the device that takes over adopts it").
 */
fun orphanAdoptPlayOrder(hubOffersPlayOrder: Boolean, sessionShuffle: Boolean, sessionOrder: List<Int>?, queueSize: Int): DirectivePlayOrder {
	if (!hubOffersPlayOrder) return DirectivePlayOrder(null, null)
	return DirectivePlayOrder(sessionShuffle, sessionOrder?.takeIf { sessionShuffle && PlayOrder.isPermutation(it, queueSize) })
}

/**
 * A publish waits while the state is shuffled with no order of exactly its rows (W1 review
 * finding 3): `shuffle:true, order:null` made the hub send back an order of its own, which could
 * cross this device's next publish and leave the two on different orders. The state gets its order
 * in the same update as the mode; until it has one there is nothing true to publish.
 */
fun publishHeldForOrder(hubOffersPlayOrder: Boolean, shuffle: Boolean, order: List<Int>?, queueSize: Int): Boolean =
	hubOffersPlayOrder && shuffle && !PlayOrder.isPermutation(order, queueSize)

/**
 * The play order a controller shows for the session another device plays (`session.order`): only
 * when the hub offers `playOrder` and the playing device's row has the cap (then it is what that
 * device plays), the session is shuffled, and the order is of exactly the mirrored rows. Otherwise
 * none, and the queue is listed in queue order as before.
 */
fun mirrorPlayOrder(hubOffersPlayOrder: Boolean, activeDeviceCaps: Set<String>?, shuffle: Boolean, order: List<Int>?, queueSize: Int): List<Int>? {
	if (!hubOffersPlayOrder || activeDeviceCaps == null || HUB_FEATURE_PLAY_ORDER !in activeDeviceCaps || !shuffle) return null
	return order?.takeIf { PlayOrder.isPermutation(it, queueSize) }
}

/**
 * When a publish held back by the hub-driven quiet window (2 s after a directive) is looked at
 * again. Today: never; the next state emission does it, and a paused player emits none.
 */
fun publishRecheckAt(nowMs: Long, hubDrivenUntilMs: Long): Long? = null
