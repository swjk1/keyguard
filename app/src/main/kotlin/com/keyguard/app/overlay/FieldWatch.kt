package com.keyguard.app.overlay

/**
 * Whether a thing the service just saw is allowed to take the watch away from the field it is
 * on.
 *
 * [MonitoredField] answers "may this node be read at all". This answers the question that comes
 * immediately after and had no owner: *this* node may not be read — does that mean the field we
 * were watching is over, or does it mean we should look away and carry on?
 *
 * Nothing forced that distinction while the service subscribed only to focus and text changes,
 * because both of those are the platform stating that a particular field did a particular
 * thing. `TYPE_WINDOW_CONTENT_CHANGED` is not. It is a wake-up — the handler's own doc says so
 * — fired continuously by the keyboard's window and by anything on screen that animates, and
 * what it wakes us up to read is whatever `findFocus(FOCUS_INPUT)` happens to return at that
 * instant. On a host whose input focus sits on a non-editable wrapper, or during the moment the
 * keyboard's window is the active one, that is a node [MonitoredField] rightly refuses.
 *
 * Treating that refusal as "focus moved to a field we may not read" is what made the warning
 * flash. The wake-up hid the overlay, the next keystroke re-adopted the real field and put it
 * back, and with the wake-up rate-limited to 60ms the two alternated for as long as the user
 * kept typing. Nothing was wrong with the verdict, the scan or the placement — the service was
 * being told to stop watching several times a second and starting again in between.
 *
 * So the authority of a sighting is part of the sighting. A focus change and a text change
 * speak for a field; a wake-up speaks for the screen, and a screen has no standing to end a
 * composition. See [decide].
 *
 * The same asymmetry applies one level up, to windows — see [endsComposition].
 *
 * Pure, so the whole table is checked on the JVM. The combinations that matter here are the
 * ones that only appear on someone else's phone, in someone else's app, at typing speed.
 */
object FieldWatch {

    /** How a node came to our attention, and therefore how much it proves. */
    enum class Sighting {
        /** `TYPE_VIEW_FOCUSED`. The platform stating that focus moved. Authoritative. */
        FOCUS,

        /** `TYPE_VIEW_TEXT_CHANGED`. A field reporting its own edit. Authoritative. */
        TEXT,

        /** `TYPE_WINDOW_CONTENT_CHANGED`. A wake-up. Says nothing about focus. */
        WAKE_UP,
    }

    /** What the sighting should do to the watch. */
    enum class Action {
        /** Already watching this node. Nothing to do, and in particular no re-scan. */
        KEEP,

        /** Start watching this node. */
        ADOPT,

        /**
         * Stop watching, and hide. The field being offered is one we may not read, and the
         * sighting had the authority to say focus is now on it.
         */
        RELEASE,

        /** Look away. The watch, and anything on screen because of it, stays exactly as it is. */
        IGNORE,
    }

    /**
     * @param monitorable [MonitoredField.mayMonitor] for the node that was seen.
     * @param sameAsTarget whether it is the node already being watched.
     */
    fun decide(
        sighting: Sighting,
        monitorable: Boolean,
        sameAsTarget: Boolean,
    ): Action = when {
        sameAsTarget -> Action.KEEP

        monitorable -> Action.ADOPT

        // The fix, and the only exemption in the table. A wake-up that resolved to something
        // unreadable is a wake-up we cannot use, not a password field the user just tapped
        // into. Dropping it costs nothing: if focus really did move, a FOCUS sighting for the
        // new field is on its way and will be believed.
        sighting == Sighting.WAKE_UP -> Action.IGNORE

        // Focus moved to a password field, or a text change arrived from one. This is the case
        // the protected flag exists for, and it must hide. Deliberately not weakened when
        // nothing is being watched: the flag means "the field in front of us may not be read",
        // and that is true whether or not we were mid-composition when we found out.
        else -> Action.RELEASE
    }

    /** Coarse kind of the window a `TYPE_WINDOW_STATE_CHANGED` came from. */
    enum class WindowKind {
        /** An app's own window, including the launcher. */
        APPLICATION,

        /** The keyboard. */
        KEYBOARD,

        /** Status bar, navigation bar, split-screen divider, an accessibility overlay. */
        SYSTEM,

        /** Not in the window list by the time we looked. */
        UNKNOWN,
    }

    /**
     * Whether a window changing state means the composition is over.
     *
     * The service ends a composition on window-state changes because that is how it notices
     * somebody left the app — and it does so unconditionally, after an earlier attempt to guard
     * it on "did the field really go away" left a warning about one app's text floating over
     * another's. That is the right instinct and the wrong axis. The guard belongs on *which
     * window changed*, not on what became of the field.
     *
     * The keyboard's window changes state constantly while it is being used: an emoji panel
     * opening, a height change, one-handed mode, voice input. None of those is the user going
     * anywhere, and every one of them was ending the composition — which hid the warning, reset
     * the rolling context, and reported a spurious `ABANDONED_SWITCHED` to the parent for a
     * message still being typed. System windows are the same story: a notification shade pulled
     * down and pushed back up leaves the user exactly where they were.
     *
     * [WindowKind.UNKNOWN] ends the composition. A window that is no longer in the list is
     * usually a popup that has already gone, but "hide when unsure" is the only safe default
     * here: the cost of being wrong is a warning that vanished, and the cost of the other
     * default is a warning about a conversation the user has left.
     */
    fun endsComposition(kind: WindowKind): Boolean = when (kind) {
        WindowKind.KEYBOARD, WindowKind.SYSTEM -> false
        WindowKind.APPLICATION, WindowKind.UNKNOWN -> true
    }
}
