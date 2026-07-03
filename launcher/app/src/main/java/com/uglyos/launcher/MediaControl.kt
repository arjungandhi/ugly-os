package com.uglyos.launcher

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * A do-nothing notification listener. We never read notifications through it —
 * its only job is to be an *enabled* listener component, because that's the one
 * credential Android accepts for [MediaSessionManager.getActiveSessions]. Once
 * the user turns it on, we can see and drive whatever app is playing audio.
 */
class UglyNotificationListenerService : NotificationListenerService()

/** The component the media APIs want as proof we're an enabled listener. */
fun mediaListenerComponent(context: Context): ComponentName =
    ComponentName(context, UglyNotificationListenerService::class.java)

/**
 * Whether the user has granted notification-listener access, the gate for
 * reading active media sessions. Granted on a system screen, so recheck on
 * resume rather than caching.
 */
fun hasMediaAccess(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

/**
 * A snapshot of the current media session for the home-screen control: what's
 * playing and which transport moves are on offer. [isPlaying] drives the
 * play/pause glyph; a bar shows for a paused session too so you can resume it.
 */
data class NowPlaying(
    val title: String,
    val artist: String?,
    val isPlaying: Boolean,
    val canPrev: Boolean,
    val canNext: Boolean,
)

/** Playback states worth surfacing — a live session, playing or paused. */
private fun PlaybackState?.isLive(): Boolean = when (this?.state) {
    PlaybackState.STATE_PLAYING,
    PlaybackState.STATE_BUFFERING,
    PlaybackState.STATE_PAUSED -> true
    else -> false
}

/** True while sound is (or is about to be) coming out, vs. merely paused. */
private fun PlaybackState?.isPlaying(): Boolean =
    this?.state == PlaybackState.STATE_PLAYING || this?.state == PlaybackState.STATE_BUFFERING

/**
 * Of the active sessions (ordered most-recent first), the one to control: a
 * playing session wins over a paused one, so hitting play in a second app hands
 * the bar over to it. Null when nothing is live.
 */
fun pickMediaController(controllers: List<MediaController>): MediaController? =
    controllers.firstOrNull { it.playbackState.isPlaying() }
        ?: controllers.firstOrNull { it.playbackState.isLive() }

/** A [NowPlaying] for this controller, or null when its session isn't live. */
fun MediaController.toNowPlaying(): NowPlaying? {
    val state = playbackState
    if (!state.isLive()) return null
    val md = metadata
    val title = md?.text(MediaMetadata.METADATA_KEY_TITLE)
        ?: md?.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        ?: "playing"
    val artist = md?.text(MediaMetadata.METADATA_KEY_ARTIST)
        ?: md?.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        ?: md?.text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
    val actions = state?.actions ?: 0L
    return NowPlaying(
        title = title,
        artist = artist,
        isPlaying = state.isPlaying(),
        canPrev = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
        canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
    )
}

/** A trimmed, non-blank metadata string, or null. */
private fun MediaMetadata.text(key: String): String? =
    getText(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
