package com.tvbridge.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 唯一后台调度入口。周期任务与一次性任务都落到 [UpdateCoordinator.refresh]。
 *
 * doWork 的返回约定：SUCCESS / PARTIAL / SKIPPED → success()（不重试）；
 * FAILED → retry()（由 WorkManager 提供系统退避）。
 */
class SignalRefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result = try {
        SignalUpdateSettings.attach(applicationContext)
        val force = inputData.getBoolean(KEY_FORCE, false)
        val status = UpdateCoordinator.refresh(applicationContext, ignoreAutoRefresh = force)
        when (status.state) {
            State.SUCCESS, State.PARTIAL, State.SKIPPED, State.IDLE, State.RUNNING -> Result.success()
            State.FAILED -> Result.retry()
        }
    } catch (t: Throwable) {
        android.util.Log.w(TAG, "信号源更新任务异常", t)
        Result.retry()
    }

    companion object {
        const val UNIQUE_PERIODIC = "signal-refresh-periodic"
        const val UNIQUE_NOW = "signal-refresh-now"
        const val KEY_FORCE = "force"
        private const val TAG = "SignalRefreshWorker"

        /**
         * 幂等重排周期任务。intervalHours <= 0（仅手动）时直接取消周期任务。
         * UPDATE 策略确保改配置是“更新”而非新增多个周期任务。
         */
        fun enqueuePeriodic(ctx: Context, intervalHours: Int, wifiOnly: Boolean) {
            val app = ctx.applicationContext
            if (intervalHours <= 0) {
                WorkManager.getInstance(app).cancelUniqueWork(UNIQUE_PERIODIC)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<SignalRefreshWorker>(
                intervalHours.coerceAtLeast(1).toLong(), TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setInputData(Data.Builder().putBoolean(KEY_FORCE, false).build())
                .build()
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }

        /** 一次性任务（冷启动 / 手动）：KEEP 避免重复入队。 */
        fun enqueueNow(ctx: Context) {
            val app = ctx.applicationContext
            val request = OneTimeWorkRequestBuilder<SignalRefreshWorker>()
                .setInputData(Data.Builder().putBoolean(KEY_FORCE, true).build())
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.KEEP, request)
        }
    }
}
