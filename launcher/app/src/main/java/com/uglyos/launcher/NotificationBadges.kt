package com.uglyos.launcher

import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which apps currently have an active, dismissable notification — the signal
 * behind the dock's status dots ("you have an unread text over here"). Written
 * by [UglyNotificationListenerService] as notifications come and go, read by the
 * home-screen dock.
 *
 * A process-wide singleton because the listener service and the ui live in the
 * same process but have no other handle on each other: the system owns the
 * service instance, so this StateFlow is how it hands state to Compose.
 *
 * Empty whenever the listener isn't connected (access not granted, or torn
 * down), so the dock simply shows no dots rather than stale ones.
 */
object NotificationBadges {
    private val _packages = MutableStateFlow<Set<String>>(emptySet())

    /** Packages with at least one badge-worthy notification right now. */
    val packages: StateFlow<Set<String>> = _packages.asStateFlow()

    /** Replace the set wholesale — the listener recomputes it from scratch. */
    fun update(packages: Set<String>) {
        _packages.value = packages
    }

    /** Forget everything; used when the listener disconnects. */
    fun clear() {
        _packages.value = emptySet()
    }
}

/**
 * Whether a notification should light up a dock dot. We only count ones the user
 * can act on and clear — a real message, not furniture. [StatusBarNotification.isClearable]
 * drops ongoing notifications (media playback, active calls, foreground-service
 * pins), and we skip our own so the launcher never badges itself.
 */
fun StatusBarNotification.isBadgeWorthy(selfPackage: String): Boolean =
    isClearable && packageName != selfPackage
