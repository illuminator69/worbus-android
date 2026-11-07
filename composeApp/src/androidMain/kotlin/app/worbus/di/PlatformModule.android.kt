package app.worbus.di

import androidx.media3.common.util.UnstableApi
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.koin.android.ext.koin.androidApplication
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import app.worbus.data.database.CacheDatabase
import app.worbus.data.database.CheckedFirstOpenDriver
import app.worbus.data.database.DownloadDatabase
import app.worbus.data.database.MIGRATION_CACHE_15_16
import app.worbus.data.database.MIGRATION_CACHE_16_17
import app.worbus.data.database.MIGRATION_CACHE_17_18
import app.worbus.data.database.MIGRATION_CACHE_18_19
import app.worbus.data.database.MIGRATION_CACHE_22_23
import app.worbus.data.database.MIGRATION_CACHE_23_24
import app.worbus.data.database.MIGRATION_DOWNLOAD_3_4
import app.worbus.data.database.MIGRATION_DOWNLOAD_4_5
import app.worbus.data.database.checkCacheDbAtOpen
import app.worbus.domain.manager.AndroidHubLocalDevice
import app.worbus.domain.manager.AndroidLocalNetworkAccess
import app.worbus.domain.manager.LocalNetworkAccess
import app.worbus.domain.manager.LocalSubnet
import app.worbus.domain.manager.AndroidLocalSubnet
import app.worbus.domain.manager.AppIconManager
import app.worbus.domain.manager.AudioGainManager
import app.worbus.domain.manager.CastBridgeStatus
import app.worbus.domain.manager.cast.CastBridgeManager
import app.worbus.domain.manager.ConnectivityManager
import app.worbus.domain.manager.LinkManager
import app.worbus.domain.manager.LogManager
import app.worbus.domain.manager.PermissionManager
import app.worbus.domain.manager.DownloadForegroundController
import app.worbus.domain.manager.HubLocalDevice
import app.worbus.domain.manager.HubNetworkKick
import app.worbus.domain.manager.HubSocketClientFactory
import app.worbus.domain.manager.pingingWebSocketClient
import app.worbus.domain.manager.NotificationManager
import app.worbus.domain.manager.SecretVault
import app.worbus.domain.manager.ShareManager
import app.worbus.domain.manager.StorageManager
import app.worbus.domain.manager.androidSecretVault
import app.worbus.exoplayer.AudioGainProcessor
import app.worbus.shared.AppPresence
import app.worbus.shared.AndroidMediaPlayerViewModel
import app.worbus.shared.MediaPlayerViewModel
import java.io.File

@UnstableApi
actual val platformModule = module {
	single { PlatformType.Android }

	single<CacheDatabase> {
		val dbPath = androidApplication()
			.getDatabasePath("cache.db")
			.absolutePath
		// B-167: a corrupt cache is moved aside before Room opens its first connection, on the
		// thread of that open (a DAO's coroutine, not the main thread at startKoin); one log line
		// (tag CacheDb) says what was found and how long the check took. downloads.db below is
		// never checked or moved: its rows are the only record of the downloaded files.
		Room
			.databaseBuilder<CacheDatabase>(get(), dbPath)
			.setDriver(CheckedFirstOpenDriver(BundledSQLiteDriver()) { checkCacheDbAtOpen(File(dbPath)) })
			// Without this, bumping the cache version for the DownloadEntity change would drop the
			// user's entire cached library and force a full re-sync.
			.addMigrations(
				MIGRATION_CACHE_15_16,
				MIGRATION_CACHE_16_17,
				MIGRATION_CACHE_17_18,
				MIGRATION_CACHE_18_19,
				// Additive (the lb-bot index mirror). Without it the 23 bump wipes the library.
				MIGRATION_CACHE_22_23,
				// Additive (indices only). Without it the 24 bump wipes the library.
				MIGRATION_CACHE_23_24
			)
			.fallbackToDestructiveMigration(true)
			.build()
	}

	single<DownloadDatabase> {
		val dbPath = androidApplication()
			.getDatabasePath("downloads.db")
			.absolutePath
		Room
			.databaseBuilder<DownloadDatabase>(get(), dbPath)
			.setDriver(BundledSQLiteDriver())
			// Unlike the cache DB, this one must NOT be dropped on upgrade: its rows are the only
			// record of which audio files exist on disk. Dropping it would orphan every downloaded
			// file — invisible to the app, still occupying the user's storage. The destructive
			// fallback stays only as a last resort for versions with no migration path.
			.addMigrations(MIGRATION_DOWNLOAD_3_4, MIGRATION_DOWNLOAD_4_5)
			.fallbackToDestructiveMigration(true)
			.build()
	}

	single<MediaPlayerViewModel> {
		AndroidMediaPlayerViewModel(
			application = androidApplication(),
			stateRepository = get(),
			songRepository = get(),
			albumDao = get(),
			downloadManager = get(),
			connectivityManager = get(),
			sessionManager = get(),
			preferenceManager = get(),
			audioGainManager = get(),
			savedQueueRepository = get(),
			snackBarManager = get()
		)
	}

	singleOf(::AppIconManager)
	singleOf(::AudioGainManager)
	singleOf(::AudioGainProcessor)
	singleOf(::LinkManager)
	singleOf(::PermissionManager)
	singleOf(::ShareManager)
	singleOf(::NotificationManager)
	singleOf(::DownloadForegroundController)
	singleOf(::StorageManager)
	singleOf(::ConnectivityManager)
	singleOf(::LogManager)
	// The hub socket's client, with OkHttp-level pings (B-045). HubManager is createdAtStart, so
	// losing this registration fails every launch rather than silently dropping the pings.
	single<HubSocketClientFactory> { HubSocketClientFactory { pingingWebSocketClient() } }
	// What the hub is told this phone is, and its media volume, read from the system and set
	// there. HubManager's other fork-only binding: lost, every launch fails the same way.
	single<HubLocalDevice> { AndroidHubLocalDevice(androidApplication()) }
	// B-108: the local network permission, read by HubManager and LoginManager. Lost, the app dies at
	// launch with NoDefinitionFoundException (HubManager is createdAtStart).
	single<LocalNetworkAccess> { AndroidLocalNetworkAccess(androidApplication()) }
	// Worbus P3: this device's own private IPv4 on Wi-Fi or Ethernet, for the sweep of its /24 (HubFinder,
	// createdAtStart). Lost, the app dies at launch with NoDefinitionFoundException.
	single<LocalSubnet> { AndroidLocalSubnet(androidApplication()) }
	// B-279: the secrets file and its Keystore cipher, read by SecretStore only. Lost, the app dies
	// at launch with NoDefinitionFoundException (PreferenceManager needs SecretStore).
	single<SecretVault> { androidSecretVault(androidApplication()) }

	// Eager: discovery has to already be running when the picker opens, and the bridge has to be
	// up for the speaker to appear in OTHER clients' pickers whether or not this phone's UI is
	// on screen. Same reason HubManager is createdAtStart.
	single(createdAtStart = true) {
		CastBridgeManager(androidApplication(), get(), get()).apply { start() }
	}
	single<CastBridgeStatus> { get<CastBridgeManager>() }

	// Eager: it counts started activities, so it has to exist before the first one starts.
	// Read by the playback service to decide who the volume buttons belong to.
	single(createdAtStart = true) { AppPresence(androidApplication()) }

	// Eager as well: nothing injects it, and it has to be listening before the first network
	// change. It kicks the main hub socket only, never the bridges above.
	single(createdAtStart = true) {
		HubNetworkKick(androidApplication(), get()).apply { start() }
	}
}
