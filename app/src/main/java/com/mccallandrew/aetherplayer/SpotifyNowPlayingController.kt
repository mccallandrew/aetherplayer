package com.mccallandrew.aetherplayer

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException

/*
 * Observes Spotify's MediaSession and forwards transport
 * commands. Requires NotificationListener access so
 * MediaSessionManager returns active sessions.
 */
class SpotifyNowPlayingController(
    context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onNowPlayingChanged(state: NowPlayingState)
    }

    data class NowPlayingState(
        val hasSession: Boolean,
        val title: String?,
        val artist: String?,
        val artwork: Bitmap?,
        val isPlaying: Boolean,
        val canPlayPause: Boolean,
        val canSkipNext: Boolean,
        val canSkipPrevious: Boolean
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val mediaSessionManager =
        appContext.getSystemService(Context.MEDIA_SESSION_SERVICE)
            as MediaSessionManager

    private val listenerComponent = ComponentName(
        appContext,
        SpotifyNotificationListener::class.java
    )

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
            attachSpotifyController(sessions)
        }

    private val controllerCallback = object : MediaController.Callback() {

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publishState()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publishState()
        }

        override fun onSessionDestroyed() {
            clearController()
            publishIdle()
        }
    }

    private var mediaController: MediaController? = null
    private var currentArtwork: Bitmap? = null
    private var started = false

    fun start() {
        if (started) {
            return
        }

        started = true

        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                listenerComponent,
                mainHandler
            )

            attachSpotifyController(
                mediaSessionManager.getActiveSessions(listenerComponent)
            )
        } catch (exception: SecurityException) {
            Log.e(TAG, "Missing notification listener access.", exception)
            publishIdle()
        }
    }

    fun stop() {
        if (!started) {
            return
        }

        started = false

        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(
                sessionsChangedListener
            )
        } catch (_: Exception) {
            // Listener may already be gone.
        }

        clearController()
        currentArtwork = null
    }

    fun playPause() {
        val controller = mediaController ?: return
        val state = controller.playbackState

        if (state?.state == PlaybackState.STATE_PLAYING) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
    }

    fun skipNext() {
        mediaController?.transportControls?.skipToNext()
    }

    fun skipPrevious() {
        mediaController?.transportControls?.skipToPrevious()
    }

    private fun attachSpotifyController(
        sessions: List<MediaController>?
    ) {
        val spotifyController = sessions?.firstOrNull { controller ->
            controller.packageName == KioskCommandReceiver.SPOTIFY_PACKAGE
        }

        if (spotifyController == null) {
            clearController()
            publishIdle()
            return
        }

        if (mediaController?.sessionToken == spotifyController.sessionToken) {
            publishState()
            return
        }

        clearController()

        mediaController = spotifyController
        spotifyController.registerCallback(controllerCallback, mainHandler)
        publishState()
    }

    private fun clearController() {
        mediaController?.unregisterCallback(controllerCallback)
        mediaController = null
    }

    private fun publishIdle() {
        currentArtwork = null

        listener.onNowPlayingChanged(
            NowPlayingState(
                hasSession = false,
                title = null,
                artist = null,
                artwork = null,
                isPlaying = false,
                canPlayPause = false,
                canSkipNext = false,
                canSkipPrevious = false
            )
        )
    }

    private fun publishState() {
        val controller = mediaController

        if (controller == null) {
            publishIdle()
            return
        }

        val metadata = controller.metadata
        val playbackState = controller.playbackState
        val actions = playbackState?.actions ?: 0L
        val isPlaying =
            playbackState?.state == PlaybackState.STATE_PLAYING

        val artwork = resolveArtwork(metadata)
        currentArtwork = artwork

        val canPause = actions and PlaybackState.ACTION_PAUSE != 0L
        val canPlay = actions and PlaybackState.ACTION_PLAY != 0L

        listener.onNowPlayingChanged(
            NowPlayingState(
                hasSession = true,
                title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                artwork = currentArtwork,
                isPlaying = isPlaying,
                canPlayPause = canPause || canPlay,
                canSkipNext =
                    actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
                canSkipPrevious =
                    actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L
            )
        )
    }

    private fun resolveArtwork(metadata: MediaMetadata?): Bitmap? {
        if (metadata == null) {
            return null
        }

        metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let {
            return it
        }

        metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let {
            return it
        }

        val artUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: return null

        return decodeBitmapUri(artUri)
    }

    private fun decodeBitmapUri(uriString: String): Bitmap? {
        return try {
            val uri = Uri.parse(uriString)

            appContext.contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)
            }
        } catch (exception: IOException) {
            Log.w(TAG, "Unable to load artwork from $uriString", exception)
            null
        } catch (exception: SecurityException) {
            Log.w(TAG, "No access to artwork $uriString", exception)
            null
        }
    }

    companion object {
        private const val TAG = "AetherPlayer"
    }
}
