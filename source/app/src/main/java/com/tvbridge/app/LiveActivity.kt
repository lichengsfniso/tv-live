package com.tvbridge.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * 独立直播 APK 的唯一入口。
 *
 * 从源工程桌面 Activity 中逐条承接直播分支：直接持有 LiveRepository（替代全局单例），
 * 构造 LivePanel、分发遥控器按键、转发生命周期。exit 回调改为 finish()，
 * noSources 回调改为 App 内设置弹窗。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LiveActivity : Activity() {
    lateinit var repo: LiveRepository
    private var livePanel: LivePanel? = null
    private var setupDialog: AlertDialog? = null
    private lateinit var root: FrameLayout
    /** 播放中收到更新完成回调时只置位，等 onResume / 切台再真正重建列表。 */
    @Volatile private var pendingChannelRefresh = false

    /** 后台更新完成后的统一回调：播放中不打扰、不抢焦点。 */
    private val updateStatusListener: (UpdateStatus) -> Unit = { status ->
        if (status.state == State.SUCCESS || status.state == State.PARTIAL || status.state == State.FAILED) {
            if (livePanel?.isPlaying() == true) {
                pendingChannelRefresh = true
            } else {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    repo.refreshPlugin()
                    livePanel?.refresh(deferCurrentRemoval = true)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SignalUpdateSettings.attach(applicationContext)
        repo = LiveRepository(this)
        UpdateCoordinator.bindRepository(repo)
        // T05：冷启动同步恢复上次在线表（无网络），使首帧即可用
        YangshipinCatalog.loadFromDisk(this)
        // 幂等挂周期任务 + 一次冷启动补刷，均不阻塞首帧播放
        SignalRefreshWorker.enqueuePeriodic(this, SignalUpdateSettings.intervalHours, SignalUpdateSettings.wifiOnly)
        SignalRefreshWorker.enqueueNow(this)
        UpdateCoordinator.addListener(updateStatusListener)
        root = FrameLayout(this)
        setContentView(root)
        showLive()
        // 可选：外部以 "channel_id" 指定频道拉起
        intent.getStringExtra("channel_id")?.let { id ->
            repo.findChannel(id)?.let { channel -> livePanel?.play(channel, true) }
        }
    }

    /** 等价源工程 showLive(autoPlay=true)：刷新插件 → 断点续播 → 构造面板 → 常亮。 */
    private fun showLive() {
        repo.refreshPlugin()
        val previous = repo.resumeChannel()
        if (previous == null) {
            showLiveSetup()
            return
        }
        if (livePanel == null) {
            val panel = LivePanel(
                this,
                repo,
                { finish() },
                { showLiveSetup() },
                { c, tap, state -> YangshipinPlayer(c, tap, state) },
                { showUpdateSettings() }
            )
            livePanel = panel
            root.addView(panel, FrameLayout.LayoutParams(-1, -1))
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            livePanel?.refresh()
        }
        livePanel?.play(previous, false)
        dismissSetup()
    }

    /** 无源时的设置弹窗：导入节目单文本 / 添加直链 / 订阅 URL。 */
    private fun showLiveSetup() {
        if (isFinishing || isDestroyed) return
        if (setupDialog?.isShowing == true) return
        val items: Array<CharSequence> = arrayOf("导入节目单文本", "添加直播源（名称 + URL）", "订阅节目单 URL", "频道表在线地址（可选）", "返回直播")
        val dialog = AlertDialog.Builder(this)
            .setTitle("设置直播源")
            .setItems(items) { _, index ->
                when (index) {
                    0 -> promptImportText()
                    1 -> promptAddDirect()
                    2 -> promptSubscribe()
                    3 -> promptCatalogUrl()
                    else -> dismissSetup()
                }
            }
            .setNegativeButton("返回直播", null)
            .setOnDismissListener { setupDialog = null }
            .create()
        setupDialog = dialog
        dialog.show()
    }

    private fun dismissSetup() {
        setupDialog?.dismiss()
        setupDialog = null
    }

    private fun promptImportText() {
        val input = newInput("粘贴 m3u / txt 节目单内容", 6)
        AlertDialog.Builder(this)
            .setTitle("导入节目单文本")
            .setView(inputBox(input))
            .setPositiveButton("导入") { _, _ ->
                val text = input.text.toString()
                if (text.isBlank()) {
                    NativeUi.notice(this, "节目单内容为空")
                } else {
                    applySource { repo.importText(text) }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun promptAddDirect() {
        val name = newInput("名称（可留空）", 1)
        val url = newInput("http(s)://... 或 rtsp://... 或 udp://...", 1)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(name, LinearLayout.LayoutParams(-1, -2))
            addView(url, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        AlertDialog.Builder(this)
            .setTitle("添加直播源")
            .setView(box)
            .setPositiveButton("添加") { _, _ ->
                applySource { repo.addDirect(name.text.toString().trim(), url.text.toString().trim()) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun promptSubscribe() {
        val input = newInput("https://example.com/list.m3u", 1)
        AlertDialog.Builder(this)
            .setTitle("订阅节目单 URL")
            .setView(inputBox(input))
            .setPositiveButton("订阅") { _, _ ->
                val address = input.text.toString().trim()
                if (address.isEmpty()) {
                    NativeUi.notice(this, "请输入订阅地址")
                } else {
                    applySource { repo.subscribe(address) }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 频道表在线地址唯一填写入口：showLiveSetup 第 4 项与更新设置面板都汇入这里。 */
    private fun promptCatalogUrl() {
        val input = newInput("频道表在线地址（留空则只用内置频道表）", 1)
        val builder = AlertDialog.Builder(this)
            .setTitle("频道表在线地址")
            .setView(inputBox(input))
            .setPositiveButton("保存") { _, _ ->
                val url = input.text.toString().trim()
                SignalUpdateSettings.setCatalogUrl(url)
                NativeUi.notice(this, if (url.isEmpty()) "已清空，使用内置频道表" else "已保存，正在检查更新")
            }
            .setNegativeButton("取消", null)
        NativeUi.show(builder)
    }

    /** LivePanel「更新设置」按钮入口；地址行再统一跳回 promptCatalogUrl()。 */
    private fun showUpdateSettings() {
        if (isFinishing || isDestroyed) return
        UpdateSettingsUi.show(
            this,
            onEditCatalogUrl = { if (!isFinishing && !isDestroyed) promptCatalogUrl() }
        ) { SignalRefreshWorker.enqueueNow(this) }
    }

    private fun newInput(hint: String, lines: Int): EditText = EditText(this).apply {
        this.hint = hint
        minLines = 1
        maxLines = lines
        isSingleLine = lines == 1
        textSize = 16f
    }

    private fun inputBox(input: EditText): FrameLayout {
        val pad = dp(20)
        return FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(input, FrameLayout.LayoutParams(-1, -2))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** 订阅含网络请求，统一放 IO 线程；结果回到主线程刷新 UI。 */
    private fun applySource(action: () -> Boolean) {
        AppThreads.onIo {
            val ok = try {
                action()
            } catch (t: Throwable) {
                false
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                NativeUi.notice(this, repo.lastMessage)
                if (ok) {
                    liveUpdated()
                } else {
                    showLiveSetup()
                }
            }
        }
    }

    /** 等价源工程 liveUpdated()：有可播频道则重建直播面板，否则仅刷新。 */
    private fun liveUpdated() {
        if (repo.resumeChannel() != null) showLive() else livePanel?.refresh()
    }

    /** 只在 ACTION_DOWN 时转发给 LivePanel，其余交给系统（与源工程行为一致）。 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && livePanel?.handleKey(event.keyCode) == true) return true
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        if (livePanel?.back() != true) {
            livePanel?.stop()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        repo.refreshPlugin()
        if (pendingChannelRefresh) {
            pendingChannelRefresh = false
            livePanel?.refresh(deferCurrentRemoval = true)
        }
        livePanel?.resume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        livePanel?.pause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun onDestroy() {
        UpdateCoordinator.removeListener(updateStatusListener)
        livePanel?.stop()
        livePanel?.let { root.removeView(it) }
        livePanel = null
        dismissSetup()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}
