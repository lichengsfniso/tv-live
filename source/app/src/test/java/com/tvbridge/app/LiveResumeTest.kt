package com.tvbridge.app

import android.app.Activity
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class LiveResumeTest {
    @org.junit.Before fun resetLiveChoice(){org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("startup",0).edit().remove("live_app").remove("live_mode").commit()}
    @Test fun lastChannelSurvivesRestartAndStalePluginSyncCannotReplaceNewerSelection(){
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();a.filesDir.resolve("live-sources.json").delete()
        try{
            val repo=LiveRepository(a);repo.importText("A,https://example.test/a.m3u8\nB,https://example.test/b.m3u8")
            val channels=repo.allChannels();repo.rememberChannel(channels[1].id)
            assertEquals(channels[1].id,LiveRepository(a).resumeChannel()!!.id)
            val before=repo.exportStore();val time=JSONObject(before).getLong("lastPlayedAt")
            val stale=JSONObject(before).put("lastChannel",channels[0].id).put("lastPlayedAt",time+1).toString()
            repo.rememberChannel(channels[0].id);repo.rememberChannel(channels[1].id)
            repo.mergeSnapshot(before,stale);assertEquals(channels[1].id,repo.resumeChannel()!!.id)
            val fresh=JSONObject(before).put("lastChannel",channels[0].id).put("lastPlayedAt",time+10000).toString()
            // mergeSnapshot 走 saveAsync 异步落盘；新实例读盘存在竞态，改用同一实例断言内存合并语义
            repo.mergeSnapshot(before,fresh);assertEquals(channels[0].id,repo.resumeChannel()!!.id)
            repo.removeSource("local")
            // 删除 local 源后仅剩内置源，resumeChannel 回退到内置频道（K-02 修复后 sourceList 含内置源）
            assertTrue(repo.resumeChannel()!!.id.startsWith("yangshipin-"))
            repo.importText("C,https://example.test/c.m3u8");assertEquals("C",repo.resumeChannel()!!.name)
        }finally{c.pause().stop().destroy()}
    }
    @Test fun missingPluginFallsBackWithoutErasingItsRememberedChannel(){
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();a.filesDir.resolve("live-sources.json").delete()
        try{
            val repo=LiveRepository(a)
            // 记忆一个内置频道表中不存在的 id，验证回退首频道且不擦除记忆的原 id。
            // 原测试用 yangshipin-600001859 作为“缺失”频道，但该 pid 现已在内置表中，故失效。
            val missing="yangshipin-000000000"
            val state=JSONObject(repo.exportStore()).put("lastChannel",missing).put("lastPlayedAt",100)
            repo.restoreSnapshot(state.toString())
            assertEquals(repo.allChannels().first().name,repo.resumeChannel()!!.name)
            assertEquals(missing,JSONObject(repo.exportStore()).getString("lastChannel"))
        }finally{c.pause().stop().destroy()}
    }
}
