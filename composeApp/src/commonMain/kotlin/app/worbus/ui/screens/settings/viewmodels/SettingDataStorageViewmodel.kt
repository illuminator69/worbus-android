package app.worbus.ui.screens.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.worbus.domain.manager.SyncManager
import app.worbus.domain.manager.WriteQueue
import app.worbus.domain.manager.ConnectivityManager
import app.worbus.domain.manager.DownloadManager
import app.worbus.domain.manager.rebuildLibrary
import app.worbus.domain.repositories.DbRepository

class SettingsDataStorageViewModel(
	private val syncManager: SyncManager,
	private val dbRepository: DbRepository,
	private val writeQueue: WriteQueue,
	private val downloadManager: DownloadManager,
	connectivityManager: ConnectivityManager
) : ViewModel() {

	val syncState = syncManager.syncState
		.stateIn(
			scope = viewModelScope,
			started = SharingStarted.WhileSubscribed(5000),
			initialValue = syncManager.syncState.value
		)

	val pendingActionCount: StateFlow<Int>
		field = MutableStateFlow(0)

	val downloadCount = downloadManager.downloadCount.stateIn(
		viewModelScope, SharingStarted.WhileSubscribed(5000), 0
	)
	val downloadSize = downloadManager.downloadSize.stateIn(
		viewModelScope, SharingStarted.WhileSubscribed(5000), 0L
	)

	val isOnline = connectivityManager.isOnline

	init {
		loadPendingActions()
	}

	/**
	 * Observed, not read once: the row opens the pending-actions browser, where rows can be
	 * removed one by one, and a count taken when this screen opened would be wrong on the way back.
	 */
	private fun loadPendingActions() {
		viewModelScope.launch(Dispatchers.IO) {
			writeQueue.observe().collect { pendingActionCount.value = it.size }
		}
	}

	fun triggerManualSync() {
		syncManager.triggerManualSync()
	}

	fun rebuildDatabase() {
		viewModelScope.launch(Dispatchers.IO) {
			// The pull starts after the wipe (rebuildLibrary), not beside it.
			rebuildLibrary(
				stopSync = syncManager::stopPeriodicSync,
				// B-373: the cached library only. Writes the server has not had survive it, and the
				// pull that follows applies them over what it reads.
				wipe = { dbRepository.removeEverything() },
				sync = syncManager::triggerManualSync
			)
		}
	}

	fun removeAllActions() {
		viewModelScope.launch(Dispatchers.IO) {
			writeQueue.clear()
			pendingActionCount.value = 0
		}
	}

	fun clearAllDownloads() {
		downloadManager.clearAllDownloads()
	}
}
