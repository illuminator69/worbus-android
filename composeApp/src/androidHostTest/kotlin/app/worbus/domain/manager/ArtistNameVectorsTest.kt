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
 * Android answered differently on 9 rows before this (the `android` column of the desktop's table):
 * a letter outside the BMP (UTF-16 units were tested), whitespace other than a space (NBSP, U+3000,
 * tab stayed inside a word), an empty credit ({""}, so `!!!` = `???`), and `\b` around the keywords.
 * Characters that do not show are written as escapes; a supplementary character as its surrogates.
 */
class ArtistNameVectorsTest {
	private val tyoshi = "𠮷" // 𠮷 U+20BB7, outside the BMP
	private val other = "𠀋" // U+2000B, outside the BMP

	/** input to its key. */
	private val keys = listOf(
		"Daft Punk" to "daft punk",
		"AC/DC" to "ac dc",
		"Jay-Z" to "jay z",
		"  The   Beatles  " to "the beatles",
		"Blink-182" to "blink 182",
		"방탄소년단" to "방탄소년단",
		"米津玄師" to "米津玄師",
		"ヨルシカ" to "ヨルシカ",
		"Кино" to "кино",
		"ДДТ" to "ддт",
		"Σωκράτης" to "σωκράτης",
		"ΣΩΚΡΆΤΗΣ" to "σωκράτης",
		"Björk" to "björk",
		"Björk" to "bjo rk",
		"Sigur Rós" to "sigur rós",
		"Straße" to "straße",
		"İlhan" to "i lhan",
		"Ｙｏａｓｏｂｉ" to "ｙｏａｓｏｂｉ",
		"Ⅷ" to "",
		"Area²" to "area",
		"فرقة ٣" to "فرقة ٣",
		"कुमार सानू" to "क म र स न",
		"Daft Punk" to "daft punk",
		"米津　玄師" to "米津 玄師",
		"a\u0009b" to "a b",
		"${tyoshi}野家" to "${tyoshi}野家",
		"!!!" to "",
		"🎵" to "", // 🎵
		"" to ""
	)

	/** (a, b, sameArtist). */
	private val pairs = listOf(
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
		Triple("Daft Punk feat. Julian Casablancas", "Daft Punk", true),
		Triple("Pink Floyd", "Pink", false),
		Triple("방탄소년단", "방탄소년단", true),
		Triple("米津玄師", "米津玄師", true),
		Triple("Кино", "Кино", true),
		Triple("КИНО", "кино", true),
		Triple("Σωκράτης", "ΣΩΚΡΆΤΗΣ", true),
		Triple("米津玄師 & 菅田将暉", "菅田将暉", true),
		Triple("Кино feat. Земфира", "Земфира", true),
		Triple("Кино", "Ария", false),
		Triple("Björk", "Bjärk", false),
		Triple("Би-2", "Ю-2", false),
		Triple("Iannis Xénakis", "Iannis", false),
		Triple("BTS (방탄소년단)", "BTS", false),
		Triple("Joe Hisaishi 久石譲", "Joe Hisaishi", false),
		Triple("Björk", "Bjork", false),
		Triple("Björk", "Björk", false),
		Triple("José", "Jose", true),
		Triple("Ｙｏａｓｏｂｉ", "Yoasobi", false),
		Triple("कुमार सानू", "कोमार सोनू", true),
		Triple("Daft Punk", "Daft Punk", true),
		Triple("米津　玄師", "米津 玄師", true),
		Triple("${tyoshi}野家", "${other}野家", false),
		Triple("!!!", "???", false),
		Triple("", "", false),
		Triple("🎵", "💀", false) // 🎵 / 💀
	)

	@Test
	fun theTableIsTheDesktopsSixtyFiveRows() {
		assertEquals(65, keys.size + pairs.size)
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
