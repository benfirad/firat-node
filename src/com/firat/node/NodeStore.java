package com.firat.node;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

final class NodeStore {
    static final String PREFS = "firat_node_private";
    static final String CHANNEL = "firat_mail_summary";
    static final String ACTION_HOURLY = "com.firat.node.HOURLY_MAIL";
    static final String ACTION_MORNING = "com.firat.node.MORNING_MAIL";

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Mail summaries", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("FIRAT NODE read-only mail summaries");
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

    static synchronized void addMail(Context context, String sender, String subject, long when) {
        if (sender == null) sender = "Unknown sender";
        if (subject == null) subject = "(no subject)";
        if (ignored(context, sender, subject)) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
            JSONArray old = new JSONArray(prefs.getString("mail_items", "[]"));
            JSONArray next = new JSONArray();
            JSONObject fresh = new JSONObject();
            fresh.put("sender", sender.substring(0, Math.min(sender.length(), 120)));
            fresh.put("subject", subject.substring(0, Math.min(subject.length(), 180)));
            fresh.put("when", when);
            next.put(fresh);
            for (int i = 0; i < old.length() && next.length() < 30; i++) next.put(old.getJSONObject(i));
            prefs.edit().putString("mail_items", next.toString()).apply();
        } catch (Exception ignored) { }
    }

    static List<String> recentMail(Context context, long since, int limit) {
        List<String> rows = new ArrayList<String>();
        try {
            JSONArray items = new JSONArray(context.getSharedPreferences(PREFS, 0).getString("mail_items", "[]"));
            for (int i = 0; i < items.length() && rows.size() < limit; i++) {
                JSONObject item = items.getJSONObject(i);
                if (item.optLong("when", 0) < since) continue;
                rows.add(item.optString("sender") + " — " + item.optString("subject"));
            }
        } catch (Exception ignored) { }
        return rows;
    }

    static void clearSensitiveCache(Context context) {
        context.getSharedPreferences(PREFS, 0).edit().remove("mail_items").apply();
    }

    static void schedule(Context context) {
        ensureChannel(context);
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        scheduleOne(context, alarm, ACTION_HOURLY, 701, nextHour());
        scheduleOne(context, alarm, ACTION_MORNING, 702, nextMorning());
    }

    private static void scheduleOne(Context context, AlarmManager alarm, String action, int request, long at) {
        Intent intent = new Intent(context, NodeAlarmReceiver.class).setAction(action);
        PendingIntent pending = PendingIntent.getBroadcast(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
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
