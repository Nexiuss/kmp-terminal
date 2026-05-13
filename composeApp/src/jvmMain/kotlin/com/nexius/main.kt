package com.nexius

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "KMP SSH 终端 + SFTP") {
        SSHSftpApp()
    }
}