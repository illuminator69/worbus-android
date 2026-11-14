package app.worbus.ui.components.sheets

import app.worbus.ui.util.atSpeed
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import app.worbus.domain.manager.PreferenceManager
import app.worbus.ui.theme.NavicTheme
import app.worbus.ui.util.CoverAmbient
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModalBottomSheet(
	onDismissRequest: () -> Unit,
	modifier: Modifier = Modifier,
	// **One resting place: fully shown.** Material's own default also enables a half-way
	// place (`PartiallyExpanded`), which the opening heads for and which exists only while the
	// sheet is taller than half the screen. A sheet that reads after it opens starts shorter
	// than that — so it shows whole — and when its content lands and passes half the screen
	// the half-way place comes into being and the sheet goes THERE: it opened whole, then
	// dropped until its last control was cut in half (the missing-album sheet's "Find
	// sources", seen on the phone). With only Hidden and Expanded there is nowhere to stop
	// short: the sheet rests at its content's height, and content taller than the screen
	// scrolls inside it. This is the state upstream's own sheets already pass (Share, Sort,
	// Account, Sleep timer); a caller that wants the half-way stop passes its own.
	sheetState: SheetState = rememberBottomSheetState(
		initialValue = SheetValue.Hidden,
		enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
	),
	sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
	sheetGesturesEnabled: Boolean = true,
	shape: Shape = BottomSheetDefaults.ExpandedShape,
	containerColor: Color = BottomSheetDefaults.ContainerColor,
	contentColor: Color = contentColorFor(containerColor),
	tonalElevation: Dp = 0.dp,
	scrimColor: Color = BottomSheetDefaults.ScrimColor,
	dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
	contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.modalWindowInsets },
	properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
	// When set, the sheet carries an Apple-Music-style dominant-colour wash
	// (Haze can't backdrop a ModalBottomSheet — separate window). The container is
	// the opaque muted top colour so the WHOLE surface (incl. the drag-handle
	// notch) blends; a top→bottom gradient is drawn over the content starting from
	// that same top colour (seamless), and the content is themed with the cover
	// scheme so its text/cards adapt. The Material surface still clips it to [shape].
	ambient: CoverAmbient? = null,
	sheetTitle: String? = null,
	// **A sheet opens in one motion, whatever arrives after its first frame.** Material rests a
	// sheet at `screen height − sheet height` and works that out again on every change of the
	// sheet's size; the opening animation picks its target ONCE, from the first frame, and is
	// restarted from where it is — a fresh tween, fast at the start — each time the anchors move
	// under it. Upstream's sheets never notice: their content is all there on the first frame.
	// The fork's sheets read from lb-bot after they open, so the first frame is a spinner, the
	// sheet starts for a resting place a hand high, and every answer that lands (the album, its
	// editions, its covers, the search results) throws it at a new one: a series of jerks where
	// there should be one slide. With this on, a change of the content's height is eased, so the
	// sheet's size — and the resting place Material derives from it — moves smoothly, and the
	// one opening animation follows it up. A sheet whose content is static is unaffected.
	// **Never while a finger is on the sheet** (B-121): near the top of the screen Material gives
	// the content more room as the sheet is dragged down (the status bar's share of the inset
	// shrinks), so a drag changes the content's height every frame; eased, that height lags, the
	// resting place moves under the drag, and the sheet snapped back to the top instead of
	// following the finger — a full-height sheet could not be swiped away at all. Measured on the
	// emulator: with easing during the drag the offset bounced between 3 and 124 px over a 1200 px
	// swipe; without it the offset followed the finger to the bottom and the sheet dismissed.
	// Off for the two navigation scenes, which host whole pages (the player, the queue) and
	// are left exactly as they were.
	animateContentHeight: Boolean = true,
	content: @Composable ColumnScope.() -> Unit,
) {
	// Whether a finger is down anywhere on the sheet (handle or content). Observed on the
	// Initial pass and never consumed, so the sheet's own drag and the content's scrolling see
	// every event as before.
	var touched by remember { mutableStateOf(false) }
	// B-442 / B-154: a sheet closes on the spring it opens on (the owner's walk of `a7ea2fe1`: "it should just be the
	// reverse of how they open"). Material's sheet takes its specs from the motion scheme, in a SideEffect on every
	// recomposition of the sheet (BottomSheet, M3 1.5): `defaultSpatialSpec` to show and to settle a released drag,
	// `fastEffectsSpec` to hide. `hide()` is what Back, the player's chevron and a tap on the scrim run, so they closed
	// on an effects spring (~100 ms to 95 % on the VM AVD) while a dragged close settled on the opening's spring. The
	// sheet's scheme ([sheetMotionScheme]) gives the hide that spring; the content gets the page's scheme back
	// ([outerMotion]). Never through the sheet state: Material's SideEffect overwrites anything written there.
	val outerMotion = MaterialTheme.motionScheme
	val sheetMotion = remember(outerMotion) { sheetMotionScheme(outerMotion) }
	val outerColors = MaterialTheme.colorScheme
	val outerShapes = MaterialTheme.shapes
	val outerType = MaterialTheme.typography
	val withPageMotion: @Composable (@Composable () -> Unit) -> Unit = { inner ->
		MaterialExpressiveTheme(colorScheme = outerColors, motionScheme = outerMotion, shapes = outerShapes, typography = outerType, content = inner)
	}
	MaterialExpressiveTheme(colorScheme = outerColors, motionScheme = sheetMotion, shapes = outerShapes, typography = outerType) {
	androidx.compose.material3.ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		// Nothing that takes touches here: M3 1.5 applies this modifier BEFORE `draggableAnchors` moves the sheet down
		// to its resting place, so it lies over [0, sheet height] from the TOP of the window, above the scrim in hit
		// testing. The B-121 touch watch sat here and took every tap above a tall sheet (B-442: the album sheet,
		// 1881 of 2992 px on the AVD, could not be closed by a tap above it). It is on the handle and the content.
		modifier = modifier.semantics {
			sheetTitle?.let { sheetTitle ->
				paneTitle = sheetTitle
			}
		},
		sheetState = sheetState,
		sheetMaxWidth = sheetMaxWidth,
		sheetGesturesEnabled = sheetGesturesEnabled,
		shape = shape,
		containerColor = ambient?.top ?: containerColor,
		// Drive the implicit content colour from the cover scheme too, so elements
		// that rely on LocalContentColor (not an explicit MaterialTheme colour) stay
		// legible on the tinted surface.
		contentColor = ambient?.onAmbient ?: contentColor,
		tonalElevation = tonalElevation,
		scrimColor = scrimColor,
		dragHandle = dragHandle?.let { handle -> @Composable { Box(Modifier.watchTouch { touched = it }) { handle() } } },
		// For the ambient wash, M3 insets only the TOP: the drag handle then sits below the
		// status bar, as on every Material sheet. With no inset at all (as before B-121) a sheet
		// tall enough to reach the top drew its handle inside the status-bar band, where a pull
		// opened the notification shade instead of moving the sheet. The container is the
		// gradient's own top colour, so that band shows no seam. The bottom and sides are
		// re-applied INSIDE the gradient below, so it reaches the true bottom edge and no
		// container-coloured frame shows there.
		contentWindowInsets = if (ambient != null) ({ contentWindowInsets().only(WindowInsetsSides.Top) }) else contentWindowInsets,
		properties = properties,
		content = { withPageMotion {
			// INSIDE the window insets (below), not around them: the keyboard's inset changes
			// every frame as it slides, and easing that too would let the keyboard cover the
			// field it was opened for. The eased size is clamped to what the sheet is given,
			// so a shrinking viewport is obeyed at once.
			// ONE node for the sheet's life, told whether to ease. Swapping `animateContentSize()`
			// in and out with the finger made a fresh node on the finger's lift, which takes its
			// first measurement as its starting size, so a tap that changes the content (the new
			// mix form's kind: Mood Flow's few rows to Genre's list) landed at full height at once.
			// B-435 (motion lane, 2026-11-14): the height's spring at the animation speed.
			val heightSpeed = koinInject<PreferenceManager>().motionSpeed
			val eased = if (animateContentHeight) Modifier.easedHeight(ease = !touched, speed = heightSpeed) else Modifier
			val watched = Modifier.watchTouch { touched = it }
			if (ambient != null) {
				val insets = contentWindowInsets()
				NavicTheme(ambient.scheme, contentColor = ambient.onAmbient) {
					Column(
						modifier = Modifier
							.fillMaxWidth()
							// `weight(fill = false)` bounds this wrapper to the space the
							// sheet actually has. Without it a plain Column measures its
							// children with INFINITE max height, so any content using
							// `verticalScroll` gets a viewport as tall as its own content
							// — nothing to scroll — and the overflow is simply clipped at
							// the screen edge. `AboutSheet` is exactly that shape, and it
							// silently lost the ability to scroll the moment it started
							// passing an ambient. The non-ambient branch below never had
							// the problem because the sheet's own column bounds it.
							.weight(1f, fill = false)
							.background(
								Brush.verticalGradient(
									listOf(ambient.top, ambient.top, ambient.bottom)
								)
							)
							// Apply the rest of the content insets here, so the gradient reaches the
							// true bottom/side edges and no container-coloured border shows.
							.windowInsetsPadding(insets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
							.then(watched)
							.then(eased),
						content = content
					)
				}
			} else if (animateContentHeight) {
				// The same bounded column the ambient branch has (`weight(fill = false)`: see
				// there), so the content is measured exactly as before and only its size eases.
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.weight(1f, fill = false)
						.then(watched)
						.then(eased),
					content = content
				)
			} else {
				// Nothing eased, so nothing to watch.
				content()
			}
		} },
	)
	}
}

/**
 * B-442: the motion scheme Material's sheet reads its specs from: [base], with the hide (`fastEffectsSpec`, the only
 * thing Material's sheet reads it for) on [base]'s `defaultSpatialSpec`, the spring the sheet opens on and settles a
 * released drag on. So Back, the player's chevron and a tap above the sheet close it as a drag does: the opening's
 * motion the other way, at the animation speed ([base] is the theme's, already at the speed). Read by the sheet alone:
 * its content gets [base] back.
 */
internal fun sheetMotionScheme(base: MotionScheme): MotionScheme = SheetMotionScheme(base)

private class SheetMotionScheme(private val base: MotionScheme) : MotionScheme by base {
	override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = base.defaultSpatialSpec()
}

/**
 * B-121: reports whether a finger is down on this node, watched on the Initial pass and never consumed, so the sheet's
 * own drag and the content's scrolling see every event as before.
 */
private fun Modifier.watchTouch(onPressed: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
	awaitPointerEventScope {
		while (true) {
			val event = awaitPointerEvent(PointerEventPass.Initial)
			onPressed(event.changes.any { it.pressed })
		}
	}
}

/**
 * The content's height, eased towards what it measures while [ease] holds and reported exactly
 * (and the easing reset to it) while it does not, so easing that resumes starts from the height
 * on screen. `animateContentSize()`'s spring, clipped as it clips; the eased height is kept
 * within what the sheet gives.
 */
private fun Modifier.easedHeight(ease: Boolean, speed: Float): Modifier = clipToBounds() then EasedHeightElement(ease, speed)

private data class EasedHeightElement(val ease: Boolean, val speed: Float) : ModifierNodeElement<EasedHeightNode>() {
	override fun create() = EasedHeightNode(ease, speed)

	override fun update(node: EasedHeightNode) {
		node.speed = speed
		node.update(ease)
	}

	override fun InspectorInfo.inspectableProperties() {
		name = "easedHeight"
		properties["ease"] = ease
	}
}

private class EasedHeightNode(private var ease: Boolean, var speed: Float) : Modifier.Node(), LayoutModifierNode {
	private var height: Animatable<Int, AnimationVector1D>? = null
	private var target = 0

	fun update(ease: Boolean) {
		if (this.ease == ease) return
		this.ease = ease
		if (!ease) snap()
		invalidateMeasurement()
	}

	private fun snap() {
		val anim = height ?: return
		if (isAttached) coroutineScope.launch { anim.snapTo(target) }
	}

	override fun onDetach() {
		height = null
	}

	override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
		val placeable = measurable.measure(constraints)
		val measured = placeable.height
		val anim = height ?: Animatable(measured, Int.VectorConverter, 1).also {
			height = it
			target = measured
		}
		if (measured != target) {
			target = measured
			if (ease) {
				coroutineScope.launch {
					anim.animateTo(measured, spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 1).atSpeed(speed))
				}
			} else {
				snap()
			}
		}
		val shown = constraints.constrainHeight(if (ease) anim.value else measured)
		return layout(placeable.width, shown) {
			placeable.place(0, 0)
		}
	}
}
