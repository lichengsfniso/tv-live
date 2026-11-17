package com.tvbridge.app

import android.app.Activity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * K-02 回归测试：独立 APK 中 LiveLaunch.builtIn() 恒为 true，但 sourceList() 必须返回全部源，
 * 保证用户通过“导入节目单文本 / 添加直播源 / 订阅 URL”加入的自定义源可见、可选、可续播。
 *
 * 修复前（sourceList 仅返回 DEFAULT_SOURCE）本组用例必然失败；修复后应全部通过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class LiveRepositorySourcesTest {

    @org.junit.Before
    fun resetState() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        app.getSharedPreferences("startup", 0).edit().clear().commit()
        app.getSharedPreferences("live-resume", 0).edit().clear().commit()
    }

    /** 内置源 + 本地导入源：sourceList/allChannels/findChannel 必须同时可见两者。 */
    @Test
    fun builtInAndImportedLocalSourceAreBothVisible() {
        // Arrange
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.filesDir.resolve("live-sources.json").delete()
        val repo = LiveRepository(activity)
        assertTrue("init 后内置源应自动就绪", repo.sourceList().any { it.url == LiveRepository.DEFAULT_SOURCE })

        // Act
        assertTrue("导入应成功", repo.importText("自定义频道,https://example.test/custom.m3u8"))

        // Assert
        val urls = repo.sourceList().map { it.url }
        assertTrue("sourceList 必须包含内置源，实际=$urls", urls.contains(LiveRepository.DEFAULT_SOURCE))
        assertTrue("sourceList 必须包含本地导入源，实际=$urls", urls.contains("local"))
        assertEquals("导入后应激活自定义源", "local", repo.activeSource())
        val custom = repo.allChannels().firstOrNull { it.name == "自定义频道" }
        assertNotNull("allChannels 必须能看到自定义源频道", custom)
        assertNotNull("findChannel 必须能找到自定义源频道", repo.findChannel(custom!!.id))
    }

    /** 内置源 + 自定义订阅源：恢复快照后订阅源仍可见，且可续播其中的频道。 */
    @Test
    fun customSubscriptionSourceIsVisibleAndResumable() {
        // Arrange
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.filesDir.resolve("live-sources.json").delete()
        val repo = LiveRepository(activity)
        val url = "https://example.com/list.m3u8"
        val channel = JSONObject()
            .put("id", "sub-channel-1")
            .put("name", "订阅频道")
            .put("group", "自定义")
            .put("streams", JSONArray().put("https://example.test/stream.m3u8"))
        val state = JSONObject(repo.exportStore())
            .put("active", url)
        state.getJSONArray("sources").put(
            JSONObject()
                .put("url", url)
                .put("name", "订阅")
                .put("updated", System.currentTimeMillis())
                .put("channels", JSONArray().put(channel))
        )

        // Act
        repo.restoreSnapshot(state.toString())

        // Assert
        val urls = repo.sourceList().map { it.url }
        assertTrue("sourceList 必须包含内置源，实际=$urls", urls.contains(LiveRepository.DEFAULT_SOURCE))
        assertTrue("sourceList 必须包含订阅源，实际=$urls", urls.contains(url))
        assertEquals("订阅频道", repo.allChannels().firstOrNull { it.id == "sub-channel-1" }?.name)
        assertEquals("订阅频道", repo.findChannel("sub-channel-1")?.name)
        assertEquals("续播须命中订阅源频道", "sub-channel-1", repo.resumeChannel()?.id)
    }
}
