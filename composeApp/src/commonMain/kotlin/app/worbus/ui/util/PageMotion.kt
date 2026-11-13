package app.worbus.ui.util

import app.worbus.domain.models.settings.AnimationStyle

/**
 * navi-connect I-042: the timing of page pushes and pops, the cover's shared-element flight and
 * bottom sheets, per [AnimationStyle]. Pure values and no Compose imports, so a host test can lock
 * them; [Material3Transitions], the three cover `BoundsTransform` sites and `ModalBottomSheet`
 * build their specs from it.
 *
 * Expressive and Standard are the numbers these sites used before Quick existed — they must not
 * move. Quick's live in [QuickPageMotion]: retuning it is a one-number edit there.
 */
data class PageMotion(
	/**
	 * The incoming page's fade in, after [enterFadeDelayMs]: it starts when the outgoing page's fade
	 * out has ended, so the two pages' text is never drawn over each other (B-378).
	 */
	val enterFadeMs: Int,
	val enterFadeDelayMs: Int,
	val enterSlideMs: Int,
	/** How far the incoming page travels; null is half the screen width. */
	val enterSlideDp: Int?,
	val enterEasing: PageEasing,
	val exitFadeMs: Int,
	val exitSlideMs: Int,
	val exitSlideDp: Int,
	val sharedBoundsMs: Int,
	val sheetShowMs: Int,
	val sheetHideMs: Int,
	/**
	 * B-403 (the owner, 2026-11-13): a root-tab switch's fade-through, the old tab out over
	 * [tabExitFadeMs], then the new one in over [tabEnterFadeMs]. It was a page push's fades (500 ms).
	 */
	val tabExitFadeMs: Int,
	val tabEnterFadeMs: Int,
	/**
	 * B-403: the regular slide, both pages moving the whole width and no fade, for Settings and its
	 * pages ([pageChangeOf]).
	 */
	val slideMs: Int,
	/**
	 * A fold (`Fold.kt`) and what moves with it, in ms, and for one that holds a list; null is the motion
	 * scheme's own springs, which is what Expressive and Standard use.
	 */
	val foldMs: Int? = null,
	val foldTallMs: Int? = null
)

/** Which of [Material3Transitions]' easings the incoming page uses. */
enum class PageEasing { Emphasized, EmphasizedDecelerate }

/**
 * Expressive and Standard: 500 ms from half the width. B-378 (the owner, 2026-11-09): Material's
 * shared-axis timing, both pages sliding the whole 500 ms, the outgoing page fading out over the
 * first 35 % (175 ms) and the incoming fading in over the rest, never both at once. It was a
 * 450 ms fade in from the first frame over a 250 ms fade out, the two titles drawn over each other.
 */
val DefaultPageMotion = PageMotion(
	enterFadeMs = 325,
	enterFadeDelayMs = 175,
	enterSlideMs = 500,
	enterSlideDp = null,
	enterEasing = PageEasing.Emphasized,
	exitFadeMs = 175,
	exitSlideMs = 500,
	exitSlideDp = 30,
	sharedBoundsMs = 500,
	sheetShowMs = 400,
	sheetHideMs = 600,
	tabExitFadeMs = 90,
	tabEnterFadeMs = 210,
	slideMs = 300
)

/** Quick: a starting proposal (lb-bot's web UI enters in 220 ms over 18 px), tuned on the phone. */
val QuickPageMotion = PageMotion(
	enterFadeMs = 165,
	enterFadeDelayMs = 85,
	enterSlideMs = 250,
	enterSlideDp = 30,
	enterEasing = PageEasing.EmphasizedDecelerate,
	exitFadeMs = 85,
	exitSlideMs = 250,
	exitSlideDp = 30,
	sharedBoundsMs = 350,
	sheetShowMs = 300,
	sheetHideMs = 250,
	tabExitFadeMs = 60,
	tabEnterFadeMs = 140,
	slideMs = 250,
	foldMs = 200,
	foldTallMs = 280
)

/** When each page's fade runs, in ms from the start of a page change (B-378). */
data class PageFadeWindows(val exitStartMs: Int, val exitEndMs: Int, val enterStartMs: Int, val enterEndMs: Int)

/** The outgoing page from the first frame, the incoming one after it. */
fun pageFadeWindows(motion: PageMotion): PageFadeWindows =
	PageFadeWindows(0, motion.exitFadeMs, motion.enterFadeDelayMs, motion.enterFadeDelayMs + motion.enterFadeMs)

/** A root-tab switch's two fades (B-403): the old tab from the first frame, the new one after it. */
fun tabFadeWindows(motion: PageMotion): PageFadeWindows =
	PageFadeWindows(0, motion.tabExitFadeMs, motion.tabExitFadeMs, motion.tabExitFadeMs + motion.tabEnterFadeMs)

/**
 * What a page is, as far as the motion between two pages goes (B-403). An entry says it in its
 * metadata ([pageKindMetadata]); an entry that says nothing is [PageKind.Other].
 */
enum class PageKind {
	/** Settings and every page under it. */
	Settings,
	Other
}

/** Which motion a page change runs. */
enum class PageChange {
	/** B-378's slide and fade: both pages slide, the old one fades out over the first third, then the new one in. */
	SharedAxis,

	/** The regular slide: both pages move the whole width together, nothing fades. */
	Slide
}

/**
 * B-403 (the owner, 2026-11-13): Settings and its pages use the regular slide, with no fade, going in,
 * between them and leaving. Both pages move the whole width: nothing is drawn over the other page's
 * text, and the old page never vanishes at once. Everything else keeps the slide and fade.
 */
fun pageChangeOf(from: PageKind, to: PageKind): PageChange = when {
	from == PageKind.Settings || to == PageKind.Settings -> PageChange.Slide
	else -> PageChange.SharedAxis
}

private const val PAGE_KIND_KEY = "app.worbus.pageKind"

/** An entry's metadata saying what page it is ([PageKind]). */
fun pageKindMetadata(kind: PageKind): Map<String, Any> = mapOf(PAGE_KIND_KEY to kind)

/** What [metadata] says the page is; [PageKind.Other] when it says nothing. */
fun pageKindOf(metadata: Map<String, Any>?): PageKind = metadata?.get(PAGE_KIND_KEY) as? PageKind ?: PageKind.Other

/** The fold's time under [motion], or null for the motion scheme's springs. */
fun foldMs(motion: PageMotion, tall: Boolean): Int? = if (tall) motion.foldTallMs else motion.foldMs

fun pageMotionOf(style: AnimationStyle): PageMotion = when (style) {
	AnimationStyle.Expressive, AnimationStyle.Standard -> DefaultPageMotion
	AnimationStyle.Quick -> QuickPageMotion
}
