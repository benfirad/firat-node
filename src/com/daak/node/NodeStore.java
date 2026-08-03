package com.daak.node;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class NodeStore {
    static final String PREFS = "daak_node_private";
    private static final String LEGACY_CHANNEL = "daak_mail_summary_legacy";
    private static final String CHANNEL_PREFIX = "daak_summary_loud_v1_";
    private static final String SOUND_PREF = "notification_sound_key";
    static final String ACTION_HOURLY = "com.daak.node.HOURLY_MAIL";
    static final String ACTION_MORNING = "com.daak.node.MORNING_MAIL";

    static String channelId(Context context) {
        return CHANNEL_PREFIX + soundKey(context);
    }

    static String soundKey(Context context) {
        String key = context.getSharedPreferences(PREFS, 0).getString(SOUND_PREF, "pulse");
        return key.equals("terminal") || key.equals("deep") ? key : "pulse";
    }

    static Uri soundUri(Context context, String key) {
        int resource = key.equals("terminal") ? R.raw.terminal_tick
                : key.equals("deep") ? R.raw.deep_node : R.raw.daak_pulse;
        return Uri.parse("android.resource://" + context.getPackageName() + "/" + resource);
    }

    static boolean selectSound(Context context, String key, boolean systemDefault) {
        if (!key.equals("terminal") && !key.equals("deep")) key = "pulse";
        context.getSharedPreferences(PREFS, 0).edit().putString(SOUND_PREF, key).apply();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.deleteNotificationChannel(LEGACY_CHANNEL);
                manager.deleteNotificationChannel(CHANNEL_PREFIX + "pulse");
                manager.deleteNotificationChannel(CHANNEL_PREFIX + "terminal");
                manager.deleteNotificationChannel(CHANNEL_PREFIX + "deep");
            }
        }
        ensureChannel(context);
        if (!systemDefault) return true;
        if (!Settings.System.canWrite(context)) return false;
        RingtoneManager.setActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION, soundUri(context, key));
        return true;
    }

    static void migrateLoudSound(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        if (prefs.getBoolean("loud_sound_v1_migrated", false)) return;
        String selected = "pulse";
        Cursor cursor = null;
        try {
            Uri current = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION);
            if (current != null) {
                cursor = context.getContentResolver().query(current,
                        new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    String name = cursor.getString(0).toLowerCase(Locale.US);
                    if (name.contains("terminal")) selected = "terminal";
                    else if (name.contains("deep")) selected = "deep";
                }
            }
        } catch (Exception ignored) { }
        finally { if (cursor != null) cursor.close(); }
        selectSound(context, selected, true);
        prefs.edit().putBoolean("loud_sound_v1_migrated", true).apply();
    }

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            String key = soundKey(context);
            NotificationChannel channel = new NotificationChannel(channelId(context), "DAAK summaries", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("DAAK NODE read-only mail and task summaries • loud embedded tone");
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            channel.setSound(soundUri(context, key), attributes);
            manager.createNotificationChannel(channel);
        }
    }

    static boolean ignored(Context context, String sender, String subject) {
        String haystack = (sender + " " + subject).toLowerCase(Locale.US);
        String defaults = "spam,junk,newsletter,unsubscribe,no-reply,noreply";
        String raw = context.getSharedPreferences(PREFS, 0).getString("ignored_senders", defaults);
        for (String token : raw.split(",")) {
            token = token.trim().toLowerCase(Locale.US);
            if (token.length() > 0 && haystack.contains(token)) return true;
        }
        return false;
    }

    static synchronized String addMail(Context context, String source, String sender, String subject,
                                       long when, String fingerprint) {
        if (source == null) source = "Mail";
        if (sender == null) sender = "Unknown sender";
        if (subject == null) subject = "(no subject)";
        if (ignored(context, sender, subject)) return null;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
            JSONArray seen = new JSONArray(prefs.getString("mail_seen", "[]"));
            if (fingerprint == null) fingerprint = source + "\n" + sender + "\n" + subject;
            fingerprint = Integer.toHexString(fingerprint.hashCode());
            for (int i = 0; i < seen.length(); i++)
                if (fingerprint.equals(seen.optString(i))) return null;
            JSONArray nextSeen = new JSONArray(); nextSeen.put(fingerprint);
            for (int i = 0; i < seen.length() && nextSeen.length() < 80; i++)
                nextSeen.put(seen.optString(i));

            JSONArray old = new JSONArray(prefs.getString("mail_items", "[]"));
            JSONArray next = new JSONArray();
            JSONObject fresh = new JSONObject();
            fresh.put("source", source.substring(0, Math.min(source.length(), 40)));
            fresh.put("sender", sender.substring(0, Math.min(sender.length(), 120)));
            fresh.put("subject", subject.substring(0, Math.min(subject.length(), 180)));
            fresh.put("when", when);
            next.put(fresh);
            long oldest = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
            for (int i = 0; i < old.length() && next.length() < 40; i++) {
                JSONObject item = old.optJSONObject(i);
                if (item != null && item.optLong("when", 0L) >= oldest) next.put(item);
            }
            prefs.edit().putString("mail_seen", nextSeen.toString())
                    .putString("mail_items", next.toString()).apply();
            return "Mail • " + source + " • " + sender + " — " + subject;
        } catch (Exception ignored) { return null; }
    }

    static List<String> recentMail(Context context, long since, int limit) {
        List<String> rows = new ArrayList<String>();
        try {
            JSONArray items = new JSONArray(context.getSharedPreferences(PREFS, 0).getString("mail_items", "[]"));
            for (int i = 0; i < items.length() && rows.size() < limit; i++) {
                JSONObject item = items.getJSONObject(i);
                if (item.optLong("when", 0) < since) continue;
                String source = item.optString("source", "Mail");
                rows.add(source + " • " + item.optString("sender") + " — " + item.optString("subject"));
            }
        } catch (Exception ignored) { }
        return rows;
    }

    static synchronized String addWhatsAppTask(Context context, String sender, String text, long when) {
        if (sender == null) sender = "WhatsApp";
        if (text == null) text = "";
        sender = sender.trim(); text = text.trim();
        if (text.length() == 0 || !whatsAppAutomationEnabled(context) || ignoredWhatsApp(context, sender, text)) return null;
        if (!looksActionable(text)) return null;
        String fingerprint = Integer.toHexString(canonicalWhatsAppText(text).hashCode());
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
            JSONArray seen = new JSONArray(prefs.getString("whatsapp_seen", "[]"));
            for (int i = 0; i < seen.length(); i++) if (fingerprint.equals(seen.optString(i))) return null;
            JSONArray nextSeen = new JSONArray(); nextSeen.put(fingerprint);
            for (int i = 0; i < seen.length() && nextSeen.length() < 50; i++) nextSeen.put(seen.optString(i));

            JSONArray old = new JSONArray(prefs.getString("whatsapp_tasks", "[]"));
            for (int i = 0; i < old.length(); i++) {
                JSONObject existing = old.optJSONObject(i);
                if (existing != null && fingerprint.equals(Integer.toHexString(
                        canonicalWhatsAppText(existing.optString("text")).hashCode()))) return null;
            }
            JSONArray next = new JSONArray();
            JSONObject fresh = new JSONObject();
            fresh.put("sender", sender.substring(0, Math.min(sender.length(), 120)));
            fresh.put("text", text.substring(0, Math.min(text.length(), 300)));
            fresh.put("when", when); next.put(fresh);
            for (int i = 0; i < old.length() && next.length() < 20; i++) next.put(old.getJSONObject(i));
            prefs.edit().putString("whatsapp_seen", nextSeen.toString()).putString("whatsapp_tasks", next.toString()).apply();
            return "WhatsApp • " + sender + ": " + text;
        } catch (Exception ignored) { return null; }
    }

    static List<String> recentWhatsAppTasks(Context context, int limit) {
        List<String> rows = new ArrayList<String>();
        HashSet<String> seenText = new HashSet<String>();
        try {
            JSONArray items = new JSONArray(context.getSharedPreferences(PREFS, 0).getString("whatsapp_tasks", "[]"));
            for (int i = 0; i < items.length() && rows.size() < limit; i++) {
                JSONObject item = items.getJSONObject(i);
                String sender = item.optString("sender"), text = item.optString("text");
                if (!looksActionable(text)) continue;
                String normalized = canonicalWhatsAppText(text);
                if (!seenText.add(normalized)) continue;
                rows.add(sender + " — " + text);
            }
        } catch (Exception ignored) { }
        return rows;
    }

    private static String canonicalWhatsAppText(String text) {
        String normalized = text.toLowerCase(new Locale("tr", "TR")).replaceAll("\\s+", " ").trim();
        int colon = normalized.indexOf(':');
        if (colon > 0 && colon < 60) {
            String prefix = normalized.substring(0, colon);
            if (!prefix.contains("http") && prefix.split(" ").length <= 6)
                normalized = normalized.substring(colon + 1).trim();
        }
        return normalized;
    }

    static boolean whatsAppAutomationEnabled(Context context) {
        return context.getSharedPreferences(PREFS, 0).getBoolean("whatsapp_tasks_enabled", true);
    }

    private static boolean ignoredWhatsApp(Context context, String sender, String text) {
        String haystack = (sender + " " + text).toLowerCase(Locale.US);
        String raw = context.getSharedPreferences(PREFS, 0).getString("ignored_whatsapp_senders", "");
        for (String token : raw.split(",")) {
            token = token.trim().toLowerCase(Locale.US);
            if (token.length() > 0 && haystack.contains(token)) return true;
        }
        return false;
    }

    private static boolean looksActionable(String text) {
        String value = text.toLowerCase(new Locale("tr", "TR"));
        String[] noise = {"yeni mesajları kontrol", "mesaj bekleniyor", "checking for new messages",
                "whatsapp web", "yedekleme yapılıyor", "backup in progress",
                "todo listo para empezar a chatear", "ready to start chatting"};
        for (String marker : noise) if (value.contains(marker)) return false;
        String[] markers = {"unutma", "hatırlat", "hatirlat", "yapar mısın", "yapar misin",
                "yapabilir misin", "bakabilir misin", "kontrol eder misin", "lütfen", "lutfen",
                "gönder", "gonder", "arar mısın", "arar misin", "yazabilir misin",
                "alır mısın", "alir misin", "getir", "götür", "gotur",
                "randevu", "rezervasyon", "toplantı", "toplanti", "son tarih", "son gün",
                "deadline", "due date", "todo:", "todo -", "#todo", "to-do", "yapılacak", "yapilacak",
                "yarın ", "yarin ", "bugün ", "bugun ", "saat kaçta", "saat kacta"};
        for (String marker : markers) if (value.contains(marker)) return true;
        return startsAsWord(value, "ara") || startsAsWord(value, "öde") || startsAsWord(value, "ode");
    }

    private static boolean startsAsWord(String value, String stem) {
        return Pattern.compile("(^|[^\\p{L}])" + Pattern.quote(stem), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(value).find();
    }

    static boolean actionableForTest(String text) { return looksActionable(text == null ? "" : text); }

    static void noteCapture(Context context, String source) {
        String key = source.toLowerCase(Locale.US).replaceAll("[^a-z]", "");
        SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        prefs.edit().putLong("capture_" + key + "_last", System.currentTimeMillis())
                .putInt("capture_" + key + "_count", prefs.getInt("capture_" + key + "_count", 0) + 1)
                .apply();
    }

    static void noteListenerConnected(Context context) {
        context.getSharedPreferences(PREFS, 0).edit()
                .putLong("notification_listener_connected", System.currentTimeMillis()).apply();
    }

    static List<String> mailBridgeRows(Context context) {
        List<String> rows = new ArrayList<String>();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        rows.add("BRIDGE • " + age(prefs.getLong("notification_listener_connected", 0L)));
        rows.add(appStatus(context, "com.google.android.gm", "GMAIL", "gmail", prefs));
        rows.add(appStatus(context, "net.thunderbird.android", "THUNDERBIRD", "thunderbird", prefs));
        return rows;
    }

    static String whatsAppBridgeRow(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        return "WHATSAPP SIGNAL • " + age(prefs.getLong("capture_whatsapp_last", 0L));
    }

    private static String appStatus(Context context, String packageName, String label, String key,
                                    SharedPreferences prefs) {
        try { context.getPackageManager().getPackageInfo(packageName, 0); }
        catch (Exception error) { return label + " • NOT INSTALLED"; }
        int count = prefs.getInt("capture_" + key + "_count", 0);
        return label + " • " + age(prefs.getLong("capture_" + key + "_last", 0L)) + " • " + count;
    }

    private static String age(long when) {
        if (when <= 0L) return "READY / NO SIGNAL YET";
        long minutes = Math.max(0L, (System.currentTimeMillis() - when) / 60000L);
        if (minutes < 1L) return "ACTIVE NOW";
        if (minutes < 60L) return minutes + " MIN AGO";
        long hours = minutes / 60L;
        if (hours < 24L) return hours + " H AGO";
        return (hours / 24L) + " D AGO";
    }

    static boolean oledLockPlayerEnabled(Context context) {
        return context.getSharedPreferences(PREFS, 0).getBoolean("oled_lock_player", true);
    }

    static boolean toggleOledLockPlayer(Context context) {
        boolean enabled = !oledLockPlayerEnabled(context);
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("oled_lock_player", enabled).apply();
        return enabled;
    }

    static synchronized void clearMailBefore(Context context, long cutoff) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
            JSONArray old = new JSONArray(prefs.getString("mail_items", "[]"));
            JSONArray remaining = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.optJSONObject(i);
                if (item != null && item.optLong("when", 0L) > cutoff) remaining.put(item);
            }
            prefs.edit().putString("mail_items", remaining.toString()).apply();
        } catch (Exception ignored) { }
    }

    static void schedule(Context context) {
        ensureChannel(context);
        BookBackupJobService.schedule(context);
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) return;
        scheduleOne(context, alarm, ACTION_HOURLY, 701, nextHour());
        scheduleOne(context, alarm, ACTION_MORNING, 702, nextMorning());
    }

    private static void scheduleOne(Context context, AlarmManager alarm, String action, int request, long at) {
        Intent intent = new Intent(context, NodeAlarmReceiver.class).setAction(action);
        PendingIntent pending = PendingIntent.getBroadcast(context, request, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending); }
        catch (RuntimeException ignored) { alarm.set(AlarmManager.RTC_WAKEUP, at, pending); }
    }

    private static long nextHour() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.HOUR_OF_DAY, 1); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static long nextMorning() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 7); c.set(Calendar.MINUTE, 30); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1);
        return c.getTimeInMillis();
    }
}
