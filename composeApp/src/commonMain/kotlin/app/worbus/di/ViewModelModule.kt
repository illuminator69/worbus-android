package app.worbus.di

import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import app.worbus.domain.models.DomainAlbumListType
import app.worbus.domain.models.DomainArtistListType
import app.worbus.domain.models.DomainFilter
import app.worbus.domain.models.DomainSong
import app.worbus.domain.models.DomainSongListType
import app.worbus.ui.components.dialogs.DeletionViewModel
import app.worbus.ui.components.sheets.ChangelogViewModel
import app.worbus.ui.screens.album.viewmodels.AlbumListViewModel
import app.worbus.ui.screens.artist.viewmodels.ArtistDetailViewModel
import app.worbus.ui.screens.artist.viewmodels.ArtistListViewModel
import app.worbus.ui.screens.collection.viewmodels.CollectionDetailViewModel
import app.worbus.ui.screens.external.viewmodels.ExternalAlbumViewModel
import app.worbus.ui.screens.external.viewmodels.ExternalArtistViewModel
import app.worbus.ui.screens.browse.BrowseFeedViewModel
import app.worbus.ui.screens.browse.BrowseKind
import app.worbus.ui.screens.discover.viewmodels.DiscoverViewModel
import app.worbus.ui.screens.fresh.viewmodels.FreshViewModel
import app.worbus.ui.screens.home.HomeViewModel
import app.worbus.ui.screens.home.mood.MoodMixViewModel
import app.worbus.ui.screens.mixes.viewmodels.MixListViewModel
import app.worbus.ui.screens.radio.custom.CustomRadioViewModel
import app.worbus.ui.screens.mixes.viewmodels.MixDetailViewModel
import app.worbus.ui.screens.library.viewmodels.LibraryViewModel
import app.worbus.ui.screens.listening.viewmodels.ListeningCardViewModel
import app.worbus.ui.screens.listening.viewmodels.ListeningViewModel
import app.worbus.ui.screens.downloads.viewmodels.WishlistViewModel
import app.worbus.ui.screens.genre.viewmodels.GenreListViewModel
import app.worbus.ui.screens.lyrics.viewmodels.LyricsScreenViewModel
import app.worbus.ui.screens.nowPlaying.viewmodels.NowPlayingViewModel
import app.worbus.ui.components.sheets.AddToPlaylistViewModel
import app.worbus.ui.screens.playlist.viewmodels.PlaylistCreateDialogViewModel
import app.worbus.ui.screens.playlist.viewmodels.PlaylistListViewModel
import app.worbus.ui.screens.playlist.viewmodels.PlaylistUpdateDialogViewModel
import app.worbus.ui.screens.queue.viewmodels.QueueViewModel
import app.worbus.ui.screens.queue.viewmodels.RelatedSongsViewModel
import app.worbus.ui.screens.savedqueues.viewmodels.SavedQueuesViewModel
import app.worbus.ui.screens.radio.viewmodels.RadioCreateDialogViewModel
import app.worbus.ui.screens.radio.viewmodels.RadioListViewModel
import app.worbus.ui.screens.search.viewmodels.SearchViewModel
import app.worbus.ui.screens.settings.viewmodels.LyricsPriorityViewModel
import app.worbus.ui.screens.settings.viewmodels.NavtabsViewModel
import app.worbus.ui.screens.downloads.viewmodels.DownloadCenterViewModel
import app.worbus.ui.screens.attention.AttentionViewModel
import app.worbus.ui.screens.sourcehistory.SourceHistoryViewModel
import app.worbus.ui.screens.matches.viewmodels.LibraryMatchesViewModel
import app.worbus.ui.screens.settings.viewmodels.PendingActionsViewModel
import app.worbus.ui.screens.settings.viewmodels.SettingsDataStorageViewModel
import app.worbus.ui.screens.share.viewmodels.ShareDialogViewModel
import app.worbus.ui.screens.share.viewmodels.ShareListViewModel
import app.worbus.ui.screens.song.viewmodels.SongDetailViewModel
import app.worbus.ui.screens.song.viewmodels.SongListViewModel
import app.worbus.domain.repositories.SongListArtist
import app.worbus.ui.screens.stats.viewmodels.StatisticsViewModel
import app.worbus.ui.viewmodel.RootViewModel

val viewModelModule = module {
	viewModelOf(::ArtistDetailViewModel)
	viewModelOf(::FreshViewModel)
	viewModelOf(::HomeViewModel)
	viewModelOf(::DiscoverViewModel)
	// P3: Charts and Editors' picks, one view model each, keyed by which.
	viewModel { (kind: BrowseKind) -> BrowseFeedViewModel(kind, get(), get(), get()) }
	viewModelOf(::MixListViewModel)
	viewModelOf(::CustomRadioViewModel)
	viewModel { params ->
		MixDetailViewModel(
			mixId = params.get(),
			hubManager = get(),
			radioManager = get(),
			songRepository = get(),
			downloadManager = get()
		)
	}
	// P7: a Home mood tile's mix page, keyed by the mood and the tile.
	viewModel { (moodId: String, variantId: String) ->
		MoodMixViewModel(
			moodId = moodId,
			variantId = variantId,
			moodManager = get(),
			songDao = get(),
			radioManager = get(),
			songRepository = get(),
			downloadManager = get()
		)
	}
	viewModelOf(::LibraryViewModel)
	viewModelOf(::WishlistViewModel)
	viewModelOf(::ListeningViewModel)
	viewModelOf(::ListeningCardViewModel)

	// Parameterised on their route keys — a MusicBrainz artist mbid and a
	// release-group id, neither of which is resolvable from the graph.
	viewModel { (artistMbid: String, name: String) ->
		ExternalArtistViewModel(
			artistMbid = artistMbid,
			artistName = name,
			lbBotManager = get(),
			lbIndexSync = get(),
			connectivityManager = get()
		)
	}
	viewModel { (rgid: String, artistMbid: String, artistName: String, artistId: String, title: String) ->
		ExternalAlbumViewModel(
			rgid = rgid,
			artistMbid = artistMbid,
			artistName = artistName,
			artistId = artistId,
			title = title,
			artistDao = get(),
			albumDao = get(),
			dbRepository = get(),
			lbBotManager = get(),
			lbIndexSync = get(),
			previewManager = get(),
			mediaPlayer = get(),
			fillActions = get(),
			connectivityManager = get()
		)
	}

	viewModel { (song: DomainSong?) ->
		LyricsScreenViewModel(
			song = song,
			repository = get()
		)
	}

	viewModel { (songs: List<DomainSong>, playlistToExclude: String?) ->
		PlaylistUpdateDialogViewModel(
			songs = songs,
			playlistToExclude = playlistToExclude,
			sessionManager = get(),
			snackBarManager = get()
		)
	}

	viewModel { params ->
		AlbumListViewModel(
			initialListType = params.getOrNull<DomainAlbumListType>() ?: DomainAlbumListType.AlphabeticalByArtist,
			initialFilters = params.getOrNull<Set<DomainFilter>>(),
			repository = get(),
			sessionManager = get(),
			preferenceManager = get()
		)
	}
	viewModel { params ->
		SongListViewModel(
			initialListType = params.getOrNull<DomainSongListType>() ?: DomainSongListType.FrequentlyPlayed,
			initialFilters = params.getOrNull<Set<DomainFilter>>(),
			artist = params.getOrNull<SongListArtist>(),
			repository = get(),
			downloadManager = get(),
			sessionManager = get(),
			preferenceManager = get(),
			connectivityManager = get()
		)
	}
	viewModel { params ->
		ArtistListViewModel(
			initialListType = params.getOrNull<DomainArtistListType>() ?: DomainArtistListType.AlphabeticalByName,
			initialFilters = params.getOrNull<Set<DomainFilter>>(),
			repository = get(),
			albumDao = get(),
			sessionManager = get(),
			preferenceManager = get()
		)
	}
	viewModelOf(::SearchViewModel)
	viewModelOf(::GenreListViewModel)
	viewModelOf(::RadioListViewModel)
	viewModelOf(::RadioCreateDialogViewModel)
	viewModelOf(::PlaylistListViewModel)
	viewModelOf(::QueueViewModel)
	viewModelOf(::RelatedSongsViewModel)
	viewModelOf(::SavedQueuesViewModel)
	viewModelOf(::ShareListViewModel)
	viewModelOf(::DeletionViewModel)
	viewModelOf(::ShareDialogViewModel)
	viewModel { (songs: List<DomainSong>) ->
		PlaylistCreateDialogViewModel(
			songs = songs,
			playlistDao = get(),
			sessionManager = get(),
			snackBarManager = get()
		)
	}
	viewModel { params ->
		CollectionDetailViewModel(
			collectionId = params.get(),
			repository = get(),
			songRepository = get(),
			albumRepository = get(),
			downloadManager = get(),
			sessionManager = get(),
			snackBarManager = get(),
			lbBotManager = get(),
			lbIndexSync = get(),
			connectivityManager = get()
		)
	}
	viewModelOf(::SongDetailViewModel)
	viewModelOf(::SettingsDataStorageViewModel)
	viewModelOf(::PendingActionsViewModel)
	viewModelOf(::LibraryMatchesViewModel)
	viewModelOf(::DownloadCenterViewModel)
	viewModelOf(::AttentionViewModel)
	viewModelOf(::SourceHistoryViewModel)
	viewModelOf(::ChangelogViewModel)
	viewModel { params ->
		NowPlayingViewModel(
			player = params.get(),
			songRepository = get(),
			savedQueueRepository = get(),
			hubManager = get()
		)
	}
	viewModel { params ->
		AddToPlaylistViewModel(
			song = params.get(),
			playlistDao = get(),
			dbRepository = get(),
			sessionManager = get()
		)
	}
	viewModelOf(::NavtabsViewModel)
	viewModelOf(::LyricsPriorityViewModel)
	viewModelOf(::RootViewModel)
	viewModelOf(::StatisticsViewModel)
}
