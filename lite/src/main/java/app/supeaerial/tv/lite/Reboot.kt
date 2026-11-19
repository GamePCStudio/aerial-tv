package app.supeaerial.tv.lite

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SuperHome's 「重启设备」. A Home app is not normally allowed to switch the TV off and on again, so
 * this walks every way an app can ask and leaves the reason for each refusal in the log.
 *
 * The order is by how sure the answer is:
 *
 *  1. [PowerManager.reboot] — needs REBOOT, signature|privileged. It lands when the APK sits in the
 *     firmware (/system/priv-app with the permission granted) or is platform-signed; installed from
 *     an APK it throws [SecurityException], which is the case on every TV SuperHome has run on so far.
 *  2. root's `reboot` — where the firmware ships a `su` that answers, as plenty of Chinese boxes do.
 *     It runs in a thread of its own: a `su` that stops for a prompt nobody answers would otherwise
 *     hold Home's worker for the rest of the session.
 *  3. [AdbReboot] — the TV's own adbd on loopback port 5555. Those boxes are adb-over-network already,
 *     so the port is open without a cable, and the order it gives is `reboot:` — the one `adb reboot`
 *     sends, which adbd carries out itself by writing the `sys.powerctl` property that init obeys. That
 *     is the whole point: the permission to write it is adbd's in the SELinux policy, not this app's
 *     and not a shell's, so it reaches TVs where 1 and 2 both miss — including a user build whose adbd
 *     is only uid shell. Where adbd wants to be told who is calling, the TV is asked to authorize a key
 *     of its own, once, the same prompt `adb connect` gets.
 *  4. the firmware's own shutdown page, then the REBOOT broadcast — AOSP guards both with the same
 *     REBOOT the first route already lacked, but some MStar and Realtek builds leave them open.
 *
 * The device-owner route is deliberately not tried: `DevicePolicyManager.reboot` takes the component
 * of a `DeviceAdminReceiver`, so it only answers for an app that declares one and has been set as the
 * device owner with it. SuperHome declares no admin and asking to be one — which can then lock the
 * screen and enforce encryption among other things — is far more of the TV than a reboot row is worth.
 *
 * Called from Home's worker thread, never the main one: the last routes wait, and a `su` can block.
 * Nothing here returns a verdict: a route that works stops this app and never gets back to the
 * caller, so the caller only ever hears "still awake" — and says so, rather than leaving the row
 * looking broken.
 */
object Reboot {
    private const val TAG = "AerialReboot"
    /** Room given to a TV that was told to go down, before Home calls the attempt refused. */
    private const val SHUTDOWN_WAIT_MS = 6000L
    private const val SU_WAIT_MS = 6000L
    /** Where a `su` is kept on these firmwares. The bare name is what a live PATH would find. */
    private val SUS = arrayOf("su", "/system/xbin/su", "/system/bin/su", "/sbin/su")
    /** AOSP's shutdown activity, and the broadcast the framework sends when it reboots itself. */
    private const val SHUTDOWN_ACTION = "android.intent.action.ACTION_REQUEST_SHUTDOWN"
    private const val REBOOT_ACTION = "android.intent.action.REBOOT"

    fun attempt(context: Context) {
        val app = context.applicationContext
        route("PowerManager.reboot") {
            (app.getSystemService(Context.POWER_SERVICE) as PowerManager).reboot(null)
        }
        su()
        // The TV's own adb, where its default port is open: `reboot:` over loopback, which is the order
        // `adb reboot` gives and adbd does the writing. It needs neither this app's REBOOT nor a `su`,
        // only adbd — and the boxes SuperHome runs on are adb-over-network already, so that is usually
        // there. Once adbd has taken the order, asking the shutdown page as well would only put a page up
        // on a TV that is already going down.
        if (AdbReboot.attempt(app)) {
            Log.i(TAG, "adbd: the order is given, so nothing else is asked")
            runCatching { Thread.sleep(SHUTDOWN_WAIT_MS) }
            return
        }
        // Only worth asking where this firmware has someone to answer, since a request that goes
        // nowhere is otherwise indistinguishable from a reboot that is on its way.
        page(app, SHUTDOWN_ACTION)
        broadcast(app, REBOOT_ACTION)
        Log.i(TAG, "every route answered; giving the TV a moment before calling this refused")
        runCatching { Thread.sleep(SHUTDOWN_WAIT_MS) }
    }

    /** A call that either takes the device down or says why it did not. */
    private fun route(name: String, block: () -> Unit) {
        runCatching(block).onFailure { Log.i(TAG, "$name: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** Root's reboot, where a `su` is on the box and this app may run it; its own thread, so a silent prompt cannot hold Home. */
    private fun su() {
        // Asking first is the only way the log can tell a box with no root from a box whose root is
        // not for apps: an exec refused for want of an execute bit says `IOException` either way.
        val bin = SUS.firstOrNull { runCatching { File(it).canExecute() }.getOrDefault(false) }
        if (bin == null) { Log.i(TAG, "su: nothing runnable in $SUS"); return }
        val exited = AtomicBoolean(false)
        val t = Thread {
            runCatching {
                val p = Runtime.getRuntime().exec(arrayOf(bin, "-c", "reboot"))
                p.outputStream.use { it.write("reboot\n".toByteArray()) }
                val code = p.waitFor()
                exited.set(true)
                Log.i(TAG, "su -c reboot exited $code")
            }.onFailure { Log.i(TAG, "su: ${it.javaClass.simpleName}: ${it.message}") }
        }.apply { isDaemon = true }
        t.start()
        runCatching { t.join(SU_WAIT_MS) }
        if (!exited.get() && t.isAlive) Log.i(TAG, "su is still thinking: leaving it behind")
    }

    /** AOSP's shutdown activity, where this firmware exports one; the wait at the end is its proof. */
    private fun page(context: Context, action: String) {
        val who = runCatching { context.packageManager.resolveActivity(Intent(action), 0) }
            .getOrNull()?.activityInfo?.takeIf { it.exported }
        if (who == null) { Log.i(TAG, "$action: no such page on this TV"); return }
        Log.i(TAG, "$action: asked ${who.packageName}")
        route(action) { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** The framework's reboot broadcast, where an exported receiver is listening. */
    private fun broadcast(context: Context, action: String) {
        val who = runCatching { context.packageManager.queryBroadcastReceivers(Intent(action), 0) }
            .getOrNull()?.firstOrNull { it.activityInfo.exported }?.activityInfo
        if (who == null) { Log.i(TAG, "$action: no receiver on this TV"); return }
        Log.i(TAG, "$action: asked ${who.packageName}")
        route(action) { context.sendBroadcast(Intent(action)) }
    }
}
