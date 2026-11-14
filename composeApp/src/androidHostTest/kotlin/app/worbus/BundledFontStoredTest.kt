package app.worbus

import kotlin.test.Test
import kotlin.test.assertTrue

/*
 * B-446 (the owner, 2026-11-14): the first album page opened in a process stuttered, and the next ones did not.
 * Measured on the VM AVD (release, speed-profile, simpleperf on a profileable copy): Google Sans, a 4 MB variable
 * font, was stored DEFLATED in the APK, and every variation a page asked for first (`defaultFont(grade, round)`,
 * a Typeface each) was loaded by `android.graphics.fonts.Font.Builder`, which inflates a deflated asset into a new
 * 4 MB buffer (zlib's inflate_fast, a copy, the allocation) on the main thread, inside the album page's first
 * content frame: ~35-46 ms of main-thread CPU there. A stored asset is mapped in place.
 */
class BundledFontStoredTest {
	@Test
	fun theBundledFontIsStoredSoItIsMappedNotInflated() {
		val build = sourceFile("../androidApp/build.gradle.kts").readText()
		val block = build.substringAfter("androidResources {", "").substringBefore("}")
		assertTrue("noCompress += \"ttf\"" in block, "androidApp stores .ttf assets uncompressed")
		// Every bundled font is one the rule covers.
		val fonts = sourceFile("src/commonMain/composeResources/font").listFiles().orEmpty().map { it.name }
		assertTrue(fonts.isNotEmpty())
		for (font in fonts) assertTrue(font.endsWith(".ttf"), "$font is not covered by noCompress \"ttf\"")
	}

}
