package com.tvbridge.app

import android.content.Context

/**
 * 独立直播 APK 的模式 shim。
 *
 * 源工程中 LiveLaunch 从 SharedPreferences("startup") 读取桌面/直播模式；本工程只保留
 * 内置央视频道一条路径，固定返回 true，使 LiveRepository / LiveCatalog 的既有逻辑
 * 一行不改即可运行。
 */
object LiveLaunch {
    fun builtIn(context: Context): Boolean = true

    fun label(context: Context): String = "内置直播"

    /** 兼容占位：源码中调用点已裁剪，保留签名避免遗漏的调用方编译失败。 */
    fun selectInternal(context: Context, mode: String, repo: LiveRepository): Boolean = true
}
