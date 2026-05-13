package com.nexius

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SSHSftpApp() {
    val sshManager = remember { SSHManager() }
    val scope = rememberCoroutineScope()

    // 连接配置
    var host by remember { mutableStateOf("192.168.1.100") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("password") }
    var isConnected by remember { mutableStateOf(false) }

    // 终端
    var command by remember { mutableStateOf("") }
    var terminalOutput by remember { mutableStateOf("") }

    // SFTP
    var localFilePath by remember { mutableStateOf("") }
    var remoteFileName by remember { mutableStateOf("") }
    var sftpTip by remember { mutableStateOf("") }

    MaterialTheme(colors = lightColors()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.Start
        ) {
            // 1. SSH 连接区域
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                shape = RoundedCornerShape(8.dp),
                elevation = 4.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("SSH 连接配置", style = MaterialTheme.typography.h6)

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextField(
                            host, { host = it },
                            label = { Text("主机") },
                            modifier = Modifier.weight(1f)
                        )
                        TextField(
                            port, { port = it },
                            label = { Text("端口") },
                            modifier = Modifier.weight(0.3f)
                        )
                        TextField(
                            username, { username = it },
                            label = { Text("用户名") },
                            modifier = Modifier.weight(1f)
                        )
                        TextField(
                            password, { password = it },
                            label = { Text("密码") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Button(
                        onClick = {
                            if (!isConnected) {
                                try {
                                    sshManager.connect(host, port.toInt(), username, password)
                                    isConnected = true
                                    terminalOutput = "连接成功！\n"
                                } catch (e: Exception) {
                                    terminalOutput = "连接失败：${e.message}\n"
                                }
                            } else {
                                sshManager.disconnect()
                                isConnected = false
                                terminalOutput = "已断开连接\n"
                            }
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(if (isConnected) "断开连接" else "连接 SSH")
                    }
                }
            }

            // 2. 终端区域
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 8.dp),
                shape = RoundedCornerShape(8.dp),
                elevation = 4.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("远程终端", style = MaterialTheme.typography.h6)
                    Text("当前目录：${sshManager.currentRemoteDir}", color = MaterialTheme.colors.primary)

                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colors.background)
                            .verticalScroll(rememberScrollState())
                    ) {
                        AnsiText(terminalOutput)
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextField(
                            command, { command = it },
                            modifier = Modifier.weight(1f)
                                // 修复：正确判断回车键
                                .onKeyEvent { event ->
                                    if (event.key == androidx.compose.ui.input.key.Key.Enter && isConnected && command.isNotBlank()) {
                                        scope.launch {
                                            val res = sshManager.executeCommand(command)
                                            terminalOutput += "$ $command\n$res"
                                            command = ""
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                },
                            label = { Text("输入命令 (回车执行)") },
                            enabled = isConnected
                        )
                        Button(onClick = {
                            if (isConnected && command.isNotEmpty()) {
                                scope.launch {
                                    val res = sshManager.executeCommand(command)
                                    terminalOutput += res
                                    command = ""
                                }
                            }
                        }, enabled = isConnected) {
                            Text("执行")
                        }
                    }
                }
            }

            // 3. SFTP 功能区域
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                elevation = 4.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("SFTP 文件传输（同步终端目录）", style = MaterialTheme.typography.h6)
                    Text(sftpTip, color = if (sftpTip.contains("成功")) MaterialTheme.colors.primary else MaterialTheme.colors.error)

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextField(
                            localFilePath, { localFilePath = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("本地文件路径(上传) / 保存目录(下载)") },
                            enabled = isConnected
                        )
                        TextField(
                            remoteFileName, { remoteFileName = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("远程文件名") },
                            enabled = isConnected
                        )
                    }

                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (isConnected && localFilePath.isNotBlank()) {
                                scope.launch {
                                    sftpTip = sshManager.uploadFile(File(localFilePath))
                                }
                            }
                        }, enabled = isConnected, modifier = Modifier.weight(1f)) {
                            Text("上传到当前目录")
                        }

                        Button(onClick = {
                            if (isConnected && remoteFileName.isNotBlank() && localFilePath.isNotBlank()) {
                                scope.launch {
                                    sftpTip = sshManager.downloadFile(remoteFileName, localFilePath)
                                }
                            }
                        }, enabled = isConnected, modifier = Modifier.weight(1f)) {
                            Text("从当前目录下载")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AnsiText(text: String) {
    val annotated = buildAnnotatedString {
        val parts = text.split(Regex("(?=\\x1B\\[)"))
        parts.forEach { part ->
            if (part.startsWith("\u001B[")) {
                val codeEnd = part.indexOf('m')
                if (codeEnd > 0) {
                    val code = part.substring(2, codeEnd)
                    val content = part.substring(codeEnd + 1)
                    val style = when (code) {
                        "0" -> SpanStyle()
                        "1" -> SpanStyle(fontWeight = FontWeight.Bold)
                        "31" -> SpanStyle(color = Color.Red)
                        "32" -> SpanStyle(color = Color.Green)
                        "33" -> SpanStyle(color = Color.Yellow)
                        "34" -> SpanStyle(color = Color.Blue)
                        "36" -> SpanStyle(color = Color.Cyan)
                        else -> SpanStyle()
                    }
                    withStyle(style) {
                        append(content)
                    }
                }
            } else {
                append(part)
            }
        }
    }

    BasicText(text = annotated)
}