package com.anthonyla.paperize.core.util

/**
 * Describes an exception together with its whole cause chain on one line, e.g.
 * `IllegalStateException: decode failed ← caused by FileNotFoundException: /x.jpg`.
 *
 * Used for diagnostics (logs, error notifications) so the root cause is visible
 * without needing a connected debugger or logcat.
 */
fun Throwable.causeChain(maxDepth: Int = 8): String {
    val parts = mutableListOf<String>()
    val seen = HashSet<Throwable>()
    var current: Throwable? = this
    while (current != null && parts.size < maxDepth && seen.add(current)) {
        val message = current.message?.takeIf { it.isNotBlank() }
        parts += if (message != null) {
            "${current.javaClass.simpleName}: $message"
        } else {
            current.javaClass.simpleName
        }
        current = current.cause
    }
    return parts.joinToString(" ← caused by ")
}
