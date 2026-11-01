package app.supeaerial.tv.lite

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import java.security.MessageDigest

/** As the full app's FocusStyle: High Contrast adds the white frame (the default on D-pad remotes). */
enum class FocusStyle(val key: String) {
    Standard("standard"), HighContrast("high_contrast");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: HighContrast
    }
}

/** As the full app's UiSounds: the TV's own key sounds, Aerial's, or none. */
enum class UiSounds(val key: String) {
    Tv("tv"), Aerial("aerial"), Off("off");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/** As the full app's Effects; in Lite, Full adds the focused tile's shadow and Lite drops every animation. */
enum class Effects(val key: String) {
    Auto("auto"), Full("full"), Lite("lite");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Auto
    }
}

/**
 * SuperHome: the shape of an app's picture. Wide is Aerial Lite's own tile art (a 16:9 banner cut to
 * the tile); Square is the app's own Android icon, drawn as the launchers on every phone show it.
 * The number of apps a row follows the shape, so a square tile does not need the wide one's height.
 * [DEFAULT] is the shape a TV starts on: SuperHome ships square icons, eight a row.
 */
enum class IconShape(val key: String, val columns: IntRange, val defaultColumns: Int) {
    Wide("wide", 4..6, 6), Square("square", 6..8, 8);

    val square get() = this == Square

    companion object {
        val DEFAULT = Square
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * Lite's preferences, under the full app's names (settings/AerialSettings.kt), so the two read alike
 * and the phone page's settings mean the same. The starting values below are SuperHome's own: a fresh
 * TV gets square icons eight a row, two rows, no plate under the top row, and every name shown.
 * Main thread only.
 */
object LiteSettings {
    private const val PREFS = "settings"
    private lateinit var prefs: SharedPreferences

    var focusStyle = FocusStyle.HighContrast; private set
    var oneRowHome = false; private set
    var peekNextRow = true; private set
    /** SuperHome: an app's picture is Aerial's wide tile art, or the app's own square icon. */
    var iconShape = IconShape.DEFAULT; private set
    /** Apps a row on Home and in folders; [IconShape.columns] bounds it and [IconShape.defaultColumns] is its default. */
    var homeColumns = IconShape.DEFAULT.defaultColumns; private set
    var homePlate = false; private set
    var showAllNames = true; private set
    var clockRight = false; private set
    /** SuperHome's factory choice: the big clock across the top rather than one small line. */
    var largeClock = true; private set
    var showWeather = true; private set
    var screensaverClock = true; private set
    var backStartsScreensaver = true; private set
    var uiSounds = UiSounds.Tv; private set
    var effects = Effects.Auto; private set
    var screensaverMinutes = 5; private set
    var bootApp: String? = null; private set

    const val NEVER = 0
    /** The counts offered for the shape chosen, so the row and the setter follow it. */
    val HOME_COLUMNS get() = iconShape.columns
    /** The full app's choices. */
    val screensaverChoices = listOf(2, 5, 10, 20, NEVER)

    var pinSet = false; private set
    var pinArrows = false; private set
    private var pinHash: String? = null
    private var unlockedUntil = 0L
    private const val UNLOCK_MS = 10 * 60 * 1000L

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        focusStyle = FocusStyle.of(prefs.getString("focus_style", null))
        oneRowHome = prefs.getBoolean("one_row_home", false)
        peekNextRow = prefs.getBoolean("peek_next_row", true)
        // The shape first: the saved count of a different shape is not a choice for this one.
        iconShape = IconShape.of(prefs.getString("icon_shape", null))
        homeColumns = prefs.getInt("home_columns", iconShape.defaultColumns).takeIf { it in HOME_COLUMNS }
            ?: iconShape.defaultColumns
        homePlate = prefs.getBoolean("home_plate", false)
        showAllNames = prefs.getBoolean("show_all_names", true)
        clockRight = prefs.getBoolean("clock_right", false)
        largeClock = prefs.getBoolean("large_clock", true)
        showWeather = prefs.getBoolean("show_weather", true)
        screensaverClock = prefs.getBoolean("screensaver_clock", true)
        backStartsScreensaver = prefs.getBoolean("back_starts_screensaver", true)
        // As the full app: the TV's own sounds where its key sound switch is on, else Aerial's.
        uiSounds = UiSounds.of(prefs.getString("ui_sounds_kind", null)) ?: if (
            runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) }.getOrDefault(1) == 1
        ) UiSounds.Tv else UiSounds.Aerial
        effects = Effects.of(prefs.getString("effects", null))
        screensaverMinutes = prefs.getInt("screensaver_minutes", 5)
        bootApp = prefs.getString("boot_app", null)
        pinHash = prefs.getString("pin_hash", null)
        pinSet = pinHash != null
        pinArrows = prefs.getBoolean("pin_arrows", false)
    }

    private fun put(block: SharedPreferences.Editor.() -> Unit) = prefs.edit(action = block)

    fun chooseFocusStyle(v: FocusStyle) { focusStyle = v; put { putString("focus_style", v.key) } }
    fun chooseOneRowHome(v: Boolean) { oneRowHome = v; put { putBoolean("one_row_home", v) } }

    /**
     * SuperHome: another shape also changes how many apps go a row, so a count the old shape
     * allowed and this one does not takes the new shape's default. The tiles are made again for it.
     */
    fun chooseIconShape(v: IconShape) {
        if (v == iconShape) return
        val kept = homeColumns.takeIf { it in v.columns } ?: v.defaultColumns
        iconShape = v; homeColumns = kept
        put { putString("icon_shape", v.key); putInt("home_columns", kept) }
    }

    fun chooseHomeColumns(v: Int) { if (v in HOME_COLUMNS) { homeColumns = v; put { putInt("home_columns", v) } } }
    fun choosePeekNextRow(v: Boolean) { peekNextRow = v; put { putBoolean("peek_next_row", v) } }
    fun chooseHomePlate(v: Boolean) { homePlate = v; put { putBoolean("home_plate", v) } }
    fun chooseShowAllNames(v: Boolean) { showAllNames = v; put { putBoolean("show_all_names", v) } }
    fun chooseClockRight(v: Boolean) { clockRight = v; put { putBoolean("clock_right", v) } }
    fun chooseLargeClock(v: Boolean) { largeClock = v; put { putBoolean("large_clock", v) } }
    fun chooseShowWeather(v: Boolean) { showWeather = v; put { putBoolean("show_weather", v) } }
    fun chooseScreensaverClock(v: Boolean) { screensaverClock = v; put { putBoolean("screensaver_clock", v) } }
    fun chooseBackStartsScreensaver(v: Boolean) { backStartsScreensaver = v; put { putBoolean("back_starts_screensaver", v) } }
    fun chooseUiSounds(v: UiSounds) { uiSounds = v; put { putString("ui_sounds_kind", v.key) } }
    fun chooseEffects(v: Effects) { effects = v; put { putString("effects", v.key) } }
    fun chooseScreensaverMinutes(v: Int) { screensaverMinutes = v; put { putInt("screensaver_minutes", v) } }
    fun chooseBootApp(pkg: String?) { bootApp = pkg; put { putString("boot_app", pkg) } }

    /** Animations and the focused tile's shadow: with hardware acceleration, unless Effects says Lite. */
    fun full(hardware: Boolean): Boolean = hardware && effects != Effects.Lite

    // ---- parental PIN (the full app's, issue #10) ----

    val locked: Boolean get() = pinSet && SystemClock.elapsedRealtime() > unlockedUntil

    fun unlock(pin: String): Boolean {
        if (hash(pin) != pinHash) return false
        unlockedUntil = SystemClock.elapsedRealtime() + UNLOCK_MS
        return true
    }

    fun relock() { unlockedUntil = 0L }

    /** Sets the PIN, digits or [arrows] (U, D, L, R), or turns it off with null. */
    fun choosePin(pin: String?, arrows: Boolean = false) {
        pinHash = pin?.let(::hash)
        pinSet = pinHash != null
        pinArrows = pin != null && arrows
        // Locked from the start: a parent who sets the PIN and hands the remote over expects it
        // to hold at once, not after the next Home press (issue #718).
        unlockedUntil = 0L
        put { putString("pin_hash", pinHash); putBoolean("pin_arrows", pinArrows) }
    }

    /** The full app's hash, so a PIN reads the same in both. */
    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("aerial:$pin".toByteArray()).joinToString("") { "%02x".format(it) }
}
