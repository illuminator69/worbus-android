package app.worbus.domain.manager

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * W1 (navi-connect DESIGN-w1-play-order-2026-11-10 §1, §3; B-105, Android half): the playing device
 * owns play order under shuffle and publishes it with its queue; the hub steps that order and
 * patches it for a controller's edits, and the device adopts what the hub sends. Every rule
 * HubManager applies to it is a function here. Today: none of it (the seams name what HubManager
 * does now).
 */

/** `welcome.features` and the hello cap of a device that publishes and adopts play order. */
const val HUB_FEATURE_PLAY_ORDER = "playOrder"

/** The caps this device adds to its hello for play order. */
fun playOrderCaps(): List<String> = emptyList()

/** What a publish is deduped by. */
fun publishSignature(idsSig: String, shuffle: Boolean, order: List<Int>?, hubOffersPlayOrder: Boolean): String = idsSig

/** The fields the playing device's `act:setQueue` adds for its shuffle and play order. */
fun playOrderPublishFields(hubOffersPlayOrder: Boolean, shuffle: Boolean, order: List<Int>?, queueSize: Int): Map<String, JsonElement> =
	emptyMap()

/** Whether the active device publishes its queue while paused. */
fun publishesWhilePaused(hubOffersPlayOrder: Boolean, activeId: String?, myId: String?): Boolean = false

/** The shuffle mode and play order a hub directive (`load`, `queueChanged`, `setShuffle`) hands this device. */
data class DirectivePlayOrder(val shuffle: Boolean?, val order: List<Int>?)

fun directivePlayOrder(msg: JsonObject, hubOffersPlayOrder: Boolean): DirectivePlayOrder = DirectivePlayOrder(null, null)

/** The play order a controller shows for the session another device plays. */
fun mirrorPlayOrder(hubOffersPlayOrder: Boolean, activeDeviceCaps: Set<String>?, shuffle: Boolean, order: List<Int>?, queueSize: Int): List<Int>? =
	null
