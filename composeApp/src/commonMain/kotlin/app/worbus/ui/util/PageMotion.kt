package app.worbus.ui.util

import app.worbus.domain.manager.PreferenceManager
import app.worbus.domain.models.settings.AnimationStyle
import kotlin.math.roundToInt

/**
 * navi-connect I-042: the timing of page pushes and pops, Settings' and the album's slides, a root-tab
 * switch (and every chip's content swap, `ContentSwap.kt`), the cover's shared-element flight, bottom sheets
 * and the pace of Quick's folds, per [AnimationStyle], and
 * scaled by the animation speed setting ([PreferenceManager.motionSpeed], I-075). Pure values and no Compose
 * imports, so a host test can lock them; [Material3Transitions], the cover `BoundsTransform`, `Fold`,
 * `ModalBottomSheet` and the custom radio's steps build their specs from it ([pageMotion]), so this one table
 * is every page and tab motion's time.
 *
 * I-075 (the owner's ruling, 2026-11-13 evening): **Quick is the speed the owner saw on Expressive at
 * `4b592a4e`** ([QuickPageMotion]), and **Expressive (and Standard, which has always shared its table) is
 * back to the speed and easing of `bd39384d`**, before B-378, B-403 and B-404 ([DefaultPageMotion]), with
 * their structural fixes kept: one back animation, the two pages' fades never overlapping, no fade (so no
 * backdrop) into or out of a cover page, one motion for every album opener, Settings' slide with no fade.
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
	 * pages ([pageChangeOf]), on [slideEasing]; and B-404's album slide ([coverSlideTiming]), on
	 * [enterEasing].
	 */
	val slideMs: Int,
	/** Settings' slide's curve (B-403 chose EaseOutQuart, upstream's predictive-back slide). */
	val slideEasing: PageEasing,
	/**
	 * B-436 (motion lane, 2026-11-14): how much quicker than the motion scheme's own spring a fold (`Fold.kt`)
	 * and what moves with it run: stiffness × pace². Every style folds on the scheme's spring, so a fold moves
	 * as the components around it do; 1 is the scheme's own (Expressive, Standard). Quick's folds were fixed
	 * tweens (200 ms, 280 for a block that holds a list) beside the expressive springs of its components, the
	 * mismatch `Fold.kt` was written to remove.
	 */
	val foldPace: Float = 1f
)

/** Which of [Material3Transitions]' easings a motion uses. */
enum class PageEasing { Emphasized, EmphasizedDecelerate, EaseOutQuart }

/**
 * Expressive and Standard (I-075: the speed of `bd39384d`, the structural fixes kept). At `bd39384d` every
 * page change, Settings' and an album's included, was one motion: the incoming page slid 500 ms from half
 * the width on Emphasized and faded in over 450 ms from its first frame, the outgoing page faded out over
 * 250 ms and slid 30 dp over 300 ms on EmphasizedAccelerate; a dragged Back was a separate 300 ms
 * full-width slide on EaseOutQuart; a root tab cross-faded on the default springs (MediumLow, settled in
 * about 330 ms); the cover flew 500 ms on EmphasizedDecelerate. So:
 *
 * - **The slide and fade** (any two pages that are neither Settings nor a cover page): 500 ms from half the
 *   width on Emphasized, as at `bd39384d` and as now. B-378 changed only its structure, which stays: the
 *   old page fades out over the first 175 ms and the new one in over the remaining 325, never both at once
 *   (the two fades must share the 500 ms, so Material's split rather than the old 250 and 450), and both
 *   pages slide the whole time (the old one stopped at 300 ms on EmphasizedAccelerate, by when it has
 *   faded out anyway).
 * - **Settings' slide and the album slide** take the old page change's 500 ms on Emphasized (B-403 and
 *   B-404 had made them 300 ms, Settings' on EaseOutQuart). Whole width, no fade, as B-403 and B-404 made them.
 * - **The cover's flight** lands with the album slide (B-404), so 500 ms on Emphasized (it was 500 ms on
 *   EmphasizedDecelerate).
 * - **A root tab** fades through (Ruling 7: never both tabs at once) over the old springs' ~330 ms, split as
 *   Material's fade-through (30 / 70): 100 out, 230 in. B-403 made it 90 + 210.
 * - **Back** is the pop of whichever motion runs (B-378's one back animation), so it has no time of its own.
 * - Folds: the motion scheme's springs.
 * - **Sheets** rise over 400 ms and fall over **300** (B-436, motion lane 2026-11-14): they fell over 600, slower
 *   than they rose, upstream's numbers. An exit is shorter than its enter (Material's guidance, and Quick's 300 /
 *   250 already); 300 rather than Material's 200-250 so a Quick sheet still never closes slower than this one.
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
	sheetShowMs = 400,
	sheetHideMs = 300,
	tabExitFadeMs = 100,
	tabEnterFadeMs = 230,
	slideMs = 500,
	slideEasing = PageEasing.Emphasized
)

/**
 * Quick (I-075, the owner's ruling): **the page motion Expressive had at `4b592a4e`**, every number this
 * session's B-378, B-403 and B-404 set: Settings' slide 300 ms on EaseOutQuart; the album slide and the cover's
 * flight 300 ms on Emphasized; a root tab 90 out then 210 in. Quick's own sheets, which this session did not
 * touch and which were already quicker than Expressive's, are kept.
 *
 * **Its folds** (B-436, motion lane 2026-11-14) are the motion scheme's spring, as every style's are, at Quick's
 * pace: 500 / 300, Quick's push against Expressive's, so stiffness × 2.78. They were tweens of 200 ms (280 for a
 * block holding a list) beside the expressive springs Quick's components run on; a spring at that pace keeps
 * them as quick (rwalk3 measured the avatar menu's fold at 214 ms under Expressive and 130 under Quick; 214 / 1.67
 * is 128) and makes them move as the chips and the side bar next to them do. Tweens under every style would be
 * the same mismatch the other way round, Expressive's folds a tween among its own springs.
 *
 * **The slide and fade is 300 ms** (review M2, the owner's ruling of 2026-11-13: "make it 300"): it had become
 * Expressive's 500, so the commonest page change no longer told the two presets apart. From half the width on
 * Emphasized, both pages sliding the whole time, and the fades scaled with it (× 0.6) so they keep B-378's
 * structure: the old page out over 0-105 ms, the new one in over 105-300 ms, never both at once, the new fade
 * ending with the slide.
 *
 * It was a starting proposal, half of everything (250 ms, 30 dp, EmphasizedDecelerate; Settings and the
 * album 250; tabs 60 + 140; the flight 250).
 */
val QuickPageMotion = PageMotion(
	enterFadeMs = 195,
	enterFadeDelayMs = 105,
	enterSlideMs = 300,
	enterSlideDp = null,
	enterEasing = PageEasing.Emphasized,
	exitFadeMs = 105,
	exitSlideMs = 300,
	exitSlideDp = 30,
	sheetShowMs = 300,
	sheetHideMs = 250,
	tabExitFadeMs = 90,
	tabEnterFadeMs = 210,
	slideMs = 300,
	slideEasing = PageEasing.EaseOutQuart,
	foldPace = 500f / 300f
)

/** I-075: the animation speed setting's range and steps (0.5×, 0.75× … 2×); 1× is the preset as it is. */
const val MOTION_SPEED_MIN = 0.5f
const val MOTION_SPEED_MAX = 2f
const val MOTION_SPEED_DEFAULT = 1f

/** The slider's in-between stops: 0.5 to 2 by 0.25 is seven values, so five between the ends. */
const val MOTION_SPEED_STEPS = 5

/** A stored speed as the slider offers it: inside the range, on a quarter, anything unreadable 1×. */
fun motionSpeedOf(stored: Float): Float =
	if (!stored.isFinite()) MOTION_SPEED_DEFAULT
	else ((stored.coerceIn(MOTION_SPEED_MIN, MOTION_SPEED_MAX) * 4f).roundToInt() / 4f)

/** The speed as the setting says it: "1×", "1.25×", "0.5×". */
fun motionSpeedLabel(speed: Float): String {
	val s = motionSpeedOf(speed)
	val hundredths = (s * 100).roundToInt()
	val text = if (hundredths % 100 == 0) "${hundredths / 100}" else "${hundredths / 100}." +
		"${hundredths % 100}".padStart(2, '0').trimEnd('0')
	return "$text×"
}

/**
 * I-075: [this] at [speed] (2× is half of every time, 0.5× twice it). Every duration and delay scales, so
 * the fades' windows keep their order and the album slide and the cover's flight still land together;
 * distances and curves do not change. A fold's pace is relative to the motion scheme, which `NavicTheme` already
 * scales by the speed, so it does not change either.
 */
fun PageMotion.atSpeed(speed: Float): PageMotion {
	val s = motionSpeedOf(speed)
	if (s == 1f) return this
	fun ms(v: Int) = (v / s).roundToInt()
	// A delayed fade scales by its ends, so a fade that ended with the slide still ends with it.
	return copy(
		enterFadeMs = ms(enterFadeDelayMs + enterFadeMs) - ms(enterFadeDelayMs),
		enterFadeDelayMs = ms(enterFadeDelayMs),
		enterSlideMs = ms(enterSlideMs),
		exitFadeMs = ms(exitFadeMs),
		exitSlideMs = ms(exitSlideMs),
		sheetShowMs = ms(sheetShowMs),
		sheetHideMs = ms(sheetHideMs),
		tabExitFadeMs = ms(tabExitFadeMs),
		tabEnterFadeMs = ms(tabExitFadeMs + tabEnterFadeMs) - ms(tabExitFadeMs),
		slideMs = ms(slideMs)
	)
}

/** When each page's fade runs, in ms from the start of a page change (B-378). */
data class PageFadeWindows(val exitStartMs: Int, val exitEndMs: Int, val enterStartMs: Int, val enterEndMs: Int)

/** The outgoing page from the first frame, the incoming one after it. */
fun pageFadeWindows(motion: PageMotion): PageFadeWindows =
	PageFadeWindows(0, motion.exitFadeMs, motion.enterFadeDelayMs, motion.enterFadeDelayMs + motion.enterFadeMs)

/** A root-tab switch's two fades (B-403): the old tab from the first frame, the new one after it. */
fun tabFadeWindows(motion: PageMotion): PageFadeWindows =
	PageFadeWindows(0, motion.tabExitFadeMs, motion.tabExitFadeMs, motion.tabExitFadeMs + motion.tabEnterFadeMs)

/**
 * What a page is, as far as the motion between two pages goes (B-403, B-404). An entry says it in its
 * metadata ([pageKindMetadata]); an entry that says nothing is [PageKind.Other].
 */
enum class PageKind {
	/** Settings and every page under it. */
	Settings,

	/**
	 * B-404: a page that draws its own cover's colours over the backdrop: an album's or playlist's
	 * (`CollectionDetail`), an artist's, an unowned album's, a mix's (review m4). The unowned artist's page
	 * wears the backdrop's (`Washed`) and is one too, as the other end of album <-> artist.
	 */
	Cover,

	/**
	 * Review m4: a scene of more than one pane with a cover page on top (a tablet's or a landscape phone's
	 * list beside an album). Between two such scenes the slide and fade, since a whole-width slide would
	 * carry a list pane off that is the list on both sides; into or out of a one-pane page it is the album
	 * slide (B-404 review L6), as in portrait.
	 */
	Panes,
	Other
}

/** Which motion a page change runs. */
enum class PageChange {
	/** B-378's slide and fade: both pages slide, the old one fades out over the first third, then the new one in. */
	SharedAxis,

	/**
	 * The regular slide (B-403, Settings): both pages move the whole width together on the style's
	 * [PageMotion.slideEasing] (Emphasized under Expressive and Standard, EaseOutQuart under Quick), nothing fades.
	 */
	Slide,

	/**
	 * The album slide (B-404, the owner's ruling of 2026-11-13): into or out of a cover page, from anywhere.
	 * Both pages move the whole width together, nothing fades, in the artist page's time ([PageMotion.slideMs])
	 * on the discography page's easing ([PageMotion.enterEasing]): [coverSlideTiming].
	 */
	CoverSlide
}

/**
 * B-403 (the owner, 2026-11-13): Settings and its pages use the regular slide, with no fade, going in,
 * between them and leaving.
 *
 * B-404 (the owner's re-walk, 2026-11-13): opening an album ran two different motions. From the artist
 * page (cover to cover) it was the regular slide, 300 ms on EaseOutQuart; from the full discography (a
 * washed page to a cover page) B-378's slide and fade, 500 ms on the Emphasized curve, whose fade-through
 * left neither page drawn for a moment. The owner: "inconsistent behavior which should never happen";
 * everywhere the album slide runs it takes the artist page's speed and the discography's easing. So every
 * change into or out of a cover page, whatever the other page is, is one motion, [PageChange.CoverSlide]:
 * no fade, so no backdrop between the two pages. Settings keeps its own slide (B-403 passed).
 *
 * B-404 review L6: in landscape (a phone gets two panes too, CLAUDE.md §7) the album beside its list
 * ([PageKind.Panes]) going to an artist page ran the 500 ms slide and fade, while the artist page going to
 * an album ran the album slide: the fade-through between album and artist the owner had ruled out. A
 * change between a two-pane cover scene and a one-pane page is the album slide too: the whole scene leaves
 * either way, list pane included, as Home does in portrait. Two two-pane scenes keep the slide and fade
 * (review m4: the list pane is a list on both sides). Opening an album BESIDE its list is no page change
 * at all: one scene, whose detail pane takes the list-detail scaffold's own pane motion while the list
 * stays where it is, which a whole-width slide of both pages cannot do.
 */
fun pageChangeOf(from: PageKind, to: PageKind): PageChange = when {
	from == PageKind.Settings || to == PageKind.Settings -> PageChange.Slide
	from == PageKind.Panes && to == PageKind.Panes -> PageChange.SharedAxis
	from == PageKind.Cover || to == PageKind.Cover || from == PageKind.Panes || to == PageKind.Panes -> PageChange.CoverSlide
	else -> PageChange.SharedAxis
}

/** A whole-width slide's time and curve. */
data class SlideTiming(val durationMs: Int, val easing: PageEasing)

/**
 * The album slide's time and curve (B-404): the artist page's time, the discography page's easing. The
 * cover's flight from a tile to the album page ([rememberCoverBoundsTransform]) takes the same, so the
 * cover lands as the page does.
 */
fun coverSlideTiming(motion: PageMotion): SlideTiming = SlideTiming(motion.slideMs, motion.enterEasing)

/**
 * Motion lane review L3 (2026-11-14): the time and curve of a shared element's flight (a cover, the bottom bar's
 * bounds) in a page change. A bar tap ([tabTap], `TabTapMotion`) fades through in [tabFadeWindows]' time (Expressive
 * 330 ms), so its flights take that time: on the album slide's 500 the cover landed after the page had (B-404's rule
 * the other way round), and the bar's bounds held every tab tap at 500 ms. Any other change is [coverSlideTiming].
 */
fun sharedFlightTiming(motion: PageMotion, tabTap: Boolean): SlideTiming =
	if (tabTap) SlideTiming(tabFadeWindows(motion).enterEndMs, motion.enterEasing) else coverSlideTiming(motion)

private const val PAGE_KIND_KEY = "app.worbus.pageKind"

/** An entry's metadata saying what page it is ([PageKind]). */
fun pageKindMetadata(kind: PageKind): Map<String, Any> = mapOf(PAGE_KIND_KEY to kind)

/** What [metadata] says the page is; [PageKind.Other] when it says nothing. */
fun pageKindOf(metadata: Map<String, Any>?): PageKind = metadata?.get(PAGE_KIND_KEY) as? PageKind ?: PageKind.Other

/**
 * What a scene is, from its entries' metadata, the page on top last. Review m4: a scene of more than one
 * pane (a tablet's list beside a cover page) is not a cover page but [PageKind.Panes]: B-404's slide would
 * carry the list pane off with it, so such a change keeps the slide and fade.
 */
fun pageKindOfScene(entries: List<Map<String, Any>>): PageKind {
	val top = pageKindOf(entries.lastOrNull())
	return if (top == PageKind.Cover && entries.size > 1) PageKind.Panes else top
}

/**
 * A style's motion at a speed. I-075: Standard keeps sharing Expressive's table, as it has since Quick was
 * added (I-042): the two styles differ in the components' motion scheme (`Theme.kt`), not in page motion,
 * and a Standard left on `4b592a4e`'s numbers would be Quick's page motion under another name.
 */
fun pageMotionOf(style: AnimationStyle, speed: Float = MOTION_SPEED_DEFAULT): PageMotion = when (style) {
	AnimationStyle.Expressive, AnimationStyle.Standard -> DefaultPageMotion
	AnimationStyle.Quick -> QuickPageMotion
}.atSpeed(speed)

/** The motion the settings ask for: the animation style at the animation speed. Every motion site reads this. */
fun PreferenceManager.pageMotion(): PageMotion = pageMotionOf(animationStyle, motionSpeed)
