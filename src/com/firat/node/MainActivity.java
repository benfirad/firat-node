package com.firat.node;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.pm.ApplicationInfo;
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
import android.location.Address;
import android.location.Geocoder;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.provider.CalendarContract;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.view.animation.PathInterpolator;
import android.webkit.MimeTypeMap;
import android.speech.RecognizerIntent;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.OverScroller;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileOutputStream;
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
import java.util.Calendar;
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
    private static final String BUILD_VERSION = "6.9.4";
    private static final String BOOK_READER_PACKAGE = "ua.acclorite.book_story";
    private static final int TERMUX_PERMISSION_REQUEST = 73;
    private static final int CALENDAR_PERMISSION_REQUEST = 74;
    private static final int LOCATION_PERMISSION_REQUEST = 75;
    private static final int DICTATION_REQUEST = 76;
    private static final int AIRPLAY_AUDIO_REQUEST = 77;
    private static final int STORAGE_PERMISSION_REQUEST = 78;
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
        removeLegacyRemoteState();
        NodeStore.migrateLoudSound(this);
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

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (nodeView != null && Intent.ACTION_MAIN.equals(intent.getAction())) {
            pendingProtectedAction = null;
            if (biometricCancellation != null) {
                biometricCancellation.cancel();
                biometricCancellation = null;
            }
            nodeView.showMode(NodeView.HOME);
        }
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

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        hideSystemBars();
        if (nodeView != null) nodeView.playRotationTransition();
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

    private void removeLegacyRemoteState() {
        SharedPreferences prefs = getSharedPreferences(NodeStore.PREFS, 0);
        if (prefs.getBoolean("legacy_remote_state_removed_v1", false)) return;
        prefs.edit()
                .remove("remote_lolile").remove("remote_mac")
                .remove("remote_bedirhan_mac").remove("remote_bedirhan_windows")
                .putBoolean("legacy_remote_state_removed_v1", true).apply();
    }

    @Override public void onBackPressed() {
        if (nodeView != null && nodeView.mode == NodeView.DISK_VIEW && !NodeView.DISK_ROOT.equals(nodeView.diskPath)) nodeView.diskUp();
        else if (nodeView != null && nodeView.mode != NodeView.HOME) nodeView.showMode(NodeView.HOME);
        else if (nodeView == null) super.onBackPressed();
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
        if (requestCode == AIRPLAY_AUDIO_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                queueAirplayAudio(data.getData());
            }
            return;
        }
        if (requestCode != DICTATION_REQUEST || resultCode != RESULT_OK || data == null || nodeView == null) return;
        ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (results != null && !results.isEmpty()) nodeView.showDictationResult(results.get(0));
    }

    private void startAirplayPicker() {
        if (getPackageManager().getLaunchIntentForPackage("com.termux") == null) {
            toast("DAAK AirPlay için Termux kurulumu eksik");
            return;
        }
        if (checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE"}, STORAGE_PERMISSION_REQUEST);
            toast("Bir kez dosya erişimine izin ver, sonra AirPlay'e tekrar dokun");
            return;
        }
        if (checkSelfPermission("com.termux.permission.RUN_COMMAND") != PackageManager.PERMISSION_GRANTED) {
            ensureTermuxPermission();
            toast("Bir kez Termux komut iznine izin ver, sonra AirPlay'e tekrar dokun");
            return;
        }
        try {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            picker.setType("audio/*");
            startActivityForResult(Intent.createChooser(picker,
                    "DAAK AIRPLAY // MÜZİK SEÇ"), AIRPLAY_AUDIO_REQUEST);
        } catch (RuntimeException error) {
            toast("Ses dosyası seçici açılamadı");
        }
    }

    private void queueAirplayAudio(final Uri uri) {
        if (nodeView != null) {
            nodeView.message = "AIRPLAY // DOSYA HAZIRLANIYOR";
            nodeView.invalidate();
        }
        new Thread(() -> {
            String displayName = "müzik";
            Cursor cursor = null;
            try {
                cursor = getContentResolver().query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                    displayName = cursor.getString(0);
                }
            } catch (RuntimeException ignored) { }
            finally { if (cursor != null) cursor.close(); }

            String extension = "audio";
            int dot = displayName.lastIndexOf('.');
            if (dot >= 0 && dot + 1 < displayName.length()) {
                String candidate = displayName.substring(dot + 1).toLowerCase(Locale.US);
                if (candidate.matches("[a-z0-9]{1,5}")) extension = candidate;
            }
            File directory = new File("/sdcard/Download/DAAK-AirPlay-Queue");
            File target = new File(directory, "current." + extension);
            try {
                if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("queue directory");
                File[] old = directory.listFiles();
                if (old != null) for (File file : old) {
                    if (file.getName().startsWith("current.") && !file.equals(target)) file.delete();
                }
                InputStream input = getContentResolver().openInputStream(uri);
                if (input == null) throw new IllegalStateException("audio input");
                FileOutputStream output = new FileOutputStream(target, false);
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
                input.close(); output.flush(); output.getFD().sync(); output.close();
                final String readyName = displayName;
                runOnUiThread(() -> {
                    runTermuxRaw("exec ~/.shortcuts/daak-airplay " + target.getAbsolutePath(), true, null);
                    if (nodeView != null) {
                        nodeView.message = "AIRPLAY → MAC // " + trimLabel(readyName, 28);
                        nodeView.invalidate();
                    }
                    toast("Mac'e açık kaynak AirPlay aktarımı başladı");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (nodeView != null) {
                        nodeView.message = "AIRPLAY // DOSYA HATASI";
                        nodeView.invalidate();
                    }
                    toast("Müzik AirPlay kuyruğuna kopyalanamadı");
                });
            }
        }, "daak-airplay-queue").start();
    }

    private String trimLabel(String value, int maximum) {
        String clean = value == null ? "müzik" : value.replace('\n', ' ').trim();
        return clean.length() <= maximum ? clean : clean.substring(0, Math.max(1, maximum - 1)) + "…";
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
        if (manual && nodeView != null) { nodeView.message = "UPDATE // CHECKING"; nodeView.invalidate(); }
        NodeUpdater.check(this, new NodeUpdater.CheckCallback() {
            @Override public void onCurrent() {
                updateCheckRunning = false;
                prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply();
                if (manual) toast("DAAK NODE güncel");
                if (nodeView != null) { nodeView.message = "UPDATE // CURRENT"; nodeView.invalidate(); }
            }
            @Override public void onAvailable(final NodeUpdater.Update update) {
                updateCheckRunning = false;
                prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply();
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
        runTermuxRaw("exec ~/.shortcuts/daak-sshd", true, null);
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
        try {
            startActivity(intent);
            if (nodeView != null) nodeView.noteExternalLaunch(packageName);
        }
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

    private static final class AgendaEntry {
        final long when;
        final String text;
        AgendaEntry(long when, String text) { this.when = when; this.text = text; }
    }

    private static final class Hit {
        final RectF rect;
        final String action;
        final AppEntry app;
        Hit(RectF rect, String action) { this.rect = rect; this.action = action; this.app = null; }
        Hit(RectF rect, AppEntry app) { this.rect = rect; this.action = "APP"; this.app = app; }
    }

    private final class NodeView extends View {
        static final int HOME = 0, APPS = 1, HELP = 2, CONTROL = 3, DISK_VIEW = 4, REMOTE = 5,
                CODEX_VIEW = 6, INFO_PANEL = 7;
        static final String DISK_ROOT = "smb://lolile/kurek";
        static final float TEXT_SCALE = 1.06f;
        // Samsung never published a CSS/Pantone value for S9 Lilac Purple. This palette is
        // sampled and OLED-adjusted from Samsung's launch render: true black remains black.
        final int mint = Color.rgb(201, 167, 220);       // #C9A7DC
        final int mintDim = Color.rgb(128, 96, 154);    // #80609A
        final int panel = Color.rgb(12, 7, 15);
        final int panelHot = Color.rgb(27, 14, 34);
        final int line = Color.rgb(58, 38, 70);
        final int soft = Color.rgb(184, 168, 193);
        final int ghost = Color.rgb(91, 72, 101);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Typeface mono = Typeface.create("monospace", Typeface.NORMAL);
        final Handler handler = new Handler(Looper.getMainLooper());
        final OverScroller contentScroller;
        Ringtone previewRingtone;
        VelocityTracker velocityTracker;
        ValueAnimator pressAnimator;
        final List<Hit> hits = new ArrayList<Hit>();
        final List<AppEntry> allApps = new ArrayList<AppEntry>();
        final List<AppEntry> shownApps = new ArrayList<AppEntry>();
        final List<String> diskItems = new ArrayList<String>();
        final List<String> rememberItems = new ArrayList<String>();
        final List<String> rememberIds = new ArrayList<String>();
        final List<String> rememberTexts = new ArrayList<String>();
        final List<Boolean> rememberDone = new ArrayList<Boolean>();
        final List<AgendaEntry> holidayEntries = new ArrayList<AgendaEntry>();
        String diskPath = DISK_ROOT;
        int mode = HOME;
        String query = "";
        String meshIp = "CHECKING", macState = "CHECKING", diskState = "CHECKING";
        String sshState = "CHECKING", message = "DAAK NODE V" + BUILD_VERSION + " READY";
        String weather = "TAP TO ENABLE", weatherDetails = "", agendaOne = "Calendar permission required", agendaTwo = "";
        String mailLine = "Connect Thunderbird + notification access";
        String whatsAppLine = "Görev bildirimi bekleniyor";
        String weatherCity = "";
        String rememberLine = "SYNCING WITH MAC...";
        String mediaTitle = "Aktif oynatma yok", mediaSource = "AUXIO OFFLINE READY";
        String holidayCountry = "";
        String diskMessage = "Cached index not loaded";
        long diskRequestToken;
        boolean diskLoading;
        int rememberOpenCount;
        boolean rooted, vaultUnlocked;
        float drawerScroll, diskScroll, panelScroll, downX, downY, lastY;
        boolean moved, wakeOnly;
        RectF pressedRect;
        float pressGlow;
        String panelKind = "";
        final List<String> panelItems = new ArrayList<String>();
        long mailViewedAt;
        float burnX, burnY;
        long lastInteraction = System.currentTimeMillis();
        long lastUiTouchUpAt;
        long lastWeatherRefresh, nextDiskRetry, lastObsidianSync, lastCalendarRefresh, lastHolidayRefresh;
        int diskRetryStep;
        boolean active, destroyed, statusRefreshRunning;
        LocationManager pendingLocationManager;
        LocationListener pendingLocationListener;
        final Runnable statusTicker = new Runnable() {
            @Override public void run() {
                if (!active || destroyed) return;
                refreshStatus();
                refreshRemember();
                if (System.currentTimeMillis() - lastCalendarRefresh > 10L * 60L * 1000L) refreshCalendar();
                handler.postDelayed(this, 60_000L);
            }
        };
        final Runnable oledTicker = new Runnable() {
            @Override public void run() {
                if (!active || destroyed) return;
                invalidate();
                handler.postDelayed(this, 30_000L);
            }
        };
        String cleanupPackage;
        final Runnable appCleanup = new Runnable() {
            @Override public void run() {
                if (!active || destroyed || cleanupPackage == null) return;
                String target = cleanupPackage;
                cleanupPackage = null;
                if (!isCleanupCandidate(target)) return;
                ActivityManager manager = (ActivityManager)getSystemService(ACTIVITY_SERVICE);
                if (manager != null) manager.killBackgroundProcesses(target);
                if (isFastCleanupPackage(target)) requestFastCleanup(target);
                message = "MEMORY // " + target.toUpperCase(Locale.US) + " CLOSED";
                invalidate();
            }
        };

        NodeView(Context context) {
            super(context);
            contentScroller = new OverScroller(context);
            setBackgroundColor(Color.BLACK);
            holidayCountry = getSharedPreferences(NodeStore.PREFS, 0).getString("holiday_country", "");
            reloadApps();
            refreshCalendar();
            refreshHolidays(false);
            refreshWeather(false);
        }

        void startUpdates() {
            if (destroyed) return;
            active = true;
            handler.removeCallbacks(statusTicker);
            handler.removeCallbacks(oledTicker);
            handler.removeCallbacks(appCleanup);
            refreshStatus();
            refreshRemember();
            syncObsidian(false);
            handler.postDelayed(statusTicker, 60_000L);
            handler.postDelayed(oledTicker, 30_000L);
            if (cleanupPackage != null) handler.postDelayed(appCleanup, cleanupDelayMs(cleanupPackage));
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
            handler.removeCallbacks(oledTicker);
            handler.removeCallbacks(appCleanup);
        }

        void shutdown() {
            destroyed = true;
            active = false;
            if (previewRingtone != null) previewRingtone.stop();
            if (pressAnimator != null) pressAnimator.cancel();
            if (velocityTracker != null) { velocityTracker.recycle(); velocityTracker = null; }
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

        String pinnedAction(int index) {
            AppEntry app = pinnedApp(index);
            if (index == 0 && app.packageName.equals("org.oxycblt.auxio")) return "MUSIC";
            return "PKG:" + app.packageName;
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
            if (mode == INFO_PANEL && !panelKind.equals("") && newMode != INFO_PANEL) closeInfoPanel();
            mode = newMode;
            drawerScroll = 0;
            panelScroll = 0;
            if (!contentScroller.isFinished()) contentScroller.abortAnimation();
            invalidate();
        }

        void noteExternalLaunch(String packageName) {
            if (packageName == null || !packageName.matches("[A-Za-z0-9._]+")) return;
            cleanupPackage = packageName;
            handler.removeCallbacks(appCleanup);
        }

        boolean isCleanupCandidate(String packageName) {
            String[] keepAlive = {
                    "com.whatsapp", "com.tailscale.ipn", "net.thunderbird.android", "com.termux",
                    "com.bitchat.droid",
                    "org.futo.inputmethod.latin", "org.futo.voiceinput", "org.oxycblt.auxio",
                    "com.google.android.calendar", "org.fossify.clock", "hu.vmiklos.plees_tracker"
            };
            for (String keep : keepAlive) if (keep.equals(packageName)) return false;
            try {
                ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
                return (info.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
            } catch (PackageManager.NameNotFoundException error) { return false; }
        }

        long cleanupDelayMs(String packageName) {
            if (isFastCleanupPackage(packageName)) return 15_000L;
            return 10L * 60L * 1000L;
        }

        boolean isFastCleanupPackage(String packageName) {
            return packageName.equals("ru.tech.imageresizershrinker") ||
                    packageName.equals("me.zhanghai.android.files") ||
                    packageName.equals("com.google.android.apps.photos");
        }

        void requestFastCleanup(final String packageName) {
            if (!isFastCleanupPackage(packageName)) return;
            new Thread(() -> {
                File queue = new File(getFilesDir(), "daak-node");
                File temp = new File(queue, "cleanup.request.tmp");
                File target = new File(queue, "cleanup.request");
                try {
                    if (!queue.exists() && !queue.mkdirs()) return;
                    FileOutputStream output = new FileOutputStream(temp, false);
                    output.write(packageName.getBytes("UTF-8"));
                    output.flush(); output.getFD().sync(); output.close();
                    if (target.exists() && !target.delete()) return;
                    if (!temp.renameTo(target)) temp.delete();
                } catch (Exception ignored) { temp.delete(); }
            }, "node-app-cleaner").start();
        }

        void playRotationTransition() {
            lastInteraction = System.currentTimeMillis();
            animate().cancel();
            setAlpha(0f); setScaleX(0.965f); setScaleY(0.965f);
            post(() -> animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(280L).setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f))
                    .withLayer().start());
        }

        int oledContentAlpha() {
            long idle = System.currentTimeMillis() - lastInteraction;
            if (idle >= 10L * 60L * 1000L) return 0;
            if (idle >= 5L * 60L * 1000L) return 96;
            if (idle >= 2L * 60L * 1000L) return 184;
            return 255;
        }

        void refreshStatus() {
            if (destroyed || statusRefreshRunning) return;
            statusRefreshRunning = true;
            long phase = ((System.currentTimeMillis() / 60_000L) * 7L) % 25L;
            burnX = dp(((phase % 5L) - 2L) * 2L);
            burnY = dp(((phase / 5L) - 2L) * 2L);
            new Thread(new Runnable() {
                @Override public void run() {
                    final String ip = findMeshIp();
                    final String mac = canConnect(nodeConfig("mac_host", "mac"), 22) ? "ONLINE" : "OFFLINE";
                    final String disk = canConnect(nodeConfig("lolile_host", "lolile"), 445) ? "ONLINE" : "OFFLINE";
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
            String root = Base64.encodeToString(DISK_ROOT.getBytes(Charset.forName("UTF-8")), Base64.NO_WRAP);
            runTermuxRaw("exec ~/.shortcuts/lolile-list " + root + " 0 /sdcard/Download/daak-lolile-health.txt", true, null);
        }

        void updateMailLine() {
            List<String> rows = NodeStore.recentMail(MainActivity.this, System.currentTimeMillis() - 24L * 60L * 60L * 1000L, 1);
            mailLine = rows.isEmpty() ? "Gmail + Thunderbird hazır" : rows.get(0);
            List<String> whatsApp = NodeStore.recentWhatsAppTasks(MainActivity.this, 1);
            whatsAppLine = whatsApp.isEmpty() ? "Yeni görev yok" : whatsApp.get(0);
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
                    final ArrayList<JSONObject> active = new ArrayList<JSONObject>();
                    final ArrayList<String> activeItems = new ArrayList<String>();
                    final ArrayList<String> activeIds = new ArrayList<String>();
                    final ArrayList<String> activeTexts = new ArrayList<String>();
                    final ArrayList<Boolean> activeDone = new ArrayList<Boolean>();
                    int open = 0;
                    if (items != null) for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.optJSONObject(i);
                        if (item == null || !item.isNull("deletedAt")) continue;
                        String text = item.optString("text", "").trim();
                        if (!item.optBoolean("isDone", false)) open++;
                        if (text.length() > 0) active.add(item);
                    }
                    Collections.sort(active, (left, right) -> {
                        boolean leftDone = left.optBoolean("isDone", false);
                        boolean rightDone = right.optBoolean("isDone", false);
                        if (leftDone != rightDone) return leftDone ? 1 : -1;
                        return Double.compare(right.optDouble("updatedAt", 0), left.optDouble("updatedAt", 0));
                    });
                    for (int i = 0; i < active.size() && i < 50; i++) {
                        JSONObject item = active.get(i);
                        String text = item.optString("text", "").trim();
                        boolean done = item.optBoolean("isDone", false);
                        activeItems.add((done ? "✓ " : "• ") + text);
                        activeIds.add(item.optString("id", ""));
                        activeTexts.add(text);
                        activeDone.add(done);
                    }
                    final int openFinal = open;
                    handler.post(() -> {
                        if (destroyed) return;
                        rememberItems.clear(); rememberItems.addAll(activeItems); rememberOpenCount = openFinal;
                        rememberIds.clear(); rememberIds.addAll(activeIds);
                        rememberTexts.clear(); rememberTexts.addAll(activeTexts);
                        rememberDone.clear(); rememberDone.addAll(activeDone);
                        rememberLine = activeItems.isEmpty() ? "NOT YOK • TAP TO CAPTURE" : openFinal + " OPEN • " + activeItems.get(0).substring(2);
                        if (mode == INFO_PANEL && panelKind.equals("REMEMBER")) {
                            panelItems.clear(); panelItems.addAll(activeItems);
                            panelScroll = Math.min(panelScroll, maxActiveScroll());
                        }
                        invalidate();
                    });
                } catch (Exception error) {
                    handler.post(() -> { if (!destroyed) { rememberLine = "MAC SYNC OFFLINE"; invalidate(); } });
                }
            }, "node-remember-read").start();
        }

        void showRememberItemMenu(final int index) {
            if (index < 0 || index >= rememberIds.size() || index >= rememberTexts.size()) return;
            final String id = rememberIds.get(index);
            final String text = rememberTexts.get(index);
            final boolean done = rememberDone.get(index);
            final String[] options = {done ? "YENİDEN AÇ" : "TAMAMLANDI", "DÜZENLE", "KOPYALA", "SİL"};
            AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                    .setTitle("REMEMBER // " + trimText(text, 38))
                    .setItems(options, (d, which) -> {
                        if (which == 0) mutateRememberNote(id, "done", text, !done, false);
                        else if (which == 1) showRememberEdit(id, text, done);
                        else if (which == 2) copyRememberText(text);
                        else confirmRememberDelete(id, text, done);
                    })
                    .setNegativeButton("KAPAT", null).create();
            showDaakDialog(dialog);
        }

        void showRememberEdit(final String id, String text, final boolean done) {
            final EditText input = new EditText(MainActivity.this);
            input.setSingleLine(false); input.setMinLines(3); input.setText(text); input.setSelection(text.length());
            styleInput(input);
            AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                    .setTitle("REMEMBER // DÜZENLE")
                    .setView(input)
                    .setPositiveButton("KAYDET", (d, which) -> {
                        String edited = input.getText().toString().trim();
                        if (edited.length() == 0) toast("Boş not kaydedilmedi");
                        else mutateRememberNote(id, "edit", edited, done, false);
                    })
                    .setNegativeButton("İPTAL", null).create();
            showDaakDialog(dialog);
        }

        void copyRememberText(String text) {
            ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("daakREMEMBER", text));
            toast("Not panoya kopyalandı");
        }

        void confirmRememberDelete(final String id, final String text, final boolean done) {
            AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                    .setTitle("REMEMBER // SİL")
                    .setMessage("Bu madde tüm daakREMEMBER cihazlarından silinsin mi?\n\n" + text)
                    .setPositiveButton("SİL", (d, which) -> mutateRememberNote(id, "delete", text, done, true))
                    .setNegativeButton("İPTAL", null).create();
            showDaakDialog(dialog);
        }

        void mutateRememberNote(final String id, final String operation, final String text,
                                final boolean done, final boolean offerUndo) {
            message = "REMEMBER // " + operation.toUpperCase(Locale.US); invalidate();
            new Thread(() -> {
                try {
                    JSONObject snapshot = new JSONObject(new String(rememberRequest("GET", "/snapshot", null), "UTF-8"));
                    JSONArray items = snapshot.optJSONArray("items");
                    if (items == null) throw new IllegalStateException("empty snapshot");
                    JSONObject selected = null;
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject candidate = items.optJSONObject(i);
                        if (candidate != null && id.equalsIgnoreCase(candidate.optString("id", ""))) {
                            selected = candidate; break;
                        }
                    }
                    if (selected == null) throw new IllegalStateException("note missing");
                    double now = System.currentTimeMillis() / 1000.0 - 978307200.0;
                    now = Math.max(now, selected.optDouble("updatedAt", 0) + 0.001);
                    if (operation.equals("edit")) selected.put("text", text);
                    if (operation.equals("done")) selected.put("isDone", done);
                    if (operation.equals("delete")) selected.put("deletedAt", now);
                    else if (operation.equals("restore")) selected.put("deletedAt", JSONObject.NULL);
                    selected.put("updatedAt", now);
                    JSONObject envelope = new JSONObject(); envelope.put("deviceName", "DAAK NODE"); envelope.put("items", items);
                    rememberRequest("POST", "/merge", envelope.toString().getBytes("UTF-8"));
                    handler.post(() -> {
                        if (destroyed) return;
                        message = operation.equals("delete") ? "REMEMBER // DELETED" : "REMEMBER // SYNCED";
                        syncObsidian(true); refreshRemember(); invalidate();
                        if (offerUndo) {
                            AlertDialog undo = new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("REMEMBER // SİLİNDİ")
                                    .setMessage(trimText(text, 120))
                                    .setPositiveButton("GERİ AL", (d, which) -> mutateRememberNote(id, "restore", text, done, false))
                                    .setNegativeButton("TAMAM", null).create();
                            showDaakDialog(undo);
                        }
                    });
                } catch (Exception error) {
                    handler.post(() -> {
                        if (!destroyed) { message = "REMEMBER // SYNC FAILED"; invalidate(); toast("Mac/TailSync erişilemiyor; değişiklik yapılmadı"); }
                    });
                }
            }, "node-remember-mutate").start();
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
            final boolean calendarAllowed = checkSelfPermission("android.permission.READ_CALENDAR") == PackageManager.PERMISSION_GRANTED;
            new Thread(() -> {
                ArrayList<AgendaEntry> entries = new ArrayList<AgendaEntry>();
                ArrayList<String> rows = new ArrayList<String>();
                long begin = System.currentTimeMillis(), end = begin + 30L * 24L * 60L * 60L * 1000L;
                Cursor cursor = null;
                try {
                    if (calendarAllowed) {
                        Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
                        ContentUris.appendId(builder, begin); ContentUris.appendId(builder, end);
                        cursor = getContentResolver().query(builder.build(), new String[]{
                                CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                                CalendarContract.Instances.EVENT_LOCATION}, null, null,
                                CalendarContract.Instances.BEGIN + " ASC");
                        while (cursor != null && cursor.moveToNext()) {
                            long when = cursor.getLong(1);
                            String at = new SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(new Date(when));
                            String title = cursor.getString(0) == null ? "Etkinlik" : cursor.getString(0).replace('\n', ' ').replace('|', '/');
                            String location = cursor.getString(2);
                            entries.add(new AgendaEntry(when, at + " • " + title +
                                    (location == null || location.trim().length() == 0 ? "" : " @ " + location.replace('\n', ' ').replace('|', '/'))));
                        }
                    }
                } catch (Exception ignored) { }
                finally { if (cursor != null) cursor.close(); }
                synchronized (holidayEntries) { entries.addAll(holidayEntries); }
                Collections.sort(entries, (left, right) -> Long.compare(left.when, right.when));
                for (int i = 0; i < entries.size() && i < 50; i++) rows.add(entries.get(i).text);
                exportCalendarMarkdown(rows);
                final String one = rows.size() > 0 ? rows.get(0) : calendarAllowed ? "Yaklaşan etkinlik yok" : "Tap to grant calendar access";
                final String two = rows.size() > 1 ? rows.get(1) : "";
                handler.post(() -> {
                    if (destroyed) return;
                    lastCalendarRefresh = System.currentTimeMillis();
                    agendaOne = one; agendaTwo = two; invalidate();
                });
            }, "node-calendar").start();
        }

        void exportCalendarMarkdown(List<String> rows) {
            File vault = new File("/sdcard/Documents/DAAK-Vault");
            File temp = new File(vault, ".DAAK Calendar.md.tmp");
            File target = new File(vault, "DAAK Calendar.md");
            try {
                if (!vault.exists() && !vault.mkdirs()) return;
                StringBuilder body = new StringBuilder();
                body.append("# DAAK Calendar\n\n");
                body.append("> Google Calendar salt okunur etkinlikleri + hava durumu ülkesine göre özel günler.\n");
                body.append("> Konumun yalnızca ülke kodu yerel olarak saklanır; özel gün önbelleği offline kullanılabilir.\n\n");
                if (holidayCountry.length() > 0) body.append("Özel gün ülkesi: **").append(holidayCountry).append("**\n\n");
                body.append("Son güncelleme: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date())).append("\n\n");
                if (rows.isEmpty()) body.append("- Yaklaşan etkinlik yok.\n");
                else for (String row : rows) body.append("- ").append(row).append("\n");
                FileOutputStream output = new FileOutputStream(temp, false);
                output.write(body.toString().getBytes("UTF-8")); output.flush(); output.getFD().sync(); output.close();
                if (target.exists() && !target.delete()) return;
                if (!temp.renameTo(target)) temp.delete();
            } catch (Exception ignored) { temp.delete(); }
        }

        void setHolidayCountry(String rawCountry) {
            String country = rawCountry == null ? "" : rawCountry.trim().toUpperCase(Locale.US);
            if (!country.matches("[A-Z]{2}")) return;
            if (country.equals(holidayCountry)) return;
            holidayCountry = country;
            getSharedPreferences(NodeStore.PREFS, 0).edit().putString("holiday_country", country).apply();
            lastHolidayRefresh = 0L;
            refreshHolidays(true);
        }

        void detectHolidayCountry(double latitude, double longitude) {
            try {
                Geocoder geocoder = new Geocoder(MainActivity.this, Locale.getDefault());
                List<Address> results = geocoder.getFromLocation(latitude, longitude, 1);
                if (results != null && !results.isEmpty()) setHolidayCountry(results.get(0).getCountryCode());
            } catch (Exception ignored) { }
        }

        void refreshHolidays(boolean force) {
            final String country = holidayCountry.length() == 0
                    ? getSharedPreferences(NodeStore.PREFS, 0).getString("holiday_country", "") : holidayCountry;
            if (!country.matches("[A-Z]{2}")) return;
            SharedPreferences prefs = getSharedPreferences(NodeStore.PREFS, 0);
            long cachedAt = prefs.getLong("holiday_cache_at_" + country, 0L);
            String cached = prefs.getString("holiday_cache_" + country, "");
            if (!force && cached.length() > 2 && System.currentTimeMillis() - cachedAt < 7L * 24L * 60L * 60L * 1000L) {
                applyHolidayJson(country, cached, false); return;
            }
            new Thread(() -> {
                try {
                    Calendar calendar = Calendar.getInstance();
                    int year = calendar.get(Calendar.YEAR);
                    JSONArray combined = new JSONArray();
                    for (int selectedYear = year; selectedYear <= year + 1; selectedYear++) {
                        JSONArray fetched = readJsonArray("https://date.nager.at/api/v4/Holidays/" + country + "/" + selectedYear);
                        for (int i = 0; i < fetched.length(); i++) combined.put(fetched.getJSONObject(i));
                    }
                    prefs.edit().putString("holiday_cache_" + country, combined.toString())
                            .putLong("holiday_cache_at_" + country, System.currentTimeMillis()).apply();
                    applyHolidayJson(country, combined.toString(), true);
                } catch (Exception error) {
                    if (cached.length() > 2) applyHolidayJson(country, cached, false);
                }
            }, "node-holidays").start();
        }

        JSONArray readJsonArray(String address) throws Exception {
            HttpURLConnection connection = (HttpURLConnection)new URL(address).openConnection();
            try {
                connection.setConnectTimeout(4000); connection.setReadTimeout(5000);
                if (connection.getResponseCode() != 200) throw new IllegalStateException("HTTP " + connection.getResponseCode());
                InputStream input = connection.getInputStream(); byte[] buffer = new byte[4096]; int count;
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
                input.close(); return new JSONArray(new String(output.toByteArray(), "UTF-8"));
            } finally { connection.disconnect(); }
        }

        void applyHolidayJson(final String country, String raw, boolean refreshed) {
            try {
                JSONArray values = new JSONArray(raw);
                ArrayList<AgendaEntry> upcoming = new ArrayList<AgendaEntry>();
                long begin = System.currentTimeMillis() - 12L * 60L * 60L * 1000L;
                long end = System.currentTimeMillis() + 30L * 24L * 60L * 60L * 1000L;
                SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                SimpleDateFormat label = new SimpleDateFormat("dd MMM", Locale.getDefault());
                for (int i = 0; i < values.length(); i++) {
                    JSONObject holiday = values.optJSONObject(i);
                    if (holiday == null) continue;
                    Date date = parser.parse(holiday.optString("date", ""));
                    if (date == null || date.getTime() < begin || date.getTime() > end) continue;
                    String name = holiday.optString("name", "Special day").replace('\n', ' ').replace('|', '/');
                    upcoming.add(new AgendaEntry(date.getTime(), label.format(date) + " • " + country + " SPECIAL • " + name));
                }
                Collections.sort(upcoming, (left, right) -> Long.compare(left.when, right.when));
                handler.post(() -> {
                    if (destroyed) return;
                    synchronized (holidayEntries) { holidayEntries.clear(); holidayEntries.addAll(upcoming); }
                    lastHolidayRefresh = System.currentTimeMillis();
                    refreshCalendar();
                    if (refreshed) { message = "CALENDAR // " + country + " SPECIAL DAYS"; invalidate(); }
                });
            } catch (Exception ignored) { }
        }

        void refreshWeather(boolean force) {
            if (!force && System.currentTimeMillis() - lastWeatherRefresh < 30L * 60L * 1000L) return;
            String configuredCity = getSharedPreferences(NodeStore.PREFS, 0).getString("weather_city", "").trim();
            if (configuredCity.length() > 0) { fetchCityWeather(configuredCity); return; }
            if (checkSelfPermission("android.permission.ACCESS_COARSE_LOCATION") != PackageManager.PERMISSION_GRANTED) {
                weather = "TAP FOR LOCATION"; weatherDetails = ""; invalidate(); return;
            }
            LocationManager manager = (LocationManager)getSystemService(LOCATION_SERVICE);
            if (manager == null) {
                weather = "LOCATION UNAVAILABLE"; weatherDetails = ""; invalidate(); return;
            }
            Location location = null;
            try {
                location = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (location == null) location = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            } catch (RuntimeException ignored) { }
            if (location == null) {
                weather = "LOCATING..."; weatherDetails = ""; invalidate();
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
                            if (!destroyed) { weather = "LOCATION DISABLED"; weatherDetails = ""; invalidate(); }
                        }
                    };
                    manager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER,
                            pendingLocationListener, Looper.getMainLooper());
                } catch (RuntimeException error) {
                    clearPendingLocationRequest();
                    weather = "LOCATION DISABLED"; weatherDetails = ""; invalidate();
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
                    detectHolidayCountry(lat, lon);
                    URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon + "&current=temperature_2m,apparent_temperature,weather_code&timezone=auto");
                    connection = (HttpURLConnection)url.openConnection(); connection.setConnectTimeout(3500); connection.setReadTimeout(3500);
                    InputStream input = connection.getInputStream(); StringBuilder json = new StringBuilder(); byte[] buffer = new byte[2048]; int count;
                    while ((count = input.read(buffer)) > 0) json.append(new String(buffer, 0, count, "UTF-8")); input.close();
                    JSONObject current = new JSONObject(json.toString()).getJSONObject("current");
                    final String prefix = weatherCity.length() == 0 ? "" : weatherCity.toUpperCase(Locale.getDefault()) + " • ";
                    final String value = prefix + Math.round(current.getDouble("temperature_2m")) + "°C";
                    final String details = "HİS " + Math.round(current.getDouble("apparent_temperature")) + "°C • " + weatherCode(current.getInt("weather_code"));
                    lastWeatherRefresh = System.currentTimeMillis(); handler.post(() -> {
                        if (!destroyed) { weather = value; weatherDetails = details; invalidate(); }
                    });
                } catch (Exception error) { handler.post(() -> {
                    if (!destroyed) { weather = "WEATHER OFFLINE"; weatherDetails = ""; invalidate(); }
                }); }
                finally { if (connection != null) connection.disconnect(); }
            }, "node-weather").start();
        }

        void fetchCityWeather(final String city) {
            weather = "SEARCHING " + city.toUpperCase(Locale.getDefault()); weatherDetails = ""; invalidate();
            new Thread(() -> {
                HttpURLConnection connection = null;
                try {
                    URL url = new URL("https://geocoding-api.open-meteo.com/v1/search?name=" + URLEncoder.encode(city, "UTF-8") + "&count=1&language=tr&format=json");
                    connection = (HttpURLConnection)url.openConnection(); connection.setConnectTimeout(3500); connection.setReadTimeout(3500);
                    InputStream input = connection.getInputStream(); StringBuilder json = new StringBuilder(); byte[] buffer = new byte[2048]; int count;
                    while ((count = input.read(buffer)) > 0) json.append(new String(buffer, 0, count, "UTF-8")); input.close();
                    JSONObject result = new JSONObject(json.toString()).getJSONArray("results").getJSONObject(0);
                    Location location = new Location("city"); location.setLatitude(result.getDouble("latitude")); location.setLongitude(result.getDouble("longitude"));
                    weatherCity = result.optString("name", city);
                    setHolidayCountry(result.optString("country_code", ""));
                    fetchWeather(location);
                } catch (Exception error) { handler.post(() -> {
                    if (!destroyed) { weather = "CITY NOT FOUND"; weatherDetails = ""; invalidate(); }
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
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    String interfaceName = ni.getName() == null ? "" : ni.getName().toLowerCase(Locale.US);
                    if (!(interfaceName.startsWith("tun") || interfaceName.contains("tailscale") ||
                            interfaceName.startsWith("wg"))) continue;
                    for (java.net.InetAddress address : Collections.list(ni.getInetAddresses())) {
                        String host = address.getHostAddress();
                        if (!address.isLoopbackAddress() && isTailnetIpv4(host)) return host;
                    }
                }
            } catch (Exception ignored) { }
            return "OFFLINE";
        }

        boolean isTailnetIpv4(String host) {
            if (host == null || !host.startsWith("100.")) return false;
            String[] parts = host.split("\\.");
            if (parts.length != 4) return false;
            try {
                int second = Integer.parseInt(parts[1]);
                return second >= 64 && second <= 127;
            } catch (NumberFormatException ignored) { return false; }
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
            paint.setTextSize(dp(size * TEXT_SCALE)); paint.setColor(color);
            paint.setTypeface(Typeface.create(mono, bold ? Typeface.BOLD : Typeface.NORMAL));
            paint.setStyle(Paint.Style.FILL); paint.setStrokeWidth(dp(1));
        }
        void box(Canvas c, RectF r, float radius, int fill, int stroke) {
            paint.setStyle(Paint.Style.FILL); paint.setColor(fill); c.drawRoundRect(r, dp(radius), dp(radius), paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(stroke);
            c.drawRoundRect(r, dp(radius), dp(radius), paint);
        }
        void center(Canvas c, String text, float x, float y) { c.drawText(text, x - paint.measureText(text) / 2f, y, paint); }
        String fitText(String text, float maxWidth) {
            if (text == null) return "";
            if (paint.measureText(text) <= maxWidth) return text;
            String suffix = "…";
            int end = text.length();
            while (end > 1 && paint.measureText(text.substring(0, end) + suffix) > maxWidth) end--;
            return text.substring(0, Math.max(1, end)).trim() + suffix;
        }
        void addHit(RectF rect, String action) { hits.add(new Hit(new RectF(rect), action)); }
        void button(Canvas c, RectF rect, String tag, String title, String subtitle, boolean hot, String action) {
            box(c, rect, 13, hot ? panelHot : panel, hot ? mintDim : line);
            c.save(); c.clipRect(rect);
            float available = rect.width() - dp(24);
            type(6.5f, hot ? mint : mintDim, true); c.drawText(fitText(tag, available), rect.left + dp(12), rect.top + dp(15), paint);
            type(10.5f, hot ? mint : soft, true); c.drawText(fitText(title, available), rect.left + dp(12), rect.top + dp(31), paint);
            type(6.2f, ghost, false); c.drawText(fitText(subtitle, available), rect.left + dp(12), rect.bottom - dp(7), paint);
            c.restore();
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
                else if (mode == CODEX_VIEW) drawCodexLandscape(c);
                else if (mode == INFO_PANEL) drawInfoPanelLandscape(c);
                else drawRemoteLandscape(c);
                drawDockLandscape(c);
            } else {
                if (mode == HOME) drawHome(c);
                else if (mode == APPS) drawApps(c);
                else if (mode == HELP) drawHelp(c);
                else if (mode == CONTROL) drawControl(c);
                else if (mode == DISK_VIEW) drawDisk(c);
                else if (mode == CODEX_VIEW) drawCodex(c);
                else if (mode == INFO_PANEL) drawInfoPanel(c);
                else drawRemote(c);
                drawDock(c);
            }
            drawPressFeedback(c);
            c.restore();
            int contentAlpha = oledContentAlpha();
            if (contentAlpha < 255) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(255 - contentAlpha, 0, 0, 0));
                c.drawRect(0, 0, getWidth(), getHeight(), paint);
            }
        }

        void drawPressFeedback(Canvas c) {
            if (pressedRect == null || pressGlow <= 0f) return;
            float inset = dp(2.5f * pressGlow);
            RectF animated = new RectF(pressedRect);
            animated.inset(inset, inset);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb((int)(52f * pressGlow), 201, 167, 220));
            c.drawRoundRect(animated, dp(12), dp(12), paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.4f));
            paint.setColor(Color.argb((int)(220f * pressGlow), 201, 167, 220));
            c.drawRoundRect(animated, dp(12), dp(12), paint);
        }

        void animateHit(final Hit hit) {
            if (isDockAction(hit.action)) {
                if (pressAnimator != null) { pressAnimator.cancel(); pressAnimator = null; }
                pressedRect = null; pressGlow = 0f;
                handle(hit);
                return;
            }
            if (pressAnimator != null) pressAnimator.cancel();
            pressedRect = new RectF(hit.rect); pressGlow = 1f; invalidate();
            pressAnimator = ValueAnimator.ofFloat(1f, 0f);
            pressAnimator.setDuration(190L);
            pressAnimator.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
            pressAnimator.addUpdateListener(animation -> {
                pressGlow = (Float)animation.getAnimatedValue(); invalidate();
            });
            pressAnimator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    if (pressAnimator == animation) { pressedRect = null; pressAnimator = null; invalidate(); }
                }
            });
            pressAnimator.start();
            handler.postDelayed(() -> { if (!destroyed) handle(hit); }, 70L);
        }

        boolean isDockAction(String action) {
            return action.equals("HOME") || action.equals("APPS") || action.equals("CODEX")
                    || action.equals("DISK") || action.equals("HELP");
        }

        void drawTopBar(Canvas c) {
            float w = getWidth();
            type(8, mintDim, true); c.drawText("DAAK//NODE  v" + BUILD_VERSION, dp(16), dp(25), paint);
            type(13, mint, true); c.drawText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date()), dp(16), dp(45), paint);
            String state = meshIp.equals("OFFLINE") ? "NO MESH" : "TAILNET";
            type(8, meshIp.equals("OFFLINE") ? soft : mint, true);
            c.drawText(state, w - dp(165), dp(25), paint);
            BatteryManager bm = (BatteryManager)getSystemService(BATTERY_SERVICE);
            int battery = bm == null ? 0 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            type(8, soft, true); c.drawText("BAT " + battery + "%", w - dp(165), dp(43), paint);
            RectF vault = new RectF(w - dp(77), dp(9), w - dp(42), dp(47));
            type(6.3f, vaultUnlocked ? mint : mintDim, true); center(c, vaultUnlocked ? "OPEN" : "BIO", vault.centerX(), dp(36)); addHit(vault, "VAULT");
            RectF control = new RectF(w - dp(36), dp(9), w - dp(10), dp(47));
            type(18, mint, true); center(c, "≡", control.centerX(), dp(36)); addHit(control, "CONTROL");
            paint.setColor(line); paint.setStrokeWidth(dp(1)); c.drawLine(dp(16), dp(55), w - dp(16), dp(55), paint);
        }

        void drawHome(Canvas c) {
            float left = dp(16), right = getWidth() - dp(16), gap = dp(8);
            RectF terminal = new RectF(left, dp(67), right, dp(178));
            box(c, terminal, 16, panel, line);
            type(7, mintDim, true); c.drawText("// LIVE CONTROL PLANE", left + dp(14), dp(86), paint);
            type(9, mint, false);
            c.drawText("MESH  " + meshIp, left + dp(14), dp(108), paint);
            c.drawText("MAC " + macState + "  •  LOLILE " + diskState, left + dp(14), dp(129), paint);
            c.drawText("SSHD " + sshState + "  •  ROOT " + (rooted ? "YES" : "NO"), left + dp(14), dp(150), paint);
            type(6.5f, soft, true); c.drawText("> " + trimText(message, 43), left + dp(14), dp(169), paint);

            type(7, mintDim, true); c.drawText("// WORKSPACE", left, dp(197), paint);
            float half = (right - left - gap) / 2f;
            button(c, new RectF(left, dp(205), left + half, dp(257)), "CLI", "CODEX", "MAC • REAL CLI", true, "CODEX");
            button(c, new RectF(left + half + gap, dp(205), right, dp(257)), "SMB3", "LOLILE KUREK", "smb://lolile/kurek", true, "DISK");
            button(c, new RectF(left, dp(265), left + half, dp(317)), "GOOGLE", "REMOTE", "CHROME REMOTE DESKTOP", false, "REMOTE");
            button(c, new RectF(left + half + gap, dp(265), right, dp(317)), "LINUX", "DEBIAN", "LOCAL PROOT", false, "LOCAL");

            type(7, mintDim, true); c.drawText("// INTELLIGENCE", left, dp(337), paint);
            RectF mail = new RectF(left, dp(347), left + half, dp(397));
            box(c, mail, 12, panel, line); type(7, mint, true); c.drawText("MAIL // READ ONLY", mail.left + dp(10), dp(365), paint);
            type(6.1f, soft, false); c.drawText(trimText(mailLine, 25), mail.left + dp(10), dp(386), paint); addHit(mail, "MAIL");
            RectF whatsApp = new RectF(left + half + gap, dp(347), right, dp(397));
            box(c, whatsApp, 12, panel, line); type(7, mint, true); c.drawText("WHATSAPP // TASKS", whatsApp.left + dp(10), dp(365), paint);
            type(6.1f, soft, false); c.drawText(trimText(whatsAppLine, 24), whatsApp.left + dp(10), dp(386), paint); addHit(whatsApp, "WHATSAPP");

            RectF remember = new RectF(left, dp(405), left + half, dp(455));
            box(c, remember, 12, panelHot, mintDim); type(7, mint, true); c.drawText("daakREMEMBER", remember.left + dp(10), dp(423), paint);
            type(6.1f, soft, false); c.drawText(trimText(rememberLine, 25), remember.left + dp(10), dp(444), paint); addHit(remember, "REMEMBER");
            RectF climate = new RectF(left + half + gap, dp(405), right, dp(455));
            box(c, climate, 12, panel, line); type(7, mint, true); c.drawText("WEATHER", climate.left + dp(10), dp(423), paint);
            type(6.1f, soft, false); c.drawText(trimText(weather, 24), climate.left + dp(10), dp(444), paint); addHit(climate, "WEATHER");

            RectF agenda = new RectF(left, dp(463), right, dp(513));
            box(c, agenda, 12, panel, line); type(7, mint, true);
            c.drawText(holidayCountry.length() == 0 ? "GOOGLE CALENDAR // NEXT" : "CALENDAR + " + holidayCountry + " SPECIAL DAYS",
                    agenda.left + dp(10), dp(481), paint);
            type(6.2f, soft, false); c.drawText(trimText(agendaOne, 49), agenda.left + dp(10), dp(502), paint); addHit(agenda, "CALENDAR");

            type(7, mintDim, true); c.drawText("// PINNED // 2 × 3", left, dp(535), paint);
            String[] names = {pinnedName(0), pinnedName(1), pinnedName(2), pinnedName(3), "REMEM", "RM-OS"};
            String[] actions = {pinnedAction(0), pinnedAction(1), pinnedAction(2), pinnedAction(3), "REMEMBER", "RMOS"};
            float cardW = (right - left - gap * 2f) / 3f;
            float cardsTop = dp(545), cardsBottom = getHeight() - dp(79);
            float cardH = Math.max(dp(36), (cardsBottom - cardsTop - gap) / 2f);
            for (int i = 0; i < 6; i++) {
                int row = i / 3, column = i % 3;
                float x = left + column * (cardW + gap), y = cardsTop + row * (cardH + gap);
                RectF r = new RectF(x, y, x + cardW, y + cardH);
                box(c, r, 12, panel, line); type(7.2f, soft, true); center(c, names[i], r.centerX(), r.centerY() + dp(2.5f));
                addHit(r, actions[i]);
            }
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
            button(c, new RectF(x1 + tileW + gap, tileTop, x1 + col, tileTop + tileH), "SMB3", "DISK", "LOLILE / KUREK", true, "DISK");
            button(c, new RectF(x1, tileTop + tileH + gap, x1 + tileW, bottom), "GOOGLE", "REMOTE", "CHROME RD", false, "REMOTE");
            button(c, new RectF(x1 + tileW + gap, tileTop + tileH + gap, x1 + col, bottom), "LINUX", "DEBIAN", "LOCAL", false, "LOCAL");

            RectF remember = new RectF(x2, top, x2 + col, dp(174));
            box(c, remember, 14, panelHot, mintDim); type(8, mint, true); c.drawText("daakREMEMBER // TAILSYNC", x2 + dp(12), top + dp(22), paint);
            type(7, soft, false); c.drawText(trimText(rememberLine, 34), x2 + dp(12), top + dp(48), paint);
            type(6, ghost, false); c.drawText("TAP → VIEW / CAPTURE • 45831", x2 + dp(12), top + dp(73), paint); addHit(remember, "REMEMBER");
            RectF obsidian = new RectF(x2, dp(182), x2 + col, dp(239));
            button(c, obsidian, "MD", "RM-OS / OBSIDIAN", "DAAK VAULT • SYNCED", true, "RMOS");
            float miniTop = dp(247), miniW = (col - gap * 2f) / 3f;
            String[] miniNames = {pinnedName(0), pinnedName(1), "MAIL"};
            String[] miniActions = {pinnedAction(0), pinnedAction(1), "MAIL"};
            for (int i = 0; i < 3; i++) {
                RectF r = new RectF(x2 + i * (miniW + gap), miniTop, x2 + i * (miniW + gap) + miniW, bottom);
                box(c, r, 11, panel, line); type(6.5f, soft, true); center(c, miniNames[i], r.centerX(), r.centerY() + dp(3)); addHit(r, miniActions[i]);
            }

            RectF mail = new RectF(x3, top, x3 + col, dp(120));
            box(c, mail, 12, panel, line); type(7, mint, true); c.drawText("MAIL // ALL ACCOUNTS", x3 + dp(10), top + dp(20), paint);
            type(6.3f, soft, false); c.drawText(trimText(mailLine, 35), x3 + dp(10), top + dp(42), paint); addHit(mail, "MAIL");
            RectF whatsApp = new RectF(x3, dp(128), x3 + col, dp(181));
            box(c, whatsApp, 12, panel, line); type(7, mint, true); c.drawText("WHATSAPP // TASKS", x3 + dp(10), dp(148), paint);
            type(6.3f, soft, false); c.drawText(trimText(whatsAppLine, 35), x3 + dp(10), dp(170), paint); addHit(whatsApp, "WHATSAPP");
            RectF climate = new RectF(x3, dp(189), x3 + col, dp(242));
            box(c, climate, 12, panel, line); type(7, mint, true); c.drawText("WEATHER", x3 + dp(10), dp(208), paint);
            type(6.3f, soft, false); c.drawText(trimText(weather, 35), x3 + dp(10), dp(230), paint); addHit(climate, "WEATHER");
            RectF agenda = new RectF(x3, dp(250), x3 + col, bottom);
            box(c, agenda, 12, panel, line); type(7, mint, true); c.drawText("AGENDA // NEXT", x3 + dp(10), dp(269), paint);
            type(6.1f, soft, false); c.drawText(trimText(agendaOne, 35), x3 + dp(10), dp(290), paint); addHit(agenda, "CALENDAR");
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

        String panelTitle() {
            if (panelKind.equals("MAIL")) return "MAIL // READ ONLY";
            if (panelKind.equals("WHATSAPP")) return "WHATSAPP // TASK ROUTER";
            if (panelKind.equals("MUSIC")) return "MUSIC // LOCAL + STREAM";
            if (panelKind.equals("POWER_LOLILE")) return "POWER // LOLILE WINDOWS";
            if (panelKind.equals("POWER_MAC")) return "POWER // MY MAC";
            return "daakREMEMBER // TAILSYNC";
        }

        String panelStatus() {
            if (panelKind.equals("MAIL")) return "GMAIL + THUNDERBIRD • READ ONLY";
            if (panelKind.equals("WHATSAPP")) return NodeStore.whatsAppAutomationEnabled(MainActivity.this)
                    ? "BALANCED DETECTOR • AUTO TASKS ON" : "AUTO TASKS OFF";
            if (panelKind.equals("MUSIC")) return "ACTIVE SESSION • OLED LOCK PLAYER";
            if (panelKind.startsWith("POWER_")) return "TAILNET SSH • WAKE-ON-LAN";
            return rememberOpenCount + " AÇIK NOT • TAILNET ONLY";
        }

        String panelPrimaryLabel() {
            if (panelKind.equals("MAIL")) return "MAIL APPS";
            if (panelKind.equals("WHATSAPP")) return "WHATSAPP";
            if (panelKind.equals("MUSIC")) return "PLAY/PAUSE";
            if (panelKind.startsWith("POWER_")) return "WAKE";
            return "NEW NOTE";
        }

        String panelSecondaryLabel() {
            if (panelKind.equals("REMEMBER")) return "REFRESH";
            if (panelKind.equals("MUSIC")) return "NEXT";
            if (panelKind.startsWith("POWER_")) return "SHUTDOWN";
            return "FILTER";
        }

        void drawInfoPanel(Canvas c) {
            float left = dp(16), right = getWidth() - dp(16), top = dp(68), bottom = getHeight() - dp(78);
            RectF shell = new RectF(left, top, right, bottom);
            box(c, shell, 18, panel, line);
            type(7, mintDim, true); c.drawText("// DAAK INTELLIGENCE", left + dp(14), top + dp(23), paint);
            type(15, mint, true); c.drawText(panelTitle(), left + dp(14), top + dp(50), paint);
            type(6.3f, soft, true); c.drawText(panelStatus(), left + dp(14), top + dp(70), paint);
            paint.setColor(line); c.drawRect(left + dp(14), top + dp(82), right - dp(14), top + dp(83), paint);

            float listTop = top + dp(94), actionTop = bottom - dp(58), rowH = dp(55);
            c.save(); c.clipRect(left + dp(10), listTop, right - dp(10), actionTop - dp(8));
            if (panelItems.isEmpty()) {
                type(8, ghost, false); c.drawText(panelKind.equals("REMEMBER") ? "Mac çevrimdışı veya henüz not yok."
                        : "Yeni kayıt yok • rahat ol.", left + dp(16), listTop + dp(25), paint);
            }
            for (int i = 0; i < panelItems.size(); i++) {
                float y = listTop + i * rowH - panelScroll;
                if (y + rowH < listTop || y > actionTop) continue;
                RectF row = new RectF(left + dp(12), y, right - dp(12), y + rowH - dp(6));
                box(c, row, 10, i == 0 ? panelHot : Color.BLACK, i == 0 ? mintDim : line);
                type(6, mintDim, true); c.drawText(String.format(Locale.US, "%02d", i + 1), row.left + dp(9), y + dp(18), paint);
                type(7.2f, soft, false); c.drawText(trimText(panelItems.get(i), 44), row.left + dp(36), y + dp(28), paint);
                if (panelKind.equals("REMEMBER") && row.top >= listTop && row.bottom <= actionTop - dp(8))
                    addHit(row, "REMEMBER_ITEM:" + i);
            }
            c.restore();

            float gap = dp(8), buttonW = (right - left - gap * 2f) / 3f;
            String primary = panelPrimaryLabel();
            String secondary = panelSecondaryLabel();
            RectF first = new RectF(left, actionTop, left + buttonW, bottom);
            RectF second = new RectF(left + buttonW + gap, actionTop, left + buttonW * 2f + gap, bottom);
            RectF close = new RectF(left + buttonW * 2f + gap * 2f, actionTop, right, bottom);
            box(c, first, 12, panelHot, mintDim); type(6.5f, mint, true); center(c, primary, first.centerX(), first.centerY() + dp(2)); addHit(first, "PANEL_PRIMARY");
            box(c, second, 12, panel, line); type(6.5f, soft, true); center(c, secondary, second.centerX(), second.centerY() + dp(2)); addHit(second, "PANEL_SECONDARY");
            String tertiary = panelKind.equals("MUSIC") ? "LOCK PLAYER" : "CLOSE";
            box(c, close, 12, panel, line); type(6.5f, soft, true); center(c, tertiary, close.centerX(), close.centerY() + dp(2));
            addHit(close, panelKind.equals("MUSIC") ? "PANEL_TERTIARY" : "PANEL_CLOSE");
        }

        void drawInfoPanelLandscape(Canvas c) {
            float left = dp(16), right = getWidth() - dp(16), top = dp(68), bottom = getHeight() - dp(57), gap = dp(10);
            RectF shell = new RectF(left, top, right, bottom);
            box(c, shell, 16, panel, line);
            float side = Math.min(dp(230), (right - left) * 0.31f), listLeft = left + side + gap;
            type(7, mintDim, true); c.drawText("// DAAK INTELLIGENCE", left + dp(14), top + dp(24), paint);
            type(13, mint, true); c.drawText(panelTitle(), left + dp(14), top + dp(51), paint);
            type(6, soft, true); c.drawText(panelStatus(), left + dp(14), top + dp(73), paint);
            String primary = panelPrimaryLabel();
            button(c, new RectF(left + dp(12), top + dp(91), left + side - dp(2), top + dp(144)), "OPEN", primary, "PRIVATE ACTION", true, "PANEL_PRIMARY");
            button(c, new RectF(left + dp(12), top + dp(152), left + side - dp(2), top + dp(205)), "TOOLS", panelSecondaryLabel(), "LOCAL SETTINGS", false, "PANEL_SECONDARY");
            button(c, new RectF(left + dp(12), top + dp(213), left + side - dp(2), bottom - dp(12)),
                    panelKind.equals("MUSIC") ? "OLED" : "BACK", panelKind.equals("MUSIC") ? "LOCK PLAYER" : "CLOSE",
                    panelKind.equals("MUSIC") ? "PLAYER / SOURCES" : "RETURN HOME", false,
                    panelKind.equals("MUSIC") ? "PANEL_TERTIARY" : "PANEL_CLOSE");
            float rowH = dp(44);
            c.save(); c.clipRect(listLeft, top + dp(12), right - dp(12), bottom - dp(12));
            if (panelItems.isEmpty()) { type(8, ghost, false); c.drawText("Yeni kayıt yok • rahat ol.", listLeft + dp(12), top + dp(40), paint); }
            for (int i = 0; i < panelItems.size(); i++) {
                float y = top + dp(12) + i * rowH - panelScroll;
                if (y + rowH < top || y > bottom) continue;
                RectF row = new RectF(listLeft, y, right - dp(12), y + rowH - dp(5));
                box(c, row, 9, i == 0 ? panelHot : Color.BLACK, i == 0 ? mintDim : line);
                type(6, mintDim, true); c.drawText(String.format(Locale.US, "%02d", i + 1), row.left + dp(9), y + dp(17), paint);
                type(7, soft, false); c.drawText(trimText(panelItems.get(i), 72), row.left + dp(38), y + dp(25), paint);
                if (panelKind.equals("REMEMBER") && row.top >= top + dp(12) && row.bottom <= bottom - dp(12))
                    addHit(row, "REMEMBER_ITEM:" + i);
            }
            c.restore();
        }

        void drawHelpLandscape(Canvas c) {
            float left = dp(16), right = getWidth() - dp(16), top = dp(67), bottom = getHeight() - dp(58), gap = dp(8);
            String[][] cards = {
                    {"CODEX", "ÇALIŞMA ALANI SEÇ", "CODEX"}, {"LOLILE", "KUREK / SMB3", "DISK"},
                    {"REM", "DAAK REMEMBER", "REMEMBER"}, {"RM-OS", "OBSIDIAN VAULT", "RMOS"},
                    {"SEC", "BIOMETRICS", "SET:SECURITY"}, {"SYS", "ANDROID SETTINGS", "SET:SYSTEM"},
                    {"VPN", "TAILSCALE", "PKG:com.tailscale.ipn"}, {"KEY", "KEY MAPPER", "PKG:io.github.sds100.keymapper"}
            };
            float colW = (right - left - gap * 3f) / 4f, rowH = (bottom - top - gap) / 2f;
            for (int i = 0; i < cards.length; i++) {
                int row = i / 4, colIndex = i % 4;
                RectF r = new RectF(left + colIndex * (colW + gap), top + row * (rowH + gap), left + colIndex * (colW + gap) + colW, top + row * (rowH + gap) + rowH);
                button(c, r, cards[i][0], cards[i][1], i < 4 ? "DAAK ACTION" : "SETUP / RECOVERY", i < 4, cards[i][2]);
            }
        }

        void drawControlLandscape(Canvas c) {
            float left = dp(16), right = getWidth() - dp(16), top = dp(67), bottom = getHeight() - dp(58), gap = dp(8);
            String[][] tiles = {
                    {"VPN", "TAILSCALE", "PKG:com.tailscale.ipn"}, {"PC", "DAAK LOLILE", "LOLILE_HUB"}, {"PIN", "PINNED APPS", "PINS"}, {"KEY", "KEYBOARD", "SET:INPUT"},
                    {"SEC", "BIOMETRICS", "SET:SECURITY"}, {"WA", "WHATSAPP TASKS", "WHATSAPP"}, {"MIC", "DAAK INBOX", "DICTATE"}, {"UP", "UPDATE", "CHECK_UPDATE"},
                    {"ALM", "FOSSIFY CLOCK", "PKG:org.fossify.clock"}, {"ZZZ", "SLEEP TRACKER", "PKG:hu.vmiklos.plees_tracker"},
                    {"SND", "NOTIFY SOUND", "SOUND"}, {"NLS", "NOTIFY ACCESS", "SET:MAILACCESS"}
            };
            float colW = (right - left - gap * 3f) / 4f, rowH = (bottom - top - gap * 2f) / 3f;
            for (int i = 0; i < tiles.length; i++) {
                int row = i / 4, colIndex = i % 4;
                RectF r = new RectF(left + colIndex * (colW + gap), top + row * (rowH + gap), left + colIndex * (colW + gap) + colW, top + row * (rowH + gap) + rowH);
                button(c, r, tiles[i][0], tiles[i][1], i == 0 ? meshIp : "OPEN PANEL", i == 7, tiles[i][2]);
            }
        }

        void drawRemote(Canvas c) {
            float left = dp(16), right = getWidth() - dp(16), gap = dp(8);
            type(17, mint, true); c.drawText("REMOTE // TOUCH DESKTOP", left, dp(88), paint);
            type(7, soft, false); c.drawText("CHROME REMOTE DESKTOP • GOOGLE ACCOUNT", left, dp(107), paint);
            String[] names = {"LOLILE WINDOWS", "MY MAC", "BEDIRHAN MAC", "BEDIRHAN WINDOWS"};
            float half = (right - left - gap) / 2f, top = dp(124), h = dp(92);
            for (int i = 0; i < names.length; i++) {
                int row = i / 2, col = i % 2;
                RectF r = new RectF(left + col * (half + gap), top + row * (h + gap),
                        left + col * (half + gap) + half, top + row * (h + gap) + h);
                button(c, r, "GOOGLE", names[i], "OPEN DEVICE LIST", i < 2, "REMOTE_CONNECT:" + i);
            }
            RectF configure = new RectF(left, top + dp(208), right, top + dp(266));
            button(c, configure, "GOOGLE", "REMOTE DEVICES", "SIGN IN / DEVICE LIST", false, "REMOTE_CONFIG");
            RectF powerLolie = new RectF(left, top + dp(274), left + half, top + dp(334));
            RectF powerMac = new RectF(left + half + gap, top + dp(274), right, top + dp(334));
            button(c, powerLolie, "POWER", "LOLILE", "WOL / SHUTDOWN", true, "POWER_LOLILE");
            button(c, powerMac, "POWER", "MY MAC", "WOL / SHUTDOWN", false, "POWER_MAC");
            type(7, ghost, false); c.drawText("Google hesabın → cihazı seç → touchpad / touch controls", left, top + dp(355), paint);
        }

        void drawRemoteLandscape(Canvas c) {
            float left = dp(16), right = getWidth() - dp(16), top = dp(67), bottom = getHeight() - dp(58), gap = dp(8);
            String[] names = {"LOLILE WINDOWS", "MY MAC", "BEDIRHAN MAC", "BEDIRHAN WINDOWS"};
            float side = dp(180), listLeft = left + side + gap;
            RectF configure = new RectF(left, top, left + side, top + (bottom - top) * 0.48f);
            button(c, configure, "GOOGLE", "REMOTE HUB", "SIGN IN / DEVICE LIST", true, "REMOTE_CONFIG");
            RectF powerLolie = new RectF(left, configure.bottom + gap, left + (side - gap) / 2f, bottom);
            RectF powerMac = new RectF(powerLolie.right + gap, configure.bottom + gap, left + side, bottom);
            button(c, powerLolie, "PWR", "LOLILE", "WOL / OFF", true, "POWER_LOLILE");
            button(c, powerMac, "PWR", "MAC", "WOL / OFF", false, "POWER_MAC");
            float cell = (right - listLeft - gap) / 2f, h = (bottom - top - gap) / 2f;
            for (int i = 0; i < names.length; i++) {
                int row = i / 2, col = i % 2;
                RectF r = new RectF(listLeft + col * (cell + gap), top + row * (h + gap),
                        listLeft + col * (cell + gap) + cell, top + row * (h + gap) + h);
                button(c, r, "GOOGLE", names[i], "OPEN DEVICE LIST", i < 2, "REMOTE_CONNECT:" + i);
            }
        }

        void connectRemote(int index) {
            launchChromeRemoteDesktop();
        }

        void showRemoteConfig() {
            launchChromeRemoteDesktop();
        }

        void launchChromeRemoteDesktop() {
            Intent launch = getPackageManager().getLaunchIntentForPackage("com.google.chromeremotedesktop");
            if (launch != null) { startActivity(launch); return; }
            try {
                Intent store = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=com.google.chromeremotedesktop"));
                store.setPackage("com.android.vending"); startActivity(store);
            } catch (RuntimeException error) {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=com.google.chromeremotedesktop")));
            }
        }

        void drawCodex(Canvas c) {
            float left = dp(16), right = getWidth() - dp(16), gap = dp(9);
            type(17, mint, true); c.drawText("CODEX // WORKSPACE", left, dp(88), paint);
            type(7, soft, false); c.drawText("MAC • REAL CODEX CLI • SELECT CONTEXT", left, dp(107), paint);
            float top = dp(124), h = dp(82);
            button(c, new RectF(left, top, right, top + h), "ZERO", "PROJESİZ", "BOŞ VE GEÇİCİ ÇALIŞMA ALANI", true, "CODEX_RUN:standalone");
            top += h + gap;
            button(c, new RectF(left, top, right, top + h), "HUB", "DAAK KNOWLEDGE", "RM-OS • OBSIDIAN • REMEMBER", true, "CODEX_RUN:hub");
            top += h + gap;
            button(c, new RectF(left, top, right, top + h), "NODE", "PROJE TELEFONU", "DAAK NODE SOURCE WORKSPACE", false, "CODEX_RUN:phone");
            top += h + gap;
            button(c, new RectF(left, top, right, top + h), "PATH", "MAC KLASÖRÜ", "ÖZEL PROJE YOLU GİR", false, "CODEX_CUSTOM");
            type(7, ghost, false); c.drawText("Seçim biyometri kasasından sonra Mac'teki CLI'yi açar.", left, top + h + dp(27), paint);
        }

        void drawCodexLandscape(Canvas c) {
            float left = dp(16), right = getWidth() - dp(16), top = dp(67), bottom = getHeight() - dp(58), gap = dp(9);
            float halfW = (right - left - gap) / 2f, halfH = (bottom - top - gap) / 2f;
            button(c, new RectF(left, top, left + halfW, top + halfH), "ZERO", "PROJESİZ", "TEMPORARY / CLEAN", true, "CODEX_RUN:standalone");
            button(c, new RectF(left + halfW + gap, top, right, top + halfH), "HUB", "DAAK KNOWLEDGE", "RM-OS / OBSIDIAN", true, "CODEX_RUN:hub");
            button(c, new RectF(left, top + halfH + gap, left + halfW, bottom), "NODE", "PROJE TELEFONU", "DAAK NODE SOURCE", false, "CODEX_RUN:phone");
            button(c, new RectF(left + halfW + gap, top + halfH + gap, right, bottom), "PATH", "MAC KLASÖRÜ", "CUSTOM WORKSPACE", false, "CODEX_CUSTOM");
        }

        void showCodexCustomPath() {
            final EditText input = new EditText(MainActivity.this);
            input.setSingleLine(true);
            input.setHint("/Users/kai/Documents/proje-adi");
            new AlertDialog.Builder(MainActivity.this).setTitle("MAC PROJE KLASÖRÜ")
                    .setMessage("Documents veya DAAK çalışma alanı içindeki mevcut bir klasörü yaz.")
                    .setView(input)
                    .setPositiveButton("CODEX AÇ", (dialog, which) -> {
                        String path = input.getText().toString().trim();
                        if (path.length() == 0) { toast("Klasör yolu boş"); return; }
                        String encoded = Base64.encodeToString(path.getBytes(Charset.forName("UTF-8")), Base64.NO_WRAP);
                        launchCodexWorkspace("custom", encoded);
                    })
                    .setNegativeButton("İPTAL", null).show();
        }

        void launchCodexWorkspace(String mode, String encoded) {
            guarded(() -> runTermux("exec ~/.shortcuts/codex " + mode +
                    (encoded.length() == 0 ? "" : " " + encoded), "CODEX CLI"));
        }

        void drawDiskLandscape(Canvas c) {
            float left = dp(16), right = getWidth() - dp(16), top = dp(67), bottom = getHeight() - dp(58), gap = dp(8);
            float side = dp(190), listLeft = left + side + gap;
            float sideThird = (bottom - top - gap * 2f) / 3f;
            RectF back = new RectF(left, top, left + side, top + sideThird);
            RectF refresh = new RectF(left, top + sideThird + gap, left + side, top + sideThird * 2f + gap);
            RectF backup = new RectF(left, top + sideThird * 2f + gap * 2f, left + side, bottom);
            button(c, back, "BACK", "UP ONE LEVEL", trimText(diskPath, 22), false, "DISK_UP");
            button(c, refresh, "SMB3", "REFRESH", diskState + " • TAILNET", true, "DISK_REFRESH");
            button(c, backup, "READ", "OLED LIBRARY", "READER + BACKUP", false, "DISK_BACKUP");
            float colW = (right - listLeft - gap) / 2f, rowH = dp(42);
            type(7, diskLoading ? mint : soft, false); c.drawText(trimText(diskMessage, 68), listLeft, top + dp(10), paint);
            top += dp(16);
            float listTop = top;
            if (diskItems.isEmpty()) { type(9, soft, false); c.drawText(diskMessage, listLeft, top + dp(28), paint); }
            else {
                c.save(); c.clipRect(listLeft, listTop, right, bottom);
                for (int i = 0; i < diskItems.size(); i++) {
                    int row = i / 2, colIndex = i % 2; float x = listLeft + colIndex * (colW + gap), y = listTop + row * rowH - diskScroll;
                    if (y + rowH < listTop || y > bottom) continue;
                    RectF r = new RectF(x, y, x + colW, y + rowH - dp(3)); box(c, r, 7, panel, line);
                    String item = diskItems.get(i); type(7, item.startsWith("[D]") ? mint : soft, item.startsWith("[D]")); c.drawText(trimText(item, 34), x + dp(9), y + dp(25), paint);
                    if (r.top >= listTop && r.bottom <= bottom) addHit(r, diskItemAction(item));
                }
                c.restore();
            }
        }

        void drawHelp(Canvas c) {
            float left = dp(16), right = getWidth() - dp(16);
            type(17, mint, true); c.drawText("HELP // START HERE", left, dp(88), paint);
            String[] lines = {
                    "CODEX  → Mac'teki gerçek Codex CLI; API kullanmaz.",
                    "LOLILE → smb://lolile/kurek; Tailscale + SMB3.",
                    "LOCAL  → Telefonda Debian Linux ortamı.",
                    "APPS   → Tüm uygulamalar; dokun, ara, kaydır.",
                    "VAULT  → Kritik komutlar için 90 sn biyometrik izin.",
                    "MESH   → İnternet portu yok; Tailnet cihazları erişir.",
                    "MAIL   → Gmail + Thunderbird salt okunur özet.",
                    "OLED   → Arayüz kayar; 2/5 dk kararır, 10 dk siyah olur."
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
                    {"UP", "UPDATE", "CHECK_UPDATE"}, {"MIC", "DAAK INBOX", "DICTATE"},
                    {"ALM", "FOSSIFY CLOCK", "PKG:org.fossify.clock"}, {"ZZZ", "SLEEP TRACKER", "PKG:hu.vmiklos.plees_tracker"},
                    {"SND", "NOTIFY SOUND", "SOUND"}, {"NLS", "NOTIFY ACCESS", "SET:MAILACCESS"}
            };
            float half = (right - left - gap) / 2f, top = dp(124), h = dp(58);
            for (int i = 0; i < tiles.length; i++) {
                int row = i / 2, col = i % 2;
                RectF r = new RectF(left + col * (half + gap), top + row * (h + gap),
                        left + col * (half + gap) + half, top + row * (h + gap) + h);
                button(c, r, tiles[i][0], tiles[i][1], i == 0 ? meshIp : "OPEN PANEL", false, tiles[i][2]);
            }
            RectF refresh = new RectF(left, top + dp(402), right, top + dp(460));
            button(c, refresh, "SCAN", "REFRESH NODE STATUS", "NO CHANGES • READ ONLY", true, "REFRESH");
        }

        void drawDisk(Canvas c) {
            float left = dp(16), right = getWidth() - dp(16);
            type(17, mint, true); c.drawText("LOLILE // " + trimText(diskPath, 27), left, dp(88), paint);
            type(7, soft, false); c.drawText("TAILSCALE • SMB3 ENCRYPTED • " + diskState, left, dp(107), paint);
            float gap = dp(7), third = (right - left - gap * 2f) / 3f;
            RectF back = new RectF(left, dp(120), left + third, dp(180));
            RectF refresh = new RectF(left + third + gap, dp(120), left + third * 2f + gap, dp(180));
            RectF backup = new RectF(left + third * 2f + gap * 2f, dp(120), right, dp(180));
            button(c, back, "BACK", "UP ONE LEVEL", "NATIVE BROWSER", false, "DISK_UP");
            button(c, refresh, "SMB3", "REFRESH", "PRIVATE TAILNET", true, "DISK_REFRESH");
            button(c, backup, "READ", "OLED BOOKS", "READER + BACKUP", false, "DISK_BACKUP");
            type(7, diskLoading ? mint : soft, false); c.drawText(trimText(diskMessage, 48), left, dp(199), paint);
            float listTop = dp(210), listBottom = getHeight() - dp(96), y = listTop - diskScroll;
            if (diskItems.isEmpty()) {
                type(9, soft, false); c.drawText(diskMessage, left, y + dp(24), paint);
            } else {
                c.save(); c.clipRect(left, listTop, right, listBottom);
                for (int i = 0; i < diskItems.size(); i++) {
                    RectF row = new RectF(left, y, right, y + dp(43));
                    if (row.bottom < listTop || row.top > listBottom) { y += dp(46); continue; }
                    if (i % 2 == 0) { paint.setStyle(Paint.Style.FILL); paint.setColor(panel); c.drawRect(row, paint); }
                    String item = diskItems.get(i);
                    type(9, item.startsWith("[D]") ? mint : soft, item.startsWith("[D]"));
                    c.drawText(item, left + dp(10), y + dp(27), paint);
                    if (row.top >= listTop && row.bottom <= listBottom) addHit(row, diskItemAction(item));
                    y += dp(46);
                }
                c.restore();
            }
            type(7, ghost, false); c.drawText("↕ kaydır • klasör → gez • dosya → stream önizle / indir", left, getHeight() - dp(91), paint);
        }

        void refreshDiskIndex() {
            diskLoading = true;
            diskRequestToken = System.currentTimeMillis();
            final long request = diskRequestToken;
            diskMessage = "Refreshing over Tailnet...";
            message = "LOLILE INDEX REFRESHING"; invalidate();
            String encoded = Base64.encodeToString(diskPath.getBytes(Charset.forName("UTF-8")), Base64.NO_WRAP);
            String target = "/sdcard/Download/daak-lolile-list.txt";
            String command = "exec ~/.shortcuts/lolile-list " + encoded + " " + request + " " + target;
            runTermuxRaw(command, true, null);
            handler.postDelayed(() -> pollDiskIndex(request, 0), 1000L);
        }

        void loadCachedDiskIndex() {
            readDiskIndex(0L, false);
            if (diskItems.isEmpty()) loadPrivateDiskCache();
        }

        void loadPrivateDiskCache() {
            try {
                SharedPreferences prefs = getSharedPreferences(NodeStore.PREFS, 0);
                String cachedPath = prefs.getString("kurek_cache_path", DISK_ROOT);
                JSONArray cached = new JSONArray(prefs.getString("kurek_cache_items", "[]"));
                if (cachedPath.equals(DISK_ROOT) || cachedPath.startsWith(DISK_ROOT + "/")) diskPath = cachedPath;
                if (cached.length() > 0) {
                    diskItems.clear();
                    for (int i = 0; i < cached.length(); i++) diskItems.add(cached.getString(i));
                    diskMessage = diskItems.size() + " items • cached";
                }
            } catch (Exception ignored) { }
        }

        void persistPrivateDiskCache(String path, List<String> items) {
            try {
                JSONArray cached = new JSONArray();
                for (String item : items) cached.put(item);
                getSharedPreferences(NodeStore.PREFS, 0).edit()
                        .putString("kurek_cache_path", path)
                        .putString("kurek_cache_items", cached.toString()).apply();
            } catch (Exception ignored) { }
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
            String failureDetail = "";
            boolean complete = !requireComplete;
            boolean failed = false;
            try {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String lineText;
                while ((lineText = reader.readLine()) != null) {
                    if (lineText.startsWith("[P] ")) loadedPath = lineText.substring(4).trim();
                    else if (lineText.startsWith("[OK] ")) {
                        if (lineText.equals("[OK] " + expectedToken)) complete = true;
                    }
                    else if (lineText.startsWith("[ERR] ")) {
                        String marker = "[ERR] " + expectedToken;
                        if (lineText.startsWith(marker)) {
                            complete = true; failed = true;
                            failureDetail = lineText.substring(marker.length()).trim();
                        }
                    }
                    else if (lineText.trim().length() > 0) loaded.add(lineText.trim());
                }
                reader.close();
                if (complete || !requireComplete) {
                    if (loadedPath != null && (loadedPath.equals(DISK_ROOT) || loadedPath.startsWith(DISK_ROOT + "/"))) diskPath = loadedPath;
                    if (!failed) {
                        diskItems.clear(); diskItems.addAll(loaded);
                    }
                }
            } catch (Exception ignored) { return false; }
            if (!complete) return false;
            diskLoading = false;
            if (failed) {
                String detail = failureDetail.length() == 0 ? "offline" : trimText(failureDetail, 44);
                diskMessage = diskItems.isEmpty() ? "SMB3 failed • " + detail : "Refresh failed • cache • " + detail;
                message = "LOLILE INDEX ERROR";
            } else {
                diskMessage = loaded.isEmpty() ? "Folder is empty" : loaded.size() + " items • live";
                message = "LOLILE INDEX READY";
                if (loadedPath != null) persistPrivateDiskCache(loadedPath, loaded);
            }
            invalidate(); return true;
        }

        String diskItemAction(String item) {
            String snapshot = diskPath + "\u0000" + item;
            return "DISK_ITEM64:" + Base64.encodeToString(snapshot.getBytes(Charset.forName("UTF-8")),
                    Base64.NO_WRAP | Base64.URL_SAFE);
        }

        void openDiskItemSnapshot(String encoded) {
            final String snapshot;
            try {
                snapshot = new String(Base64.decode(encoded, Base64.NO_WRAP | Base64.URL_SAFE),
                        Charset.forName("UTF-8"));
            } catch (RuntimeException error) { return; }
            int separator = snapshot.indexOf('\u0000');
            if (separator < 0) return;
            String sourcePath = snapshot.substring(0, separator);
            String item = snapshot.substring(separator + 1);
            if (!(sourcePath.equals(DISK_ROOT) || sourcePath.startsWith(DISK_ROOT + "/")) ||
                    !(item.startsWith("[D] ") || item.startsWith("[F] "))) return;
            String name = item.length() > 4 ? item.substring(4) : "";
            if (item.startsWith("[D] ")) {
                diskRequestToken++;
                diskPath = sourcePath + (sourcePath.endsWith("/") ? "" : "/") + name;
                diskItems.clear();
                diskScroll = 0;
                refreshDiskIndex();
            } else showDiskFileActions(sourcePath, name);
        }

        void showDiskFileActions(final String sourcePath, final String name) {
            AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                    .setTitle("KUREK // " + trimText(name, 38))
                    .setMessage("Önizleme dosyayı telefonda kalıcı olarak kaydetmez; veri yalnız görüntülenirken Tailnet üzerinden akar.")
                    .setPositiveButton("ÖNİZLE", (d, which) -> previewDiskFile(sourcePath, name))
                    .setNeutralButton("İNDİR", (d, which) -> downloadDiskFile(sourcePath, name))
                    .setNegativeButton("İPTAL", null).create();
            showDaakDialog(dialog);
        }

        void previewDiskFile(String sourcePath, String name) {
            String fullPath = sourcePath + (sourcePath.endsWith("/") ? "" : "/") + name;
            String encoded = Base64.encodeToString(fullPath.getBytes(Charset.forName("UTF-8")), Base64.NO_WRAP);
            long token = System.currentTimeMillis();
            diskMessage = "Streaming preview • " + trimText(name, 24);
            message = "KUREK FILE // PREVIEW"; invalidate();
            runTermuxRaw("exec ~/.shortcuts/lolile-preview " + encoded + " " + token, true, null);
            handler.postDelayed(() -> pollDiskPreview(token, name, 0), 500L);
        }

        void pollDiskPreview(final long token, final String name, final int attempt) {
            File status = new File("/sdcard/Download/daak-lolile-preview-" + token + ".txt");
            if (status.isFile() && status.length() > 0) {
                try {
                    BufferedReader reader = new BufferedReader(new FileReader(status));
                    String result = reader.readLine(); reader.close(); status.delete();
                    if (result != null && result.startsWith("[OK] ")) {
                        Uri url = Uri.parse(result.substring(5).trim());
                        if (!"http".equals(url.getScheme()) || !"127.0.0.1".equals(url.getHost()) ||
                                url.getPort() < 1024 || url.getPort() > 65535 ||
                                url.getPath() == null || !url.getPath().startsWith("/p/")) {
                            throw new IllegalStateException("unsafe preview URL");
                        }
                        diskMessage = "Preview ready • no local copy";
                        message = "KUREK FILE // STREAMING"; invalidate();
                        Intent view = new Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        String previewBrowser = getPackageManager().getLaunchIntentForPackage("org.mozilla.fennec_fdroid") != null
                                ? "org.mozilla.fennec_fdroid"
                                : getPackageManager().getLaunchIntentForPackage("org.mozilla.firefox") != null
                                ? "org.mozilla.firefox" : "";
                        if (previewBrowser.length() == 0) {
                            toast("Stream önizleme için Fennec veya Firefox gerekli");
                            diskMessage = "Preview browser missing • install Fennec"; invalidate();
                            return;
                        }
                        view.setPackage(previewBrowser);
                        noteExternalLaunch(previewBrowser);
                        startActivity(view);
                    } else {
                        diskMessage = "Preview failed • LOLILE offline";
                        message = "KUREK PREVIEW // ERROR"; invalidate();
                    }
                    return;
                } catch (Exception ignored) {
                    status.delete();
                    diskMessage = "Preview rejected • tap to retry";
                    message = "KUREK PREVIEW // ERROR"; invalidate();
                    return;
                }
            }
            if (attempt < 39) handler.postDelayed(() -> pollDiskPreview(token, name, attempt + 1), 500L);
            else {
                diskMessage = "Preview timeout • LOLILE offline";
                message = "KUREK PREVIEW // TIMEOUT"; invalidate();
            }
        }

        void downloadDiskFile(String sourcePath, String name) {
            String fullPath = sourcePath + (sourcePath.endsWith("/") ? "" : "/") + name;
            String encoded = Base64.encodeToString(fullPath.getBytes(Charset.forName("UTF-8")), Base64.NO_WRAP);
            long token = System.currentTimeMillis();
            diskMessage = "Downloading " + trimText(name, 25) + "...";
            message = "KUREK FILE // DOWNLOADING"; invalidate();
            runTermuxRaw("exec ~/.shortcuts/lolile-fetch " + encoded + " " + token, true, null);
            handler.postDelayed(() -> pollDiskDownload(token, name, 0), 1000L);
        }

        void pollDiskDownload(final long token, final String name, final int attempt) {
            File status = new File("/sdcard/Download/daak-lolile-fetch-" + token + ".txt");
            if (status.isFile() && status.length() > 0) {
                try {
                    BufferedReader reader = new BufferedReader(new FileReader(status));
                    String result = reader.readLine(); reader.close(); status.delete();
                    if (result != null && result.startsWith("[OK] ")) {
                        diskMessage = "Downloaded • opening " + trimText(name, 22);
                        message = "KUREK FILE // READY"; invalidate();
                        openDownloadedFile(result.substring(5).trim());
                    } else {
                        diskMessage = "Download failed • tap file to retry";
                        message = "KUREK FILE // ERROR"; invalidate();
                    }
                    return;
                } catch (Exception ignored) { }
            }
            if (attempt < 50) handler.postDelayed(() -> pollDiskDownload(token, name, attempt + 1), 1000L);
            else {
                diskMessage = "Download timeout • tap file to retry";
                message = "KUREK FILE // TIMEOUT"; invalidate();
            }
        }

        String mimeForFile(String path) {
            int dot = path.lastIndexOf('.');
            String extension = dot >= 0 ? path.substring(dot + 1).toLowerCase(Locale.US) : "";
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime != null) return mime;
            if (extension.equals("md") || extension.equals("log") || extension.equals("json") || extension.equals("csv")) return "text/plain";
            return "application/octet-stream";
        }

        void openDownloadedFile(String absolutePath) {
            if (!absolutePath.startsWith("/sdcard/Download/DAAK-Kurek/")) {
                toast("Güvensiz dosya yolu reddedildi"); return;
            }
            File downloaded = new File(absolutePath);
            Uri document = new Uri.Builder().scheme("content").authority(KurekFileProvider.AUTHORITY)
                    .appendPath(downloaded.getName()).build();
            Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(document, mimeForFile(absolutePath));
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            if (getPackageManager().getLaunchIntentForPackage("me.zhanghai.android.files") != null)
                view.setPackage("me.zhanghai.android.files");
            try { startActivity(view); }
            catch (RuntimeException error) {
                toast("Görüntüleyici bulunamadı • Material Files açılıyor");
                launchPackage("me.zhanghai.android.files");
            }
        }

        void diskUp() {
            String current = diskPath;
            while (current.endsWith("/") && current.length() > DISK_ROOT.length()) current = current.substring(0, current.length() - 1);
            int slash = current.lastIndexOf('/');
            diskPath = slash < DISK_ROOT.length() ? DISK_ROOT : current.substring(0, slash);
            diskScroll = 0;
            refreshDiskIndex();
        }

        void openDiskTerminal() {
            String encoded = Base64.encodeToString(diskPath.getBytes(Charset.forName("UTF-8")), Base64.NO_WRAP);
            runTermux("exec ~/.shortcuts/lolile-disk " + encoded, "LOLILE SMB3");
        }

        void showBookBackupPanel() {
            File status = new File("/sdcard/Documents/DAAK-Vault/Kitap Backup Status.md");
            String detail = "Henüz yerel yedek oluşturulmadı. Kaynak yaklaşık 2.79 GiB; otomatik senkron yalnız Wi-Fi ve şarj sırasında çalışır.";
            if (status.isFile()) {
                try {
                    BufferedReader reader = new BufferedReader(new FileReader(status));
                    StringBuilder text = new StringBuilder();
                    String lineText;
                    while ((lineText = reader.readLine()) != null) {
                        if (lineText.startsWith("- ")) text.append(lineText.substring(2)).append('\n');
                    }
                    reader.close();
                    if (text.length() > 0) detail = text.toString().trim();
                } catch (Exception ignored) { }
            }
            AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                    .setTitle("KİTAP MERAKLISINA // OLED")
                    .setMessage("Book's Story • tam siyah okuma profili\n" +
                            "EPUB hemen açılır • PDF ilk metin çıkarımı 30–60 sn sürebilir\n\n" + detail)
                    .setPositiveButton("OKUYUCU", (d, which) -> openBookReader())
                    .setNeutralButton("YEDEKLE", (d, which) -> {
                        diskMessage = "Book backup started • Wi-Fi / SMB3";
                        message = "KUREK BOOKS // LOCAL SYNC"; invalidate();
                        runTermuxRaw("exec ~/.shortcuts/lolile-books-sync", true, null);
                        toast("Yerel kitap yedeği başladı");
                    })
                    .setNegativeButton("DOSYALAR", (d, which) -> openBookBackupFolder()).create();
            showDaakDialog(dialog);
        }

        void openBookReader() {
            if (getPackageManager().getLaunchIntentForPackage(BOOK_READER_PACKAGE) == null) {
                toast("Book's Story bulunamadı • F-Droid üzerinden kur");
                return;
            }
            launchPackage(BOOK_READER_PACKAGE);
        }

        void openBookBackupFolder() {
            Uri folder = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
                    "primary:Documents/DAAK-Vault/Kitap Meraklısına");
            Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(folder, "inode/directory")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                            Intent.FLAG_ACTIVITY_NEW_TASK);
            if (getPackageManager().getLaunchIntentForPackage("me.zhanghai.android.files") != null)
                view.setClassName("me.zhanghai.android.files", "me.zhanghai.android.files.filelist.FileListActivity");
            try {
                noteExternalLaunch("me.zhanghai.android.files");
                startActivity(view);
            } catch (RuntimeException error) {
                launchPackage("me.zhanghai.android.files");
                toast("Documents / DAAK-Vault / Kitap Meraklısına");
            }
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
                        (i == 2 && mode == CODEX_VIEW) || (i == 3 && mode == DISK_VIEW) || (i == 4 && mode == HELP);
                type(7.5f, active ? mint : soft, true); center(c, labels[i], r.centerX(), top + dp(34));
                if (active) {
                    float halfLine = Math.min(paint.measureText(labels[i]) * 0.58f, cell * 0.30f);
                    paint.setStyle(Paint.Style.FILL); paint.setColor(mint);
                    c.drawRoundRect(new RectF(r.centerX() - halfLine, top + dp(43),
                            r.centerX() + halfLine, top + dp(45)), dp(1), dp(1), paint);
                }
                addHit(r, actions[i]);
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
                boolean selected = (i == 0 && mode == HOME) || (i == 1 && mode == APPS) ||
                        (i == 2 && mode == CODEX_VIEW) || (i == 3 && mode == DISK_VIEW) || (i == 4 && mode == HELP);
                type(7, selected ? mint : soft, true); center(c, labels[i], r.centerX(), top + dp(27));
                if (selected) {
                    float halfLine = Math.min(paint.measureText(labels[i]) * 0.58f, cell * 0.30f);
                    paint.setStyle(Paint.Style.FILL); paint.setColor(mint);
                    c.drawRoundRect(new RectF(r.centerX() - halfLine, top + dp(35),
                            r.centerX() + halfLine, top + dp(37)), dp(1), dp(1), paint);
                }
                addHit(r, actions[i]);
            }
        }

        void showSearch() {
            final EditText input = new EditText(MainActivity.this);
            input.setSingleLine(true); input.setText(query); input.setSelectAllOnFocus(true);
            styleInput(input);
            AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Uygulama ara").setView(input)
                    .setPositiveButton("ARA", (d, which) -> { query = input.getText().toString(); applyFilter(); })
                    .setNegativeButton("TEMİZLE", (d, which) -> { query = ""; applyFilter(); }).create();
            dialog.setOnShowListener(d -> {
                input.requestFocus();
                dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            });
            showDaakDialog(dialog);
        }

        void styleInput(EditText input) {
            input.setTextColor(soft); input.setHintTextColor(ghost);
            input.setBackgroundTintList(ColorStateList.valueOf(mintDim));
            input.setPadding(dpInt(12), dpInt(8), dpInt(12), dpInt(8));
        }

        int dpInt(float value) { return Math.round(dp(value)); }

        void showDaakDialog(AlertDialog dialog) {
            dialog.setOnDismissListener(ignored -> hideSystemBars());
            dialog.show();
            if (dialog.getWindow() != null)
                dialog.getWindow().getDecorView().setBackgroundColor(panel);
            int[] buttons = {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE};
            for (int which : buttons) if (dialog.getButton(which) != null)
                dialog.getButton(which).setTextColor(which == AlertDialog.BUTTON_POSITIVE ? mint : soft);
            ListView list = dialog.getListView();
            if (list != null) {
                list.setBackgroundColor(panel);
                list.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                    @Override public void onChildViewAdded(View parent, View child) { styleDialogListChild(child); }
                    @Override public void onChildViewRemoved(View parent, View child) { }
                });
                list.post(() -> {
                    for (int i = 0; i < list.getChildCount(); i++) styleDialogListChild(list.getChildAt(i));
                });
            }
        }

        void styleDialogListChild(View child) {
            if (child instanceof TextView) ((TextView)child).setTextColor(soft);
            if (child instanceof ViewGroup) {
                ViewGroup group = (ViewGroup)child;
                for (int i = 0; i < group.getChildCount(); i++) styleDialogListChild(group.getChildAt(i));
            }
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
            ArrayList<String> rows = new ArrayList<String>();
            rows.add(NodeStore.whatsAppBridgeRow(MainActivity.this));
            List<String> tasks = NodeStore.recentWhatsAppTasks(MainActivity.this, 7);
            if (tasks.isEmpty()) rows.add("Henüz görev cümlesi yakalanmadı");
            else rows.addAll(tasks);
            openInfoPanel("WHATSAPP", rows);
        }

        void showWhatsAppSettings() {
            SharedPreferences prefs = getSharedPreferences(NodeStore.PREFS, 0);
            final EditText input = new EditText(MainActivity.this);
            input.setSingleLine(false); input.setMinLines(2);
            input.setHint("Yok sayılacak kişi/grup adları, virgülle");
            input.setText(prefs.getString("ignored_whatsapp_senders", ""));
            styleInput(input);
            boolean enabled = NodeStore.whatsAppAutomationEnabled(MainActivity.this);
            AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                    .setTitle("AUTO TASKS // " + (enabled ? "ON" : "OFF"))
                    .setMessage("Yalnız görev belirten bildirimler alınır; mesaj gönderilmez.")
                    .setView(input)
                    .setPositiveButton("KAYDET", (d, which) -> prefs.edit()
                            .putString("ignored_whatsapp_senders", input.getText().toString()).apply())
                    .setNeutralButton(enabled ? "KAPAT" : "AÇ", (d, which) -> prefs.edit()
                            .putBoolean("whatsapp_tasks_enabled", !enabled).apply())
                    .setNegativeButton("İPTAL", null).create();
            showDaakDialog(dialog);
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
            final String host = nodeConfig("lolile_host", "lolile");
            message = "DAAK LOLILE // CHECKING"; invalidate();
            new Thread(() -> {
                final boolean online = canConnect(host, 17657);
                handler.post(() -> {
                    if (destroyed) return;
                    if (online) {
                        try {
                            message = "DAAK LOLILE // ONLINE"; invalidate();
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://" + host + ":17657/")));
                        } catch (RuntimeException error) { toast("daakLOLILE paneli açılamadı"); }
                        return;
                    }
                    message = "DAAK LOLILE // OFFLINE"; invalidate();
                    AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                            .setTitle("DAAK LOLILE // OFFLINE")
                            .setMessage("LOLİLE paneli 17657 portunda yanıt vermiyor. Kurek önbelleğini gezebilir veya Chrome Remote Desktop ile bilgisayarı uyandırabilirsin.")
                            .setPositiveButton("KUREK", (d, which) -> {
                                showMode(DISK_VIEW); loadCachedDiskIndex(); refreshDiskIndex();
                            })
                            .setNeutralButton("REMOTE", (d, which) -> showMode(REMOTE))
                            .setNegativeButton("KAPAT", null).create();
                    showDaakDialog(dialog);
                });
            }, "node-lolile-hub").start();
        }

        void showMailPanel() {
            ArrayList<String> rows = new ArrayList<String>(NodeStore.mailBridgeRows(MainActivity.this));
            List<String> messages = NodeStore.recentMail(MainActivity.this,
                    System.currentTimeMillis() - 24L * 60L * 60L * 1000L, 8);
            if (messages.isEmpty()) rows.add("Son 24 saatte yeni mail yok • rahat ol");
            else rows.addAll(messages);
            mailViewedAt = System.currentTimeMillis();
            openInfoPanel("MAIL", rows);
        }

        void showMailSources() {
            final String[] sources = {"GMAIL // READ ONLY BRIDGE", "THUNDERBIRD // READ ONLY BRIDGE"};
            new AlertDialog.Builder(MainActivity.this).setTitle("MAIL APPS // GÖNDERME YOK")
                    .setItems(sources, (choiceDialog, which) -> {
                        if (which == 0) launchOrStore("com.google.android.gm");
                        else launchOrStore("net.thunderbird.android");
                    }).setNegativeButton("KAPAT", null).show();
        }

        void showMusicPanel() {
            ArrayList<String> rows = new ArrayList<String>();
            updateMediaStatus();
            rows.add(mediaTitle);
            rows.add(mediaSource);
            rows.add(getPackageManager().getLaunchIntentForPackage("com.termux") == null
                    ? "AIRPLAY → MAC • FREE BRIDGE KURULUMU EKSİK"
                    : "AIRPLAY → MAC • FREE RAOP READY");
            rows.add("AUXIO • USER FILES • FULL OFFLINE");
            rows.add("YT MUSIC DOWNLOADS • STAY INSIDE OFFICIAL APP");
            openInfoPanel("MUSIC", rows);
        }

        MediaController activeMediaController() {
            try {
                MediaSessionManager manager = (MediaSessionManager)getSystemService(MEDIA_SESSION_SERVICE);
                if (manager == null) return null;
                List<MediaController> sessions = manager.getActiveSessions(
                        new ComponentName(MainActivity.this, MailNotificationListener.class));
                MediaController fallback = null;
                for (MediaController controller : sessions) {
                    if (fallback == null) fallback = controller;
                    PlaybackState state = controller.getPlaybackState();
                    if (state != null && state.getState() == PlaybackState.STATE_PLAYING) return controller;
                }
                return fallback;
            } catch (SecurityException ignored) { return null; }
        }

        void updateMediaStatus() {
            MediaController controller = activeMediaController();
            if (controller == null) {
                mediaTitle = "Aktif oynatma yok"; mediaSource = "AUXIO OFFLINE READY"; return;
            }
            MediaMetadata metadata = controller.getMetadata();
            String title = metadata == null ? "Aktif medya" : metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = metadata == null ? "" : metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            mediaTitle = (artist == null || artist.trim().length() == 0 ? "" : artist + " • ")
                    + (title == null || title.trim().length() == 0 ? "Aktif medya" : title);
            try {
                ApplicationInfo info = getPackageManager().getApplicationInfo(controller.getPackageName(), 0);
                mediaSource = getPackageManager().getApplicationLabel(info).toString().toUpperCase(Locale.US);
            } catch (Exception ignored) { mediaSource = controller.getPackageName(); }
        }

        void toggleMediaPlayback() {
            MediaController controller = activeMediaController();
            if (controller == null) { launchPackage("org.oxycblt.auxio"); return; }
            PlaybackState state = controller.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) controller.getTransportControls().pause();
            else controller.getTransportControls().play();
            handler.postDelayed(this::showMusicPanel, 250L);
        }

        void skipMediaNext() {
            MediaController controller = activeMediaController();
            if (controller == null) { launchPackage("org.oxycblt.auxio"); return; }
            controller.getTransportControls().skipToNext();
            handler.postDelayed(this::showMusicPanel, 250L);
        }

        void showMusicSources() {
            boolean automatic = NodeStore.oledLockPlayerEnabled(MainActivity.this);
            final String[] sources = {"DAAK OLED PLAYER // ŞİMDİ AÇ",
                    "OTOMATİK KİLİT PLAYER // " + (automatic ? "AÇIK" : "KAPALI"),
                    "AIRPLAY → MAC // DOSYA SEÇ", "AUXIO // TAM OFFLINE",
                    "YOUTUBE MUSIC // RESMÎ OFFLINE", "SPOTIFY // RESMÎ"};
            AlertDialog dialog = new AlertDialog.Builder(MainActivity.this).setTitle("MUSIC // OLED PLAYER")
                    .setItems(sources, (choiceDialog, which) -> {
                        if (which == 0) openOledPlayer();
                        else if (which == 1) {
                            boolean enabled = NodeStore.toggleOledLockPlayer(MainActivity.this);
                            toast("Otomatik OLED kilit player " + (enabled ? "açıldı" : "kapatıldı"));
                        } else if (which == 2) startAirplayPicker();
                        else if (which == 3) launchPackage("org.oxycblt.auxio");
                        else if (which == 4) launchOrStore("com.google.android.apps.youtube.music");
                        else launchOrStore("com.spotify.music");
                    }).setNegativeButton("KAPAT", null).create();
            showDaakDialog(dialog);
        }

        void openOledPlayer() {
            if (activeMediaController() == null) {
                toast("Önce Auxio veya başka bir müzik kaynağında oynatmayı başlat");
                launchPackage("org.oxycblt.auxio");
                return;
            }
            startActivity(new Intent(MainActivity.this, OledPlayerActivity.class));
        }

        void launchOrStore(String packageName) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch != null) { launchPackage(packageName); return; }
            try {
                Intent store = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + packageName));
                store.setPackage("com.android.vending"); startActivity(store);
            } catch (RuntimeException error) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + packageName)));
            }
        }

        void showPowerPanel(String device) {
            ArrayList<String> rows = new ArrayList<String>();
            String key = device.equals("lolile") ? "lolile_wol_mac" : "mac_wol_mac";
            String configured = nodeConfig(key, "").trim();
            rows.add(configured.length() == 0 ? "WOL MAC NOT CONFIGURED • config.properties" : "WOL MAC CONFIGURED • PRIVATE DEVICE CONFIG");
            rows.add("WAKE sends a LAN magic packet through Termux");
            rows.add("SHUTDOWN requires biometric approval + key-only SSH");
            openInfoPanel(device.equals("lolile") ? "POWER_LOLILE" : "POWER_MAC", rows);
        }

        void runPower(String operation) {
            message = "POWER // " + operation.toUpperCase(Locale.US); invalidate();
            runTermuxRaw("exec ~/.shortcuts/daak-power " + operation, true, null);
            toast("Power command sent: " + operation);
        }

        void confirmShutdown(String device) {
            AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                    .setTitle("DAAK // POWER")
                    .setMessage((device.equals("lolile") ? "Lolie Windows" : "Mac") + " tamamen kapatılsın mı?")
                    .setPositiveButton("SHUTDOWN", (d, which) -> guarded(() -> runPower(device + "-shutdown")))
                    .setNegativeButton("İPTAL", null).create();
            showDaakDialog(dialog);
        }

        void showMailFilters() {
            SharedPreferences prefs = getSharedPreferences(NodeStore.PREFS, 0);
            final EditText input = new EditText(MainActivity.this);
            input.setText(prefs.getString("ignored_senders", "spam,junk,newsletter,unsubscribe,no-reply,noreply"));
            input.setSingleLine(false); input.setMinLines(3);
            styleInput(input);
            AlertDialog dialog = new AlertDialog.Builder(MainActivity.this).setTitle("Gizlenecek gönderen/kelimeler")
                    .setMessage("Virgülle ayır. Eşleşen mail DAAK özetine alınmaz.")
                    .setView(input)
                    .setPositiveButton("KAYDET", (d, which) -> prefs.edit().putString("ignored_senders", input.getText().toString()).apply())
                    .setNegativeButton("İPTAL", null).create();
            showDaakDialog(dialog);
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

        void applyNotificationSound(String key, String label) {
            if (!Settings.System.canWrite(MainActivity.this)) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                } catch (RuntimeException ignored) { openSettings(Settings.ACTION_SOUND_SETTINGS); }
                toast("DAAK NODE için sistem ayarı izni gerekli");
                return;
            }
            if (!NodeStore.selectSound(MainActivity.this, key, true)) {
                toast("Bildirim sesi değiştirilemedi"); return;
            }
            Uri sound = NodeStore.soundUri(MainActivity.this, key);
            if (previewRingtone != null) previewRingtone.stop();
            previewRingtone = RingtoneManager.getRingtone(MainActivity.this, sound);
            if (previewRingtone != null) previewRingtone.play();
            message = "SOUND // " + label.toUpperCase(Locale.US); invalidate();
            toast(label + " seçildi ve önizleniyor");
        }

        void showNotificationSoundPanel() {
            final String[] labels = {"Terminal Tick — yüksek/kısa", "DAAK Pulse — yüksek/yumuşak", "Deep Node — yüksek/koyu"};
            final String[] keys = {"terminal", "pulse", "deep"};
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("DAAK // BİLDİRİM SESİ")
                    .setItems(labels, (dialog, which) -> applyNotificationSound(keys[which], labels[which]))
                    .setNeutralButton("SİSTEM SESLERİ", (dialog, which) -> openSettings(Settings.ACTION_SOUND_SETTINGS))
                    .setNegativeButton("KAPAT", null).show();
        }

        void showRememberPanel() {
            refreshRemember();
            openInfoPanel("REMEMBER", new ArrayList<String>(rememberItems));
        }

        void openInfoPanel(String kind, List<String> rows) {
            panelKind = kind;
            panelItems.clear(); panelItems.addAll(rows);
            panelScroll = 0;
            mode = INFO_PANEL;
            if (!contentScroller.isFinished()) contentScroller.abortAnimation();
            invalidate();
        }

        void closeInfoPanel() {
            panelKind = ""; panelItems.clear(); panelScroll = 0; mailViewedAt = 0L;
        }

        void panelPrimary() {
            if (panelKind.equals("MAIL")) showMailSources();
            else if (panelKind.equals("WHATSAPP")) launchPackage("com.whatsapp");
            else if (panelKind.equals("MUSIC")) toggleMediaPlayback();
            else if (panelKind.equals("POWER_LOLILE")) guarded(() -> runPower("lolile-wake"));
            else if (panelKind.equals("POWER_MAC")) guarded(() -> runPower("mac-wake"));
            else showRememberCapture();
        }

        void panelSecondary() {
            if (panelKind.equals("MAIL")) showMailFilters();
            else if (panelKind.equals("WHATSAPP")) showWhatsAppSettings();
            else if (panelKind.equals("MUSIC")) skipMediaNext();
            else if (panelKind.equals("POWER_LOLILE")) confirmShutdown("lolile");
            else if (panelKind.equals("POWER_MAC")) confirmShutdown("mac");
            else { refreshRemember(); handler.postDelayed(this::showRememberPanel, 650L); }
        }

        void panelTertiary() {
            if (panelKind.equals("MUSIC")) showMusicSources();
            else showMode(HOME);
        }

        void showRememberCapture() {
            final EditText input = new EditText(MainActivity.this);
            input.setSingleLine(false); input.setMinLines(3); input.setHint("Aklına geleni yakala...");
            styleInput(input);
            AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                    .setTitle("REMEMBER // QUICK CAPTURE")
                    .setView(input)
                    .setPositiveButton("MAC'E SENKRONLA", (d, which) -> addRememberNote(input.getText().toString()))
                    .setNegativeButton("İPTAL", null).create();
            showDaakDialog(dialog);
        }

        void launchObsidian() {
            openObsidianFile("daakREMEMBER.md");
        }

        void openObsidianFile(String file) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("obsidian://open?vault=DAAK-Vault&file=" + Uri.encode(file)));
                intent.setPackage("md.obsidian"); startActivity(intent);
            } catch (RuntimeException error) { launchPackage("md.obsidian"); }
        }

        void showRmOsPanel() {
            final String[] options = {
                    "RM-OS ANA SAYFA",
                    "RM-OS CAPTURE // YENİ NOT",
                    "RM-OS SENKRON DURUMU",
                    "daakREMEMBER",
                    "DAAK CALENDAR"
            };
            new AlertDialog.Builder(MainActivity.this).setTitle("RM-OS // KNOWLEDGE FABRIC")
                    .setItems(options, (dialog, which) -> {
                        if (which == 0) openObsidianFile("RM-OS/README.md");
                        else if (which == 1) openObsidianFile("RM-OS Capture.md");
                        else if (which == 2) openObsidianFile("RM-OS Sync Status.md");
                        else if (which == 3) launchObsidian();
                        else openObsidianFile("DAAK Calendar.md");
                    })
                    .setNegativeButton("KAPAT", null).show();
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX() - burnX, y = event.getY() - burnY;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (!contentScroller.isFinished()) contentScroller.abortAnimation();
                if (velocityTracker != null) velocityTracker.recycle();
                velocityTracker = VelocityTracker.obtain(); velocityTracker.addMovement(event);
                wakeOnly = oledContentAlpha() < 255;
                lastInteraction = System.currentTimeMillis(); invalidate();
                downX = x; downY = y; lastY = y; moved = false;
                pressedRect = null; pressGlow = 0f;
                for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).rect.contains(x, y)) {
                    Hit hit = hits.get(i);
                    if (!isDockAction(hit.action)) {
                        pressedRect = new RectF(hit.rect); pressGlow = 0.42f;
                    }
                    break;
                }
                invalidate(); return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                if (velocityTracker != null) velocityTracker.addMovement(event);
                if (isScrollableMode()) {
                    float delta = lastY - y;
                    if (Math.abs(y - downY) > dp(5)) {
                        moved = true; pressedRect = null; pressGlow = 0f;
                    }
                    setActiveScroll(activeScroll() + delta);
                    lastY = y; invalidate();
                }
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_CANCEL) {
                recycleVelocity(); pressedRect = null; pressGlow = 0f; invalidate(); return true;
            }
            if (event.getAction() != MotionEvent.ACTION_UP) return true;
            if (velocityTracker != null) velocityTracker.addMovement(event);
            if (wakeOnly) { wakeOnly = false; recycleVelocity(); pressedRect = null; pressGlow = 0f; invalidate(); return true; }
            if (downY > getHeight() - dp(115) && downY - y > dp(65)) { recycleVelocity(); showMode(HOME); return true; }
            if (downX > getWidth() - dp(24) && downX - x > dp(65)) { recycleVelocity(); showMode(CONTROL); return true; }
            if (downY < dp(58) && y - downY > dp(55)) { recycleVelocity(); showMode(CONTROL); return true; }
            if (moved) {
                if (isScrollableMode() && velocityTracker != null) {
                    velocityTracker.computeCurrentVelocity(1000, 9000f);
                    float velocity = velocityTracker.getYVelocity();
                    contentScroller.fling(0, Math.round(activeScroll()), 0, Math.round(-velocity),
                            0, 0, 0, Math.round(maxActiveScroll()), 0, Math.round(dp(32)));
                    postInvalidateOnAnimation();
                }
                recycleVelocity(); pressedRect = null; pressGlow = 0f; return true;
            }
            recycleVelocity(); pressedRect = null; pressGlow = 0f;
            softHaptic();
            lastUiTouchUpAt = SystemClock.elapsedRealtime();
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit hit = hits.get(i);
                if (hit.rect.contains(x, y)) { animateHit(hit); return true; }
            }
            return true;
        }

        boolean isScrollableMode() { return mode == APPS || mode == DISK_VIEW || mode == INFO_PANEL; }
        float activeScroll() { return mode == APPS ? drawerScroll : mode == DISK_VIEW ? diskScroll : panelScroll; }
        void setActiveScroll(float value) {
            float clamped = Math.max(0f, Math.min(maxActiveScroll(), value));
            if (mode == APPS) drawerScroll = clamped;
            else if (mode == DISK_VIEW) diskScroll = clamped;
            else panelScroll = clamped;
        }
        float maxActiveScroll() {
            if (mode == APPS) {
                if (getWidth() > getHeight()) return Math.max(0, ((shownApps.size() + 1) / 2f) * dp(42) - (getHeight() - dp(172)));
                return Math.max(0, shownApps.size() * dp(49) - (getHeight() - dp(260)));
            }
            if (mode == DISK_VIEW) {
                if (getWidth() > getHeight()) return Math.max(0, ((diskItems.size() + 1) / 2f) * dp(42) - (getHeight() - dp(141)));
                return Math.max(0, diskItems.size() * dp(46) - (getHeight() - dp(306)));
            }
            float visible = getWidth() > getHeight() ? getHeight() - dp(149) : getHeight() - dp(306);
            float rowHeight = getWidth() > getHeight() ? dp(44) : dp(55);
            return Math.max(0, panelItems.size() * rowHeight - Math.max(dp(40), visible));
        }
        void recycleVelocity() {
            if (velocityTracker != null) { velocityTracker.recycle(); velocityTracker = null; }
        }

        @Override public void computeScroll() {
            if (!contentScroller.computeScrollOffset()) return;
            setActiveScroll(contentScroller.getCurrY());
            postInvalidateOnAnimation();
        }

        void handle(Hit hit) {
            String measuredAction = hit.app != null ? "PKG:" + hit.app.packageName : hit.action;
            long responseMs = lastUiTouchUpAt == 0L ? 0L : SystemClock.elapsedRealtime() - lastUiTouchUpAt;
            Log.i("DAAK_UI", "action=" + measuredAction + " response_ms=" + responseMs);
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
            else if (a.equals("RMOS")) showRmOsPanel();
            else if (a.equals("WEATHER")) showWeatherSetup();
            else if (a.equals("SOUND")) showNotificationSoundPanel();
            else if (a.equals("PINS")) showPinnedManager();
            else if (a.equals("WHATSAPP")) showWhatsAppPanel();
            else if (a.equals("MUSIC")) showMusicPanel();
            else if (a.equals("POWER_LOLILE")) showPowerPanel("lolile");
            else if (a.equals("POWER_MAC")) showPowerPanel("mac");
            else if (a.equals("PANEL_PRIMARY")) panelPrimary();
            else if (a.equals("PANEL_SECONDARY")) panelSecondary();
            else if (a.equals("PANEL_TERTIARY")) panelTertiary();
            else if (a.equals("PANEL_CLOSE")) showMode(HOME);
            else if (a.startsWith("REMEMBER_ITEM:")) showRememberItemMenu(Integer.parseInt(a.substring(14)));
            else if (a.equals("LOLILE_HUB")) guarded(() -> launchLolileHub());
            else if (a.equals("DICTATE")) startDictation();
            else if (a.equals("CHECK_UPDATE")) guarded(() -> maybeCheckUpdate(true));
            else if (a.equals("REMOTE_CONFIG")) showRemoteConfig();
            else if (a.startsWith("REMOTE_CONNECT:")) guarded(() -> connectRemote(Integer.parseInt(a.substring(15))));
            else if (a.equals("CALENDAR")) {
                if (checkSelfPermission("android.permission.READ_CALENDAR") != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{"android.permission.READ_CALENDAR"}, CALENDAR_PERMISSION_REQUEST);
                else launchOrStore("com.google.android.calendar");
            }
            else if (a.equals("DISK_REFRESH")) refreshDiskIndex();
            else if (a.equals("DISK_BACKUP")) showBookBackupPanel();
            else if (a.equals("DISK_UP")) diskUp();
            else if (a.startsWith("DISK_ITEM64:")) openDiskItemSnapshot(a.substring(12));
            else if (a.equals("DISK_TERM")) openDiskTerminal();
            else if (a.equals("CODEX")) showMode(CODEX_VIEW);
            else if (a.startsWith("CODEX_RUN:")) launchCodexWorkspace(a.substring(10), "");
            else if (a.equals("CODEX_CUSTOM")) showCodexCustomPath();
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
