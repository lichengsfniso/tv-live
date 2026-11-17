package com.tvbridge.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** T05 验收：正确 JSON、checksum 错、版本非法、重复 pid、超量、非法 pid、空 channels。 */
class OnlineCatalogParserTest {
    private val updatedAt = "2026-01-16T10:00:00Z"
    private val defaultChannels = listOf(
        Triple("600001859", "CCTV-1 综合", "央视"),
        Triple("600001800", "CCTV-2 财经", "央视")
    )

    private fun buildJson(
        channels: List<Triple<String, String, String>> = defaultChannels,
        version: Int = 20260116,
        schema: Int = 1,
        checksumOverride: String? = null,
        includeChecksum: Boolean = true
    ): String {
        val array = JSONArray(channels.map { (pid, name, group) ->
            JSONObject().put("pid", pid).put("name", name).put("group", group)
        })
        val live = channels.map { (pid, name, group) ->
            LiveChannel("yangshipin-$pid", name.trim().take(160), group.trim().take(60), listOf("one-plugin://live/$pid"))
        }
        val sum = checksumOverride ?: OnlineCatalogParser.checksum(version, updatedAt, live)
        val root = JSONObject()
            .put("schema", schema)
            .put("version", version)
            .put("updatedAt", updatedAt)
            .put("channels", array)
        if (includeChecksum) root.put("checksum", sum)
        return root.toString()
    }

    @Test
    fun validCatalogIsParsedWithStableIdsAndStreams() {
        val parsed = OnlineCatalogParser.parse(buildJson())
        assertEquals(20260116, parsed.version)
        assertEquals(updatedAt, parsed.updatedAt)
        assertEquals(2, parsed.channels.size)
        assertEquals("yangshipin-600001859", parsed.channels.first { it.name.contains("CCTV-1") }.id)
        assertEquals(
            listOf("one-plugin://live/600001800"),
            parsed.channels.first { it.name.contains("CCTV-2") }.streams
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongChecksumIsRejected() {
        OnlineCatalogParser.parse(buildJson(checksumOverride = "0".repeat(64)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingChecksumIsRejected() {
        OnlineCatalogParser.parse(buildJson(includeChecksum = false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveVersionIsRejected() {
        OnlineCatalogParser.parse(buildJson(version = 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongSchemaIsRejected() {
        OnlineCatalogParser.parse(buildJson(schema = 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicatePidIsRejected() {
        OnlineCatalogParser.parse(buildJson(channels = defaultChannels + Triple("600001859", "重复", "央视")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun illegalPidIsRejected() {
        OnlineCatalogParser.parse(buildJson(channels = listOf(Triple("12", "短 PID", "央视"))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyChannelsAreRejected() {
        OnlineCatalogParser.parse(buildJson(channels = emptyList()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun tooManyChannelsAreRejected() {
        val many = (0 until 2001).map { Triple("${600000000 + it}", "频道$it", "央视") }
        OnlineCatalogParser.parse(buildJson(channels = many))
    }

    @Test
    fun checksumComparisonIsCaseInsensitive() {
        val json = JSONObject(buildJson())
        json.put("checksum", json.getString("checksum").uppercase())
        assertEquals(2, OnlineCatalogParser.parse(json.toString()).channels.size)
    }
}
