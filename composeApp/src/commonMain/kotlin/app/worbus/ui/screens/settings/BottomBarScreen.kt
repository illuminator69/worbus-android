package app.worbus.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import app.worbus.di.isLandscape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.toImmutableList
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.option_bottom_bar_collapse_mode
import worbus.composeapp.generated.resources.option_bottom_bar_visibility_mode
import worbus.composeapp.generated.resources.option_hide_bottom_bar_if_idle
import worbus.composeapp.generated.resources.option_mini_player_progress_style
import worbus.composeapp.generated.resources.option_mini_player_style
import worbus.composeapp.generated.resources.title_bars
import worbus.composeapp.generated.resources.title_mini_player
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import app.worbus.di.LocalPlatformContext
import app.worbus.domain.manager.PreferenceManager
import app.worbus.domain.models.settings.BottomBarCollapseMode
import app.worbus.domain.models.settings.BottomBarVisibilityMode
import app.worbus.domain.models.settings.MiniPlayerProgressStyle
import app.worbus.domain.models.settings.MiniPlayerStyle
import app.worbus.ui.components.common.SegmentedListItemDefaults
import app.worbus.ui.components.layouts.NestedTopBar
import app.worbus.ui.components.layouts.NestedTopBarDefaults
import app.worbus.ui.screens.settings.components.SettingsChoiceItem
import app.worbus.ui.screens.settings.components.SettingsGroup
import app.worbus.ui.screens.settings.components.SettingsGroupDefaults
import app.worbus.ui.screens.settings.components.SettingsToggleItem

@Composable
fun BottomBarScreen() {
	val platformContext = LocalPlatformContext.current
	val preferenceManager = koinInject<PreferenceManager>()

	Scaffold(
		topBar = {
			NestedTopBar(
				title = { Text(stringResource(Res.string.title_bars)) },
				navigationAction = { NestedTopBarDefaults.NavigationAction() }
			)
		}
	) { innerPadding ->
		CompositionLocalProvider(
			LocalMinimumInteractiveComponentSize provides 0.dp
		) {
			Column(
				modifier = Modifier
					.padding(innerPadding)
					.verticalScroll(rememberScrollState())
					.padding(horizontal = 16.dp),
				verticalArrangement = Arrangement.spacedBy(SettingsGroupDefaults.GapBetweenGroups)
			) {
				SettingsGroup {
					val count = if (!platformContext.isLandscape()) 2 else 1
					SettingsChoiceItem(
						choices = BottomBarCollapseMode.entries.toImmutableList(),
						selectedChoice = preferenceManager.bottomBarCollapseMode,
						onChoiceSelected = { preferenceManager.bottomBarCollapseMode = it },
						content = { Text(stringResource(Res.string.option_bottom_bar_collapse_mode)) },
						label = { stringResource(it.displayName) },
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = count)
					)

					if (!platformContext.isLandscape()) {
						SettingsChoiceItem(
							choices = BottomBarVisibilityMode.entries.toImmutableList(),
							selectedChoice = preferenceManager.bottomBarVisibilityMode,
							onChoiceSelected = { preferenceManager.bottomBarVisibilityMode = it },
							content = { Text(stringResource(Res.string.option_bottom_bar_visibility_mode)) },
							label = { stringResource(it.displayName) },
							shapes = SegmentedListItemDefaults.segmentedShapes(index = 1, count = count)
						)
					}
				}

				SettingsGroup(title = { Text(stringResource(Res.string.title_mini_player)) }) {
					SettingsChoiceItem(
						choices = MiniPlayerStyle.entries.toImmutableList(),
						selectedChoice = preferenceManager.miniPlayerStyle,
						onChoiceSelected = { preferenceManager.miniPlayerStyle = it },
						content = { Text(stringResource(Res.string.option_mini_player_style)) },
						label = { stringResource(it.displayName) },
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = 3)
					)
					SettingsChoiceItem(
						choices = MiniPlayerProgressStyle.entries.toImmutableList(),
						selectedChoice = preferenceManager.miniPlayerProgressStyle,
						onChoiceSelected = { preferenceManager.miniPlayerProgressStyle = it },
						content = { Text(stringResource(Res.string.option_mini_player_progress_style)) },
						label = { stringResource(it.displayName) },
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 1, count = 3)
					)
					SettingsToggleItem(
						content = { Text(stringResource(Res.string.option_hide_bottom_bar_if_idle)) },
						checked = preferenceManager.hideIfIdle,
						onCheckedChange = { preferenceManager.hideIfIdle = it },
						shapes = SegmentedListItemDefaults.segmentedShapes(index = 2, count = 3)
					)
				}
			}
		}
	}
}
