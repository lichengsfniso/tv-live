package com.tvbridge.app

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 信号源与更新设置页。
 *
 * v1.1 §4.3.2：地址输入框**不在此类**写 pref——面板里的「频道表在线地址」行只负责
 * 跳转到 LiveActivity.promptCatalogUrl()，全局唯一写入口仍是 SignalUpdateSettings.setCatalogUrl()。
 * 本类用现有 NativeUi.show / GlassUi 风格构建，遥控器可达，更新过程不弹窗不抢焦点。
 */
object UpdateSettingsUi {

    private val intervals = listOf(
        "6 小时" to 6,
        "12 小时" to 12,
        "24 小时" to 24,
        "仅手动" to 0
    )

    /**
     * @param onEditCatalogUrl 由 LiveActivity 传入 promptCatalogUrl()，两个填写入口共用
     * @param onChanged 配置 / 手动检查变化后的回调（入队一次性 Worker）
     */
    fun show(activity: Activity, onEditCatalogUrl: () -> Unit, onChanged: () -> Unit) {
        SignalUpdateSettings.attach(activity.applicationContext)
        OfficialRouteMemory.attach(activity.applicationContext)

        val unit = NativeUi.scale(activity)
        fun dp(value: Int) = (value * unit).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }

        // ① 自动刷新总开关
        val autoBox = CheckBox(activity).apply {
            text = "自动刷新订阅源"
            isChecked = SignalUpdateSettings.autoRefresh
            nativeText()
            setTextColor(GlassUi.colors(context))
            NativeUi.touchClicks(this)
            setOnCheckedChangeListener { _, checked -> SignalUpdateSettings.autoRefresh = checked }
        }
        root.addView(autoBox, LinearLayout.LayoutParams(-1, dp(44)))

        // ② 刷新频率
        root.addView(TextView(activity).apply {
            text = "刷新频率"
            nativeText()
            setTextColor(GlassUi.colors(context))
        }, LinearLayout.LayoutParams(-1, dp(36)))

        val freqGroup = RadioGroup(activity).apply { orientation = RadioGroup.HORIZONTAL }
        val hoursByViewId = mutableMapOf<Int, Int>()
        intervals.forEach { (label, hours) ->
            val button = RadioButton(activity).apply {
                id = View.generateViewId()
                text = label
                isAllCaps = false
                nativeText()
                setTextColor(GlassUi.colors(context))
                NativeUi.touchClicks(this)
            }
            hoursByViewId[button.id] = hours
            freqGroup.addView(button, LinearLayout.LayoutParams(0, dp(40), 1f))
        }
        hoursByViewId.entries.firstOrNull { it.value == SignalUpdateSettings.intervalHours }?.key
            ?.let { freqGroup.check(it) }
        freqGroup.setOnCheckedChangeListener { _, checkedId ->
            hoursByViewId[checkedId]?.let { SignalUpdateSettings.intervalHours = it }
        }
        root.addView(freqGroup, LinearLayout.LayoutParams(-1, dp(44)))

        // ③ 仅 WiFi
        val wifiBox = CheckBox(activity).apply {
            text = "仅 WiFi 自动刷新"
            isChecked = SignalUpdateSettings.wifiOnly
            nativeText()
            setTextColor(GlassUi.colors(context))
            NativeUi.touchClicks(this)
            setOnCheckedChangeListener { _, checked -> SignalUpdateSettings.wifiOnly = checked }
        }
        root.addView(wifiBox, LinearLayout.LayoutParams(-1, dp(44)))

        // ④ 频道表在线地址：只做跳转，真正填写/写入统一在 promptCatalogUrl()
        val configuredUrl = SignalUpdateSettings.catalogUrl
        val urlRow = NativeUi.optionButton(
            activity,
            "频道表在线地址",
            if (configuredUrl.isBlank()) "未启用" else "已配置"
        ).apply {
            setOnClickListener { activity.dismissSafely { onEditCatalogUrl() } }
        }
        root.addView(urlRow, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) })

        // ⑤ 状态区
        val statusView = TextView(activity).apply {
            nativeText()
            setTextColor(GlassUi.colors(context))
            text = statusText()
        }
        root.addView(statusView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        fun refreshStatus() { statusView.text = statusText() }

        // ⑥ 立即检查更新（仍受 30min 节流）
        root.addView(NativeUi.optionButton(activity, "立即检查更新").apply {
            setOnClickListener {
                refreshStatus()
                activity.dismissSafely { onChanged() }
            }
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) })

        // ⑦ 清除流缓存并重试：仅失败时可见，避免误触
        root.addView(NativeUi.optionButton(activity, "清除流缓存并重试").apply {
            visibility = if (SignalUpdateSettings.consecutiveFailures > 0 ||
                SignalUpdateSettings.status().state == State.FAILED
            ) View.VISIBLE else View.GONE
            setOnClickListener {
                val resolver = YangshipinStreamResolver(activity.applicationContext)
                try {
                    resolver.invalidateAll()
                } finally {
                    resolver.release()
                }
                refreshStatus()
                activity.dismissSafely { onChanged() }
            }
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })

        val builder = AlertDialog.Builder(activity)
            .setTitle("信号源与更新")
            .setView(root)
            .setPositiveButton("返回", null)
        NativeUi.show(builder)
    }

    /** 状态区文案：上次更新时间 / 订阅增减 / 在线表状态 / 连续失败提示。 */
    private fun statusText(): String {
        val status = SignalUpdateSettings.status()
        val last = SignalUpdateSettings.lastSuccessAt
        val time = if (last > 0L) {
            runCatching {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(last))
            }.getOrDefault(last.toString())
        } else "从未更新"

        val subs = if (status.subscriptions.ok + status.subscriptions.fail > 0) {
            "订阅源：成功 ${status.subscriptions.ok} / 失败 ${status.subscriptions.fail}，" +
                "新增 ${status.subscriptions.added} / 减少 ${status.subscriptions.removed}"
        } else "订阅源：暂无更新记录"

        val catalog = if (SignalUpdateSettings.catalogUrl.isBlank()) {
            "频道表：当前使用内置频道表"
        } else if (status.catalog.updated) {
            "频道表：v${status.catalog.version}，${status.catalog.count} 个频道"
        } else {
            // §4.3.3 失败轻量提示：不弹窗，只在状态区说明仍用内置表
            "频道表：${status.catalog.reason.ifBlank { "待更新" }}（仍用内置表）"
        }

        val warning = if (SignalUpdateSettings.consecutiveFailures >= 3) {
            "\n更新异常：已连续失败 ${SignalUpdateSettings.consecutiveFailures} 次"
        } else ""
        return "上次更新：$time\n$subs\n$catalog$warning"
    }

    private fun TextView.nativeText() {
        NativeUi.textSize(this, NativeUi.BODY_TEXT_SIZE)
    }

    private fun Activity.dismissSafely(action: () -> Unit) {
        NativeUi.dismissDialogs()
        action()
    }
}
