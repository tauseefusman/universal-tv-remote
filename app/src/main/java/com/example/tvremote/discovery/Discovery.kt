package com.example.tvremote.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.example.tvremote.core.TvDevice
import com.example.tvremote.core.TvPlatform
import com.example.tvremote.net.LocalTls
import com.example.tvremote.net.Net
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import okhttp3.Request
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * Local-only discovery:
 *  - Android TV / Google TV: mDNS `_androidtvremote2._tcp`
 *  - Roku, LG webOS, Samsung: SSDP M-SEARCH, then a quick HTTP lookup for name/model/MAC where the TV exposes it.
 * Everything stays on the LAN; nothing is sent to a cloud service.
 */
class Discovery(private val context: Context) {
    private val http = LocalTls.plainHttp()

    fun discover(durationMs: Long = 6000): Flow<TvDevice> = channelFlow {
        val seen = ConcurrentHashMap.newKeySet<String>()
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("tvremote").apply { setReferenceCounted(false); acquire() }

        // --- mDNS for Android TV ---
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val discListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                launch { resolveWithRetry(nsd, info)?.let { r ->
                    val host = r.host?.hostAddress ?: return@let
                    val id = "atv:${r.serviceName}"
                    if (seen.add(id)) trySend(TvDevice(id, r.serviceName, TvPlatform.ANDROID_TV, host, 6466))
                } }
            }
        }
        runCatching { nsd.discoverServices("_androidtvremote2._tcp.", NsdManager.PROTOCOL_DNS_SD, discListener) }

        // --- SSDP for Roku / webOS / Samsung ---
        launch(Dispatchers.IO) {
            val targets = listOf("roku:ecp", "urn:lge-com:service:webos-second-screen:1",
                "urn:samsung.com:device:RemoteControlReceiver:1", "ssdp:all")
            DatagramSocket().use { sock ->
                sock.soTimeout = 700
                repeat(2) {
                    targets.forEach { st ->
                        val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n".toByteArray()
                        runCatching { sock.send(DatagramPacket(msg, msg.size, InetAddress.getByName("239.255.255.250"), 1900)) }
                    }
                }
                val end = System.currentTimeMillis() + durationMs
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end && isActive) {
                    try {
                        val p = DatagramPacket(buf, buf.size); sock.receive(p)
                        val text = String(p.data, 0, p.length)
                        val host = p.address.hostAddress ?: continue
                        val platform = when {
                            text.contains("roku", true) -> TvPlatform.ROKU
                            text.contains("webos", true) || text.contains("lge", true) -> TvPlatform.WEBOS
                            text.contains("samsung", true) -> TvPlatform.TIZEN
                            else -> null
                        } ?: continue
                        if (seen.add("host:$host")) launch { probeAs(host, platform)?.let { send(it) } }
                    } catch (_: SocketTimeoutException) { }
                }
            }
        }

        // Stop after the window closes.
        launch { delay(durationMs + 500); close() }
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(discListener) }
            runCatching { lock.release() }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveWithRetry(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? {
        repeat(4) {
            val d = CompletableDeferred<NsdServiceInfo?>()
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) { d.complete(null) }
                override fun onServiceResolved(i: NsdServiceInfo) { d.complete(i) }
            })
            d.await()?.let { return it }
            delay(400)
        }
        return null
    }

    /** Manual IP entry: try each platform's well-known control port and identify the TV. */
    suspend fun probe(host: String): TvDevice? = coroutineScope {
        val roku = async { if (Net.portOpen(host, 8060)) probeAs(host, TvPlatform.ROKU) else null }
        val samsung = async { if (Net.portOpen(host, 8001)) probeAs(host, TvPlatform.TIZEN) else null }
        val lg = async { if (Net.portOpen(host, 3001) || Net.portOpen(host, 3000)) probeAs(host, TvPlatform.WEBOS) else null }
        val atv = async { if (Net.portOpen(host, 6466)) TvDevice("atv:$host", "Android TV ($host)", TvPlatform.ANDROID_TV, host, 6466) else null }
        listOf(roku, samsung, lg, atv).awaitAll().firstOrNull { it != null }
    }

    private suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        runCatching { http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.string() else null } }.getOrNull()
    }

    private suspend fun probeAs(host: String, platform: TvPlatform): TvDevice? = when (platform) {
        TvPlatform.ROKU -> fetch("http://$host:8060/query/device-info")?.let { xml ->
            fun t(n: String) = Regex("<$n>([^<]*)</$n>").find(xml)?.groupValues?.get(1)
            TvDevice(
                id = "roku:${t("serial-number") ?: host}", name = t("user-device-name") ?: t("friendly-device-name") ?: "Roku",
                platform = TvPlatform.ROKU, host = host, port = 8060, model = t("model-name"), firmware = t("software-version"),
                mac = (t("wifi-mac") ?: t("ethernet-mac")),
            )
        }
        TvPlatform.TIZEN -> fetch("http://$host:8001/api/v2/")?.let { body ->
            val j = runCatching { JSONObject(body) }.getOrNull(); val d = j?.optJSONObject("device")
            TvDevice(
                id = "tizen:${d?.optString("id").orEmpty().ifBlank { host }}", name = d?.optString("name").orEmpty().ifBlank { "Samsung TV" },
                platform = TvPlatform.TIZEN, host = host, port = 8002, model = d?.optString("modelName"),
                firmware = j?.optString("version"), mac = d?.optString("wifiMac")?.takeIf { it.isNotBlank() },
            )
        }
        TvPlatform.WEBOS -> TvDevice("webos:$host", "LG webOS TV ($host)", TvPlatform.WEBOS, host, 3001)
        TvPlatform.ANDROID_TV -> null
    }
}
