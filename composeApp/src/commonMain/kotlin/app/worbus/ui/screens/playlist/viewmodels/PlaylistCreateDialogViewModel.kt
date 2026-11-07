package app.worbus.ui.screens.playlist.viewmodels

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.notice_created_playlist
import app.worbus.data.database.dao.PlaylistDao
import app.worbus.data.database.mappers.toDomainModel
import app.worbus.data.database.mappers.toEntity
import app.worbus.domain.manager.SessionManager
import app.worbus.domain.manager.SnackBarManager
import app.worbus.domain.models.DomainPlaylist
import app.worbus.domain.models.DomainSong
import app.worbus.ui.core.UiState

class PlaylistCreateDialogViewModel(
	private val songs: List<DomainSong>,
	private val playlistDao: PlaylistDao,
	private val sessionManager: SessionManager,
	private val snackBarManager: SnackBarManager
) : ViewModel() {
	val creationState: StateFlow<UiState<Nothing?>>
		field = MutableStateFlow<UiState<Nothing?>>(UiState.Success(null))

	private val _events = Channel<Event>()
	val events = _events.receiveAsFlow()

	val name = TextFieldState()

	fun create() {
		viewModelScope.launch {
			creationState.value = UiState.Loading()
			try {
				val playlist = sessionManager.api.createPlaylist(
					name = name.text.toString(),
					songIds = songs.map { it.id }
				)
				playlistDao.insertPlaylist(playlist.toEntity())
				// The dialog's view model outlives one dialog where it is opened again from the same
				// place (the player's playlist sheet): the next one starts empty.
				name.clearText()
				_events.send(
					Event.Dismiss(
						playlistDao.getPlaylistById(playlist.id)!!.toDomainModel()
					)
				)
				creationState.value = UiState.Success(null)
				snackBarManager.notify(Res.string.notice_created_playlist, playlist.name)
			} catch (e: Exception) {
				creationState.value = UiState.Error(e)
			}
		}
	}

	sealed class Event {
		data class Dismiss(val playlist: DomainPlaylist) : Event()
	}
}
