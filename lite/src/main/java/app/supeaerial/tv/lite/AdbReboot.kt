package app.supeaerial.tv.lite

import android.os.SystemClock
import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * SuperHome's 「重启设备」 as the TV's own adb client: where adbd is listening on its default port,
 * open it on loopback and ask for the reboot the way `adb reboot` does — one `shell:` stream running
 * `reboot`, no cable and no second machine.
 *
 * Worth a route of its own because it lands where the other two miss. The boxes SuperHome runs on are
 * adb-over-network already, so port 5555 is open on the TV without anybody touching it, and adbd's own
 * uid is root on plenty of these firmwares; on the newer ones `reboot` is only a `sys.powerctl` property
 * write that init obeys, which the shell uid is allowed to do. Neither needs this app to hold REBOOT,
 * which an APK installed from storage never gets, or a `su` that answers, which plenty of boxes lack.
 *
 * Deliberately narrow, so nothing here can reach past one reboot:
 *  - loopback and 5555 only — the port `service.adb.tcp.port` is set to. Never the LAN address, so this
 *    cannot be pointed at another TV on the network.
 *  - no features asked for (the banner is the bare `host::`), so adbd hands over the plain `shell:`
 *    service it has had since Android 4, and the `shell,v2:` framing never comes up.
 *  - no RSA authentication: an adbd that demands one answers with AUTH before anything else, and this
 *    gives up there. SuperHome holds no adb key and is not going to start keeping one.
 *  - one command. The frames after it are read only to log what the TV said back.
 *
 * Runs on Home's worker thread and bounds itself: 400 ms to open the socket, 2.5 s per frame, 8 s in
 * all. A refusal says which step stopped, because that is the difference between a TV whose adb is off,
 * a TV whose adb is locked, and a TV that took the order and is already going down.
 */
object AdbReboot {
    private const val TAG = "AerialReboot"
    private const val HOST = "127.0.0.1"
    private const val PORT = 5555
    private const val CONNECT_TIMEOUT_MS = 400
    private const val READ_TIMEOUT_MS = 2500
    private const val TOTAL_MS = 8000L

    /**
     * adb's version word, and the size of frame this side can take. Both are what the real `adb` client
     * puts in its own first CNXN — checked against adb 1.0.41 talking to a stand-in adbd — except the
     * frame size, which is kept at 16 KiB on purpose: adbd answers with no more than this side
     * advertised, and 16 KiB is more than a `reboot` ever says back.
     */
    private const val VERSION = 0x01000001
    private const val MAX_DATA = 16384
    /** This side's id for the one stream it opens: adb's local ids start at 1. */
    private const val LOCAL_ID = 1

    /**
     * True once adbd has accepted `shell:reboot`: from there the TV is on its way down and nothing this
     * app does matters. False means the order was not given, so the caller may still try its other
     * routes.
     */
    fun attempt(): Boolean {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.i(TAG, "adbd: $HOST:$PORT is not open (${e.javaClass.simpleName}: ${e.message})")
            runCatching { socket.close() }
            return false
        }
        var opened = false
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            val input = DataInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            frame(out, "CNXN", VERSION, MAX_DATA, "host::")
            val until = SystemClock.elapsedRealtime() + TOTAL_MS
            var asked = false
            while (SystemClock.elapsedRealtime() < until) {
                val head = ByteArray(24)
                input.readFully(head)
                // The first four bytes of a header are the command's own letters, little-endian like
                // every other field, so they read as text rather than as a number to be transcribed.
                val cmd = String(head, 0, 4, Charsets.US_ASCII)
                val length = le(head, 12)
                if (length < 0 || length > MAX_DATA) {
                    Log.i(TAG, "adbd: a $cmd frame claiming $length bytes, so this stopped")
                    return opened
                }
                val body = ByteArray(length)
                if (length > 0) input.readFully(body)
                val text = String(body, Charsets.UTF_8).trim('\u0000')
                when (cmd) {
                    "CNXN" -> {
                        Log.i(TAG, "adbd: ${if (text.isEmpty()) "answered" else text}")
                        frame(out, "OPEN", LOCAL_ID, 0, "shell:reboot")
                        asked = true
                    }
                    "AUTH" -> {
                        Log.i(TAG, "adbd: wants RSA authentication, which this app holds no key for")
                        return false
                    }
                    "OKAY" -> if (asked && !opened) {
                        opened = true
                        Log.i(TAG, "adbd: took shell:reboot")
                    }
                    // The command's output, errors merged in. Answering the frame is what the protocol
                    // asks for; logging what it says is what tells a refused `reboot` from a missing one.
                    "WRTE" -> {
                        if (opened) frame(out, "OKAY", LOCAL_ID, le(head, 4))
                        if (text.isNotEmpty()) Log.i(TAG, "adbd: the TV said: $text")
                    }
                    "CLSE", "CLBE" -> {
                        Log.i(TAG, "adbd: closed the stream ${if (opened) "after the order" else "instead of taking it"}")
                        return opened
                    }
                    else -> Log.i(TAG, "adbd: a $cmd frame, ignored")
                }
            }
            // Silence from here on is the expected shape of success: the TV is going down and the frames
            // stop arriving, so the only question left is whether the order was ever taken.
            Log.i(TAG, "adbd: ${if (opened) "said nothing more, which is what a TV that is going down does" else "answered nothing usable"}")
            return opened
        } catch (e: SocketTimeoutException) {
            Log.i(TAG, "adbd: ${if (opened) "no more frames after the order was taken" else "no reply within ${READ_TIMEOUT_MS}ms"}")
            return opened
        } catch (e: IOException) {
            // The stream ending is how an adbd that has just rebooted the TV says goodbye, so it counts
            // as success whenever the order had already been taken.
            Log.i(TAG, "adbd: the stream ended (${e.javaClass.simpleName}: ${e.message})${if (opened) ", after the order was taken" else ""}")
            return opened
        } catch (e: Exception) {
            Log.i(TAG, "adbd: ${e.javaClass.simpleName}: ${e.message}")
            return false
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * One adb message: 24 bytes of header, then its body. The header is command, arg0, arg1, length,
     * checksum, magic — every field little-endian, the checksum the plain sum of the body's bytes, and
     * the magic the command with all its bits flipped. The order matters and is not the order the words
     * read nicely: it is what a real adb 1.0.41 client puts on the wire, read back off a stand-in adbd
     * that answers the frames the way AOSP's does — the magic last, checked, with the length.
     *
     * That client's own `OPEN` carries a checksum of 0 where its body sums to 2766, so the checksum is
     * not one of adbd's checks today. Ours is filled in anyway: the field exists, and a sum that is
     * right has never got a frame refused.
     */
    private fun frame(out: OutputStream, cmd: String, arg0: Int, arg1: Int, body: String? = null) {
        // adb's strings go on the wire as C strings, so the trailing NUL counts toward the length. A
        // frame that carries nothing — an OKAY — holds no body at all, not even that NUL.
        val bytes = if (body == null) ByteArray(0) else (body + '\u0000').toByteArray(Charsets.UTF_8)
        val tag = cmd.toByteArray(Charsets.US_ASCII)
        var sum = 0
        for (b in bytes) sum += b.toInt() and 0xFF
        out.write(tag)
        putInt(out, arg0)
        putInt(out, arg1)
        putInt(out, bytes.size)
        putInt(out, sum)
        for (b in tag) out.write(b.toInt() xor 0xFF)
        out.write(bytes)
    }

    private fun putInt(out: OutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 24) and 0xFF)
    }

    private fun le(bytes: ByteArray, at: Int): Int {
        var value = 0
        for (step in 0..3) value = value or ((bytes[at + step].toInt() and 0xFF) shl (8 * step))
        return value
    }
}
