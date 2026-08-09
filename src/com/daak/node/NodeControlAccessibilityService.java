package com.daak.node;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
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
            info.packageNames = new String[]{"com.keenetic.kn"};
            setServiceInfo(info);
        }
        active = this;
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        addGestureStrip();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!wolActive || event == null || event.getPackageName() == null ||
                !"com.keenetic.kn".contentEquals(event.getPackageName())) return;
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) return;
        Log.d(TAG, "Keenetic event class=" + event.getClassName() +
                " source=" + source.getViewIdResourceName());
        AccessibilityNodeInfo root = source;
        AccessibilityNodeInfo parent;
        while ((parent = root.getParent()) != null) {
            if (root != source) root.recycle();
            root = parent;
        }
        try { advanceHeadlessWol(root); }
        finally {
            if (root != source) root.recycle();
            source.recycle();
        }
    }

    @Override public void onInterrupt() { }

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
                WindowManager.LayoutParams.MATCH_PARENT, dp(10));
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

        BottomGestureView() {
            super(NodeControlAccessibilityService.this);
            setBackgroundColor(Color.TRANSPARENT);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downY = event.getRawY();
                downAt = event.getEventTime();
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                float distance = downY - event.getRawY();
                long duration = event.getEventTime() - downAt;
                if (distance >= dp(42) && duration <= 1400L) returnHome();
                return true;
            }
            return true;
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
