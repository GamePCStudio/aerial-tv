package app.supeaerial.tv.lite

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** A preset folder from the list: the name Home shows, and the apps put inside it. */
data class NameGroup(val name: String, val packages: List<String>)

/**
 * SuperHome's preset list: `supeaerialappname.txt`, package name → the name shown on the tile, plus
 * preset folders. The same file in three places and one list built into the APK:
 *
 *  - the copy the owner edits on the TV: `/sdcard/tv/supeaerialappname.txt`, or the app's own
 *    directory on external storage (no permission needed there, which makes it usable on new firmwares too);
 *  - the copy on our own site, refreshed at most every [CHECK_EVERY_MS] and kept in
 *    `files/super/appname.txt` so the TV still has it offline;
 *  - the copy packed in this APK (`assets/supeaerialappname.txt`), so a TV with no network at all
 *    still gets the preset names.
 *
 * The names a person types themselves for one app (the phone page's rename, [AppNames]) outrank all
 * three: a preset list is a default for every TV, a typed name is this TV's choice. Above the list,
 * the local file outranks the downloaded one, because putting a file on the TV is the owner saying
 * "this is what I want", and it works whatever the network did.
 *
 * File format — one entry a line, `#` starts a comment, blank lines ignored, fields split by
 * spaces or tabs:
 * ```
 * name   com.hgtv.star           红果短剧TV          # the tile says this instead of the app's own label
 * group  影视                     com.hgtv.star,com.gitvdemo.video   # a preset folder (its name holds no spaces)
 * url    https://example.com/my-appname.txt         # where to fetch the list, instead of the built-in address
 * ```
 * Written as UTF-8; a file saved as GBK (Windows Notepad's default in China) is read as GBK.
 */
object SuperNames {
    private const val TAG = "SuperHome"
    private const val FILE = "supeaerialappname.txt"
    /** Our own site, the one the icon library already comes from (site/super/ in this repository). */
    private const val CLOUD = "https://aerial.qust.me/super/$FILE"
    private const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L
    /** A failed fetch is tried again after this, not on every start. */
    private const val RETRY_MS = 10 * 60 * 1000L

    private class Lists(val names: Map<String, String>, val groups: List<NameGroup>, val url: String?)

    private lateinit var context: Context
    private lateinit var cacheFile: File
    private lateinit var prefs: android.content.SharedPreferences

    @Volatile private var bundled = Lists(emptyMap(), emptyList(), null)
    @Volatile private var cloud = Lists(emptyMap(), emptyList(), null)
    @Volatile private var local = Lists(emptyMap(), emptyList(), null)
    @Volatile private var localStamp = 0L
    @Volatile private var lastAttempt = 0L

    fun init(context: Context) {
        if (::cacheFile.isInitialized) return
        this.context = context.applicationContext
        prefs = this.context.getSharedPreferences("super_names", Context.MODE_PRIVATE)
        cacheFile = File(this.context.filesDir, "super").apply { mkdirs() }.let { File(it, "appname.txt") }
        bundled = parse(runCatching {
            this.context.assets.open(FILE).use { decode(it.readBytes()) }
        }.getOrNull())
        cloud = parse(cacheFile.takeIf { it.exists() }?.let { runCatching { decode(it.readBytes()) }.getOrNull() })
        readLocal()
        Log.i(TAG, "list: ${local.names.size} from the TV's file, ${cloud.names.size} from the cloud, ${bundled.names.size} built in")
    }

    /** The preset name for [pkg], in the order the layers above; null when no layer has one. */
    fun name(pkg: String): String? = local.names[pkg] ?: cloud.names[pkg] ?: bundled.names[pkg]

    /** Which layer a name came from, for logcat: typed, file, cloud, apk, or the app's own label. */
    fun source(pkg: String): String = when {
        AppNames.of(pkg) != null -> "typed"
        local.names.containsKey(pkg) -> "file"
        cloud.names.containsKey(pkg) -> "cloud"
        bundled.names.containsKey(pkg) -> "apk"
        else -> "app"
    }

    /**
     * The preset folders, highest layer first and each name once: what the layer above says about a
     * folder is what Home uses. [SuperSeed] puts the apps in them, and only the ones it has not seen before.
     */
    fun groups(): List<NameGroup> = LinkedHashSet<NameGroup>().run {
        for (g in local.groups + cloud.groups + bundled.groups) if (none { it.name == g.name }) add(g)
        toList()
    }

    /** One line for logcat with the tiles: how many names come from the preset list, and from which layer. */
    fun logNames(apps: List<AppEntry>) {
        val preset = apps.filter { source(it.key).let { s -> s == "file" || s == "cloud" || s == "apk" } }
        Log.i(TAG, "${preset.size} names taken from the preset list: " +
            preset.take(8).joinToString(", ") { "${it.key}(${source(it.key)})" })
    }

    /**
     * Reads the TV's own file, then fetches the list when it is due. Off the main thread, like
     * [IconLibrary.refresh]; true when a name or a folder changed, so Home lays its tiles out again.
     */
    @Synchronized
    fun refresh(): Boolean {
        val before = snapshot()
        readLocal()
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("checked_at", 0L) > CHECK_EVERY_MS && now - lastAttempt > RETRY_MS) fetch(now)
        return snapshot() != before
    }

    /** The downloaded list, kept on the TV so it still has it offline. Fetched over HTTPS, then plain HTTP. */
    private fun fetch(now: Long) {
        lastAttempt = now
        val url = local.url ?: CLOUD
        val into = File(cacheFile.parentFile, "appname.new")
        val got = runCatching { Net.download(url, into) }
            // Android 4.x does not always trust the certificate on the wire; the site answers over
            // plain HTTP too, which is what the wallpapers do for the same reason.
            .recoverCatching { e ->
                Log.i(TAG, "$url failed over HTTPS (${e.javaClass.simpleName}); trying HTTP")
                Net.download(url.replaceFirst("https://", "http://"), into)
            }
        val bytes = if (got.isSuccess) runCatching { into.readBytes() }.getOrNull() else null
        into.delete()
        if (bytes == null) {
            got.exceptionOrNull()?.let { Log.w(TAG, "could not fetch the list from $url", it) }
            // Tried again after [RETRY_MS] rather than on every start.
            prefs.edit().putLong("checked_at", now - CHECK_EVERY_MS + RETRY_MS).apply()
            return
        }
        val parsed = parse(decode(bytes))
        // A response that is not the list — a captive portal, a redirect to the site's own page —
        // parses to nothing. That must not empty the layer the TV already has.
        if (parsed.names.isEmpty() && parsed.groups.isEmpty()) {
            Log.w(TAG, "the list at $url holds no names or groups; keeping the copy on this TV")
            prefs.edit().putLong("checked_at", now).apply()
            return
        }
        cloud = parsed
        runCatching { cacheFile.writeBytes(bytes) }.onFailure { Log.w(TAG, "could not keep the downloaded list", it) }
        prefs.edit().putLong("checked_at", now).apply()
    }

    /**
     * What Home would draw from the list right now: every name, the layer that wins left standing,
     * and the folders. Compared before and after a refresh, so a name that only changed its text —
     * not which packages are named — still counts as a change.
     */
    private fun snapshot(): String {
        val merged = LinkedHashMap<String, String>()
        for (list in listOf(bundled, cloud, local)) merged.putAll(list.names)
        return merged.toString() + '|' + groups()
    }

    /** The TV's own file, if there is one; gone again, its layer goes empty with it. */
    private fun readLocal() {
        val file = localFile()
        if (file == null) {
            if (localStamp != 0L) {
                localStamp = 0L
                local = Lists(emptyMap(), emptyList(), null)
            }
            return
        }
        val stamp = file.lastModified()
        if (stamp == localStamp && localStamp != 0L) return
        localStamp = stamp
        local = parse(runCatching { decode(file.readBytes()) }.onFailure { Log.w(TAG, "could not read $file", it) }.getOrNull())
    }

    /**
     * The list on this TV, if there is one. `/sdcard/tv/` first, because that is where the owner
     * puts it; then the app's own directory on external storage, which needs no permission and so
     * also works on a firmware that keeps /sdcard shut.
     */
    private fun localFile(): File? = candidates().firstOrNull { it.exists() }

    @android.annotation.SuppressLint("SdCardPath")
    private fun candidates(): List<File> = buildList {
        val storage = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        if (storage != null) add(File(storage, "tv/$FILE"))
        // The path the owner is told to use, kept beside the real one for the firmwares whose
        // getStorageDirectory() points somewhere else while /sdcard still holds the files.
        add(File("/sdcard/tv", FILE))
        runCatching { context.getExternalFilesDir(null) }.getOrNull()?.let { add(File(it, FILE)) }
    }

    /** UTF-8, or GBK when the bytes are not UTF-8 (Windows Notepad in China saves that way). */
    private fun decode(bytes: ByteArray): String {
        val text = runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrElse {
            Log.i(TAG, "the list is not UTF-8; reading it as GBK")
            runCatching { String(bytes, charset("GBK")) }.getOrElse { String(bytes) }
        }
        // Notepad writes a byte-order mark, which would otherwise become part of the first keyword.
        return text.removePrefix("\uFEFF")
    }

    private fun parse(text: String?): Lists {
        if (text == null) return Lists(emptyMap(), emptyList(), null)
        val names = LinkedHashMap<String, String>()
        val groups = ArrayList<NameGroup>()
        var url: String? = null
        for (raw in text.lines()) {
            val line = raw.trim().ifEmpty { continue }
            if (line.startsWith("#") || line.startsWith("//")) continue
            val head = line.split(Regex("[ \\t]+"), 2)
            val rest = head.getOrNull(1)?.trim().orEmpty()
            when (head[0].lowercase()) {
                "name" -> {
                    val parts = rest.split(Regex("[ \\t]+"), 2)
                    val pkg = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: continue
                    val shown = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    names[pkg] = shown
                }
                "group" -> {
                    val parts = rest.split(Regex("[ \\t]+"), 2)
                    val name = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: continue
                    val pkgs = parts.getOrNull(1)?.split(',', ' ', '\t')?.filter { it.isNotBlank() } ?: emptyList()
                    if (pkgs.isNotEmpty()) groups += NameGroup(name, pkgs)
                }
                "url" -> url = rest.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                else -> Log.w(TAG, "unknown line in the list: $line")
            }
        }
        return Lists(names, groups, url)
    }
}
