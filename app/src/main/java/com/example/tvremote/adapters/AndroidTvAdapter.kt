package com.example.tvremote.adapters

import android.content.Context
import android.util.Base64
import com.example.tvremote.core.*
import com.example.tvremote.net.LocalTls
import com.example.tvremote.net.Net
import com.example.tvremote.net.Pb
import com.example.tvremote.net.Pb.find
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLSocket

/**
 * Android TV / Google TV via the "Android TV Remote Service" v2 protocol (what the official Google TV app uses):
 *  - mDNS service  _androidtvremote2._tcp  (control on TCP 6466, pairing on TCP 6467)
 *  - Mutual TLS with a self-signed client cert generated once per phone/TV pairing
 *  - Pairing: 6-digit hex code shown on TV, proven via SHA-256 over both public keys + code
 *  - Messages: length-delimited protobuf, hand-encoded in [Pb]
 *
 * STATUS: written from the public protocol description; NOT compiled or tested against hardware in this build.
 */
class AndroidTvAdapter(@Suppress("unused") private val context: Context) : TvAdapter {
    override val platform = TvPlatform.ANDROID_TV
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state
    override fun features() = Compatibility.base.getValue(platform)

    private var socket: SSLSocket? = null
    private var scope: CoroutineScope? = null
    private val writeLock = Mutex()
    private var device: TvDevice? = null

    // ---------- Pairing ----------

    override suspend fun beginPairing(device: TvDevice): PairingSession {
        val session = PairingSessionImpl(device.host, AtvCrypto.generateP12())
        session.start()
        return session
    }

    private class PairingSessionImpl(val host: String, val p12: String) : PairingSession {
        override val kind = PairingKind.PIN_ON_TV
        private var sock: SSLSocket? = null
        private lateinit var clientKey: RSAPublicKey
        private lateinit var serverKey: RSAPublicKey
        @Volatile private var symbolLength = 6

        private fun outer(vararg payload: ByteArray) =
            Pb.concat(Pb.int(1, 2), Pb.int(2, 200), *payload)

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

        private fun readUntil(field: Int, step: String): List<Pb.Field> {
            val input = sock!!.inputStream
            while (true) {
                val raw = Pb.readFrame(input)
                val f = Pb.parse(raw)
                val status = f.find(2)?.long?.toInt() ?: 200
                if (status != 200) throw PairingDenied("TV rejected pairing at step \"$step\" (status $status). TV reply: ${hex(raw)}")
                if (f.find(field) != null) return f
            }
        }

        /** Pick the encoding the TV says it will display (its output encoding), falling back to hex/6. */
        private fun chooseEncoding(optionReply: List<Pb.Field>): Pair<Int, Int> {
            val inner = runCatching { Pb.parse(optionReply.find(20)!!.data!!) }.getOrNull() ?: return 3 to 6
            fun encs(n: Int) = inner.filter { it.number == n && it.data != null }.map { Pb.parse(it.data!!) }
                .map { (it.find(1)?.long?.toInt() ?: 3) to (it.find(2)?.long?.toInt() ?: 6) }
            val all = encs(2) + encs(1)
            return all.firstOrNull { it.first == 3 } ?: all.firstOrNull() ?: (3 to 6)
        }

        suspend fun start() = withContext(Dispatchers.IO) {
            try {
                val ks = AtvCrypto.load(p12)
                val ctx = LocalTls.sslContext(AtvCrypto.keyManagers(ks))
                val s = (ctx.socketFactory.createSocket(host, 6467) as SSLSocket).also { it.soTimeout = 60_000 }
                s.startHandshake()
                sock = s
                clientKey = AtvCrypto.clientCert(ks).publicKey as RSAPublicKey
                serverKey = s.session.peerCertificates[0].publicKey as RSAPublicKey
                val out = s.outputStream
                // PairingRequest{service_name=1, client_name=2}
                Pb.writeFrame(out, outer(Pb.msg(10, Pb.str(1, "androidtvremote2"), Pb.str(2, "Universal TV Remote"))))
                readUntil(11, "request")
                // PairingOption{input_encodings=1{type=3 HEX, symbol_length=6}, preferred_role=3 (INPUT=1)}
                Pb.writeFrame(out, outer(Pb.msg(20, Pb.msg(1, Pb.int(1, 3), Pb.int(2, 6)), Pb.int(3, 1))))
                val optionReply = readUntil(20, "options")
                val (encType, encLen) = chooseEncoding(optionReply)
                symbolLength = encLen
                // PairingConfiguration{encoding=1{type, symbol_length}, client_role=2 (INPUT=1)}
                Pb.writeFrame(out, outer(Pb.msg(30, Pb.msg(1, Pb.int(1, encType), Pb.int(2, encLen)), Pb.int(2, 1))))
                readUntil(31, "configuration")
                // TV now displays the code.
            } catch (e: IOException) {
                cancel(); throw PairingDenied("Could not reach the TV pairing service: ${e.message}")
            }
        }

        override suspend fun submit(code: String?): String = withContext(Dispatchers.IO) {
            val hex = code?.trim()?.uppercase().orEmpty()
            if (!Regex("[0-9A-F]{$symbolLength}").matches(hex)) throw PairingDenied("Enter the $symbolLength-character code shown on the TV.")
            val codeBytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val md = MessageDigest.getInstance("SHA-256")
            md.update(mod(clientKey)); md.update(exp(clientKey)); md.update(mod(serverKey)); md.update(exp(serverKey))
            md.update(codeBytes, 1, codeBytes.size - 1)
            val secret = md.digest()
            // Local sanity check: first hash byte must equal first code byte, else the code was mistyped.
            if (secret[0] != codeBytes[0]) throw PairingDenied("That code doesn't match. Check the TV screen and try again.")
            try {
                Pb.writeFrame(sock!!.outputStream, outer(Pb.msg(40, Pb.bytes(1, secret))))
                readUntil(41, "secret")
            } catch (e: IOException) {
                throw PairingDenied("The TV closed the connection (pairing denied or timed out).")
            } finally { cancel() }
            p12
        }

        override fun cancel() { runCatching { sock?.close() } }

        private fun strip(b: BigInteger): ByteArray {
            val a = b.toByteArray(); return if (a.size > 1 && a[0] == 0.toByte()) a.copyOfRange(1, a.size) else a
        }
        private fun mod(k: RSAPublicKey) = strip(k.modulus)
        private fun exp(k: RSAPublicKey) = strip(k.publicExponent)
    }

    // ---------- Connection ----------

    override suspend fun connect(device: TvDevice, credentials: String?) {
        disconnect()
        this.device = device
        if (credentials == null) { _state.value = ConnectionState.NeedsPairing; return }
        _state.value = ConnectionState.Connecting
        try {
            withContext(Dispatchers.IO) {
                val ks = AtvCrypto.load(credentials)
                val ctx = LocalTls.sslContext(AtvCrypto.keyManagers(ks))
                val s = ctx.socketFactory.createSocket().also { it.connect(java.net.InetSocketAddress(device.host, 6466), 3000) } as SSLSocket
                s.startHandshake()
                socket = s
            }
        } catch (e: Exception) {
            _state.value = Net.classifyOffline(device.host, listOf(6466, 8009))
            return
        }
        val sc = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scope = it }
        val ready = CompletableDeferred<Unit>()
        sc.launch { readLoop(ready) }
        try {
            withTimeout(5000) { ready.await() }
            _state.value = ConnectionState.Connected
        } catch (e: TimeoutCancellationException) {
            _state.value = ConnectionState.Error("TV did not complete the handshake. Re-pair if this keeps happening.")
        }
    }

    private suspend fun readLoop(ready: CompletableDeferred<Unit>) {
        val s = socket ?: return
        try {
            while (currentCoroutineContext().isActive) {
                val f = Pb.parse(Pb.readFrame(s.inputStream))
                when {
                    f.find(1) != null -> { // remote_configure from TV -> reply with ours
                        val dev = Pb.concat(Pb.str(1, "Android"), Pb.str(2, "Universal TV Remote"), Pb.int(3, 1),
                            Pb.str(4, "1"), Pb.str(5, "com.example.tvremote"), Pb.str(6, "0.1.0"))
                        write(Pb.msg(1, Pb.msg(1, Pb.int(1, 622), Pb.msg(2, dev))))
                    }
                    f.find(2) != null -> { // remote_set_active
                        write(Pb.msg(2, Pb.int(1, 622))); if (!ready.isCompleted) ready.complete(Unit)
                    }
                    f.find(8) != null -> { // ping request -> response with same val1
                        val v = Pb.parse(f.find(8)!!.data!!).find(1)?.long?.toInt() ?: 0
                        write(Pb.msg(9, Pb.int(1, v)))
                    }
                }
            }
        } catch (e: Exception) {
            val d = device
            if (_state.value == ConnectionState.Connected || _state.value == ConnectionState.Connecting) {
                _state.value = if (d != null) Net.classifyOffline(d.host, listOf(6466)) else ConnectionState.Disconnected
            }
            if (!ready.isCompleted) ready.completeExceptionally(e)
        }
    }

    private suspend fun write(payload: ByteArray) = writeLock.withLock {
        withContext(Dispatchers.IO) { socket?.outputStream?.let { Pb.writeFrame(it, payload) } }
    }

    override suspend fun disconnect() {
        scope?.cancel(); scope = null
        runCatching { socket?.close() }; socket = null
        _state.value = ConnectionState.Disconnected
    }

    // ---------- Control ----------

    private val keyCodes = mapOf(
        RemoteKey.POWER to 26, RemoteKey.HOME to 3, RemoteKey.BACK to 4, RemoteKey.MENU to 82,
        RemoteKey.SETTINGS to 176, RemoteKey.SOURCE to 178,
        RemoteKey.UP to 19, RemoteKey.DOWN to 20, RemoteKey.LEFT to 21, RemoteKey.RIGHT to 22, RemoteKey.OK to 23,
        RemoteKey.VOL_UP to 24, RemoteKey.VOL_DOWN to 25, RemoteKey.MUTE to 164,
        RemoteKey.CH_UP to 166, RemoteKey.CH_DOWN to 167,
        RemoteKey.NUM0 to 7, RemoteKey.NUM1 to 8, RemoteKey.NUM2 to 9, RemoteKey.NUM3 to 10, RemoteKey.NUM4 to 11,
        RemoteKey.NUM5 to 12, RemoteKey.NUM6 to 13, RemoteKey.NUM7 to 14, RemoteKey.NUM8 to 15, RemoteKey.NUM9 to 16,
        RemoteKey.PLAY_PAUSE to 85, RemoteKey.NEXT to 87, RemoteKey.PREVIOUS to 88,
        RemoteKey.REWIND to 89, RemoteKey.FAST_FORWARD to 90,
    )

    override suspend fun send(key: RemoteKey, action: KeyAction) {
        val code = keyCodes[key] ?: return
        // direction: START_LONG=1, END_LONG=2, SHORT=3
        val dir = when (action) { KeyAction.PRESS -> 1; KeyAction.RELEASE -> 2; KeyAction.CLICK -> 3 }
        write(Pb.msg(10, Pb.int(1, code), Pb.int(2, dir)))
    }

    /** Fallback: per-character key events (letters, digits, space, a few symbols). Full IME batch edit is not implemented. */
    override suspend fun sendText(text: String) {
        for (c in text.lowercase()) {
            val code = when (c) {
                in 'a'..'z' -> 29 + (c - 'a'); in '0'..'9' -> 7 + (c - '0')
                ' ' -> 62; '.' -> 56; ',' -> 55; '\n' -> 66; else -> null
            } ?: continue
            write(Pb.msg(10, Pb.int(1, code), Pb.int(2, 3)))
            delay(25)
        }
    }

    override suspend fun moveCursor(dx: Int, dy: Int) {
        // Android TV has no pointer channel: translate swipes into D-pad steps.
        val k = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) (if (dx > 0) RemoteKey.RIGHT else RemoteKey.LEFT)
        else (if (dy > 0) RemoteKey.DOWN else RemoteKey.UP)
        send(k)
    }

    override suspend fun launchApp(app: KnownApp): LaunchResult {
        val link = AppIds.resolve(platform, app) ?: return LaunchResult.Unsupported("No link known for ${app.label}")
        return launchAppId(link)
    }

    /**
     * Launch via deep link. Android TV does not report whether the app is installed; if no app handles the link
     * the TV usually ignores it silently, so we can only report "sent".
     */
    override suspend fun launchAppId(appId: String): LaunchResult = try {
        write(Pb.msg(90, Pb.str(1, appId))); LaunchResult.Launched
    } catch (e: Exception) { LaunchResult.Failed(e.message ?: "send failed") }

    override suspend fun listApps(): List<AppEntry> = emptyList() // Remote v2 does not expose installed apps.
    override suspend fun switchInput(input: HdmiInput): Boolean = false // Only the generic SOURCE key (TV_INPUT) is available.

    override suspend fun powerOn(device: TvDevice): Boolean {
        if (_state.value == ConnectionState.Connected) { send(RemoteKey.POWER); return true }
        return device.mac?.let { Net.wakeOnLan(it) } ?: false
    }
    override fun powerOnHint() =
        "Enable Wake-on-LAN / \"Wake on Wi-Fi\" in the TV's network or power settings (name and availability vary by brand). Many Android TVs only support this on Ethernet."
}

internal object AtvCrypto {
    private const val PW = "tvremote"

    fun generateP12(): String {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=universal-tv-remote")
        val now = Date()
        val builder = JcaX509v3CertificateBuilder(name, BigInteger(64, SecureRandom()), now, Date(now.time + 20L * 365 * 24 * 3600 * 1000), name, kp.public)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private)))
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry("client", kp.private, PW.toCharArray(), arrayOf(cert))
        val bos = ByteArrayOutputStream(); ks.store(bos, PW.toCharArray())
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    fun load(b64: String): KeyStore =
        KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(Base64.decode(b64, Base64.NO_WRAP)), PW.toCharArray()) }

    fun keyManagers(ks: KeyStore): Array<KeyManager> =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, PW.toCharArray()) }.keyManagers

    fun clientCert(ks: KeyStore) = ks.getCertificate("client") as java.security.cert.X509Certificate
}
