package com.mccallandrew.aetherplayer

import android.service.notification.NotificationListenerService

/*
 * Exists so MediaSessionManager can return active sessions.
 * Device Owner grants access via setNotificationListenerAccessGranted;
 * no notification handling is required.
 */
class SpotifyNotificationListener : NotificationListenerService()
