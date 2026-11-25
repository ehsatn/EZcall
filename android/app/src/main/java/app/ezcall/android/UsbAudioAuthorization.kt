package app.ezcall.android

internal class UsbAudioAuthorization(private val now: () -> Long) {
    class Request(val expiresAt: Long) {
        @Volatile var claimedAt: Long? = null
            private set

        fun claim(at: Long): Boolean {
            if (claimedAt != null || at >= expiresAt) return false
            claimedAt = at
            return true
        }
    }

    private var pending: Request? = null

    @Synchronized fun begin(window: Long): Request = Request(now() + window).also { pending = it }

    @Synchronized fun claim(uid: Int): Boolean = AdbIdentity.isAdbUid(uid) && pending?.claim(now()) == true

    @Synchronized fun cancel(request: Request) {
        if (pending === request) pending = null
    }

    @Synchronized fun isPending(request: Request, startupWindow: Long): Boolean = pending === request &&
        now() < (request.claimedAt?.plus(startupWindow) ?: request.expiresAt)

    @Synchronized fun hasRequest(startupWindow: Long) = pending?.let { isPending(it, startupWindow) } == true
}
