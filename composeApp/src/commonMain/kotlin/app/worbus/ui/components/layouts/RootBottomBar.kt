package app.worbus.ui.components.layouts

import androidx.navigation3.ui.LocalNavAnimatedContentScope
import app.worbus.di.LocalSharedTransitionScope
import app.worbus.ui.util.rememberCoverBoundsTransform
import app.worbus.ui.util.atMotionSpeed
import app.worbus.ui.util.motionMs
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import org.koin.compose.koinInject
import app.worbus.di.LocalPlatformContext
import app.worbus.domain.manager.PreferenceManager
import app.worbus.domain.models.settings.BottomBarCollapseMode
import app.worbus.domain.models.settings.MiniPlayerStyle
import app.worbus.ui.components.common.LocalCoverAmbientBottom
import app.worbus.ui.components.common.blur.LocalExpressiveBlur
import app.worbus.ui.components.common.blur.expressiveBlurEffect
import app.worbus.ui.util.easedVerticalGradient

@Composable
fun RootBottomBar(
	scrolled: Boolean,
	modifier: Modifier = Modifier,
	shadows: Boolean = true,
	hideMiniPlayer: Boolean = false,
	// The colour the scrim under a detached bar fades up from. Null means the page behind the bar
	// is the plain themed surface, which is every screen but the washed library home.
	scrimColor: Color? = null,
	windowInsets: WindowInsets = WindowInsets.systemBars
) {
	val platformContext = LocalPlatformContext.current
	if (platformContext.sizeClass.widthSizeClass >= WindowWidthSizeClass.Medium) return

	val preferenceManager = koinInject<PreferenceManager>()
	val expressiveBlur = LocalExpressiveBlur.current
	val detached = preferenceManager.miniPlayerStyle == MiniPlayerStyle.Detached
	val scrolled =
		scrolled && preferenceManager.bottomBarCollapseMode == BottomBarCollapseMode.OnScroll
	val progress by animateFloatAsState(
		targetValue = if (scrolled) 0f else 1f,
		// B-435 (motion lane, 2026-11-14): at the animation speed.
		animationSpec = spring<Float>(
			dampingRatio = Spring.DampingRatioLowBouncy,
			stiffness = Spring.StiffnessMediumLow
		).atMotionSpeed()
	)
	// NOT `by`: this is read in the DRAW phase below, not at composition.
	//
	// Read as a plain value here, it recomposed this whole function — mini-player included, and
	// with Expressive blur on that means rebuilding the pill's blur node — on every frame of a
	// 600 ms tween, which fires at every scroll start and every scroll stop. `progress` above is
	// already deferred correctly into its `graphicsLayer` lambdas; this was the one that leaked.
	val shadowFadeProgress = animateFloatAsState(
		targetValue = if (scrolled || !shadows) 0f else 1f,
		animationSpec = tween(durationMillis = motionMs(600))
	)
	// The page's own bottom colour when there is a cover ambient (see [LocalCoverAmbientBottom]);
	// `surface` only as the last resort, for screens that paint themselves plainly.
	val surfaceColor = scrimColor
		?: LocalCoverAmbientBottom.current
		?: MaterialTheme.colorScheme.surface
	// Motion lane (2026-11-14): ONE bar on screen while the pages change. Every page's Scaffold draws its own bar
	// (§7), so a whole-width slide (an album, Settings) carried the old page's bar off and slid a second one in
	// beside it, while a tab's fade-through faded it out and in. As a shared element across the two pages it stays
	// where it is, whatever the page change: drawn once, the new page's, from the change's first frame. A page with
	// no bar takes nothing: the other page's bar then moves with its page, as before. Under the cover's flight.
	val sharedBar = with(LocalSharedTransitionScope.current) {
		Modifier.sharedElement(
			sharedContentState = rememberSharedContentState(ROOT_BOTTOM_BAR_KEY),
			animatedVisibilityScope = LocalNavAnimatedContentScope.current,
			boundsTransform = rememberCoverBoundsTransform(),
			zIndexInOverlay = -1f
		)
	}
	Column(
		// Frost the backdrop (the screen content marked as the app's blur source) behind the
		// bar when Expressive blur is on — but ONLY for the docked/full-bleed style. When
		// detached, the mini-player pill (and the floating capsule nav) frost their own shapes,
		// so a full-width band here would defeat the floating look.
		modifier = sharedBar
			.then(modifier)
			.then(if (detached) Modifier else Modifier.expressiveBlurEffect(expressiveBlur))
			.then(
				if (detached)
					Modifier.drawBehind {
						drawRect(
							Brush.easedVerticalGradient(
								color = surfaceColor.copy(alpha = shadowFadeProgress.value)
							)
						)
					}
				else Modifier
			)
	) {
		// The single MiniPlayer mirrors the remote session when another
		// navi-connect device is active (see MiniPlayer / MediaPlayerViewModel).
		if (!hideMiniPlayer) MiniPlayer(
			modifier = Modifier.graphicsLayer {
				alpha = progress.coerceIn(0f..1f)
				translationY = ((1f - progress) * (size.height * 2)).coerceAtLeast(
					if (preferenceManager.miniPlayerStyle == MiniPlayerStyle.Detached) -2048f else 0f
				)
			},
			enabled = !scrolled,
			windowInsets = windowInsets.only(WindowInsetsSides.Horizontal)
		)
		BottomBar(
			containerColor = when {
				preferenceManager.miniPlayerStyle == MiniPlayerStyle.Detached ->
					NavigationBarDefaults.containerColor.copy(alpha = 0f)
				// Let the frosted backdrop show through the nav bar when blur is on.
				expressiveBlur.enabled -> NavigationBarDefaults.containerColor.copy(alpha = 0.55f)
				else -> NavigationBarDefaults.containerColor
			},
			modifier = Modifier.graphicsLayer {
				alpha = progress.coerceIn(0f..1f)
				translationY = ((1f - progress) * size.height).coerceAtLeast(
					if (detached) -2048f else 0f
				)
			},
			enabled = !scrolled,
			windowInsets = windowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
		)
	}
}

/** The bottom bar's shared-element key: the same on every page, so the bar stays put while they change. */
private const val ROOT_BOTTOM_BAR_KEY = "app.worbus.rootBottomBar"
