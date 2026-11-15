package app.scanova.core.common.log

import android.util.Log

/**
 * The only logging entry point in the app.
 *
 * Rule (docs/ARCHITECTURE.md §10): never log document titles or text, tokens,
 * keys, e-mail addresses or server-supplied messages. Callers pass a fixed
 * event name plus ids and codes. A [Throwable] is reduced to its class name,
 * because exception messages routinely embed URLs, file names and payloads.
 */
object ScanovaLog {

    fun info(tag: String, event: String) {
        Log.i(tag, event)
    }

    fun warn(tag: String, event: String, cause: Throwable? = null) {
        Log.w(tag, format(event, cause))
    }

    fun error(tag: String, event: String, cause: Throwable? = null) {
        Log.e(tag, format(event, cause))
    }

    private fun format(event: String, cause: Throwable?): String =
        if (cause == null) event else "$event (${cause.javaClass.simpleName})"
}
