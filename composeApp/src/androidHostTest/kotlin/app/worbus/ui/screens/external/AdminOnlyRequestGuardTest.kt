package app.worbus.ui.screens.external

import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertTrue

/*
 * B-408: the album page tells "only an admin can" (the server can download, this user may not) from
 * "this server isn't set up" (the server cannot) before it draws the request box.
 */
class AdminOnlyRequestGuardTest {
	@Test
	fun theAlbumPageAsksWhetherOffIsTheServerOrTheUser() {
		val page = sourceFile("src/commonMain/kotlin/app/worbus/ui/screens/external/ExternalAlbumScreen.kt").readText()
		val support = page.substringAfter("support = when {").substringBefore("},")
		val adminOnly = support.indexOf("Support.ADMIN_ONLY")
		assertTrue(adminOnly >= 0 && adminOnly < support.indexOf("Support.DOWNLOADS_OFF"), support)
		assertTrue("downloadsAdminOnly" in support, support)
	}
}
