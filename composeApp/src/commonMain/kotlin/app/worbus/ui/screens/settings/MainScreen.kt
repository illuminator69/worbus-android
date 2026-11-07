package app.worbus.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.dropUnlessResumed
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.subtitle_about
import worbus.composeapp.generated.resources.subtitle_appearance
import worbus.composeapp.generated.resources.subtitle_data_storage
import worbus.composeapp.generated.resources.subtitle_developer
import worbus.composeapp.generated.resources.subtitle_server
import worbus.composeapp.generated.resources.subtitle_playback
import worbus.composeapp.generated.resources.subtitle_quality_data
import worbus.composeapp.generated.resources.title_about
import worbus.composeapp.generated.resources.title_appearance
import worbus.composeapp.generated.resources.title_data_storage
import worbus.composeapp.generated.resources.title_developer
import worbus.composeapp.generated.resources.title_server
import worbus.composeapp.generated.resources.title_playback
import worbus.composeapp.generated.resources.title_quality_data
import worbus.composeapp.generated.resources.title_settings
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import app.worbus.di.LocalNavStack
import app.worbus.di.LocalPlatformContext
import app.worbus.domain.manager.PreferenceManager
import app.worbus.icons.Icons
import app.worbus.icons.filled.Info
import app.worbus.icons.filled.Palette
import app.worbus.icons.outlined.ChevronForward
import app.worbus.icons.outlined.Code
import app.worbus.icons.outlined.Computer
import app.worbus.icons.outlined.DataTable
import app.worbus.icons.outlined.Note
import app.worbus.icons.outlined.Speed
import app.worbus.ui.components.common.SegmentedListItem
import app.worbus.ui.components.common.SegmentedListItemDefaults
import app.worbus.ui.components.layouts.NestedTopBar
import app.worbus.ui.navigation.Screen
import app.worbus.ui.screens.settings.components.SettingsGroup
import app.worbus.ui.screens.settings.components.SettingsGroupDefaults
import app.worbus.ui.theme.defaultFont

/** One row of the Settings list. */
private class SettingsPage(
	val destination: Screen,
	val icon: ImageVector,
	val iconSize: Dp = 22.dp,
	val title: StringResource,
	val subtitle: StringResource
)

@Composable
fun SettingsScreen() {
	// Developer options (crash-the-app, test tools) are for debug builds only.
	val showDeveloper = LocalPlatformContext.current.isDebugBuild
	val pages = listOf(
		// The Server first: nothing else in the app works for a stranger until it is set up.
		SettingsPage(Screen.Settings.NaviConnect, Icons.Outlined.Computer, 24.dp, Res.string.title_server, Res.string.subtitle_server),
		SettingsPage(Screen.Settings.Playback, Icons.Outlined.Note, 24.dp, Res.string.title_playback, Res.string.subtitle_playback),
		SettingsPage(Screen.Settings.StreamingQuality, Icons.Outlined.Speed, 24.dp, Res.string.title_quality_data, Res.string.subtitle_quality_data),
		// Now Playing and Bars are Appearance's pages now.
		SettingsPage(Screen.Settings.Appearance, Icons.Filled.Palette, 24.dp, Res.string.title_appearance, Res.string.subtitle_appearance),
		SettingsPage(Screen.Settings.DataStorage, Icons.Outlined.DataTable, 24.dp, Res.string.title_data_storage, Res.string.subtitle_data_storage)
	)
	val aboutPages = buildList {
		add(SettingsPage(Screen.Settings.About, Icons.Filled.Info, 22.dp, Res.string.title_about, Res.string.subtitle_about))
		if (showDeveloper) {
			add(SettingsPage(Screen.Settings.Developer, Icons.Outlined.Code, 24.dp, Res.string.title_developer, Res.string.subtitle_developer))
		}
	}
	Scaffold(
		topBar = { NestedTopBar({ Text(stringResource(Res.string.title_settings)) }) }
	) { innerPadding ->
		Column(
			modifier = Modifier
				.padding(innerPadding)
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 16.dp),
			verticalArrangement = Arrangement.spacedBy(SettingsGroupDefaults.GapBetweenGroups)
		) {
			SettingsGroup {
				pages.forEachIndexed { index, page ->
					PageRow(
						destination = page.destination,
						icon = page.icon,
						iconSize = page.iconSize,
						title = page.title,
						subtitle = page.subtitle,
						shapes = SegmentedListItemDefaults.segmentedShapes(index = index, count = pages.size)
					)
				}
			}
			SettingsGroup {
				aboutPages.forEachIndexed { index, page ->
					PageRow(
						destination = page.destination,
						icon = page.icon,
						iconSize = page.iconSize,
						title = page.title,
						subtitle = page.subtitle,
						shapes = SegmentedListItemDefaults.segmentedShapes(index = index, count = aboutPages.size)
					)
				}
			}
		}
	}
}

@Composable
private fun PageRow(
	destination: Screen? = null,
	icon: ImageVector,
	iconSize: Dp = 22.dp,
	title: StringResource,
	subtitle: StringResource,
	shapes: ListItemShapes
) {
	val backStack = LocalNavStack.current
	val preferenceManager = koinInject<PreferenceManager>()
	SegmentedListItem(
		shapes = shapes,
		onClick = dropUnlessResumed {
			destination?.let { destination ->
				backStack.lastOrNull()?.let {
					if (it is Screen.Settings) {
						if (it !is Screen.Settings.Root) {
							backStack.removeLastOrNull()
						}
						backStack.add(destination)
					}
				}
			}
		},
		contentPadding = PaddingValues(if (preferenceManager.theme.isMaterialLike()) 16.dp else 12.dp)
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			if (preferenceManager.theme.isMaterialLike()) {
				Column(
					modifier = Modifier
						.size(40.dp)
						.background(MaterialTheme.colorScheme.primary, CircleShape),
					horizontalAlignment = Alignment.CenterHorizontally,
					verticalArrangement = Arrangement.Center
				) {
					Icon(
						imageVector = icon,
						contentDescription = null,
						modifier = Modifier.size(iconSize),
						tint = MaterialTheme.colorScheme.onPrimary
					)
				}
			} else {
				Icon(
					icon,
					contentDescription = null,
					modifier = Modifier.padding(start = 8.dp, end = 5.dp).size(22.dp),
					tint = MaterialTheme.colorScheme.primary
				)
			}
			Column(
				Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(1.dp)
			) {
				Text(
					stringResource(title),
					style = MaterialTheme.typography.titleSmall.copy(
						fontFamily = defaultFont(100),
						fontSize = 16.sp,
						lineHeight = 16.sp
					)
				)
				Text(
					stringResource(subtitle),
					style = MaterialTheme.typography.bodyMedium.copy(
						fontFamily = defaultFont(grade = 10),
						lineHeight = 14.sp
					),
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
			if (!preferenceManager.theme.isMaterialLike()) {
				Icon(
					Icons.Outlined.ChevronForward,
					null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
		}
	}
}
