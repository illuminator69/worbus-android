package app.worbus.ui.screens.radio.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemShapes
import app.worbus.ui.components.sheets.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.materialkolor.rememberDynamicColorScheme
import dev.zt64.compose.pipette.HsvColor
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.action_delete
import worbus.composeapp.generated.resources.action_edit
import worbus.composeapp.generated.resources.action_more
import worbus.composeapp.generated.resources.info_unknown
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import app.worbus.domain.manager.PreferenceManager
import app.worbus.domain.models.DomainRadio
import app.worbus.domain.models.settings.ThemeMode
import app.worbus.icons.Icons
import app.worbus.icons.outlined.Delete
import app.worbus.icons.outlined.Badge
import app.worbus.icons.outlined.MoreVert
import app.worbus.icons.outlined.Radio
import app.worbus.ui.components.common.LibraryRow
import app.worbus.ui.components.common.LibraryRowDefaults
import kotlin.math.abs

/** Where a radio's site is, without its scheme: "radioparadise.com". */
internal fun radioLine(radio: DomainRadio): String? =
	radio.homepageUrl?.removePrefix("http://")?.removePrefix("https://")?.trimEnd('/')?.ifBlank { null }

/**
 * One internet radio as the Library's row (P5 round 2): its tile, its name, its site. A tap plays
 * it; a long press, or the "more" button, opens what can be done with it (Edit, Delete), which a
 * radio never had.
 */
@Composable
fun RadioRow(
	radio: DomainRadio,
	shapes: ListItemShapes,
	onPlay: () -> Unit,
	onMenu: () -> Unit,
	modifier: Modifier = Modifier
) {
	LibraryRow(
		title = radio.name,
		line = radioLine(radio) ?: stringResource(Res.string.info_unknown),
		onClick = onPlay,
		onLongClick = onMenu,
		shapes = shapes,
		modifier = modifier,
		art = { RadioArt(radio, Modifier.size(LibraryRowDefaults.ArtSize)) },
		trailing = {
			IconButton(onClick = onMenu) {
				Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(Res.string.action_more))
			}
		}
	)
}

/** A radio's face: its name's colour (as the old cards had), with the radio glyph. */
@Composable
fun RadioArt(radio: DomainRadio, modifier: Modifier = Modifier) {
	val preferenceManager = koinInject<PreferenceManager>()
	val systemDark = isSystemInDarkTheme()
	val isDark = when (preferenceManager.themeMode) {
		ThemeMode.System -> systemDark
		ThemeMode.Dark -> true
		ThemeMode.Light -> false
	}
	val seed = remember(radio.name) {
		HsvColor(hue = abs(radio.name.hashCode() % 360).toFloat(), saturation = 0.5f, value = 0.6f).toColor()
	}
	val scheme = rememberDynamicColorScheme(seedColor = seed, isDark = isDark)
	Box(
		modifier.clip(preferenceManager.coverArtShape.shape).background(scheme.primaryContainer),
		contentAlignment = Alignment.Center
	) {
		Icon(
			Icons.Outlined.Radio,
			contentDescription = null,
			tint = scheme.onPrimaryContainer,
			modifier = Modifier.fillMaxSize(0.5f)
		)
	}
}

/** What can be done with a radio: Edit (name, stream, site) and Delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioSheet(
	radio: DomainRadio,
	onDismissRequest: () -> Unit,
	onEdit: () -> Unit,
	onDelete: () -> Unit
) {
	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		dragHandle = null,
		contentWindowInsets = { BottomSheetDefaults.modalWindowInsets.add(WindowInsets(left = 8.dp, right = 8.dp)) }
	) {
		Spacer(Modifier.height(16.dp))
		ListItem(
			leadingContent = { RadioArt(radio, Modifier.size(LibraryRowDefaults.ArtSize)) },
			headlineContent = { Text(radio.name) },
			supportingContent = { Text(radioLine(radio) ?: radio.streamUrl, maxLines = 1) }
		)
		HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
		ListItem(
			content = { Text(stringResource(Res.string.action_edit)) },
			leadingContent = { Icon(Icons.Outlined.Badge, null) },
			onClick = {
				onDismissRequest()
				onEdit()
			},
			contentPadding = PaddingValues(horizontal = 16.dp)
		)
		ListItem(
			content = { Text(stringResource(Res.string.action_delete)) },
			leadingContent = { Icon(Icons.Outlined.Delete, null) },
			onClick = {
				onDismissRequest()
				onDelete()
			},
			contentPadding = PaddingValues(horizontal = 16.dp)
		)
		Spacer(Modifier.height(8.dp))
	}
}
