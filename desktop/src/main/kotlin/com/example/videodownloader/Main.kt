package com.example.videodownloader

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.example.videodownloader.ui.App
import com.example.videodownloader.ui.AppIcon
import kotlin.system.exitProcess

fun main() {
    // 한글 파일명·경로가 깨지지 않도록 시작할 때 고정한다.
    System.setProperty("file.encoding", "UTF-8")
    val container = AppContainer()
    Runtime.getRuntime().addShutdownHook(Thread { container.store.flush() })

    application {
        val settings by container.settings.settings.collectAsState()
        var visible by remember { mutableStateOf(true) }
        val trayState = rememberTrayState()
        val icon = rememberVectorPainter(AppIcon)
        val windowState = rememberWindowState(size = DpSize(1100.dp, 760.dp), position = WindowPosition.PlatformDefault)

        fun quit() {
            container.shutdown()
            exitApplication()
            exitProcess(0)
        }

        LaunchedEffect(Unit) {
            container.downloads.completed.collect { item ->
                if (container.settings.current().notifyOnComplete) {
                    trayState.sendNotification(Notification("다운로드 완료", item.fileName, Notification.Type.Info))
                }
            }
        }

        Tray(
            icon = icon,
            state = trayState,
            tooltip = "동영상 다운로더",
            onAction = { visible = true },
            menu = {
                Item("열기", onClick = { visible = true })
                Item("모두 일시정지", onClick = { container.downloads.pauseAll() })
                Separator()
                Item("종료", onClick = ::quit)
            },
        )

        Window(
            onCloseRequest = { if (settings.minimizeToTray) visible = false else quit() },
            visible = visible,
            state = windowState,
            title = "동영상 다운로더",
            icon = icon,
        ) {
            App(container)
        }
    }
}
