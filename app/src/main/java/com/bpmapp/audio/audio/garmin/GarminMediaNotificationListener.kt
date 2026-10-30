// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.audio.garmin

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Notification listener used as the anchor ComponentName for
 * [android.media.session.MediaSessionManager.getActiveSessions]: when
 * notification access is granted, that call returns the active media sessions
 * of OTHER apps, letting [MediaKeyDispatcher] drive external players
 * (Qobuz, etc.) through their [android.media.session.MediaController]
 * transport controls.
 *
 * The service itself never reads notifications; it exists only so the app is
 * registered in `enabled_notification_listeners`. Lives in the SDK-free main
 * source set so both flavors compile it (harmless for fdroid).
 */
class GarminMediaNotificationListener : NotificationListenerService() {

    companion object {

        /** True when the user granted notification access to this listener. */
        fun isEnabled(context: Context): Boolean {
            val flattened = ComponentName(context, GarminMediaNotificationListener::class.java)
                .flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return enabled.split(':').any { it.equals(flattened, ignoreCase = true) }
        }
    }
}
