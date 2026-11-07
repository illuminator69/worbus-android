package app.worbus.domain.models.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme
import dev.zt64.compose.pipette.HsvColor
import worbus.composeapp.generated.resources.Res
import worbus.composeapp.generated.resources.theme_dynamic
import worbus.composeapp.generated.resources.theme_crisp
import worbus.composeapp.generated.resources.theme_evergreen
import worbus.composeapp.generated.resources.theme_rose
import worbus.composeapp.generated.resources.theme_seeded
import org.jetbrains.compose.resources.StringResource
import org.koin.compose.koinInject
import app.worbus.di.LocalPlatformContext
import app.worbus.domain.manager.PreferenceManager
import app.worbus.ui.util.darkIosColorScheme
import app.worbus.ui.util.lightIosColorScheme

/**
 * Theme choices that the user can choose from.
 *
 * Stored by position (`getEnum`, the ordinal), so an entry is renamed in place and never moved:
 * Worbus P5 renamed iOS, AppleMusic and Spotify to Crisp, Rose and Evergreen (design §6.11), and a
 * saved choice still reads the same theme.
 */
enum class Theme(val title: StringResource) {

	/**
	 * The app will be themed based on a "seed" colour.
	 *
	 * When this is selected, `accentColor(H/S/V)` settings
	 * will be exposed in the UI as a colour picker.
	 */
	Seeded(Res.string.theme_seeded),

	/**
	 * The app will be themed based on whatever the user
	 * chose in system settings. Android only.
	 */
	Dynamic(Res.string.theme_dynamic),

	/** Flat and bright, with soft corners, and a blue accent. */
	Crisp(Res.string.theme_crisp),

	/** The same as Crisp, with red and pink highlights. */
	Rose(Res.string.theme_rose),

	/** The same as Crisp, with green highlights. */
	Evergreen(Res.string.theme_evergreen);

	@Composable
	fun colorScheme(): ColorScheme {
		val platformContext = LocalPlatformContext.current
		val inDarkTheme = isSystemInDarkTheme()
		val preferenceManager = koinInject<PreferenceManager>()
		val isDark = remember(preferenceManager.themeMode) {
			when (preferenceManager.themeMode) {
				ThemeMode.System -> inDarkTheme
				ThemeMode.Dark -> true
				ThemeMode.Light -> false
			}
		}
		return when (this) {
			Dynamic -> platformContext.colorScheme ?: remember(isDark) {
				if (isDark)
					if (preferenceManager.amoled)
						darkColorScheme(
							surface = Color.Black,
							onSurface = Color.White,
							background = Color.Black,
							onBackground = Color.White
						)
					else darkColorScheme()
				else expressiveLightColorScheme()
			}

			Seeded -> rememberDynamicColorScheme(
				seedColor = HsvColor(
					hue = preferenceManager.paletteAccentH,
					saturation = 1f,
					value = 1f
				).toColor(),
				isDark = isDark,
				specVersion = ColorSpec.SpecVersion.SPEC_2025,
				style = preferenceManager.paletteStyle,
				isAmoled = preferenceManager.amoled
			)

			Crisp -> if (isDark)
				darkIosColorScheme(Color(0, 145, 255))
			else lightIosColorScheme(Color(0, 136, 255))

			Rose -> if (isDark)
				darkIosColorScheme(Color(255, 55, 95))
			else lightIosColorScheme(Color(255, 45, 85))

			Evergreen -> if (isDark)
				darkIosColorScheme(Color(30, 215, 96))
			else lightIosColorScheme(Color(30, 215, 96))
		}
	}

	fun isMaterialLike(): Boolean = when (this) {
		Dynamic,
		Seeded -> true

		else -> false
	}
}
