package com.motya.mvpn.data

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL

object SubscriptionUpdater {
    fun fetch(url: String): List<VlessProfile> {
        val text = (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 15_000
            readTimeout = 20_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "MVPN/0.1")
            inputStream.bufferedReader().use { it.readText() }
        }
        return decode(text)
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("vless://", ignoreCase = true) }
            .mapNotNull { runCatching { VlessParser.parse(it) }.getOrNull() }
            .toList()
    }

    private fun decode(text: String): String {
        val clean = text.trim()
        if (clean.contains("vless://", ignoreCase = true)) return clean
        return runCatching { String(Base64.decode(clean, Base64.DEFAULT)) }.getOrDefault(clean)
    }
}
