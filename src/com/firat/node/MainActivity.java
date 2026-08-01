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
import android.provider.Settings;
import android.provider.CalendarContract;
import android.net.Uri;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Toast;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int TERMUX_PERMISSION_REQUEST = 73;
    private static final int CALENDAR_PERMISSION_REQUEST = 74;
    private static final int LOCATION_PERMISSION_REQUEST = 75;
    private NodeView nodeView;
    private Runnable pendingProtectedAction;
    private long vaultUnlockedUntil;

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
        if (nodeView != null) {
            nodeView.reloadApps();
            nodeView.refreshStatus();
        }
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
        if (nodeView != null && nodeView.mode != NodeView.HOME) nodeView.showMode(NodeView.HOME);
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
            BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                    .setTitle("FIRAT NODE // CONTROL VAULT")
                    .setSubtitle("Parmak izi, iris veya cihaz kilidi")
                    .setDeviceCredentialAllowed(true).build();
            prompt.authenticate(new CancellationSignal(), getMainExecutor(),
                    new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    vaultUnlockedUntil = System.currentTimeMillis() + 90_000L;
                    nodeView.vaultUnlocked = true;
                    nodeView.message = "VAULT OPEN // 90 SEC";
                    Runnable action = pendingProtectedAction;
                    pendingProtectedAction = null;
                    nodeView.invalidate();
                    if (action != null) action.run();
                }
                @Override public void onAuthenticationError(int code, CharSequence error) {
                    pendingProtectedAction = null;
                    nodeView.message = "VAULT LOCKED // SET PIN OR BIOMETRICS";
                    nodeView.invalidate();
                }
            });
        } catch (Exception error) {
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
            launchPackage("com.termux");
        }
    }

    private void launchPackage(String packageName) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            toast("Uygulama bulunamadı: " + packageName);
            return;
        }
        try { startActivity(intent); }
        catch (ActivityNotFoundException error) { toast("Uygulama açılamadı"); }
    }

    private void openSettings(String action) {
        try { startActivity(new Intent(action)); }
        catch (ActivityNotFoundException error) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    private String nodeConfig(String key, String fallback) {
        File file = new File("/sdcard/Download/firat-node/config.properties");
        Properties props = new Properties();
        try {
            FileReader reader = new FileReader(file); props.load(reader); reader.close();
            return props.getProperty(key, fallback).trim();
        } catch (Exception ignored) { return fallback; }
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
        static final int HOME = 0, APPS = 1, HELP = 2, CONTROL = 3, DISK_VIEW = 4;
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
        int mode = HOME;
        String query = "";
        String meshIp = "CHECKING", macState = "CHECKING", diskState = "CHECKING";
        String sshState = "CHECKING", message = "FIRAT NODE V2 READY";
        String weather = "TAP TO ENABLE", agendaOne = "Calendar permission required", agendaTwo = "";
        String mailLine = "Connect Thunderbird + notification access";
        String weatherCity = "";
        boolean rooted, vaultUnlocked;
        float drawerScroll, downX, downY, lastY;
        boolean moved;
        float burnX, burnY;
        long lastWeatherRefresh, nextDiskRetry;
        int diskRetryStep;

        NodeView(Context context) {
            super(context);
            setBackgroundColor(Color.BLACK);
            reloadApps();
            refreshStatus();
            refreshCalendar();
            refreshWeather(false);
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    refreshStatus();
                    invalidate();
                    handler.postDelayed(this, 60_000L);
                }
            }, 60_000L);
        }

        void reloadApps() {
            allApps.clear();
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> infos = getPackageManager().queryIntentActivities(intent, 0);
            for (ResolveInfo info : infos) {
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
                handler.post(() -> { agendaOne = one; agendaTwo = two; invalidate(); });
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
            Location location = null;
            try {
                location = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (location == null) location = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            } catch (SecurityException ignored) { }
            if (location == null) {
                weather = "LOCATING..."; invalidate();
                try {
                    manager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, new LocationListener() {
                        @Override public void onLocationChanged(Location fresh) { fetchWeather(fresh); }
                        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
                        @Override public void onProviderEnabled(String provider) { }
                        @Override public void onProviderDisabled(String provider) { weather = "LOCATION DISABLED"; invalidate(); }
                    }, Looper.getMainLooper());
                } catch (Exception error) { weather = "LOCATION DISABLED"; invalidate(); }
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
                    lastWeatherRefresh = System.currentTimeMillis(); handler.post(() -> { weather = value; invalidate(); });
                } catch (Exception error) { handler.post(() -> { weather = "WEATHER OFFLINE"; invalidate(); }); }
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
                } catch (Exception error) { handler.post(() -> { weather = "CITY NOT FOUND"; invalidate(); }); }
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
            try { socket.connect(new InetSocketAddress(host, port), 900); return true; }
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
            if (mode == HOME) drawHome(c);
            else if (mode == APPS) drawApps(c);
            else if (mode == HELP) drawHelp(c);
            else if (mode == CONTROL) drawControl(c);
            else drawDisk(c);
            drawDock(c);
            c.restore();
        }

        void drawTopBar(Canvas c) {
            float w = getWidth();
            type(8, mintDim, true); c.drawText("FIRAT//NODE", dp(16), dp(25), paint);
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
            button(c, new RectF(left, dp(285), left + half, dp(341)), "SSH", "MAC SHELL", macState, false, "MAC");
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
            String[] names = {"AUXIO", "CAMERA", "FILM", "TOOLBOX"};
            String[] pkgs = {"org.oxycblt.auxio", "com.sec.android.app.camera",
                    "io.github.yahiaangelo.filmsimulator.android", "ru.tech.imageresizershrinker"};
            float cardW = (right - left - gap * 3f) / 4f;
            for (int i = 0; i < 4; i++) {
                RectF r = new RectF(left + i * (cardW + gap), dp(545), left + i * (cardW + gap) + cardW, dp(594));
                box(c, r, 12, panel, line); type(6.5f, soft, true); center(c, names[i], r.centerX(), dp(575));
                addHit(r, "PKG:" + pkgs[i]);
            }
            type(6.5f, ghost, false); c.drawText("SWIPE DOWN → NODE CONTROL", left, dp(613), paint);
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
                    {"VPN", "TAILSCALE", "PKG:com.tailscale.ipn"}, {"NET", "WI-FI", "SET:WIFI"},
                    {"RADIO", "BLUETOOTH", "SET:BT"}, {"UI", "DISPLAY", "SET:DISPLAY"},
                    {"SEC", "BIOMETRICS", "SET:SECURITY"}, {"ALERT", "NOTIFICATIONS", "SET:NOTIFY"},
                    {"MAIL", "MAIL ACCESS", "SET:MAILACCESS"}, {"ROOT", "MAGISK", "PKG:com.topjohnwu.magisk"}
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
            type(17, mint, true); c.drawText("LOLILE // B:\\", left, dp(88), paint);
            type(7, soft, false); c.drawText("TAILSCALE • ED25519 SFTP • " + diskState, left, dp(107), paint);
            RectF refresh = new RectF(left, dp(120), right, dp(166));
            button(c, refresh, "SFTP", "REFRESH DISK INDEX", "LOLILE • PRIVATE TAILNET", true, "DISK_REFRESH");
            float y = dp(180);
            if (diskItems.isEmpty()) {
                type(9, soft, false); c.drawText("Disk index loading...", left, y + dp(24), paint);
            } else {
                for (int i = 0; i < diskItems.size() && i < 13; i++) {
                    RectF row = new RectF(left, y, right, y + dp(43));
                    if (i % 2 == 0) { paint.setStyle(Paint.Style.FILL); paint.setColor(panel); c.drawRect(row, paint); }
                    String item = diskItems.get(i);
                    type(9, item.startsWith("[D]") ? mint : soft, item.startsWith("[D]"));
                    c.drawText(item, left + dp(10), y + dp(27), paint); addHit(row, "DISK_TERM"); y += dp(46);
                }
            }
            type(7, ghost, false); c.drawText("Dosya açma/aktarma: terminal SFTP oturumunu kullan", left, getHeight() - dp(91), paint);
        }

        void refreshDiskIndex() {
            diskItems.clear(); message = "LOLILE INDEX REFRESHING"; invalidate();
            runTermuxRaw("exec ~/.shortcuts/lolile-list", true, null);
            postDelayed(new Runnable() {
                @Override public void run() {
                    File file = new File("/sdcard/Download/firat-lolile-list.txt");
                    try {
                        BufferedReader reader = new BufferedReader(new FileReader(file));
                        String lineText;
                        while ((lineText = reader.readLine()) != null) if (lineText.trim().length() > 0) diskItems.add(lineText);
                        reader.close();
                        message = "LOLILE INDEX READY";
                    } catch (Exception error) { message = "LOLILE INDEX ERROR"; }
                    invalidate();
                }
            }, 4500L);
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

        void showMailPanel() {
            List<String> rows = NodeStore.recentMail(MainActivity.this, System.currentTimeMillis() - 24L * 60L * 60L * 1000L, 8);
            StringBuilder body = new StringBuilder("READ ONLY // Son 24 saat\n\n");
            if (rows.isEmpty()) body.append("Yeni mailin yok, rahat ol.\n\nThunderbird hesabını ve FIRAT NODE bildirim erişimini bağlaman gerekebilir.");
            else for (String row : rows) body.append("• ").append(row).append("\n\n");
            AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                    .setTitle("FIRAT NODE // MAIL")
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
                    .setMessage("Virgülle ayır. Eşleşen mail FIRAT özetine alınmaz.")
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

        @Override public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX() - burnX, y = event.getY() - burnY;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                downX = x; downY = y; lastY = y; moved = false; return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                if (mode == APPS) {
                    float delta = lastY - y; if (Math.abs(y - downY) > dp(5)) moved = true;
                    float max = Math.max(0, shownApps.size() * dp(49) - (getHeight() - dp(260)));
                    drawerScroll = Math.max(0, Math.min(max, drawerScroll + delta));
                    lastY = y; invalidate();
                }
                return true;
            }
            if (event.getAction() != MotionEvent.ACTION_UP) return true;
            if (downY < dp(58) && y - downY > dp(55)) { showMode(CONTROL); return true; }
            if (moved) return true;
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
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
            else if (a.equals("SEARCH")) showSearch();
            else if (a.equals("VAULT")) authenticate();
            else if (a.equals("REFRESH")) { message = "STATUS REFRESHING"; refreshStatus(); }
            else if (a.equals("MAIL")) showMailPanel();
            else if (a.equals("WEATHER")) showWeatherSetup();
            else if (a.equals("CALENDAR")) {
                if (checkSelfPermission("android.permission.READ_CALENDAR") != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{"android.permission.READ_CALENDAR"}, CALENDAR_PERMISSION_REQUEST);
                else launchPackage("org.fossify.calendar");
            }
            else if (a.equals("DISK_REFRESH")) refreshDiskIndex();
            else if (a.equals("DISK_TERM")) runTermux("exec ~/.shortcuts/lolile-disk", "LOLILE SFTP");
            else if (a.equals("CODEX")) guarded(() -> runTermux("exec ~/.shortcuts/codex", "CODEX CLI"));
            else if (a.equals("MAC")) guarded(() -> runTermux("exec ~/.shortcuts/mac", "MAC SSH"));
            else if (a.equals("LOCAL")) guarded(() -> runTermux("exec ~/.shortcuts/debian", "DEBIAN"));
            else if (a.equals("DISK")) guarded(() -> { showMode(DISK_VIEW); refreshDiskIndex(); });
            else if (a.startsWith("PKG:")) launchPackage(a.substring(4));
            else if (a.equals("SET:SECURITY")) openSettings(Settings.ACTION_SECURITY_SETTINGS);
            else if (a.equals("SET:SYSTEM")) openSettings(Settings.ACTION_SETTINGS);
            else if (a.equals("SET:WIFI")) openSettings(Settings.ACTION_WIFI_SETTINGS);
            else if (a.equals("SET:BT")) openSettings(Settings.ACTION_BLUETOOTH_SETTINGS);
            else if (a.equals("SET:DISPLAY")) openSettings(Settings.ACTION_DISPLAY_SETTINGS);
            else if (a.equals("SET:NOTIFY")) openSettings("android.settings.NOTIFICATION_SETTINGS");
            else if (a.equals("SET:MAILACCESS")) openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        }
    }
}
