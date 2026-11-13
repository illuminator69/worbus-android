package app.worbus.ui.screens.external

import app.worbus.domain.manager.DownloadsAccess
import app.worbus.sourceFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * B-408: the album page tells "only an admin can" (the server can, this user may not) from "this server
 * isn't set up" (the server cannot) before it draws the request box, and (review m3) says neither until
 * the server has said who the user is.
 */
class AdminOnlyRequestGuardTest {
	@Test
	fun theRequestBoxSaysWhatThisUserMayDo() {
		assertEquals(Support.ABSENT, requestSupport(configured = false, access = DownloadsAccess.ON, sourcePicker = true))
		assertEquals(Support.ADMIN_ONLY, requestSupport(true, DownloadsAccess.ADMIN_ONLY, true))
		assertEquals(Support.DOWNLOADS_OFF, requestSupport(true, DownloadsAccess.SERVER_OFF, true))
		assertEquals(Support.ABSENT, requestSupport(true, DownloadsAccess.ROLE_UNKNOWN, true), "nothing before the welcome")
		assertEquals(Support.NEEDS_HUB_RESTART, requestSupport(true, DownloadsAccess.ON, false))
		assertEquals(Support.YES, requestSupport(true, DownloadsAccess.ON, true))
	}

	@Test
	fun theAlbumPageAsksIt() {
		val page = sourceFile("src/commonMain/kotlin/app/worbus/ui/screens/external/ExternalAlbumScreen.kt").readText()
		assertTrue("support = requestSupport(lbBot.isConfigured, downloadsAccess, lbBot.supportsSourcePicker)" in page)
	}
}
