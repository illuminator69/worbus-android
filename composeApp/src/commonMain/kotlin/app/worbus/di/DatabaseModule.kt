package app.worbus.di

import org.koin.dsl.module
import app.worbus.data.database.CacheDatabase
import app.worbus.data.database.DownloadDatabase
import app.worbus.data.database.WriteQueueDatabase

val databaseModule = module {
	// CacheDatabase is initialised inside PlatformModule since we need Context on android
	// TODO: find a less shitty workaround for that^
	single { get<CacheDatabase>().albumDao() }
	single { get<CacheDatabase>().genreDao() }
	single { get<CacheDatabase>().playlistDao() }
	single { get<CacheDatabase>().songDao() }
	single { get<CacheDatabase>().artistDao() }
	single { get<CacheDatabase>().radioDao() }
	single { get<CacheDatabase>().lyricDao() }
	single { get<CacheDatabase>().syncActionDao() }
	single { get<CacheDatabase>().savedQueueDao() }
	single { get<CacheDatabase>().lbIndexDao() }
	single { get<DownloadDatabase>().downloadDao() }
	// B-373: the offline write queue lives in writes.db, its own file, never dropped (review major 3:
	// not downloads.db, which 0.7.0 would wipe on a rollback if its version moved).
	single { get<WriteQueueDatabase>().pendingWriteDao() }
	// Q-165: what each download was made from, beside the queue in writes.db (version 2).
	single { get<WriteQueueDatabase>().downloadOriginDao() }
}
