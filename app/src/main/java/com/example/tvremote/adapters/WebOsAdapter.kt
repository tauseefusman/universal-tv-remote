package com.example.tvremote.adapters

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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * LG webOS via SSAP over WebSocket (wss 3001, falling back to ws 3000). Pairing: the TV shows an
 * "Allow this device?" prompt; on accept it returns a client-key we store and reuse. Button presses use the
 * pointer-input socket exposed by the TV (ssap://com.webos.service.networkinput/getPointerInputSocket).
 * Power-on over Wi-Fi uses Wake-on-LAN: LG Connect Apps / "Turn on via Wi-Fi" must be enabled on the TV.
 *
 * STATUS: written from public community documentation of SSAP; NOT compiled or tested against hardware in this build.
 */
class WebOsAdapter : TvAdapter {
    override val platform = TvPlatform.WEBOS
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state
    override fun features() = Compatibility.base.getValue(platform)

    private val client = LocalTls.okHttp()
    private var ws: WebSocket? = null
    private var pointer: WebSocket? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val ids = AtomicInteger()
    private var device: TvDevice? = null

    private val manifest = JSONObject().put("manifestVersion", 1).put("permissions", JSONArray(listOf(
        "LAUNCH", "LAUNCH_WEBAPP", "CONTROL_AUDIO", "CONTROL_POWER", "CONTROL_INPUT_TV", "CONTROL_INPUT_TEXT",
        "CONTROL_MOUSE_AND_KEYBOARD", "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_PLAYBACK",
        "READ_INSTALLED_APPS", "READ_INPUT_DEVICE_LIST", "READ_RUNNING_APPS", "READ_POWER_STATE",
    )))

    override suspend fun beginPairing(device: TvDevice): PairingSession = object : PairingSession {
        override val kind = PairingKind.APPROVE_ON_TV
        override suspend fun submit(code: String?): String =
            // Blocks until the user taps "Yes" on the TV (or it times out / is denied).
            open(device, null, waitMs = 60_000)
        override fun cancel() { ws?.cancel() }
    }

    override suspend fun connect(device: TvDevice, credentials: String?) {
        disconnect(); this.device = device
        if (credentials == null) { _state.value = ConnectionState.NeedsPairing; return }
        _state.value = ConnectionState.Connecting
        try { open(device, credentials, waitMs = 8_000); _state.value = ConnectionState.Connected }
        catch (e: PairingDenied) { _state.value = ConnectionState.NeedsPairing }
        catch (e: Exception) { _state.value = Net.classifyOffline(device.host, listOf(3000, 3001)) }
    }

    /** Opens the SSAP socket and registers. Returns the client key. */
    private suspend fun open(device: TvDevice, key: String?, waitMs: Long): String {
        var lastErr: Exception? = null
        for (url in listOf("wss://${device.host}:3001", "ws://${device.host}:3000")) {
            try { return openAt(url, key, waitMs) } catch (e: PairingDenied) { throw e } catch (e: Exception) { lastErr = e }
        }
        throw lastErr ?: IllegalStateException("unreachable")
    }

    private suspend fun openAt(url: String, key: String?, waitMs: Long): String {
        val registered = CompletableDeferred<String>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val payload = JSONObject().put("forcePairing", false).put("pairingType", "PROMPT").put("manifest", manifest)
                key?.let { payload.put("client-key", it) }
                webSocket.send(JSONObject().put("type", "register").put("id", "register_0").put("payload", payload).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = JSONObject(text)
                when (m.optString("type")) {
                    "registered" -> registered.complete(m.getJSONObject("payload").getString("client-key"))
                    "error" -> if (m.optString("id").startsWith("register"))
                        registered.completeExceptionally(PairingDenied("The TV denied the connection request."))
                    else -> pending.remove(m.optString("id"))?.complete(m)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                registered.completeExceptionally(t)
                if (_state.value == ConnectionState.Connected) {
                    val d = device
                    CoroutineScope(Dispatchers.IO).launch {
                        _state.value = if (d != null) Net.classifyOffline(d.host, listOf(3000, 3001)) else ConnectionState.Disconnected
                    }
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (_state.value == ConnectionState.Connected) _state.value = ConnectionState.Disconnected
            }
        }
        val sock = client.newWebSocket(Request.Builder().url(url).build(), listener)
        try {
            val clientKey = withTimeout(waitMs) { registered.await() }
            ws = sock
            return clientKey
        } catch (e: TimeoutCancellationException) {
            sock.cancel(); throw PairingDenied("No response from the TV. Did you accept the prompt on screen?")
        } catch (e: Exception) { sock.cancel(); throw e }
    }

    private suspend fun request(uri: String, payload: JSONObject? = null, timeoutMs: Long = 4000): JSONObject {
        val id = "r_${ids.incrementAndGet()}"
        val d = CompletableDeferred<JSONObject>().also { pending[id] = it }
        val msg = JSONObject().put("type", "request").put("id", id).put("uri", uri)
        payload?.let { msg.put("payload", it) }
        ws?.send(msg.toString()) ?: throw IllegalStateException("not connected")
        return try { withTimeout(timeoutMs) { d.await() } } finally { pending.remove(id) }
    }

    private suspend fun ensurePointer(): WebSocket? {
        pointer?.let { return it }
        val path = runCatching {
            request("ssap://com.webos.service.networkinput/getPointerInputSocket").getJSONObject("payload").getString("socketPath")
        }.getOrNull() ?: return null
        val s = client.newWebSocket(Request.Builder().url(path).build(), object : WebSocketListener() {
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { pointer = null }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { pointer = null }
        })
        pointer = s; return s
    }

    override suspend fun disconnect() {
        runCatching { ws?.close(1000, null) }; runCatching { pointer?.close(1000, null) }
        ws = null; pointer = null; pending.clear(); _state.value = ConnectionState.Disconnected
    }

    private val buttons = mapOf(
        RemoteKey.HOME to "HOME", RemoteKey.BACK to "BACK", RemoteKey.MENU to "MENU", RemoteKey.SETTINGS to "MENU",
        RemoteKey.UP to "UP", RemoteKey.DOWN to "DOWN", RemoteKey.LEFT to "LEFT", RemoteKey.RIGHT to "RIGHT", RemoteKey.OK to "ENTER",
        RemoteKey.VOL_UP to "VOLUMEUP", RemoteKey.VOL_DOWN to "VOLUMEDOWN", RemoteKey.MUTE to "MUTE",
        RemoteKey.CH_UP to "CHANNELUP", RemoteKey.CH_DOWN to "CHANNELDOWN",
        RemoteKey.PLAY_PAUSE to "PLAY", RemoteKey.REWIND to "REWIND", RemoteKey.FAST_FORWARD to "FASTFORWARD",
    ) + (0..9).associate { RemoteKey.valueOf("NUM$it") to "$it" }

    override suspend fun send(key: RemoteKey, action: KeyAction) {
        if (action == KeyAction.RELEASE) return // webOS buttons are single events; repeat is driven by the UI
        when (key) {
            RemoteKey.POWER -> { runCatching { request("ssap://system/turnOff") }; return }
            RemoteKey.SOURCE -> { launchAppId("com.webos.app.inputpicker"); return }
            else -> {}
        }
        val name = buttons[key] ?: return
        ensurePointer()?.send("type:button\nname:$name\n\n")
    }

    override suspend fun sendText(text: String) {
        runCatching { request("ssap://com.webos.service.ime/insertText", JSONObject().put("text", text).put("replace", 0)) }
    }

    override suspend fun moveCursor(dx: Int, dy: Int) {
        ensurePointer()?.send("type:move\ndx:$dx\ndy:$dy\ndown:0\n\n")
    }

    override suspend fun launchApp(app: KnownApp): LaunchResult {
        val id = AppIds.resolve(platform, app) ?: return LaunchResult.Unsupported("No app id known for ${app.label}")
        val installed = listApps().map { it.id }
        if (installed.isNotEmpty() && id !in installed) return LaunchResult.NotInstalled
        return launchAppId(id)
    }

    override suspend fun launchAppId(appId: String): LaunchResult = try {
        val r = request("ssap://system.launcher/launch", JSONObject().put("id", appId))
        if (r.optString("type") == "error") LaunchResult.NotInstalled else LaunchResult.Launched
    } catch (e: Exception) { LaunchResult.Failed(e.message ?: "launch failed") }

    override suspend fun listApps(): List<AppEntry> = try {
        val pts = request("ssap://com.webos.applicationManager/listLaunchPoints").getJSONObject("payload").getJSONArray("launchPoints")
        (0 until pts.length()).map { pts.getJSONObject(it) }.map { AppEntry(it.getString("id"), it.optString("title", it.getString("id"))) }
    } catch (e: Exception) { emptyList() }

    override suspend fun switchInput(input: HdmiInput): Boolean = try {
        val id = "HDMI_" + input.name.last()
        request("ssap://tv/switchInput", JSONObject().put("inputId", id)).optString("type") != "error"
    } catch (e: Exception) { false }

    override suspend fun powerOn(device: TvDevice) = device.mac?.let { Net.wakeOnLan(it) } ?: false
    override fun powerOnHint() = "On the LG TV enable Settings > General > Devices > External Devices > TV On With Mobile > Turn on via Wi-Fi (path varies by webOS version). The TV's MAC address must be known."
}
