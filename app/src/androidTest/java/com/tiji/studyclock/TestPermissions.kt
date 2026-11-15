package com.tiji.studyclock

import android.Manifest
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry

/**
 * 开始计时会请求通知权限，系统弹窗会盖住应用窗口。计时相关用例在测试进程内直接授权，
 * 既避免弹窗干扰，也能让前台服务真正展示通知。
 */
fun grantNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.uiAutomation.grantRuntimePermission(
        instrumentation.targetContext.packageName,
        Manifest.permission.POST_NOTIFICATIONS,
    )
}
