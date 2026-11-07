package app.worbus.ui.screens.wishlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.title_wishlist
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import app.worbus.di.LocalBottomBarScrollManager
import app.worbus.di.LocalNavStack
import app.worbus.di.LocalPlatformContext
import app.worbus.di.isLandscape
import app.worbus.domain.manager.LbBotManager
import app.worbus.domain.manager.LbDownloads
import app.worbus.domain.manager.SourceHistorySection
import app.worbus.domain.manager.PreferenceManager
import app.worbus.domain.models.settings.BottomBarVisibilityMode
import app.worbus.ui.components.common.SegmentedListItemDefaults
import app.worbus.ui.components.layouts.NestedTopBar
import app.worbus.ui.components.layouts.PullToRefreshBox
import app.worbus.ui.components.layouts.RootBottomBar
import app.worbus.ui.navigation.Screen
import app.worbus.ui.screens.downloads.viewmodels.WishlistViewModel
import app.worbus.ui.screens.downloads.wishlistSection
import app.worbus.ui.screens.sourcehistory.SourceHistoryScreen
import app.worbus.ui.util.withoutTop

/**
 * Worbus P1 (N3): the wishlist on its own page, from the avatar menu's Library tools, whatever lb-bot's
 * downloads mode. It is Source history's Wishlist chip on a page of its own: the same list
 * (`wishlistSection`), the same view model, the same row (a tap opens the album's page, a swipe takes
 * it off), and Source history keeps its chip while downloading is on. With downloading off the rows say
 * what is saved and never how often it was looked for.
 */
@Composable
fun WishlistScreen() {
	val viewModel = koinViewModel<WishlistViewModel>()
	val state by viewModel.state.collectAsStateWithLifecycle()
	val lbBot = koinInject<LbBotManager>()
	val downloads by lbBot.downloads.collectAsState()
	val backStack = LocalNavStack.current
	val preferenceManager = koinInject<PreferenceManager>()
	val platformContext = LocalPlatformContext.current
	val scope = rememberCoroutineScope()
	var refreshing by remember { mutableStateOf(false) }

	Scaffold(
		topBar = { NestedTopBar(title = { Text(stringResource(Res.string.title_wishlist)) }) },
		bottomBar = {
			val scrollManager = LocalBottomBarScrollManager.current
			if (!platformContext.isLandscape() &&
				preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens
			) {
				RootBottomBar(scrolled = scrollManager.isTriggered)
			}
		}
	) { innerPadding ->
		PullToRefreshBox(
			isRefreshing = refreshing,
			onRefresh = {
				scope.launch {
					refreshing = true
					viewModel.load().join()
					refreshing = false
				}
			},
			modifier = Modifier.padding(top = innerPadding.calculateTopPadding())
		) {
			LazyColumn(
				modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
				contentPadding = innerPadding.withoutTop() + PaddingValues(top = 8.dp, bottom = 24.dp),
				verticalArrangement = Arrangement.spacedBy(SegmentedListItemDefaults.SegmentedGap)
			) {
				wishlistSection(
					state = state,
					onOpen = { item ->
						backStack.add(Screen.ExternalAlbum(rgid = item.rgid, artistName = item.artist, title = item.title))
					},
					onRemove = { viewModel.remove(it.rgid) },
					downloadsOn = downloads.isOn
				)
			}
		}
	}
}

/**
 * Worbus P1: whether Source history's page shows the Wishlist page instead. While downloading is off the
 * avatar menu offers no Source history (its requests, sources and transfers are a download's), but a
 * saved back stack or an old link can still name it; the wishlist is the one part of it that means
 * something then.
 */
fun sourceHistoryShowsWishlist(downloads: LbDownloads): Boolean = !downloads.isOn

/** Source history on [section], or, while downloading is off, the Wishlist page ([sourceHistoryShowsWishlist]). */
@Composable
fun SourceHistoryOrWishlist(section: SourceHistorySection) {
	val downloads by koinInject<LbBotManager>().downloads.collectAsState()
	if (sourceHistoryShowsWishlist(downloads)) WishlistScreen() else SourceHistoryScreen(section)
}
