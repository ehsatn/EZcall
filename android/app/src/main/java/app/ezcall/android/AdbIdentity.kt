package app.ezcall.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Proves that a Wireless debugging connection reached this phone's real adbd before ezcall sends it a secret.
 *
 * libadb doesn't check adbd's TLS certificate, and there is nothing stable to pin: adbd makes a new random key each
 * time it starts, and pairing goes through system_server's key rather than adbd's. Apps also can't see which uid owns a
 * port. So ezcall asks the shell on the other end to call [AdbIdentityProvider], and Android reports the caller's uid.
 * Only adbd's shell (uid 2000) or root can make that call. A service that another app registers under the same mDNS
 * type can't. Later commands go over the same TLS session, which only the verified peer can read.
 */
internal object AdbIdentity {
    const val AUTHORITY_SUFFIX = ".adbcheck"
    private const val METHOD = "verify"
    private const val SHELL_UID = 2000
    private const val ROOT_UID = 0
    private val NONCE = Regex("[0-9a-f]{32}")
    private val AUTHORITY = Regex("[A-Za-z0-9._]+")
    private val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()

    class Unverified : IOException("Wireless debugging didn't prove it is this phone's adbd")

    fun newNonce(): String = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    /** The shell command that makes adbd's shell call back into ezcall with [nonce]. */
    fun command(authority: String, user: Int, nonce: String): String {
        require(authority.matches(AUTHORITY) && user >= 0 && nonce.matches(NONCE))
        return "content call --user $user --uri content://$authority --method $METHOD --arg $nonce"
    }

    /** Records [nonce] only when the shell or root sent it. */
    fun record(method: String, nonce: String?, callingUid: Int): Boolean {
        if (method != METHOD || nonce == null || !nonce.matches(NONCE)) return false
        if (!isAdbUid(callingUid)) return false
        seen.add(nonce)
        return true
    }

    /** True once, if the shell or root called back with [nonce]. */
    fun consume(nonce: String): Boolean = seen.remove(nonce)

    fun isAdbUid(uid: Int) = uid == SHELL_UID || uid == ROOT_UID
}

/** Exported for adbd. USB setup also requires an active, one-use request from the Sender screen. */
class AdbIdentityProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        AdbIdentity.record(method, arg, Binder.getCallingUid())
        return null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val uid = Binder.getCallingUid()
        if (uri.path != "/usb" || mode != "r" || !AdbIdentity.isAdbUid(uid)) throw FileNotFoundException()
        val script = CallAudio.Setup.claimUsb(checkNotNull(context), uid)
            ?: "echo 'Open Set up with USB on Sender and try again.' >&2\nexit 1\n"
        val (read, write) = ParcelFileDescriptor.createPipe()
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(script.toByteArray(Charsets.UTF_8)) }
            return read
        } catch (error: IOException) {
            read.close()
            throw error
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
                       sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
