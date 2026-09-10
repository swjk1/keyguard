package com.keyguard.app.input

/**
 * The keyboard's own record of what the user has typed into the current field.
 *
 * Necessary because the platform's view of the field is unreliable for this purpose: on
 * Android `getTextBeforeCursor` costs an IPC round trip per call, and on iOS
 * `documentContextBeforeInput` is truncated to roughly the last two sentences and
 * `textDidChange` does not even fire on every text change. Maintaining our own buffer gives
 * both platforms one consistent, complete view to scan.
 *
 * **In-memory only. Never persisted.** [clear] must be called when the input field changes
 * so text never crosses apps or conversations.
 */
class ShadowBuffer(private val maxLength: Int = DEFAULT_MAX_LENGTH) {

    private val builder = StringBuilder()

    val text: String get() = builder.toString()
    val length: Int get() = builder.length
    val isEmpty: Boolean get() = builder.isEmpty()

    fun insert(fragment: String) {
        if (fragment.isEmpty()) return
        builder.append(fragment)
        trimToCap()
    }

    /** Removes up to [count] characters from the end. Returns how many were actually removed. */
    fun deleteBackward(count: Int = 1): Int {
        if (count <= 0 || builder.isEmpty()) return 0
        val removed = minOf(count, builder.length)
        builder.setLength(builder.length - removed)
        return removed
    }

    fun clear() {
        builder.setLength(0)
    }

    /**
     * Reconciles against the host field's actual content.
     *
     * Our buffer drifts whenever something other than our keys changes the field — a paste,
     * a cursor move, an autocomplete, or the host clearing the input on send. When the host
     * disagrees, the host is right.
     *
     * @return true if the buffer was corrected, meaning the change came from outside us.
     */
    fun reconcile(hostTextBeforeCursor: CharSequence?): Boolean {
        val host = hostTextBeforeCursor?.toString() ?: ""
        val mine = builder.toString()

        // The host view may be a truncated tail of a longer field, so a suffix match still
        // counts as agreement rather than drift.
        if (mine == host || (host.isNotEmpty() && mine.endsWith(host))) return false

        builder.setLength(0)
        builder.append(host)
        trimToCap()
        return true
    }

    private fun trimToCap() {
        if (builder.length > maxLength) {
            builder.delete(0, builder.length - maxLength)
        }
    }

    companion object {
        /**
         * Bounded so a pathological field cannot grow the buffer without limit. Well above
         * any realistic message, and far below the iOS extension memory ceiling.
         */
        const val DEFAULT_MAX_LENGTH: Int = 4_000
    }
}
