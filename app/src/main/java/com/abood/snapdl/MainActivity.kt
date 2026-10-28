package com.abood.snapdl

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private data class Option(val label: String, val audio: Boolean, val value: Int)

    private val options = listOf(
        Option("🎵 صوت فقط — MP3 جودة 128kbps", true, 128),
        Option("🎵 صوت فقط — MP3 جودة 320kbps (أعلى جودة)", true, 320),
        Option("🎬 فيديو 360p", false, 360),
        Option("🎬 فيديو 720p HD", false, 720),
        Option("🎬 فيديو 1080p Full HD", false, 1080),
    )

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var urlInput: EditText
    private lateinit var fetchBtn: Button
    private lateinit var pasteBtn: Button
    private lateinit var updateBtn: Button
    private lateinit var thumb: ImageView
    private lateinit var titleText: TextView
    private lateinit var optionsBox: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var cancelBtn: Button

    private var currentUrl = ""
    private var downloading = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 29) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
        buildUi()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "SnapDL — تحميل الفيديوهات"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1a1a1a"))
        })
        root.addView(TextView(this).apply {
            text = "الصق رابط من يوتيوب، انستغرام، فيسبوك، قوقل وغيرها، أو شاركه للتطبيق مباشرة."
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, dp(4), 0, dp(12))
        })

        urlInput = EditText(this).apply {
            hint = "https://..."
            setSingleLine(true)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        root.addView(urlInput, lp())

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pasteBtn = Button(this).apply {
            text = "لصق"
            setOnClickListener { pasteFromClipboard() }
        }
        fetchBtn = Button(this).apply {
            text = "جلب الخيارات"
            setOnClickListener { fetchInfo(urlInput.text.toString().trim()) }
        }
        row.addView(pasteBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(fetchBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        root.addView(row, lp())

        thumb = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        root.addView(thumb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)).apply {
            topMargin = dp(12)
        })

        titleText = TextView(this).apply {
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1a1a1a"))
            setPadding(0, dp(8), 0, dp(8))
            visibility = View.GONE
        }
        root.addView(titleText)

        optionsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(optionsBox)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        root.addView(progress, lp())

        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
        }
        root.addView(statusText)

        cancelBtn = Button(this).apply {
            text = "إلغاء التحميل"
            visibility = View.GONE
            setOnClickListener {
                try {
                    YoutubeDL.getInstance().destroyProcessById("dl")
                } catch (_: Exception) {
                }
            }
        }
        root.addView(cancelBtn, lp())

        updateBtn = Button(this).apply {
            text = "تحديث محرك التحميل (إذا وقف موقع عن الشغل)"
            textSize = 12f
            setOnClickListener { updateEngine() }
        }
        root.addView(updateBtn, lp(24))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            isFillViewport = true
            addView(root)
        })
    }

    private fun lp(top: Int = 8) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun optionButton(opt: Option) = Button(this).apply {
        text = opt.label
        isAllCaps = false
        textSize = 15f
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor(if (opt.audio) "#7B1FA2" else "#1565C0"))
        }
        setOnClickListener { startDownload(opt) }
    }

    // ---------------------------------------------------------------- Input

    private fun pasteFromClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val url = extractUrl(text)
        if (url == null) {
            status("ما لقيت رابط بالحافظة")
            return
        }
        urlInput.setText(url)
        fetchInfo(url)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val url = extractUrl(i.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
            if (url != null) {
                urlInput.setText(url)
                fetchInfo(url)
            }
        }
    }

    private fun extractUrl(text: String): String? =
        Regex("https?://\\S+").find(text)?.value

    // ---------------------------------------------------------------- Logic

    private fun status(msg: String) {
        ui.post { statusText.text = msg }
    }

    private fun engineReady(): Boolean {
        if (App.ready) return true
        val err = App.initError
        status(if (err != null) "فشل تشغيل المحرك: $err" else "المحرك عم يتجهز، جرب بعد ثواني…")
        return false
    }

    private fun fetchInfo(raw: String) {
        val url = extractUrl(raw)
        if (url == null) {
            status("الرابط غير صحيح")
            return
        }
        if (!engineReady()) return
        currentUrl = url
        status("عم أجلب معلومات الفيديو…")
        fetchBtn.isEnabled = false
        optionsBox.visibility = View.GONE
        thumb.visibility = View.GONE
        titleText.visibility = View.GONE

        io.execute {
            try {
                val req = YoutubeDLRequest(url).apply { addOption("--no-playlist") }
                val info = YoutubeDL.getInstance().getInfo(req)
                val title = info.title ?: "بدون عنوان"
                val bmp: Bitmap? = try {
                    info.thumbnail?.let { BitmapFactory.decodeStream(URL(it).openStream()) }
                } catch (_: Exception) {
                    null
                }
                ui.post {
                    titleText.text = title
                    titleText.visibility = View.VISIBLE
                    if (bmp != null) {
                        thumb.setImageBitmap(bmp)
                        thumb.visibility = View.VISIBLE
                    }
                    optionsBox.removeAllViews()
                    options.forEach { optionsBox.addView(optionButton(it), lp()) }
                    optionsBox.visibility = View.VISIBLE
                    statusText.text = "اختار الصيغة والجودة:"
                    fetchBtn.isEnabled = true
                }
            } catch (e: Exception) {
                ui.post {
                    statusText.text = "تعذر جلب الفيديو: ${shortError(e)}"
                    fetchBtn.isEnabled = true
                }
            }
        }
    }

    private fun startDownload(opt: Option) {
        if (downloading || currentUrl.isEmpty() || !engineReady()) return
        downloading = true
        setOptionsEnabled(false)
        progress.progress = 0
        progress.visibility = View.VISIBLE
        cancelBtn.visibility = View.VISIBLE
        status("بدأ التحميل…")

        io.execute {
            val tmp = File(getExternalFilesDir(null), "tmp")
            try {
                tmp.deleteRecursively()
                tmp.mkdirs()

                val req = YoutubeDLRequest(currentUrl)
                req.addOption("--no-playlist")
                req.addOption("-o", "${tmp.absolutePath}/%(title).80s [%(id)s].%(ext)s")
                if (opt.audio) {
                    req.addOption("-f", "bestaudio/best")
                    req.addOption("-x")
                    req.addOption("--audio-format", "mp3")
                    req.addOption("--audio-quality", "${opt.value}K")
                } else {
                    val h = opt.value
                    req.addOption(
                        "-f",
                        "bv*[height<=$h][ext=mp4]+ba[ext=m4a]/b[height<=$h][ext=mp4]/bv*[height<=$h]+ba/b[height<=$h]/b"
                    )
                    req.addOption("--merge-output-format", "mp4")
                }

                YoutubeDL.getInstance().execute(req, "dl") { p, _, _ ->
                    ui.post {
                        progress.progress = (p.coerceIn(0f, 100f) * 10).toInt()
                        statusText.text = "عم ينزل… ${"%.1f".format(p.coerceIn(0f, 100f))}%"
                    }
                }

                val out = tmp.listFiles()
                    ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                    ?.maxByOrNull { it.length() }
                    ?: throw IllegalStateException("ما انحفظ أي ملف")

                status("عم أحفظ الملف…")
                val where = saveToDownloads(out)
                tmp.deleteRecursively()
                ui.post {
                    progress.progress = 1000
                    statusText.text = "✅ تم التحميل\n$where"
                }
            } catch (e: Exception) {
                tmp.deleteRecursively()
                ui.post { statusText.text = "❌ فشل التحميل: ${shortError(e)}" }
            } finally {
                ui.post {
                    downloading = false
                    setOptionsEnabled(true)
                    cancelBtn.visibility = View.GONE
                }
            }
        }
    }

    private fun setOptionsEnabled(enabled: Boolean) {
        for (i in 0 until optionsBox.childCount) optionsBox.getChildAt(i).isEnabled = enabled
    }

    private fun saveToDownloads(f: File): String {
        val mime = when (f.extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            else -> "application/octet-stream"
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SnapDL")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("تعذر إنشاء الملف بمجلد التحميلات")
            contentResolver.openOutputStream(uri)!!.use { out ->
                f.inputStream().use { it.copyTo(out) }
            }
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "SnapDL"
            )
            dir.mkdirs()
            f.copyTo(File(dir, f.name), overwrite = true)
        }
        return "Download/SnapDL/${f.name}"
    }

    private fun updateEngine() {
        if (!engineReady()) return
        status("عم أحدّث المحرك…")
        updateBtn.isEnabled = false
        io.execute {
            try {
                val r = YoutubeDL.getInstance().updateYoutubeDL(this)
                status("تم التحديث ($r)")
            } catch (e: Exception) {
                status("فشل التحديث: ${shortError(e)}")
            }
            ui.post { updateBtn.isEnabled = true }
        }
    }

    private fun shortError(e: Exception): String {
        val m = (e.message ?: e.toString()).lineSequence().lastOrNull { it.isNotBlank() } ?: "خطأ غير معروف"
        return if (m.length > 220) m.take(220) + "…" else m
    }
}
