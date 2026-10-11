package com.example.videodownloader

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
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
import com.example.videodownloader.util.SingleInstance
import com.example.videodownloader.util.Storage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // 한글 파일명·경로가 깨지지 않도록 시작할 때 고정한다.
    System.setProperty("file.encoding", "UTF-8")
    // 웹페이지(Chromium)는 OS 창이라 Compose 팝업(다운로드 버튼·메뉴)을 별도 창으로 띄워야 그 위에 보인다.
    if (System.getProperty("compose.layers.type") == null) System.setProperty("compose.layers.type", "WINDOW")
    // Direct3D 렌더러는 투명한 팝업 창(다운로드 버튼)을 그리다 충돌한다. OpenGL 로 그린다.
    if (System.getProperty("skiko.renderApi") == null) System.setProperty("skiko.renderApi", "OPENGL")

    // 이미 실행 중이면 그 창을 앞으로 가져오고(주소가 있으면 새 탭으로 열고) 이 프로세스는 끝낸다.
    val startUrls = args.filter { it.startsWith("http://") || it.startsWith("https://") }
    val instance = SingleInstance(Storage().appDir)
    if (!instance.acquire(startUrls)) exitProcess(0)

    val container = AppContainer()
    // VideoDownloader.exe <주소> 로 실행하면 내장 브라우저에서 그 주소를 연다.
    startUrls.firstOrNull()?.let(container.browser::openOnStart)
    Runtime.getRuntime().addShutdownHook(Thread { container.store.flush() })

    application {
        val settings by container.settings.settings.collectAsState()
        var visible by remember { mutableStateOf(true) }
        var composeWindow by remember { mutableStateOf<ComposeWindow?>(null) }
        val trayState = rememberTrayState()
        val icon = rememberVectorPainter(AppIcon)
        val windowState = rememberWindowState(size = DpSize(1100.dp, 760.dp), position = WindowPosition.PlatformDefault)

        fun quit() {
            // 내장 브라우저 정리가 멈춰도 프로세스가 남지 않도록 5초 뒤에는 강제로 끝낸다.
            Thread { Thread.sleep(5_000); Runtime.getRuntime().halt(0) }.apply { isDaemon = true }.start()
            runCatching { container.shutdown() }
            instance.release()
            exitApplication()
            exitProcess(0)
        }

        fun hasWork(): Boolean =
            container.downloads.downloads.value.any { it.status.isActive } || container.subtitles.jobs.value.isNotEmpty()

        LaunchedEffect(Unit) {
            instance.listen { urls ->
                SwingUtilities.invokeLater {
                    visible = true
                    windowState.isMinimized = false
                    urls.forEach { container.browser.newTab(it) }
                    composeWindow?.let { it.toFront(); it.requestFocus() }
                }
            }
        }

        LaunchedEffect(Unit) {
            container.downloads.completed.collect { item ->
                if (container.settings.current().notifyOnComplete) {
                    trayState.sendNotification(Notification("다운로드 완료", item.fileName, Notification.Type.Info))
                }
            }
        }

        // 트레이로 내려간 동안 다운로드·자막 작업이 모두 끝나면 앱을 완전히 종료해 메모리를 돌려준다.
        LaunchedEffect(visible) {
            if (visible) return@LaunchedEffect
            while (true) {
                combine(container.downloads.downloads, container.subtitles.jobs) { downloads, jobs ->
                    downloads.any { it.status.isActive } || jobs.isNotEmpty()
                }.first { busy -> !busy }
                delay(3_000) // 대기열의 다음 작업이 바로 시작되는 경우를 기다린다.
                if (!hasWork()) quit()
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
            onCloseRequest = {
                // 받는 중일 때만 트레이에서 마저 받고, 끝나면 스스로 종료한다. 할 일이 없으면 바로 종료한다.
                if (settings.minimizeToTray && hasWork()) {
                    visible = false
                    trayState.sendNotification(
                        Notification(
                            "동영상 다운로더",
                            "다운로드가 끝나면 자동으로 종료합니다. 바로 끝내려면 트레이 아이콘에서 「종료」를 누르세요.",
                            Notification.Type.Info,
                        ),
                    )
                } else {
                    quit()
                }
            },
            visible = visible,
            state = windowState,
            title = "동영상 다운로더",
            icon = icon,
        ) {
            LaunchedEffect(Unit) {
                composeWindow = window
                // 창을 옮기면 웹페이지의 드롭다운 등 팝업 위치를 맞춘다.
                window.addComponentListener(object : java.awt.event.ComponentAdapter() {
                    override fun componentMoved(e: java.awt.event.ComponentEvent) = container.browser.notifyWindowMoved()
                })
            }
            App(container)
        }
    }
}
