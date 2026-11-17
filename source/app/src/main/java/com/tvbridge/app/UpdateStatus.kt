package com.tvbridge.app

/** 订阅源刷新结果。 */
data class SourceResult(
    val ok: Int = 0,
    val fail: Int = 0,
    val added: Int = 0,
    val removed: Int = 0,
    val details: List<String> = emptyList()
)

/** 在线频道表刷新结果。 */
data class CatalogResult(
    val enabled: Boolean = false,
    val updated: Boolean = false,
    val version: Int = 0,
    val count: Int = 0,
    val reason: String = ""
)

/** 一次后台更新的聚合状态。 */
data class UpdateStatus(
    val state: State = State.IDLE,
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
    val subscriptions: SourceResult = SourceResult(),
    val catalog: CatalogResult = CatalogResult(),
    val message: String = ""
)

/** SKIPPED 不计入连续失败；PARTIAL 表示 A / B 中有一部分成功。 */
enum class State { IDLE, RUNNING, SUCCESS, PARTIAL, FAILED, SKIPPED }
