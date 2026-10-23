package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so Home may see what is playing (Android shows the media sessions of other apps only to
 * an allowed notification listener). It reads no notifications: as soon as the TV connects it, it asks
 * to be let go again, so nothing runs for it. Allowed once from the computer:
 * {@code cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.MediaAccess}.
 */
public class MediaAccess extends NotificationListenerService {
    @Override
    public void onListenerConnected() {
        requestUnbind();
    }
}
