package app.supeaerial.tv.lite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log

/**
 * SuperHome's 「音量」: the TV's own music volume lifted to full when Home comes up, and where the
 * choice says 锁死, kept there for as long as Home is the screen in front.
 *
 * [VolumeBoot.Max] writes once, on the way Home comes up, and does not write again when Home is
 * looked at later. [VolumeBoot.Lock] answers every change straight back, whoever made it: the
 * remote's volume keys end up adjusting STREAM_MUSIC on these boxes. [VolumeBoot.Off] leaves
 * everything alone and registers nothing.
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
    private var receiver: BroadcastReceiver? = null
    /** What the watch was registered on, kept only so [stop] can take it back off the same context. */
    private var holder: Context? = null

    /** Home coming up, and the choice being made in Personalize: the level once, and the watch where asked for. */
    fun start(context: Context) {
        val app = context.applicationContext
        val audio = service(app) ?: return
        lift(audio, app)
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
        lift(audio, app)
        watch(audio, app)
    }

    /** Home out of front, or going away: the watch goes with it, so no other screen is fought over. */
    fun stop() {
        val r = receiver
        val app = holder
        receiver = null
        holder = null
        if (r != null && app != null) runCatching { app.unregisterReceiver(r) }
            .onFailure { Log.w(TAG, "cannot stop watching the volume", it) }
    }

    private fun service(context: Context): AudioManager? =
        runCatching { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }.getOrNull()

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
        stop()
        if (LiteSettings.volumeBoot != VolumeBoot.Lock) return
        val r = object : BroadcastReceiver() {
            // Any stream's change is answered by putting MUSIC back: one read when it is already
            // there, which [lift] logs rather than staying quiet about. The write is silent and with
            // no flags, so the TV's panel never shows.
            override fun onReceive(context: Context, intent: Intent) { lift(audio, app) }
        }
        runCatching { app.registerReceiver(r, IntentFilter(VOLUME_CHANGED)) }
            .onFailure { Log.w(TAG, "cannot watch the volume", it) }
        receiver = r
        holder = app
    }

    /** The music stream to full, silently; nothing when the choice is off or the level is not ours. */
    private fun lift(audio: AudioManager, context: Context) {
        if (LiteSettings.volumeBoot == VolumeBoot.Off) return
        if (OutputDevice.volumeFixed(context)) {
            Log.i(TAG, "this device's volume is fixed, set past it: nothing to lift")
            return
        }
        val max = runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        if (max <= 0) return
        // Saying so when it is already there is the only way to tell 「锁死 answering」 apart from
        // 「never registered」 in a log, which is the question on a box whose sound is set past it.
        val now = runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(max)
        if (now >= max) {
            Log.i(TAG, "music volume ${LiteSettings.volumeBoot.key}: already at $max")
            return
        }
        // Flags 0: the TV's volume panel must not pop up over Home every time the level is put back.
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0) }
            .onSuccess { Log.i(TAG, "music volume ${LiteSettings.volumeBoot.key}: lifted to $max") }
            .onFailure { Log.w(TAG, "setStreamVolume refused (MODIFY_AUDIO_SETTINGS not granted?)", it) }
    }
}
