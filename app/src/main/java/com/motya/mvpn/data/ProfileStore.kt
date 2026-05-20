package com.motya.mvpn.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    fun load(): List<VlessProfile> {
        val raw = prefs.getString("items", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val params = item.getJSONObject("params")
                    add(
                        VlessProfile(
                            raw = item.optString("raw"),
                            id = item.getString("id"),
                            name = item.getString("name"),
                            host = item.getString("host"),
                            port = item.getInt("port"),
                            params = params.keys().asSequence().associateWith { params.getString(it) },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(items: List<VlessProfile>) {
        val array = JSONArray()
        items.forEach { profile ->
            array.put(
                JSONObject()
                    .put("raw", profile.raw)
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("host", profile.host)
                    .put("port", profile.port)
                    .put("params", JSONObject(profile.params)),
            )
        }
        prefs.edit().putString("items", array.toString()).apply()
    }

    fun loadSubscriptions(): List<String> {
        val raw = prefs.getString("subscriptions", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) add(array.getString(i))
            }
        }.getOrDefault(emptyList())
    }

    fun saveSubscriptions(items: List<String>) {
        val array = JSONArray()
        items.distinct().forEach(array::put)
        prefs.edit().putString("subscriptions", array.toString()).apply()
    }

    fun lastSubscriptionUpdate(): Long = prefs.getLong("subscriptions_last_update", 0L)

    fun markSubscriptionsUpdated() {
        prefs.edit().putLong("subscriptions_last_update", System.currentTimeMillis()).apply()
    }
}
