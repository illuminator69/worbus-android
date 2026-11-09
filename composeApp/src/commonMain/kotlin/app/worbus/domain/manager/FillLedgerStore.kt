package app.worbus.domain.manager

import app.worbus.util.Logger
import kotlinx.serialization.json.Json

/**
 * The persisted fill ledger: one JSON object in [PreferenceManager.lbBotWatches], keyed by rgid
 * (album downloads) or review group id (gap fills). [LbBotManager] reads and writes it only here,
 * under its `watchLock`.
 */
internal class FillLedgerStore(
	private val preferenceManager: PreferenceManager,
	private val json: Json,
	private val now: () -> Long
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
	fun load(): Map<String, LbWatch> = try {
		val now = now()
		// Decode and prune SETTLED rows only.
		//
		// An unsettled row past WATCH_TIMEOUT_MS used to be dropped right here, which made the
		// poll loop's "time out the stale ones first" step unreachable: it takes `live` from
		// this function, so the rows it wanted to settle had already ceased to exist. They were
		// deleted instead of finished — no `gave_up` history row, no notification, and a fill
		// the user had been watching simply vanished from the Download Center. Expiry is a state
		// transition and belongs to [applyAlbumStatus], not to the deserializer.
		val all = json.decodeFromString<Map<String, LbWatch>>(preferenceManager.lbBotWatches)
			.filterValues { watch ->
				!watch.settled || now - watch.finishedAtOrStart() < LbBotManager.LEDGER_RETAIN_MS
			}
		if (all.size <= LbBotManager.LEDGER_MAX) all
		else all.entries
			.sortedByDescending { it.value.finishedAtOrStart() }
			.take(LbBotManager.LEDGER_MAX)
			.associate { it.key to it.value }
	} catch (e: Exception) {
		Logger.w("LbBotManager", "could not read persisted fills, starting empty", e)
		emptyMap()
	}

	fun save(watches: Map<String, LbWatch>) {
		preferenceManager.lbBotWatches = try {
			json.encodeToString(watches)
		} catch (e: Exception) {
			Logger.e("LbBotManager", "could not persist fills", e)
			"{}"
		}
	}
}
