package com.example.tvremote.adapters

import android.util.Base64
import com.example.tvremote.core.*
import com.example.tvremote.net.LocalTls
import com.example.tvremote.net.Net
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Samsung Tizen (2016+ models) via the smart-remote WebSocket on wss port 8002
 * (/api/v2/channels/samsung.remote.control). First connection shows an "Allow" prompt on the TV; the TV returns a
 * token we reuse. Power-on over Wi-Fi uses Wake-on-LAN (Settings > General > Network > Expert Settings > Power On with Mobile).
 *
 * STATUS: written from public community documentation; NOT compiled or tested against hardware in this build.
 * Samsung firmware varies a lot: app listing and some key codes are known to differ or be disabled on newer models.
 */
class TizenAdapter : TvAdapter {
    override val platform = TvPlatform.TIZEN
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state
    override fun features() = Compatibility.base.getValue(platform)

    private val client = LocalTls.okHttp()
    private var ws: WebSocket? = null
    private var device: TvDevice? = null
    private var appsWaiter: CompletableDeferred<List<AppEntry>>? = null

    override suspend fun beginPairing(device: TvDevice): PairingSession = object : PairingSession {
        override val kind = PairingKind.APPROVE_ON_TV
        override suspend fun submit(code: String?): String = open(device, null, 40_000)
        override fun cancel() { ws?.cancel() }
    }

    override suspend fun connect(device: TvDevice, credentials: String?) {
        disconnect(); this.device = device
        if (credentials == null) { _state.value = ConnectionState.NeedsPairing; return }
        _state.value = ConnectionState.Connecting
        try { open(device, credentials, 8_000); _state.value = ConnectionState.Connected }
        catch (e: PairingDenied) { _state.value = ConnectionState.NeedsPairing }
        catch (e: Exception) { _state.value = Net.classifyOffline(device.host, listOf(8001, 8002)) }
    }

    private suspend fun open(device: TvDevice, token: String?, waitMs: Long): String {
        val name = Base64.encodeToString("UniversalTvRemote".toByteArray(), Base64.NO_WRAP)
        val url = "wss://${device.host}:8002/api/v2/channels/samsung.remote.control?name=$name" + (token?.let { "&token=$it" } ?: "")
        val done = CompletableDeferred<String>()
        val sock = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (m.optString("event")) {
                    "ms.channel.connect" -> done.complete(m.optJSONObject("data")?.optString("token").orEmpty().ifBlank { token.orEmpty() })
                    "ms.channel.unauthorized", "ms.channel.timeOut" -> done.completeExceptionally(PairingDenied("The TV denied or ignored the connection request."))
                    "ed.installedApp.get" -> {
                        val arr = m.optJSONObject("data")?.optJSONArray("data")
                        val list = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }
                            .map { AppEntry(it.getString("appId"), it.optString("name", it.getString("appId"))) }
                        appsWaiter?.complete(list)
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                done.completeExceptionally(t)
                if (_state.value == ConnectionState.Connected) {
                    val d = device
                    CoroutineScope(Dispatchers.IO).launch { _state.value = Net.classifyOffline(d.host, listOf(8001, 8002)) }
                }
            }
        })
        try {
            val t = withTimeout(waitMs) { done.await() }
            ws = sock; return t
        } catch (e: TimeoutCancellationException) {
            sock.cancel(); throw PairingDenied("No response from the TV. Did you choose Allow on screen?")
        } catch (e: Exception) { sock.cancel(); throw e }
    }

    override suspend fun disconnect() { runCatching { ws?.close(1000, null) }; ws = null; _state.value = ConnectionState.Disconnected }

    private fun sendJson(o: JSONObject) = ws?.send(o.toString()) ?: false

    private fun keyMsg(code: String, cmd: String) = JSONObject().put("method", "ms.remote.control").put("params",
        JSONObject().put("Cmd", cmd).put("DataOfCmd", code).put("Option", "false").put("TypeOfRemote", "SendRemoteKey"))

    private val keys = mapOf(
        RemoteKey.POWER to "KEY_POWER", RemoteKey.HOME to "KEY_HOME", RemoteKey.BACK to "KEY_RETURN",
        RemoteKey.MENU to "KEY_MENU", RemoteKey.SETTINGS to "KEY_TOOLS", RemoteKey.SOURCE to "KEY_SOURCE",
        RemoteKey.UP to "KEY_UP", RemoteKey.DOWN to "KEY_DOWN", RemoteKey.LEFT to "KEY_LEFT", RemoteKey.RIGHT to "KEY_RIGHT",
        RemoteKey.OK to "KEY_ENTER", RemoteKey.VOL_UP to "KEY_VOLUP", RemoteKey.VOL_DOWN to "KEY_VOLDOWN", RemoteKey.MUTE to "KEY_MUTE",
        RemoteKey.CH_UP to "KEY_CHUP", RemoteKey.CH_DOWN to "KEY_CHDOWN",
        RemoteKey.PLAY_PAUSE to "KEY_PLAY", // Samsung has no single toggle key; KEY_PLAY/KEY_PAUSE are separate
        RemoteKey.REWIND to "KEY_REWIND", RemoteKey.FAST_FORWARD to "KEY_FF",
    ) + (0..9).associate { RemoteKey.valueOf("NUM$it") to "KEY_$it" }

    override suspend fun send(key: RemoteKey, action: KeyAction) {
        val code = keys[key] ?: return
        sendJson(keyMsg(code, when (action) { KeyAction.CLICK -> "Click"; KeyAction.PRESS -> "Press"; KeyAction.RELEASE -> "Release" }))
    }

    override suspend fun sendText(text: String) {
        sendJson(JSONObject().put("method", "ms.remote.control").put("params", JSONObject()
            .put("Cmd", Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP))
            .put("DataOfCmd", "base64").put("TypeOfRemote", "SendInputString")))
    }

    override suspend fun launchApp(app: KnownApp): LaunchResult {
        val id = AppIds.resolve(platform, app) ?: return LaunchResult.Unsupported("No app id known for ${app.label}")
        val installed = listApps().map { it.id }
        if (installed.isNotEmpty() && id !in installed) return LaunchResult.NotInstalled
        return launchAppId(id)
    }

    override suspend fun launchAppId(appId: String): LaunchResult {
        val ok = sendJson(JSONObject().put("method", "ms.channel.emit").put("params", JSONObject()
            .put("event", "ed.apps.launch").put("to", "host")
            .put("data", JSONObject().put("action_type", "DEEP_LINK").put("appId", appId))))
        return if (ok) LaunchResult.Launched else LaunchResult.Failed("Not connected")
    }

    override suspend fun listApps(): List<AppEntry> {
        val w = CompletableDeferred<List<AppEntry>>().also { appsWaiter = it }
        sendJson(JSONObject().put("method", "ms.channel.emit").put("params",
            JSONObject().put("event", "ed.installedApp.get").put("to", "host")))
        return try { withTimeout(3000) { w.await() } } catch (e: Exception) { emptyList() } // many models never answer
    }

    override suspend fun switchInput(input: HdmiInput): Boolean = false // Only cycling via KEY_SOURCE/KEY_HDMI is reliable.

    override suspend fun powerOn(device: TvDevice) = device.mac?.let { Net.wakeOnLan(it) } ?: false
    override fun powerOnHint() = "On the Samsung TV enable Settings > General > Network > Expert Settings > Power On with Mobile. Works mainly on 2016+ models; the TV's MAC address must be known."
}
