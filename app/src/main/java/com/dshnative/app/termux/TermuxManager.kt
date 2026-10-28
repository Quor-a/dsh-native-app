package com.dshnative.app.termux

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * 内置 Termux 终端环境管理。
 * bootstrap 打包在 assets/termux-bootstrap.tar.gzdata（gzip 压缩的 tar，data/data/com.termux/files/usr 结构），
 * 首次运行解压到 filesDir，通过 proot 模拟 root 运行 bash，无需 root/Shizuku。
 * 注意：AGP 的资产合并器会把扩展名为 .gz 的文件自动解压成 .tar 再打包进 APK，
 * 因此这里用 .gzdata 扩展名存放 gzip 数据，避免被打包工具解压。
 */
object TermuxManager {

    private const val ASSET = "termux-bootstrap.tar.gzdata"
    /** 相对 filesDir 的 Termux 用户态根 */
    const val REL_PREFIX = "data/data/com.termux/files/usr"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var ready: Boolean = false
        private set

    @Volatile
    var extracting: Boolean = false
        private set

    /** 最近一次初始化/解压失败的具体原因（供 UI 展示） */
    @Volatile
    var lastError: String? = null
        private set

    /** 初始化：必须先从 Application/Activity 调用一次 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun prefixDir(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, REL_PREFIX)
    }

    /** 是否已解压完成（bash 存在） */
    fun isExtracted(): Boolean {
        val dir = prefixDir() ?: return false
        return File(dir, "bin/bash").exists()
    }

    /** 解压 bootstrap（异步调用，不要在主线程做） */
    fun ensureExtracted(context: Context): Boolean {
        if (isExtracted()) {
            ready = true
            return true
        }
        if (extracting) return false
        extracting = true
        return try {
            val filesDir = context.filesDir
            val usr = File(filesDir, REL_PREFIX)
            // 解压前检查可用空间：解压后约 300MB，要求可用 > 500MB
            val neededMb = 500L
            val usableMb = filesDir.usableSpace / (1024 * 1024)
            if (usableMb < neededMb) {
                lastError = "存储空间不足：解压 Termux 需要约 ${neededMb}MB，当前可用 ${usableMb}MB。请清理后重试。"
                ready = false
                false
            } else {
                val tmpTar = File(filesDir, "termux-bootstrap-tmp")
                try {
                    // 打包工具不会解压 .gzdata，因此首选 ASSET；同时兼容历史 APK 里的
                    // .tar（AGP 自动解压产物）和 .tar.gz 名字。
                    val assetName = try {
                        val names = context.assets.list("").orEmpty()
                        when {
                            names.contains(ASSET) -> ASSET
                            names.contains("termux-bootstrap.tar.gz") -> "termux-bootstrap.tar.gz"
                            names.contains("termux-bootstrap.tar") -> "termux-bootstrap.tar"
                            else -> null
                        }
                    } catch (_: Exception) {
                        ASSET
                    }
                    if (assetName == null) {
                        lastError = "解压失败：assets 中找不到 Termux bootstrap"
                        ready = false
                        false
                    } else {
                        context.assets.open(assetName).use { input ->
                            tmpTar.outputStream().use { out -> input.copyTo(out) }
                        }
                        // 检测是否为 gzip（魔数 1f 8b），兼容 .tar.gz 与已解压的 .tar
                        val isGzip = tmpTar.inputStream().use { ins ->
                            val b1 = ins.read()
                            val b2 = ins.read()
                            b1 == 0x1f && b2 == 0x8b
                        }
                        if (isGzip) {
                            GZIPInputStream(tmpTar.inputStream()).use { gz ->
                                extractTar(gz, filesDir)
                            }
                        } else {
                            tmpTar.inputStream().use { raw ->
                                extractTar(raw, filesDir)
                            }
                        }
                        tmpTar.delete()
                        ready = true
                        lastError = null
                        true
                    }
                } catch (e: Exception) {
                    // 解压失败：清理残留，下次重试
                    try { tmpTar.delete() } catch (_: Exception) {}
                    try { usr.deleteRecursively() } catch (_: Exception) {}
                    lastError = "解压失败：${e.javaClass.simpleName}${e.message?.let { "：$it" } ?: ""}"
                    ready = false
                    false
                }
            }
        } catch (e: Exception) {
            lastError = "初始化失败：${e.message ?: "未知错误"}"
            ready = false
            false
        } finally {
            extracting = false
        }
    }

    /**
     * 在 Termux 环境中执行命令（proot -0 模拟 root）。
     * @return JSON 字符串 {exit, stdout, stderr}
     */
    fun exec(command: String, timeoutSeconds: Int = 60): String {
        val ctx = appContext ?: return """{"error":"Termux 未初始化"}"""
        if (!ready && !isExtracted()) {
            return """{"error":"Termux 尚未解压完成${lastError?.let { "：$it" } ?: ""}"}"""
        }
        ready = true
        val filesDir = ctx.filesDir
        val usr = File(filesDir, REL_PREFIX)
        val proot = File(usr, "bin/proot")
        val bash = File(usr, "bin/bash")
        if (!proot.exists() || !bash.exists()) {
            return """{"error":"Termux bootstrap 损坏"}"""
        }
        return try {
            val cmd = listOf(
                proot.absolutePath, "-0", "-r", filesDir.absolutePath,
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", "/sdcard:/sdcard",
                "/usr/bin/env", "-i",
                "HOME=/data/data/com.termux/files/home",
                "TERM=xterm-256color",
                "PREFIX=/data/data/com.termux/files/usr",
                "LD_LIBRARY_PATH=/data/data/com.termux/files/usr/lib",
                "PATH=/data/data/com.termux/files/usr/bin",
                "TMPDIR=/data/data/com.termux/files/usr/tmp",
                bash.absolutePath, "-c", command
            )
            val proc = ProcessBuilder(cmd).start()
            proc.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()
            val exit = if (proc.isAlive) {
                proc.destroyForcibly()
                -1
            } else {
                proc.exitValue()
            }
            val sb = StringBuilder("""{"exit":$exit,"stdout":""")
            sb.append(escape(out.take(6000)))
            sb.append("""","stderr":""")
            sb.append(escape(err.take(2000)))
            sb.append("""}""")
            sb.toString()
        } catch (e: Exception) {
            """{"error":"Termux 执行失败: ${e.message}"}"""
        }
    }

    // ---------- tar 解压（GNU tar 格式，支持长文件名扩展头） ----------

    private fun extractTar(stream: InputStream, dest: File) {
        val header = ByteArray(512)
        val buf = ByteArray(64 * 1024)
        var pendingName: String? = null
        while (true) {
            val read = readFully(stream, header)
            if (read < 512) break
            if (header.all { it == 0.toByte() }) break

            val name = String(header, 0, 100, Charsets.UTF_8).trimEnd('\u0000').trimEnd(' ')
            val size = parseTarSize(String(header, 124, 12, Charsets.US_ASCII))
            val typeFlag = header[156].toInt().toChar()

            when {
                // GNU 长文件名扩展头：内容 = 下一个条目的真实路径
                typeFlag == 'L' -> {
                    val bytes = ByteArray(size.toInt())
                    readFully(stream, bytes)
                    pendingName = String(bytes, Charsets.UTF_8).trimEnd('\u0000')
                    skipFully(stream, paddingFor(size))
                }
                // 目录
                typeFlag == '5' || name.endsWith("/") -> {
                    File(dest, pendingName ?: name).mkdirs()
                    pendingName = null
                    skipFully(stream, size)
                    skipFully(stream, paddingFor(size))
                }
                // 普通文件（含符号链接等，按内容写入）
                name.isNotEmpty() -> {
                    val target = File(dest, pendingName ?: name)
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out ->
                        var remaining = size
                        while (remaining > 0) {
                            val chunk = stream.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
                            if (chunk < 0) break
                            out.write(buf, 0, chunk)
                            remaining -= chunk
                        }
                    }
                    pendingName = null
                    skipFully(stream, paddingFor(size))
                }
                else -> {
                    skipFully(stream, size)
                    skipFully(stream, paddingFor(size))
                }
            }
        }
    }

    private fun paddingFor(size: Long): Long = ((512 - (size % 512)) % 512).toLong()

    private fun readFully(stream: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = stream.read(buf, off, buf.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    private fun skipFully(stream: InputStream, n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) {
                if (stream.read() < 0) return
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun parseTarSize(field: String): Long {
        if (field.isEmpty()) return 0
        val b = field[0].code
        // GNU base-256 编码（最高位置 1）
        if (b and 0x80 != 0) {
            var value = (b and 0x7f).toLong()
            for (i in 1 until field.length) {
                value = (value shl 8) + (field[i].code and 0xff)
            }
            return value
        }
        return field.trim().toLongOrNull(8) ?: 0
    }

    private fun escape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }
}