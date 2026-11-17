package com.tvbridge.app

import android.app.Activity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T02 验收：digest 未变零改动；单源失败不影响其余；自动刷新不改 active；收藏迁移命中。
 * 通过注入 m3uTransport 完全避开真实网络。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LiveRepositoryAutoRefreshTest {
    private val subUrl = "https://example.test/list.m3u"
    private val stream1 = "https://cdn.example.test/c1.m3u8"
    private val stream2 = "https://cdn.example.test/c2.m3u8"
    private val body = "#EXTM3U\n#EXTINF:-1,CCTV-1\n$stream1\n#EXTINF:-1,CCTV-2\n$stream2\n"

    @Before
    fun resetState() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("live-subscriptions", 0).edit().clear().commit()
        app.getSharedPreferences("signal-update-settings", 0).edit().clear().commit()
        app.filesDir.resolve("live-sources.json").delete()
        app.filesDir.resolve("live-sources.pre-refresh.json").delete()
    }

    private fun newRepo(transport: (String) -> String) =
        LiveRepository(Robolectric.buildActivity(Activity::class.java).setup().get(), transport)

    private fun installSource(repo: LiveRepository, url: String, channels: List<LiveChannel>) {
        val state = JSONObject(repo.exportStore())
        state.getJSONArray("sources").put(
            JSONObject()
                .put("url", url)
                .put("name", "订阅")
                .put("updated", 0L)
                .put("channels", JSONArray(channels.map { it.json() }))
        )
        repo.restoreSnapshot(state.toString())
    }

    @Test
    fun unchangedDigestKeepsChannelListAndActiveUntouched() {
        val channels = PlaylistParser.parse(body, subUrl)
        val repo = newRepo { body }
        installSource(repo, subUrl, channels)
        RuntimeEnvironment.getApplication().getSharedPreferences("live-subscriptions", 0)
            .edit().putString(subUrl, PlaylistParser.digest(body)).commit()

        val beforeChannels = repo.sourceList().first { it.url == subUrl }.channels.toString()
        val beforeActive = repo.activeSource()

        val result = repo.refreshAllSubscriptions()

        assertEquals(1, result.ok)
        assertEquals(0, result.fail)
        assertEquals(0, result.added)
        assertEquals(0, result.removed)
        assertEquals(beforeChannels, repo.sourceList().first { it.url == subUrl }.channels.toString())
        assertEquals("自动刷新不得切换 active", beforeActive, repo.activeSource())
    }

    @Test
    fun oneFailingSourceDoesNotDamageTheOthers() {
        val goodUrl = "https://example.test/good.m3u"
        val badUrl = "https://example.test/bad.m3u"
        val otherBody = "#EXTM3U\n#EXTINF:-1,新频道\nhttps://cdn.example.test/new.m3u8\n"
        val repo = newRepo { url ->
            if (url == badUrl) throw java.io.IOException("模拟源站超时") else otherBody
        }
        val goodBefore = listOf(LiveChannel("g1", "旧频道", "组", listOf("https://cdn.example.test/old.m3u8")))
        val badBefore = listOf(LiveChannel("b1", "坏源频道", "组", listOf("https://cdn.example.test/bad.m3u8")))
        installSource(repo, goodUrl, goodBefore)
        installSource(repo, badUrl, badBefore)
        val beforeActive = repo.activeSource()

        val result = repo.refreshAllSubscriptions()

        assertEquals(1, result.ok)
        assertEquals(1, result.fail)
        assertTrue(result.details.any { it.contains(badUrl) })
        assertEquals("好源必须更新", 1, repo.sourceList().first { it.url == goodUrl }.channels.size)
        assertEquals("坏源必须保留原频道", badBefore, repo.sourceList().first { it.url == badUrl }.channels)
        assertEquals(beforeActive, repo.activeSource())
    }

    @Test
    fun changedBodyUpdatesChannelsInPlaceWithoutActivatingSource() {
        val oldChannels = listOf(LiveChannel("old-1", "旧名称", "组", listOf(stream1)))
        val repo = newRepo { body }
        installSource(repo, subUrl, oldChannels)
        val beforeActive = repo.activeSource()

        val result = repo.refreshAllSubscriptions()

        assertEquals(1, result.ok)
        val refreshed = repo.sourceList().first { it.url == subUrl }
        assertEquals(2, refreshed.channels.size)
        assertEquals("订阅", refreshed.name)
        assertEquals(beforeActive, repo.activeSource())
        assertTrue(repo.lastRefreshAt > 0L)
    }

    @Test
    fun favoritesMigrateToRenamedChannelByStreamDigest() {
        val oldChannels = listOf(LiveChannel("old-id", "CCTV-1 HD", "央视", listOf(stream1)))
        val repo = newRepo { body }
        installSource(repo, subUrl, oldChannels)
        repo.toggleFavorite("old-id")
        assertTrue(repo.favorite("old-id"))

        val result = repo.refreshAllSubscriptions()
        assertEquals(1, result.ok)
        val newChannels = repo.sourceList().first { it.url == subUrl }.channels
        val migrated = ChannelIdMigrator.migrate(newChannels, setOf("old-id"), oldChannels)
        repo.replaceFavorites(migrated)

        assertFalse("旧 ID 应被替换", repo.favorite("old-id"))
        assertTrue("应按流 URL 摘要命中新 ID", repo.favorite(newChannels.first().id))
    }

    @Test
    fun snapshotBackupCanRollbackStore() {
        val repo = newRepo { body }
        installSource(repo, subUrl, listOf(LiveChannel("kept", "保留频道", "组", listOf(stream1))))
        val snapshot = repo.snapshotStore()

        repo.refreshAllSubscriptions()
        assertNotEquals("kept", repo.sourceList().first { it.url == subUrl }.channels.first().id)

        repo.restoreSnapshot(snapshot)
        assertEquals("kept", repo.sourceList().first { it.url == subUrl }.channels.first().id)
    }
}
