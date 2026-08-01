package com.firat.node;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

final class NodeUpdater {
    static final String DEFAULT_MANIFEST =
            "https://github.com/benfirad/firat-node/releases/latest/download/update.json";

    interface CheckCallback {
        void onCurrent();
        void onAvailable(Update update);
        void onError(String message);
    }

    interface InstallCallback {
        void onStatus(String status);
        void onError(String message);
    }

    static final class Update {
        final int versionCode;
        final String versionName;
        final String apkUrl;
        final String sha256;
        final String notes;

        Update(int versionCode, String versionName, String apkUrl, String sha256, String notes) {
            this.versionCode = versionCode; this.versionName = versionName; this.apkUrl = apkUrl;
            this.sha256 = sha256.toLowerCase(Locale.US); this.notes = notes;
        }
    }

    private NodeUpdater() { }

    static void check(final Context context, final CheckCallback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                String manifest = NodeConfig.get(context, "update_manifest", DEFAULT_MANIFEST);
                connection = (HttpURLConnection)new URL(manifest).openConnection();
                connection.setConnectTimeout(5000); connection.setReadTimeout(7000);
                if (connection.getResponseCode() != 200) throw new IllegalStateException("HTTP " + connection.getResponseCode());
                JSONObject json = new JSONObject(new String(readAll(connection.getInputStream()), "UTF-8"));
                Update update = new Update(json.getInt("versionCode"), json.getString("versionName"),
                        json.getString("apkUrl"), json.getString("sha256"), json.optString("notes", ""));
                int installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
                if (update.versionCode > installed) main.post(() -> callback.onAvailable(update));
                else main.post(callback::onCurrent);
            } catch (Exception error) {
                main.post(() -> callback.onError(error.getClass().getSimpleName() + ": " + error.getMessage()));
            } finally { if (connection != null) connection.disconnect(); }
        }, "daak-update-check").start();
    }

    static void downloadAndInstall(final Context context, final Update update, final InstallCallback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                main.post(() -> callback.onStatus("UPDATE // DOWNLOADING"));
                File destination = new File(context.getFilesDir(), "DAAK-NODE-update.apk");
                connection = (HttpURLConnection)new URL(update.apkUrl).openConnection();
                connection.setConnectTimeout(8000); connection.setReadTimeout(30000);
                if (connection.getResponseCode() != 200) throw new IllegalStateException("APK HTTP " + connection.getResponseCode());
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                InputStream input = connection.getInputStream();
                FileOutputStream output = new FileOutputStream(destination);
                byte[] buffer = new byte[32768]; int count;
                while ((count = input.read(buffer)) > 0) { output.write(buffer, 0, count); digest.update(buffer, 0, count); }
                output.getFD().sync(); output.close(); input.close();
                StringBuilder actual = new StringBuilder();
                for (byte value : digest.digest()) actual.append(String.format(Locale.US, "%02x", value & 0xff));
                if (!actual.toString().equals(update.sha256)) {
                    destination.delete(); throw new SecurityException("SHA-256 mismatch");
                }
                main.post(() -> callback.onStatus("UPDATE // VERIFIED • INSTALLING"));
                String source = shellQuote(destination.getAbsolutePath());
                String command = "cp " + source + " /data/local/tmp/DAAK-NODE-update.apk && " +
                        "chmod 0644 /data/local/tmp/DAAK-NODE-update.apk && " +
                        "pm install -r /data/local/tmp/DAAK-NODE-update.apk";
                Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
                String outputText = new String(readAll(process.getInputStream()), "UTF-8");
                int exit = process.waitFor();
                if (exit != 0 || !outputText.contains("Success")) throw new IllegalStateException("Install failed: " + outputText.trim());
            } catch (Exception error) {
                main.post(() -> callback.onError(error.getClass().getSimpleName() + ": " + error.getMessage()));
            } finally { if (connection != null) connection.disconnect(); }
        }, "daak-update-install").start();
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static String shellQuote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
