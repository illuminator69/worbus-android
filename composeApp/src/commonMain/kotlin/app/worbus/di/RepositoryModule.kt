package app.worbus.di

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import app.worbus.domain.repositories.AlbumRepository
import app.worbus.domain.repositories.ArtistRepository
import app.worbus.domain.repositories.CollectionRepository
import app.worbus.domain.repositories.DbRepository
import app.worbus.domain.repositories.GenreRepository
import app.worbus.domain.repositories.LyricsRepository
import app.worbus.domain.repositories.PlayerStateRepository
import app.worbus.domain.repositories.PlaylistRepository
import app.worbus.domain.repositories.RadioRepository
import app.worbus.domain.repositories.SavedQueueRepository
import app.worbus.domain.repositories.SearchRepository
import app.worbus.domain.repositories.ShareRepository
import app.worbus.domain.repositories.SongRepository
import app.worbus.domain.search.LibraryFuzzyIndex

val repositoryModule = module {
	singleOf(::AlbumRepository)
	singleOf(::ArtistRepository)
	singleOf(::DbRepository)
	singleOf(::GenreRepository)
	singleOf(::LyricsRepository)
	singleOf(::SearchRepository)
	singleOf(::LibraryFuzzyIndex)
	singleOf(::ShareRepository)
	singleOf(::CollectionRepository)
	singleOf(::PlaylistRepository)
	singleOf(::SongRepository)
	singleOf(::RadioRepository)
	singleOf(::SavedQueueRepository)
	singleOf(::PlayerStateRepository)
}
