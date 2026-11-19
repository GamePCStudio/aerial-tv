package app.supeaerial.tv.lite

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

/**
 * SuperHome's 「重启设备」 as the TV's own adb client: where adbd is listening on its default port,
 * open it on loopback and order the reboot the way `adb reboot` does — the `reboot:` service, no cable
 * and no second machine.
 *
 * Worth a route of its own because it lands where the others miss. The boxes SuperHome runs on are
 * adb-over-network already, so port 5555 is open on the TV without anybody touching it, and adbd does
 * the reboot itself rather than asking anyone with a permission: [order] hands over `reboot:`, whose
 * handler in adbd writes the `sys.powerctl` property that init obeys, and the SELinux policy gives the
 * adbd domain that property (`set_prop(adbd, powerctl_prop)` in private/adbd.te) while the shell domain
 * has no such line — which is why the order is `reboot:` and not a shell running `reboot`, and why this
 * needs neither this app's REBOOT nor a `su` that answers. The MiTV that first showed this up is the
 * case in point: user build, adbd as uid shell, `ro.adb.secure=1`.
 *
 * Deliberately narrow, so nothing here can reach past one reboot:
 *  - loopback and 5555 only — the port `service.adb.tcp.port` is set to. Never the LAN address, so this
 *    cannot be pointed at another TV on the network.
 *  - the one service name `reboot:` (and `shell:reboot` only where that is refused). Nothing else is
 *    ever opened, so no file, no property write, no other command.
 *  - the key: a 2048-bit RSA pair made on the TV the first time adbd asks for authentication, kept in
 *    this app's own private directory, and used for nothing except signing the token adbd offers. Its
 *    public half is what the TV is asked to authorize — the same dialog `adb connect` gets.
 *
 * Runs on Home's worker thread and bounds itself: 400 ms to open the socket, 2.5 s per frame, 8 s in
 * all — 20 s while the TV is waiting for somebody to confirm the authorization dialog, because that one
 * step needs a person. A refusal says which step stopped, since that is the difference between a TV
 * whose adb is off, a TV whose adb is not yet authorized, and a TV that took the order already.
 */
object AdbReboot {
    private const val TAG = "AerialReboot"
    private const val HOST = "127.0.0.1"
    private const val PORT = 5555
    private const val CONNECT_TIMEOUT_MS = 400
    private const val READ_TIMEOUT_MS = 2500
    private const val TOTAL_MS = 8000L
    private const val AUTH_WAIT_MS = 20000L

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
    /** AUTH's three types, from the protocol's own table. */
    private const val AUTH_TOKEN = 1
    private const val AUTH_SIGNATURE = 2
    private const val AUTH_PUBLIC_KEY = 3

    /** The TV's own pair, made once and kept; the private half never leaves this process. */
    private var privateKey: PrivateKey? = null
    private var publicKey: String? = null
    /** Set when a connection ended because the TV was still being asked to authorize this key. */
    private var awaitingAuth = false

    /**
     * True once adbd has taken the reboot. False means the order was not taken, so the caller may still
     * try its other routes. Two orders are worth asking for: `reboot:` is the one `adb reboot` sends and
     * is adbd doing the work itself; `shell:reboot` only matters on the older boxes where adbd runs as
     * root and does not know the service, and is asked for only after the first is refused.
     */
    fun attempt(context: Context): Boolean {
        val app = context.applicationContext
        awaitingAuth = false
        if (order(app, "reboot:")) return true
        // The dialog was shown and left unanswered. A second connection would only ask the same person
        // the same question again, twice in a row, and hold this thread for another 20 s doing it.
        if (awaitingAuth) {
            Log.i(TAG, "adbd: the TV was asked to allow this key and nobody answered, so nothing was tried further")
            return false
        }
        return order(app, "shell:reboot")
    }

    /** One connection, one order. Everything it logs names the step that stopped. */
    private fun order(context: Context, service: String): Boolean {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.i(TAG, "adbd: $HOST:$PORT is not open (${e.javaClass.simpleName}: ${e.message})")
            runCatching { socket.close() }
            return false
        }
        var opened = false
        var asked = false
        var signed = false
        var offered = false
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            val input = DataInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            frame(out, "CNXN", VERSION, MAX_DATA, "host::")
            var until = SystemClock.elapsedRealtime() + TOTAL_MS
            while (SystemClock.elapsedRealtime() < until) {
                val head = ByteArray(24)
                input.readFully(head)
                // The first four bytes of a header are the command's own letters, little-endian like
                // every other field, so they read as text rather than as a number to be transcribed.
                val cmd = String(head, 0, 4, Charsets.US_ASCII)
                val type = le(head, 4)
                val length = le(head, 12)
                if (length < 0 || length > MAX_DATA) {
                    Log.i(TAG, "adbd: a $cmd frame claiming $length bytes, so this stopped")
                    return false
                }
                val body = ByteArray(length)
                if (length > 0) input.readFully(body)
                when (cmd) {
                    "CNXN" -> {
                        val banner = String(body, Charsets.UTF_8).trim('\u0000')
                        Log.i(TAG, "adbd: ${if (banner.isEmpty()) "answered" else banner}")
                        frame(out, "OPEN", LOCAL_ID, 0, service)
                        asked = true
                    }
                    "AUTH" -> when (type) {
                        // One answer per token, which is what adbd's side requires: it keeps a single
                        // token, and verifies whatever signature frame arrives against that one. A client
                        // that answered the same token twice would leave adbd holding a newer token than
                        // the next signature covers, and the two would stay out of step forever.
                        //
                        // So the order is adbd's own, the one `adb connect` follows: sign the first
                        // token, offer the public key for the next one if that signature was refused,
                        // and after the offer sign every token that arrives — the moment somebody
                        // confirms the dialog on the TV, the next signature is the one adbd accepts and
                        // this reboot lands without a second press.
                        AUTH_TOKEN -> {
                            if (!keys(context)) return false
                            if (!signed && !offered) {
                                signed = true
                                // The protocol caps what may be sent before the peer's CONNECT at 4096
                                // bytes, and a 2048-bit signature is 256 of them.
                                val signature = sign(body) ?: return false
                                frame(out, "AUTH", AUTH_SIGNATURE, 0, signature)
                                Log.i(TAG, "adbd: signed its token, waiting to see whether it is trusted")
                            } else if (!offered) {
                                offered = true
                                val key = publicKey ?: return false
                                frame(out, "AUTH", AUTH_PUBLIC_KEY, 0, "$key SuperHome@tv")
                                Log.i(TAG, "adbd: did not know that key, so it has the public one now and the" +
                                    " TV should be asking to allow debugging — say yes and this presses on")
                                // The read timeout is what actually stops this loop, so the wait for a
                                // person has to widen that too — extending only the deadline would let the
                                // blocking read give up after 2.5 s of an unanswered dialog.
                                socket.soTimeout = AUTH_WAIT_MS.toInt()
                                until = SystemClock.elapsedRealtime() + AUTH_WAIT_MS
                            } else {
                                val signature = sign(body) ?: return false
                                frame(out, "AUTH", AUTH_SIGNATURE, 0, signature)
                            }
                        }
                        else -> Log.i(TAG, "adbd: an AUTH frame of type $type, ignored")
                    }
                    "OKAY" -> if (asked && !opened) {
                        opened = true
                        Log.i(TAG, "adbd: took $service")
                    }
                    // Anything the service writes back is its refusal: `reboot:` answers the success path
                    // by saying nothing and going down, and the failure path with the reason — adbd's own
                    // handler prints "reboot (reboot,adb) failed" when the property write is denied.
                    "WRTE" -> {
                        if (opened) frame(out, "OKAY", LOCAL_ID, le(head, 4))
                        val text = String(body, Charsets.UTF_8).trim('\u0000')
                        Log.i(TAG, "adbd: refused $service: $text")
                        return false
                    }
                    "CLSE", "CLBE" -> {
                        Log.i(TAG, "adbd: closed the stream ${if (opened) "after $service" else "instead of taking it"}")
                        return opened
                    }
                    else -> Log.i(TAG, "adbd: a $cmd frame, ignored")
                }
            }
            // Silence from here on is the expected shape of success: the TV is going down and the frames
            // stop arriving, so the only question left is whether the order was ever taken.
            if (!opened) awaitingAuth = offered
            if (opened) Log.i(TAG, "adbd: said nothing more, which is what a TV that is going down does")
            else Log.i(TAG, "adbd: answered nothing usable for $service")
            return opened
        } catch (e: SocketTimeoutException) {
            if (!opened) awaitingAuth = offered
            if (opened) Log.i(TAG, "adbd: no more frames after $service was taken")
            else Log.i(TAG, "adbd: no reply within ${socket.soTimeout}ms")
            return opened
        } catch (e: IOException) {
            // The stream ending is how an adbd that has just rebooted the TV says goodbye, so it counts
            // as success whenever the order had already been taken.
            Log.i(TAG, "adbd: the stream ended (${e.javaClass.simpleName}: ${e.message})" +
                "${if (opened) ", after $service was taken" else ""}")
            return opened
        } catch (e: Exception) {
            Log.i(TAG, "adbd: ${e.javaClass.simpleName}: ${e.message}")
            return false
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * AUTH's token, signed the way adbd verifies it: RSA with SHA-1 over the 20 bytes it offered
     * (`RSA_sign(NID_sha1, …)` in adb's own auth code), which is what SHA1withRSA produces.
     */
    private fun sign(token: ByteArray): ByteArray? {
        val key = privateKey ?: return null
        return try {
            val signature = Signature.getInstance("SHA1withRSA")
            signature.initSign(key)
            signature.update(token)
            signature.sign()
        } catch (e: Exception) {
            Log.i(TAG, "adbd: signing its token failed (${e.javaClass.simpleName}: ${e.message})")
            null
        }
    }

    /**
     * The TV's own key pair: read back from this app's private directory if it has been made there
     * before, otherwise made now. Making it costs a second or two on a 4.x box, which is why this only
     * runs when adbd actually asks for authentication — a box with `ro.adb.secure=0` never gets here.
     */
    private fun keys(context: Context): Boolean {
        if (privateKey != null && publicKey != null) return true
        val dir = File(context.filesDir, "adb")
        val secret = File(dir, "adbkey")
        val offeredKey = File(dir, "adbkey.pub")
        if (secret.canRead() && offeredKey.canRead()) {
            try {
                privateKey = KeyFactory.getInstance("RSA").generatePrivate(
                    PKCS8EncodedKeySpec(Base64.decode(secret.readText().trim(), Base64.NO_WRAP))
                )
                publicKey = offeredKey.readText().trim()
                Log.i(TAG, "adbd: using the key this app saved before")
                return true
            } catch (e: Exception) {
                Log.i(TAG, "adbd: the saved key is unreadable (${e.javaClass.simpleName}: ${e.message}), making a new one")
            }
        }
        return try {
            dir.mkdirs()
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            val pair = generator.generateKeyPair()
            // Both halves in adb's own shape: the private one base64 PKCS#8, the public one base64
            // SubjectPublicKeyInfo, which is what an adb server's adbkey and adbkey.pub files hold.
            val publicText = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)
            secret.writeText(Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
            offeredKey.writeText(publicText)
            privateKey = pair.private
            publicKey = publicText
            Log.i(TAG, "adbd: made a key for this TV, kept in ${dir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.i(TAG, "adbd: no key to offer (${e.javaClass.simpleName}: ${e.message})")
            false
        }
    }

    /**
     * One adb message: 24 bytes of header, then its body. The header is command, arg0, arg1, length,
     * checksum, magic — every field little-endian, the checksum the plain sum of the body's bytes, and
     * the magic the command with all its bits flipped. The order is not the order the words read
     * nicely and it matters: a real adb 1.0.41 client puts the magic last, which its own frames show
     * when a stand-in adbd parses them, and adbd checks that magic and the length.
     *
     * That client's own `OPEN` carries a checksum of 0 where its body sums to 2766, so the checksum is
     * not one of adbd's checks today. Ours is filled in anyway: the field exists, and a sum that is
     * right has never got a frame refused.
     */
    private fun frame(out: OutputStream, cmd: String, arg0: Int, arg1: Int, body: ByteArray?) {
        val bytes = body ?: ByteArray(0)
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

    /** adb's strings go on the wire as C strings, so the trailing NUL counts toward the length. */
    private fun frame(out: OutputStream, cmd: String, arg0: Int, arg1: Int, body: String) =
        frame(out, cmd, arg0, arg1, (body + '\u0000').toByteArray(Charsets.UTF_8))

    /** A frame that carries nothing — an OKAY — is 24 bytes of header and no body, not one NUL. */
    private fun frame(out: OutputStream, cmd: String, arg0: Int, arg1: Int) = frame(out, cmd, arg0, arg1, null)

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
