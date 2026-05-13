package com.nexius

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.ChannelShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class SSHManager {
    private val jsch = JSch()
    private var session: com.jcraft.jsch.Session? = null
    private var channelShell: ChannelShell? = null
    private var channelSftp: ChannelSftp? = null

    private var shellInput: OutputStream? = null
    private var shellOutput: InputStream? = null

    var currentRemoteDir: String = "/"
        private set

    fun connect(
        host: String,
        port: Int = 22,
        username: String,
        password: String
    ) {
        session = jsch.getSession(username, host, port).apply {
            setPassword(password)
            setConfig("StrictHostKeyChecking", "no")
            connect(3000)
        }

        channelShell = (session?.openChannel("shell") as ChannelShell).apply {
            setPtySize(80, 24, 800, 600)
            connect()
            shellInput = outputStream
            shellOutput = inputStream
        }

        channelSftp = (session?.openChannel("sftp") as ChannelSftp).apply {
            connect()
        }
    }

    suspend fun executeCommand(command: String): String = withContext(Dispatchers.IO) {
        if (shellInput == null) return@withContext "未连接 SSH"

        shellInput?.write("$command\n".toByteArray())
        shellInput?.flush()

        Thread.sleep(150) // 稳定等待输出
        val buffer = ByteArray(10240)
        val output = StringBuilder()
        while (shellOutput?.available() ?: 0 > 0) {
            val len = shellOutput?.read(buffer) ?: 0
            output.append(String(buffer, 0, len))
        }

        // 修复：只在 cd 后更新目录，避免 ls 等命令污染解析
        if (command.startsWith("cd ")) {
            updateCurrentDir()
        }

        output.toString()
    }

    // 核心修复：干净解析 pwd 路径
    private suspend fun updateCurrentDir() = withContext(Dispatchers.IO) {
        try {
            // 清空脏输出
            while (shellOutput?.available() ?: 0 > 0) {
                val buf = ByteArray(1024)
                shellOutput?.read(buf)
            }

            // 执行纯 pwd 命令
            shellInput?.write("pwd\n".toByteArray())
            shellInput?.flush()
            Thread.sleep(150)

            val buffer = ByteArray(1024)
            val output = StringBuilder()
            while ((shellOutput?.available() ?: 0) > 0) {
                val len = shellOutput?.read(buffer)
                output.append(String(buffer, 0, len!!))
            }

            // 超强清洗：只保留真正路径
            val lines = output.lines().filter { it.isNotBlank() }
            val path = lines.lastOrNull {
                it.startsWith("/") && !it.contains("#") && !it.contains("$") && !it.contains("@")
            } ?: "/"

            currentRemoteDir = path
            channelSftp?.cd(currentRemoteDir)
        } catch (e: Exception) {
            // 异常不崩溃
            e.printStackTrace()
        }
    }

    suspend fun uploadFile(localFile: File): String = withContext(Dispatchers.IO) {
        try {
            val remotePath = "$currentRemoteDir/${localFile.name}"
            channelSftp?.put(localFile.absolutePath, remotePath)
            "上传成功：$remotePath"
        } catch (e: Exception) {
            "上传失败：${e.message}"
        }
    }

    suspend fun downloadFile(
        remoteFileName: String,
        localSavePath: String
    ): String = withContext(Dispatchers.IO) {
        try {
            val remotePath = "$currentRemoteDir/$remoteFileName"
            channelSftp?.get(remotePath, localSavePath)
            "下载成功：$localSavePath/$remoteFileName"
        } catch (e: Exception) {
            "下载失败：${e.message}"
        }
    }

    fun disconnect() {
        shellInput?.close()
        shellOutput?.close()
        channelShell?.disconnect()
        channelSftp?.disconnect()
        session?.disconnect()
    }
}