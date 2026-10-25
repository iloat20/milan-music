package com.milan.music.widget

/**
 * What tapping the widget's transport button may do in the current state.
 *
 * Kept separate from [MusicWidgetProvider] so the cold-start rule is testable without
 * Android's PendingIntent machinery.
 */
internal enum class WidgetPlayAction {
    TOGGLE,
    OPEN_APP_AND_PLAY,
}

/**
 * A widget tap is user-initiated, but Android 17's background-audio hardening still
 * gates playback by the service's while-in-use capability. Starting a queue from a
 * broadcast after process death is the one path whose WIU state is inferred rather
 * than observed, so it does not start playback directly: the button opens the app and
 * the existing visible-activity startup path owns the first play.
 *
 * A loaded queue is different. The foreground service already exists and the tap is
 * only a transport command, so it toggles in place.
 */
internal fun widgetPlayAction(hasQueue: Boolean): WidgetPlayAction =
    if (hasQueue) WidgetPlayAction.TOGGLE else WidgetPlayAction.OPEN_APP_AND_PLAY

/**
 * Re-evaluates a toggle click against the live process state.
 *
 * RemoteViews can outlive the app process, so the action chosen by [widgetPlayAction]
 * at render time is not authoritative when the click arrives.
 */
internal fun widgetToggleAction(hasQueue: Boolean): WidgetPlayAction =
    if (hasQueue) WidgetPlayAction.TOGGLE else WidgetPlayAction.OPEN_APP_AND_PLAY
