package app.worbus.ui.screens.lyrics.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import app.worbus.domain.models.DomainSong
import app.worbus.domain.models.lyrics.LyricsResult
import app.worbus.domain.repositories.LyricsRepository
import app.worbus.ui.core.UiState

class LyricsScreenViewModel(
	private val song: DomainSong?,
	private val repository: LyricsRepository
) : ViewModel() {
	val lyricsState: StateFlow<UiState<LyricsResult?>>
		field = MutableStateFlow<UiState<LyricsResult?>>(UiState.Loading())

	init {
		refreshResults()
	}

	fun refreshResults() {
		viewModelScope.launch {
			if (song == null) {
				lyricsState.value = UiState.Success(null)
				return@launch
			}
			lyricsState.value = UiState.Loading()
			try {
				lyricsState.value = UiState.Success(
					repository.fetchLyrics(song)
				)
			} catch (e: Exception) {
				lyricsState.value = UiState.Error(e)
			}
		}
	}
}
