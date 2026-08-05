package com.daak.node;

import android.content.Context;
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

    interface SendCallback {
        void onComplete(boolean sent);
    }

    /**
     * The only network write entry point. Callers must invoke this from an
     * explicit user action for one selected item; notification capture never
     * reaches this method.
     */
    static void sendSelected(final Context context, final String text,
                             final String folder, final JSONArray labels,
                             final SendCallback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                boolean sent = text != null && text.trim().length() > 0 &&
                        addNote(context, text.trim(), folder, labels);
                if (callback != null) callback.onComplete(sent);
            }
        }, "daak-remember-explicit-share").start();
    }

    /** Prevent pre-v7 queued notification content from leaking after upgrade. */
    static void clearLegacyAutomaticQueue(Context context) {
        context.getSharedPreferences(NodeStore.PREFS, 0).edit().remove(PENDING).apply();
    }

    private static synchronized boolean addNote(Context context, String text,
                                                String folder, JSONArray labels) {
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
            note.put("folder", normalizeFolder(folder));
            if (labels != null && labels.length() > 0) note.put("labels", labels);
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

    private static String normalizeFolder(String folder) {
        if (folder == null) return "inbox";
        String value = folder.toLowerCase(Locale.US);
        if (value.equals("tasks") || value.equals("whatsapp") || value.equals("mail")
                || value.equals("notes")) return value;
        return "inbox";
    }
}
