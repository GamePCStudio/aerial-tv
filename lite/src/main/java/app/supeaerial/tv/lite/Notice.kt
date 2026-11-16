package app.supeaerial.tv.lite

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.widget.LinearLayout
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * SuperHome's 「启动时重要信息提示」: `superaerialnotice.txt`, shown in the middle of the screen when
 * Home comes up. The same file in four places and one built into the APK, read in the same order and by
 * the same rules as [SuperNames]'s preset list:
 *
 *  - the copy the owner edits on the TV: `/sdcard/tv/superaerialnotice.txt`, or the app's own directory
 *    on external storage (no permission needed there, which makes it usable on new firmwares too);
 *  - the copy at whatever address the TV says, fetched at most every [CHECK_EVERY_MS] and kept in
 *    `files/super/notice.txt` so the TV still has it offline;
 *  - the copy on our own site, [CLOUD], when the TV says nothing;
 *  - the copy packed in this APK (`assets/superaerialnotice.txt`), so a TV with no network still gets
 *    a notice.
 *
 * Where the middle copy comes from, as in [SuperNames]: a `url` line in the TV's own txt, else the
 * first URL among the lines of `superaerialnotice.url` next to it, else [CLOUD]. A `url` line says
 * where to fetch and is not shown; every other line is shown as it stands, blank lines kept, so the
 * notice an owner wrote line by line comes up line by line.
 *
 * Two things decide what comes up, and they are deliberately not the same switch:
 *
 *  - 「启动时重要信息提示」 开 — the notice is shown whenever Home starts, whatever it says.
 *  - 重要提醒 — a notice this TV has never been shown ([pending]) is announced even when that switch is
 *    关, because turning the popup off is a person saying "do not repeat this every morning", not "keep
 *    news from me". It is asked about until it has been read, and then holds its peace.
 *
 * What has been shown is kept as a hash of the text, not a date: rewriting the file, or a new copy
 * arriving from the site, is one notice to read rather than a reminder for a week, and a TV with no
 * network is not told about news it cannot have.
 */
object Notice {
    private const val TAG = "SuperHome"
    private const val FILE = "superaerialnotice.txt"
    /** The TV's own address for the notice: one file holding just a URL, next to the txt. */
    private const val URL_FILE = "superaerialnotice.url"
    /** The owner's own site; the copy in this repository is `site/superaerial/`, deployed by hand. */
    private const val CLOUD = "https://800915.xyz/superaerial/$FILE"
    private const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L
    /** A failed fetch is tried again after this, not on every start. */
    private const val RETRY_MS = 10 * 60 * 1000L

    /** One layer's notice: the text shown, and the address its own `url` line asked for. */
    private class Body(val text: String, val url: String?)

    /** The opening tag of an HTML or XML document, wherever it is: no notice an owner typed starts like one. */
    private val page = Regex("<(!doctype|html|\\?xml)", RegexOption.IGNORE_CASE)

    private lateinit var context: Context
    private lateinit var cacheFile: File
    private lateinit var prefs: SharedPreferences

    @Volatile private var bundled: Body? = null
    @Volatile private var cloud: Body? = null
    @Volatile private var local: Body? = null
    @Volatile private var localStamp = 0L
    @Volatile private var lastAttempt = 0L
    /** What [current] said last time it was looked at, so [refresh] can say whether it moved. */
    @Volatile private var last = ""

    fun init(context: Context) {
        if (::cacheFile.isInitialized) return
        this.context = context.applicationContext
        prefs = this.context.getSharedPreferences("super_notice", Context.MODE_PRIVATE)
        cacheFile = File(this.context.filesDir, "super").apply { mkdirs() }.let { File(it, "notice.txt") }
        bundled = body(runCatching { this.context.assets.open(FILE).use { decode(it.readBytes()) } }.getOrNull())
        cloud = body(cacheFile.takeIf { it.exists() }?.let { runCatching { decode(it.readBytes()) }.getOrNull() })
        readLocal()
        val read = seen()?.let { "read one (${it.take(8)})" } ?: "seen none yet"
        Log.i(TAG, "notice: ${size(local)} characters from the TV's file, ${size(cloud)} from the cloud, " +
            "${size(bundled)} built in; this TV has $read")
    }

    private fun size(body: Body?) = body?.text?.length ?: 0

    /** The notice Home would show now: the TV's own file, then the cloud's, then the built-in one. */
    fun current(): String? = (local ?: cloud ?: bundled)?.text?.takeIf { it.isNotBlank() }

    /** Which layer [current] came from, for logcat only — a TV screen has no business naming files. */
    fun source(): String = when {
        local != null -> "file"
        cloud != null -> "cloud"
        else -> "apk"
    }

    private fun seen(): String? = prefs.getString("seen_hash", null)

    /** Says the notice on screen has been read: [pending] is false for it until its text changes. */
    fun markSeen() {
        val text = current() ?: return
        val hash = hash(text)
        if (hash == seen()) return
        prefs.edit().putString("seen_hash", hash).apply()
        Log.i(TAG, "notice: this TV has now read the ${source()} copy (${hash.take(8)})")
    }

    /** True when the text on offer is one this TV has never been shown: 重要提醒, whatever the switch says. */
    fun pending(): Boolean {
        val text = current() ?: return false
        return hash(text) != seen()
    }

    /**
     * Reads the TV's own file, then fetches the notice when it is due. Off the main thread, like
     * [SuperNames.refresh]; true when the text shown changed, which is what makes a 重要提醒.
     */
    @Synchronized
    fun refresh(): Boolean {
        readLocal()
        val now = System.currentTimeMillis()
        val url = local?.url ?: tvUrl()
        // An address newly written on the TV takes effect at once; an unchanged one waits for the check.
        if ((url != prefs.getString("fetched_url", null) || now - prefs.getLong("checked_at", 0L) > CHECK_EVERY_MS) &&
            now - lastAttempt > RETRY_MS
        ) fetch(url, now)
        val text = current().orEmpty()
        if (text == last) return false
        last = text
        return true
    }

    /** The address written on the TV: the first line of `superaerialnotice.url` that is one. */
    private fun tvUrl(): String? {
        val file = candidates(URL_FILE).firstOrNull { it.exists() } ?: return null
        val text = runCatching { decode(file.readBytes()) }
            .onFailure { Log.w(TAG, "could not read $file", it) }.getOrNull() ?: return null
        val url = text.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
        if (url == null) Log.w(TAG, "$file holds no address; using the built-in one")
        return url
    }

    /**
     * The downloaded notice, kept on the TV so it still has it offline. [chosen] is what the TV's own
     * files say, where null is [CLOUD]. Fetched over HTTPS, then plain HTTP, as [SuperNames.fetch] does.
     */
    private fun fetch(chosen: String?, now: Long) {
        lastAttempt = now
        val url = chosen ?: CLOUD
        // Noted here rather than only on success, so an address that does not answer is not retried every start.
        prefs.edit().putString("fetched_url", chosen).apply()
        val into = File(cacheFile.parentFile, "notice.new")
        val got = runCatching { Net.download(url, into) }
            // Android 4.x does not always trust the certificate on the wire; the site answers over plain
            // HTTP too, which is what the wallpapers and the preset list do for the same reason.
            .recoverCatching { e ->
                Log.i(TAG, "$url failed over HTTPS (${e.javaClass.simpleName}); trying HTTP")
                Net.download(url.replaceFirst("https://", "http://"), into)
            }
        val bytes = if (got.isSuccess) runCatching { into.readBytes() }.getOrNull() else null
        into.delete()
        if (bytes == null) {
            got.exceptionOrNull()?.let { Log.w(TAG, "could not fetch the notice from $url", it) }
            prefs.edit().putLong("checked_at", now - CHECK_EVERY_MS + RETRY_MS).apply()
            return
        }
        val body = body(decode(bytes))
        // A response that is not a notice — a captive portal, the site's own page — must not blank the
        // copy this TV already has, so an empty one never takes the cloud layer.
        if (body == null) {
            Log.w(TAG, "the notice at $url holds nothing to show; keeping the copy on this TV")
            prefs.edit().putLong("checked_at", now).apply()
            return
        }
        cloud = body
        Log.i(TAG, "the notice came from $url: ${body.text.length} characters")
        runCatching { cacheFile.writeBytes(bytes) }.onFailure { Log.w(TAG, "could not keep the downloaded notice", it) }
        prefs.edit().putLong("checked_at", now).apply()
    }

    /** The TV's own notice, if there is one; gone again, its layer goes empty with it. */
    private fun readLocal() {
        val file = candidates(FILE).firstOrNull { it.exists() }
        if (file == null) {
            if (localStamp != 0L) {
                localStamp = 0L
                local = null
            }
            return
        }
        val stamp = file.lastModified()
        if (stamp == localStamp && localStamp != 0L) return
        localStamp = stamp
        local = body(runCatching { decode(file.readBytes()) }
            .onFailure { Log.w(TAG, "could not read $file", it) }.getOrNull())
    }

    /**
     * One file's notice: its `url` line taken out as the address, the rest kept line for line. Nothing
     * comes of a file that says only an address, of an empty one, or of a web page — no box over no news.
     */
    private fun body(text: String?): Body? {
        if (text == null) return null
        if (page.containsMatchIn(text)) {
            // Where nothing is deployed, the site's own edge answers 200 with an empty HTML shell. That
            // is a document, not the notice, and it must not take the built-in copy's place on every TV.
            Log.w(TAG, "that is a web page, not a notice; keeping the copy this TV has")
            return null
        }
        var url: String? = null
        val kept = ArrayList<String>()
        for (raw in text.lines()) {
            val parts = raw.trim().split(Regex("[ \\t]+"), 2)
            val rest = parts.getOrNull(1)?.trim()
            if (rest != null && parts[0].equals("url", ignoreCase = true)) {
                if (rest.startsWith("http://") || rest.startsWith("https://")) url = rest
                else Log.w(TAG, "the notice's url line is not an address: ${raw.trim()}")
                continue
            }
            kept += raw
        }
        val shown = kept.joinToString("\n").trimEnd()
        return if (shown.isBlank()) null else Body(shown, url)
    }

    /** Where the owner's own files live on this TV, best first, exactly as [SuperNames.candidates]. */
    @android.annotation.SuppressLint("SdCardPath")
    private fun candidates(name: String): List<File> = buildList {
        val storage = runCatching { android.os.Environment.getExternalStorageDirectory() }.getOrNull()
        if (storage != null) add(File(storage, "tv/$name"))
        // The path the owner is told to use, kept beside the real one for the firmwares whose
        // getStorageDirectory() points somewhere else while /sdcard still holds the files.
        add(File("/sdcard/tv", name))
        runCatching { context.getExternalFilesDir(null) }.getOrNull()?.let { add(File(it, name)) }
    }

    /** UTF-8, or GBK when the bytes are not UTF-8 (Windows Notepad in China saves that way). */
    private fun decode(bytes: ByteArray): String {
        val text = runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrElse {
            Log.i(TAG, "the notice is not UTF-8; reading it as GBK")
            runCatching { String(bytes, charset("GBK")) }.getOrElse { String(bytes) }
        }
        // Notepad writes a byte-order mark, which would otherwise sit in front of the first word.
        return text.removePrefix("\uFEFF")
    }

    /** The text's own fingerprint: what has been read, and what counts as news. */
    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * The notice box: its text in the middle of the screen, its two buttons under it. Hand-drawn like the
 * rest of Lite and moved by the remote — [Sheet]'s rows, so up and down leave one for the other and OK
 * presses the focused one.
 *
 * 「确认」 counts down from twelve and, five seconds after it counts them out, presses itself: a TV's
 * remote is slow and Home should not stay behind a box nobody is reading. The count holds its peace with
 * a person who is reading — a key pressed in those last five seconds takes the twelve back rather than
 * answering the box over his head — and it only ever answers 确认, which he can press at any moment: the
 * box opens with the focus on it. 不再提醒 is always its own press.
 * BACK does what 确认 does, which is why [close] is the callback that says the notice has been read.
 *
 * [prompt] is the other face, for when 「启动时重要信息提示」 is 关 and the text is news: the notice is
 * announced, with 查看 for the text and 稍后 for later. Nothing is marked read without the text having
 * been shown, so it asks again next start until it has been seen.
 */
class NoticeSheet(
    context: Context,
    close: () -> Unit,
    private val prompt: Boolean,
    private val text: String,
    private val onNever: () -> Unit,
    private val onView: () -> Unit,
) : Sheet(context, close) {
    companion object {
        private const val COUNTDOWN = 12
        /** The pause once the count is out, before the box answers itself. */
        private const val AUTO_MS = 5000L
        /** The box's width in canvas units: nine lines of the notice across it, and no wider. */
        private const val WIDTH = 460f
    }

    private val handler = Handler(Looper.getMainLooper())
    private val body = Body(context, text)
    private val first = Button(context, string(if (prompt) R.string.sh_notice_view else R.string.sh_notice_confirm))
    private val second = Button(context, string(if (prompt) R.string.sh_notice_later else R.string.sh_notice_never))
    /** The seconds left on 确认; none are counted at all on the prompt. */
    private var left = if (prompt) 0 else COUNTDOWN
    private val tick = Runnable { left--; count() }
    private val auto = Runnable { close() }

    init {
        val panel = GlassBox(context, 26f)
        addView(panel, LayoutParams(Ui.pxi(WIDTH), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.pxi(26f), Ui.pxi(22f), Ui.pxi(26f), Ui.pxi(22f))
        }
        panel.addView(col, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // The three words of the title over the middle of the box, not over its left edge: the label is
        // as wide as the box, so it is the text that has to be told to centre itself.
        col.addView(context.label(string(if (prompt) R.string.sh_notice_new else R.string.sh_notice_title), 17f, 600)
            .apply { gravity = Gravity.CENTER_HORIZONTAL },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (prompt) {
            // The news, not the text: 查看 is what shows the text, so the prompt says only that there is
            // one and where it came from — which is the question a person answers without reading it.
            col.addView(context.label(string(R.string.sh_notice_new_hint), 13f, 500, Ui.SECONDARY),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = Ui.pxi(14f) })
        } else {
            col.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = Ui.pxi(16f) })
        }
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(first, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = Ui.pxi(10f) })
        buttons.addView(second, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = Ui.pxi(18f) })

        // The text is one row of the remote and the two buttons the next: up from 确认 reaches the text,
        // whose own left and right are its pages. A prompt has no text to reach, so it has one row.
        if (!prompt) row(body)
        row(first, second)
        first.action = { if (prompt) onView() else close() }
        second.action = { if (prompt) close() else onNever() }

        // Focus lands on the countdown button, the first column of the last row: the box is on screen to
        // be dismissed, and OK must end it whenever he is done reading — before, during or after the count.
        focusFirst(if (prompt) 0 else 1, 0)
        if (!prompt) count()
        // It grows into place, as the full app's dialog.
        panel.scaleX = 0.94f; panel.scaleY = 0.94f; panel.alpha = 0f
        panel.post {
            if (LiteSettings.full(panel.isHardwareAccelerated)) panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).start()
            else { panel.scaleX = 1f; panel.scaleY = 1f; panel.alpha = 1f }
        }
    }

    private fun string(id: Int) = context.getString(id)

    /** One second of the countdown, then the five it waits before answering itself. */
    private fun count() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(auto)
        if (left > 0) {
            first.text = context.getString(R.string.sh_notice_confirm_count, left)
            first.invalidate()
            handler.postDelayed(tick, 1000)
            return
        }
        first.text = string(R.string.sh_notice_confirm)
        handler.postDelayed(auto, AUTO_MS)
    }

    override fun onKey(e: KeyEvent): Boolean {
        if (!prompt && e.action == KeyEvent.ACTION_DOWN && e.keyCode != KeyEvent.KEYCODE_BACK && left <= 0) {
            // Reading it still: the twelve seconds back, and the box waits for him.
            left = COUNTDOWN
            count()
        }
        return super.onKey(e)
    }

    override fun onDetachedFromWindow() {
        // Taken off the screen from anywhere — BACK, another panel over it, Home destroyed: the count
        // must not go on answering a box that has gone.
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    /**
     * The notice's text: set by hand, the way the owner wrote it — 1.5 lines of leading, and no line of
     * it cut off. There is only so much room on a TV, so what does not fit is paged, and paging is what
     * the remote's left and right do while the text holds the focus ([side] is asked before focus moves).
     * A notice that fits says nothing about pages.
     */
    private class Body(context: Context, private val text: String) : Control(context) {
        private val paint = TextPaint(Ui.text(15.5f, 500))
        private val note = TextPaint(Ui.text(11f, 500, Ui.SECONDARY))
        private val pageFormat = context.getString(R.string.sh_notice_page)
        private var layout: StaticLayout? = null
        private var lineHeight = 1
        private var perPage = 1
        private var page = 0

        @Suppress("DEPRECATION")
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val l = StaticLayout(text, paint, w, Layout.Alignment.ALIGN_NORMAL, 1.5f, 0f, false)
            layout = l
            lineHeight = (l.height / l.lineCount.coerceAtLeast(1)).coerceAtLeast(1)
            perPage = (Ui.px(180f) / lineHeight).toInt().coerceAtLeast(2).coerceAtMost(l.lineCount.coerceAtLeast(1))
            if (page >= pages()) page = pages() - 1
            setMeasuredDimension(w, perPage * lineHeight)
        }

        override fun onDraw(canvas: Canvas) {
            val l = layout ?: return
            canvas.save()
            canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
            canvas.translate(0f, -(page * perPage * lineHeight).toFloat())
            paint.color = Ui.WHITE
            l.draw(canvas)
            canvas.restore()
            val p = pages()
            if (p > 1) {
                val label = String.format(pageFormat, page + 1, p)
                note.color = Ui.SECONDARY
                note.textAlign = Paint.Align.RIGHT
                canvas.drawText(label, width.toFloat(), height - Ui.px(3f) - note.ascent(), note)
            }
        }

        private fun pages(): Int {
            val l = layout ?: return 1
            return ((l.lineCount + perPage - 1) / perPage).coerceAtLeast(1)
        }

        override fun side(dir: Int): Boolean {
            if (pages() <= 1) return false
            // Taken either way, so the remote does not leave the text at the edges of a long notice.
            page = (page + dir).coerceIn(0, pages() - 1)
            invalidate()
            return true
        }
    }

    /** One of the two buttons: corners 12, 48 high, its word in the middle. 确认's word carries the count. */
    private class Button(context: Context, var text: String) : Control(context) {
        override val focusScale = 1.05f
        private val paint = TextPaint(Ui.text(14f, 600))

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Ui.pxi(48f))

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            tint.draw(canvas, Shapes.mask(width, height, Ui.px(12f)), 0f, 0f, if (focusedLook) Ui.FOCUS else Ui.FILL)
            paint.color = if (focusedLook) Ui.ON_FOCUS else Ui.WHITE
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(text, w / 2, baseline(paint, h / 2), paint)
        }
    }
}
