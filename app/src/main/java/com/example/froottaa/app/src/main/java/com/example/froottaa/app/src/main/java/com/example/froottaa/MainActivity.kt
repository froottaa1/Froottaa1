package com.example.froottaa

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var bar: ProgressBar
    private lateinit var ipInput: EditText
    private lateinit var pinInput: EditText
    private lateinit var cbMedia: CheckBox
    private lateinit var cbAudio: CheckBox
    private lateinit var cbOther: CheckBox
    private var receiver: Receiver? = null
    @Volatile private var sender: Sender? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val ui = object : Ui {
        override fun status(msg: String) { runOnUiThread { status.text = msg } }
        override fun progress(done: Long, total: Long) {
            runOnUiThread {
                bar.max = 1000
                bar.progress = if (total > 0) (done * 1000 / total).toInt() else 1000
                info.text = "${done shr 20} MB / ${total shr 20} MB"
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        fun label(t: String, size: Float = 16f) = TextView(this).apply { text = t; textSize = size }.also { col.addView(it) }
        fun button(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }.also { col.addView(it) }

        label("نقل الهاتف (PhoneTransfer)", 24f)
        label("1) على الجهاز المستقبل:")
        button("📥 استقبال") { startReceive() }
        label("2) على الجهاز المرسل:")
        ipInput = EditText(this).apply { hint = "IP الجهاز المستقبل"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }.also { col.addView(it) }
        pinInput = EditText(this).apply { hint = "PIN"; inputType = InputType.TYPE_CLASS_NUMBER }.also { col.addView(it) }
        cbMedia = CheckBox(this).apply { text = "صور وفيديو"; isChecked = true }.also { col.addView(it) }
        cbAudio = CheckBox(this).apply { text = "موسيقى وصوتيات"; isChecked = true }.also { col.addView(it) }
        cbOther = CheckBox(this).apply { text = "باقي الملفات (مستندات، PDF، APK...)"; isChecked = true }.also { col.addView(it) }
        button("📤 إرسال") { startSend() }
        button("⏹ إيقاف") { sender?.cancelled = true; receiver?.stop(); receiver = null; ui.status("تم الإيقاف") }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).also { col.addView(it) }
        info = label("")
        status = label("جاهز")

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun hasStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun ensureStorage(): Boolean {
        if (hasStorage()) return true
        Toast.makeText(this, "اسمح بالوصول لكل الملفات ثم ارجع واضغط تاني", Toast.LENGTH_LONG).show()
        if (Build.VERSION.SDK_INT >= 30) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
        return false
    }

    private fun lock(on: Boolean) {
        if (on) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonetransfer:wl").apply { acquire(6 * 60 * 60 * 1000L) }
        } else wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun localIps(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    } catch (e: Exception) { emptyList() }

    private fun startReceive() {
        if (!ensureStorage()) return
        receiver?.stop()
        val pin = (1000..9999).random().toString()
        receiver = Receiver(Environment.getExternalStorageDirectory(), pin, ui).also { it.start() }
        lock(true)
        val ips = localIps().joinToString("\n").ifEmpty { "(وصّل الواي فاي أو نقطة الاتصال)" }
        ui.status("في انتظار المرسل...\nIP:\n$ips\nPIN: $pin\n\nاكتبهم في الجهاز المرسل.")
    }

    private val mediaExt = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng", "mp4", "mkv", "mov", "avi", "3gp", "webm", "m4v")
    private val audioExt = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr")

    private fun scan(root: File, media: Boolean, audio: Boolean, other: Boolean): List<File> =
        root.walkTopDown()
            .onEnter { d -> !(d.parentFile == root && d.name == "Android") && !d.name.startsWith(".") }
            .filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".part") }
            .filter {
                val e = it.extension.lowercase()
                when {
                    e in mediaExt -> media
                    e in audioExt -> audio
                    else -> other
                }
            }.toList()

    private fun startSend() {
        if (!ensureStorage()) return
        val host = ipInput.text.toString().trim()
        val pin = pinInput.text.toString().trim()
        if (host.isEmpty() || pin.isEmpty()) { Toast.makeText(this, "اكتب IP و PIN", Toast.LENGTH_SHORT).show(); return }
        val m = cbMedia.isChecked; val a = cbAudio.isChecked; val o = cbOther.isChecked
        lock(true)
        thread {
            ui.status("🔍 جاري فحص الملفات...")
            val root = Environment.getExternalStorageDirectory()
            val files = scan(root, m, a, o)
            if (files.isEmpty()) { ui.status("مفيش ملفات للإرسال"); lock(false); return@thread }
            ui.status("📤 ${files.size} ملف، جاري الإرسال...")
            val s = Sender(host, pin, files, root, ui)
            sender = s
            s.run()
            lock(false)
        }
    }

    override fun onDestroy() { receiver?.stop(); lock(false); super.onDestroy() }
}
