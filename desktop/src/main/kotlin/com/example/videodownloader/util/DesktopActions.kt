package com.example.videodownloader.util

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File

/** 파일 열기·탐색기 표시·클립보드 등 Windows 쪽 동작. */
object DesktopActions {

    fun open(file: File): Boolean = runCatching {
        Desktop.getDesktop().open(file)
        true
    }.getOrDefault(false)

    /** 탐색기에서 파일을 선택한 상태로 연다. 파일이 없으면 폴더만 연다. */
    fun showInFolder(file: File) {
        runCatching {
            if (file.exists()) {
                ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
            } else {
                (file.parentFile ?: file).takeIf { it.exists() }?.let { Desktop.getDesktop().open(it) }
            }
        }
    }

    fun browse(url: String) {
        runCatching { Desktop.getDesktop().browse(java.net.URI(url)) }
    }

    fun clipboardText(): String? = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()
}
