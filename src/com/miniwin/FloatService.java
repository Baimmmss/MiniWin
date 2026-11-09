package com.miniwin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Owns everything drawn over other apps: the launcher bubble and the floating browser windows. */
public class FloatService extends Service {
    static final String ACTION_BUBBLE_ON = "com.miniwin.BUBBLE_ON";
    static final String ACTION_BUBBLE_OFF = "com.miniwin.BUBBLE_OFF";
    static final String ACTION_WEB = "com.miniwin.WEB";
    static final String ACTION_STOP = "com.miniwin.STOP";
    static final String EXTRA_URL = "url";
    static final String PREF_BUBBLE = "bubble";

    private static final String CHANNEL = "miniwin";
    private static final int HANDLE_WIDTH_DP = 40;
    // Android only protects 200dp per screen edge from the back gesture, so the strip stops there.
    private static final int HANDLE_HEIGHT_DP = 200;
    private static final int TAP_MAX_DP = 16;
    private static final int MIN_SWIPE_DP = 12;
    private static final long DOUBLE_TOUCH_MS = 1200;
    private static final long HOLD_TO_MOVE_MS = 500;
    private static final int PILL_IDLE = 0x99808080;
    private static final int PILL_ACTIVE = 0xFF4F8CFF;

    private final List<WebWindow> webWindows = new ArrayList<>();
    private WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout handle;
    private int pendingWebWindows;

    static boolean bubbleWanted(Context c) {
        return c.getSharedPreferences("miniwin", MODE_PRIVATE).getBoolean(PREF_BUBBLE, true);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Floating windows", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        String action = intent == null ? ACTION_BUBBLE_ON : intent.getAction();
        if (!Settings.canDrawOverlays(this) || ACTION_STOP.equals(action)) {
            getSharedPreferences("miniwin", MODE_PRIVATE).edit().putBoolean(PREF_BUBBLE, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_BUBBLE_ON.equals(action)) {
            showHandle();
        } else if (ACTION_BUBBLE_OFF.equals(action)) {
            hideHandle();
        } else if (ACTION_WEB.equals(action)) {
            // The first WebView blocks this thread for a while, so let the picker close first.
            pendingWebWindows++;
            final String url = intent.getStringExtra(EXTRA_URL);
            handler.postDelayed(() -> {
                pendingWebWindows--;
                webWindows.add(new WebWindow(this, url, webWindows.size()));
            }, 500);
        }
        stopIfIdle();
        // Bring the handle back if the system kills this service.
        return handle != null ? START_STICKY : START_NOT_STICKY;
    }

    void onWebWindowClosed(WebWindow w) {
        webWindows.remove(w);
        stopIfIdle();
    }

    private void stopIfIdle() {
        if (handle == null && webWindows.isEmpty() && pendingWebWindows == 0) stopSelf();
    }

    private void goForeground() {
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, FloatService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_note)
                .setContentTitle("MiniWin is running")
                .setContentText("Tap to pick an app")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    /** A strip on the screen edge. Two quick taps or inward swipes open the picker; hold and drag to move it. */
    private void showHandle() {
        if (handle != null) return;
        final SharedPreferences prefs = getSharedPreferences("miniwin", MODE_PRIVATE);
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(dp(HANDLE_WIDTH_DP), dp(HANDLE_HEIGHT_DP),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.y = clampHandleY(prefs.getInt("handle_y", dp(180)));

        handle = new FrameLayout(this);
        final View pill = new View(this);
        final GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(PILL_IDLE);
        pillBg.setCornerRadius(dp(3));
        pill.setBackground(pillBg);
        handle.addView(pill, new FrameLayout.LayoutParams(dp(6), dp(110)));
        placeHandle(lp, pill, prefs.getBoolean("handle_left", false));

        // Keeps the system's edge-swipe "back" gesture from taking these swipes.
        if (Build.VERSION.SDK_INT >= 29) {
            handle.addOnLayoutChangeListener((v, l, t, r, bottom, ol, ot, or, ob) ->
                    v.setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, r - l, bottom - t))));
        }

        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        final Runnable resetPill = () -> pillBg.setColor(PILL_IDLE);
        handle.setOnTouchListener(new View.OnTouchListener() {
            float startRawX, startRawY;
            int startY;
            boolean moving;
            long lastTouchAt;

            final Runnable startMoving = () -> {
                moving = true;
                pillBg.setColor(PILL_ACTIVE);
            };

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                float dx = e.getRawX() - startRawX;
                float dy = e.getRawY() - startRawY;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startRawX = e.getRawX();
                        startRawY = e.getRawY();
                        startY = lp.y;
                        handler.removeCallbacks(resetPill);
                        handler.postDelayed(startMoving, HOLD_TO_MOVE_MS);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (moving) {
                            lp.y = clampHandleY(startY + (int) dy);
                            boolean left = e.getRawX() < getResources().getDisplayMetrics().widthPixels / 2f;
                            placeHandle(lp, pill, left);
                            prefs.edit().putBoolean("handle_left", left).putInt("handle_y", lp.y).apply();
                            wm.updateViewLayout(handle, lp);
                        } else if (Math.hypot(dx, dy) > slop) {
                            handler.removeCallbacks(startMoving);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        handler.removeCallbacks(startMoving);
                        boolean left = (lp.gravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) == Gravity.START;
                        float inward = left ? dx : -dx;
                        boolean tap = Math.hypot(dx, dy) < dp(TAP_MAX_DP);
                        // The strip is mostly swiped across, so an up/down scroll that starts here doesn't count.
                        boolean swipe = inward > dp(MIN_SWIPE_DP) && inward * 2 > Math.abs(dy);
                        if (!moving && (tap || swipe)) {
                            long now = SystemClock.uptimeMillis();
                            if (now - lastTouchAt <= DOUBLE_TOUCH_MS) {
                                lastTouchAt = 0;
                                startActivity(new Intent(FloatService.this, MainActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            } else {
                                lastTouchAt = now;
                            }
                            pillBg.setColor(PILL_ACTIVE);
                            handler.postDelayed(resetPill, DOUBLE_TOUCH_MS);
                        } else {
                            pillBg.setColor(PILL_IDLE);
                        }
                        moving = false;
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(startMoving);
                        pillBg.setColor(PILL_IDLE);
                        moving = false;
                        return true;
                    default:
                        return false;
                }
            }
        });
        wm.addView(handle, lp);
    }

    private int clampHandleY(int y) {
        int max = getResources().getDisplayMetrics().heightPixels - dp(HANDLE_HEIGHT_DP);
        return Math.max(0, Math.min(y, max));
    }

    private void placeHandle(WindowManager.LayoutParams lp, View pill, boolean left) {
        int side = left ? Gravity.START : Gravity.END;
        lp.gravity = Gravity.TOP | side;
        ((FrameLayout.LayoutParams) pill.getLayoutParams()).gravity = Gravity.CENTER_VERTICAL | side;
        pill.requestLayout();
    }

    private void hideHandle() {
        if (handle != null) {
            wm.removeView(handle);
            handle = null;
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        hideHandle();
        for (WebWindow w : new ArrayList<>(webWindows)) w.destroy();
        webWindows.clear();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
