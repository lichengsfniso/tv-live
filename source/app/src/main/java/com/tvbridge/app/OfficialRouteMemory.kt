package com.tvbridge.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * 官方（央视频）线路记忆，持久化到 SharedPreferences("official-routes")。
 *
 * key 使用 PlaylistParser.digest(pid)，避免 pid 中的特殊字符落到 pref key；
 * 最多保留 200 条，超出按插入顺序淘汰最早的一批（与 PlaybackRoutes 策略一致）。
 */
object OfficialRouteMemory {
    private const val PREF_NAME = "official-routes"
    private const val ORDER_KEY = "_order"
    private const val MAX_ENTRIES = 200

    @Volatile private var appContext: Context? = null

    /** 注入应用上下文；可重复调用，仅第一次生效。 */
    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun prefs(): SharedPreferences = appContext
        ?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        ?: throw IllegalStateException("OfficialRouteMemory.attach(context) 尚未调用")

    private fun keyOf(pid: String): String = PlaylistParser.digest(pid)

    private fun readOrder(store: SharedPreferences): JSONArray = try {
        JSONArray(store.getString(ORDER_KEY, "[]") ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }

    /** 默认 false（央视频主线路）。 */
    fun preferred(pid: String): Boolean = prefs().getBoolean(keyOf(pid), false)

    fun preferred(context: Context, pid: String): Boolean {
        attach(context)
        return preferred(pid)
    }

    fun remember(pid: String, preferCctv: Boolean) {
        val store = prefs()
        val key = keyOf(pid)
        val order = readOrder(store)
        val existing = (0 until order.length()).firstOrNull { order.optString(it) == key }
        if (existing != null) order.remove(existing)
        order.put(key)
        while (order.length() > MAX_ENTRIES) order.remove(0)

        store.edit().putBoolean(key, preferCctv).putString(ORDER_KEY, order.toString()).apply()

        // 淘汰后清理对应的布尔值，避免 pref 无限膨胀
        val keep = (0 until order.length()).map { order.optString(it) }.toSet()
        val cleanup = store.edit()
        var changed = false
        store.all.keys.filter { it != ORDER_KEY && it !in keep }.forEach {
            cleanup.remove(it)
            changed = true
        }
        if (changed) cleanup.apply()
    }

    fun remember(context: Context, pid: String, preferCctv: Boolean) {
        attach(context)
        remember(pid, preferCctv)
    }

    fun clear(pid: String) {
        val store = prefs()
        val key = keyOf(pid)
        val order = readOrder(store)
        val existing = (0 until order.length()).firstOrNull { order.optString(it) == key }
        if (existing != null) order.remove(existing)
        store.edit().remove(key).putString(ORDER_KEY, order.toString()).apply()
    }
}
