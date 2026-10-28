package com.dshnative.app.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shizuku 免 Root 权限支持。
 *
 * 通过 Shizuku（moe.shizuku.manager）获得 adb 级别的 shell 权限：
 * - [isShizukuAvailable]：Shizuku 服务是否运行（pingBinder）
 * - [isGranted]：本应用是否已获得 Shizuku 授权
 * - [requestPermission]：发起授权请求（失败时回退为打开 Shizuku 管理器）
 * - [openShizukuManager]：打开 Shizuku 管理器让用户授权（未安装则打开官网）
 * - [execShell]：以 Shizuku 权限执行 shell 命令（需已授权）
 *
 * Shizuku 13 将 `newProcess` 设为私有（公共 API 移除），这里通过反射调用
 * `Shizuku.newProcess(String[], String[], String)` 创建远程进程（Android 反射可用）。
 * 所有调用均包裹 try/catch，保证未安装 Shizuku 时应用不崩溃。
 */
object ShizukuManager {

    private const val TAG = "ShizukuManager"

    /** 授权请求码（Shizuku 通过 OnRequestPermissionResultListener 回传） */
    const val REQUEST_CODE = 10001

    data class ShizukuResult(
        val ok: Boolean,
        val exit: Int = -1,
        val stdout: String = "",
        val stderr: String = ""
    )

    /** 授权结果监听器（供 UI 观察授权状态变化） */
    interface PermissionResultListener {
        fun onResult(granted: Boolean)
    }

    private val resultListeners = CopyOnWriteArrayList<PermissionResultListener>()
    private var listenerRegistered = false

    private val internalListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "onRequestPermissionResult granted=$granted")
            resultListeners.forEach { listener ->
                try {
                    listener.onResult(granted)
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Shizuku 服务是否运行（已安装且 binder 已连接） */
    fun isShizukuAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        Log.w(TAG, "isShizukuAvailable error: ${e.message}")
        false
    }

    /** 本应用是否已获得 Shizuku 授权 */
    fun isGranted(): Boolean = try {
        if (!Shizuku.pingBinder()) {
            false
        } else {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }
    } catch (e: Throwable) {
        Log.w(TAG, "isGranted error: ${e.message}")
        false
    }

    /** Android 11+ 是否应显示授权说明 */
    fun shouldShowRationale(): Boolean = try {
        !Shizuku.isPreV11() && Shizuku.shouldShowRequestPermissionRationale()
    } catch (e: Throwable) {
        false
    }

    /**
     * 发起 Shizuku 授权请求。
     * 优先调用 Shizuku.requestPermission(REQUEST_CODE)（需主线程）；
     * 若 Shizuku 不可用或调用失败，回退为打开 Shizuku 管理器应用详情页。
     * 返回 true 表示已发起请求（或已授权），false 表示回退到了管理器。
     */
    fun requestPermission(context: Context): Boolean {
        return try {
            registerInternalListener()
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Shizuku binder 不可用，打开管理器")
                openShizukuManager(context)
                return false
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                return true
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Shizuku.requestPermission(REQUEST_CODE)
            } else {
                Handler(Looper.getMainLooper()).post {
                    runCatching { Shizuku.requestPermission(REQUEST_CODE) }
                        .onFailure { openShizukuManager(context) }
                }
            }
            true
        } catch (e: Throwable) {
            Log.w(TAG, "requestPermission failed: ${e.message}", e)
            openShizukuManager(context)
            false
        }
    }

    /** 打开 Shizuku 管理器（用户可在其中为本应用授权）；未安装则打开官网 */
    fun openShizukuManager(context: Context): Boolean = try {
        val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.manager")
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"))
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallback)
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "openShizukuManager failed", e)
        false
    }

    /** 注册授权结果监听器 */
    fun addResultListener(listener: PermissionResultListener) {
        resultListeners.add(listener)
    }

    /** 移除授权结果监听器 */
    fun removeResultListener(listener: PermissionResultListener) {
        resultListeners.remove(listener)
    }

    /**
     * 以 Shizuku 权限执行 shell 命令（需已授权）。
     * 通过反射调用 Shizuku.newProcess（13.x 私有静态方法）创建远程进程。
     */
    fun execShell(command: String): ShizukuResult {
        if (!isShizukuAvailable()) {
            return ShizukuResult(ok = false, stderr = "Shizuku 不可用（未安装或服务未运行）")
        }
        if (!isGranted()) {
            return ShizukuResult(ok = false, stderr = "Shizuku 未授权，请先打开 Shizuku 管理器为本应用授权")
        }
        return try {
            val process = createShizukuProcess(arrayOf("sh", "-c", command))
                ?: return ShizukuResult(ok = false, stderr = "Shizuku 无法创建远程进程")

            var stdout = ""
            var stderr = ""
            val outThread = Thread {
                try {
                    stdout = process.inputStream.bufferedReader().readText()
                } catch (_: Exception) {
                }
            }
            val errThread = Thread {
                try {
                    stderr = process.errorStream.bufferedReader().readText()
                } catch (_: Exception) {
                }
            }
            outThread.start()
            errThread.start()

            val exit = try {
                val done = process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
                if (done) process.exitValue() else -1
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                try {
                    process.destroy()
                } catch (_: Exception) {
                }
                -1
            }
            outThread.join(3000)
            errThread.join(3000)
            ShizukuResult(ok = exit == 0, exit = exit, stdout = stdout.trim(), stderr = stderr.trim())
        } catch (e: Throwable) {
            Log.e(TAG, "execShell failed", e)
            ShizukuResult(ok = false, stderr = "Shizuku 执行失败: ${e.message}")
        }
    }

    /** 反射调用 Shizuku.newProcess（13.x 中为私有静态方法） */
    private fun createShizukuProcess(cmd: Array<String>): Process? {
        return try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, cmd, null, null) as? Process
        } catch (e: Throwable) {
            Log.w(TAG, "反射调用 Shizuku.newProcess 失败", e)
            null
        }
    }

    private fun registerInternalListener() {
        if (listenerRegistered) return
        try {
            Shizuku.addRequestPermissionResultListener(internalListener)
            listenerRegistered = true
        } catch (e: Throwable) {
            Log.w(TAG, "addRequestPermissionResultListener failed", e)
        }
    }
}