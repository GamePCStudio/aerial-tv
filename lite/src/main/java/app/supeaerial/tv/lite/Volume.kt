package app.supeaerial.tv.lite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * SuperHome's 「音量」: the TV's own music volume lifted to full when Home comes up, and where the
 * choice says 锁死, kept there for as long as Home is the screen in front.
 *
 * [VolumeBoot.Max] writes when Home comes up and keeps looking for a few seconds after (see
 * [settle]), then leaves the level alone; [VolumeBoot.Lock] answers every change straight back,
 * whoever made it: the remote's volume keys end up adjusting STREAM_MUSIC on these boxes.
 * [VolumeBoot.Off] leaves everything alone and registers nothing.
 *
 * Nothing is written where the box's volume is fixed, since on those the level is set past it — over
 * HDMI-CEC or IR to the TV or the soundbar (the full app's issues #1307, #1104) and a slider here
 * moves nothing; the log says so instead of pretending. Writing the level is checked against
 * MODIFY_AUDIO_SETTINGS, which the manifest declares. Every call is wrapped: a refusal costs a line
 * in the log, never a crash.
 *
 * Home calls [start] when it comes up and when the choice is made, [resume] when it is back in
 * front, and [stop] when it leaves the front or is destroyed — so a film playing in another app is
 * never fought over.
 */
object Volume {
    private const val TAG = "AerialVolume"
    /**
     * `AudioManager.VOLUME_CHANGED_ACTION`, which an app may not name: the field is
     * @UnsupportedAppUsage, so only its value is public. AudioService sends it for every volume
     * group's change (main branch, AudioService.java:9706 `sendBroadcastToAll(mVolumeChanged…)`).
     */
    private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
    /**
     * The box picks the output it actually sounds through later than Home comes up, and the level is
     * stored per output: on the Phicomm X12 (Android 7.1.2) Home looked at 17.5 s after boot and was
     * told 15 of 15 — the speaker's — while AudioFlinger opened the HDMI output 4.6 s later at index
     * 4, which is the number that reaches the TV. So a level found at the top says nothing about the
     * output that is coming: it is looked at again every [SETTLE_STEP_MS] until [SETTLE_MS] has gone
     * by, Home leaves the front, or there turns out to be nothing here to move.
     */
    private const val SETTLE_STEP_MS = 3000L
    private const val SETTLE_MS = 45 * 1000L
    private var receiver: BroadcastReceiver? = null
    /** What the watch and the settle look were registered on, kept so [stop] can take both back off. */
    private var holder: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    /** When the settle look gives up; 0 while it is not running. */
    private var settleEnds = 0L

    /** What one look at the level did, so the caller knows whether looking again can come to anything. */
    private enum class Lift {
        /** Written up to the top: the output that sounds is taken care of. */
        Done,
        /** Found at the top already — of whichever output is answering today. */
        AtTop,
        /** Off, or nothing here to move, or the write refused: looking again would change nothing. */
        Skip,
    }

    /** Home coming up, and the choice being made in Personalize: the level, the watch where asked for. */
    fun start(context: Context) {
        val app = context.applicationContext
        val audio = service(app) ?: return
        holder = app
        // A lift here says only that the output answering *now* is at the top, which is the same
        // claim the look is checking, so the window is kept open either way: this is the one moment
        // the box is still deciding what its sound goes through.
        if (lift(audio, app, "start") != Lift.Skip) settle()
        watch(audio, app)
    }

    /**
     * Home back in front. 「启动时自动最大化」 is not done again here — it says 启动时, and a level the
     * owner left lower while an app was in front stays lower. Only 自动锁死 picks its watch back up,
     * and lifts once first, since whatever played in front may have turned the sound down unwatched.
     */
    fun resume(context: Context) {
        if (LiteSettings.volumeBoot != VolumeBoot.Lock) return
        val app = context.applicationContext
        val audio = service(app) ?: return
        holder = app
        lift(audio, app, "resume")
        watch(audio, app)
    }

    /** Home out of front, or going away: the watch and the looking go with it, so no other screen is fought over. */
    fun stop() {
        unwatch()
        handler.removeCallbacks(settleLook)
        settleEnds = 0L
        holder = null
    }

    private fun service(context: Context): AudioManager? =
        runCatching { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }.getOrNull()

    /**
     * The look that waits for the box's real output: quiet while it is waiting — a line every three
     * seconds would bury the one line that matters, the lift — and saying so at the end when nothing
     * was ever found below the top, since otherwise "the TV's output was at the top all along" and
     * "Home never moved anything" read the same in a log.
     */
    private fun settle() {
        handler.removeCallbacks(settleLook)
        lifted = false
        settleEnds = SystemClock.elapsedRealtime() + SETTLE_MS
        handler.postDelayed(settleLook, SETTLE_STEP_MS)
    }

    private var lifted = false

    private val settleLook = object : Runnable {
        override fun run() {
            val app = holder ?: return
            val audio = service(app) ?: return
            if (LiteSettings.volumeBoot == VolumeBoot.Off) return
            val lift = lift(audio, app, "settle")
            if (lift == Lift.Done) lifted = true
            if (lift == Lift.Skip || SystemClock.elapsedRealtime() >= settleEnds) {
                settleEnds = 0L
                if (!lifted && lift == Lift.AtTop) {
                    val max = runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
                    Log.i(TAG, "music volume ${LiteSettings.volumeBoot.key}: nothing was below $max in ${SETTLE_MS / 1000}s")
                }
                return
            }
            handler.postDelayed(this, SETTLE_STEP_MS)
        }
    }

    /**
     * The watch that keeps the level held: registered only for 自动锁死, and only while Home is in
     * front. It is the framework's own notice rather than an observer on the stored row, because on
     * the boxes that keep a volume per output that row is named after the output — `volume_music`
     * becomes `volume_music_hdmi` (AudioService.java:8693 with :9095) — and a watch by key would
     * miss the change on exactly the TVs the setting is for. The action is a protected system
     * broadcast, so Android 13+ asks for no exported/not-exported flag, and a receiver registered
     * from a live Home is outside the implicit-broadcast limits anyway.
     */
    private fun watch(audio: AudioManager, app: Context) {
        unwatch()
        if (LiteSettings.volumeBoot != VolumeBoot.Lock) return
        val r = object : BroadcastReceiver() {
            // Any stream's change is answered by putting MUSIC back: one read when it is already
            // there, which [lift] logs rather than staying quiet about. The write is silent and with
            // no flags, so the TV's panel never shows.
            override fun onReceive(context: Context, intent: Intent) { lift(audio, app, "watch") }
        }
        runCatching { app.registerReceiver(r, IntentFilter(VOLUME_CHANGED)) }
            .onFailure { Log.w(TAG, "cannot watch the volume", it) }
        receiver = r
    }

    private fun unwatch() {
        val r = receiver
        val app = holder
        receiver = null
        if (r != null && app != null) runCatching { app.unregisterReceiver(r) }
            .onFailure { Log.w(TAG, "cannot stop watching the volume", it) }
    }

    /** The music stream to full, silently; nothing when the choice is off or the level is not ours. */
    private fun lift(audio: AudioManager, context: Context, why: String): Lift {
        if (LiteSettings.volumeBoot == VolumeBoot.Off) return Lift.Skip
        if (OutputDevice.volumeFixed(context)) {
            if (why != "settle") Log.i(TAG, "this device's volume is fixed, set past it: nothing to lift")
            return Lift.Skip
        }
        val max = runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        if (max <= 0) return Lift.Skip
        val now = runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(max)
        if (now >= max) {
            // Saying so when it is already there is the only way to tell 「锁死 answering」 apart from
            // 「never registered」 in a log. The settle look keeps quiet about it, since it is the
            // same answer repeated, and reports once when it stops.
            if (why != "settle") Log.i(TAG, "music volume ${LiteSettings.volumeBoot.key} ($why): found at the top, $now of $max")
            return Lift.AtTop
        }
        // Flags 0: the TV's volume panel must not pop up over Home every time the level is put back.
        val wrote = runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0) }
            .onSuccess { Log.i(TAG, "music volume ${LiteSettings.volumeBoot.key} ($why): lifted from $now to $max") }
            .onFailure { Log.w(TAG, "setStreamVolume refused (MODIFY_AUDIO_SETTINGS not granted?)", it) }
            .isSuccess
        return if (wrote) Lift.Done else Lift.Skip
    }
}
