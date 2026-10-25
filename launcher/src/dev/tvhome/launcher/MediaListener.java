package dev.tvhome.launcher;

import android.service.notification.NotificationListenerService;

/**
 * Never reads notifications. Android only lets an app list other apps' media sessions
 * if it holds notification-listener access, so this empty service is that key.
 */
public class MediaListener extends NotificationListenerService {
}
