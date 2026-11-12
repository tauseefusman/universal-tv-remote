package com.example.tvremote.adapters

import com.example.tvremote.core.*
import com.example.tvremote.net.LocalTls
import com.example.tvremote.net.Net
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * Roku / Roku TV via External Control Protocol (ECP), plain HTTP on port 8060. No pairing required, but the TV's
 * Settings > System > Advanced system settings > Control by mobile apps must be "Default" or "Permissive"
 * (not "Disabled"/"Limited"). For power-on over Wi-Fi: Settings > System > Power > Fast TV start (Roku TVs).
 *
 * STATUS: written from the public ECP documentation; NOT compiled or tested against hardware in this build.
 */
class RokuAdapter : TvAdapter {
    override val platform = TvPlatform.ROKU
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state
    private val http = LocalTls.plainHttp()
    private var base = ""
    private var scope: CoroutineScope? = null
    private var isTv = true
    private var caps: Set<Feature> = Compatibility.base.getValue(platform)

    override fun features() = caps
    override suspend fun beginPairing(device: TvDevice): PairingSession = object : PairingSession {
        override val kind = PairingKind.NONE
        override suspend fun submit(code: String?) = ""
        override fun cancel() {}
    }

    private suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("$base$path").build()).execute().use { r ->
                if (r.code == 403) throw SecurityException("403")
                if (r.isSuccessful) r.body?.string() else null
            }
        }.getOrElse { if (it is SecurityException) throw it else null }
    }

    private suspend fun post(path: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("$base$path").post(ByteArray(0).toRequestBody()).build())
                .execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun tag(xml: String, name: String) = Regex("<$name>([^<]*)</$name>").find(xml)?.groupValues?.get(1)

    override suspend fun connect(device: TvDevice, credentials: String?) {
        disconnect()
        base = "http://${device.host}:${device.port ?: 8060}"
        _state.value = ConnectionState.Connecting
        refresh(device)
        if (_state.value == ConnectionState.Connected || _state.value == ConnectionState.Sleeping) {
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { sc ->
                sc.launch { while (isActive) { delay(5000); refresh(device) } }
            }
        }
    }

    private suspend fun refresh(device: TvDevice) {
        try {
            val xml = get("/query/device-info")
            if (xml == null) {
                _state.value = Net.classifyOffline(device.host, listOf(8060)); return
            }
            isTv = tag(xml, "is-tv") == "true"
            caps = Compatibility.base.getValue(platform).let { b ->
                // Players (non-TV) have no volume/power-on/inputs/channels.
                if (isTv) b else b - setOf(Feature.VOLUME, Feature.MUTE, Feature.CHANNEL, Feature.HDMI_INPUTS, Feature.SOURCE)
            }
            _state.value = if (tag(xml, "power-mode").let { it == "PowerOn" || it == null }) ConnectionState.Connected
            else ConnectionState.Sleeping // "Ready" / "DisplayOff": display off but ECP alive
        } catch (e: SecurityException) {
            _state.value = ConnectionState.Error(
                "The Roku refused control. On the TV go to Settings > System > Advanced system settings > Control by mobile apps and choose Default or Permissive.")
        }
    }

    override suspend fun disconnect() { scope?.cancel(); scope = null; _state.value = ConnectionState.Disconnected }

    private val keys = mapOf(
        RemoteKey.HOME to "Home", RemoteKey.BACK to "Back", RemoteKey.MENU to "Info", RemoteKey.SOURCE to "InputTuner",
        RemoteKey.UP to "Up", RemoteKey.DOWN to "Down", RemoteKey.LEFT to "Left", RemoteKey.RIGHT to "Right", RemoteKey.OK to "Select",
        RemoteKey.VOL_UP to "VolumeUp", RemoteKey.VOL_DOWN to "VolumeDown", RemoteKey.MUTE to "VolumeMute",
        RemoteKey.CH_UP to "ChannelUp", RemoteKey.CH_DOWN to "ChannelDown", RemoteKey.POWER to "Power",
        RemoteKey.PLAY_PAUSE to "Play", RemoteKey.REWIND to "Rev", RemoteKey.FAST_FORWARD to "Fwd",
        RemoteKey.PREVIOUS to "InstantReplay", // closest equivalent: replay last few seconds
    )

    override suspend fun send(key: RemoteKey, action: KeyAction) {
        val name = keys[key] ?: return
        val verb = when (action) { KeyAction.CLICK -> "keypress"; KeyAction.PRESS -> "keydown"; KeyAction.RELEASE -> "keyup" }
        post("/$verb/$name")
    }

    override suspend fun sendText(text: String) {
        for (c in text) { post("/keypress/Lit_" + URLEncoder.encode(c.toString(), "UTF-8")); delay(20) }
    }

    override suspend fun moveCursor(dx: Int, dy: Int) {
        send(if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) (if (dx > 0) RemoteKey.RIGHT else RemoteKey.LEFT)
        else (if (dy > 0) RemoteKey.DOWN else RemoteKey.UP))
    }

    override suspend fun launchApp(app: KnownApp): LaunchResult {
        val id = AppIds.resolve(platform, app) ?: return LaunchResult.Unsupported("No channel id known for ${app.label}")
        val installed = listApps().map { it.id }
        if (installed.isNotEmpty() && id !in installed) return LaunchResult.NotInstalled
        return launchAppId(id)
    }

    override suspend fun launchAppId(appId: String): LaunchResult =
        if (post("/launch/$appId")) LaunchResult.Launched else LaunchResult.Failed("The Roku did not accept the launch request")

    override suspend fun listApps(): List<AppEntry> {
        val xml = runCatching { get("/query/apps") }.getOrNull() ?: return emptyList()
        return Regex("<app id=\"([^\"]+)\"[^>]*>([^<]+)</app>").findAll(xml)
            .map { AppEntry(it.groupValues[1], it.groupValues[2].replace("&amp;", "&")) }.toList()
    }

    override suspend fun switchInput(input: HdmiInput): Boolean =
        isTv && post("/keypress/Input" + input.name) // InputHDMI1.. : name is HDMI1 -> "InputHDMI1"

    override suspend fun powerOn(device: TvDevice): Boolean {
        if (!isTv) return false
        base = "http://${device.host}:${device.port ?: 8060}"
        return post("/keypress/PowerOn")
    }

    override fun powerOnHint() = "On the Roku TV enable Settings > System > Power > Fast TV start so it keeps listening on Wi-Fi in standby."
}
