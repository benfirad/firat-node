package com.firat.node;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.UUID;

final class RememberBridge {
    private static final String PENDING = "remember_pending_tasks";

    private RememberBridge() { }

    static void pushOrQueue(final Context context, final String text) {
        new Thread(new Runnable() {
            @Override public void run() {
                if (!addNote(context, text)) enqueue(context, text);
            }
        }, "daak-remember-notification").start();
    }

    static void flushPending(final Context context) {
        new Thread(new Runnable() {
            @Override public void run() {
                SharedPreferences prefs = context.getSharedPreferences(NodeStore.PREFS, 0);
                try {
                    JSONArray old = new JSONArray(prefs.getString(PENDING, "[]"));
                    JSONArray remaining = new JSONArray();
                    for (int i = 0; i < old.length(); i++) {
                        String text = old.optString(i, "");
                        if (text.length() > 0 && !addNote(context, text)) remaining.put(text);
                    }
                    prefs.edit().putString(PENDING, remaining.toString()).apply();
                } catch (Exception ignored) { }
            }
        }, "daak-remember-retry").start();
    }

    private static synchronized boolean addNote(Context context, String text) {
        try {
            JSONObject snapshot = new JSONObject(new String(request(context, "GET", "/snapshot", null), "UTF-8"));
            JSONArray items = snapshot.optJSONArray("items");
            if (items == null) items = new JSONArray();
            double appleTime = System.currentTimeMillis() / 1000.0 - 978307200.0;
            JSONObject note = new JSONObject();
            note.put("id", UUID.randomUUID().toString().toUpperCase(Locale.US));
            note.put("text", text);
            note.put("createdAt", appleTime);
            note.put("updatedAt", appleTime);
            note.put("isDone", false);
            note.put("deletedAt", JSONObject.NULL);
            items.put(note);
            JSONObject envelope = new JSONObject();
            envelope.put("deviceName", "DAAK NODE");
            envelope.put("items", items);
            request(context, "POST", "/merge", envelope.toString().getBytes("UTF-8"));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static byte[] request(Context context, String method, String path, byte[] body) throws Exception {
        String host = NodeConfig.get(context, "remember_host", NodeConfig.get(context, "mac_host", "mac"));
        URL url = new URL("http://" + host + ":45831" + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(3500);
            connection.setReadTimeout(4500);
            connection.setRequestProperty("Content-Type", "application/json");
            if (body != null && body.length > 0) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                OutputStream output = connection.getOutputStream();
                output.write(body);
                output.close();
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
            InputStream input = connection.getInputStream();
            byte[] buffer = new byte[4096];
            int count;
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            while ((count = input.read(buffer)) > 0) data.write(buffer, 0, count);
            input.close();
            return data.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private static synchronized void enqueue(Context context, String text) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(NodeStore.PREFS, 0);
            JSONArray old = new JSONArray(prefs.getString(PENDING, "[]"));
            JSONArray next = new JSONArray();
            for (int i = 0; i < old.length() && next.length() < 29; i++) next.put(old.optString(i));
            next.put(text);
            prefs.edit().putString(PENDING, next.toString()).apply();
        } catch (Exception ignored) { }
    }
}
