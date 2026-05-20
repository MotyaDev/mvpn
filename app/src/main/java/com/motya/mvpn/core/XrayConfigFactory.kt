package com.motya.mvpn.core

import com.motya.mvpn.data.VlessProfile
import org.json.JSONArray
import org.json.JSONObject

object XrayConfigFactory {
    fun build(profile: VlessProfile): String {
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vless")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", profile.host)
                            .put("port", profile.port)
                            .put(
                                "users",
                                JSONArray().put(
                                    JSONObject()
                                        .put("id", profile.id)
                                        .put("encryption", profile.params["encryption"] ?: "none")
                                        .put("level", 8)
                                        .apply { putIfNotBlank("flow", profile.params["flow"]) },
                                ),
                            ),
                    ),
                ),
            )
            .put("streamSettings", streamSettings(profile))
            .put("mux", JSONObject().put("enabled", false))

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("stats", JSONObject())
            .put(
                "policy",
                JSONObject()
                    .put("levels", JSONObject().put("8", JSONObject().put("handshake", 4).put("connIdle", 300)))
                    .put("system", JSONObject().put("statsOutboundUplink", true).put("statsOutboundDownlink", true)),
            )
            .put(
                "inbounds",
                JSONArray().put(
                    JSONObject()
                        .put("tag", "tun")
                        .put("protocol", "tun")
                        .put(
                            "settings",
                            JSONObject()
                                .put("name", "mvpn0")
                                .put("MTU", 1500)
                                .put("mtu", 1500)
                                .put("userLevel", 8),
                        )
                        .put("sniffing", sniffing()),
                ),
            )
            .put("outbounds", JSONArray().put(outbound).put(freedom()).put(block()))
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray()))
            .put("dns", JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8")))
            .toString()
    }

    fun buildPing(profile: VlessProfile): String = JSONObject()
        .put("log", JSONObject().put("loglevel", "warning"))
        .put("outbounds", JSONArray().put(vlessOutbound(profile)))
        .toString()

    private fun vlessOutbound(profile: VlessProfile): JSONObject = JSONObject()
        .put("tag", "proxy")
        .put("protocol", "vless")
        .put(
            "settings",
            JSONObject().put(
                "vnext",
                JSONArray().put(
                    JSONObject()
                        .put("address", profile.host)
                        .put("port", profile.port)
                        .put(
                            "users",
                            JSONArray().put(
                                JSONObject()
                                    .put("id", profile.id)
                                    .put("encryption", profile.params["encryption"] ?: "none")
                                    .put("level", 8)
                                    .apply { putIfNotBlank("flow", profile.params["flow"]) },
                            ),
                        ),
                ),
            ),
        )
        .put("streamSettings", streamSettings(profile))
        .put("mux", JSONObject().put("enabled", false))

    private fun streamSettings(profile: VlessProfile): JSONObject {
        val params = profile.params
        val network = params["type"].orEmpty().lowercase().ifBlank { "tcp" }
        val security = params["security"].orEmpty().lowercase().ifBlank { "none" }
        val json = JSONObject().put("network", network).put("security", security)

        if (security == "tls") {
            json.put(
                "tlsSettings",
                JSONObject()
                    .put("serverName", params["sni"] ?: profile.host)
                    .put("allowInsecure", params["allowInsecure"] == "1")
                    .put("alpn", splitArray(params["alpn"]))
                    .apply { putIfNotBlank("fingerprint", params["fp"]) },
            )
        }
        if (security == "reality") {
            json.put(
                "realitySettings",
                JSONObject()
                    .put("serverName", params["sni"] ?: profile.host)
                    .put("fingerprint", params["fp"] ?: "chrome")
                    .put("publicKey", params["pbk"].orEmpty())
                    .put("shortId", params["sid"].orEmpty())
                    .put("spiderX", params["spx"].orEmpty().ifBlank { "/" }),
            )
        }
        when (network) {
            "ws" -> json.put(
                "wsSettings",
                JSONObject()
                    .put("path", params["path"].orEmpty().ifBlank { "/" })
                    .put("headers", JSONObject().put("Host", params["host"] ?: params["sni"] ?: profile.host)),
            )
            "grpc" -> json.put("grpcSettings", JSONObject().put("serviceName", params["serviceName"].orEmpty()))
            "httpupgrade" -> json.put(
                "httpupgradeSettings",
                JSONObject()
                    .put("path", params["path"].orEmpty().ifBlank { "/" })
                    .put("host", params["host"] ?: params["sni"] ?: profile.host),
            )
            "xhttp", "splithttp" -> json.put(
                "xhttpSettings",
                JSONObject()
                    .put("path", params["path"].orEmpty().ifBlank { "/" })
                    .put("host", params["host"] ?: params["sni"] ?: profile.host),
            )
            "tcp" -> Unit
        }
        return json
    }

    private fun JSONObject.putIfNotBlank(key: String, value: String?): JSONObject {
        if (!value.isNullOrBlank()) put(key, value)
        return this
    }

    private fun splitArray(value: String?): JSONArray {
        val array = JSONArray()
        value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach(array::put)
        return array
    }

    private fun sniffing() = JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls").put("quic"))
    private fun freedom() = JSONObject().put("protocol", "freedom").put("tag", "direct")
    private fun block() = JSONObject().put("protocol", "blackhole").put("tag", "block")
}
