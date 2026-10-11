package com.example.videodownloader.util

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import kotlin.concurrent.thread

/**
 * 앱을 하나만 실행한다. 이미 실행 중이면 그 앱에 "창 보이기(+열 주소)"를 전하고 새 프로세스는 바로 끝낸다.
 * 잠금 파일로 실행 여부를 알고, 실행 중인 앱은 이 PC 안(127.0.0.1)에서만 받는 포트로 요청을 듣는다.
 */
class SingleInstance(dir: File) {
    private val lockFile = File(dir, "instance.lock")
    private val portFile = File(dir, "instance.port")
    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    /** 처음 실행이면 true. 이미 실행 중이면 [args] 를 전달하고 false. */
    fun acquire(args: List<String>): Boolean {
        lockFile.parentFile?.mkdirs()
        val channel = RandomAccessFile(lockFile, "rw").channel
        lock = runCatching { channel.tryLock() }.getOrNull()
        if (lock != null) return true
        channel.close()
        runCatching {
            val port = portFile.readText().trim().toInt()
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.getOutputStream().bufferedWriter().use { out ->
                    out.write((listOf(TOKEN) + args).joinToString("\n"))
                }
            }
        }
        return false
    }

    /** 다른 실행에서 온 요청(열 주소 목록, 비어 있으면 창만 보이기)을 [onRequest] 로 넘긴다. */
    fun listen(onRequest: (List<String>) -> Unit) {
        val socket = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        server = socket
        portFile.writeText(socket.localPort.toString())
        thread(isDaemon = true, name = "single-instance") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching {
                    client.use { c ->
                        val lines = c.getInputStream().bufferedReader().readLines()
                        if (lines.firstOrNull() == TOKEN) onRequest(lines.drop(1))
                    }
                }
            }
        }
    }

    fun release() {
        runCatching { server?.close() }
        runCatching { lock?.release() }
        portFile.delete()
    }

    private companion object {
        const val TOKEN = "videodownloader-show"
    }
}
