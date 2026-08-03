package com.firat.node;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.List;

public final class MailNotificationListener extends NotificationListenerService {
    private BroadcastReceiver screenReceiver;
    private long lastPlayerLaunch;

    @Override public void onCreate() {
        super.onCreate();
        screenReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) showLockPlayerIfNeeded();
            }
        };
        registerReceiver(screenReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (pkg == null) return;
        Notification notification = sbn.getNotification();
        if (notification == null) return;
        processNotification(getApplicationContext(), pkg, notification, sbn.getKey(), sbn.getPostTime());
    }

    static void processNotification(Context context, String pkg, Notification notification, String key, long when) {
        if (context == null || pkg == null || notification == null) return;
        Bundle extras = notification.extras;
        String title = extras == null ? "" : NotificationCapture.clean(
                extras.getCharSequence(Notification.EXTRA_TITLE) == null ? "" :
                        extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        List<NotificationCapture.Item> items = NotificationCapture.extract(notification);
        if (pkg.contains("thunderbird") || pkg.contains("k9mail") || pkg.equals("com.google.android.gm")) {
            String source = pkg.equals("com.google.android.gm") ? "Gmail" : "Thunderbird";
            if (!items.isEmpty()) NodeStore.noteCapture(context, source);
            for (NotificationCapture.Item item : items) {
                String sender = item.sender.equals("Unknown") ? title : item.sender;
                if (sender.length() == 0) sender = source;
                String fingerprint = pkg + "\n" + sender + "\n" + item.text;
                String mail = NodeStore.addMail(context, source, sender, item.text, when, fingerprint);
                if (mail != null) RememberBridge.pushOrQueue(
                        context.getApplicationContext(), mail, "mail", new org.json.JSONArray());
            }
            NodeStore.schedule(context);
        } else if (pkg.equals("com.whatsapp")) {
            if (!items.isEmpty()) NodeStore.noteCapture(context, "WhatsApp");
            for (NotificationCapture.Item item : items) {
                String sender = NotificationCapture.conversationSender(title, item.sender);
                String task = NodeStore.addWhatsAppTask(context, sender, item.text, when);
                if (task != null) {
                    org.json.JSONArray labels = new org.json.JSONArray(); labels.put("tasks");
                    RememberBridge.pushOrQueue(
                            context.getApplicationContext(), task, "whatsapp", labels);
                }
            }
        }
    }

    @Override public void onListenerConnected() {
        NodeStore.noteListenerConnected(this);
        NodeStore.schedule(this);
        RememberBridge.flushPending(getApplicationContext());
    }

    private void showLockPlayerIfNeeded() {
        if (!NodeStore.oledLockPlayerEnabled(this)) return;
        long now = System.currentTimeMillis();
        if (now - lastPlayerLaunch < 3000L || !hasPlayingMedia()) return;
        lastPlayerLaunch = now;
        try {
            Intent player = new Intent(this, OledPlayerActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP |
                            Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(player);
        } catch (RuntimeException ignored) { }
    }

    private boolean hasPlayingMedia() {
        try {
            MediaSessionManager manager = (MediaSessionManager)getSystemService(MEDIA_SESSION_SERVICE);
            if (manager == null) return false;
            List<MediaController> sessions = manager.getActiveSessions(
                    new ComponentName(this, MailNotificationListener.class));
            for (MediaController controller : sessions) {
                PlaybackState state = controller.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) return true;
            }
        } catch (SecurityException ignored) { }
        return false;
    }

    @Override public void onDestroy() {
        if (screenReceiver != null) {
            try { unregisterReceiver(screenReceiver); }
            catch (RuntimeException ignored) { }
        }
        super.onDestroy();
    }
}
