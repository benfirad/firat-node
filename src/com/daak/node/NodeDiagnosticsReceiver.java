package com.daak.node;

import android.app.Notification;
import android.app.Person;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;

public final class NodeDiagnosticsReceiver extends BroadcastReceiver {
    static final String ACTION = "com.daak.node.DIAGNOSTIC_NOTIFICATION_CAPTURE";
    static final String ACTION_MEDIA_START = "com.daak.node.DIAGNOSTIC_MEDIA_START";
    static final String ACTION_MEDIA_STOP = "com.daak.node.DIAGNOSTIC_MEDIA_STOP";
    static final String ACTION_AIRPLAY_SEND = "com.daak.node.DIAGNOSTIC_AIRPLAY_SEND";
    static final String ACTION_KEENETIC_WOL = "com.daak.node.DIAGNOSTIC_KEENETIC_WOL";
    private static MediaSession diagnosticSession;

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (ACTION_MEDIA_START.equals(intent.getAction())) {
            startMediaSession(context);
            if (intent.getBooleanExtra("launch", false)) {
                context.startActivity(new Intent(context, OledPlayerActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            }
            setResultData("PASS diagnostic_media=playing"); return;
        }
        if (ACTION_MEDIA_STOP.equals(intent.getAction())) {
            if (diagnosticSession != null) { diagnosticSession.release(); diagnosticSession = null; }
            setResultData("PASS diagnostic_media=stopped"); return;
        }
        if (ACTION_AIRPLAY_SEND.equals(intent.getAction())) {
            String path = intent.getStringExtra("path");
            String queuePrefix = "/sdcard/Download/DAAK-AirPlay-Queue/";
            if (path == null || !path.startsWith(queuePrefix) || path.contains("..")) {
                setResultData("FAIL invalid_airplay_path"); return;
            }
            Intent command = new Intent("com.termux.RUN_COMMAND");
            command.setClassName("com.termux", "com.termux.app.RunCommandService");
            command.putExtra("com.termux.RUN_COMMAND_PATH",
                    "/data/data/com.termux/files/home/.shortcuts/daak-airplay");
            command.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{path});
            command.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
            command.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            try {
                context.startService(command);
                setResultData("PASS diagnostic_airplay=launched");
            } catch (RuntimeException error) {
                setResultData("FAIL diagnostic_airplay=" + error.getClass().getSimpleName());
            }
            return;
        }
        if (ACTION_KEENETIC_WOL.equals(intent.getAction())) {
            boolean masked = NodeControlAccessibilityService.showWolMask();
            boolean queued = masked && RootActionQueue.request(context, "keenetic-wol-dry-run");
            if (masked && !queued) NodeControlAccessibilityService.finishWol();
            setResultData(queued ? "PASS diagnostic_keenetic_wol=masked_and_queued" :
                    masked ? "FAIL diagnostic_keenetic_wol=queue_error" :
                    "FAIL diagnostic_keenetic_wol=gesture_service_offline");
            return;
        }
        if (!ACTION.equals(intent.getAction())) return;
        long now = System.currentTimeMillis();
        Notification gmail = new Notification.Builder(context)
                .setContentTitle("Test Sender").setContentText("Test subject").build();
        Notification thunderbird = new Notification.Builder(context)
                .setContentTitle("Unified Inbox")
                .setStyle(new Notification.InboxStyle().addLine("Sender A — Subject A").addLine("Sender B — Subject B"))
                .build();
        Person user = new Person.Builder().setName("DAAK User").build();
        Person sender = new Person.Builder().setName("Test Contact").build();
        Notification whatsapp = new Notification.Builder(context)
                .setContentTitle("Test Group")
                .setStyle(new Notification.MessagingStyle(user)
                        .addMessage(new Notification.MessagingStyle.Message(
                                "Yarın saat 10'da raporu gönderir misin?", now, sender)))
                .build();

        List<NotificationCapture.Item> gmailItems = NotificationCapture.extract(gmail);
        List<NotificationCapture.Item> thunderbirdItems = NotificationCapture.extract(thunderbird);
        List<NotificationCapture.Item> whatsappItems = NotificationCapture.extract(whatsapp);
        boolean actionable = !whatsappItems.isEmpty() && NodeStore.actionableForTest(whatsappItems.get(0).text);
        boolean noiseRejected = !NodeStore.actionableForTest("WhatsApp Web is currently active") &&
                !NodeStore.actionableForTest("¿Todo listo para empezar a chatear?");
        boolean groupNormalized = "Test Group • Test Contact".equals(
                NotificationCapture.conversationSender("Test Group (2 mensajes)", "Test Contact"));

        SharedPreferences prefs = context.getSharedPreferences(NodeStore.PREFS, 0);
        String oldMail = prefs.getString("mail_items", "[]"), oldMailSeen = prefs.getString("mail_seen", "[]");
        String oldTasks = prefs.getString("whatsapp_tasks", "[]"), oldWhatsAppSeen = prefs.getString("whatsapp_seen", "[]");
        boolean mailStorage = false, whatsAppStorage = false;
        try {
            NodeStore.addMail(context, "SELFTEST", "Test Sender", "Test subject", now,
                    "selftest-mail-" + now);
            mailStorage = !NodeStore.recentMail(context, now - 1000L, 1).isEmpty();
            whatsAppStorage = NodeStore.addWhatsAppTask(context, "Test Contact",
                    "Yarın raporu gönderir misin?", now) != null;
        } finally {
            prefs.edit().putString("mail_items", oldMail).putString("mail_seen", oldMailSeen)
                    .putString("whatsapp_tasks", oldTasks).putString("whatsapp_seen", oldWhatsAppSeen).commit();
        }
        boolean pass = gmailItems.size() == 1 && thunderbirdItems.size() == 2 &&
                whatsappItems.size() == 1 && actionable && noiseRejected && groupNormalized &&
                mailStorage && whatsAppStorage;
        setResultData((pass ? "PASS" : "FAIL") +
                " gmail=" + gmailItems.size() +
                " thunderbird=" + thunderbirdItems.size() +
                " whatsapp=" + whatsappItems.size() +
                " actionable=" + actionable +
                " noise_rejected=" + noiseRejected +
                " group_normalized=" + groupNormalized +
                " mail_storage=" + mailStorage +
                " whatsapp_storage=" + whatsAppStorage);
    }

    private static void startMediaSession(Context context) {
        if (diagnosticSession != null) diagnosticSession.release();
        diagnosticSession = new MediaSession(context, "DAAK OLED Player Selftest");
        diagnosticSession.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "OLED Player Selftest")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "DAAK NODE")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Secure Lock Screen")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 180000L).build());
        diagnosticSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
                        PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackState.STATE_PLAYING, 42000L, 1f, SystemClock.elapsedRealtime()).build());
        diagnosticSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { Log.i("DAAK_PLAYER", "action=PLAY"); }
            @Override public void onPause() { Log.i("DAAK_PLAYER", "action=PAUSE"); }
            @Override public void onSkipToNext() { Log.i("DAAK_PLAYER", "action=NEXT"); }
            @Override public void onSkipToPrevious() { Log.i("DAAK_PLAYER", "action=PREVIOUS"); }
        });
        diagnosticSession.setActive(true);
    }
}
