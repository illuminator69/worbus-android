package app.worbus.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * B-313, the owner's ruling (2026-11-12): one artist-name rule on both clients, a letter in ANY script
 * counts. The table is Worbus for desktop's `artist-name-vectors.ts` (navi-connect
 * RESULTS-phase-b-desktop-2026-11-10.md, "B-313: the shared normaliser vectors"), row for row, held
 * here on `creditKey` and on `sameArtist` both ways round.
 *
 * The rule: lower-case (language-neutral), then every code point that is not a letter (\p{L}) or a
 * decimal digit (\p{Nd}) is a separator, runs collapse to one space, ends trimmed; no accent folding,
 * no NFC/NFKC. A credit splits into acts on CREDIT_SEPARATORS, whose keywords count only where no
 * letter, mark, decimal digit or connector stands beside them, in any script. An empty key is no act,
 * and two credits with no act never match.
 *
 * The amendment (2026-11-12, the coordinator's ruling for both clients): an act `A (B)`, one trailing
 * part in round or full-width brackets, is also A alone and B alone, as whole keys; B never when every
 * word of its key is a number or a disambiguator ("(US)", "(band)", "(2)"); two bracketed acts match
 * only as wholes.
 *
 * Android answered differently on 9 rows of the build before this (the `android` column of the desktop's table):
 * a letter outside the BMP (UTF-16 units were tested), whitespace other than a space (NBSP, U+3000,
 * tab stayed inside a word), an empty credit ({""}, so `!!!` = `???`), and `\b` around the keywords.
 * Characters that do not show are written as escapes; a supplementary character as its surrogates.
 */
class ArtistNameVectorsTest {
	// Generated from the desktop's `artist-name-vectors.ts` at Worbus for desktop `557d89831` (89 rows: the
	// 65 of the build plus the `A (B)` amendment, whose "BTS (방탄소년단) / BTS" is now a match).
	/** input to its key. */
	private val keys = listOf(
		"Daft Punk" to "daft punk", // Latin: unchanged
		"AC/DC" to "ac dc", // punctuation is a separator
		"Jay-Z" to "jay z", // an unspaced hyphen is a separator inside the act
		"  The   Beatles  " to "the beatles", // runs collapse, ends trimmed
		"Blink-182" to "blink 182", // ASCII digits kept
		"방탄소년단" to "방탄소년단", // Hangul syllables are letters
		"米津玄師" to "米津玄師", // Han ideographs are letters
		"ヨルシカ" to "ヨルシカ", // katakana are letters
		"Кино" to "кино", // Cyrillic, lower-cased
		"ДДТ" to "ддт", // Cyrillic capitals
		"Σωκράτης" to "σωκράτης", // Greek, final sigma kept
		"ΣΩΚΡΆΤΗΣ" to "σωκράτης", // Greek capitals: a final Σ lower-cases to ς
		"Björk" to "björk", // precomposed ö is a letter, not folded to o
		"Bjo\u0308rk" to "bjo rk", // decomposed (NFD) ö: the combining mark is a separator; no NFC
		"Sigur Rós" to "sigur rós", // no accent folding
		"Straße" to "straße", // ß stays ß (no case folding to ss)
		"İlhan" to "i lhan", // İ lower-cases to i + U+0307, the mark a separator
		"Ｙｏａｓｏｂｉ" to "ｙｏａｓｏｂｉ", // full-width Ｙｏａｓｏｂｉ: lower-cased, NOT folded to ASCII (no NFKC)
		"Ⅷ" to "", // a Roman-numeral sign (Nl) is not a digit
		"Area²" to "area", // a superscript (No) is not a digit
		"فرقة ٣" to "فرقة ٣", // Arabic letters and Arabic-Indic digits (Nd)
		"क\u0941म\u093eर स\u093eन\u0942" to "क म र स न", // Devanagari vowel signs are marks, so separators (see the pairs)
		"Daft\u00a0Punk" to "daft punk", // a no-break space is a separator; Android keeps it inside the word
		"米津\u3000玄師" to "米津 玄師", // an ideographic space is a separator; Android keeps it inside the word
		"a\u0009b" to "a b", // a tab is a separator; Android keeps it
		"\ud842\udfb7野家" to "\ud842\udfb7野家", // 𠮷 (U+20BB7, outside the BMP) is a letter; Android tests UTF-16 units and drops it
		"!!!" to "", // nothing but punctuation
		"\ud83c\udfb5" to "", // an emoji is not a letter
		"" to "", // empty
	)

	/** (a, b, sameArtist). */
	private val pairs = listOf(
		Triple("Phoebe Bridgers • Phoebe Bridgers, Christian Lee Hutson", "Phoebe Bridgers", true), // bullet
		Triple("Phoebe Bridgers•Christian Lee Hutson", "Christian Lee Hutson", true), // bullet
		Triple("Lana Del Rey - Nikki Lane", "Lana Del Rey", true), // spaced hyphen
		Triple("Lana Del Rey - Nikki Lane", "Nikki Lane", true), // spaced hyphen
		Triple("Jay-Z", "Jay", false), // unspaced hyphen
		Triple("Run-DMC", "Run", false), // unspaced hyphen
		Triple("Run-DMC", "DMC", false), // unspaced hyphen
		Triple("Lana Del Rey -Nikki Lane", "Nikki Lane", false), // one side unspaced
		Triple("AC/DC", "AC", false), // slash
		Triple("Jay-Z", "Jay-Z", true), // itself
		Triple("Daft Punk feat. Julian Casablancas", "Daft Punk", true), // feat.
		Triple("Pink Floyd", "Pink", false), // containment is not agreement
		Triple("방탄소년단", "방탄소년단", true), // Hangul, itself
		Triple("米津玄師", "米津玄師", true), // Han, itself
		Triple("Кино", "Кино", true), // Cyrillic, itself
		Triple("КИНО", "кино", true), // Cyrillic, case
		Triple("Σωκράτης", "ΣΩΚΡΆΤΗΣ", true), // Greek, case and final sigma
		Triple("米津玄師 & 菅田将暉", "菅田将暉", true), // split, then a Han act
		Triple("Кино feat. Земфира", "Земфира", true), // split, then a Cyrillic act
		Triple("Кино", "Ария", false), // two different names
		Triple("Björk", "Bjärk", false), // constructed: both were "bj rk"
		Triple("Би-2", "Ю-2", false), // constructed: both were "2"
		Triple("Iannis Xénakis", "Iannis", false), // x before é split there (an ASCII word boundary); a letter in any script keeps x inside the word
		Triple("Joe Hisaishi 久石譲", "Joe Hisaishi", false), // an alias with no bracket
		Triple("BTS (방탄소년단)", "BTS", true), // A (B) matches A
		Triple("BTS (방탄소년단)", "방탄소년단", true), // A (B) matches B
		Triple("BTS（방탄소년단）", "BTS", true), // full-width brackets, no space before them
		Triple("BTS（방탄소년단）", "방탄소년단", true), // full-width brackets: B
		Triple("BTS (방탄소년단)", "BTS (방탄소년단)", true), // A (B) matches itself
		Triple("BTS (방탄소년단)", "BTS (防弾少年団)", false), // A (B) against A (C): no
		Triple("BTS (방탄소년단)", "방탄소년단 (BTS)", false), // two bracketed acts compare as wholes: the swapped form does not match (open question)
		Triple("IU (아이유) & BTS (방탄소년단)", "아이유", true), // the credit is split first, then each act read
		Triple("BTS (방탄소년단)", "방탄", false), // B matches only as a whole key
		Triple("BTS (방탄소년단)", "BT", false), // A matches only as a whole key
		Triple("Nirvana (US)", "Nirvana", true), // disambiguated A (B) matches A
		Triple("Nirvana (US)", "US", false), // a disambiguator is not a name
		Triple("Nirvana (UK)", "Nirvana (US)", false), // disambiguators still tell two acts apart
		Triple("Genesis (band)", "Band", false), // a disambiguator is not a name
		Triple("Bush (UK band)", "UK Band", false), // every word a disambiguator
		Triple("Nirvana (2)", "Nirvana", true), // a Discogs number: A matches
		Triple("Nirvana (2)", "2", false), // a number is not a name
		Triple("Hans Zimmer (composer)", "Composer", false), // a role word is not a name
		Triple("아이유 (가수)", "가수", false), // a native-script role word is not a name
		Triple("Robbie Robertson (The Band)", "The Band", true), // a name in the bracket is a name, even one made of a disambiguator and "the"
		Triple("Foo (Bar) (Baz)", "Foo", false), // two bracketed parts: not the shape
		Triple("BTS [방탄소년단]", "BTS", false), // square brackets: not the shape
		Triple("BTS (방탄소년단", "BTS", false), // an unclosed bracket: not the shape
		Triple("Foo (Bar)", "Foo Bar", true), // the whole key, as before
		Triple("!!! (Chk Chk Chk)", "Chk Chk Chk", true), // A has no key: the whole is B, as before
		Triple("Björk", "Bjork", false), // no accent folding
		Triple("Bjo\u0308rk", "Björk", false), // NFD against NFC: no normalisation form
		Triple("Jose\u0301", "Jose", true), // NFD: the mark is a separator, so the accent drops
		Triple("Ｙｏａｓｏｂｉ", "Yoasobi", false), // no NFKC
		Triple("क\u0941म\u093eर स\u093eन\u0942", "क\u094bम\u093eर स\u094bन\u0942", true), // constructed: names that differ only in vowel signs (marks) collide, on both clients
		Triple("Daft\u00a0Punk", "Daft Punk", true), // a no-break space separates words
		Triple("米津\u3000玄師", "米津 玄師", true), // an ideographic space separates words
		Triple("\ud842\udfb7野家", "\ud840\udc0b野家", false), // constructed: two letters outside the BMP are two letters (Android reads both as "野家")
		Triple("!!!", "???", false), // no act on either side
		Triple("", "", false), // empty against empty
		Triple("\ud83c\udfb5", "\ud83d\udc80", false), // emoji only: no act
	)

	@Test
	fun theTableIsTheDesktopsEightyNineRows() {
		assertEquals(29 to 60, keys.size to pairs.size)
	}

	@Test
	fun everyKeyRow() {
		val wrong = keys.filter { (input, key) -> creditKey(input) != key }
			.map { (input, key) -> "${esc(input)}: expected ${esc(key)}, got ${esc(creditKey(input))}" }
		assertEquals(emptyList(), wrong)
	}

	@Test
	fun everyPairRowBothWaysRound() {
		val wrong = pairs.filter { (a, b, same) -> sameArtist(a, b) != same || sameArtist(b, a) != same }
			.map { (a, b, same) -> "${esc(a)} / ${esc(b)}: expected $same" }
		assertEquals(emptyList(), wrong)
	}

	private fun esc(s: String): String = buildString {
		append('"')
		var i = 0
		while (i < s.length) {
			val cp = s.codePointAt(i)
			if (cp < 0x20 || cp == 0xA0 || cp == 0x3000 || cp > 0xFFFF || Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) {
				append("\\u{").append(cp.toString(16)).append('}')
			} else appendCodePoint(cp)
			i += Character.charCount(cp)
		}
		append('"')
	}
}
