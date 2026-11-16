package app.supeaerial.tv.lite

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import java.io.File
import java.io.InputStream

/**
 * SuperHome's 「系统帮助」: the pictures that say how the TV is used, one to a page.
 *
 * Where they come from, in the order [pages] takes them — the same shape as [SuperNames] and [Notice],
 * so the set an owner puts on a TV replaces the built-in one without another build:
 *
 *  - `help/` next to the owner's own files on the TV (`/sdcard/tv/help/`), then the app's own directory
 *    on external storage, which needs no permission and so works on a firmware that keeps /sdcard shut;
 *  - `assets/help/` packed in this APK.
 *
 * The first place that holds any picture wins whole; nothing is merged across them. png, jpg, jpeg,
 * webp, gif and bmp, in file-name order — the names are how the owner sets the reading order, so
 * `01-开机.png, 02-音量.png` is the way to write them.
 *
 * A picture is read only when its page is asked for, and sampled down to the box: a photo off a phone
 * is several thousand pixels across, and on the boxes this is for that is more than the memory wants.
 * Which place the set came from and how many are in it go to logcat; the screen says 1 / 3.
 */
object Help {
    private const val TAG = "SuperHome"
    private const val DIR = "help"
    private val types = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

    /** One page of the set: its name for the log, and whether it is a file on the TV or built in. */
    class Page(val shown: String, val file: File?, val asset: String?)

    /** The set to show: the TV's own folder first, then the app's, then what this APK carries. */
    @SuppressLint("SdCardPath")
    fun pages(context: Context): List<Page> {
        val dirs = buildList {
            runCatching { android.os.Environment.getExternalStorageDirectory() }.getOrNull()?.let { add(File(it, "tv/$DIR")) }
            add(File("/sdcard/tv/$DIR"))
            runCatching { context.getExternalFilesDir(DIR) }.getOrNull()?.let { add(it) }
        }
        for (d in dirs) {
            val got = runCatching { d.listFiles()?.filter { it.isFile && it.extension.lowercase() in types } }
                .onFailure { Log.w(TAG, "help: could not list $d", it) }.getOrNull().orEmpty()
            if (got.isNotEmpty()) return told(got.sortedBy { it.name.lowercase() }.map { Page(it.name, it, null) }, d.absolutePath)
        }
        val built = runCatching {
            context.assets.list(DIR)?.toList()
                ?.filter { it.substringAfterLast('.', "").lowercase() in types }
                ?.sortedBy { it.lowercase() }?.map { Page(it, null, "$DIR/$it") }
        }.onFailure { Log.w(TAG, "help: could not list the built-in set", it) }.getOrNull().orEmpty()
        return told(built, "the APK")
    }

    private fun told(pages: List<Page>, from: String): List<Page> {
        Log.i(TAG, "help: ${pages.size} pictures from $from" +
            (if (pages.isEmpty()) "" else ", ${pages.first().shown} first"))
        return pages
    }

    /** The picture of one page, at most [wide] and [high] device pixels across; null if it will not read. */
    fun bitmap(context: Context, page: Page, wide: Int, high: Int): Bitmap? {
        val sample = sample(context, page, wide, high)
        return runCatching {
            val opts = BitmapFactory.Options().apply { if (sample > 1) inSampleSize = sample }
            open(context, page).use { BitmapFactory.decodeStream(it, null, opts) }
        }.onFailure { Log.w(TAG, "help: could not read ${page.shown}", it) }.getOrNull()
    }

    /** How far to shrink it before decoding, as a power of two: the header alone says. */
    private fun sample(context: Context, page: Page, wide: Int, high: Int): Int = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(context, page).use { BitmapFactory.decodeStream(it, null, bounds) }
        var s = 1
        while (bounds.outWidth / (s * 2) >= wide.coerceAtLeast(1) && bounds.outHeight / (s * 2) >= high.coerceAtLeast(1)) s *= 2
        s
    }.getOrElse { 1 }

    private fun open(context: Context, page: Page): InputStream =
        page.file?.inputStream() ?: context.assets.open(page.asset!!)
}

/** The box's width in canvas units, and the tallest a picture may be: the canvas is 960 by 540. */
private const val HELP_WIDTH = 620f
private const val HELP_MAX_HIGH = 400f
/** What a picture is held to before it is measured, when none has been read yet. */
private const val HELP_FALLBACK = 16f / 9f

/**
 * The help's box: the picture in the middle of the screen, the remote's left and right turning the
 * pages, OK or BACK ending it. Focus starts on the picture itself, which is the only thing there.
 *
 * A TV's box has little memory and less patience, so only the page in front is decoded, and only when
 * the box knows how wide it is ([Help.bitmap]). A picture whose file will not read leaves the plate
 * empty and says so in logcat — one bad file does not take the set down.
 *
 * With nothing on the TV and nothing built in, the box says where the pictures go instead of opening
 * an empty frame: a row that answers with nothing looks broken, and this is the row a person presses
 * to find out how the launcher works.
 */
class HelpSheet(context: Context, close: () -> Unit) : Sheet(context, close) {
    private val pages = Help.pages(context)
    private val picture = Picture(context, pages)

    init {
        val panel = GlassBox(context, 26f)
        addView(panel, LayoutParams(Ui.pxi(HELP_WIDTH), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.pxi(20f), Ui.pxi(18f), Ui.pxi(20f), Ui.pxi(18f))
        }
        panel.addView(col, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        col.addView(picture, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        row(picture)
        // OK ends it, as BACK does: one control on the sheet, and it is the thing being read.
        picture.action = close
        focusFirst(0, 0)
        panel.scaleX = 0.94f; panel.scaleY = 0.94f; panel.alpha = 0f
        panel.post {
            if (LiteSettings.full(panel.isHardwareAccelerated)) panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).start()
            else { panel.scaleX = 1f; panel.scaleY = 1f; panel.alpha = 1f }
        }
    }

    /** The page in front: the plate it sits on, the picture inside, and 1 / N when there is more than one. */
    private class Picture(context: Context, private val pages: List<Help.Page>) : Control(context) {
        // The picture is drawn already; growing it under focus would only blur it.
        override val focusScale = 1.0f
        private val note = TextPaint(Ui.text(11f, 500, Ui.SECONDARY))
        private val pageFormat = context.getString(R.string.sh_notice_page)
        private val saying = TextPaint(Ui.text(15.5f, 500))
        private val words = context.getString(R.string.sh_help_empty)
        private var page = 0
        private var art: Bitmap? = null
        private var none: StaticLayout? = null

        @Suppress("DEPRECATION")
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            if (pages.isEmpty()) {
                val l = StaticLayout(words, saying, w, Layout.Alignment.ALIGN_NORMAL, 1.5f, 0f, false)
                none = l
                setMeasuredDimension(w, l.height.coerceAtLeast(Ui.pxi(60f)))
                return
            }
            if (art == null) art = Help.bitmap(context, pages[page], w, Ui.pxi(HELP_MAX_HIGH))
            val a = art
            // A picture of its own shape, or 16:9 while nothing has been read; never taller than the box.
            val high = if (a == null) (w / HELP_FALLBACK).toInt() else (w.toFloat() * a.height / a.width).toInt()
            setMeasuredDimension(w, high.coerceAtMost(Ui.pxi(HELP_MAX_HIGH)).coerceAtLeast(Ui.pxi(120f)))
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            tint.draw(canvas, Shapes.mask(width, height, Ui.px(14f)), 0f, 0f, if (focusedLook) Ui.FOCUS else Ui.FILL)
            if (pages.isEmpty()) {
                val l = none ?: return
                canvas.save()
                canvas.translate(0f, (h - l.height) / 2f)
                saying.color = if (focusedLook) Ui.ON_FOCUS else Ui.WHITE
                l.draw(canvas)
                canvas.restore()
                return
            }
            val a = art
            if (a != null) {
                val s = minOf(w / a.width, h / a.height)
                val dw = (a.width * s).toInt(); val dh = (a.height * s).toInt()
                val left = ((w - dw) / 2).toInt(); val top = ((h - dh) / 2).toInt()
                canvas.drawBitmap(a, null, Rect(left, top, left + dw, top + dh), bitmapPaint)
            }
            // Nothing decoded is an empty plate: [Help.bitmap] has already said which file would not read.
            if (pages.size > 1) {
                val label = String.format(pageFormat, page + 1, pages.size)
                note.color = if (focusedLook) 0x991D1D1F.toInt() else Ui.SECONDARY
                note.textAlign = Paint.Align.RIGHT
                canvas.drawText(label, w - Ui.px(10f), h - Ui.px(6f) - note.ascent(), note)
            }
        }

        /** Left and right turn the pages, and hold at either end, as the notice's text does. */
        override fun side(dir: Int): Boolean {
            if (pages.size <= 1) return false
            val to = (page + dir).coerceIn(0, pages.size - 1)
            if (to != page) {
                page = to
                // What is measured is the plate inside its padding, which is what the picture is fitted to.
                val target = if (width > 0) width else Ui.pxi(HELP_WIDTH) - Ui.pxi(40f)
                art = Help.bitmap(context, pages[page], target, Ui.pxi(HELP_MAX_HIGH))
                // A page of another shape needs the box again.
                requestLayout()
                invalidate()
            }
            return true
        }
    }
}
