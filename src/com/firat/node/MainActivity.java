package com.firat.node;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.biometrics.BiometricPrompt;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.provider.CalendarContract;
import android.net.Uri;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.speech.RecognizerIntent;
import android.widget.EditText;
import android.widget.Toast;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int TERMUX_PERMISSION_REQUEST = 73;
    private static final int CALENDAR_PERMISSION_REQUEST = 74;
    private static final int LOCATION_PERMISSION_REQUEST = 75;
    private static final int DICTATION_REQUEST = 76;
    private NodeView nodeView;
    private Runnable pendingProtectedAction;
    private CancellationSignal biometricCancellation;
    private long vaultUnlockedUntil;
    private boolean updateCheckRunning;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        hideSystemBars();
        nodeView = new NodeView(this);
        setContentView(nodeView);
        applyBlackWallpapers();
        NodeStore.schedule(this);
        ensureTermuxPermission();
        if (checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED) {
            startSshdBackground();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        hideSystemBars();
        RememberBridge.flushPending(getApplicationContext());
        if (nodeView != null) {
            nodeView.reloadApps();
            nodeView.startUpdates();
        }
        maybeCheckUpdate(false);
    }

    @Override protected void onPause() {
        if (nodeView != null) nodeView.pauseUpdates();
        super.onPause();
    }

    @Override protected void onDestroy() {
        pendingProtectedAction = null;
        if (biometricCancellation != null) {
            biometricCancellation.cancel();
            biometricCancellation = null;
        }
        if (nodeView != null) nodeView.shutdown();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void applyBlackWallpapers() {
        SharedPreferences prefs = getSharedPreferences(NodeStore.PREFS, 0);
        if (prefs.getBoolean("black_wallpaper_v3", false)) return;
        try {
            Bitmap black = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            black.eraseColor(Color.BLACK);
            WallpaperManager.getInstance(this).setBitmap(black, null, true,
                    WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
            black.recycle();
            prefs.edit().putBoolean("black_wallpaper_v3", true).apply();
        } catch (Exception ignored) { }
    }

    @Override public void onBackPressed() {
        if (nodeView != null && nodeView.mode == NodeView.DISK_VIEW && !"B:\\".equals(nodeView.diskPath)) nodeView.diskUp();
        else if (nodeView != null && nodeView.mode != NodeView.HOME) nodeView.showMode(NodeView.HOME);
        else super.onBackPressed();
    }

    private void ensureTermuxPermission() {
        if (checkSelfPermission("com.termux.permission.RUN_COMMAND") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"com.termux.permission.RUN_COMMAND"}, TERMUX_PERMISSION_REQUEST);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == TERMUX_PERMISSION_REQUEST && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startSshdBackground();
        }
        if (code == CALENDAR_PERMISSION_REQUEST && nodeView != null) nodeView.refreshCalendar();
        if (code == LOCATION_PERMISSION_REQUEST && nodeView != null) nodeView.refreshWeather(true);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != DICTATION_REQUEST || resultCode != RESULT_OK || data == null || nodeView == null) return;
        ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (results != null && !results.isEmpty()) nodeView.showDictationResult(results.get(0));
    }

    private void startDictation() {
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR");
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "DAAK DİKTE // cihaz üzerinde");
            startActivityForResult(intent, DICTATION_REQUEST);
        } catch (RuntimeException error) {
            launchPackage("org.futo.voiceinput");
        }
    }

    private void maybeCheckUpdate(final boolean manual) {
        if (updateCheckRunning) return;
        SharedPreferences prefs = getSharedPreferences(NodeStore.PREFS, 0);
        long now = System.currentTimeMillis();
        if (!manual && now - prefs.getLong("last_update_check", 0L) < 24L * 60L * 60L * 1000L) return;
        updateCheckRunning = true;
        prefs.edit().putLong("last_update_check", now).apply();
        if (manual && nodeView != null) { nodeView.message = "UPDATE // CHECKING"; nodeView.invalidate(); }
        NodeUpdater.check(this, new NodeUpdater.CheckCallback() {
            @Override public void onCurrent() {
                updateCheckRunning = false;
                if (manual) toast("DAAK NODE güncel");
                if (nodeView != null) { nodeView.message = "UPDATE // CURRENT"; nodeView.invalidate(); }
            }
            @Override public void onAvailable(final NodeUpdater.Update update) {
                updateCheckRunning = false;
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("DAAK NODE " + update.versionName)
                        .setMessage((update.notes.length() == 0 ? "Yeni güvenli güncelleme hazır." : update.notes) +
                                "\n\nAPK indirilmeden önce SHA-256 ile doğrulanacak; kurulum biyometri ister.")
                        .setPositiveButton("DOĞRULA VE KUR", (d, which) -> guarded(() -> installUpdate(update)))
                        .setNegativeButton("SONRA", null).show();
            }
            @Override public void onError(String error) {
                updateCheckRunning = false;
                if (manual) toast("Güncelleme kontrolü başarısız: " + error);
                if (nodeView != null && manual) { nodeView.message = "UPDATE // OFFLINE"; nodeView.invalidate(); }
            }
        });
    }

    private void installUpdate(NodeUpdater.Update update) {
        NodeUpdater.downloadAndInstall(this, update, new NodeUpdater.InstallCallback() {
            @Override public void onStatus(String status) {
                if (nodeView != null) { nodeView.message = status; nodeView.invalidate(); }
            }
            @Override public void onError(String error) {
                toast("Güncelleme kurulamadı: " + error);
                if (nodeView != null) { nodeView.message = "UPDATE // FAILED"; nodeView.invalidate(); }
            }
        });
    }

    private void startSshdBackground() {
        runTermuxRaw("pgrep -x sshd >/dev/null 2>&1 || sshd", true, null);
        if (nodeView != null) nodeView.postDelayed(new Runnable() {
            @Override public void run() { nodeView.refreshStatus(); }
        }, 2000L);
    }

    private void guarded(final Runnable action) {
        if (System.currentTimeMillis() < vaultUnlockedUntil) action.run();
        else {
            pendingProtectedAction = action;
            authenticate();
        }
    }

    private void authenticate() {
        try {
            if (biometricCancellation != null) biometricCancellation.cancel();
            biometricCancellation = new CancellationSignal();
            BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                    .setTitle("DAAK NODE // CONTROL VAULT")
                    .setSubtitle("Parmak izi, iris veya cihaz kilidi")
                    .setDeviceCredentialAllowed(true).build();
            prompt.authenticate(biometricCancellation, getMainExecutor(),
                    new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    biometricCancellation = null;
                    if (nodeView == null || isFinishing() || isDestroyed()) return;
                    vaultUnlockedUntil = System.currentTimeMillis() + 90_000L;
                    nodeView.vaultUnlocked = true;
                    nodeView.message = "VAULT OPEN // 90 SEC";
                    Runnable action = pendingProtectedAction;
                    pendingProtectedAction = null;
                    nodeView.invalidate();
                    if (action != null) action.run();
                }
                @Override public void onAuthenticationError(int code, CharSequence error) {
                    biometricCancellation = null;
                    pendingProtectedAction = null;
                    if (nodeView == null || isFinishing() || isDestroyed()) return;
                    nodeView.message = "VAULT LOCKED // SET PIN OR BIOMETRICS";
                    nodeView.invalidate();
                }
            });
        } catch (Exception error) {
            biometricCancellation = null;
            if (nodeView == null || isFinishing() || isDestroyed()) return;
            nodeView.message = "BIOMETRIC SETUP REQUIRED";
            nodeView.invalidate();
            openSettings(Settings.ACTION_SECURITY_SETTINGS);
        }
    }

    private void runTermux(String command, String label) {
        if (checkSelfPermission("com.termux.permission.RUN_COMMAND") != PackageManager.PERMISSION_GRANTED) {
            ensureTermuxPermission();
            nodeView.message = "TERMUX PERMISSION REQUIRED";
            nodeView.invalidate();
            return;
        }
        runTermuxRaw(command, false, label);
    }

    private void runTermuxRaw(String command, boolean background, String label) {
        try {
            Intent intent = new Intent("com.termux.RUN_COMMAND");
            intent.setClassName("com.termux", "com.termux.app.RunCommandService");
            intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", command});
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", background);
            if (!background) intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
            startService(intent);
            if (label != null) {
                nodeView.message = label + " // LAUNCHED";
                nodeView.invalidate();
            }
        } catch (Exception error) {
            if (nodeView != null) {
                nodeView.message = "TERMUX ERROR";
                nodeView.invalidate();
            }
            if (!background) launchPackage("com.termux");
        }
    }

    private void launchPackage(String packageName) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            toast("Uygulama bulunamadı: " + packageName);
            return;
        }
        try { startActivity(intent); }
        catch (RuntimeException error) { toast("Uygulama açılamadı"); }
    }

    private void openSettings(String action) {
        try { startActivity(new Intent(action)); }
        catch (RuntimeException error) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (RuntimeException ignored) { toast("Ayar ekranı açılamadı"); }
        }
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    private String nodeConfig(String key, String fallback) {
        return NodeConfig.get(this, key, fallback);
    }

    private static final class AppEntry {
        final String label;
        final String packageName;
        AppEntry(String label, String packageName) { this.label = label; this.packageName = packageName; }
    }

    private static final class Hit {
        final RectF rect;
        final String action;
        final AppEntry app;
        Hit(RectF rect, String action) { this.rect = rect; this.action = action; this.app = null; }
        Hit(RectF rect, AppEntry app) { this.rect = rect; this.action = "APP"; this.app = app; }
    }

    private final class NodeView extends View {
        static final int HOME = 0, APPS = 1, HELP = 2, CONTROL = 3, DISK_VIEW = 4, REMOTE = 5;
        final int mint = Color.rgb(120, 247, 212);
        final int mintDim = Color.rgb(57, 135, 114);
        final int panel = Color.rgb(5, 12, 10);
        final int panelHot = Color.rgb(8, 27, 22);
        final int line = Color.rgb(19, 59, 49);
        final int soft = Color.rgb(144, 165, 158);
        final int ghost = Color.rgb(62, 82, 76);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Typeface mono = Typeface.create("monospace", Typeface.NORMAL);
        final Handler handler = new Handler(Looper.getMainLooper());
        final List<Hit> hits = new ArrayList<Hit>();
        final List<AppEntry> allApps = new ArrayList<AppEntry>();
        final List<AppEntry> shownApps = new ArrayList<AppEntry>();
        final List<String> diskItems = new ArrayList<String>();
        final List<String> rememberItems = new ArrayList<String>();
        String diskPath = "B:\\";
        int mode = HOME;
        String query = "";
        String meshIp = "CHECKING", macState = "CHECKING", diskState = "CHECKING";
        String sshState = "CHECKING", message = "DAAK NODE V6 READY";
        String weather = "TAP TO ENABLE", agendaOne = "Calendar permission required", agendaTwo = "";
        String mailLine = "Connect Thunderbird + notification access";
        String weatherCity = "";
        String rememberLine = "SYNCING WITH MAC...";
        String diskMessage = "Cached index not loaded";
        long diskRequestToken;
        boolean diskLoading;
        int rememberOpenCount;
        boolean rooted, vaultUnlocked;
        float drawerScroll, downX, downY, lastY;
        boolean moved;
        float burnX, burnY;
        long lastWeatherRefresh, nextDiskRetry, lastObsidianSync;
        int diskRetryStep;
        boolean active, destroyed, statusRefreshRunning;
        LocationManager pendingLocationManager;
        LocationListener pendingLocationListener;
        final Runnable statusTicker = new Runnable() {
            @Override public void run() {
                if (!active || destroyed) return;
                refreshStatus();
                refreshRemember();
                handler.postDelayed(this, 60_000L);
            }
        };

        NodeView(Context context) {
            super(context);
            setBackgroundColor(Color.BLACK);
            reloadApps();
            refreshCalendar();
            refreshWeather(false);
        }

        void startUpdates() {
            if (destroyed) return;
            active = true;
            handler.removeCallbacks(statusTicker);
            refreshStatus();
            refreshRemember();
            syncObsidian(false);
            handler.postDelayed(statusTicker, 60_000L);
        }

        void syncObsidian(boolean force) {
            long now = System.currentTimeMillis();
            if (!force && now - lastObsidianSync < 10L * 60L * 1000L) return;
            lastObsidianSync = now;
            runTermuxRaw("exec ~/.shortcuts/remember-obsidian-sync", true, null);
        }

        void pauseUpdates() {
            active = false;
            handler.removeCallbacks(statusTicker);
        }

        void shutdown() {
            destroyed = true;
            active = false;
            handler.removeCallbacksAndMessages(null);
            clearPendingLocationRequest();
        }

        void clearPendingLocationRequest() {
            if (pendingLocationManager != null && pendingLocationListener != null) {
                try { pendingLocationManager.removeUpdates(pendingLocationListener); }
                catch (RuntimeException ignored) { }
            }
            pendingLocationManager = null;
            pendingLocationListener = null;
        }

        void reloadApps() {
            allApps.clear();
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> infos = getPackageManager().queryIntentActivities(intent, 0);
            for (ResolveInfo info : infos) {
                if (info.activityInfo == null) continue;
                String pkg = info.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                allApps.add(new AppEntry(String.valueOf(info.loadLabel(getPackageManager())), pkg));
            }
            Collections.sort(allApps, new Comparator<AppEntry>() {
                @Override public int compare(AppEntry a, AppEntry b) {
                    return a.label.compareToIgnoreCase(b.label);
                }
            });
            applyFilter();
        }

        AppEntry pinnedApp(int index) {
            String[] defaults = {"org.oxycblt.auxio", "com.sec.android.app.camera",
                    "io.github.yahiaangelo.filmsimulator.android", "ru.tech.imageresizershrinker"};
            String wanted = getSharedPreferences(NodeStore.PREFS, 0).getString("pinned_" + index, defaults[index]);
            for (AppEntry app : allApps) if (app.packageName.equals(wanted)) return app;
            return new AppEntry("EMPTY", wanted);
        }

        String pinnedName(int index) {
            return trimText(pinnedApp(index).label.toUpperCase(Locale.getDefault()), 7);
        }

        String pinnedAction(int index) { return "PKG:" + pinnedApp(index).packageName; }

        void applyFilter() {
            shownApps.clear();
            String needle = query.trim().toLowerCase(Locale.US);
            for (AppEntry app : allApps) {
                if (needle.length() == 0 || app.label.toLowerCase(Locale.US).contains(needle) ||
                        app.packageName.toLowerCase(Locale.US).contains(needle)) shownApps.add(app);
            }
            drawerScroll = 0;
            invalidate();
        }

        void showMode(int newMode) {
            mode = newMode;
            drawerScroll = 0;
            invalidate();
        }

        void refreshStatus() {
            if (destroyed || statusRefreshRunning) return;
            statusRefreshRunning = true;
            long phase = (System.currentTimeMillis() / 60_000L) % 9L;
            burnX = dp((phase % 3L) - 1L);
            burnY = dp((phase / 3L) - 1L);
            new Thread(new Runnable() {
                @Override public void run() {
                    final String ip = findMeshIp();
                    final String mac = canConnect(nodeConfig("mac_host", "mac"), 22) ? "ONLINE" : "OFFLINE";
                    final String disk = canConnect(nodeConfig("lolile_host", "lolile"), 22) ? "ONLINE" : "OFFLINE";
                    final String ssh = canConnect("127.0.0.1", 8022) ? "READY" : "STOPPED";
                    handler.post(new Runnable() {
                        @Override public void run() {
                            statusRefreshRunning = false;
                            if (destroyed) return;
                            meshIp = ip; macState = mac; diskState = disk; sshState = ssh;
                            rooted = new File("/sbin/su").exists() || new File("/system/bin/su").exists();
                            if (System.currentTimeMillis() >= vaultUnlockedUntil) vaultUnlocked = false;
                            updateMailLine();
                            if (disk.equals("ONLINE")) { diskRetryStep = 0; nextDiskRetry = 0; }
                            else scheduleDiskRetry();
                            invalidate();
                        }
                    });
                }
            }, "node-status").start();
        }

        void scheduleDiskRetry() {
            if (meshIp.equals("OFFLINE")) return;
            if (checkSelfPermission("com.termux.permission.RUN_COMMAND") != PackageManager.PERMISSION_GRANTED) return;
            if (getPackageManager().getLaunchIntentForPackage("com.termux") == null) return;
            long now = System.currentTimeMillis();
            if (now < nextDiskRetry) return;
            int[] minutes = {2, 3, 5};
            int delay = minutes[Math.min(diskRetryStep, minutes.length - 1)];
            diskRetryStep++;
            nextDiskRetry = now + delay * 60_000L;
            runTermuxRaw("exec ~/.shortcuts/lolile-list", true, null);
        }

        void updateMailLine() {
            List<String> rows = NodeStore.recentMail(MainActivity.this, System.currentTimeMillis() - 24L * 60L * 60L * 1000L, 1);
            mailLine = rows.isEmpty() ? "Yeni mail yok • rahat ol" : rows.get(0);
        }

        byte[] rememberRequest(String method, String path, byte[] body) throws Exception {
            String host = nodeConfig("remember_host", nodeConfig("mac_host", "mac"));
            URL url = new URL("http://" + host + ":45831" + path);
            HttpURLConnection connection = (HttpURLConnection)url.openConnection();
            try {
                connection.setRequestMethod(method);
                connection.setConnectTimeout(3500);
                connection.setReadTimeout(4500);
                connection.setRequestProperty("Content-Type", "application/json");
                if (body != null && body.length > 0) {
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(body.length);
                    OutputStream output = connection.getOutputStream();
                    output.write(body); output.close();
                }
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
                InputStream input = connection.getInputStream();
                byte[] buffer = new byte[4096]; int count; java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
                while ((count = input.read(buffer)) > 0) data.write(buffer, 0, count);
                input.close(); return data.toByteArray();
            } finally { connection.disconnect(); }
        }

        void refreshRemember() {
            new Thread(() -> {
                try {
                    JSONObject snapshot = new JSONObject(new String(rememberRequest("GET", "/snapshot", null), "UTF-8"));
                    JSONArray items = snapshot.optJSONArray("items");
                    final ArrayList<String> activeItems = new ArrayList<String>();
                    int open = 0;
                    if (items != null) for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.optJSONObject(i);
                        if (item == null || !item.isNull("deletedAt")) continue;
                        String text = item.optString("text", "").trim();
                        if (!item.optBoolean("isDone", false)) open++;
                        if (text.length() > 0 && activeItems.size() < 12)
                            activeItems.add((item.optBoolean("isDone", false) ? "✓ " : "• ") + text);
                    }
                    final int openFinal = open;
                    handler.post(() -> {
                        if (destroyed) return;
                        rememberItems.clear(); rememberItems.addAll(activeItems); rememberOpenCount = openFinal;
                        rememberLine = activeItems.isEmpty() ? "NOT YOK • TAP TO CAPTURE" : openFinal + " OPEN • " + activeItems.get(0).substring(2);
                        invalidate();
                    });
                } catch (Exception error) {
                    handler.post(() -> { if (!destroyed) { rememberLine = "MAC SYNC OFFLINE"; invalidate(); } });
                }
            }, "node-remember-read").start();
        }

        void addRememberNote(final String rawText) {
            final String text = rawText.trim();
            if (text.length() == 0) return;
            message = "REMEMBER // SENDING"; invalidate();
            new Thread(() -> {
                try {
                    JSONObject snapshot = new JSONObject(new String(rememberRequest("GET", "/snapshot", null), "UTF-8"));
                    JSONArray items = snapshot.optJSONArray("items");
                    if (items == null) items = new JSONArray();
                    double appleTime = System.currentTimeMillis() / 1000.0 - 978307200.0;
                    JSONObject note = new JSONObject();
                    note.put("id", UUID.randomUUID().toString().toUpperCase(Locale.US));
                    note.put("text", text); note.put("createdAt", appleTime); note.put("updatedAt", appleTime);
                    note.put("isDone", false); note.put("deletedAt", JSONObject.NULL); items.put(note);
                    JSONObject envelope = new JSONObject(); envelope.put("deviceName", "DAAK NODE"); envelope.put("items", items);
                    rememberRequest("POST", "/merge", envelope.toString().getBytes("UTF-8"));
                    handler.post(() -> { if (!destroyed) { message = "REMEMBER // SYNCED"; syncObsidian(true); refreshRemember(); } });
                } catch (Exception error) {
                    handler.post(() -> { if (!destroyed) { message = "REMEMBER // MAC OFFLINE"; invalidate(); } });
                }
            }, "node-remember-write").start();
        }

        void refreshCalendar() {
            if (checkSelfPermission("android.permission.READ_CALENDAR") != PackageManager.PERMISSION_GRANTED) {
                agendaOne = "Tap to grant calendar access"; agendaTwo = ""; invalidate(); return;
            }
            new Thread(() -> {
                ArrayList<String> rows = new ArrayList<String>();
                long begin = System.currentTimeMillis(), end = begin + 7L * 24L * 60L * 60L * 1000L;
                Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
                ContentUris.appendId(builder, begin); ContentUris.appendId(builder, end);
                Cursor cursor = null;
                try {
                    cursor = getContentResolver().query(builder.build(), new String[]{CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN}, null, null, CalendarContract.Instances.BEGIN + " ASC");
                    while (cursor != null && cursor.moveToNext() && rows.size() < 2) {
                        String at = new SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(new Date(cursor.getLong(1)));
                        rows.add(at + " • " + cursor.getString(0));
                    }
                } catch (Exception ignored) { }
                finally { if (cursor != null) cursor.close(); }
                final String one = rows.size() > 0 ? rows.get(0) : "Yaklaşan etkinlik yok";
                final String two = rows.size() > 1 ? rows.get(1) : "";
                handler.post(() -> {
                    if (destroyed) return;
                    agendaOne = one; agendaTwo = two; invalidate();
                });
            }, "node-calendar").start();
        }

        void refreshWeather(boolean force) {
            if (!force && System.currentTimeMillis() - lastWeatherRefresh < 30L * 60L * 1000L) return;
            String configuredCity = getSharedPreferences(NodeStore.PREFS, 0).getString("weather_city", "").trim();
            if (configuredCity.length() > 0) { fetchCityWeather(configuredCity); return; }
            if (checkSelfPermission("android.permission.ACCESS_COARSE_LOCATION") != PackageManager.PERMISSION_GRANTED) {
                weather = "TAP FOR LOCATION"; invalidate(); return;
            }
            LocationManager manager = (LocationManager)getSystemService(LOCATION_SERVICE);
            if (manager == null) {
                weather = "LOCATION UNAVAILABLE"; invalidate(); return;
            }
            Location location = null;
            try {
                location = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (location == null) location = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            } catch (RuntimeException ignored) { }
            if (location == null) {
                weather = "LOCATING..."; invalidate();
                try {
                    clearPendingLocationRequest();
                    pendingLocationManager = manager;
                    pendingLocationListener = new LocationListener() {
                        @Override public void onLocationChanged(Location fresh) {
                            clearPendingLocationRequest();
                            if (!destroyed) fetchWeather(fresh);
                        }
                        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
                        @Override public void onProviderEnabled(String provider) { }
                        @Override public void onProviderDisabled(String provider) {
                            clearPendingLocationRequest();
                            if (!destroyed) { weather = "LOCATION DISABLED"; invalidate(); }
                        }
                    };
                    manager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER,
                            pendingLocationListener, Looper.getMainLooper());
                } catch (RuntimeException error) {
                    clearPendingLocationRequest();
                    weather = "LOCATION DISABLED"; invalidate();
                }
                return;
            }
            fetchWeather(location);
        }

        void fetchWeather(Location location) {
            final double lat = location.getLatitude(), lon = location.getLongitude();
            new Thread(() -> {
                HttpURLConnection connection = null;
                try {
                    URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon + "&current=temperature_2m,apparent_temperature,weather_code&timezone=auto");
                    connection = (HttpURLConnection)url.openConnection(); connection.setConnectTimeout(3500); connection.setReadTimeout(3500);
                    InputStream input = connection.getInputStream(); StringBuilder json = new StringBuilder(); byte[] buffer = new byte[2048]; int count;
                    while ((count = input.read(buffer)) > 0) json.append(new String(buffer, 0, count, "UTF-8")); input.close();
                    JSONObject current = new JSONObject(json.toString()).getJSONObject("current");
                    final String prefix = weatherCity.length() == 0 ? "" : weatherCity.toUpperCase(Locale.getDefault()) + " • ";
                    final String value = prefix + Math.round(current.getDouble("temperature_2m")) + "°C • feels " + Math.round(current.getDouble("apparent_temperature")) + "° • " + weatherCode(current.getInt("weather_code"));
                    lastWeatherRefresh = System.currentTimeMillis(); handler.post(() -> {
                        if (!destroyed) { weather = value; invalidate(); }
                    });
                } catch (Exception error) { handler.post(() -> {
                    if (!destroyed) { weather = "WEATHER OFFLINE"; invalidate(); }
                }); }
                finally { if (connection != null) connection.disconnect(); }
            }, "node-weather").start();
        }

        void fetchCityWeather(final String city) {
            weather = "SEARCHING " + city.toUpperCase(Locale.getDefault()); invalidate();
            new Thread(() -> {
                HttpURLConnection connection = null;
                try {
                    URL url = new URL("https://geocoding-api.open-meteo.com/v1/search?name=" + URLEncoder.encode(city, "UTF-8") + "&count=1&language=tr&format=json");
                    connection = (HttpURLConnection)url.openConnection(); connection.setConnectTimeout(3500); connection.setReadTimeout(3500);
                    InputStream input = connection.getInputStream(); StringBuilder json = new StringBuilder(); byte[] buffer = new byte[2048]; int count;
                    while ((count = input.read(buffer)) > 0) json.append(new String(buffer, 0, count, "UTF-8")); input.close();
                    JSONObject result = new JSONObject(json.toString()).getJSONArray("results").getJSONObject(0);
                    Location location = new Location("city"); location.setLatitude(result.getDouble("latitude")); location.setLongitude(result.getDouble("longitude"));
                    weatherCity = result.optString("name", city); fetchWeather(location);
                } catch (Exception error) { handler.post(() -> {
                    if (!destroyed) { weather = "CITY NOT FOUND"; invalidate(); }
                }); }
                finally { if (connection != null) connection.disconnect(); }
            }, "node-weather-city").start();
        }

        String weatherCode(int code) {
            if (code == 0) return "CLEAR";
            if (code <= 3) return "CLOUDY";
            if (code <= 48) return "FOG";
            if (code <= 67) return "RAIN";
            if (code <= 77) return "SNOW";
            if (code <= 82) return "SHOWERS";
            return "STORM";
        }

        String trimText(String value, int max) {
            if (value == null) return "";
            return value.length() <= max ? value : value.substring(0, max - 1) + "…";
        }

        boolean canConnect(String host, int port) {
            Socket socket = new Socket();
            try { socket.connect(new InetSocketAddress(host, port), 2500); return true; }
            catch (Exception ignored) { return false; }
            finally { try { socket.close(); } catch (Exception ignored) { } }
        }

        String findMeshIp() {
            try {
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                    for (java.net.InetAddress address : Collections.list(ni.getInetAddresses())) {
                        String host = address.getHostAddress();
                        if (!address.isLoopbackAddress() && host != null && host.startsWith("100.")) return host;
                    }
            } catch (Exception ignored) { }
            return "OFFLINE";
        }

        float dp(float value) { return value * getResources().getDisplayMetrics().density; }
        void softHaptic() {
            try {
                Vibrator vibrator = (Vibrator)getSystemService(VIBRATOR_SERVICE);
                if (vibrator == null || !vibrator.hasVibrator()) return;
                if (android.os.Build.VERSION.SDK_INT >= 26)
                    vibrator.vibrate(VibrationEffect.createOneShot(9L, 64));
                else vibrator.vibrate(9L);
            } catch (RuntimeException ignored) { }
        }
        void type(float size, int color, boolean bold) {
            paint.setTextSize(dp(size)); paint.setColor(color);
            paint.setTypeface(Typeface.create(mono, bold ? Typeface.BOLD : Typeface.NORMAL));
            paint.setStyle(Paint.Style.FILL); paint.setStrokeWidth(dp(1));
        }
        void box(Canvas c, RectF r, float radius, int fill, int stroke) {
            paint.setStyle(Paint.Style.FILL); paint.setColor(fill); c.drawRoundRect(r, dp(radius), dp(radius), paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(stroke);
            c.drawRoundRect(r, dp(radius), dp(radius), paint);
        }
        void center(Canvas c, String text, float x, float y) { c.drawText(text, x - paint.measureText(text) / 2f, y, paint); }
        void addHit(RectF rect, String action) { hits.add(new Hit(new RectF(rect), action)); }
        void button(Canvas c, RectF rect, String tag, String title, String subtitle, boolean hot, String action) {
            box(c, rect, 13, hot ? panelHot : panel, hot ? mintDim : line);
            type(7, hot ? mint : mintDim, true); c.drawText(tag, rect.left + dp(12), rect.top + dp(17), paint);
            type(11, hot ? mint : soft, true); c.drawText(title, rect.left + dp(12), rect.top + dp(39), paint);
            type(7, ghost, false); c.drawText(subtitle, rect.left + dp(12), rect.bottom - dp(10), paint);
            addHit(rect, action);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); c.drawColor(Color.BLACK); hits.clear();
            c.save(); c.translate(burnX, burnY);
            drawTopBar(c);
            if (getWidth() > getHeight()) {
                if (mode == HOME) drawHomeLandscape(c);
                else if (mode == APPS) drawAppsLandscape(c);
                else if (mode == HELP) drawHelpLandscape(c);
                else if (mode == CONTROL) drawControlLandscape(c);
                else if (mode == DISK_VIEW) drawDiskLandscape(c);
                else drawRemoteLandscape(c);
                drawDockLandscape(c);
            } else {
                if (mode == HOME) drawHome(c);
                else if (mode == APPS) drawApps(c);
                else if (mode == HELP) drawHelp(c);
                else if (mode == CONTROL) drawControl(c);
                else if (mode == DISK_VIEW) drawDisk(c);
                else drawRemote(c);
                drawDock(c);
            }
            c.restore();
        }

        void drawTopBar(Canvas c) {
            float w = getWidth();
            type(8, mintDim, true); c.drawText("DAAK//NODE", dp(16), dp(25), paint);
            type(13, mint, true); c.drawText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date()), dp(16), dp(45), paint);
            String state = meshIp.equals("OFFLINE") ? "NO MESH" : "TAILNET";
            type(8, meshIp.equals("OFFLINE") ? soft : mint, true);
            c.drawText(state, w - dp(105), dp(25), paint);
            BatteryManager bm = (BatteryManager)getSystemService(BATTERY_SERVICE);
            int battery = bm == null ? 0 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            type(8, soft, true); c.drawText("BAT " + battery + "%", w - dp(105), dp(43), paint);
            RectF control = new RectF(w - dp(36), dp(9), w - dp(10), dp(47));
            type(18, mint, true); center(c, "≡", control.centerX(), dp(36)); addHit(control, "CONTROL");
            paint.setColor(line); paint.setStrokeWidth(dp(1)); c.drawLine(dp(16), dp(55), w - dp(16), dp(55), paint);
        }

        void drawHome(Canvas c) {
            float left = dp(16), right = getWidth() - dp(16), gap = dp(8);
            RectF terminal = new RectF(left, dp(67), right, dp(192));
            box(c, terminal, 16, panel, line);
            type(7, mintDim, true); c.drawText("// LIVE CONTROL PLANE", left + dp(14), dp(86), paint);
            type(9, mint, false);
            c.drawText("MESH  " + meshIp, left + dp(14), dp(109), paint);
            c.drawText("MAC " + macState + "  •  LOLILE " + diskState, left + dp(14), dp(132), paint);
            c.drawText("SSHD " + sshState + "  •  ROOT " + (rooted ? "YES" : "NO"), left + dp(14), dp(155), paint);
            type(6.8f, soft, true); c.drawText("> " + trimText(message, 43), left + dp(14), dp(178), paint);

            type(7, mintDim, true); c.drawText("// WORKSPACE", left, dp(211), paint);
            float half = (right - left - gap) / 2f;
            button(c, new RectF(left, dp(221), left + half, dp(277)), "CLI", "CODEX", "MAC • REAL CLI", true, "CODEX");
            button(c, new RectF(left + half + gap, dp(221), right, dp(277)), "SFTP", "LOLILE DISK", "B:\\ • TAILNET", true, "DISK");
            button(c, new RectF(left, dp(285), left + half, dp(341)), "TOUCH", "REMOTE", "4 DEVICES", false, "REMOTE");
            button(c, new RectF(left + half + gap, dp(285), right, dp(341)), "LINUX", "DEBIAN", "LOCAL PROOT", false, "LOCAL");

            type(7, mintDim, true); c.drawText("// INTELLIGENCE", left, dp(363), paint);
            RectF mail = new RectF(left, dp(374), left + half, dp(440));
            box(c, mail, 12, panel, line); type(7, mint, true); c.drawText("MAIL // READ ONLY", mail.left + dp(10), dp(393), paint);
            type(6.5f, soft, false); c.drawText(trimText(mailLine, 24), mail.left + dp(10), dp(417), paint);
            type(6, ghost, false); c.drawText("07:30 + HOURLY", mail.left + dp(10), dp(432), paint); addHit(mail, "MAIL");
            RectF climate = new RectF(left + half + gap, dp(374), right, dp(440));
            box(c, climate, 12, panel, line); type(7, mint, true); c.drawText("WEATHER", climate.left + dp(10), dp(393), paint);
            type(6.5f, soft, false); c.drawText(trimText(weather, 23), climate.left + dp(10), dp(417), paint);
            type(6, ghost, false); c.drawText("OPEN-METEO • 30 MIN", climate.left + dp(10), dp(432), paint); addHit(climate, "WEATHER");
            RectF agenda = new RectF(left, dp(448), right, dp(514));
            box(c, agenda, 12, panel, line); type(7, mint, true); c.drawText("AGENDA // NEXT 7 DAYS", agenda.left + dp(10), dp(467), paint);
            type(6.5f, soft, false); c.drawText(trimText(agendaOne, 49), agenda.left + dp(10), dp(489), paint);
            type(6.2f, ghost, false); c.drawText(trimText(agendaTwo, 52), agenda.left + dp(10), dp(506), paint); addHit(agenda, "CALENDAR");

            type(7, mintDim, true); c.drawText("// PINNED", left, dp(535), paint);
            String[] names = {pinnedName(0), pinnedName(1), pinnedName(2), pinnedName(3), "REMEM", "OBSID"};
            String[] actions = {pinnedAction(0), pinnedAction(1), pinnedAction(2), pinnedAction(3), "REMEMBER", "OBSIDIAN"};
            float cardW = (right - left - gap * 5f) / 6f;
            for (int i = 0; i < 6; i++) {
                RectF r = new RectF(left + i * (cardW + gap), dp(545), left + i * (cardW + gap) + cardW, dp(594));
                box(c, r, 12, panel, line); type(5.4f, soft, true); center(c, names[i], r.centerX(), dp(575));
                addHit(r, actions[i]);
            }
            type(6.5f, ghost, false); c.drawText("SWIPE DOWN → NODE CONTROL", left, dp(613), paint);
        }

        void drawHomeLandscape(Canvas c) {
            float left = dp(16), right = getWidth() - dp(16), top = dp(67), bottom = getHeight() - dp(58), gap = dp(8);
            float col = (right - left - gap * 2f) / 3f;
            float x1 = left, x2 = left + col + gap, x3 = x2 + col + gap;

            RectF terminal = new RectF(x1, top, x1 + col, dp(191));
            box(c, terminal, 14, panel, line); type(7, mintDim, true); c.drawText("// LIVE CONTROL", x1 + dp(12), top + dp(20), paint);
            type(8, mint, false); c.drawText("MESH  " + meshIp, x1 + dp(12), top + dp(45), paint);
            c.drawText("MAC " + macState + " • LOLILE " + diskState, x1 + dp(12), top + dp(67), paint);
            c.drawText("SSHD " + sshState + " • ROOT " + (rooted ? "YES" : "NO"), x1 + dp(12), top + dp(89), paint);
            type(6.2f, soft, true); c.drawText("> " + trimText(message, 34), x1 + dp(12), top + dp(112), paint);

            float tileTop = dp(199), tileH = (bottom - tileTop - gap) / 2f, tileW = (col - gap) / 2f;
            button(c, new RectF(x1, tileTop, x1 + tileW, tileTop + tileH), "CLI", "CODEX", "MAC CLI", true, "CODEX");
            button(c, new RectF(x1 + tileW + gap, tileTop, x1 + col, tileTop + tileH), "SFTP", "DISK", "LOLILE B:\\", true, "DISK");
            button(c, new RectF(x1, tileTop + tileH + gap, x1 + tileW, bottom), "TOUCH", "REMOTE", "4 DEVICES", false, "REMOTE");
            button(c, new RectF(x1 + tileW + gap, tileTop + tileH + gap, x1 + col, bottom), "LINUX", "DEBIAN", "LOCAL", false, "LOCAL");

            RectF remember = new RectF(x2, top, x2 + col, dp(174));
            box(c, remember, 14, panelHot, mintDim); type(8, mint, true); c.drawText("daakREMEMBER // TAILSYNC", x2 + dp(12), top + dp(22), paint);
            type(7, soft, false); c.drawText(trimText(rememberLine, 34), x2 + dp(12), top + dp(48), paint);
            type(6, ghost, false); c.drawText("TAP → VIEW / CAPTURE • 45831", x2 + dp(12), top + dp(73), paint); addHit(remember, "REMEMBER");
            RectF obsidian = new RectF(x2, dp(182), x2 + col, dp(239));
            button(c, obsidian, "MD", "OBSIDIAN", "DAAK VAULT • LOCAL", false, "OBSIDIAN");
            float miniTop = dp(247), miniW = (col - gap * 2f) / 3f;
            String[] miniNames = {pinnedName(0), pinnedName(1), "MAIL"};
            String[] miniActions = {pinnedAction(0), pinnedAction(1), "MAIL"};
            for (int i = 0; i < 3; i++) {
                RectF r = new RectF(x2 + i * (miniW + gap), miniTop, x2 + i * (miniW + gap) + miniW, bottom);
                box(c, r, 11, panel, line); type(6.5f, soft, true); center(c, miniNames[i], r.centerX(), r.centerY() + dp(3)); addHit(r, miniActions[i]);
            }

            RectF mail = new RectF(x3, top, x3 + col, dp(126));
            box(c, mail, 12, panel, line); type(7, mint, true); c.drawText("MAIL // ALL ACCOUNTS", x3 + dp(10), top + dp(20), paint);
            type(6.3f, soft, false); c.drawText(trimText(mailLine, 35), x3 + dp(10), top + dp(42), paint); addHit(mail, "MAIL");
            RectF climate = new RectF(x3, dp(134), x3 + col, dp(193));
            box(c, climate, 12, panel, line); type(7, mint, true); c.drawText("WEATHER", x3 + dp(10), dp(153), paint);
            type(6.3f, soft, false); c.drawText(trimText(weather, 35), x3 + dp(10), dp(177), paint); addHit(climate, "WEATHER");
            RectF agenda = new RectF(x3, dp(201), x3 + col, bottom);
            box(c, agenda, 12, panel, line); type(7, mint, true); c.drawText("AGENDA // NEXT", x3 + dp(10), dp(221), paint);
            type(6.3f, soft, false); c.drawText(trimText(agendaOne, 35), x3 + dp(10), dp(246), paint);
            type(6, ghost, false); c.drawText(trimText(agendaTwo, 37), x3 + dp(10), dp(269), paint); addHit(agenda, "CALENDAR");
        }

        void drawApps(Canvas c) {
            float left = dp(16), right = getWidth() - dp(16);
            type(17, mint, true); c.drawText("ALL APPS", left, dp(87), paint);
            type(7, soft, false); c.drawText(shownApps.size() + " LAUNCHABLE • TEXT MODE", left, dp(105), paint);
            RectF search = new RectF(left, dp(116), right, dp(157));
            box(c, search, 11, panel, line); type(9, query.length() == 0 ? soft : mint, false);
            c.drawText(query.length() == 0 ? "SEARCH APPLICATIONS..." : "FILTER: " + query.toUpperCase(Locale.US), left + dp(12), dp(142), paint);
            addHit(search, "SEARCH");
            float top = dp(170), bottom = getHeight() - dp(82), rowH = dp(49);
            c.save(); c.clipRect(left, top, right, bottom);
            for (int i = 0; i < shownApps.size(); i++) {
                float y = top + i * rowH - drawerScroll;
                if (y + rowH < top || y > bottom) continue;
                RectF row = new RectF(left, y, right, y + rowH - dp(3));
                if (i % 2 == 0) { paint.setStyle(Paint.Style.FILL); paint.setColor(panel); c.drawRect(row, paint); }
                type(8, mintDim, true); c.drawText(String.format(Locale.US, "%02d", i + 1), left + dp(8), y + dp(20), paint);
                type(10, soft, true); c.drawText(shownApps.get(i).label.toUpperCase(Locale.US), left + dp(40), y + dp(20), paint);
                type(6, ghost, false); c.drawText(shownApps.get(i).packageName, left + dp(40), y + dp(37), paint);
                hits.add(new Hit(new RectF(row), shownApps.get(i)));
            }
            c.restore();
        }

        void drawAppsLandscape(Canvas c) {
            float left = dp(16), right = getWidth() - dp(16), gap = dp(8);
            RectF search = new RectF(left, dp(67), right, dp(105));
            box(c, search, 10, panel, line); type(8, query.length() == 0 ? soft : mint, false);
            c.drawText(query.length() == 0 ? "ALL APPS // TAP TO SEARCH" : "FILTER: " + query.toUpperCase(Locale.US), left + dp(12), dp(92), paint); addHit(search, "SEARCH");
            float top = dp(114), bottom = getHeight() - dp(58), rowH = dp(42), colW = (right - left - gap) / 2f;
            c.save(); c.clipRect(left, top, right, bottom);
            for (int i = 0; i < shownApps.size(); i++) {
                int rowIndex = i / 2, column = i % 2;
                float y = top + rowIndex * rowH - drawerScroll, x = left + column * (colW + gap);
                if (y + rowH < top || y > bottom) continue;
                RectF row = new RectF(x, y, x + colW, y + rowH - dp(3));
                box(c, row, 7, panel, line); type(7, mintDim, true); c.drawText(String.format(Locale.US, "%02d", i + 1), x + dp(8), y + dp(17), paint);
                type(8, soft, true); c.drawText(trimText(shownApps.get(i).label.toUpperCase(Locale.US), 25), x + dp(36), y + dp(18), paint);
                type(5.5f, ghost, false); c.drawText(trimText(shownApps.get(i).packageName, 39), x + dp(36), y + dp(33), paint);
                hits.add(new Hit(new RectF(row), shownApps.get(i)));
            }
            c.restore();
        }

        void drawHelpLandscape(Canvas c) {
            float left = dp(16), right = getWidth() - dp(16), top = dp(67), bottom = getHeight() - dp(58), gap = dp(8);
            String[] lines = {
                    "CODEX → Mac gerçek CLI", "LOLILE → B:\\ Tailnet SFTP", "REMEMBER → Mac TailSync", "OBSIDIAN → DAAK Vault",
                    "MAIL → 5+ hesap salt okunur", "AGENDA → Android Calendar", "VAULT → biyometri/PIN", "APPS → ara ve çalıştır"
            };
            float colW = (right - left - gap * 3f) / 4f, rowH = (bottom - top - gap) / 2f;
            for (int i = 0; i < lines.length; i++) {
                int row = i / 4, colIndex = i % 4;
                RectF r = new RectF(left + colIndex * (colW + gap), top + row * (rowH + gap), left + colIndex * (colW + gap) + colW, top + row * (rowH + gap) + rowH);
                box(c, r, 11, panel, line); type(7, i < 4 ? mint : soft, true); c.drawText(trimText(lines[i], 25), r.left + dp(10), r.top + dp(25), paint);
            }
        }

        void drawControlLandscape(Canvas c) {
            float left = dp(16), right = getWidth() - dp(16), top = dp(67), bottom = getHeight() - dp(58), gap = dp(8);
            String[][] tiles = {
                    {"VPN", "TAILSCALE", "PKG:com.tailscale.ipn"}, {"PC", "DAAK LOLILE", "LOLILE_HUB"}, {"PIN", "PINNED APPS", "PINS"}, {"KEY", "KEYBOARD", "SET:INPUT"},
                    {"SEC", "BIOMETRICS", "SET:SECURITY"}, {"WA", "WHATSAPP TASKS", "WHATSAPP"}, {"MIC", "DAAK INBOX", "DICTATE"}, {"UP", "UPDATE", "CHECK_UPDATE"}
            };
            float colW = (right - left - gap * 3f) / 4f, rowH = (bottom - top - gap) / 2f;
            for (int i = 0; i < tiles.length; i++) {
                int row = i / 4, colIndex = i % 4;
                RectF r = new RectF(left + colIndex * (colW + gap), top + row * (rowH + gap), left + colIndex * (colW + gap) + colW, top + row * (rowH + gap) + rowH);
                button(c, r, tiles[i][0], tiles[i][1], i == 0 ? meshIp : "OPEN PANEL", i == 7, tiles[i][2]);
            }
        }

        String remoteEndpoint(int index) {
            SharedPreferences prefs = getSharedPreferences(NodeStore.PREFS, 0);
            String[] keys = {"remote_lolile", "remote_mac", "remote_bedirhan_mac", "remote_bedirhan_windows"};
            String fallback = "";
            if (index == 0) fallback = nodeConfig("lolile_host", "lolile") + ":21118";
            else if (index == 1) fallback = nodeConfig("mac_host", "mac") + ":21118";
            return prefs.getString(keys[index], fallback).trim();
        }

        void drawRemote(Canvas c) {
            float left = dp(16), right = getWidth() - dp(16), gap = dp(8);
            type(17, mint, true); c.drawText("REMOTE // TOUCH DESKTOP", left, dp(88), paint);
            type(7, soft, false); c.drawText("RUSTDESK • TAILNET DIRECT • NO PUBLIC PORT", left, dp(107), paint);
            String[] names = {"LOLILE WINDOWS", "MY MAC", "BEDIRHAN MAC", "BEDIRHAN WINDOWS"};
            float half = (right - left - gap) / 2f, top = dp(124), h = dp(92);
            for (int i = 0; i < names.length; i++) {
                int row = i / 2, col = i % 2;
                String endpoint = remoteEndpoint(i);
                RectF r = new RectF(left + col * (half + gap), top + row * (h + gap),
                        left + col * (half + gap) + half, top + row * (h + gap) + h);
                button(c, r, "TOUCH", names[i], endpoint.length() == 0 ? "NOT CONFIGURED" : trimText(endpoint, 24),
                        i < 2, "REMOTE_CONNECT:" + i);
            }
            RectF configure = new RectF(left, top + dp(208), right, top + dp(278));
            button(c, configure, "EDIT", "REMOTE DEVICES", "IDS / TAILNET IP • LOCAL ONLY", false, "REMOTE_CONFIG");
            type(7, ghost, false); c.drawText("RustDesk toolbar → Touch mode • keyboard icon → on-screen keys", left, top + dp(304), paint);
        }

        void drawRemoteLandscape(Canvas c) {
            float left = dp(16), right = getWidth() - dp(16), top = dp(67), bottom = getHeight() - dp(58), gap = dp(8);
            String[] names = {"LOLILE WINDOWS", "MY MAC", "BEDIRHAN MAC", "BEDIRHAN WINDOWS"};
            float side = dp(180), listLeft = left + side + gap;
            RectF configure = new RectF(left, top, left + side, bottom);
            button(c, configure, "REMOTE", "TOUCH HUB", "TAP TO CONFIGURE", true, "REMOTE_CONFIG");
            float cell = (right - listLeft - gap) / 2f, h = (bottom - top - gap) / 2f;
            for (int i = 0; i < names.length; i++) {
                int row = i / 2, col = i % 2;
                String endpoint = remoteEndpoint(i);
                RectF r = new RectF(listLeft + col * (cell + gap), top + row * (h + gap),
                        listLeft + col * (cell + gap) + cell, top + row * (h + gap) + h);
                button(c, r, "TOUCH", names[i], endpoint.length() == 0 ? "NOT CONFIGURED" : trimText(endpoint, 27),
                        i < 2, "REMOTE_CONNECT:" + i);
            }
        }

        void connectRemote(int index) {
            String endpoint = remoteEndpoint(index);
            if (endpoint.length() == 0) { showRemoteConfig(index); return; }
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("rustdesk://connect/" + Uri.encode(endpoint)));
                intent.setPackage("com.carriez.flutter_hbb"); startActivity(intent);
            } catch (RuntimeException error) { launchPackage("com.carriez.flutter_hbb"); }
        }

        void showRemoteConfig() {
            final String[] names = {"LOLILE Windows", "My Mac", "Bedirhan Mac", "Bedirhan Windows"};
            new AlertDialog.Builder(MainActivity.this).setTitle("REMOTE // DEVICE SELECT")
                    .setItems(names, (dialog, which) -> showRemoteConfig(which))
                    .setPositiveButton("RUSTDESK", (d, which) -> launchPackage("com.carriez.flutter_hbb"))
                    .setNegativeButton("KAPAT", null).show();
        }

        void showRemoteConfig(final int index) {
            final String[] keys = {"remote_lolile", "remote_mac", "remote_bedirhan_mac", "remote_bedirhan_windows"};
            final String[] names = {"LOLILE Windows", "My Mac", "Bedirhan Mac", "Bedirhan Windows"};
            final EditText input = new EditText(MainActivity.this);
            input.setSingleLine(true); input.setHint("RustDesk ID or Tailnet IP:21118"); input.setText(remoteEndpoint(index));
            new AlertDialog.Builder(MainActivity.this).setTitle(names[index])
                    .setMessage("Şifre burada tutulmaz; RustDesk güvenli deposunda kalır.")
                    .setView(input)
                    .setPositiveButton("KAYDET", (d, which) -> {
                        getSharedPreferences(NodeStore.PREFS, 0).edit().putString(keys[index], input.getText().toString().trim()).apply();
                        invalidate();
                    })
                    .setNeutralButton("TEMİZLE", (d, which) -> {
                        getSharedPreferences(NodeStore.PREFS, 0).edit().putString(keys[index], "").apply(); invalidate();
                    })
                    .setNegativeButton("İPTAL", null).show();
        }

        void drawDiskLandscape(Canvas c) {
            float left = dp(16), right = getWidth() - dp(16), top = dp(67), bottom = getHeight() - dp(58), gap = dp(8);
            float side = dp(190), listLeft = left + side + gap;
            RectF refresh = new RectF(left, top, left + side, bottom);
            button(c, refresh, "SFTP", trimText(diskPath, 22), diskState + " • TAP REFRESH", true, "DISK_REFRESH");
            float colW = (right - listLeft - gap) / 2f, rowH = dp(42);
            if (diskItems.isEmpty()) { type(9, soft, false); c.drawText(diskMessage, listLeft, top + dp(28), paint); }
            else for (int i = 0; i < diskItems.size() && i < 12; i++) {
                int row = i / 2, colIndex = i % 2; float x = listLeft + colIndex * (colW + gap), y = top + row * rowH;
                if (y + rowH > bottom) break;
                RectF r = new RectF(x, y, x + colW, y + rowH - dp(3)); box(c, r, 7, panel, line);
                String item = diskItems.get(i); type(7, item.startsWith("[D]") ? mint : soft, item.startsWith("[D]")); c.drawText(trimText(item, 34), x + dp(9), y + dp(25), paint); addHit(r, "DISK_ITEM:" + i);
            }
        }

        void drawHelp(Canvas c) {
            float left = dp(16), right = getWidth() - dp(16);
            type(17, mint, true); c.drawText("HELP // START HERE", left, dp(88), paint);
            String[] lines = {
                    "CODEX  → Mac'teki gerçek Codex CLI; API kullanmaz.",
                    "LOLILE → B:\\ diski; Tailscale + Ed25519 SFTP.",
                    "LOCAL  → Telefonda Debian Linux ortamı.",
                    "APPS   → Tüm uygulamalar; dokun, ara, kaydır.",
                    "VAULT  → Kritik komutlar için 90 sn biyometrik izin.",
                    "MESH   → İnternet portu yok; Tailnet cihazları erişir.",
                    "MAIL   → Salt okunur özet; gönderim daima açık onay.",
                    "CACHE  → Hassas mail özeti 10 dakikada temizlenir."
            };
            float y = dp(121);
            for (String lineText : lines) {
                RectF r = new RectF(left, y, right, y + dp(43)); box(c, r, 9, panel, line);
                type(7.2f, soft, false); c.drawText(lineText, left + dp(10), y + dp(26), paint); y += dp(49);
            }
            type(7, mintDim, true); c.drawText("// SETUP & RECOVERY", left, y + dp(8), paint); y += dp(19);
            float gap = dp(7), half = (right - left - gap) / 2f;
            button(c, new RectF(left, y, left + half, y + dp(58)), "SET", "BIOMETRICS", "PIN / IRIS", false, "SET:SECURITY");
            button(c, new RectF(left + half + gap, y, right, y + dp(58)), "SET", "ANDROID", "SYSTEM SETTINGS", false, "SET:SYSTEM");
            y += dp(66);
            button(c, new RectF(left, y, left + half, y + dp(58)), "NET", "TAILSCALE", meshIp, false, "PKG:com.tailscale.ipn");
            button(c, new RectF(left + half + gap, y, right, y + dp(58)), "KEY", "KEY MAPPER", "BIXBY", false, "PKG:io.github.sds100.keymapper");
        }

        void drawControl(Canvas c) {
            float left = dp(16), right = getWidth() - dp(16), gap = dp(8);
            type(17, mint, true); c.drawText("NODE CONTROL", left, dp(88), paint);
            type(7, soft, false); c.drawText("SYSTEM PANELS • SAFE LINKS", left, dp(106), paint);
            String[][] tiles = {
                    {"VPN", "TAILSCALE", "PKG:com.tailscale.ipn"}, {"PC", "DAAK LOLILE", "LOLILE_HUB"},
                    {"PIN", "PINNED APPS", "PINS"}, {"KEY", "KEYBOARD", "SET:INPUT"},
                    {"SEC", "BIOMETRICS", "SET:SECURITY"}, {"WA", "WHATSAPP TASKS", "WHATSAPP"},
                    {"UP", "UPDATE", "CHECK_UPDATE"}, {"MIC", "DAAK INBOX", "DICTATE"}
            };
            float half = (right - left - gap) / 2f, top = dp(124), h = dp(71);
            for (int i = 0; i < tiles.length; i++) {
                int row = i / 2, col = i % 2;
                RectF r = new RectF(left + col * (half + gap), top + row * (h + gap),
                        left + col * (half + gap) + half, top + row * (h + gap) + h);
                button(c, r, tiles[i][0], tiles[i][1], i == 0 ? meshIp : "OPEN PANEL", false, tiles[i][2]);
            }
            RectF refresh = new RectF(left, top + dp(326), right, top + dp(397));
            button(c, refresh, "SCAN", "REFRESH NODE STATUS", "NO CHANGES • READ ONLY", true, "REFRESH");
        }

        void drawDisk(Canvas c) {
            float left = dp(16), right = getWidth() - dp(16);
            type(17, mint, true); c.drawText("LOLILE // " + trimText(diskPath, 27), left, dp(88), paint);
            type(7, soft, false); c.drawText("TAILSCALE • ED25519 SFTP • " + diskState, left, dp(107), paint);
            float gap = dp(8), half = (right - left - gap) / 2f;
            RectF back = new RectF(left, dp(120), left + half, dp(180));
            RectF refresh = new RectF(left + half + gap, dp(120), right, dp(180));
            button(c, back, "BACK", "UP ONE LEVEL", "NATIVE BROWSER", false, "DISK_UP");
            button(c, refresh, "SFTP", "REFRESH", "PRIVATE TAILNET", true, "DISK_REFRESH");
            float y = dp(194);
            if (diskItems.isEmpty()) {
                type(9, soft, false); c.drawText(diskMessage, left, y + dp(24), paint);
            } else {
                for (int i = 0; i < diskItems.size() && i < 13; i++) {
                    RectF row = new RectF(left, y, right, y + dp(43));
                    if (i % 2 == 0) { paint.setStyle(Paint.Style.FILL); paint.setColor(panel); c.drawRect(row, paint); }
                    String item = diskItems.get(i);
                    type(9, item.startsWith("[D]") ? mint : soft, item.startsWith("[D]"));
                    c.drawText(item, left + dp(10), y + dp(27), paint); addHit(row, "DISK_ITEM:" + i); y += dp(46);
                }
            }
            type(7, ghost, false); c.drawText("Klasöre dokun → gez • dosyaya dokun → güvenli SFTP", left, getHeight() - dp(91), paint);
        }

        void refreshDiskIndex() {
            loadCachedDiskIndex();
            diskLoading = true;
            diskRequestToken = System.currentTimeMillis();
            final long request = diskRequestToken;
            diskMessage = "Refreshing over Tailnet...";
            message = "LOLILE INDEX REFRESHING"; invalidate();
            String escaped = diskPath.replace("'", "''");
            String ps = "$p='" + escaped + "';[Console]::OutputEncoding=[System.Text.Encoding]::UTF8;" +
                    "Write-Output ('[P] '+$p);Get-ChildItem -LiteralPath $p | Sort-Object @{Expression={-not $_.PSIsContainer}},Name | " +
                    "ForEach-Object { if ($_.PSIsContainer) { '[D] ' + $_.Name } else { '[F] ' + $_.Name } }";
            String encoded = Base64.encodeToString(ps.getBytes(Charset.forName("UTF-16LE")), Base64.NO_WRAP);
            String target = "/sdcard/Download/daak-lolile-list.txt";
            String temporary = target + "." + request + ".tmp";
            String command = "if ssh -o ConnectTimeout=8 lolile powershell.exe -NoProfile -EncodedCommand " + encoded +
                    " > " + temporary + " 2>/dev/null; then printf '\\n[OK] " + request + "\\n' >> " + temporary +
                    "; mv -f " + temporary + " " + target +
                    "; else printf '[ERR] " + request + "\\n' > " + temporary +
                    "; mv -f " + temporary + " " + target + "; fi";
            runTermuxRaw(command, true, null);
            handler.postDelayed(() -> pollDiskIndex(request, 0), 1000L);
        }

        void loadCachedDiskIndex() {
            readDiskIndex(0L, false);
        }

        void pollDiskIndex(final long request, final int attempt) {
            if (request != diskRequestToken || destroyed) return;
            if (readDiskIndex(request, true)) return;
            if (attempt < 14) handler.postDelayed(() -> pollDiskIndex(request, attempt + 1), 1000L);
            else {
                diskLoading = false;
                diskMessage = diskItems.isEmpty() ? "Timeout • tap REFRESH" : "Refresh timed out • showing cache";
                message = "LOLILE INDEX TIMEOUT"; invalidate();
            }
        }

        boolean readDiskIndex(long expectedToken, boolean requireComplete) {
            File file = new File("/sdcard/Download/daak-lolile-list.txt");
            final ArrayList<String> loaded = new ArrayList<String>();
            String loadedPath = null;
            boolean complete = !requireComplete;
            boolean failed = false;
            try {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String lineText;
                while ((lineText = reader.readLine()) != null) {
                    if (lineText.startsWith("[P] ")) loadedPath = lineText.substring(4).trim();
                    else if (lineText.equals("[OK] " + expectedToken)) complete = true;
                    else if (lineText.equals("[ERR] " + expectedToken)) { complete = true; failed = true; }
                    else if (lineText.trim().length() > 0) loaded.add(lineText.trim());
                }
                reader.close();
                if (loadedPath != null) diskPath = loadedPath.equalsIgnoreCase("B:") ? "B:\\" : loadedPath;
                if (!loaded.isEmpty() && (complete || !requireComplete)) {
                    diskItems.clear(); diskItems.addAll(loaded);
                }
            } catch (Exception ignored) { return false; }
            if (!complete) return false;
            diskLoading = false;
            if (failed) {
                diskMessage = diskItems.isEmpty() ? "SSH/SFTP failed • tap REFRESH" : "Refresh failed • showing cache";
                message = "LOLILE INDEX ERROR";
            } else {
                diskMessage = loaded.isEmpty() ? "Folder is empty" : loaded.size() + " items • live";
                message = "LOLILE INDEX READY";
            }
            invalidate(); return true;
        }

        void openDiskItem(int index) {
            if (index < 0 || index >= diskItems.size()) return;
            String item = diskItems.get(index);
            String name = item.length() > 4 ? item.substring(4) : "";
            if (item.startsWith("[D] ")) {
                if (!diskPath.endsWith("\\")) diskPath += "\\";
                diskPath += name;
                refreshDiskIndex();
            } else openDiskTerminal();
        }

        void diskUp() {
            String current = diskPath;
            while (current.endsWith("\\") && current.length() > 3) current = current.substring(0, current.length() - 1);
            int slash = current.lastIndexOf('\\');
            diskPath = slash < 2 ? "B:\\" : current.substring(0, slash);
            refreshDiskIndex();
        }

        void openDiskTerminal() {
            String encoded = Base64.encodeToString(diskPath.getBytes(Charset.forName("UTF-8")), Base64.NO_WRAP);
            runTermux("exec ~/.shortcuts/lolile-disk " + encoded, "LOLILE SFTP");
        }

        void drawDock(Canvas c) {
            float w = getWidth(), top = getHeight() - dp(70), left = dp(10), right = w - dp(10);
            paint.setStyle(Paint.Style.FILL); paint.setColor(Color.BLACK); c.drawRect(0, top - dp(5), w, getHeight(), paint);
            paint.setColor(line); c.drawRect(left, top - dp(1), right, top, paint);
            String[] labels = {"HOME", "APPS", "CODEX", "DISK", "HELP"};
            String[] actions = {"HOME", "APPS", "CODEX", "DISK", "HELP"};
            float cell = (right - left) / 5f;
            for (int i = 0; i < 5; i++) {
                RectF r = new RectF(left + i * cell, top, left + (i + 1) * cell, getHeight());
                boolean active = (i == 0 && mode == HOME) || (i == 1 && mode == APPS) ||
                        (i == 3 && mode == DISK_VIEW) || (i == 4 && mode == HELP);
                type(7.5f, active ? mint : soft, true); center(c, labels[i], r.centerX(), top + dp(37)); addHit(r, actions[i]);
            }
        }

        void drawDockLandscape(Canvas c) {
            float w = getWidth(), top = getHeight() - dp(50), left = dp(10), right = w - dp(10);
            paint.setStyle(Paint.Style.FILL); paint.setColor(Color.BLACK); c.drawRect(0, top - dp(4), w, getHeight(), paint);
            paint.setColor(line); c.drawRect(left, top - dp(1), right, top, paint);
            String[] labels = {"HOME", "APPS", "CODEX", "DISK", "HELP"};
            String[] actions = {"HOME", "APPS", "CODEX", "DISK", "HELP"};
            float cell = (right - left) / 5f;
            for (int i = 0; i < 5; i++) {
                RectF r = new RectF(left + i * cell, top, left + (i + 1) * cell, getHeight());
                boolean selected = (i == 0 && mode == HOME) || (i == 1 && mode == APPS) || (i == 3 && mode == DISK_VIEW) || (i == 4 && mode == HELP);
                type(7, selected ? mint : soft, true); center(c, labels[i], r.centerX(), top + dp(30)); addHit(r, actions[i]);
            }
        }

        void showSearch() {
            final EditText input = new EditText(MainActivity.this);
            input.setSingleLine(true); input.setText(query); input.setSelectAllOnFocus(true);
            AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Uygulama ara").setView(input)
                    .setPositiveButton("ARA", (d, which) -> { query = input.getText().toString(); applyFilter(); })
                    .setNegativeButton("TEMİZLE", (d, which) -> { query = ""; applyFilter(); }).create();
            dialog.setOnShowListener(d -> {
                input.requestFocus();
                dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            });
            dialog.show();
        }

        void showPinnedManager() {
            final String[] slots = new String[4];
            for (int i = 0; i < 4; i++) slots[i] = (i + 1) + " • " + pinnedApp(i).label;
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("DAAK NODE // PINNED APPS")
                    .setMessage("Değiştirmek istediğin yuvayı seç.")
                    .setItems(slots, (dialog, which) -> choosePinnedApp(which))
                    .setNegativeButton("KAPAT", null).show();
        }

        void choosePinnedApp(final int slot) {
            final String[] labels = new String[allApps.size()];
            for (int i = 0; i < allApps.size(); i++) labels[i] = allApps.get(i).label;
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("PIN " + (slot + 1) + " // APP SELECT")
                    .setItems(labels, (dialog, which) -> {
                        getSharedPreferences(NodeStore.PREFS, 0).edit()
                                .putString("pinned_" + slot, allApps.get(which).packageName).apply();
                        message = "PIN " + (slot + 1) + " // " + allApps.get(which).label.toUpperCase(Locale.getDefault());
                        invalidate();
                    })
                    .setNegativeButton("İPTAL", null).show();
        }

        void showWhatsAppPanel() {
            List<String> rows = NodeStore.recentWhatsAppTasks(MainActivity.this, 8);
            StringBuilder body = new StringBuilder(NodeStore.whatsAppAutomationEnabled(MainActivity.this)
                    ? "AUTO TASKS // ON\n\n" : "AUTO TASKS // OFF\n\n");
            if (rows.isEmpty()) body.append("Henüz görev niteliğinde WhatsApp bildirimi yok.");
            else for (String row : rows) body.append("• ").append(row).append("\n\n");
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("WHATSAPP → daakREMEMBER")
                    .setMessage(body.toString())
                    .setPositiveButton("WHATSAPP", (d, which) -> launchPackage("com.whatsapp"))
                    .setNeutralButton("AYAR", (d, which) -> showWhatsAppSettings())
                    .setNegativeButton("KAPAT", null).show();
        }

        void showWhatsAppSettings() {
            SharedPreferences prefs = getSharedPreferences(NodeStore.PREFS, 0);
            final EditText input = new EditText(MainActivity.this);
            input.setSingleLine(false); input.setMinLines(2);
            input.setHint("Yok sayılacak kişi/grup adları, virgülle");
            input.setText(prefs.getString("ignored_whatsapp_senders", ""));
            boolean enabled = NodeStore.whatsAppAutomationEnabled(MainActivity.this);
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("AUTO TASKS // " + (enabled ? "ON" : "OFF"))
                    .setMessage("Yalnız görev belirten bildirimler alınır; mesaj gönderilmez.")
                    .setView(input)
                    .setPositiveButton("KAYDET", (d, which) -> prefs.edit()
                            .putString("ignored_whatsapp_senders", input.getText().toString()).apply())
                    .setNeutralButton(enabled ? "KAPAT" : "AÇ", (d, which) -> prefs.edit()
                            .putBoolean("whatsapp_tasks_enabled", !enabled).apply())
                    .setNegativeButton("İPTAL", null).show();
        }

        void showDictationResult(String result) {
            final EditText input = new EditText(MainActivity.this);
            input.setSingleLine(false); input.setMinLines(3); input.setText(result); input.setSelection(result.length());
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("DAAK INBOX // TRANSCRIPT")
                    .setView(input)
                    .setPositiveButton("CODEX İLE AYIR", (d, which) -> routeInboxWithCodex(input.getText().toString()))
                    .setNeutralButton("HEDEFİ BEN SEÇEYİM", (d, which) -> showInboxDecision(
                            input.getText().toString(), "Dikte notu", "manual", "Codex kullanılmadı."))
                    .setNegativeButton("İPTAL", null).show();
        }

        void routeInboxWithCodex(String rawText) {
            final String text = rawText.trim();
            if (text.length() == 0) return;
            if (checkSelfPermission("com.termux.permission.RUN_COMMAND") != PackageManager.PERMISSION_GRANTED) {
                ensureTermuxPermission(); return;
            }
            final long token = System.currentTimeMillis();
            String encoded = Base64.encodeToString(text.getBytes(Charset.forName("UTF-8")), Base64.NO_WRAP);
            message = "DAAK INBOX // CODEX CLASSIFYING"; invalidate();
            runTermuxRaw("exec ~/.shortcuts/daak-inbox " + encoded + " " + token, true, null);
            handler.postDelayed(() -> pollInboxResult(token, text, 0), 1500L);
            Toast.makeText(MainActivity.this, "Codex CLI düşünüyor • sonuç otomatik açılacak", Toast.LENGTH_LONG).show();
        }

        void pollInboxResult(final long token, final String original, final int attempt) {
            File file = new File("/sdcard/Download/daak-inbox-" + token + ".json");
            if (file.isFile() && file.length() > 0) {
                try {
                    BufferedReader reader = new BufferedReader(new FileReader(file));
                    StringBuilder raw = new StringBuilder(); String lineText;
                    while ((lineText = reader.readLine()) != null) raw.append(lineText);
                    reader.close(); file.delete();
                    JSONObject result = new JSONObject(raw.toString());
                    if (result.has("error")) throw new IllegalStateException(result.optString("error"));
                    String cleaned = result.optString("cleaned_text", original).trim();
                    String title = result.optString("title", "Dikte notu").trim();
                    String destination = result.optString("suggested_destination", "remember");
                    String reason = result.optString("reason", "");
                    message = "DAAK INBOX // READY"; invalidate();
                    showInboxDecision(cleaned.length() == 0 ? original : cleaned, title, destination, reason);
                    return;
                } catch (Exception error) {
                    message = "CODEX OFFLINE // MANUAL ROUTE"; invalidate();
                    showInboxDecision(original, "Dikte notu", "manual", "Codex sonucu okunamadı; hedefi sen seç.");
                    return;
                }
            }
            if (attempt < 120) handler.postDelayed(() -> pollInboxResult(token, original, attempt + 1), 1500L);
            else {
                message = "CODEX TIMEOUT // MANUAL ROUTE"; invalidate();
                showInboxDecision(original, "Dikte notu", "manual", "Mac veya Codex CLI zaman aşımına uğradı.");
            }
        }

        void showInboxDecision(final String text, final String title, String suggested, String reason) {
            String[] keys = {"remember", "obsidian", "both", "codex_only"};
            String[] labels = {"daakREMEMBER → görev", "Obsidian → kalıcı not", "İkisine de yaz", "Codex CLI oturumu aç"};
            for (int i = 0; i < keys.length; i++) if (keys[i].equals(suggested)) labels[i] = "★ " + labels[i];
            final String[] options = labels;
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("DAAK INBOX // " + trimText(title, 34))
                    .setMessage(text + (reason.length() == 0 ? "" : "\n\nCodex önerisi: " + reason) +
                            "\n\nHiçbir yere sen seçmeden yazılmadı.")
                    .setItems(options, (dialog, which) -> {
                        if (which == 0) addRememberNote(text);
                        else if (which == 1) addObsidianCapture(title, text);
                        else if (which == 2) { addRememberNote(text); addObsidianCapture(title, text); }
                        else openCodexWithPrompt(text);
                    })
                    .setNegativeButton("İPTAL", null).show();
        }

        void addObsidianCapture(String title, String text) {
            String title64 = Base64.encodeToString(title.getBytes(Charset.forName("UTF-8")), Base64.NO_WRAP);
            String text64 = Base64.encodeToString(text.getBytes(Charset.forName("UTF-8")), Base64.NO_WRAP);
            runTermuxRaw("exec ~/.shortcuts/obsidian-capture " + title64 + " " + text64, true, null);
            message = "OBSIDIAN // INBOX SAVED"; invalidate();
        }

        void openCodexWithPrompt(String text) {
            String encoded = Base64.encodeToString(text.getBytes(Charset.forName("UTF-8")), Base64.NO_WRAP);
            guarded(() -> runTermux("exec ~/.shortcuts/daak-codex " + encoded, "CODEX CLI"));
        }

        void launchLolileHub() {
            try {
                String host = nodeConfig("lolile_host", "lolile");
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://" + host + ":17657/")));
            } catch (RuntimeException error) { toast("daakLOLILE paneli açılamadı"); }
        }

        void showMailPanel() {
            List<String> rows = NodeStore.recentMail(MainActivity.this, System.currentTimeMillis() - 24L * 60L * 60L * 1000L, 8);
            StringBuilder body = new StringBuilder("READ ONLY // Son 24 saat\n\n");
            if (rows.isEmpty()) body.append("Yeni mailin yok, rahat ol.\n\nThunderbird hesabını ve DAAK NODE bildirim erişimini bağlaman gerekebilir.");
            else for (String row : rows) body.append("• ").append(row).append("\n\n");
            AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                    .setTitle("DAAK NODE // MAIL")
                    .setMessage(body.toString())
                    .setPositiveButton("THUNDERBIRD", (d, which) -> launchPackage("net.thunderbird.android"))
                    .setNeutralButton("FİLTRE", (d, which) -> showMailFilters())
                    .setNegativeButton("KAPAT", null).create();
            dialog.setOnDismissListener(d -> {
                handler.postDelayed(() -> {
                    NodeStore.clearSensitiveCache(MainActivity.this);
                    mailLine = "Sensitive cache cleared"; invalidate();
                }, 10L * 60L * 1000L);
                hideSystemBars();
            });
            dialog.show();
        }

        void showMailFilters() {
            SharedPreferences prefs = getSharedPreferences(NodeStore.PREFS, 0);
            final EditText input = new EditText(MainActivity.this);
            input.setText(prefs.getString("ignored_senders", "spam,junk,newsletter,unsubscribe,no-reply,noreply"));
            input.setSingleLine(false); input.setMinLines(3);
            new AlertDialog.Builder(MainActivity.this).setTitle("Gizlenecek gönderen/kelimeler")
                    .setMessage("Virgülle ayır. Eşleşen mail DAAK özetine alınmaz.")
                    .setView(input)
                    .setPositiveButton("KAYDET", (d, which) -> prefs.edit().putString("ignored_senders", input.getText().toString()).apply())
                    .setNegativeButton("İPTAL", null).show();
        }

        void showWeatherSetup() {
            SharedPreferences prefs = getSharedPreferences(NodeStore.PREFS, 0);
            final EditText input = new EditText(MainActivity.this);
            input.setSingleLine(true); input.setHint("Örn. Madrid"); input.setText(prefs.getString("weather_city", ""));
            new AlertDialog.Builder(MainActivity.this).setTitle("Hava durumu şehri")
                    .setMessage("Şehir cihazda yerel tutulur; kesin konum takibi gerekmez.")
                    .setView(input)
                    .setPositiveButton("KAYDET", (d, which) -> {
                        String city = input.getText().toString().trim();
                        prefs.edit().putString("weather_city", city).apply(); lastWeatherRefresh = 0; refreshWeather(true);
                    })
                    .setNeutralButton("GPS", (d, which) -> {
                        prefs.edit().remove("weather_city").apply(); lastWeatherRefresh = 0; refreshWeather(true);
                    })
                    .setNegativeButton("İPTAL", null).show();
        }

        void showRememberPanel() {
            refreshRemember();
            StringBuilder body = new StringBuilder("TAILNET ONLY // ").append(rememberOpenCount).append(" açık not\n\n");
            if (rememberItems.isEmpty()) body.append("Henüz okunabilir not yok veya Mac çevrimdışı.");
            else for (int i = 0; i < rememberItems.size() && i < 8; i++) body.append(rememberItems.get(i)).append("\n\n");
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("daakREMEMBER")
                    .setMessage(body.toString())
                    .setPositiveButton("YENİ NOT", (d, which) -> showRememberCapture())
                    .setNeutralButton("YENİLE", (d, which) -> refreshRemember())
                    .setNegativeButton("KAPAT", null).show();
        }

        void showRememberCapture() {
            final EditText input = new EditText(MainActivity.this);
            input.setSingleLine(false); input.setMinLines(3); input.setHint("Aklına geleni yakala...");
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("REMEMBER // QUICK CAPTURE")
                    .setView(input)
                    .setPositiveButton("MAC'E SENKRONLA", (d, which) -> addRememberNote(input.getText().toString()))
                    .setNegativeButton("İPTAL", null).show();
        }

        void launchObsidian() {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("obsidian://open?vault=DAAK-Vault&file=daakREMEMBER.md"));
                intent.setPackage("md.obsidian"); startActivity(intent);
            } catch (RuntimeException error) { launchPackage("md.obsidian"); }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX() - burnX, y = event.getY() - burnY;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                downX = x; downY = y; lastY = y; moved = false; return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                if (mode == APPS) {
                    float delta = lastY - y; if (Math.abs(y - downY) > dp(5)) moved = true;
                    float max;
                    if (getWidth() > getHeight()) max = Math.max(0, ((shownApps.size() + 1) / 2f) * dp(42) - (getHeight() - dp(172)));
                    else max = Math.max(0, shownApps.size() * dp(49) - (getHeight() - dp(260)));
                    drawerScroll = Math.max(0, Math.min(max, drawerScroll + delta));
                    lastY = y; invalidate();
                }
                return true;
            }
            if (event.getAction() != MotionEvent.ACTION_UP) return true;
            if (downX > getWidth() - dp(24) && downX - x > dp(65)) { showMode(CONTROL); return true; }
            if (downY < dp(58) && y - downY > dp(55)) { showMode(CONTROL); return true; }
            if (moved) return true;
            softHaptic();
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit hit = hits.get(i);
                if (hit.rect.contains(x, y)) { handle(hit); return true; }
            }
            return true;
        }

        void handle(Hit hit) {
            if (hit.app != null) { launchPackage(hit.app.packageName); return; }
            String a = hit.action;
            if (a.equals("HOME")) showMode(HOME);
            else if (a.equals("APPS")) showMode(APPS);
            else if (a.equals("HELP")) showMode(HELP);
            else if (a.equals("CONTROL")) showMode(CONTROL);
            else if (a.equals("REMOTE")) showMode(REMOTE);
            else if (a.equals("SEARCH")) showSearch();
            else if (a.equals("VAULT")) authenticate();
            else if (a.equals("REFRESH")) { message = "STATUS REFRESHING"; refreshStatus(); }
            else if (a.equals("MAIL")) showMailPanel();
            else if (a.equals("REMEMBER")) showRememberPanel();
            else if (a.equals("OBSIDIAN")) launchObsidian();
            else if (a.equals("WEATHER")) showWeatherSetup();
            else if (a.equals("PINS")) showPinnedManager();
            else if (a.equals("WHATSAPP")) showWhatsAppPanel();
            else if (a.equals("LOLILE_HUB")) guarded(() -> launchLolileHub());
            else if (a.equals("DICTATE")) startDictation();
            else if (a.equals("CHECK_UPDATE")) guarded(() -> maybeCheckUpdate(true));
            else if (a.equals("REMOTE_CONFIG")) showRemoteConfig();
            else if (a.startsWith("REMOTE_CONNECT:")) guarded(() -> connectRemote(Integer.parseInt(a.substring(15))));
            else if (a.equals("CALENDAR")) {
                if (checkSelfPermission("android.permission.READ_CALENDAR") != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{"android.permission.READ_CALENDAR"}, CALENDAR_PERMISSION_REQUEST);
                else launchPackage("org.fossify.calendar");
            }
            else if (a.equals("DISK_REFRESH")) refreshDiskIndex();
            else if (a.equals("DISK_UP")) diskUp();
            else if (a.startsWith("DISK_ITEM:")) openDiskItem(Integer.parseInt(a.substring(10)));
            else if (a.equals("DISK_TERM")) openDiskTerminal();
            else if (a.equals("CODEX")) guarded(() -> runTermux("exec ~/.shortcuts/codex", "CODEX CLI"));
            else if (a.equals("MAC")) guarded(() -> runTermux("exec ~/.shortcuts/mac", "MAC SSH"));
            else if (a.equals("LOCAL")) guarded(() -> runTermux("exec ~/.shortcuts/debian", "DEBIAN"));
            else if (a.equals("DISK")) guarded(() -> { showMode(DISK_VIEW); loadCachedDiskIndex(); refreshDiskIndex(); });
            else if (a.startsWith("PKG:")) launchPackage(a.substring(4));
            else if (a.equals("SET:SECURITY")) openSettings(Settings.ACTION_SECURITY_SETTINGS);
            else if (a.equals("SET:SYSTEM")) openSettings(Settings.ACTION_SETTINGS);
            else if (a.equals("SET:WIFI")) openSettings(Settings.ACTION_WIFI_SETTINGS);
            else if (a.equals("SET:BT")) openSettings(Settings.ACTION_BLUETOOTH_SETTINGS);
            else if (a.equals("SET:DISPLAY")) openSettings(Settings.ACTION_DISPLAY_SETTINGS);
            else if (a.equals("SET:INPUT")) openSettings(Settings.ACTION_INPUT_METHOD_SETTINGS);
            else if (a.equals("SET:NOTIFY")) openSettings("android.settings.NOTIFICATION_SETTINGS");
            else if (a.equals("SET:MAILACCESS")) openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        }
    }
}
