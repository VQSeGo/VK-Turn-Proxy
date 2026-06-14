package com.freeturn.app.domain

import java.net.HttpURLConnection
import java.net.URL

/**
 * Centralized helper for all HTTP(S) requests made by the app.
 *
 * Every connection opened through [openConnection] receives a
 * Chrome-like User-Agent header, preventing rejections from CDN WAFs
 * and reverse-proxy filters that drop default Java / Dalvik UAs.
 *
 * The URL hostname is never replaced with an IP literal — doing so
 * destroys TLS SNI and breaks servers behind XTLS-Reality / Nginx
 * with ssl_preread.
 */
object NetworkUtil {

    internal const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/125.0.0.0 Mobile Safari/537.36"

    /**
     * Open an [HttpURLConnection] to [urlStr], optionally through [proxy].
     *
     * Sets a browser-like User-Agent automatically.  Timeouts are NOT
     * set here — callers must configure [HttpURLConnection.connectTimeout]
     * and [HttpURLConnection.readTimeout] themselves.
     */
    fun openConnection(
        urlStr: String,
        proxy: java.net.Proxy? = null,
    ): HttpURLConnection {
        val url = URL(urlStr)
        val conn = if (proxy != null) {
            url.openConnection(proxy) as HttpURLConnection
        } else {
            url.openConnection() as HttpURLConnection
        }
        conn.setRequestProperty("User-Agent", BROWSER_UA)
        return conn
    }
}
