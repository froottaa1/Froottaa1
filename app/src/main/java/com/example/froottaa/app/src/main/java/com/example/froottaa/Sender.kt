package com.example.froottaa

import java.io.*
import java.net.Socket

class Sender(
    private val host: String,
    private val pin: String,
    private val files: List<File>,
    private val rootDir: File,
    private val ui: Ui
) {
    @Volatile var cancelled = false

    fun run() {
        try {
            ui.status("جاري الاتصال بـ $host...")
            val socket = Socket(host, 8888)
            val dis = DataInputStream(socket.getInputStream())
            val dos = DataOutputStream(socket.getOutputStream())

            dos.writeUTF(pin)
            dos.flush()

            val response = dis.readUTF()
            if (response != "OK") {
                ui.status("رمز PIN غير صحيح!")
                socket.close()
                return
            }

            val totalBytesAll = files.sumOf { it.length() }
            dos.writeInt(files.size)
            dos.writeLong(totalBytesAll)
            dos.flush()

            var totalBytesSent = 0L

            for (file in files) {
                if (cancelled) break
                val relativePath = file.relativeTo(rootDir).path.replace("\\", "/")
                dos.writeUTF(relativePath)
                dos.writeLong(file.length())
                dos.flush()

                val fis = FileInputStream(file)
                val buffer = ByteArray(8192)
                var bytesRead: Int

                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    if (cancelled) break
                    dos.write(buffer, 0, bytesRead)
                    totalBytesSent += bytesRead
                    ui.progress(totalBytesSent, totalBytesAll)
                }
                fis.close()
            }

            if (!cancelled) {
                val finalResp = dis.readUTF()
                if (finalResp == "SUCCESS") {
                    ui.status("تم إرسال الملفات بنجاح! ✅")
                }
            } else {
                ui.status("تم إيقاف الإرسال.")
            }
            socket.close()
        } catch (e: Exception) {
            if (!cancelled) {
                ui.status("فشل الإرسال: ${e.message}")
            }
        }
    }
}
