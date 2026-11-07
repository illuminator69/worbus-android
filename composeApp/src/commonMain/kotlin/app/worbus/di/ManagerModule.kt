package app.worbus.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.dsl.singleOf
import app.worbus.domain.manager.BrowseResolver
import org.koin.dsl.module
import app.worbus.domain.manager.AudioMuseManager
import app.worbus.domain.manager.CastScrobbler
import app.worbus.domain.manager.AlbumModeSmartPlaylists
import app.worbus.domain.manager.DownloadManager
import app.worbus.domain.manager.EqualiserManager
import app.worbus.domain.manager.HubAuth
import app.worbus.domain.manager.HubFinder
import app.worbus.domain.manager.LocalNetworkAccess
import app.worbus.domain.manager.LocalSubnet
import app.worbus.domain.manager.HubLocalDevice
import app.worbus.domain.manager.HubManager
import app.worbus.domain.manager.FillActions
import app.worbus.domain.manager.LbBotManager
import app.worbus.domain.manager.LbIndexSync
import app.worbus.domain.manager.LoginManager
import app.worbus.domain.manager.NativeApiManager
import app.worbus.domain.manager.PlaylistDownloadManager
import app.worbus.domain.manager.PreferenceManager
import app.worbus.domain.manager.PreviewManager
import app.worbus.domain.manager.RadioManager
import app.worbus.domain.manager.SecretStore
import app.worbus.domain.manager.SessionManager
import app.worbus.domain.manager.ShellController
import app.worbus.ui.viewmodel.RootEventBus
import app.worbus.domain.manager.SleepTimerManager
import app.worbus.domain.manager.SnackBarManager
import app.worbus.domain.manager.StationManager
import app.worbus.domain.manager.MoodManager
import app.worbus.domain.manager.SyncManager
import app.worbus.ui.util.AmbientColorHolder
import app.worbus.ui.util.CoverSeedPrefetcher
import app.worbus.ui.util.CoverSeedStore

val managerModule = module {
	singleOf(::AmbientColorHolder)
	// P3: where a Deezer browse tile opens, for Discover and the Charts / Editors' picks pages.
	singleOf(::BrowseResolver)
	// I-043: cover seed colours, persisted. An explicit lambda: singleOf would try to resolve the
	// constructor's default parameters. Main-thread only, so its delayed write runs on Main too.
	single {
		CoverSeedStore(
			load = { get<PreferenceManager>().coverSeeds },
			save = { get<PreferenceManager>().coverSeeds = it },
			scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
		)
	}
	// I-043: fills the store ahead of need (tiles on screen, the next songs).
	single { CoverSeedPrefetcher(get(), get(), get()) }
	singleOf(::SleepTimerManager)
	// startPeriodicSync() does nothing without a session (B-098); a login starts it after its pull.
	single(createdAtStart = true) {
		SyncManager(get(), get(), get(), get(), get(), get(), get(), get(), get()).apply {
			startPeriodicSync()
		}
	}
	singleOf(::DownloadManager)
	singleOf(::AlbumModeSmartPlaylists)
	singleOf(::SessionManager)
	singleOf(::PreferenceManager)
	// B-279: the password and the two tokens, sealed in their own file. The vault (the file and the
	// cipher) is registered by each platform module; an explicit lambda for the default parameter.
	single { SecretStore(get(), get()) }
	// Worbus P2: the device's credential on the Worbus Server (sign-in, the secret, the legacy token).
	// Every hub caller takes it; it takes nothing that takes it. HubLocalDevice is the platform's.
	single { HubAuth(get<PreferenceManager>(), get<SecretStore>(), get<SessionManager>(), get<HubLocalDevice>()) }
	singleOf(::AudioMuseManager)
	// Lazy, unlike LbBotManager below: previews have no background work to resume —
	// nothing is watched, nothing outlives the process — so the first screen that
	// asks about them is early enough.
	singleOf(::PreviewManager)
	// createdAtStart so a fill that outlived the process is picked back up without
	// waiting for the user to open the artist page it was started from.
	// The second get() is LbIndexDao, for the `lb_response_cache` half only (cachedGet); the
	// third is DbRepository, for what follows an album override job (I-022).
	single(createdAtStart = true) {
		LbBotManager(get(), get(), get(), get()).apply { resumeWatches() }
	}
	// The lb-bot index mirror's sync worker. Constructed by HubManager and SyncManager (both
	// createdAtStart), which own its three triggers: welcome, the `index` frame, the 15-min cycle.
	singleOf(::LbIndexSync)
	// The buttons of a fill (retry, cancel, allow MP3, wishlist, dismiss), shared by the Downloads
	// page and the album page's request box (P4).
	singleOf(::FillActions)
	// get()s 8 to 10 are fork-only bindings each platform module registers: HubSocketClientFactory,
	// HubLocalDevice and LocalNetworkAccess (B-108; LoginManager takes it too). The last is HubAuth.
	single(createdAtStart = true) {
		HubManager(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()).apply { start() }
	}
	// Worbus P3: looking for the Worbus Server. createdAtStart: it gives HubManager its quiet look for
	// a home address before each reconnect. The last get() is LocalSubnet, the platform's (Android only).
	single(createdAtStart = true) { HubFinder(get<PreferenceManager>(), get<SessionManager>(), get<HubManager>(), get<LocalNetworkAccess>(), get<LocalSubnet>()) }
	// createdAtStart, and never lazily: a cast session is precisely the case where no screen is
	// open and no local player is running, so nothing else would ever construct this.
	single(createdAtStart = true) {
		CastScrobbler(get(), get(), get(), get(), get(), get()).apply { start() }
	}
	// The hub's station engine (PROTOCOL §18): HTTP, availability, and the record of the station
	// that built this device's queue. Constructed by RadioManager below at start, after HubManager,
	// whose queue publish it supplies the bound station id to.
	singleOf(::StationManager)
	// Home's moods (P7): the hub's catalogue and a tile's ids.
	singleOf(::MoodManager)
	// createdAtStart so the autoplay observer is running before the first radio use. The last two
	// get()s are StationManager and SongRepository (its star events steer a bound station).
	single(createdAtStart = true) { RadioManager(get(), get(), get(), get(), get(), get(), get(), get()) }
	singleOf(::NativeApiManager)
	single(createdAtStart = true) { PlaylistDownloadManager(get(), get(), get(), get()) }
	singleOf(::SnackBarManager)
	// P1: what the shell has open (the New sheet, the avatar menu), opened from any bar or header.
	singleOf(::ShellController)
	// Audit L2-03: the rail's RootViewModel and the pages' share their events through this one store.
	singleOf(::RootEventBus)
	singleOf(::LoginManager)
	singleOf(::EqualiserManager)
}
