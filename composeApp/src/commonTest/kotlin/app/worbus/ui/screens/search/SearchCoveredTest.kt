package app.worbus.ui.screens.search

import app.worbus.ui.screens.search.components.searchCovered
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * B-449 (measured on the VM AVD, 2026-11-14: the keyboard still shown 690 ms after tapping an artist result, gone by
 * 984 ms, over the pushed artist page): the search field kept its focus while a result's page was pushed over Search.
 */
class SearchCoveredTest {
	@Test
	fun aPagePushedOverSearchCoversIt() {
		assertFalse(searchCovered(depthAtArrival = 2, depthNow = 2))
		assertTrue(searchCovered(depthAtArrival = 2, depthNow = 3))
		assertFalse(searchCovered(depthAtArrival = 2, depthNow = 1), "Search itself leaving is not a cover")
	}
}
