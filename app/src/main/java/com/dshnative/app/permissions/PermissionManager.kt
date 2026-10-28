package com.dshnative.app.permissions

import android.app.AppOpsManager
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 权限清单与检查（权限插件的数据层）。
 *
 * 覆盖三类：
 * 1. 运行时权限（Android 6.0+ 需动态请求）：通知/相机/麦克风/联系人/日历/电话/短信/通话记录/媒体/位置/附近设备
 * 2. 特殊权限（需跳系统设置页手动授予）：所有文件访问 / 悬浮窗 / 修改系统设置 / 精确闹钟 / 使用情况访问
 * 3. 普通权限（Manifest 声明即授予）：网络 / WLAN 状态 / 应用列表
 */
data class PermissionItem(
    val permission: String?,          // 运行时权限名（specialKey 非空时为 null）
    val specialKey: String?,          // 特殊权限标识（SPECIAL_*）
    val name: String,                 // 显示名
    val description: String,          // 说明
    val group: String                 // 分组
)

object PermissionManager {

    const val SPECIAL_ALL_FILES = "all_files"
    const val SPECIAL_OVERLAY = "overlay"
    const val SPECIAL_WRITE_SETTINGS = "write_settings"
    const val SPECIAL_EXACT_ALARM = "exact_alarm"
    const val SPECIAL_USAGE_STATS = "usage_stats"

    /** 全部权限清单（AI 工具与权限页共用） */
    val all: List<PermissionItem> = listOf(
        // ---- 系统 ----
        PermissionItem("android.permission.POST_NOTIFICATIONS", null, "通知", "发送与显示通知", "系统"),
        PermissionItem(null, SPECIAL_OVERLAY, "悬浮窗", "在其他应用上层显示悬浮内容", "系统"),
        PermissionItem(null, SPECIAL_WRITE_SETTINGS, "修改系统设置", "调整系统级设置（如亮度、飞行模式）", "系统"),
        PermissionItem(null, SPECIAL_EXACT_ALARM, "闹钟与提醒", "设定精确闹钟，不被打扰", "系统"),
        PermissionItem(null, SPECIAL_USAGE_STATS, "使用情况访问", "读取应用使用统计（App 使用记录）", "系统"),

        // ---- 存储 ----
        PermissionItem(null, SPECIAL_ALL_FILES, "所有文件访问", "管理外部存储的全部文件", "存储"),
        PermissionItem("android.permission.READ_MEDIA_IMAGES", null, "照片", "读取相册中的图片", "存储"),
        PermissionItem("android.permission.READ_MEDIA_VIDEO", null, "视频", "读取相册中的视频", "存储"),
        PermissionItem("android.permission.READ_MEDIA_AUDIO", null, "音频文件", "读取设备上的音频文件", "存储"),

        // ---- 相机与麦克风 ----
        PermissionItem("android.permission.CAMERA", null, "相机", "拍照、录像", "相机与麦克风"),
        PermissionItem("android.permission.RECORD_AUDIO", null, "麦克风", "录音", "相机与麦克风"),

        // ---- 联系人 / 日历 ----
        PermissionItem("android.permission.READ_CONTACTS", null, "读取联系人", "读取通讯录联系人", "联系人与日历"),
        PermissionItem("android.permission.WRITE_CONTACTS", null, "写入联系人", "新增/修改联系人", "联系人与日历"),
        PermissionItem("android.permission.READ_CALENDAR", null, "读取日历", "读取日历事件", "联系人与日历"),
        PermissionItem("android.permission.WRITE_CALENDAR", null, "写入日历", "新增/修改日历事件", "联系人与日历"),

        // ---- 电话 / 短信 / 通话记录 ----
        PermissionItem("android.permission.READ_PHONE_STATE", null, "读取电话状态", "读取设备电话状态、号码等", "电话与短信"),
        PermissionItem("android.permission.CALL_PHONE", null, "拨打电话", "直接拨打电话", "电话与短信"),
        PermissionItem("android.permission.READ_SMS", null, "读取短信", "读取短信内容", "电话与短信"),
        PermissionItem("android.permission.RECEIVE_SMS", null, "接收短信", "接收新短信并通知", "电话与短信"),
        PermissionItem("android.permission.SEND_SMS", null, "发送短信", "发送短信", "电话与短信"),
        PermissionItem("android.permission.READ_CALL_LOG", null, "读取通话记录", "读取通话记录", "电话与短信"),
        PermissionItem("android.permission.WRITE_CALL_LOG", null, "写入通话记录", "新增/修改通话记录", "电话与短信"),

        // ---- 网络 / 位置 ----
        PermissionItem("android.permission.NEARBY_WIFI_DEVICES", null, "附近设备", "连接附近的 Wi-Fi/蓝牙设备", "网络与位置"),
        PermissionItem("android.permission.ACCESS_FINE_LOCATION", null, "精确位置", "获取精确地理位置（Wi-Fi 扫描需要）", "网络与位置"),
        PermissionItem("android.permission.ACCESS_COARSE_LOCATION", null, "粗略位置", "获取大致地理位置", "网络与位置")
    )

    /** 是否已授予（含特殊权限检测） */
    fun isGranted(context: Context, item: PermissionItem): Boolean {
        if (item.specialKey != null) {
            return when (item.specialKey) {
                SPECIAL_ALL_FILES -> Environment.isExternalStorageManager()
                SPECIAL_OVERLAY -> Settings.canDrawOverlays(context)
                SPECIAL_WRITE_SETTINGS -> Settings.System.canWrite(context)
                SPECIAL_EXACT_ALARM -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                        am.canScheduleExactAlarms()
                    } else true
                }
                SPECIAL_USAGE_STATS -> hasUsageStatsPermission(context)
                else -> false
            }
        }
        val p = item.permission ?: return false
        return ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    }

    /** 特殊权限设置页 Intent（无则返回 null） */
    fun buildSpecialSettingsIntent(context: Context, key: String): Intent? = when (key) {
        SPECIAL_ALL_FILES -> Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        SPECIAL_OVERLAY -> Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        SPECIAL_WRITE_SETTINGS -> Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
        SPECIAL_EXACT_ALARM -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        } else null
        SPECIAL_USAGE_STATS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        else -> null
    }

    /** 应用详情设置页（普通权限管理入口） */
    fun buildAppSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    private fun hasUsageStatsPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }
}