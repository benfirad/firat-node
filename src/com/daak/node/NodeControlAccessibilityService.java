package com.daak.node;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * A deliberately narrow system-wide control surface.
 *
 * The transparent bottom strip translates one upward gesture into HOME. During
 * Keenetic WOL automation the full-screen, non-touchable OLED mask prevents the
 * third-party UI from flashing on screen while root taps still pass through to
 * it. No window contents are read by this service.
 */
public final class NodeControlAccessibilityService extends AccessibilityService {
    private static final String TAG = "DAAK_CONTROL";
    private static NodeControlAccessibilityService active;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private View gestureStrip;
    private WolMaskView wolMask;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        active = this;
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        addGestureStrip();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        if (active == this) active = null;
        removeView(gestureStrip);
        removeView(wolMask);
        gestureStrip = null;
        wolMask = null;
        super.onDestroy();
    }

    static boolean showWolMask() {
        final NodeControlAccessibilityService service = active;
        if (service == null) return false;
        service.handler.post(service::addWolMask);
        return true;
    }

    static void finishWol() {
        final NodeControlAccessibilityService service = active;
        if (service == null) return;
        service.handler.post(() -> {
            service.removeView(service.wolMask);
            service.wolMask = null;
            service.addGestureStrip();
        });
    }

    private void addWolMask() {
        removeView(gestureStrip);
        gestureStrip = null;
        if (wolMask != null || windows == null) return;
        wolMask = new WolMaskView();
        WindowManager.LayoutParams params = baseParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);
        params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        params.setTitle("DAAK WOL privacy mask");
        try {
            windows.addView(wolMask, params);
            Log.i(TAG, "WOL privacy mask shown");
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not show WOL privacy mask", error);
            wolMask = null;
            addGestureStrip();
        }
    }

    private void addGestureStrip() {
        if (gestureStrip != null || wolMask != null || windows == null) return;
        gestureStrip = new BottomGestureView();
        WindowManager.LayoutParams params = baseParams(
                WindowManager.LayoutParams.MATCH_PARENT, dp(42));
        params.gravity = Gravity.BOTTOM;
        try {
            windows.addView(gestureStrip, params);
        } catch (RuntimeException error) {
            gestureStrip = null;
        }
    }

    private WindowManager.LayoutParams baseParams(int width, int height) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
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
                if (distance >= dp(58) && duration <= 1400L) returnHome();
                return true;
            }
            return true;
        }
    }

    private final class WolMaskView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        WolMaskView() {
            super(NodeControlAccessibilityService.this);
            setBackgroundColor(Color.BLACK);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float left = dp(24), right = getWidth() - dp(24), bottom = getHeight() - dp(52);
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
            canvas.drawText("KEENETIC WOL  •  ARKA PLANDA ÇALIŞIYOR",
                    getWidth() / 2f, bottom - dp(20), paint);
        }
    }
}
