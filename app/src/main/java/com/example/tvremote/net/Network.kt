package com.example.tvremote.net

import com.example.tvremote.core.ConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

object Net {
    /** TCP connect probe. */
    suspend fun portOpen(host: String, port: Int, timeoutMs: Int = 1500): Boolean = withContext(Dispatchers.IO) {
        runCatching { Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true } }.getOrDefault(false)
    }

    /**
     * Distinguish "asleep" from "gone". Heuristic: if any known control/service port answers, or the host
     * responds to ICMP/echo, the device is on the network but its control channel is off => Sleeping.
     * Not reliable on every TV (some drop off the network entirely in deep standby).
     */
    suspend fun classifyOffline(host: String, probePorts: List<Int>): ConnectionState = withContext(Dispatchers.IO) {
        val reachable = runCatching { InetAddress.getByName(host).isReachable(1200) }.getOrDefault(false)
        val anyPort = probePorts.any { portOpen(host, it, 600) }
        if (reachable || anyPort) ConnectionState.Sleeping else ConnectionState.Unreachable
    }

    /** Wake-on-LAN magic packet. Only works if the TV's "wake on LAN/Wi-Fi" setting is enabled. */
    suspend fun wakeOnLan(mac: String, broadcast: String = "255.255.255.255"): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val macBytes = mac.split(":", "-").map { it.toInt(16).toByte() }.toByteArray()
            require(macBytes.size == 6)
            val payload = ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { macBytes[it % 6] }
            DatagramSocket().use { s ->
                s.broadcast = true
                repeat(3) { s.send(DatagramPacket(payload, payload.size, InetAddress.getByName(broadcast), 9)) }
            }
            true
        }.getOrDefault(false)
    }
}
