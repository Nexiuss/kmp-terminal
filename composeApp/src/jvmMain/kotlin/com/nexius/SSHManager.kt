package com.nexius

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
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
            setPtySize(180, 40, 1920, 1080)
            connect()
            shellInput = outputStream
            shellOutput = inputStream
        }

        channelSftp = (session?.openChannel("sftp") as ChannelSftp).apply {
            connect()
        }
    }

    // ====================== 普通命令执行 ======================
    suspend fun executeCommand(command: String): String = withContext(Dispatchers.IO) {
        if (shellInput == null) return@withContext "未连接 SSH"

        shellInput?.write("$command\n".toByteArray())
        shellInput?.flush()

        Thread.sleep(150)
        val buffer = ByteArray(10240)
        val output = StringBuilder()
        while (shellOutput?.available() ?: 0 > 0) {
            val len = shellOutput?.read(buffer) ?: 0
            output.append(String(buffer, 0, len))
        }

        if (command.startsWith("cd ")) {
            updateCurrentDir()
        }

        output.toString()
    }

    // ====================== 交互式实时输出（tail -f / top） ======================
    suspend fun executeInteractiveCommand(
        command: String,
        onOutput: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (shellInput == null) {
            onOutput("未连接 SSH\n")
            return@withContext
        }

        // 发送命令
        shellInput?.write("$command\n".toByteArray())
        shellInput?.flush()

        // 持续读取输出
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val available = shellOutput?.available() ?: 0
                if (available > 0) {
                    val len = shellOutput?.read(buffer) ?: 0
                    if (len <= 0) break
                    val output = String(buffer, 0, len)
                    onOutput(output)
                } else {
                    Thread.sleep(50) // 降低CPU占用
                }
            }
        } catch (_: Exception) {
        }
    }

    // ====================== ✅ Tab 自动补全（核心功能） ======================
    suspend fun tabComplete(inputCommand: String): String = withContext(Dispatchers.IO) {
        try {
            // 清空残留输出
            clearOutputBuffer()

            // 发送：输入内容 + 两次Tab（Linux 标准补全）
            val tabCommand = "$inputCommand\t\t"
            shellInput?.write(tabCommand.toByteArray())
            shellInput?.flush()

            Thread.sleep(200)

            // 读取补全输出
            val buffer = ByteArray(8192)
            val output = StringBuilder()
            while ((shellOutput?.available() ?: 0) > 0) {
                val len = shellOutput?.read(buffer)
                output.append(String(buffer, 0, len!!))
            }

            val completionText = output.toString()
            return@withContext parseBestCompletion(inputCommand, completionText)

        } catch (e: Exception) {
            inputCommand
        }
    }

    // 解析最优补全结果
    private fun parseBestCompletion(original: String, completionText: String): String {
        val lines = completionText.lines()
            .filter { it.isNotBlank() }
            .map { it.trim() }
            .filter { !it.contains("#") && !it.contains("$") && !it.startsWith("\u001B") }

        if (lines.isEmpty()) return original

        // 提取公共前缀（自动补全逻辑）
        val firstCandidate = lines.firstOrNull() ?: return original
        val lastSpace = original.lastIndexOf(' ')
        val prefix = if (lastSpace == -1) "" else original.substring(0, lastSpace + 1)
        val partial = if (lastSpace == -1) original else original.substring(lastSpace + 1)

        val best = if (firstCandidate.startsWith(partial)) firstCandidate else partial
        return "$prefix$best"
    }

    // 清空输出脏数据
    private fun clearOutputBuffer() {
        try {
            while ((shellOutput?.available() ?: 0) > 0) {
                val buf = ByteArray(2048)
                shellOutput?.read(buf)
            }
        } catch (_: Exception) {
        }
    }

    // ====================== 更新当前目录 ======================
    private suspend fun updateCurrentDir() = withContext(Dispatchers.IO) {
        try {
            clearOutputBuffer()
            shellInput?.write("pwd\n".toByteArray())
            shellInput?.flush()
            Thread.sleep(180)

            val buffer = ByteArray(1024)
            val output = StringBuilder()
            while ((shellOutput?.available() ?: 0) > 0) {
                val len = shellOutput?.read(buffer)
                output.append(String(buffer, 0, len!!))
            }

            val lines = output.lines().filter { it.isNotBlank() }
            val path = lines.lastOrNull {
                it.startsWith("/") && !it.contains("#") && !it.contains("$") && !it.contains("@")
            } ?: "/"

            currentRemoteDir = path
            channelSftp?.cd(currentRemoteDir)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ====================== SFTP 上传下载 ======================
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