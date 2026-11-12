package app.worbus.domain.manager

import app.worbus.util.userAgent
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
import app.worbus.util.Logger
import androidx.compose.runtime.LaunchedEffect

/**
 * Tier-2 AudioMuse-AI core API client, unlocking features the Navidrome plugin
 * (Tier 1) can't expose: CLAP mood search, and the fingerprint and alchemy lists the
 * `fingerprint` / `adaptive` mixes play from when the hub has no station engine (I-040; with
 * one, radio and autoplay are the hub's, `StationManager`).
 *
 * TWO ROUTES, hub preferred (see DESIGN-hub-audiomuse-proxy.md):
 *  1. **Through the hub** — `<hub>/sonic/<route>` with `Authorization: Bearer <HUB_TOKEN>`.
 *     The hub holds the AudioMuse URL, the AudioMuse token and the Navidrome
 *     password server-side, so this device carries none of them, and Tier 2 works
 *     anywhere the hub is reachable without exposing the core API.
 *  2. **Direct** — the legacy `<audioMuseUrl>/api/<route>` with the per-device AudioMuse
 *     token. Kept as an explicit fallback for a LAN-only setup with no hub.
 *
 * FAIL-SOFT by design: every call returns empty on missing config / error /
 * timeout, so callers transparently fall back to Tier 1. See
 * DESIGN-adaptive-audiomuse.md.
 */
class AudioMuseManager(
	private val settings: Settings,
	private val preferenceManager: PreferenceManager,
	private val secrets: SecretStore,
	/** Worbus P2: the device's credential on the Worbus Server, read for every hub call. */
	private val hubAuth: HubAuth
) {
	/** Which service a call is going to, and how it authenticates. */
	private data class Endpoint(val base: String, val token: String, val viaHub: Boolean)

	/**
	 * When the hub told us its proxy isn't configured (`configured:false` from the
	 * probe), so we stop preferring a route that can't work and fall through to any
	 * direct config. Expires after [HUB_DEMOTE_TTL_MS] — the hub may gain an
	 * AUDIOMUSE_URL without this app restarting. Also cleared by a hub-settings change.
	 */
	private var hubDemotedAtMs = 0L
	private var lastHubConfig = ""

	private val hubProxyUsable: Boolean
		get() = hubDemotedAtMs == 0L ||
			Clock.System.now().toEpochMilliseconds() - hubDemotedAtMs > HUB_DEMOTE_TTL_MS

	/**
	 * `ws://host:4790` → `http://host:4790`. The proxy is plain HTTP on the hub's
	 * WebSocket port, so the base is the same URL with the scheme swapped and the
	 * `/connect` path (if any) dropped.
	 */
	private fun hubProxyBase(): String? {
		// Worbus P3 (C2): the address the live socket is on, never the typed one unchecked; none while no socket.
		val raw = hubAuth.routeAddress().orEmpty()
		if (raw.isBlank() || !hubAuth.hasCredential) return null
		val bearer = hubAuth.bearer().orEmpty()
		// A hub URL or credential change invalidates what we learned about the old hub.
		val sig = "${perServerKey(preferenceManager)}|${bearer.hashCode()}"
		if (sig != lastHubConfig) {
			lastHubConfig = sig
			hubDemotedAtMs = 0L
		}
		return hubHttpBaseOf(raw)
	}

	private fun directEndpoint(): Endpoint? {
		val base = preferenceManager.audioMuseUrl.trim().trimEnd('/')
		val token = preferenceManager.audioMuseToken
		return if (base.isNotBlank() && token.isNotBlank()) Endpoint(base, token, false) else null
	}

	/** The route the next call should take, or null when Tier 2 isn't configured at all. */
	private fun endpoint(): Endpoint? {
		val hub = hubProxyBase()
		if (hub != null && hubProxyUsable) return Endpoint(hub, hubAuth.bearer().orEmpty(), true)
		return directEndpoint()
	}

	/** Hub path when routed through the proxy, upstream path when going direct. */
	private fun Endpoint.url(hubPath: String, directPath: String): String =
		base + (if (viaHub) hubPath else directPath)

	val isConfigured: Boolean
		get() = endpoint() != null

	/**
	 * Changes whenever the Tier-2 route configuration does. Preferences here are plain
	 * delegated properties with no Flow behind them, so a probe can't *observe* a settings
	 * change — but keying a `LaunchedEffect` on this re-probes as soon as the screen next
	 * recomposes or is re-entered, instead of caching a stale "unavailable" for the
	 * lifetime of the composition.
	 */
	val routeSignature: String
		get() = "${perServerKey(preferenceManager)}|${hubAuth.bearer()?.hashCode() ?: 0}|" +
			"${preferenceManager.audioMuseUrl}|${preferenceManager.audioMuseToken.isNotBlank()}"

	// One client reused for the manager's lifetime. The old per-call new/close
	// built and tore down a full HttpClient on the hot autoplay top-up path.
	private val client by lazy {
		HttpClient {
			install(ContentNegotiation) {
				json(Json {
					ignoreUnknownKeys = true
					isLenient = true
					coerceInputValues = true
				})
			}
			install(UserAgent) { agent = userAgent() }
			// Tier-2 endpoints are synchronous in-memory lookups; keep timeouts tight
			// so a slow/unreachable core fails fast and we fall back to Tier 1.
			install(HttpTimeout) {
				connectTimeoutMillis = 8_000
				requestTimeoutMillis = 30_000
				socketTimeoutMillis = 30_000
			}
		}
	}

	/**
	 * Sonic Fingerprint — ids of tracks recommended from the user's listening
	 * habits (GET /sonic/fingerprint, or /api/sonic_fingerprint/generate direct).
	 * The core needs Navidrome creds to read play history: through the hub they are
	 * injected server-side from HUB_ND_USER/HUB_ND_PASS and this device sends none;
	 * on the direct route they still come from local settings. [] on any failure.
	 */
	suspend fun fetchSonicFingerprintIds(count: Int = 50): List<String> = fetchSonicFingerprint(count).ids

	/**
	 * The fingerprint as an answer that keeps why there is none: a refusal (AudioMuse too old for
	 * the POST, the hub's own `400 bad_request`, `403 primary_only`) is never read as an empty
	 * list. What a mix page shows (B-359, matching the hub, PROTOCOL §14).
	 */
	suspend fun fetchSonicFingerprint(count: Int = 50): FingerprintAnswer {
		val ep = endpoint() ?: return FingerprintAnswer.Unavailable
		// Worbus P2 (design §6.3): a device signed in to a Worbus Server reads the fingerprint
		// through the hub only (theirs if they are the primary user, else 403); never straight from
		// AudioMuse, which would hand it the primary user's listening with this device's credentials.
		// Nor one the server signed out or refused (review M-12): it still belongs to that server.
		if (!fingerprintRouteAllowed(ep.viaHub, hubAuth.boundToWorbusServer)) return FingerprintAnswer.Unavailable

		return try {
			val url = ep.url("/sonic/fingerprint", "/api/sonic_fingerprint/generate")
			// B-359: direct, the account goes in a JSON body (AudioMuse reads the same fields from a
			// POST), never in the URL, where AudioMuse's and any proxy's access log keep it.
			val answer = if (ep.viaHub) {
				hubFingerprint(client, url, ep.token, count)
			} else {
				directFingerprint(client, url, ep.token, count, settings.getString("username", ""), secrets.navidromePassword)
			}
			if (answer !is FingerprintAnswer.Ids) Logger.w("AudioMuseManager", "sonic fingerprint: $answer")
			answer
		} catch (e: Exception) {
			Logger.e("AudioMuseManager", "sonic fingerprint failed", e)
			FingerprintAnswer.Unavailable
		}
	}

	/**
	 * Song Alchemy (POST /api/alchemy) — blend [addIds] (pull toward) and
	 * [subtractIds] (push away) into a centroid and return the nearest tracks.
	 * What an `adaptive` mix plays from without the station engine. At least one ADD is
	 * required. [] on any failure.
	 */
	suspend fun fetchAlchemyMixIds(
		addIds: List<String>,
		subtractIds: List<String>,
		count: Int = 50,
		temperature: Float? = null,
		subtractDistance: Float? = null
	): List<String> {
		if (addIds.isEmpty()) return emptyList()
		val ep = endpoint() ?: return emptyList()

		// `type` is sent on every item, as Worbus for desktop sends it: a default the Json config does not
		// encode was simply absent from the wire, leaving AudioMuse to guess.
		val items = addIds.map { AlchemyItem(op = "ADD", id = it, type = "song") } +
			subtractIds.map { AlchemyItem(op = "SUBTRACT", id = it, type = "song") }

		return try {
			val resp: AlchemyResponse = client.post(ep.url("/sonic/alchemy", "/api/alchemy")) {
				header("Authorization", "Bearer ${ep.token}")
				contentType(ContentType.Application.Json)
				setBody(
					AlchemyRequest(
						items = items,
						n = count,
						temperature = temperature,
						subtractDistance = subtractDistance
					)
				)
			}.body()
			resp.results.map { it.itemId }
		} catch (e: Exception) {
			Logger.e("AudioMuseManager", "alchemy mix failed", e)
			emptyList()
		}
	}

	/**
	 * CLAP text→audio search (POST /api/clap/search) — ids of tracks whose audio
	 * embedding best matches a free-text mood query. [] on any failure; a disabled
	 * (400) or not-yet-loaded (503) server returns an error body whose `results`
	 * defaults to empty, so it fails soft just like a network error.
	 */
	suspend fun fetchClapSearchIds(query: String, limit: Int = 100): List<String> {
		if (query.isBlank()) return emptyList()
		val ep = endpoint() ?: return emptyList()

		return try {
			val url = ep.url("/sonic/clap/search", "/api/clap/search")
			val resp: ClapSearchResponse = client.post(url) {
				header("Authorization", "Bearer ${ep.token}")
				contentType(ContentType.Application.Json)
				setBody(ClapSearchRequest(query = query.trim(), limit = limit))
			}.body()
			resp.results.map { it.itemId }
		} catch (e: Exception) {
			Logger.e("AudioMuseManager", "clap search failed", e)
			emptyList()
		}
	}

	/**
	 * Whether CLAP search is usable on the server (enabled + embeddings loaded).
	 * Used to gate the Mood Search UI. False on any failure.
	 *
	 * Doubles as the Tier-2 route probe: the hub answers with its own
	 * `configured`/`upstreamReachable` alongside the upstream stats, so a hub with
	 * no AUDIOMUSE_URL demotes us to the direct route (if any) instead of leaving
	 * Tier 2 silently dead.
	 */
	suspend fun isClapAvailable(): Boolean = clapAvailability().usable

	/**
	 * [isClapAvailable] plus *why*, so the UI can say what's wrong instead of silently
	 * hiding the entry point (a missing feature and a misconfigured one look identical
	 * otherwise, which is exactly how a broken hub route went unnoticed).
	 */
	suspend fun clapAvailability(): ClapAvailability {
		val ep = endpoint() ?: return ClapAvailability.NOT_CONFIGURED
		val stats = fetchClapStats(ep)
		if (ep.viaHub) {
			// Two ways the hub route can be useless: it reports no AudioMuse configured,
			// or it doesn't answer with anything parseable at all — unreachable, or too
			// old to route /sonic/* (in which case the WebSocket handshake answers a
			// 426 text/plain, which is not JSON). Both mean "try the direct route".
			// Only handling the first left a device with a perfectly good direct config
			// with Tier 2 silently dead, because endpoint() always prefers the hub.
			if (stats == null || stats.configured == false) {
				hubDemotedAtMs = Clock.System.now().toEpochMilliseconds()
				val direct = directEndpoint()
					?: return if (stats == null) ClapAvailability.HUB_UNREACHABLE
					else ClapAvailability.NOT_CONFIGURED
				val fallback = fetchClapStats(direct) ?: return ClapAvailability.UNREACHABLE
				return fallback.toAvailability()
			}
			if (stats.upstreamReachable == false) return ClapAvailability.UNREACHABLE
			if (stats.configured == true) hubDemotedAtMs = 0L
		}
		if (stats == null) return ClapAvailability.UNREACHABLE
		return stats.toAvailability()
	}

	private fun ClapStats.toAvailability(): ClapAvailability = when {
		!clapEnabled -> ClapAvailability.DISABLED_ON_SERVER
		!(loaded || songCount > 0) -> ClapAvailability.NOT_ANALYZED
		else -> ClapAvailability.AVAILABLE
	}

	private companion object {
		/** How long a "hub proxy not configured" answer keeps us on the direct route. */
		const val HUB_DEMOTE_TTL_MS = 10 * 60 * 1000L
	}

	private suspend fun fetchClapStats(ep: Endpoint): ClapStats? = try {
		client.get(ep.url("/sonic/clap/stats", "/api/clap/stats")) {
			header("Authorization", "Bearer ${ep.token}")
		}.body()
	} catch (e: Exception) {
		Logger.e("AudioMuseManager", "clap stats failed", e)
		null
	}
}

@Serializable
data class AlchemyRequest(
	val items: List<AlchemyItem>,
	val n: Int,
	// Omitted from the JSON when null (encodeDefaults=false) → server defaults apply.
	val temperature: Float? = null,
	@SerialName("subtract_distance") val subtractDistance: Float? = null
)

@Serializable
data class AlchemyItem(
	val op: String,
	val id: String,
	val type: String
)

@Serializable
data class AlchemyResponse(
	val results: List<FingerprintItem> = emptyList(),
	@SerialName("centroid_2d") val centroid2d: List<Float>? = null
)

@Serializable
data class FingerprintItem(
	@SerialName("item_id") val itemId: String = ""
)

@Serializable
data class ClapSearchRequest(
	val query: String,
	val limit: Int
)

@Serializable
data class ClapSearchResponse(
	val results: List<FingerprintItem> = emptyList()
)

/** Why CLAP mood search is or isn't usable — drives the Search entry's enabled state. */
enum class ClapAvailability(val usable: Boolean) {
	AVAILABLE(true),

	/** No Tier-2 route at all: neither a hub URL+token nor a direct AudioMuse URL+token. */
	NOT_CONFIGURED(false),

	/** The hub answered nothing usable and there's no direct config to fall back to. */
	HUB_UNREACHABLE(false),

	/** A route exists but the AudioMuse core didn't answer. */
	UNREACHABLE(false),

	/** Reached the core, but CLAP is switched off there. */
	DISABLED_ON_SERVER(false),

	/** CLAP is on, but no embeddings are loaded yet — the library hasn't been analyzed. */
	NOT_ANALYZED(false)
}

@Serializable
data class ClapStats(
	@SerialName("clap_enabled") val clapEnabled: Boolean = false,
	@SerialName("song_count") val songCount: Int = 0,
	val loaded: Boolean = false,
	// Hub-proxy only (PROTOCOL §14): whether the hub has AUDIOMUSE_URL set and
	// could reach it. Null on the direct route.
	val configured: Boolean? = null,
	val upstreamReachable: Boolean? = null
)

/**
 * The fingerprint call's query: only `n`, on either route. Through the hub, the hub adds the
 * Navidrome account server-side; direct, it travels in [fingerprintBody] (B-359). Nothing secret
 * goes in a URL.
 */
internal fun fingerprintQuery(viaHub: Boolean, count: Int): List<Pair<String, String>> =
	if (viaHub) listOf("n" to count.toString()) else emptyList()

/**
 * The direct route's POST body. AudioMuse's core reads the listening history of the account it is
 * given (`navidrome_user` / `navidrome_password`, the same names as its GET query) and falls back
 * to its own configured account when they are absent; measured in AudioMuse 3.7.1's
 * `app_sonic_fingerprint.py`, 2026-11-10.
 */
internal fun fingerprintBody(count: Int, user: String, password: String): FingerprintRequest =
	FingerprintRequest(n = count, navidromeUser = user, navidromePassword = password)

@Serializable
data class FingerprintRequest(
	val n: Int,
	@SerialName("navidrome_user") val navidromeUser: String,
	@SerialName("navidrome_password") val navidromePassword: String
) {
	// Never print the password, whoever logs this.
	override fun toString(): String = "FingerprintRequest(n=$n, navidromeUser=$navidromeUser, navidromePassword=***)"
}

/** What the listening fingerprint answered (B-359). A refusal is never "nothing". */
sealed interface FingerprintAnswer {
	data class Ids(val ids: List<String>) : FingerprintAnswer

	/**
	 * AudioMuse will not take the POST that carries the account, so it is too old for the listening
	 * fingerprint (it needs [need]). The hub's `503 {"code":"audiomuse_too_old"}`, or the same
	 * refusal met on the direct route.
	 */
	data class AudioMuseTooOld(val need: String = FINGERPRINT_MIN_AUDIOMUSE) : FingerprintAnswer

	/** Refused with [status] and the body's `code` ("" when it named none): `400 bad_request`, `403 primary_only`, AudioMuse's own errors. */
	data class Refused(val status: Int, val code: String) : FingerprintAnswer

	/** No route, a route this device may not use, or no answer at all. */
	data object Unavailable : FingerprintAnswer
}

val FingerprintAnswer.ids: List<String> get() = (this as? FingerprintAnswer.Ids)?.ids.orEmpty()

/** The AudioMuse that reads the account from a POST body (PROTOCOL §14; the hub's `AUDIOMUSE_MIN_VERSION`). */
const val FINGERPRINT_MIN_AUDIOMUSE = "3.7.1"

private val fingerprintJson = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

private fun fingerprintIds(body: String): FingerprintAnswer.Ids =
	FingerprintAnswer.Ids(fingerprintJson.decodeFromString<List<FingerprintItem>>(body).map { it.itemId })

private fun jsonObjectOrNull(body: String): JsonObject? =
	runCatching { fingerprintJson.parseToJsonElement(body) as? JsonObject }.getOrNull()

private fun JsonObject.text(key: String): String =
	(this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

/**
 * The hub's `_audiomuse_refused_post`, verbatim: AudioMuse will not take the POST at all when it
 * answers 405 (Flask's method check), or a 400/415 whose body is not a JSON object (Flask's own
 * page, not an error of AudioMuse's route code, which answers JSON).
 */
internal fun audioMuseRefusedPost(status: Int, body: String): Boolean = when (status) {
	405 -> true
	400, 415 -> jsonObjectOrNull(body) == null
	else -> false
}

/**
 * The direct fingerprint route (B-359): the account in a POST body, never the URL. An AudioMuse that
 * refuses the POST ([audioMuseRefusedPost]) is too old for the listening fingerprint, as the hub
 * says it (PROTOCOL §14), and is NEVER asked again by GET: without the account in the query a GET
 * answers for AudioMuse's own configured account, someone else's listening shown as this user's
 * mix, and with it the password is in a URL (review of the fix round, major 2).
 */
internal suspend fun directFingerprint(
	client: HttpClient,
	url: String,
	token: String,
	count: Int,
	user: String,
	password: String
): FingerprintAnswer {
	val posted = client.post(url) {
		header("Authorization", "Bearer $token")
		contentType(ContentType.Application.Json)
		setBody(fingerprintBody(count, user, password))
	}
	val status = posted.status.value
	val body = posted.bodyAsText()
	return when {
		status in 200..299 -> fingerprintIds(body)
		audioMuseRefusedPost(status, body) -> FingerprintAnswer.AudioMuseTooOld()
		else -> FingerprintAnswer.Refused(status, jsonObjectOrNull(body)?.text("code").orEmpty())
	}
}

/**
 * The hub's `GET /sonic/fingerprint?n=`: the hub adds the account server-side (PROTOCOL §14). Its
 * `503 {"code":"audiomuse_too_old","need":…}` is [FingerprintAnswer.AudioMuseTooOld]; any other
 * refusal (`400 bad_request`, `403 primary_only`, `503 busy`) is [FingerprintAnswer.Refused].
 */
internal suspend fun hubFingerprint(client: HttpClient, url: String, token: String, count: Int): FingerprintAnswer {
	val answer = client.get(url) {
		header("Authorization", "Bearer $token")
		fingerprintQuery(viaHub = true, count).forEach { (name, value) -> parameter(name, value) }
	}
	val status = answer.status.value
	val body = answer.bodyAsText()
	if (status in 200..299) return fingerprintIds(body)
	val refusal = jsonObjectOrNull(body)
	val code = refusal?.text("code").orEmpty()
	return if (code == "audiomuse_too_old") {
		FingerprintAnswer.AudioMuseTooOld(refusal?.text("need").orEmpty().ifBlank { FINGERPRINT_MIN_AUDIOMUSE })
	} else FingerprintAnswer.Refused(status, code)
}
