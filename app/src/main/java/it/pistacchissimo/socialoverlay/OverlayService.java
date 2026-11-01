// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Marco Rinaldi (MarcorOnline)

package it.pistacchissimo.socialoverlay;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.TextView;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/** Owns the tap-through guide, quick button and optional position controls. */
public final class OverlayService extends Service {
    private static final String CHANNEL_ID = "safe_area";
    private static final int NOTIFICATION_ID = 7;
    private static final String ACTION_REFRESH = "it.pistacchissimo.socialoverlay.REFRESH";
    private static final String ACTION_AUTOMATIC = "it.pistacchissimo.socialoverlay.AUTOMATIC";
    private static final String ACTION_CLOSE = "it.pistacchissimo.socialoverlay.CLOSE";
    private static final String ACTION_TUTORIAL = "it.pistacchissimo.socialoverlay.TUTORIAL";
    private static final String ACTION_CALIBRATE = "it.pistacchissimo.socialoverlay.CALIBRATE";
    private static volatile boolean running;
    private static volatile boolean guideVisible;
    private static boolean photoMode;
    private boolean tutorial;
    private static boolean calibrating;
    private boolean calibrationVisible;
    private boolean tutorialVisible;
    private ResultReceiver tutorialResult;

    private WindowManager windowManager;
    private View guide;
    private View toggle;
    private View trash;
    private View upArrow;
    private View downArrow;
    private View calibrationDone;
    private WindowManager.LayoutParams toggleParams;
    private int buttonMaxX;
    private int buttonMaxY;
    private int screenInsetTop;
    private int screenInsetBottom;
    private int trashTop;
    private int touchSlop;
    private float touchDownX;
    private float touchDownY;
    private int buttonDownX;
    private int buttonDownY;
    private boolean dragging;
    private boolean longPressed;
    private boolean overTrash;
    private final Runnable longPress = this::beginLongPress;

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context context) {
        Context app = context.getApplicationContext();
        MaskMode.setDismissed(app, false);
        Intent intent = new Intent(app, OverlayService.class);
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent); else app.startService(intent);
    }

    static void startTutorial(Context context, boolean visible, ResultReceiver result) {
        context.startForegroundService(new Intent(context, OverlayService.class)
                .setAction(ACTION_TUTORIAL).putExtra("visible", visible).putExtra("tutorial_result", result));
    }

    static void startCalibration(Context context) {
        context.startForegroundService(new Intent(context, OverlayService.class).setAction(ACTION_CALIBRATE));
    }

    static void startAutomatically(Context context) {
        Context app = context.getApplicationContext();
        if (!running && !MaskMode.dismissed(app)) {
            Intent intent = new Intent(app, OverlayService.class).setAction(ACTION_AUTOMATIC);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent); else app.startService(intent);
        }
    }

    static void hideAutomatically(Context context) {
        if (calibrating) return;
        // Leaving the selected app hides windows without dismissing the next automatic launch.
        context.getApplicationContext().stopService(new Intent(context, OverlayService.class));
    }

    public static void refreshMask(Context context) {
        if (running) {
            context.getApplicationContext().startService(
                    new Intent(context, OverlayService.class).setAction(ACTION_REFRESH));
        }
    }

    static void setPhotoMode(Context context, boolean photo) {
        if (photoMode == photo) return;
        photoMode = photo;
        refreshMask(context);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_CLOSE.equals(intent == null ? null : intent.getAction())) {
            MaskMode.setDismissed(this, true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_AUTOMATIC.equals(intent == null ? null : intent.getAction()) && MaskMode.dismissed(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean practice = ACTION_TUTORIAL.equals(intent == null ? null : intent.getAction());
        boolean nextCalibration = ACTION_CALIBRATE.equals(intent == null ? null : intent.getAction())
                || (calibrating && ACTION_REFRESH.equals(intent == null ? null : intent.getAction()));
        boolean nextTutorial = practice || (tutorial && ACTION_REFRESH.equals(intent == null ? null : intent.getAction()));
        if ((nextTutorial != tutorial || nextCalibration != calibrating) && running) {
            hideGuide();
            removeArrows();
            removeCalibrationDone();
            removeTrash();
            if (toggle != null) windowManager.removeViewImmediate(toggle);
            toggle = null;
            running = false;
        }
        tutorial = nextTutorial;
        calibrating = nextCalibration;
        if (ACTION_CALIBRATE.equals(intent == null ? null : intent.getAction())) calibrationVisible = true;
        if (practice) {
            tutorialVisible = intent.getBooleanExtra("visible", false);
            tutorialResult = intent.getParcelableExtra("tutorial_result");
        } else if (!tutorial) tutorialResult = null;
        if (!running) {
            showWindows();
        }
        running = true;
        updateGuideVisibility();
        if (ACTION_REFRESH.equals(intent == null ? null : intent.getAction()) && guideVisible) {
            guide.invalidate();
        }
        createChannel();
        startForeground(NOTIFICATION_ID, notification());
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        calibrating = false;
        guideVisible = false;
        if (windowManager != null) {
            removeArrows();
            removeCalibrationDone();
            if (guide != null) {
                try {
                    windowManager.removeViewImmediate(guide);
                } catch (IllegalArgumentException ignored) {
                    // The guide can already be hidden while its toggle remains on screen.
                }
            }
            if (toggle != null) windowManager.removeViewImmediate(toggle);
            if (trash != null) windowManager.removeViewImmediate(trash);
        }
        guide = null;
        toggle = null;
        trash = null;
        toggleParams = null;
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (toggle == null) return;
        readButtonBounds();
        toggleParams.x = tutorial || calibrating ? Math.max(0, buttonMaxX - dp(16)) : MaskMode.buttonX(this, buttonMaxX, dp(16));
        toggleParams.y = tutorial || calibrating ? buttonMaxY / 2 : MaskMode.buttonY(this, buttonMaxY);
        windowManager.updateViewLayout(toggle, toggleParams);
        updateArrows();
        updateCalibrationDone();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("guideVisible=" + guideVisible + " manualEnabled=" + MaskMode.guideVisible(this)
                + " photoMode=" + photoMode);
    }

    @android.annotation.SuppressLint("RtlHardcoded") // Overlay coordinates refer to physical display edges.
    private void showWindows() {
        windowManager = getSystemService(WindowManager.class);

        TextView button = new TextView(this);
        button.setText(R.string.quick_button_text);
        button.setTextColor(Color.BLACK);
        button.setTextSize(8);
        button.setPadding(0, 0, 0, 0);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setCornerRadius(dp(24));
        background.setColor(getColor(R.color.brand));
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.argb(90, 255, 255, 255)), background, null));
        button.setElevation(dp(8));
        button.setContentDescription(getString(R.string.quick_button_description));
        button.setOnClickListener(ignored -> toggleGuide());
        if (!tutorial && !calibrating) button.setOnTouchListener((view, event) -> handleToggleTouch(view, event));
        toggle = button;

        int buttonSize = dp(48);
        readButtonBounds();
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        toggleParams = new WindowManager.LayoutParams(
                buttonSize, buttonSize, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        toggleParams.gravity = Gravity.TOP | Gravity.LEFT;
        toggleParams.x = tutorial || calibrating ? Math.max(0, buttonMaxX - dp(16)) : MaskMode.buttonX(this, buttonMaxX, dp(16));
        toggleParams.y = tutorial || calibrating ? buttonMaxY / 2 : MaskMode.buttonY(this, buttonMaxY);
        windowManager.addView(toggle, toggleParams);
        if (Build.VERSION.SDK_INT < 30) toggle.post(this::updateArrows);
    }

    private boolean handleToggleTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getRawX();
                touchDownY = event.getRawY();
                buttonDownX = toggleParams.x;
                buttonDownY = toggleParams.y;
                dragging = false;
                longPressed = false;
                overTrash = false;
                view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE:
                int moveX = Math.round(event.getRawX() - touchDownX);
                int moveY = Math.round(event.getRawY() - touchDownY);
                if (!dragging && Math.max(Math.abs(moveX), Math.abs(moveY)) > touchSlop) {
                    dragging = true;
                    view.removeCallbacks(longPress);
                    showTrash();
                }
                if (dragging) {
                    toggleParams.x = upArrow == null ? clamp(buttonDownX + moveX, 0, buttonMaxX)
                            : Math.max(0, buttonMaxX - dp(16));
                    int minimum = upArrow == null ? 0 : screenInsetTop + dp(56);
                    int maximum = upArrow == null ? buttonMaxY : buttonMaxY - screenInsetBottom - dp(56);
                    toggleParams.y = clamp(buttonDownY + moveY, minimum, Math.max(minimum, maximum));
                    windowManager.updateViewLayout(toggle, toggleParams);
                    positionArrows();
                    updateTrashHover();
                }
                return true;
            case MotionEvent.ACTION_UP:
                view.removeCallbacks(longPress);
                if (trash != null && overTrash) {
                    removeTrash();
                    MaskMode.setDismissed(this, true);
                    stopSelf();
                } else if (dragging) {
                    MaskMode.saveButtonPosition(this, toggleParams.x, toggleParams.y,
                            buttonMaxX, buttonMaxY);
                } else {
                    view.performClick();
                }
                removeTrash();
                return true;
            case MotionEvent.ACTION_CANCEL:
                view.removeCallbacks(longPress);
                removeTrash();
                return true;
            default:
                return true;
        }
    }

    private void toggleGuide() {
        if (calibrating) {
            calibrationVisible = !calibrationVisible;
            updateGuideVisibility();
            return;
        }
        if (tutorial) {
            tutorialVisible = !tutorialVisible;
            updateGuideVisibility();
            if (tutorialResult != null) tutorialResult.send(tutorialVisible ? 1 : 0, null);
            return;
        }
        MaskMode.setGuideVisible(this, !MaskMode.guideVisible(this));
        updateGuideVisibility();
    }

    private void updateGuideVisibility() {
        boolean visible = calibrating ? calibrationVisible : (tutorial ? tutorialVisible
                : MaskMode.guideVisible(this) && !(MaskMode.videoOnly(this) && photoMode));
        if (visible && guide == null) addGuide();
        if (!visible) hideGuide();
        guideVisible = visible;
        updateArrows();
        updateCalibrationDone();
    }

    private void updateArrows() {
        if (toggle == null) return;
        readButtonBounds();
        if (tutorial || !guideVisible || (!calibrating && (MaskMode.get(this) == 0 || !MaskMode.adjustmentArrows(this)))
                || buttonMaxY - screenInsetTop - screenInsetBottom < dp(112)) {
            removeArrows();
            return;
        }
        // Keep controls in the masked right-hand strip, even with a previously saved left position.
        toggleParams.x = Math.max(0, buttonMaxX - dp(16));
        toggleParams.y = clamp(toggleParams.y, screenInsetTop + dp(56), buttonMaxY - screenInsetBottom - dp(56));
        windowManager.updateViewLayout(toggle, toggleParams);
        if (upArrow == null) {
            upArrow = addArrow("↑", R.string.move_mask_up, -1);
            downArrow = addArrow("↓", R.string.move_mask_down, 1);
        }
        positionArrows();
    }

    private void updateCalibrationDone() {
        if (!calibrating || toggle == null) { removeCalibrationDone(); return; }
        WindowManager.LayoutParams params = arrowParams(0);
        params.width = dp(96);
        params.x = Math.max(0, toggleParams.x - dp(104));
        if (calibrationDone != null) {
            windowManager.updateViewLayout(calibrationDone, params);
            return;
        }
        android.widget.Button done = new android.widget.Button(this);
        done.setText(R.string.calibration_done);
        done.setContentDescription(getString(R.string.calibration_done));
        done.setAllCaps(false);
        done.setTextSize(16);
        done.setTextColor(Color.WHITE);
        done.setPadding(0, 0, 0, 0);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(24));
        background.setColor(getColor(R.color.calibration_green));
        done.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.argb(90, 255, 255, 255)), background, null));
        done.setElevation(dp(8));
        done.setOnClickListener(ignored -> finishCalibration());
        calibrationDone = done;
        windowManager.addView(done, params);
    }

    private void removeCalibrationDone() {
        if (calibrationDone != null) windowManager.removeViewImmediate(calibrationDone);
        calibrationDone = null;
    }

    private void finishCalibration() {
        getSharedPreferences("social_overlay", MODE_PRIVATE).edit()
                .putBoolean("calibration_complete", true).putBoolean("calibration_finished", true)
                .putBoolean("calibration_active", false).putBoolean("adjustment_arrows", false).apply();
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        stopSelf();
    }

    private View addArrow(String symbol, int description, int direction) {
        TextView arrow = new TextView(this);
        arrow.setText(symbol);
        arrow.setTextSize(26);
        arrow.setTextColor(Color.BLACK);
        arrow.setGravity(Gravity.CENTER);
        arrow.setContentDescription(getString(description));
        arrow.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Button.class.getName());
            }
        });
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(getColor(R.color.brand));
        arrow.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.argb(90, 255, 255, 255)), background, null));
        arrow.setElevation(dp(8));
        arrow.setOnClickListener(ignored -> moveMask(direction));
        arrow.setOnLongClickListener(view -> {
            view.post(new Runnable() {
                @Override public void run() {
                    if (!view.isAttachedToWindow() || !view.isPressed() || guide == null) return;
                    moveMask(direction);
                    view.postDelayed(this, 40);
                }
            });
            return true;
        });
        windowManager.addView(arrow, arrowParams(direction));
        return arrow;
    }

    private void moveMask(int dp) {
        MaskMode.nudgeVerticalOffset(this, dp);
        if (guide != null) guide.invalidate();
    }

    @android.annotation.SuppressLint("RtlHardcoded") // Match SAFE's physical screen coordinates in every locale.
    private WindowManager.LayoutParams arrowParams(int direction) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(dp(48), dp(48),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = toggleParams.x;
        params.y = toggleParams.y + direction * dp(56);
        return params;
    }

    private void positionArrows() {
        if (upArrow == null) return;
        windowManager.updateViewLayout(upArrow, arrowParams(-1));
        windowManager.updateViewLayout(downArrow, arrowParams(1));
    }

    private void removeArrows() {
        if (upArrow != null) windowManager.removeViewImmediate(upArrow);
        if (downArrow != null) windowManager.removeViewImmediate(downArrow);
        upArrow = null;
        downArrow = null;
    }

    private Notification notification() {
        PendingIntent close = PendingIntent.getService(this, 0,
                new Intent(this, OverlayService.class).setAction(ACTION_CLOSE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_safe_area_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(content)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_safe_area_notification, getString(R.string.close_app), close).build())
                .build();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.channel_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void addGuide() {
        guide = new SafeAreaGuideView(this, tutorial || calibrating);
        WindowManager.LayoutParams guideParams = new WindowManager.LayoutParams(
                -1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        guideParams.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(guide, guideParams);
    }

    private void hideGuide() {
        if (guide != null) {
            windowManager.removeViewImmediate(guide);
            guide = null;
        }
    }

    private void beginLongPress() {
        longPressed = true;
        dragging = true;
        showTrash();
        updateTrashHover();
    }

    private void showTrash() {
        if (trash != null) return;
        TextView target = new TextView(this);
        target.setText(R.string.trash_target);
        target.setTextColor(Color.WHITE);
        target.setTextSize(12);
        target.setGravity(Gravity.CENTER);
        target.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(130, 25, 40));
        background.setCornerRadius(dp(18));
        target.setBackground(background);
        int height = dp(72);
        Point screen = screenSize();
        trashTop = screen.y - height - dp(20);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(dp(224), height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = dp(20);
        trash = target;
        windowManager.addView(trash, params);
    }

    private void updateTrashHover() {
        if (trash == null) return;
        overTrash = toggleParams.y + dp(upArrow == null ? 24 : 48) >= trashTop;
        trash.setAlpha(overTrash ? 1f : .65f);
    }

    private void removeTrash() {
        if (trash == null) return;
        windowManager.removeViewImmediate(trash);
        trash = null;
        longPressed = false;
        overTrash = false;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("deprecation")
    private void readButtonBounds() {
        Point screen = screenSize();
        buttonMaxX = Math.max(0, screen.x - dp(48));
        buttonMaxY = Math.max(0, screen.y - dp(48));
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets insets = windowManager.getCurrentWindowMetrics().getWindowInsets()
                    .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.displayCutout());
            screenInsetTop = insets.top;
            screenInsetBottom = insets.bottom;
        } else if (toggle != null && toggle.getRootWindowInsets() != null) {
            screenInsetTop = toggle.getRootWindowInsets().getStableInsetTop();
            screenInsetBottom = toggle.getRootWindowInsets().getStableInsetBottom();
        }
    }

    @SuppressWarnings("deprecation")
    private Point screenSize() {
        Point size = new Point();
        windowManager.getDefaultDisplay().getRealSize(size);
        return size;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
