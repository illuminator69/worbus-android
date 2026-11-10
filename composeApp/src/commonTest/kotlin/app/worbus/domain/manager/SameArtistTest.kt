package app.worbus.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * B-313 (Navic half): where a browse tile opens (BrowseResolver) decides "is this the same artist"
 * with its own rule, a token prefix, so it split nothing: a bullet or a spaced hyphen credit named no
 * act of its own, and "Pink" agreed with "Pink Floyd" (the containment fault CLAUDE.md §7 names).
 * The rule is the desktop's (RESULTS-phase-b-desktop-2026-11-10 § B-313): split both credits into
 * acts on `CREDIT_SEPARATORS`, then an exact match within the sets. These are its vectors, plus the
 * prefix cases the old rule existed for.
 */
class SameArtistTest {
	private val vectors = listOf(
		Triple("Phoebe Bridgers • Phoebe Bridgers, Christian Lee Hutson", "Phoebe Bridgers", true),
		Triple("Phoebe Bridgers•Christian Lee Hutson", "Christian Lee Hutson", true),
		Triple("Lana Del Rey - Nikki Lane", "Lana Del Rey", true),
		Triple("Lana Del Rey - Nikki Lane", "Nikki Lane", true),
		Triple("Jay-Z", "Jay", false),
		Triple("Run-DMC", "Run", false),
		Triple("Run-DMC", "DMC", false),
		Triple("Lana Del Rey -Nikki Lane", "Nikki Lane", false),
		Triple("AC/DC", "AC", false),
		Triple("Jay-Z", "Jay-Z", true),
		// The old rule's own cases: a full credit against the act.
		Triple("Daft Punk feat. Julian Casablancas", "Daft Punk", true),
		Triple("Daft Punk", "Daft Punk", true),
		// Containment is not agreement (CLAUDE.md §7).
		Triple("Pink Floyd", "Pink", false)
	)

	@Test
	fun theDesktopsVectors() {
		val wrong = vectors.filter { (a, b, same) -> sameArtist(a, b) != same || sameArtist(b, a) != same }
		assertEquals(emptyList(), wrong, "(a, b, expected) pairs the rule answers wrongly, either way round")
	}
}
