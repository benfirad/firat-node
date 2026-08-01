package com.firat.node;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public final class MailNotificationListener extends NotificationListenerService {
    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (pkg == null || !(pkg.contains("thunderbird") || pkg.contains("k9mail"))) return;
        Notification notification = sbn.getNotification();
        if (notification == null) return;
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        Bundle extras = notification.extras;
        if (extras == null) return;
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        NodeStore.addMail(this, title == null ? "Mail" : title.toString(), text == null ? "New message" : text.toString(), sbn.getPostTime());
        NodeStore.schedule(this);
    }

    @Override public void onListenerConnected() { NodeStore.schedule(this); }
}
