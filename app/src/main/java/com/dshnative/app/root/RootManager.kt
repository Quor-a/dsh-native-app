package com.dshnative.app.root

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Root 权限检测与执行（对应 DSH 的 root/超级用户能力）。
 *
 * 通过检查常见 su 路径 + 实际执行 `su -c id` 验证 uid=0，避免误判。
 * 所有命令在 [execAsRoot] 中通过 `su -c` 执行。
 */
object RootManager {

    data class RootResult(
        val ok: Boolean,
        val exit: Int = -1,
        val stdout: String = "",
        val stderr: String = ""
    )

    private val suCandidates = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/system/sbin/su",
        "/su/bin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/adb/magisk/su",
        "/data/adb/ksu/bin/su"
    )

    /** su 路径检测缓存（文件存在性，不会因授权状态变化而过期） */
    @Volatile
    private var cachedSuPath: String? = null

    @Volatile
    private var cacheValid: Boolean = false

    /**
     * 检测 root 是否可用：找到 su 文件且执行 `su -c id` 返回 uid=0。
     * 每次调用都会重新执行 `su -c id` 验证（授权状态变化后可立即反映）。
     */
    fun isRootAvailable(): Boolean {
        val su = findSuPath() ?: return false
        val result = execWithSu(su, "id")
        return result.ok && result.stdout.contains("uid=0")
    }

    /** 找到可用的 su 路径（无则 null） */
    fun findSuPath(): String? {
        if (cacheValid) return cachedSuPath
        val found = findSuPathInternal()
        cachedSuPath = found
        cacheValid = true
        return found
    }

    /** 清除检测缓存（设备 Root 状态变化后调用） */
    fun refresh() {
        cacheValid = false
        cachedSuPath = null
    }

    /** 以 root 执行命令（返回退出码 + 输出）。若 root 不可用返回 ok=false。 */
    fun execAsRoot(command: String): RootResult {
        val su = findSuPath() ?: return RootResult(ok = false, stderr = "未找到 su（设备无 Root）")
        return execWithSu(su, command)
    }

    /** 用指定 su 执行命令（`su -c <command>`），15 秒超时 */
    private fun execWithSu(su: String, command: String): RootResult {
        var process: Process? = null
        return try {
            process = try {
                Runtime.getRuntime().exec(arrayOf(su, "-c", command))
            } catch (_: Exception) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            }

            var stdout = ""
            var stderr = ""
            val outThread = Thread {
                try {
                    stdout = readAll(process?.inputStream)
                } catch (_: Exception) {
                }
            }
            val errThread = Thread {
                try {
                    stderr = readAll(process?.errorStream)
                } catch (_: Exception) {
                }
            }
            outThread.start()
            errThread.start()

            val finished = process.waitFor(15, TimeUnit.SECONDS)
            outThread.join(3000)
            errThread.join(3000)
            val exit = if (finished) {
                process.exitValue()
            } else {
                try {
                    process.destroy()
                } catch (_: Exception) {
                }
                -1
            }
            RootResult(ok = exit == 0, exit = exit, stdout = stdout.trim(), stderr = stderr.trim())
        } catch (e: Exception) {
            RootResult(ok = false, stderr = "su 执行失败: ${e.message}")
        } finally {
            try {
                process?.destroy()
            } catch (_: Exception) {
            }
        }
    }

    private fun findSuPathInternal(): String? {
        // 1) 固定路径探测
        for (path in suCandidates) {
            try {
                val f = File(path)
                if (f.exists() && f.isFile && f.canExecute()) return path
            } catch (_: Exception) {
            }
        }
        // 2) PATH 环境变量探测
        return try {
            val pathEnv = System.getenv("PATH") ?: ""
            val dirs = (pathEnv.split(":") + listOf("/system/bin", "/system/xbin", "/sbin")).distinct()
            for (dir in dirs) {
                if (dir.isBlank()) continue
                val f = File(dir, "su")
                if (f.exists() && f.isFile && f.canExecute()) return f.absolutePath
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun readAll(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        val reader = BufferedReader(InputStreamReader(stream), 8192)
        return try {
            val sb = StringBuilder()
            val buf = CharArray(4096)
            while (true) {
                val n = reader.read(buf)
                if (n <= 0) break
                sb.append(buf, 0, n)
            }
            sb.toString()
        } catch (_: Exception) {
            ""
        } finally {
            try {
                reader.close()
            } catch (_: Exception) {
            }
        }
    }
}