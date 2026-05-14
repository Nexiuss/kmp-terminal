package com.nexius

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SSHSftpApp() {
    val sshManager = remember { SSHManager() }
    val scope = rememberCoroutineScope()
    val terminalScrollState = rememberScrollState()

    // 连接配置
    var host by remember { mutableStateOf("192.168.1.100") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("password") }
    var isConnected by remember { mutableStateOf(false) }

    // 终端核心
    var command by remember { mutableStateOf("") }
    var terminalOutput by remember { mutableStateOf("") }
    var commandHistory by remember { mutableStateOf(listOf<String>()) }
    var historyIndex by remember { mutableIntStateOf(-1) }

    // SFTP
    var localFilePath by remember { mutableStateOf("") }
    var remoteFileName by remember { mutableStateOf("") }
    var sftpTip by remember { mutableStateOf("") }

    // 自动滚动到终端底部
    LaunchedEffect(terminalOutput) {
        terminalScrollState.animateScrollTo(terminalScrollState.maxValue)
    }

    MaterialTheme(colors = lightColors()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.Top
        ) {
            // 顶部连接栏
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                elevation = 3.dp
            ) {
                Row(
                    modifier = Modifier.padding(10.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(host, { host = it }, label = { Text("主机") }, modifier = Modifier.weight(1f), singleLine = true)
                    TextField(port, { port = it }, label = { Text("端口") }, modifier = Modifier.weight(0.4f), singleLine = true)
                    TextField(username, { username = it }, label = { Text("用户") }, modifier = Modifier.weight(1f), singleLine = true)
                    TextField(password, { password = it }, label = { Text("密码") }, modifier = Modifier.weight(1f), singleLine = true)

                    Button(
                        onClick = {
                            scope.launch {
                                if (!isConnected) {
                                    try {
                                        sshManager.connect(host, port.toInt(), username, password)
                                        isConnected = true
                                        terminalOutput = "✅ 连接成功！当前目录：${sshManager.currentRemoteDir}\n"
                                    } catch (e: Exception) {
                                        terminalOutput = "❌ 连接失败：${e.message}\n"
                                    }
                                } else {
                                    sshManager.disconnect()
                                    isConnected = false
                                    terminalOutput += "\n🔌 已断开连接\n"
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (isConnected) Color(0xFFE53935) else Color(0xFF43A047)
                        )
                    ) {
                        Text(if (isConnected) "断开" else "连接")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 终端区域（输出 + 输入 一体化）
            Card(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                elevation = 4.dp
            ) {
                Column(Modifier.fillMaxSize().padding(8.dp)) {
                    Text(
                        "终端 - ${sshManager.currentRemoteDir}",
                        style = MaterialTheme.typography.subtitle1,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.primary
                    )

                    // 终端输出框
                    Box(
                        modifier = Modifier.weight(1f)
                            .fillMaxWidth()
                            .background(Color(0xFF1A1A1A))
                            .verticalScroll(terminalScrollState)
                            .padding(8.dp)
                    ) {
                        AnsiText(terminalOutput)
                    }

                    Spacer(Modifier.height(6.dp))

                    // 命令输入框（同屏、支持回车、Tab补全、上下箭头历史）
                    TextField(
                        value = command,
                        onValueChange = { command = it },
                        modifier = Modifier.fillMaxWidth()
                            .background(Color(0xFF262626))
                            .onKeyEvent { keyEvent ->
                                if (!isConnected) return@onKeyEvent false
                                val executeCmd = command
                                when {
                                    // 回车执行命令
                                    keyEvent.key == Key.Enter && keyEvent.type == KeyEventType.KeyUp -> {

                                        if (executeCmd.isNotBlank()) {
                                            command = ""
                                            scope.launch {
                                                executeInteractiveCommand(
                                                    command = executeCmd,
                                                    sshManager = sshManager,
                                                    onOutput = { terminalOutput += it },
                                                    onComplete = {
                                                        commandHistory = commandHistory + executeCmd
                                                        historyIndex = commandHistory.size
                                                    }
                                                )
                                            }

                                        }
                                        true
                                    }

                                    // Tab 自动补全路径
                                    keyEvent.key == Key.Tab && keyEvent.type == KeyEventType.KeyUp -> {
                                        scope.launch {
                                            val completed = sshManager.tabComplete(executeCmd)
                                            if (completed.isNotEmpty()) command = completed
                                        }
                                        true
                                    }

                                    // 上箭头 历史命令
                                    keyEvent.key == Key.DirectionUp && keyEvent.type == KeyEventType.KeyUp -> {
                                        if (historyIndex > 0) {
                                            historyIndex--
                                            command = commandHistory[historyIndex]
                                        }
                                        true
                                    }

                                    // 下箭头 历史命令
                                    keyEvent.key == Key.DirectionDown && keyEvent.type == KeyEventType.KeyUp -> {
                                        if (historyIndex < commandHistory.size - 1) {
                                            historyIndex++
                                            command = commandHistory[historyIndex]
                                        } else {
                                            command = ""
                                            historyIndex = commandHistory.size
                                        }
                                        true
                                    }

                                    else -> false
                                }
                            },
                        enabled = isConnected,
                        placeholder = { Text("输入命令，回车执行｜Tab 补全｜↑↓ 历史", color = Color.Gray) },
                        singleLine = true,
                        colors = TextFieldDefaults.textFieldColors(
                            textColor = Color.White,
                            cursorColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // SFTP 文件传输
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), elevation = 3.dp) {
                Column(Modifier.padding(10.dp)) {
                    Text("SFTP 文件传输", fontWeight = FontWeight.Bold)
                    Text(sftpTip, color = if (sftpTip.contains("成功")) Color.Green else Color.Red, maxLines = 1)

                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextField(localFilePath, { localFilePath = it }, modifier = Modifier.weight(1f), label = { Text("本地路径") }, enabled = isConnected, singleLine = true)
                        TextField(remoteFileName, { remoteFileName = it }, modifier = Modifier.weight(1f), label = { Text("远程文件") }, enabled = isConnected, singleLine = true)
                    }

                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = {
                            scope.launch {
                                sftpTip = sshManager.uploadFile(File(localFilePath))
                            }
                        }, modifier = Modifier.weight(1f), enabled = isConnected) {
                            Text("上传")
                        }

                        Button(onClick = {
                            scope.launch {
                                sftpTip = sshManager.downloadFile(remoteFileName, localFilePath)
                            }
                        }, modifier = Modifier.weight(1f), enabled = isConnected) {
                            Text("下载")
                        }
                    }
                }
            }
        }
    }
}

// 交互式命令执行（支持 tail -f / top / 实时输出）
suspend fun executeInteractiveCommand(
    command: String,
    sshManager: SSHManager,
    onOutput: (String) -> Unit,
    onComplete: () -> Unit
) {
    onOutput("\n$ $command\n")
    sshManager.executeInteractiveCommand(command, onOutput)
    onComplete()
}

@Composable
fun AnsiText(
    text: String,
    modifier: Modifier = Modifier
) {
    val annotated = remember(text) { ansiToAnnotatedString(text) }

    // 支持复制
    SelectionContainer {
        BasicText(
            text = annotated,
            modifier = modifier.fillMaxWidth(),
            maxLines = Int.MAX_VALUE,
            overflow = TextOverflow.Clip,
            style = MaterialTheme.typography.body1.copy(fontSize = 14.sp)
        )
    }
}

private fun ansiToAnnotatedString(text: String): AnnotatedString {
    val regex = Regex("""\u001B\[(\d+(;\d+)*)m""")
    val builder = AnnotatedString.Builder()
    var currStyle = AnsiSpanStyle()
    var lastPos = 0

    for (match in regex.findAll(text)) {
        val plain = text.substring(lastPos, match.range.first)
        if (plain.isNotEmpty()) {
            builder.withStyle(currStyle.toSpanStyle()) { append(plain) }
        }
        val codes = match.groupValues[1].split(";").mapNotNull { it.toIntOrNull() }
        currStyle = currStyle.applyCodes(codes)
        lastPos = match.range.last + 1
    }
    val remain = text.substring(lastPos)
    if (remain.isNotEmpty()) {
        builder.withStyle(currStyle.toSpanStyle()) { append(remain) }
    }
    return builder.toAnnotatedString()
}

// ANSI 样式数据类及解析
private data class AnsiSpanStyle(
    val bold: Boolean = false,
    val color: Color = Color.Unspecified
) {
    fun toSpanStyle(): SpanStyle = SpanStyle(
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = if (color != Color.Unspecified) color else Color.White
    )

    fun applyCodes(codes: List<Int>): AnsiSpanStyle {
        var nextBold = bold
        var nextColor = color

        for (c in codes) {
            when (c) {
                0  -> { nextBold = false; nextColor = Color.Unspecified } // Reset
                1  -> nextBold = true
                in 30..37 -> nextColor = ansiColorTable[c] ?: Color.Unspecified
                in 90..97 -> nextColor = ansiBrightColorTable[c] ?: Color.Unspecified
                39 -> nextColor = Color.Unspecified // Default color
                else -> {} // 留给扩展
            }
        }
        return AnsiSpanStyle(bold = nextBold, color = nextColor)
    }

    companion object {
        // 常规前景色
        val ansiColorTable = mapOf(
            30 to Color(0xFF000000), // Black
            31 to Color(0xFFFF5252), // Red
            32 to Color(0xFF69F0AE), // Green
            33 to Color(0xFFFFD740), // Yellow
            34 to Color(0xFF40C4FF), // Blue
            35 to Color(0xFFEA80FC), // Magenta
            36 to Color(0xFF18FFFF), // Cyan
            37 to Color(0xFFFFFFFF)  // White
        )
        // 亮色
        val ansiBrightColorTable = mapOf(
            90 to Color(0xFF888888), // Bright Black
            91 to Color(0xFFFF8A80), // Bright Red
            92 to Color(0xFFB9F6CA), // Bright Green
            93 to Color(0xFFFFFF8D), // Bright Yellow
            94 to Color(0xFF80D8FF), // Bright Blue
            95 to Color(0xFFFF80AB), // Bright Magenta
            96 to Color(0xFF84FFFF), // Bright Cyan
            97 to Color(0xFFFFFFFF)  // Bright White
        )
    }
}