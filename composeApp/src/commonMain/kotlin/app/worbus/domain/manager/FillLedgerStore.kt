package app.worbus.domain.manager

import app.worbus.util.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The persisted fill ledger: one JSON object in [PreferenceManager.lbBotWatches], keyed by rgid
 * (album downloads) or review group id (gap fills). [LbBotManager] reads and writes it only here,
 * under its `watchLock`.
 */
internal class FillLedgerStore(
	private val preferenceManager: PreferenceManager,
	private val json: Json,
	private val now: () -> Long,
	/** The log, replaceable on the JVM, where `android.util.Log` is not there. */
	private val log: (warn: Boolean, message: String, e: Throwable?) -> Unit = { warn, message, e ->
		if (warn) Logger.w("LbBotManager", message, e) else Logger.e("LbBotManager", message, e)
	}
) {
	/**
	 * Two retention rules, because a running row and a finished one are different things.
	 *
	 * A row still being polled is dropped at `WATCH_TIMEOUT_MS` — twenty minutes, the
	 * wall clock that exists because lb-bot's verifier gives up and leaves a fill on
	 * `placed` forever. A settled one is *history* and is kept for [LbBotManager.LEDGER_RETAIN_MS],
	 * capped at [LbBotManager.LEDGER_MAX] rows so a heavy week can't grow the preference without
	 * bound. Before this, settling and deleting were the same act.
	 */
	fun load(): Map<String, LbWatch> {
		val now = now()
		// Decode and prune SETTLED rows only.
		//
		// An unsettled row past WATCH_TIMEOUT_MS used to be dropped right here, which made the
		// poll loop's "time out the stale ones first" step unreachable: it takes `live` from
		// this function, so the rows it wanted to settle had already ceased to exist. They were
		// deleted instead of finished — no `gave_up` history row, no notification, and a fill
		// the user had been watching simply vanished from the Download Center. Expiry is a state
		// transition and belongs to [applyAlbumStatus], not to the deserializer.
		//
		// B-364: each row is decoded on its own. The map used to be decoded in one piece, and any
		// error answered "empty", which the next save (at every start, `resumeWatches`) wrote back:
		// one row that did not decode erased the whole history. A row that does not decode, or a
		// ledger that is not a JSON object at all, is moved aside ([setAside]) and the rest kept.
		val raw = preferenceManager.lbBotWatches
		val rows = try {
			json.parseToJsonElement(raw) as? JsonObject
		} catch (e: Exception) {
			null
		}
		if (rows == null) {
			setAside(mapOf("$UNPARSED_PREFIX${raw.hashCode()}" to JsonPrimitive(raw)), "the ledger is not a JSON object", null)
			return emptyMap()
		}
		val unreadable = LinkedHashMap<String, JsonElement>()
		var firstError: Throwable? = null
		val all = LinkedHashMap<String, LbWatch>()
		for ((key, element) in rows) {
			try {
				all[key] = json.decodeFromJsonElement(LbWatch.serializer(), element)
			} catch (e: Exception) {
				unreadable[key] = element
				if (firstError == null) firstError = e
			}
		}
		if (unreadable.isNotEmpty()) setAside(unreadable, "${unreadable.size} row(s) did not decode", firstError)
		val kept = all.filterValues { watch ->
			!watch.settled || now - watch.finishedAtOrStart() < LbBotManager.LEDGER_RETAIN_MS
		}
		return if (kept.size <= LbBotManager.LEDGER_MAX) kept
		else kept.entries
			.sortedByDescending { it.value.finishedAtOrStart() }
			.take(LbBotManager.LEDGER_MAX)
			.associate { it.key to it.value }
	}

	fun save(watches: Map<String, LbWatch>) {
		val encoded = try {
			json.encodeToString(watches)
		} catch (e: Exception) {
			// Not "{}": that would erase what is stored. The last good ledger stays.
			log(false, "could not persist fills; the stored ledger is left as it was", e)
			return
		}
		preferenceManager.lbBotWatches = encoded
	}

	/** Forget the rows set aside too (a logout: the next account inherits no ledger). */
	fun forgetSetAside() {
		preferenceManager.lbBotWatchesSetAside = "{}"
	}

	/**
	 * Keep [rows] verbatim in [PreferenceManager.lbBotWatchesSetAside] (the N6 pattern for the cache
	 * database: moved aside, not deleted), the newest [SET_ASIDE_MAX] entries. Nothing reads them back;
	 * they are there to be looked at (`run-as … shared_prefs`), and a later build that can read them
	 * could. Written only when something changes, since [load] runs on every ledger read.
	 */
	private fun setAside(rows: Map<String, JsonElement>, why: String, e: Throwable?) {
		val previous = try {
			json.parseToJsonElement(preferenceManager.lbBotWatchesSetAside) as? JsonObject
		} catch (_: Exception) {
			null
		} ?: JsonObject(emptyMap())
		if (rows.all { (key, value) -> previous[key] == value }) return
		val merged = LinkedHashMap<String, JsonElement>(previous)
		rows.forEach { (key, value) ->
			merged.remove(key)
			merged[key] = value
		}
		val newest = merged.entries.toList().takeLast(SET_ASIDE_MAX).associate { it.key to it.value }
		preferenceManager.lbBotWatchesSetAside = json.encodeToString(JsonObject.serializer(), JsonObject(newest))
		log(true, "fill ledger: $why; kept aside under lbBotWatchesSetAside (${rows.keys.joinToString()})", e)
	}

	private companion object {
		const val UNPARSED_PREFIX = "@unparsed-"
		const val SET_ASIDE_MAX = 20
	}
}
