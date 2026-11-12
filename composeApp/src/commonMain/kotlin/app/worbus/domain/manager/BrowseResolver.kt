package app.worbus.domain.manager

import app.worbus.data.database.dao.AlbumDao
import app.worbus.ui.screens.discover.viewmodels.BrowseTarget

/**
 * Where a Deezer browse tile opens: the library's own album, a release-group only MusicBrainz knows,
 * or nowhere (P3: moved out of `DiscoverViewModel`, unchanged, so Discover and the Charts and Editors'
 * picks pages answer a tap the same way). Room is asked before the network, and a MusicBrainz answer
 * is validated against what was asked, never taken first (CLAUDE.md §7).
 */
class BrowseResolver(
	private val lbBotManager: LbBotManager,
	private val albumDao: AlbumDao
) {
	/**
	 * The release-group id for a browse row that arrived without one.
	 *
	 * Deezer has no MusicBrainz ids, so lb-bot resolves its rows against the local
	 * discography index by name and leaves `rgid` blank when that index has never
	 * seen the record. It deliberately does **not** fan out a MusicBrainz search per
	 * row — that spends the scanner's global one-request-per-second budget on a shelf
	 * nobody tapped — and designates the tap as where the one search happens. This is
	 * that search, for the one row the user actually chose.
	 *
	 * Returns null when nothing matched, and the caller then does nothing rather than
	 * opening an empty page: `ExternalAlbumScreen` keyed on a blank rgid loads
	 * forever, which is a worse answer than a tap that declines.
	 */
	suspend fun resolve(album: LbBrowseAlbum): BrowseTarget? {
		// 1. lb-bot already knows. Cheapest and most authoritative.
		if (album.releaseOwned && album.releaseAlbumId.isNotBlank()) {
			return BrowseTarget.Library(album.releaseAlbumId)
		}

		// 2. THE LOCAL LIBRARY, before anything on the network.
		//
		// lb-bot marks ownership from its release-group index, which only covers
		// artists whose discography it has actually scanned — so a record the user
		// owns by an unscanned artist comes back `owned: false` with no rgid, and
		// every later step is then answering the wrong question. Room holds the
		// whole library and can answer "do I have this" exactly, for free, offline.
		// This is why a Deezer tile for an album sitting on disk opened a download
		// page captioned "Not in your library".
		localAlbumFor(album.artist, album.title)?.let { return BrowseTarget.Library(it) }

		// 3. lb-bot resolved the row by name against its index.
		if (album.rgid.isNotBlank()) return BrowseTarget.External(album.rgid)

		// 4. One MusicBrainz search, for the one row the user actually tapped.
		val artist = album.artist.trim()
		val title = album.title.trim()
		if (title.isBlank()) return null
		// Both the search and the local match drop the edition suffix. Deezer
		// routinely ships "Discovery (Remastered)" where MusicBrainz and the
		// library both say "Discovery", and searching for the parenthetical
		// matches nothing while `LIKE '%…(Remastered)%'` matches nothing locally.
		val searchTitle = title.substringBefore(" (").substringBefore(" [").trim()
			.ifBlank { title }

		// FIELDED, not free text. `q` is passed to MusicBrainz verbatim, and a bare
		// "Daft Punk Discovery" scores a release-group *titled* "Daft Punk's
		// Discovery but it's in the SM64 Soundfont" above the actual `Discovery`,
		// because one title contains both search words and the other contains one.
		// That is not a hypothetical: it is what this row did.
		val candidates = if (artist.isNotBlank()) {
			lbBotManager.albumLookup(
				"""artist:"${lucene(artist)}" AND releasegroup:"${lucene(searchTitle)}""""
			).ifEmpty {
				// A fielded query that matches nothing is a real answer for a
				// mis-tagged Deezer row, so free text is still worth one attempt —
				// but the validation below is what keeps its ranking honest.
				lbBotManager.albumLookup(listOf(artist, searchTitle).joinToString(" "))
			}
		} else {
			lbBotManager.albumLookup(searchTitle)
		}

		val best = bestBrowseCandidate(candidates, artist, title) ?: return null
		if (best.releaseOwned && best.releaseAlbumId.isNotBlank()) {
			return BrowseTarget.Library(best.releaseAlbumId)
		}
		return best.rgid.ifBlank { null }?.let { BrowseTarget.External(it) }
	}

	/**
	 * The library's own album id for an artist + title, or null.
	 *
	 * Matched on normalised text rather than by id because there is no id to match
	 * on — Deezer carries no MBIDs, which is the whole reason these rows arrive
	 * unresolved. Deliberately strict: an edition suffix is ignored, everything
	 * else must agree, because a wrong "you already own this" sends the user to
	 * some other record of theirs and hides the one they asked for.
	 */
	private suspend fun localAlbumFor(artist: String, title: String): String? {
		val wantTitle = normalizeTitle(title)
		val wantArtist = normalizeName(artist)
		if (wantTitle.isBlank()) return null
		return runCatching {
			albumDao.searchAlbumsList(title.substringBefore(" (").substringBefore(" [").trim())
				.firstOrNull { row ->
					normalizeTitle(row.album.name.orEmpty()) == wantTitle &&
						(wantArtist.isBlank() || sameArtist(row.album.artistName.orEmpty(), artist))
				}
				?.album?.albumId
		}.getOrNull()
	}

	/**
	 * The candidate that is actually the record asked for, or null.
	 *
	 * **Never `firstOrNull()`.** MusicBrainz's ranking is a text score and lb-bot
	 * passes it through; validating the answer against what was asked is the only
	 * thing that separates `Discovery` from a parody of it. Declining is a correct
	 * outcome — the caller says so — and is much better than opening the wrong
	 * album, which reads as the feature being broken rather than as a near miss.
	 */
	private fun bestBrowseCandidate(
		candidates: List<LbAlbumCandidate>,
		artist: String,
		title: String
	): LbAlbumCandidate? {
		val wantTitle = normalizeTitle(title)
		val wantArtist = normalizeName(artist)
		if (wantTitle.isBlank()) return null
		return candidates
			.filter { normalizeTitle(it.title) == wantTitle }
			.filter { wantArtist.isBlank() || sameArtist(it.artist, artist) }
			// Ownership first: it is a fact about the library rather than a text
			// score, so it outranks anything MusicBrainz has to say.
			.maxByOrNull { (if (it.releaseOwned) 1_000_000 else 0) + it.score }
	}

	/** Lower-cased, punctuation-free, single-spaced. */
	private fun normalizeName(value: String): String = value
		.lowercase()
		.map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
		.joinToString("")
		.split(" ")
		.filter { it.isNotBlank() }
		.joinToString(" ")

	/**
	 * [normalizeName] plus the edition suffix dropped.
	 *
	 * "Discovery (Remastered)" and "Discovery" are the same album to a person and
	 * to this screen; keeping the parenthetical would make an owned record look
	 * unowned and send the tap to a download page.
	 */
	private fun normalizeTitle(value: String): String =
		normalizeName(value.substringBefore(" (").substringBefore(" ["))

	/** Escape what Lucene would otherwise read as syntax. */
	private fun lucene(value: String): String =
		value.replace("\\", "").replace("\"", "").trim()
}

/**
 * Two credits naming the same act (B-313): each credit is split into its acts on
 * [CREDIT_SEPARATORS], each act normalised ([creditKey]), and the two agree when one act is in both.
 *
 * MusicBrainz writes the full credit ("Daft Punk feat. Julian Casablancas") where Deezer writes the
 * act, and a library tag can be one string of several ("Phoebe Bridgers • Phoebe Bridgers, …",
 * "Lana Del Rey - Nikki Lane"). The old rule, a prefix on a token boundary, split nothing and called
 * "Pink" the same as "Pink Floyd" (containment is not agreement). The split is Worbus for desktop's
 * `album-match.ts` rule verbatim, with its vectors (`SameArtistTest`).
 *
 * The normaliser is the desktop's too since the owner's ruling (2026-11-12): a letter in any script
 * counts, on both clients. The shared vectors are `ArtistNameVectorsTest`. A credit with no act (all
 * punctuation, emoji, empty) matches nothing, itself included; an act `A (B)` is also A and B alone
 * ([creditedAct]).
 */
internal fun sameArtist(a: String, b: String): Boolean {
	val x = creditedActs(a)
	val y = creditedActs(b)
	return x.any { left -> y.any { right -> sameAct(left, right) } }
}

/**
 * B-313 amendment (2026-11-12, the coordinator's ruling for both clients; Worbus for desktop's
 * `creditedAct` / `sameAct`): an act written `A (B)`, ONE trailing part in round or full-width brackets,
 * is also A alone and B alone, each as a whole key. K-pop libraries tag "BTS (방탄소년단)" where lb-bot
 * and MusicBrainz say "BTS". B is never a name on its own when every word of its key is a number or a
 * [CREDIT_DISAMBIGUATORS] word ("(US)", "(band)", "(2)"); two bracketed acts match only as wholes
 * (`A (B)` is not `A (C)`, nor `B (A)`). Square brackets, two bracketed parts, an unclosed bracket:
 * not the shape.
 */
internal data class CreditedAct(val key: String, val bracketed: Boolean, val alone: List<String>)

private val TRAILING_BRACKET = Regex("""^([^()（）]*)[(（]([^()（）]*)[)）][\s\p{Z}]*$""")

/** Words that only tell two acts of one name apart (lower-case keys): the desktop's list, verbatim. */
internal val CREDIT_DISAMBIGUATORS: Set<String> = listOf(
	// roles
	"artist band composer dj duo group mc musician producer rapper singer songwriter trio vocalist",
	// genres
	"electronic folk hip hop indie jazz metal pop punk rap rock",
	// countries and regions
	"au br ca cn de dk es fi fr gb hk ie japan jp korea kr mx nl north nz pl ru se south tw ua uk us usa",
	// nationalities
	"american australian brazilian british canadian chinese danish dutch english finnish french german",
	"irish italian japanese korean mexican norwegian russian scottish spanish swedish",
	// the same roles in the scripts the shape is common in
	"가수 그룹 래퍼 밴드 バンド 歌手"
).joinToString(" ").split(" ").toSet()

private val ALL_DECIMAL_DIGITS = Regex("""\p{Nd}+""")

private fun isDisambiguator(key: String): Boolean =
	key.split(" ").all { ALL_DECIMAL_DIGITS.matches(it) || it in CREDIT_DISAMBIGUATORS }

/** One act's key, and for the `A (B)` shape the keys it also is alone; null when it has no key. */
internal fun creditedAct(part: String): CreditedAct? {
	val key = creditKey(part)
	if (key.isEmpty()) return null
	val shape = TRAILING_BRACKET.find(part.trim())
	val inner = shape?.let { creditKey(it.groupValues[2]) }.orEmpty()
	if (shape == null || inner.isEmpty()) return CreditedAct(key, bracketed = false, alone = emptyList())
	val alone = listOf(creditKey(shape.groupValues[1]), if (isDisambiguator(inner)) "" else inner).filter { it.isNotEmpty() }
	return CreditedAct(key, bracketed = true, alone = alone)
}

/** A credit's acts ([CREDIT_SEPARATORS]); a credit of separators only is one act, itself; none without a key. */
internal fun creditedActs(credit: String): List<CreditedAct> {
	val whole = creditedAct(credit) ?: return emptyList()
	val parts = credit.split(CREDIT_SEPARATORS).mapNotNull(::creditedAct)
	return parts.ifEmpty { listOf(whole) }
}

/** Exact whole keys; else a bracketed act against a plain one, by what the bracketed one is alone. */
internal fun sameAct(x: CreditedAct, y: CreditedAct): Boolean {
	if (x.key == y.key) return true
	if (x.bracketed == y.bracketed) return false
	val (shaped, plain) = if (x.bracketed) x to y else y to x
	return plain.key in shaped.alone
}

/**
 * Where a credit string separates acts: a hyphen-minus with whitespace on both sides, a bullet with
 * or without spaces, `&`, `+`, `,`, `;`, feat./featuring, with, vs./vs, x. Never `/` and never an
 * unspaced hyphen (AC/DC, Jay-Z, Run-DMC are one act each). Worbus for desktop's `CREDIT_SEPARATORS`.
 */
internal val CREDIT_SEPARATORS = Regex(
	// B-313: the keywords are whole words in any script, by explicit lookarounds (a letter, mark,
	// decimal digit or connector beside one keeps it inside a word): `\b` read "Iannis Xénakis" as
	// "Iannis" + "énakis" wherever it is ASCII (the JVM's host tests; JavaScript). `[\s\p{Z}]` is
	// JavaScript's `\s` (Unicode spaces), which the JVM's ASCII `\s` is not.
	"""[\s\p{Z}]+-[\s\p{Z}]+|[\s\p{Z}]*(?:&|\+|,|;|•|(?<![\p{L}\p{M}\p{Nd}\p{Pc}])(?:feat\.?|featuring|with|vs\.?|x)(?![\p{L}\p{M}\p{Nd}\p{Pc}]))[\s\p{Z}]*""",
	RegexOption.IGNORE_CASE
)

/**
 * B-313's shared key: lower-cased (language-neutral), every run of code points that are not a letter
 * (`\p{L}`) or a decimal digit (`\p{Nd}`) one space, ends trimmed. Java's regex reads code points, so
 * a letter outside the BMP counts, and every space that is not a letter (NBSP, U+3000, tab) separates.
 */
internal fun creditKey(value: String): String = value.lowercase().replace(NOT_A_LETTER_OR_DIGIT, " ").trim()

private val NOT_A_LETTER_OR_DIGIT = Regex("""[^\p{L}\p{Nd}]+""")
