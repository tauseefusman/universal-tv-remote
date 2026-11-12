package com.example.tvremote.net

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TVs present self-signed certificates on the LAN. This trust-all manager is ONLY used for
 * connections to a user-selected TV host on the local network, never for internet endpoints.
 * Android TV pairing additionally authenticates the peer via the on-screen PIN (which hashes both certs).
 */
object LocalTls {
    val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun sslContext(keyManagers: Array<javax.net.ssl.KeyManager>? = null): SSLContext =
        SSLContext.getInstance("TLS").apply { init(keyManagers, arrayOf<TrustManager>(trustAll), SecureRandom()) }

    fun okHttp(): OkHttpClient {
        val ctx = sslContext()
        return OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, trustAll)
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    /** Plain client for cleartext HTTP (Roku ECP, UPnP descriptions). */
    fun plainHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()
}
