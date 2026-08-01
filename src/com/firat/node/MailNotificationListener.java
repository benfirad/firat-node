package com.firat.node;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public final class MailNotificationListener extends NotificationListenerService {
    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (pkg == null) return;
        Notification notification = sbn.getNotification();
        if (notification == null) return;
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        Bundle extras = notification.extras;
        if (extras == null) return;
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        if (pkg.contains("thunderbird") || pkg.contains("k9mail")) {
            NodeStore.addMail(this, title == null ? "Mail" : title.toString(), text == null ? "New message" : text.toString(), sbn.getPostTime());
            NodeStore.schedule(this);
        } else if (pkg.equals("com.whatsapp")) {
            String task = NodeStore.addWhatsAppTask(this,
                    title == null ? "WhatsApp" : title.toString(),
                    text == null ? "" : text.toString(), sbn.getPostTime());
            if (task != null) RememberBridge.pushOrQueue(getApplicationContext(), task);
        }
    }

    @Override public void onListenerConnected() {
        NodeStore.schedule(this);
        RememberBridge.flushPending(getApplicationContext());
    }
}
