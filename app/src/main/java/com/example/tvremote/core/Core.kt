package com.example.tvremote.core

import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

enum class TvPlatform(val label: String) {
    ANDROID_TV("Android TV / Google TV"),
    TIZEN("Samsung Tizen"),
    WEBOS("LG webOS"),
    ROKU("Roku TV")
}

/** What a connected TV can actually do. Shown in the UI so unsupported controls are disabled, not broken. */
enum class Feature {
    POWER_TOGGLE, POWER_ON_NETWORK, DPAD, VOLUME, MUTE, CHANNEL, NUMBERS, MEDIA,
    SETTINGS, SOURCE, TEXT_INPUT, TOUCHPAD, HOLD_KEYS, LAUNCH_APPS, LIST_APPS, HDMI_INPUTS
}

enum class RemoteKey {
    POWER, HOME, BACK, MENU, SETTINGS, SOURCE,
    UP, DOWN, LEFT, RIGHT, OK,
    VOL_UP, VOL_DOWN, MUTE, CH_UP, CH_DOWN,
    NUM0, NUM1, NUM2, NUM3, NUM4, NUM5, NUM6, NUM7, NUM8, NUM9,
    PLAY_PAUSE, REWIND, FAST_FORWARD, NEXT, PREVIOUS
}

enum class KeyAction { CLICK, PRESS, RELEASE }

enum class HdmiInput(val label: String) { HDMI1("HDMI 1"), HDMI2("HDMI 2"), HDMI3("HDMI 3"), HDMI4("HDMI 4") }

/** Logical apps with one-tap shortcuts. Per-platform identifiers live in [AppIds]. */
enum class KnownApp(val label: String) {
    YOUTUBE("YouTube"), NETFLIX("Netflix"), PRIME("Prime Video"), DISNEY("Disney+"), SPOTIFY("Spotify")
}

data class TvDevice(
    val id: String,
    val name: String,
    val platform: TvPlatform,
    val host: String,
    val port: Int? = null,
    val model: String? = null,
    val firmware: String? = null,
    val mac: String? = null,
    val pairedBefore: Boolean = false,
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("platform", platform.name); put("host", host)
        port?.let { put("port", it) }; model?.let { put("model", it) }
        firmware?.let { put("firmware", it) }; mac?.let { put("mac", it) }
        put("paired", pairedBefore)
    }

    companion object {
        fun fromJson(o: JSONObject) = TvDevice(
            id = o.getString("id"), name = o.getString("name"),
            platform = TvPlatform.valueOf(o.getString("platform")), host = o.getString("host"),
            port = if (o.has("port")) o.getInt("port") else null,
            model = o.optString("model").ifBlank { null },
            firmware = o.optString("firmware").ifBlank { null },
            mac = o.optString("mac").ifBlank { null },
            pairedBefore = o.optBoolean("paired"),
        )
    }
}

data class AppEntry(val id: String, val name: String)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object NeedsPairing : ConnectionState
    data object Connected : ConnectionState
    /** Host reachable (e.g. ping/ECP answers) but control channel refused: TV is in standby. */
    data object Sleeping : ConnectionState
    /** Host not reachable at all: wrong Wi-Fi, TV unplugged, or IP changed. */
    data object Unreachable : ConnectionState
    data class Error(val message: String) : ConnectionState
}

sealed interface LaunchResult {
    data object Launched : LaunchResult
    data object NotInstalled : LaunchResult
    data class Unsupported(val reason: String) : LaunchResult
    data class Failed(val reason: String) : LaunchResult
}

enum class PairingKind { NONE, PIN_ON_TV, APPROVE_ON_TV }

/** A pairing handshake in progress. For PIN_ON_TV the UI collects a code and calls [submit]. */
interface PairingSession {
    val kind: PairingKind
    /** Returns an opaque credential blob to be stored securely, or throws [PairingDenied]. */
    suspend fun submit(code: String?): String
    fun cancel()
}

class PairingDenied(message: String) : Exception(message)

interface TvAdapter {
    val platform: TvPlatform
    val state: StateFlow<ConnectionState>
    /** Features available right now (may depend on model/firmware once connected). */
    fun features(): Set<Feature>

    suspend fun beginPairing(device: TvDevice): PairingSession
    /** [credentials] is the blob returned by [PairingSession.submit], or null for platforms that need none. */
    suspend fun connect(device: TvDevice, credentials: String?)
    suspend fun disconnect()

    suspend fun send(key: RemoteKey, action: KeyAction = KeyAction.CLICK)
    suspend fun sendText(text: String)
    suspend fun moveCursor(dx: Int, dy: Int) {}
    suspend fun launchApp(app: KnownApp): LaunchResult
    suspend fun launchAppId(appId: String): LaunchResult
    suspend fun listApps(): List<AppEntry>
    suspend fun switchInput(input: HdmiInput): Boolean
    /** Wake a sleeping TV. Returns false if this TV/platform cannot be woken over the network. */
    suspend fun powerOn(device: TvDevice): Boolean
    /** Human-readable hint about the TV setting needed for network wake, when known. */
    fun powerOnHint(): String?
}

/** Identifier tables. Values are widely used community-documented IDs; verify against your TV firmware. */
object AppIds {
    private val roku = mapOf(
        KnownApp.YOUTUBE to "837", KnownApp.NETFLIX to "12", KnownApp.PRIME to "13",
        KnownApp.DISNEY to "291097", KnownApp.SPOTIFY to "22297",
    )
    private val webos = mapOf(
        KnownApp.YOUTUBE to "youtube.leanback.v4", KnownApp.NETFLIX to "netflix",
        KnownApp.PRIME to "amazon", KnownApp.DISNEY to "com.disney.disneyplus-prod",
        KnownApp.SPOTIFY to "spotify-beehive",
    )
    private val tizen = mapOf(
        KnownApp.YOUTUBE to "111299001912", KnownApp.NETFLIX to "11101200001",
        KnownApp.PRIME to "3201512006785", KnownApp.DISNEY to "3201901017640",
        KnownApp.SPOTIFY to "3201606009684",
    )
    /** Android TV launches via deep links (app_link_launch_request), not package names. */
    private val androidTv = mapOf(
        KnownApp.YOUTUBE to "https://www.youtube.com", KnownApp.NETFLIX to "https://www.netflix.com/browse",
        KnownApp.PRIME to "https://app.primevideo.com", KnownApp.DISNEY to "https://www.disneyplus.com",
        KnownApp.SPOTIFY to "https://open.spotify.com",
    )

    fun resolve(platform: TvPlatform, app: KnownApp): String? = when (platform) {
        TvPlatform.ROKU -> roku[app]
        TvPlatform.WEBOS -> webos[app]
        TvPlatform.TIZEN -> tizen[app]
        TvPlatform.ANDROID_TV -> androidTv[app]
    }
}

/**
 * Compatibility knowledge by platform. Runtime detection (model/firmware reported by the TV) refines this;
 * the README matrix is generated from the same data. Entries marked UNVERIFIED have not been tested on hardware.
 */
object Compatibility {
    val base: Map<TvPlatform, Set<Feature>> = mapOf(
        TvPlatform.ANDROID_TV to setOf(
            Feature.POWER_TOGGLE, Feature.DPAD, Feature.VOLUME, Feature.MUTE, Feature.CHANNEL, Feature.NUMBERS,
            Feature.MEDIA, Feature.SETTINGS, Feature.SOURCE, Feature.TEXT_INPUT, Feature.HOLD_KEYS,
            Feature.LAUNCH_APPS, Feature.TOUCHPAD,
        ),
        TvPlatform.ROKU to setOf(
            Feature.POWER_TOGGLE, Feature.POWER_ON_NETWORK, Feature.DPAD, Feature.VOLUME, Feature.MUTE,
            Feature.CHANNEL, Feature.MEDIA, Feature.SOURCE, Feature.TEXT_INPUT, Feature.HOLD_KEYS,
            Feature.LAUNCH_APPS, Feature.LIST_APPS, Feature.HDMI_INPUTS, Feature.TOUCHPAD,
        ),
        TvPlatform.WEBOS to setOf(
            Feature.POWER_TOGGLE, Feature.POWER_ON_NETWORK, Feature.DPAD, Feature.VOLUME, Feature.MUTE,
            Feature.CHANNEL, Feature.NUMBERS, Feature.MEDIA, Feature.SETTINGS, Feature.SOURCE,
            Feature.TEXT_INPUT, Feature.LAUNCH_APPS, Feature.LIST_APPS, Feature.HDMI_INPUTS, Feature.TOUCHPAD,
        ),
        TvPlatform.TIZEN to setOf(
            Feature.POWER_TOGGLE, Feature.POWER_ON_NETWORK, Feature.DPAD, Feature.VOLUME, Feature.MUTE,
            Feature.CHANNEL, Feature.NUMBERS, Feature.MEDIA, Feature.SETTINGS, Feature.SOURCE,
            Feature.TEXT_INPUT, Feature.HOLD_KEYS, Feature.LAUNCH_APPS, Feature.LIST_APPS,
        ),
    )
}
