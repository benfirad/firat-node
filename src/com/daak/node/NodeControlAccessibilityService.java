package com.daak.node;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.ComponentName;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A deliberately narrow system-wide control surface.
 *
 * The transparent bottom strip translates one upward gesture into HOME. For a
 * remote WOL request, Keenetic runs on an isolated virtual display. This service
 * reads only the Keenetic package's window and activates only the remembered
 * network, configured device and WOL controls. The physical display never
 * leaves DAAK Home.
 */
public final class NodeControlAccessibilityService extends AccessibilityService {
    private static final String TAG = "DAAK_CONTROL";
    // Samsung's dedicated Bixby key is reported as keyCode 1082 and scanCode 703
    // on the Exynos Galaxy S9/S9+. Match both values so firmware variants work.
    private static final int KEYCODE_SAMSUNG_BIXBY = 1082;
    private static final int SCANCODE_SAMSUNG_BIXBY = 703;
    private static final String ANDROID_AUTO_RECEIVER_PACKAGE =
            "com.andrerinas.headunitrevived";
    private static final String ANDROID_AUTO_AUTOMATION_ACTIVITY =
            "com.andrerinas.openheadunit.main.AutomationActivity";
    private static final String ANDROID_AUTO_SELF_MODE_ACTION =
            "com.andrerinas.openheadunit.ACTION_START_SELF_MODE";
    private static final String ANDROID_AUTO_DISCONNECT_ACTION =
            "com.andrerinas.openheadunit.ACTION_DISCONNECT";
    private static final long ANDROID_AUTO_ROTATION_DEBOUNCE_MS = 700L;
    // Local Self Mode needs roughly 25 seconds to tear down Android Auto's
    // loopback wireless projection before a fresh service discovery can win.
    private static final long ANDROID_AUTO_RECONNECT_DELAY_MS = 28000L;
    private static NodeControlAccessibilityService active;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private View gestureStrip;
    private WolMaskView wolMask;
    private int wolGeneration;
    private boolean wolActive;
    private boolean wolDryRun;
    private boolean wolNetworkClicked;
    private boolean wolTargetClicked;
    private boolean wolSearchFilled;
    private boolean wolButtonClicked;
    private String wolTarget;
    private long wolStartedAt;
    private boolean simPinActive;
    private boolean simPinToggleClicked;
    private boolean simPinSubmitted;
    private String simPinValue;
    private long simPinStartedAt;
    private boolean androidAutoRotationArmed;
    private boolean androidAutoReconnectInProgress;
    private int androidAutoDisplayRotation = -1;
    private final Runnable restartAndroidAutoAfterRotation = new Runnable() {
        @Override public void run() {
            if (!androidAutoRotationArmed) return;
            Log.i("DAAK_ANDROID_AUTO", "orientation changed; reconnecting projection");
            androidAutoReconnectInProgress = true;
            startAndroidAutoAction(ANDROID_AUTO_DISCONNECT_ACTION);
            handler.postDelayed(() -> {
                if (androidAutoRotationArmed &&
                        !startAndroidAutoAction(ANDROID_AUTO_SELF_MODE_ACTION)) {
                    androidAutoRotationArmed = false;
                }
                handler.postDelayed(() -> androidAutoReconnectInProgress = false, 3500L);
            }, ANDROID_AUTO_RECONNECT_DELAY_MS);
        }
    };
    private final Runnable wolPoll = new Runnable() {
        @Override public void run() {
            if (!wolActive) return;
            if (System.currentTimeMillis() - wolStartedAt >= 50000L) {
                completeHeadlessWol(wolTargetClicked ? "WOL_BUTTON_UNAVAILABLE" :
                        wolNetworkClicked ? "DEVICE_NOT_FOUND" : "CLOUD_OFFLINE");
                return;
            }
            handler.postDelayed(this, 500L);
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            info.packageNames = new String[]{"com.keenetic.kn", "com.android.settings"};
            setServiceInfo(info);
        }
        active = this;
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        addGestureStrip();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        boolean keeneticEvent = wolActive && "com.keenetic.kn".contentEquals(event.getPackageName());
        boolean simEvent = simPinActive && "com.android.settings".contentEquals(event.getPackageName());
        if (!keeneticEvent && !simEvent) return;
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) return;
        AccessibilityNodeInfo root = source;
        AccessibilityNodeInfo parent;
        while ((parent = root.getParent()) != null) {
            if (root != source) root.recycle();
            root = parent;
        }
        try {
            if (keeneticEvent) advanceHeadlessWol(root);
            else advanceSimPinDisable(root, event);
        }
        finally {
            if (root != source) root.recycle();
            source.recycle();
        }
    }

    @Override public void onInterrupt() { }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (!androidAutoRotationArmed || configuration == null) return;
        WindowManager windowManager = windows != null ? windows :
                (WindowManager) getSystemService(WINDOW_SERVICE);
        int displayRotation = windowManager.getDefaultDisplay().getRotation();
        if (displayRotation == androidAutoDisplayRotation) return;
        androidAutoDisplayRotation = displayRotation;
        handler.removeCallbacks(restartAndroidAutoAfterRotation);
        handler.postDelayed(restartAndroidAutoAfterRotation,
                ANDROID_AUTO_ROTATION_DEBOUNCE_MS);
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (event == null || (event.getKeyCode() != KEYCODE_SAMSUNG_BIXBY &&
                event.getScanCode() != SCANCODE_SAMSUNG_BIXBY)) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            Log.i(TAG, "Bixby key -> standalone Codex");
            Intent codex = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_CLEAR_TOP |
                            Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_CODEX_STANDALONE, true);
            startActivity(codex);
        }
        // Consume both down and up so Samsung/Bixby cannot also react.
        return true;
    }

    @Override public void onDestroy() {
        if (active == this) active = null;
        handler.removeCallbacks(wolPoll);
        handler.removeCallbacks(restartAndroidAutoAfterRotation);
        androidAutoRotationArmed = false;
        androidAutoReconnectInProgress = false;
        wolActive = false;
        removeView(gestureStrip);
        removeView(wolMask);
        gestureStrip = null;
        wolMask = null;
        super.onDestroy();
    }

    static boolean showWolMask() {
        final NodeControlAccessibilityService service = active;
        if (service == null) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            service.addWolMask();
            return service.wolMask != null;
        }
        service.handler.post(service::addWolMask);
        return true;
    }

    static boolean startHeadlessWol(String target, boolean dryRun) {
        final NodeControlAccessibilityService service = active;
        if (service == null || target == null ||
                !target.matches("[A-Za-z0-9._-]+")) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            service.beginHeadlessWol(target, dryRun);
        } else {
            service.handler.post(() -> service.beginHeadlessWol(target, dryRun));
        }
        return true;
    }

    static boolean startSimPinDisable(String pin) {
        final NodeControlAccessibilityService service = active;
        if (service == null || pin == null || !pin.matches("[0-9]{4,8}")) return false;
        service.handler.post(() -> service.beginSimPinDisable(pin));
        return true;
    }

    static boolean armAndroidAutoRotation(int displayRotation) {
        final NodeControlAccessibilityService service = active;
        if (service == null) return false;
        service.handler.removeCallbacks(service.restartAndroidAutoAfterRotation);
        service.androidAutoDisplayRotation = displayRotation;
        service.androidAutoRotationArmed = true;
        service.androidAutoReconnectInProgress = false;
        Log.i("DAAK_ANDROID_AUTO", "rotation monitor armed at displayRotation=" +
                displayRotation);
        return true;
    }

    static void disarmAndroidAutoRotation() {
        final NodeControlAccessibilityService service = active;
        if (service == null) return;
        if (service.androidAutoReconnectInProgress) {
            Log.i("DAAK_ANDROID_AUTO", "keeping rotation monitor armed during reconnect");
            return;
        }
        service.handler.removeCallbacks(service.restartAndroidAutoAfterRotation);
        if (service.androidAutoRotationArmed) {
            Log.i("DAAK_ANDROID_AUTO", "rotation monitor disarmed");
        }
        service.androidAutoRotationArmed = false;
    }

    private boolean startAndroidAutoAction(String action) {
        Intent intent = new Intent(action)
                .setComponent(new ComponentName(ANDROID_AUTO_RECEIVER_PACKAGE,
                        ANDROID_AUTO_AUTOMATION_ACTIVITY))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(intent);
            return true;
        } catch (RuntimeException unavailable) {
            Log.w("DAAK_ANDROID_AUTO", "automation action failed: " + action, unavailable);
            return false;
        }
    }

    private void beginSimPinDisable(String pin) {
        simPinActive = true;
        simPinToggleClicked = false;
        simPinSubmitted = false;
        simPinValue = pin;
        simPinStartedAt = System.currentTimeMillis();
        writeSimPinResult("STARTED");
        Intent settings = new Intent(Intent.ACTION_MAIN)
                .setComponent(new ComponentName("com.android.settings",
                        "com.android.settings.Settings$IccLockSettingsActivity"))
                .addCategory(Intent.CATEGORY_DEFAULT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try { startActivity(settings); }
        catch (Exception error) { completeSimPin("SETTINGS_UNAVAILABLE"); }
    }

    private void advanceSimPinDisable(AccessibilityNodeInfo root, AccessibilityEvent event) {
        if (!simPinActive || root == null) return;
        if (System.currentTimeMillis() - simPinStartedAt > 30000L) {
            completeSimPin("TIMEOUT");
            return;
        }
        if (event.getText() != null) {
            String text = event.getText().toString();
            if (text.contains("SIM PIN disabled") || text.contains("SIM PIN’i kaldırıldı") ||
                    text.contains("Código PIN de la tarjeta SIM desactivado")) {
                completeSimPin("DISABLED");
                return;
            }
        }

        AccessibilityNodeInfo toggle = (!simPinToggleClicked || simPinSubmitted) ? firstByAnyId(root,
                "android:id/switch_widget", "com.android.settings:id/switch_widget",
                "android:id/checkbox") : null;
        if (!simPinToggleClicked && toggle != null) {
            boolean checked = toggle.isChecked();
            if (!checked) {
                toggle.recycle();
                completeSimPin("ALREADY_DISABLED");
                return;
            }
            simPinToggleClicked = clickNode(toggle);
            toggle.recycle();
            return;
        }

        if (simPinToggleClicked && !simPinSubmitted) {
            AccessibilityNodeInfo input = firstByAnyId(root,
                    "com.android.settings:id/password_entry",
                    "com.android.settings:id/pincode_edit_text",
                    "android:id/edit");
            if (input == null) input = firstEditable(root);
            if (input == null) return;
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, simPinValue);
            boolean filled = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            input.recycle();
            if (!filled) return;
            AccessibilityNodeInfo next = firstByAnyId(root,
                    "com.android.settings:id/next_button", "android:id/button1");
            if (next == null) next = exactAnyText(root, "Done", "OK", "Tamam", "Aceptar", "Hecho");
            if (next != null) {
                simPinSubmitted = clickNode(next);
                next.recycle();
                if (simPinSubmitted) simPinValue = null;
            }
            return;
        }

        if (simPinSubmitted && toggle != null) {
            boolean disabled = !toggle.isChecked();
            toggle.recycle();
            if (disabled) completeSimPin("DISABLED");
        }
    }

    private AccessibilityNodeInfo firstByAnyId(AccessibilityNodeInfo root, String... ids) {
        for (String id : ids) {
            AccessibilityNodeInfo found = firstById(root, id);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo exactAnyText(AccessibilityNodeInfo root, String... labels) {
        for (String label : labels) {
            AccessibilityNodeInfo found = exactText(root, label);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo firstEditable(AccessibilityNodeInfo root) {
        if (root == null) return null;
        if (root.isEditable()) return AccessibilityNodeInfo.obtain(root);
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo answer = firstEditable(child);
            child.recycle();
            if (answer != null) return answer;
        }
        return null;
    }

    private void completeSimPin(String status) {
        simPinActive = false;
        simPinValue = null;
        writeSimPinResult(status);
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    private void writeSimPinResult(String status) {
        File result = new File("/sdcard/Download/daak-sim-pin-result.txt");
        try {
            FileOutputStream output = new FileOutputStream(result, false);
            output.write((status + "\n").getBytes(StandardCharsets.UTF_8));
            output.close();
        } catch (Exception error) { Log.w(TAG, "SIM PIN result unavailable", error); }
    }

    private void beginHeadlessWol(String target, boolean dryRun) {
        handler.removeCallbacks(wolPoll);
        wolActive = true;
        wolDryRun = dryRun;
        wolNetworkClicked = false;
        wolTargetClicked = false;
        wolSearchFilled = false;
        wolButtonClicked = false;
        wolTarget = target;
        wolStartedAt = System.currentTimeMillis();
        File result = wolResultFile();
        if (result.exists() && !result.delete()) {
            Log.w(TAG, "Could not clear stale WOL result");
        }
        addWolMask();
        handler.postDelayed(wolPoll, 500L);
    }

    private void advanceHeadlessWol(AccessibilityNodeInfo root) {
        if (!wolActive || root == null) return;
        AccessibilityNodeInfo signIn = firstById(root, "com.keenetic.kn:id/btnSignIn");
        if (signIn != null) {
            signIn.recycle();
            completeHeadlessWol("LOGIN_REQUIRED");
            return;
        }

        AccessibilityNodeInfo wol = firstById(root, "com.keenetic.kn:id/ibWol");
        if (wol != null) {
            if (wolDryRun) {
                completeHeadlessWol("READY");
            } else if (!wolButtonClicked) {
                wolButtonClicked = clickNode(wol);
                if (wolButtonClicked) handler.postDelayed(
                        () -> completeHeadlessWol("SENT"), 900L);
            }
            wol.recycle();
            return;
        }

        AccessibilityNodeInfo target = exactText(root, wolTarget);
        if (target != null && !wolTargetClicked) {
            wolTargetClicked = clickNode(target);
            target.recycle();
            return;
        }
        if (target != null) target.recycle();

        boolean dashboard = hasId(root, "com.keenetic.kn:id/tv_network_clients");
        if (!dashboard && !wolNetworkClicked) {
            AccessibilityNodeInfo network = firstById(root, "com.keenetic.kn:id/tvNetworkName");
            if (network != null) {
                wolNetworkClicked = clickNode(network);
                network.recycle();
                return;
            }
        }

        if (dashboard && !wolSearchFilled &&
                System.currentTimeMillis() - wolStartedAt > 8000L) {
            AccessibilityNodeInfo search = firstById(root, "android:id/search_src_text");
            if (search != null) {
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        wolTarget);
                wolSearchFilled = search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                search.recycle();
            }
        }
    }

    private AccessibilityNodeInfo firstById(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
        if (nodes == null || nodes.isEmpty()) return null;
        AccessibilityNodeInfo first = AccessibilityNodeInfo.obtain(nodes.get(0));
        for (AccessibilityNodeInfo node : nodes) node.recycle();
        return first;
    }

    private boolean hasId(AccessibilityNodeInfo root, String id) {
        AccessibilityNodeInfo node = firstById(root, id);
        if (node == null) return false;
        node.recycle();
        return true;
    }

    private AccessibilityNodeInfo exactText(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null) return null;
        AccessibilityNodeInfo answer = null;
        for (AccessibilityNodeInfo node : nodes) {
            CharSequence value = node.getText();
            if (answer == null && value != null && text.equalsIgnoreCase(value.toString().trim())) {
                answer = AccessibilityNodeInfo.obtain(node);
            }
            node.recycle();
        }
        return answer;
    }

    private boolean clickNode(AccessibilityNodeInfo start) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(start);
        try {
            while (node != null) {
                if (node.isEnabled() && node.isClickable() &&
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                AccessibilityNodeInfo parent = node.getParent();
                node.recycle();
                node = parent;
            }
            return false;
        } finally {
            if (node != null) node.recycle();
        }
    }

    private File wolResultFile() {
        File directory = new File(getFilesDir(), "daak-node");
        if (!directory.exists() && !directory.mkdirs()) {
            Log.w(TAG, "Could not create WOL result directory");
        }
        return new File(directory, "keenetic.result");
    }

    private void completeHeadlessWol(String status) {
        if (!wolActive) return;
        wolActive = false;
        handler.removeCallbacks(wolPoll);
        File result = wolResultFile();
        File temporary = new File(result.getParentFile(), result.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write((status + "\n").getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
            if (!temporary.renameTo(result)) throw new IllegalStateException("rename failed");
        } catch (Exception error) {
            Log.e(TAG, "Could not publish WOL result", error);
        }
        removeView(wolMask);
        wolMask = null;
        addGestureStrip();
        Intent home = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                        Intent.FLAG_ACTIVITY_CLEAR_TOP |
                        Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_FORCE_HOME, true)
                .putExtra(MainActivity.EXTRA_WOL_STATUS, status);
        startActivity(home);
    }

    static void finishWol() {
        final NodeControlAccessibilityService service = active;
        if (service == null) return;
        service.handler.post(() -> {
            service.wolActive = false;
            service.handler.removeCallbacks(service.wolPoll);
            service.removeView(service.wolMask);
            service.wolMask = null;
            service.addGestureStrip();
        });
    }

    static int wolGeneration() {
        final NodeControlAccessibilityService service = active;
        return service == null ? -1 : service.wolGeneration;
    }

    static boolean finishWol(int expectedGeneration) {
        final NodeControlAccessibilityService service = active;
        if (service == null || Looper.myLooper() != Looper.getMainLooper()) return false;
        if (service.wolGeneration != expectedGeneration) return false;
        service.wolActive = false;
        service.handler.removeCallbacks(service.wolPoll);
        service.removeView(service.wolMask);
        service.wolMask = null;
        service.addGestureStrip();
        return true;
    }

    private void addWolMask() {
        wolGeneration++;
        removeView(gestureStrip);
        gestureStrip = null;
        if (wolMask != null || windows == null) return;
        wolMask = new WolMaskView();
        WindowManager.LayoutParams params = windowParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                dp(76), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        params.gravity = Gravity.BOTTOM;
        params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        params.setTitle("DAAK headless WOL status");
        try {
            windows.addView(wolMask, params);
            Log.i(TAG, "Headless WOL status shown");
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not show headless WOL status", error);
            wolMask = null;
            addGestureStrip();
        }
    }

    private void addGestureStrip() {
        if (gestureStrip != null || wolMask != null || windows == null) return;
        gestureStrip = new BottomGestureView();
        WindowManager.LayoutParams params = baseParams(
                WindowManager.LayoutParams.MATCH_PARENT, dp(24));
        params.gravity = Gravity.BOTTOM;
        try {
            windows.addView(gestureStrip, params);
        } catch (RuntimeException error) {
            gestureStrip = null;
        }
    }

    private WindowManager.LayoutParams baseParams(int width, int height) {
        return windowParams(width, height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
    }

    private WindowManager.LayoutParams windowParams(int width, int height, int type) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.setTitle("DAAK control surface");
        return params;
    }

    private void returnHome() {
        performGlobalAction(GLOBAL_ACTION_HOME);
        handler.postDelayed(() -> {
            Intent home = new Intent(NodeControlAccessibilityService.this, MainActivity.class)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_CLEAR_TOP |
                            Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_FORCE_HOME, true);
            startActivity(home);
        }, 80L);
    }

    private void removeView(View view) {
        if (view == null || windows == null) return;
        try { windows.removeViewImmediate(view); }
        catch (RuntimeException ignored) { }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class BottomGestureView extends View {
        private float downY;
        private long downAt;
        private boolean triggered;

        BottomGestureView() {
            super(NodeControlAccessibilityService.this);
            setBackgroundColor(Color.TRANSPARENT);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downY = event.getRawY();
                downAt = event.getEventTime();
                triggered = false;
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE ||
                    event.getActionMasked() == MotionEvent.ACTION_UP ||
                    event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                maybeReturnHome(event);
            }
            return true;
        }

        private void maybeReturnHome(MotionEvent event) {
            if (triggered) return;
            float distance = downY - event.getRawY();
            long duration = event.getEventTime() - downAt;
            if (distance < dp(42) || duration > 1400L) return;
            triggered = true;
            Log.i(TAG, "Bottom-edge swipe -> DAAK Home");
            returnHome();
        }
    }

    private final class WolMaskView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        WolMaskView() {
            super(NodeControlAccessibilityService.this);
            setBackgroundColor(Color.TRANSPARENT);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float left = dp(24), right = getWidth() - dp(24), bottom = getHeight() - dp(8);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(12, 18, 23));
            canvas.drawRoundRect(left, bottom - dp(52), right, bottom, dp(14), dp(14), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.rgb(71, 215, 255));
            canvas.drawRoundRect(left, bottom - dp(52), right, bottom, dp(14), dp(14), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(215, 235, 242));
            paint.setTextSize(dp(13));
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("KEENETIC WOL  •  GÖRÜNMEZ EKRANDA ÇALIŞIYOR",
                    getWidth() / 2f, bottom - dp(20), paint);
        }
    }
}
