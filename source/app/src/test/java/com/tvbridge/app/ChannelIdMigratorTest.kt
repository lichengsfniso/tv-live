package com.tvbridge.app

import org.junit.Assert.*
import org.junit.Test

/** T02 验收：ID 命中 / 流摘要命中 / 名称命中 / 孤儿保留 / 去重。 */
class ChannelIdMigratorTest {
    private fun channel(id: String, name: String, stream: String) =
        LiveChannel(id, name, "央视", listOf(stream))

    @Test
    fun exactIdMatchIsPreferred() {
        val new = listOf(channel("new-id", "完全不同的名字", "https://cdn.test/a.m3u8"))
        val old = listOf(channel("new-id", "旧名字", "https://cdn.test/old.m3u8"))
        assertEquals(setOf("new-id"), ChannelIdMigrator.migrate(new, setOf("new-id"), old))
    }

    @Test
    fun streamDigestMatchesRenamedChannel() {
        val stream = "https://cdn.test/stable.m3u8"
        val new = listOf(channel("id-2026", "央视综合", stream))
        val old = listOf(channel("id-2025", "CCTV-1 综合@HD", stream))
        assertEquals(setOf("id-2026"), ChannelIdMigrator.migrate(new, setOf("id-2025"), old))
    }

    @Test
    fun normalizedNameMatchesWhenStreamChanged() {
        val new = listOf(channel("id-b", "CCTV5 体育", "https://cdn.test/new.m3u8"))
        val old = listOf(channel("id-a", "CCTV 5 体育 @HD", "https://cdn.test/old.m3u8"))
        // 流已变化，但名称去空白 / 去画质后缀后一致 → 命中新 ID
        assertEquals(setOf("id-b"), ChannelIdMigrator.migrate(new, setOf("id-a"), old))
    }

    @Test
    fun orphanFavoriteIsNeverDropped() {
        val new = listOf(channel("id-n", "新频道", "https://cdn.test/n.m3u8"))
        val old = listOf(channel("orphan", "已下架频道", "https://cdn.test/gone.m3u8"))
        assertEquals(setOf("orphan"), ChannelIdMigrator.migrate(new, setOf("orphan"), old))
    }

    @Test
    fun duplicateMappingsCollapseIntoOneFavorite() {
        val stream = "https://cdn.test/one.m3u8"
        val new = listOf(channel("target", "同一频道", stream))
        val old = listOf(
            channel("a", "别名一", stream),
            channel("b", "别名二", stream)
        )
        assertEquals(setOf("target"), ChannelIdMigrator.migrate(new, setOf("a", "b"), old))
    }

    @Test
    fun normalizeStripsWhitespaceAndQualitySuffix() {
        assertEquals("CCTV5体育", ChannelIdMigrator.normalize(" CCTV 5 体育 @HD "))
        assertEquals("4K频道", ChannelIdMigrator.normalize("4K频道"))
    }
}
