package com.firat.node;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.List;

public final class NodeAlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        NodeStore.ensureChannel(context);
        SharedPreferences prefs = context.getSharedPreferences(NodeStore.PREFS, 0);
        long now = System.currentTimeMillis();
        boolean morning = intent != null && NodeStore.ACTION_MORNING.equals(intent.getAction());
        long since;
        if (morning) {
            Calendar start = Calendar.getInstance(); start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0); start.set(Calendar.SECOND, 0);
            since = start.getTimeInMillis();
        } else since = prefs.getLong("last_hourly", now - 60L * 60L * 1000L);
        List<String> rows = NodeStore.recentMail(context, since, 5);
        String title = morning ? "07:30 // Günaydın" : "DAAK NODE // Mail";
        String body = rows.isEmpty() ? "Yeni bir mailin yok, rahat ol." : rows.size() + " yeni mail: " + rows.get(0);
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(context, 703, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(context).setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(title).setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pending).setAutoCancel(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) builder.setChannelId(NodeStore.CHANNEL);
        NotificationManager manager = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(morning ? 704 : 705, builder.build());
        if (!morning) prefs.edit().putLong("last_hourly", now).apply();
        else prefs.edit().putLong("last_morning", now).apply();
        NodeStore.schedule(context);
    }
}
