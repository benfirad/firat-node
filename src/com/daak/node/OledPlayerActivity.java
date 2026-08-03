package com.daak.node;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class OledPlayerActivity extends Activity {
    private PlayerView playerView;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setShowWhenLocked(true);
        setTurnScreenOn(false);
        Window window = getWindow();
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_FULLSCREEN |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        hideBars();
        playerView = new PlayerView(this);
        setContentView(playerView);
    }

    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) hideBars();
    }

    @Override protected void onDestroy() {
        if (playerView != null) playerView.stop();
        super.onDestroy();
    }

    final class PlayerView extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Handler handler = new Handler(Looper.getMainLooper());
        final RectF previous = new RectF(), play = new RectF(), next = new RectF();
        final int mint = Color.rgb(216, 180, 254), soft = Color.rgb(238, 230, 242);
        MediaController controller;
        MediaMetadata metadata;
        PlaybackState playback;
        String source = "NO ACTIVE SESSION";
        boolean stopped;

        final Runnable refresh = new Runnable() {
            @Override public void run() {
                updateSession(); invalidate();
                if (!stopped) handler.postDelayed(this, 750L);
            }
        };

        PlayerView(Context context) {
            super(context);
            setBackgroundColor(Color.BLACK);
            paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
            updateSession(); handler.postDelayed(refresh, 300L);
        }

        void stop() { stopped = true; handler.removeCallbacks(refresh); }

        void updateSession() {
            MediaController active = null;
            try {
                MediaSessionManager manager = (MediaSessionManager)getSystemService(MEDIA_SESSION_SERVICE);
                List<MediaController> sessions = manager == null ? null : manager.getActiveSessions(
                        new ComponentName(OledPlayerActivity.this, MailNotificationListener.class));
                MediaController fallback = null;
                if (sessions != null) for (MediaController candidate : sessions) {
                    if (fallback == null) fallback = candidate;
                    PlaybackState state = candidate.getPlaybackState();
                    if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                        active = candidate; break;
                    }
                }
                if (active == null) active = fallback;
            } catch (SecurityException ignored) { }
            controller = active;
            metadata = active == null ? null : active.getMetadata();
            playback = active == null ? null : active.getPlaybackState();
            if (active == null) { source = "NO ACTIVE SESSION"; return; }
            try {
                source = getPackageManager().getApplicationLabel(
                        getPackageManager().getApplicationInfo(active.getPackageName(), 0)).toString();
            } catch (Exception error) { source = active.getPackageName(); }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight(), margin = w * 0.065f;
            canvas.drawColor(Color.BLACK);
            text(canvas, new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()), margin, h * 0.055f,
                    Math.max(20f, w * 0.036f), mint, false);
            text(canvas, "DAAK // OLED PLAYER", w - margin, h * 0.055f,
                    Math.max(12f, w * 0.019f), mint, true);

            Bitmap art = artwork();
            RectF artBox = new RectF(margin, h * 0.105f, w - margin, Math.min(h * 0.56f, w - margin));
            if (art != null && !art.isRecycled()) drawArtwork(canvas, art, artBox);
            else {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(2f, w * 0.003f)); paint.setColor(Color.rgb(57, 38, 65));
                canvas.drawRoundRect(artBox, w * 0.035f, w * 0.035f, paint);
                paint.setStyle(Paint.Style.FILL);
                text(canvas, "♪", artBox.centerX(), artBox.centerY() + w * 0.07f, w * 0.20f, mint, true);
            }

            String title = metadata == null ? "Müzik bekleniyor" : value(MediaMetadata.METADATA_KEY_TITLE, "Bilinmeyen parça");
            String artist = metadata == null ? "Auxio veya başka bir kaynağı başlat" : value(MediaMetadata.METADATA_KEY_ARTIST, source);
            float titleY = artBox.bottom + h * 0.062f;
            text(canvas, fit(title, 28), margin, titleY, Math.max(25f, w * 0.052f), soft, false);
            text(canvas, fit(artist, 38), margin, titleY + h * 0.041f, Math.max(16f, w * 0.027f), mint, false);
            text(canvas, source.toUpperCase(Locale.getDefault()), margin, titleY + h * 0.072f,
                    Math.max(12f, w * 0.018f), Color.rgb(125, 103, 132), false);

            float progressTop = titleY + h * 0.108f;
            drawProgress(canvas, margin, w - margin, progressTop);
            float controlY = Math.min(h * 0.855f, progressTop + h * 0.12f), radius = Math.min(w * 0.105f, h * 0.065f);
            previous.set(w * 0.20f - radius, controlY - radius, w * 0.20f + radius, controlY + radius);
            play.set(w * 0.50f - radius * 1.18f, controlY - radius * 1.18f, w * 0.50f + radius * 1.18f, controlY + radius * 1.18f);
            next.set(w * 0.80f - radius, controlY - radius, w * 0.80f + radius, controlY + radius);
            control(canvas, previous, "PREV", false);
            control(canvas, play, isPlaying() ? "PAUSE" : "PLAY", true);
            control(canvas, next, "NEXT", false);

            KeyguardManager keyguard = (KeyguardManager)getSystemService(KEYGUARD_SERVICE);
            String secure = keyguard != null && keyguard.isKeyguardLocked()
                    ? "SECURE KEYGUARD ACTIVE • BACK RETURNS TO LOCK" : "SCREEN OFF → AUTO LOCK PLAYER";
            text(canvas, secure, w * 0.5f, h - Math.max(35f, h * 0.032f), Math.max(10f, w * 0.016f),
                    Color.rgb(91, 76, 96), true);
        }

        Bitmap artwork() {
            if (metadata == null) return null;
            Bitmap value = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (value == null) value = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            return value;
        }

        void drawArtwork(Canvas canvas, Bitmap bitmap, RectF dst) {
            float sourceRatio = (float)bitmap.getWidth() / Math.max(1, bitmap.getHeight());
            float targetRatio = dst.width() / Math.max(1f, dst.height());
            Rect src;
            if (sourceRatio > targetRatio) {
                int width = Math.round(bitmap.getHeight() * targetRatio), left = (bitmap.getWidth() - width) / 2;
                src = new Rect(left, 0, left + width, bitmap.getHeight());
            } else {
                int height = Math.round(bitmap.getWidth() / targetRatio), top = (bitmap.getHeight() - height) / 2;
                src = new Rect(0, top, bitmap.getWidth(), top + height);
            }
            paint.setAlpha(220); canvas.drawBitmap(bitmap, src, dst, paint); paint.setAlpha(255);
        }

        void drawProgress(Canvas canvas, float left, float right, float y) {
            float fraction = 0f;
            long duration = metadata == null ? 0L : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            long position = playback == null ? 0L : Math.max(0L, playback.getPosition());
            if (duration > 0L) fraction = Math.min(1f, (float)position / duration);
            paint.setStyle(Paint.Style.FILL); paint.setColor(Color.rgb(48, 39, 51));
            canvas.drawRoundRect(new RectF(left, y, right, y + 8f), 4f, 4f, paint);
            paint.setColor(mint); canvas.drawRoundRect(new RectF(left, y, left + (right - left) * fraction, y + 8f), 4f, 4f, paint);
            text(canvas, clock(position), left, y + 34f, Math.max(11f, getWidth() * 0.017f), Color.rgb(135, 114, 141), false);
            text(canvas, clock(duration), right, y + 34f, Math.max(11f, getWidth() * 0.017f), Color.rgb(135, 114, 141), true);
        }

        void control(Canvas canvas, RectF bounds, String label, boolean emphasized) {
            paint.setStyle(Paint.Style.FILL); paint.setColor(emphasized ? mint : Color.rgb(20, 15, 22));
            canvas.drawOval(bounds, paint);
            if (!emphasized) { paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2f); paint.setColor(Color.rgb(91, 65, 101)); canvas.drawOval(bounds, paint); }
            paint.setStyle(Paint.Style.FILL); paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
            paint.setTextSize(Math.max(16f, getWidth() * (label.length() > 3 ? 0.026f : 0.040f)));
            paint.setColor(emphasized ? Color.BLACK : soft);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(label, bounds.centerX(), bounds.centerY() + getWidth() * 0.018f, paint);
        }

        void text(Canvas canvas, String value, float x, float y, float size, int color, boolean alignRightOrCenter) {
            paint.setStyle(Paint.Style.FILL); paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
            paint.setTextSize(size); paint.setColor(color);
            if (alignRightOrCenter && x == getWidth() * 0.5f) paint.setTextAlign(Paint.Align.CENTER);
            else paint.setTextAlign(alignRightOrCenter ? Paint.Align.RIGHT : Paint.Align.LEFT);
            canvas.drawText(value == null ? "" : value, x, y, paint);
        }

        String value(String key, String fallback) {
            String result = metadata == null ? null : metadata.getString(key);
            return result == null || result.trim().length() == 0 ? fallback : result;
        }

        String fit(String value, int max) { return value.length() <= max ? value : value.substring(0, Math.max(1, max - 1)) + "…"; }
        String clock(long millis) { long seconds = Math.max(0L, millis / 1000L); return String.format(Locale.US, "%d:%02d", seconds / 60L, seconds % 60L); }
        boolean isPlaying() { return playback != null && playback.getState() == PlaybackState.STATE_PLAYING; }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_UP) return true;
            if (controller == null) return true;
            float x = event.getX(), y = event.getY();
            if (previous.contains(x, y)) controller.getTransportControls().skipToPrevious();
            else if (next.contains(x, y)) controller.getTransportControls().skipToNext();
            else if (play.contains(x, y)) {
                if (isPlaying()) controller.getTransportControls().pause();
                else controller.getTransportControls().play();
            }
            handler.postDelayed(() -> { updateSession(); invalidate(); }, 180L);
            return true;
        }
    }
}
