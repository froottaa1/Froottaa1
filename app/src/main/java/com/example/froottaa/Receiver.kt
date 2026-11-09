package com.example.froottaa

import java.io.*
import java.net.ServerSocket
import kotlin.concurrent.thread

class Receiver(private val rootDir: File, private val expectedPin: String, private val ui: Ui) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = true

    fun start() {
        thread {
            try {
                serverSocket = ServerSocket(8888)
                ui.status("في انتظار الاتصال...")
                while (running) {
                    val socket = serverSocket?.accept() ?: break
                    try {
                        val dis = DataInputStream(socket.getInputStream())
                        val dos = DataOutputStream(socket.getOutputStream())

                        val pin = dis.readUTF()
                        if (pin != expectedPin) {
                            dos.writeUTF("REJECT")
                            dos.flush()
                            socket.close()
                            continue
                        } else {
                            dos.writeUTF("OK")
                            dos.flush()
                        }

                        val totalFiles = dis.readInt()
                        var totalBytesTransferred = 0L
                        val totalBytesAll = dis.readLong()

                        for (i in 0 until totalFiles) {
                            if (!running) break
                            val relativePath = dis.readUTF()
                            val fileSize = dis.readLong()

                            val outFile = File(rootDir, relativePath)
                            outFile.parentFile?.mkdirs()

                            val fos = FileOutputStream(outFile)
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var fileBytesRead = 0L

                            while (fileBytesRead < fileSize) {
                                val toRead = minOf(buffer.size.toLong(), fileSize - fileBytesRead).toInt()
                                bytesRead = dis.read(buffer, 0, toRead)
                                if (bytesRead == -1) break
                                fos.write(buffer, 0, bytesRead)
                                fileBytesRead += bytesRead
                                totalBytesTransferred += bytesRead
                                ui.progress(totalBytesTransferred, totalBytesAll)
                            }
                            fos.close()
                        }
                        dos.writeUTF("SUCCESS")
                        dos.flush()
                        ui.status("تم استقبال جميع الملفات بنجاح!")
                    } catch (e: Exception) {
                        if (running) ui.status("خطأ أثناء الاستقبال: ${e.message}")
                    } finally {
                        socket.close()
                    }
                }
            } catch (e: Exception) {
                if (running) ui.status("خطأ في الخادم: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
    }
}
