package app.worbus.domain.manager

import app.worbus.domain.models.DomainSong
import app.worbus.domain.models.Mix
import app.worbus.domain.models.MixKind
import app.worbus.domain.models.settings.StationCharacter

/**
 * A mix's songs as its page shows them (P5 round 2), built by [RadioManager.buildMix] and played
 * by [RadioManager.playMixBuild]. [station] is the station they came from, when the mix plays as
 * one: playing then continues that station rather than starting another, so what plays is what
 * the page showed.
 */
class MixBuild internal constructor(
	val mix: Mix,
	val songs: List<DomainSong>,
	internal val station: BuiltStation?
) {
	/** Whether more follows these songs when they are played. */
	val continues: Boolean get() = station != null && !station.answer.exhausted
}

/** A station started but not yet loaded: its answer, and the queue its seeds and first batch make. */
internal class BuiltStation(
	val answer: StationAnswer,
	val seed: StationSeed,
	val character: StationCharacter,
	val name: String,
	val queue: List<DomainSong>,
	/** The station's own songs in [queue] (the seeds left out), for its served set. */
	val served: List<String>
)

/** What [RadioManager.buildMix] made of a mix. */
sealed interface MixBuildResult {
	class Built(val build: MixBuild) : MixBuildResult

	/** The recipe ran and nothing came of it. */
	data object Empty : MixBuildResult

	/** A mood-filtered radio whose station could not start: it has no unfiltered fallback ([stationFallbackAllowed]). */
	data object NeedsAudioMuse : MixBuildResult

	/** A fingerprint mix whose AudioMuse is too old for the listening fingerprint (B-359; the hub's `audiomuse_too_old`). */
	data class AudioMuseTooOld(val need: String) : MixBuildResult

	/** A fingerprint mix the hub or AudioMuse refused ([code] as the body named it, "" when none): not an empty mix. */
	data class Refused(val code: String) : MixBuildResult
}

/** Whether [mix]'s songs are the listening fingerprint when it does not play as a station. */
internal fun mixIsFingerprint(mix: Mix): Boolean =
	mix.kind == MixKind.FINGERPRINT || (mix.kind == MixKind.STATION && mix.seedId.isBlank())

/** What a fingerprint answer that is not a list makes of a mix page: said, never shown as empty (B-359). */
internal fun fingerprintFailure(answer: FingerprintAnswer): MixBuildResult? = when (answer) {
	is FingerprintAnswer.AudioMuseTooOld -> MixBuildResult.AudioMuseTooOld(answer.need)
	is FingerprintAnswer.Refused -> MixBuildResult.Refused(answer.code)
	is FingerprintAnswer.Ids, FingerprintAnswer.Unavailable -> null
}
