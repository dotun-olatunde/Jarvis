package ng.ainow.jarvis.actions

/** A follow-up deadline that starts once the assistant is ready to listen. */
internal class AnswerWindow(private val nowMs: () -> Long) {
    private var deadlineMs: Long? = null

    val remainingMs: Long?
        get() = deadlineMs?.let { (it - nowMs()).coerceAtLeast(0) }

    fun start(): Long {
        if (deadlineMs == null) deadlineMs = nowMs() + 30_000L
        return checkNotNull(remainingMs)
    }

    fun reset() {
        deadlineMs = null
    }
}
