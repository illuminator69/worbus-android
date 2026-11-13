package app.worbus.ui.screens.sourcehistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.worbus.domain.manager.ActivityRead
import app.worbus.domain.manager.ActivityTransfer
import app.worbus.domain.manager.HistoryGroups
import app.worbus.domain.manager.LbActivity
import app.worbus.domain.manager.LbBotManager
import app.worbus.domain.manager.historyGroups
import app.worbus.domain.manager.sourceHistoryRows

/**
 * What lb-bot said last ([last], null before its first good answer, read at [lastAt] epoch ms) and how
 * the last read went ([status], null before any read has come back).
 */
data class SourceHistoryServer(val last: LbActivity? = null, val status: ActivityRead? = null, val lastAt: Long? = null)

/**
 * Source history (P6): lb-bot's ledger read on demand (`GET /lb/activity`) joined with this device's
 * ([mergeHistory]). The device's rows move the moment the ledger does; lb-bot's are read on opening,
 * on a pull and every [RUNNING_REREAD_MS] while one of its rows is running and the page is on screen
 * (the screen drives that; nobody watching, no read).
 */
class SourceHistoryViewModel(private val lbBot: LbBotManager) : ViewModel() {
	/** How the last read went; what it said is the manager's ([LbBotManager.activityAnswer]). */
	private val _status = MutableStateFlow<ActivityRead?>(null)

	/**
	 * Review M1 (second re-walk): lb-bot's half is the manager's last answer, the one Source history's menu
	 * badge is counted over, so the Need you chip and the badge are one number at every moment (an answer
	 * from an earlier visit included).
	 */
	val server: StateFlow<SourceHistoryServer> = combine(_status, lbBot.activityAnswer) { status, answer ->
		SourceHistoryServer(
			last = answer?.let { LbActivity(version = 1, requests = it.requests, transfers = it.transfers) },
			status = status,
			lastAt = answer?.readAt
		)
	}.stateIn(viewModelScope, SharingStarted.Eagerly, SourceHistoryServer())

	// Follow-up 2026-11-14: the badge's own join (`sourceHistoryRows`): lb-bot's activity, its attention answer
	// (the gaps' state), the ledger and what was dismissed here.
	val groups: StateFlow<HistoryGroups> = combine(
		lbBot.activityAnswer, lbBot.ledger, lbBot.attention, lbBot.dismissedRequests
	) { answer, ledger, attention, dismissed ->
		historyGroups(sourceHistoryRows(ledger, answer, attention, dismissed.keys))
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryGroups(emptyList(), emptyList()))

	val transfers: StateFlow<List<ActivityTransfer>> = lbBot.activityAnswer.map { it?.transfers.orEmpty() }
		.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

	val lbConfigured: Boolean get() = lbBot.isConfigured

	private val _loading = MutableStateFlow(false)
	/** A read of lb-bot is out. */
	val loading: StateFlow<Boolean> = _loading.asStateFlow()

	/** A good answer replaces the last; an older lb-bot forgets it; a failure keeps it on screen (the manager's). */
	fun load(): Job = viewModelScope.launch {
		// Follow-up 2026-11-14: the gaps' own state (a pick waiting) is `/lb/attention`'s; read it beside (at most
		// every 30 s, the manager's rule), not first.
		launch { lbBot.refreshAttention() }
		_loading.value = true
		val read = try {
			lbBot.activity()
		} finally {
			_loading.value = false
		}
		// The manager keeps (or, for an older lb-bot, forgets) the answer itself.
		_status.value = read
	}

	companion object {
		const val RUNNING_REREAD_MS = 10_000L
	}
}
