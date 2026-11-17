package com.tvbridge.app

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 在线频道表解析器（架构 §4.2 schema v1）。
 *
 * 信封 = schema / version / updatedAt / channels / checksum；
 * checksum = sha256(canonical)，canonical = "schema|version|updatedAt|" + channels 按 pid 升序拼接
 * "pid|name|group;"。大小写不敏感比对。任一校验失败抛 IllegalArgumentException，
 * 调用方据此丢弃本次报文、保留 lastGood 或回退 assets。
 */
object OnlineCatalogParser {
    const val SCHEMA = 1
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MIN_CHANNELS = 1
    private const val MAX_CHANNELS = 2000
    private val PID_PATTERN = Regex("[0-9]{6,12}")
    private val CHECKSUM_PATTERN = Regex("[0-9a-fA-F]{64}")

    data class Parsed(val version: Int, val updatedAt: String, val channels: List<LiveChannel>)

    fun parse(json: String): Parsed {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "频道表报文超过 2 MB" }
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("频道表 JSON 格式错误", e)
        }
        require(root.optInt("schema", -1) == SCHEMA) { "不支持的频道表 schema" }

        val version = root.optInt("version", 0)
        require(version > 0) { "频道表版本号无效" }
        val updatedAt = root.optString("updatedAt", "")

        val array: JSONArray = root.optJSONArray("channels")
            ?: throw IllegalArgumentException("频道表缺少 channels 数组")
        require(array.length() in MIN_CHANNELS..MAX_CHANNELS) { "频道数量必须在 1..2000 之间" }

        val seen = HashSet<String>()
        val channels = ArrayList<LiveChannel>(array.length())
        val canonicalParts = ArrayList<Pair<String, String>>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i)
                ?: throw IllegalArgumentException("频道条目 #$i 无效")
            val pid = item.optString("pid", "")
            require(PID_PATTERN.matches(pid)) { "频道 PID 非法: $pid" }
            require(seen.add(pid)) { "频道 PID 重复: $pid" }

            val rawName = item.optString("name", "")
            val rawGroup = item.optString("group", "")
            require(rawName.isNotBlank()) { "频道名称为空: $pid" }
            require(rawGroup.isNotBlank()) { "频道分组为空: $pid" }

            val name = rawName.trim().take(160)
            val group = rawGroup.trim().take(60)
            channels.add(LiveChannel("yangshipin-$pid", name, group, listOf("one-plugin://live/$pid")))
            canonicalParts.add(pid to "$pid|$name|$group;")
        }

        val checksum = root.optString("checksum", "")
        require(CHECKSUM_PATTERN.matches(checksum)) { "checksum 缺失或格式错误" }
        val canonical = "1|$version|$updatedAt|" +
            canonicalParts.sortedBy { it.first }.joinToString("") { it.second }
        require(sha256(canonical).equals(checksum, ignoreCase = true)) { "频道表校验和不匹配" }

        return Parsed(version, updatedAt, channels)
    }

    /** 与 [parse] 完全一致的 canonical 计算，供生成端 / 测试复用。 */
    fun canonical(version: Int, updatedAt: String, channels: List<LiveChannel>): String {
        val parts = channels.map { channel ->
            val pid = channel.id.removePrefix("yangshipin-")
            pid to "$pid|${channel.name}|${channel.group};"
        }
        return "1|$version|$updatedAt|" + parts.sortedBy { it.first }.joinToString("") { it.second }
    }

    fun checksum(version: Int, updatedAt: String, channels: List<LiveChannel>): String =
        sha256(canonical(version, updatedAt, channels))

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
