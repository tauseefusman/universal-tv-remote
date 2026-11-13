package com.example.tvremote.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tvremote.adapters.AndroidTvAdapter
import com.example.tvremote.adapters.RokuAdapter
import com.example.tvremote.adapters.TizenAdapter
import com.example.tvremote.adapters.WebOsAdapter
import com.example.tvremote.core.*
import com.example.tvremote.data.TvStore
import com.example.tvremote.discovery.Discovery
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

sealed interface PairingUi {
    data object Idle : PairingUi
    data class Starting(val tv: TvDevice) : PairingUi
    data class AwaitCode(val tv: TvDevice) : PairingUi
    data class AwaitApproval(val tv: TvDevice) : PairingUi
    data class Failed(val tv: TvDevice, val message: String) : PairingUi
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = TvStore(app)
    private val discovery = Discovery(app)
    private val adapters: Map<TvPlatform, TvAdapter> = mapOf(
        TvPlatform.ANDROID_TV to AndroidTvAdapter(app), TvPlatform.ROKU to RokuAdapter(),
        TvPlatform.WEBOS to WebOsAdapter(), TvPlatform.TIZEN to TizenAdapter(),
    )

    val tvs = store.tvs
    private val _selected = MutableStateFlow<TvDevice?>(null)
    val selected: StateFlow<TvDevice?> = _selected
    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection
    private val _features = MutableStateFlow<Set<Feature>>(emptySet())
    val features: StateFlow<Set<Feature>> = _features
    private val _discovered = MutableStateFlow<List<TvDevice>>(emptyList())
    val discovered: StateFlow<List<TvDevice>> = _discovered
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private val _pairing = MutableStateFlow<PairingUi>(PairingUi.Idle)
    val pairing: StateFlow<PairingUi> = _pairing
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps
    private val _favorites = MutableStateFlow<List<String>>(emptyList())
    val favorites: StateFlow<List<String>> = _favorites
    private val _haptics = MutableStateFlow(store.haptics)
    val haptics: StateFlow<Boolean> = _haptics
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private var active: TvAdapter? = null
    private var stateJob: Job? = null
    private var retryJob: Job? = null
    private var pairingSession: PairingSession? = null
    private val holdJobs = mutableMapOf<RemoteKey, Job>()

    init {
        val last = store.lastTvId?.let { id -> store.tvs.value.firstOrNull { it.id == id } } ?: store.tvs.value.firstOrNull()
        if (last != null) selectTv(last)
    }

    private fun say(msg: String) { _messages.tryEmit(msg) }

    // ---------- Selection, connection, auto-reconnect ----------

    fun selectTv(tv: TvDevice) {
        viewModelScope.launch {
            retryJob?.cancel(); stateJob?.cancel()
            active?.takeIf { it !== adapters[tv.platform] }?.disconnect()
            val ad = adapters.getValue(tv.platform); active = ad
            _selected.value = tv; store.lastTvId = tv.id
            _favorites.value = store.favorites(tv.id); _apps.value = emptyList()
            stateJob = launch { ad.state.collect { s -> _connection.value = s; _features.value = ad.features() } }
            connectNow(tv)
            retryJob = launch { retryLoop(tv) }
        }
    }

    private suspend fun connectNow(tv: TvDevice) {
        val ad = adapters.getValue(tv.platform)
        ad.connect(tv, store.credentials(tv.id))
        if (_connection.value == ConnectionState.Connected) _features.value = ad.features()
    }

    /** Reconnect automatically whenever the TV becomes available again; follow it if its IP changed. */
    private suspend fun retryLoop(tv: TvDevice) {
        var current = tv
        while (currentCoroutineContext().isActive) {
            delay(8000)
            when (_connection.value) {
                ConnectionState.Connected, ConnectionState.Connecting, ConnectionState.NeedsPairing -> {}
                ConnectionState.Unreachable -> {
                    val moved = runCatching { discovery.discover(3000).toList() }.getOrDefault(emptyList())
                        .firstOrNull { it.id == current.id && it.host != current.host }
                    if (moved != null) { store.updateHost(current.id, moved.host); current = current.copy(host = moved.host); _selected.value = current }
                    connectNow(current)
                }
                else -> connectNow(current)
            }
        }
    }

    // ---------- Discovery & manual add ----------

    fun scan() {
        if (_scanning.value) return
        viewModelScope.launch {
            _scanning.value = true; _discovered.value = emptyList()
            runCatching { discovery.discover().collect { d -> if (_discovered.value.none { it.id == d.id }) _discovered.value += d } }
            _scanning.value = false
            if (_discovered.value.isEmpty()) say("No TVs found. Make sure the phone and TV are on the same Wi-Fi, or add the TV by IP address.")
        }
    }

    fun addManual(host: String) {
        viewModelScope.launch {
            val tv = discovery.probe(host.trim())
            if (tv == null) say("No supported TV answered at $host. Check the IP address and that the TV is on.") else startPairing(tv)
        }
    }

    // ---------- Pairing ----------

    fun startPairing(tv: TvDevice) {
        viewModelScope.launch {
            pairingSession?.cancel(); pairingSession = null
            delay(600) // let the TV drop any previous pairing session
            val ad = adapters.getValue(tv.platform)
            _pairing.value = PairingUi.Starting(tv)
            try {
                val session = ad.beginPairing(tv); pairingSession = session
                when (session.kind) {
                    PairingKind.NONE -> finishPairing(tv, session.submit(null))
                    PairingKind.PIN_ON_TV -> _pairing.value = PairingUi.AwaitCode(tv)
                    PairingKind.APPROVE_ON_TV -> { _pairing.value = PairingUi.AwaitApproval(tv); finishPairing(tv, session.submit(null)) }
                }
            } catch (e: PairingDenied) {
                _pairing.value = PairingUi.Failed(tv, e.message ?: "Pairing was denied.")
            } catch (e: Exception) {
                _pairing.value = PairingUi.Failed(tv, "Couldn't reach the TV: ${e.message}")
            }
        }
    }

    fun submitCode(code: String) {
        val tv = (_pairing.value as? PairingUi.AwaitCode)?.tv ?: return
        viewModelScope.launch {
            try { finishPairing(tv, pairingSession!!.submit(code)) }
            catch (e: PairingDenied) {
                // Wrong code: let the user retry without restarting. Denied/timeout: session is dead, start over.
                _pairing.value = if (e.message?.contains("doesn't match") == true) PairingUi.AwaitCode(tv).also { say(e.message!!) }
                else PairingUi.Failed(tv, e.message ?: "Pairing was denied.")
            }
        }
    }

    private fun finishPairing(tv: TvDevice, credentials: String) {
        val saved = tv.copy(pairedBefore = true)
        store.upsert(saved)
        if (credentials.isNotEmpty()) store.saveCredentials(saved.id, credentials)
        _pairing.value = PairingUi.Idle; pairingSession = null
        say("Paired with ${saved.name}")
        selectTv(saved)
    }

    fun cancelPairing() { pairingSession?.cancel(); pairingSession = null; _pairing.value = PairingUi.Idle }

    fun renameTv(id: String, name: String) { store.rename(id, name); if (_selected.value?.id == id) _selected.value = _selected.value?.copy(name = name) }
    fun forgetTv(id: String) {
        viewModelScope.launch {
            if (_selected.value?.id == id) { retryJob?.cancel(); active?.disconnect(); _selected.value = null }
            store.forget(id)
            store.tvs.value.firstOrNull()?.let { selectTv(it) }
        }
    }

    // ---------- Remote control ----------

    fun press(key: RemoteKey) {
        val tv = _selected.value ?: return
        viewModelScope.launch {
            if (key == RemoteKey.POWER && _connection.value.let { it == ConnectionState.Sleeping || it == ConnectionState.Unreachable }) { wake(); return@launch }
            runCatching { active?.send(key) }.onFailure { say("Couldn't send that to ${tv.name}.") }
        }
    }

    /** Press-and-hold: native press/release where the TV supports it, otherwise repeat clicks. */
    fun holdStart(key: RemoteKey) {
        val ad = active ?: return
        holdJobs.remove(key)?.cancel()
        holdJobs[key] = viewModelScope.launch {
            if (Feature.HOLD_KEYS in _features.value) {
                runCatching { ad.send(key, KeyAction.PRESS) }
                try { awaitCancellation() } finally { withContext(NonCancellable) { runCatching { ad.send(key, KeyAction.RELEASE) } } }
            } else {
                runCatching { ad.send(key) }; delay(350)
                while (isActive) { runCatching { ad.send(key) }; delay(130) }
            }
        }
    }
    fun holdEnd(key: RemoteKey) { holdJobs.remove(key)?.cancel() }

    fun sendText(text: String) { viewModelScope.launch { runCatching { active?.sendText(text) } } }
    fun swipe(dx: Int, dy: Int) { viewModelScope.launch { runCatching { active?.moveCursor(dx, dy) } } }

    fun switchInput(i: HdmiInput) {
        viewModelScope.launch {
            if (active?.switchInput(i) != true) say("This TV can't jump straight to ${i.label}. Use Source to cycle inputs.")
        }
    }

    fun wake() {
        val tv = _selected.value ?: return
        viewModelScope.launch {
            val ad = active ?: return@launch
            val sent = ad.powerOn(tv)
            when {
                !sent -> say("This TV can't be woken over Wi-Fi from here." + (ad.powerOnHint()?.let { " $it" } ?: ""))
                _connection.value == ConnectionState.Unreachable -> say("Wake signal sent, but the TV isn't visible on the network. ${ad.powerOnHint() ?: ""}")
                else -> say("Wake signal sent to ${tv.name}.")
            }
        }
    }
    fun powerOnHint() = active?.powerOnHint()

    // ---------- Apps ----------

    fun launch(app: KnownApp) {
        viewModelScope.launch { report(app.label, active?.launchApp(app) ?: LaunchResult.Failed("No TV selected")) }
    }

    fun launchEntry(entry: String) {
        when {
            entry.startsWith("known:") -> launch(KnownApp.valueOf(entry.removePrefix("known:")))
            else -> { val (id, name) = parseEntry(entry); viewModelScope.launch { report(name, active?.launchAppId(id) ?: LaunchResult.Failed("No TV selected")) } }
        }
    }

    private fun report(name: String, r: LaunchResult) {
        val tv = _selected.value?.name ?: "the TV"
        say(when (r) {
            LaunchResult.Launched -> "Opening $name on $tv"
            LaunchResult.NotInstalled -> "$name isn't installed on $tv. Install it from the TV's app store, then try again."
            is LaunchResult.Unsupported -> "$tv can't launch $name remotely: ${r.reason}"
            is LaunchResult.Failed -> "Couldn't open $name: ${r.reason}"
        })
    }

    fun refreshApps() { viewModelScope.launch { _apps.value = runCatching { active?.listApps() }.getOrNull().orEmpty().sortedBy { it.name.lowercase() } } }

    fun entryLabel(entry: String) = if (entry.startsWith("known:")) KnownApp.valueOf(entry.removePrefix("known:")).label else parseEntry(entry).second
    private fun parseEntry(e: String): Pair<String, String> = e.removePrefix("app:").split("|", limit = 2).let { parts -> parts[0] to parts.getOrElse(1) { parts[0] } }

    fun pin(app: AppEntry) = mutateFavs { if ("app:${app.id}|${app.name}" !in it) it + "app:${app.id}|${app.name}" else it }
    fun unpin(entry: String) = mutateFavs { it - entry }
    fun move(entry: String, delta: Int) = mutateFavs { list ->
        val i = list.indexOf(entry); val j = i + delta
        if (i < 0 || j !in list.indices) list else list.toMutableList().also { it.removeAt(i); it.add(j, entry) }
    }
    private fun mutateFavs(f: (List<String>) -> List<String>) {
        val id = _selected.value?.id ?: return
        val n = f(_favorites.value); _favorites.value = n; store.setFavorites(id, n)
    }

    fun setHaptics(on: Boolean) { store.haptics = on; _haptics.value = on }

    override fun onCleared() { runBlocking { active?.disconnect() } }
}
