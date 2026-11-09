package app.worbus.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * B-364: one row of the persisted fill ledger that does not decode must not cost the others, and
 * must not be erased by the next write. The ledger is ONE JSON string preference; `resumeWatches`
 * re-saves whatever `load` returned at every start, so a load that answers "empty" for a decode
 * error is a wipe on the next launch. Each case here is the load-then-save that start does.
 */
class FillLedgerStoreTest {
	private val now = 1_800_000_000_000L
	private val settings = MemorySettings()
	private val prefs = PreferenceManager(settings, testSecrets(settings))
	private val store = FillLedgerStore(prefs, lbWireJson, { now }, log = { _, _, _ -> })

	private fun good(key: String) =
		""""$key": {"kind": "album", "key": "$key", "startedAt": ${now - 1000}, "settled": true, "finishedAt": ${now - 500}, "album": "Album $key"}"""

	/** Every string the settings hold, so a test can ask "is this text kept anywhere?" without naming a key. */
	private fun everythingStored(): String = settings.keys.joinToString("\n") { settings.getString(it, "") }

	@Test
	fun aRowThatDoesNotDecodeCostsOnlyItself() {
		// `percent` is an Int: "lots" fails that row's decode, and only that row's.
		prefs.lbBotWatches = "{${good("rg-a")}, \"rg-bad\": {\"kind\": \"album\", \"key\": \"rg-bad\", \"percent\": \"lots\"}, ${good("rg-b")}}"

		val loaded = store.load()

		assertEquals(setOf("rg-a", "rg-b"), loaded.keys, "the readable rows survive one that is not")
		assertEquals("Album rg-a", loaded.getValue("rg-a").album)
	}

	@Test
	fun theNextSaveDoesNotEraseTheRowThatDidNotDecode() {
		prefs.lbBotWatches = "{${good("rg-a")}, \"rg-bad\": {\"kind\": \"album\", \"key\": \"rg-bad\", \"percent\": \"lots\"}}"

		store.save(store.load())

		val stored = everythingStored()
		assertTrue("\"rg-a\"" in prefs.lbBotWatches, "the readable row is written back")
		assertTrue("lots" in stored, "the row that did not decode is kept aside, not erased: $stored")
	}

	@Test
	fun aLedgerThatIsNotJsonAtAllIsKeptAsideBeforeItIsOverwritten() {
		val broken = "{\"rg-a\": {\"kind\": \"album\", \"key\": \"rg-a\", \"album\": \"Trunc"
		prefs.lbBotWatches = broken

		val loaded = store.load()
		store.save(loaded)

		assertTrue(loaded.isEmpty())
		assertEquals("{}", prefs.lbBotWatches)
		assertTrue(broken in everythingStored(), "the unreadable text is kept aside")
	}

	@Test
	fun aSoundLedgerRoundTrips() {
		prefs.lbBotWatches = "{${good("rg-a")}, ${good("rg-b")}}"

		val loaded = store.load()
		store.save(loaded)

		assertEquals(setOf("rg-a", "rg-b"), store.load().keys)
	}
}
