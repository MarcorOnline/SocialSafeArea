// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Marco Rinaldi (MarcorOnline)
package it.pistacchissimo.socialoverlay;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.CheckBox;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Map;

/** Runs against a disposable preview install; exercises actual overlay arrow taps. */
public final class ArrowControlsTest extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Context context = getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("social_overlay", Context.MODE_PRIVATE);
        Map<String, ?> saved = prefs.getAll();
        boolean running = OverlayService.isRunning();
        MainActivity[] activity = new MainActivity[1];
        Bundle result = new Bundle();
        int code = -1;
        try {
            JSONObject exact = new JSONObject().put("manufacturer", "brand").put("model", "phone")
                    .put("android_sdk", 36).put("camera_package", "example.camera")
                    .put("camera_version", "1.0").put("vertical_offset_dp", 38);
            check(DeviceProfiles.isExactProfile(exact, "Brand", "PHONE", 36, "example.camera", "1.0"), "exact offset profile");
            JSONObject fallback = DeviceProfiles.select(new JSONArray().put(exact), "other", "phone", 36, "example.camera", "1.0");
            check(fallback != null && !DeviceProfiles.isExactProfile(fallback, "other", "phone", 36, "example.camera", "1.0"), "fallback is not exact");
            check(!DeviceProfiles.isExactProfile(exact, "brand", "phone", 36, "example.camera", "1.1"), "version mismatch");
            check(!DeviceProfiles.isExactProfile(exact, "brand", "phone", 35, "example.camera", "1.0"), "SDK mismatch");
            check(!DeviceProfiles.isExactProfile(exact, "brand", "other", 36, "example.camera", "1.0"), "model mismatch");
            prefs.edit().putBoolean("wizard_complete", true).remove("calibration_complete")
                    .remove("calibration_active").remove("calibration_finished").commit();
            activity[0] = (MainActivity) startActivitySync(new Intent(context, MainActivity.class)
                    .setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            waitForIdleSync();
            android.accessibilityservice.AccessibilityServiceInfo info = getUiAutomation().getServiceInfo();
            info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            getUiAutomation().setServiceInfo(info);
            onMain(() -> context.stopService(new Intent(context, OverlayService.class)));
            await(R.string.quick_button_description, false);
            onMain(() -> {
                MaskMode.setAutoPackage(context, "example.unknown.camera");
                prefs.edit().remove("adjustment_arrows").apply();
                check(MaskMode.adjustmentArrows(context), "unknown profile enables arrows by default");
                MaskMode.set(context, MaskMode.BOTH);
                MaskMode.setGuideVisible(context, true);
                MaskMode.setVideoOnly(context, false);
                MaskMode.setVerticalOffset(context, 0);
                prefs.edit().putInt("vertical_fine_px", -6).apply();
                check(MaskMode.verticalOffsetPx(context) == -6f, "legacy pixel adjustment preserved before editing");
                int sliderValue = MaskMode.verticalOffset(context);
                MaskMode.nudgeVerticalOffset(context, 1);
                check(MaskMode.verticalOffset(context) == sliderValue + 1
                        && !prefs.contains("vertical_fine_px"), "first edit aligns legacy position to slider dp");
                MaskMode.setVerticalOffset(context, 0);
                MaskMode.saveButtonPosition(context, 0, 0, 1, 1);
                OverlayService.start(context);
            });
            await(R.string.move_mask_up, true);
            Rect up = bounds(R.string.move_mask_up), safe = bounds(R.string.quick_button_description), down = bounds(R.string.move_mask_down);
            check(up.bottom <= safe.top && safe.bottom <= down.top && up.top >= 0,
                    "separate arrows above and below SAFE at top screen edge: " + up + " / " + safe + " / " + down);
            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            check(safe.left > screenWidth / 2 && screenWidth - safe.right <= Math.round(24 * context.getResources().getDisplayMetrics().density),
                    "arrow group stays at the physical right edge despite a saved left position");
            int originalRight = safe.right;
            drag(safe, safe.exactCenterX() + 120, safe.exactCenterY() + 200);
            safe = bounds(R.string.quick_button_description);
            up = bounds(R.string.move_mask_up);
            down = bounds(R.string.move_mask_down);
            check(safe.right == originalRight && up.centerX() == safe.centerX() && down.centerX() == safe.centerX()
                    && up.bottom <= safe.top && safe.bottom <= down.top, "arrows follow dragged SAFE: " + up + " / " + safe + " / " + down);
            float density = context.getResources().getDisplayMetrics().density;
            float before = MaskMode.verticalOffsetPx(context);
            int boundary = screenBoundary();
            click(R.string.move_mask_down);
            check(Math.abs(MaskMode.verticalOffsetPx(context) - before - density) < .001f,
                    "one tap moves by one dp at the current display density");
            check(MaskMode.verticalOffset(context) == 1, "arrow matches one slider step");
            check(Math.abs(screenBoundary() - boundary - density) <= 1f, "visible overlay moved one dp after screen tap");
            click(R.string.move_mask_up);
            check(MaskMode.verticalOffsetPx(context) == before, "opposite tap restores position");
            click(R.string.move_mask_up);
            check(MaskMode.verticalOffsetPx(context) == before - density, "up is negative one dp");
            onMain(() -> context.stopService(new Intent(context, OverlayService.class)));
            await(R.string.move_mask_up, false);
            onMain(() -> OverlayService.start(context));
            await(R.string.move_mask_up, true);
            check(MaskMode.verticalOffsetPx(context) == before - density, "dp adjustment survives service restart");
            float beforeHold = MaskMode.verticalOffsetPx(context);
            int holdBoundary = screenBoundary();
            hold(R.string.move_mask_down, 1200);
            float afterHold = MaskMode.verticalOffsetPx(context);
            check(afterHold >= beforeHold + 5, "holding the arrow must keep moving the mask");
            check(screenBoundary() >= holdBoundary + Math.floor(afterHold - beforeHold) - 1
                    && screenBoundary() <= holdBoundary + Math.ceil(afterHold - beforeHold) + 1, "held arrow visibly moves overlay");
            SystemClock.sleep(200);
            check(MaskMode.verticalOffsetPx(context) == afterHold, "release stops movement");
            onMain(() -> {
                MaskMode.nudgeVerticalOffset(context, Math.round((beforeHold - afterHold) / density));
                OverlayService.refreshMask(context);
            });
            click(R.string.quick_button_description);
            await(R.string.move_mask_up, false);
            await(R.string.move_mask_down, false);
            click(R.string.quick_button_description);
            await(R.string.move_mask_up, true);
            onMain(() -> {
                CheckBox flag = findFlag(activity[0].getWindow().getDecorView(), context.getString(R.string.adjustment_arrows));
                check(flag != null, "home exposes arrow flag");
                flag.setChecked(true);
                flag.performClick();
            });
            await(R.string.move_mask_up, false);
            check(!MaskMode.adjustmentArrows(context), "home flag disables arrows");
            onMain(() -> {
                CheckBox flag = findFlag(activity[0].getWindow().getDecorView(), context.getString(R.string.adjustment_arrows));
                flag.performClick();
            });
            await(R.string.move_mask_up, true);
            onMain(() -> { MaskMode.setVideoOnly(context, true); OverlayService.setPhotoMode(context, true); });
            await(R.string.move_mask_up, false);
            onMain(() -> OverlayService.setPhotoMode(context, false));
            await(R.string.move_mask_up, true);
            onMain(() -> { MaskMode.set(context, 0); OverlayService.refreshMask(context); });
            await(R.string.move_mask_up, false);
            onMain(() -> {
                MaskMode.setVerticalOffset(context, 360);
                float limit = MaskMode.verticalOffsetPx(context);
                MaskMode.nudgeVerticalOffset(context, 1);
                check(MaskMode.verticalOffsetPx(context) == limit, "upper offset limit");
                MaskMode.setVerticalOffset(context, -360);
                limit = MaskMode.verticalOffsetPx(context);
                MaskMode.nudgeVerticalOffset(context, -1);
                check(MaskMode.verticalOffsetPx(context) == limit, "lower offset limit");
                MaskMode.nudgeVerticalOffset(context, 1);
                MaskMode.resetVerticalOffset(context);
                check(!MaskMode.hasVerticalOffsetOverride(context), "reset clears fine correction");
            });
            onMain(() -> { MaskMode.set(context, MaskMode.BOTH); OverlayService.refreshMask(context); });
            await(R.string.move_mask_up, true);
            android.graphics.Point screenSize = new android.graphics.Point();
            context.getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRealSize(screenSize);
            safe = bounds(R.string.quick_button_description);
            drag(safe, screenSize.x / 2f, screenSize.y - 10f);
            await(R.string.quick_button_description, false);
            await(R.string.move_mask_up, false);
            check(MaskMode.dismissed(context), "drag to trash still dismisses SAFE with arrows");
            calibrationFlow(context, prefs, activity);
            result.putString("result", "PASS: physical one-dp taps/hold, persistence, drag/trash, visibility, limits; wizard skip, real camera calibration, Done return, manual arrows without Done, guided retry from home and interruption");
        } catch (Throwable error) {
            code = 0;
            result.putString("error", android.util.Log.getStackTraceString(error));
        } finally {
            onMain(() -> {
                context.stopService(new Intent(context, OverlayService.class));
                OverlayService.setPhotoMode(context, false);
                if (activity[0] != null) activity[0].finish();
                SharedPreferences.Editor editor = prefs.edit().clear();
                for (Map.Entry<String, ?> entry : saved.entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
                    else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
                    else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
                    else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
                    else if (value instanceof String) editor.putString(entry.getKey(), (String) value);
                }
                editor.commit();
                if (running) OverlayService.start(context);
            });
            finish(code, result);
        }
    }

    private void calibrationFlow(Context context, SharedPreferences prefs, MainActivity[] activity) {
        String camera = null;
        for (android.content.pm.ResolveInfo info : context.getPackageManager().queryIntentActivities(
                new Intent(android.provider.MediaStore.INTENT_ACTION_VIDEO_CAMERA), 0)) {
            if (context.getPackageManager().getLaunchIntentForPackage(info.activityInfo.packageName) != null) {
                camera = info.activityInfo.packageName;
                break;
            }
        }
        check(camera != null, "a camera must be installed for the guided flow test");
        final String cameraPackage = camera;
        String cameraLabel = context.getPackageManager().getLaunchIntentForPackage(camera).resolveActivityInfo(
                context.getPackageManager(), 0).loadLabel(context.getPackageManager()).toString();
        onMain(() -> prefs.edit().putBoolean("wizard_complete", false).putInt("wizard_page", 2)
                .putInt("wizard_demo", 2).putBoolean("adjustment_arrows", false)
                .putBoolean("guide_visible", false).putInt("mask_selection", 0)
                .putBoolean("experimental_video_only", true).remove("calibration_complete").commit());
        activity[0] = (MainActivity) startActivitySync(new Intent(context, MainActivity.class)
                .setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        press(activity[0], R.string.calibration_skip);
        check(prefs.getInt("wizard_page", -1) == 3 && !prefs.getBoolean("calibration_complete", false), "skip does not complete calibration");
        onMain(() -> activity[0].onBackPressed());
        press(activity[0], R.string.calibration_now);
        chooseCameraLabel(cameraLabel);
        await(R.string.calibration_done, true);
        Rect done = bounds(R.string.calibration_done), up = bounds(R.string.move_mask_up), down = bounds(R.string.move_mask_down);
        Rect safe = bounds(R.string.quick_button_description);
        check(up.bottom <= safe.top && safe.bottom <= down.top && up.centerX() == safe.centerX()
                && down.centerX() == safe.centerX() && done.right < safe.left && Math.abs(done.centerY() - safe.centerY()) <= 1,
                "guided arrows align with SAFE on the right, with Done separated on the left");
        click(R.string.quick_button_description);
        await(R.string.move_mask_up, false);
        await(R.string.calibration_done, true);
        click(R.string.quick_button_description);
        await(R.string.move_mask_up, true);
        check(MaskMode.autoPackage(context).equals(cameraPackage), "selected camera is saved");
        check(foreground(cameraPackage), "selected camera opens in front");
        check(screenBoundary() > 0, "calibration forces a visible mask despite hidden masks and Photo mode");
        float before = MaskMode.verticalOffsetPx(context);
        click(R.string.move_mask_down);
        check(Math.abs(MaskMode.verticalOffsetPx(context) - before - context.getResources().getDisplayMetrics().density) < .001f,
                "guided camera tap moves one dp");
        click(R.string.calibration_done);
        await(R.string.calibration_done, false);
        await(R.string.move_mask_up, false);
        awaitForeground(context.getPackageName());
        check(prefs.getInt("wizard_page", -1) == 3,
                "Done returns to next wizard step; page=" + prefs.getInt("wizard_page", -1));
        check(prefs.getBoolean("calibration_complete", false) && !MaskMode.adjustmentArrows(context),
                "Done suppresses automatic arrows");
        onMain(() -> {
            prefs.edit().putBoolean("wizard_complete", true).remove("adjustment_arrows").commit();
            check(!MaskMode.adjustmentArrows(context), "calibrated unknown profile no longer defaults to arrows");
            MaskMode.set(context, MaskMode.BOTH);
            MaskMode.setGuideVisible(context, true);
            MaskMode.setVideoOnly(context, false);
        });
        activity[0] = (MainActivity) startActivitySync(new Intent(context, MainActivity.class)
                .setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        await(R.string.move_mask_up, false);
        press(activity[0], R.string.customize_mask);
        onMain(() -> {
            CheckBox flag = findFlag(activity[0].getWindow().getDecorView(), context.getString(R.string.adjustment_arrows));
            check(!flag.isChecked(), "home flag reflects completed calibration");
            flag.performClick();
        });
        await(R.string.move_mask_up, true);
        await(R.string.calibration_done, false);
        onMain(() -> {
            CheckBox flag = findFlag(activity[0].getWindow().getDecorView(), context.getString(R.string.adjustment_arrows));
            flag.performClick();
        });
        press(activity[0], R.string.calibration_open);
        chooseCameraLabel(cameraLabel);
        await(R.string.calibration_done, true);
        click(R.string.calibration_done);
        await(R.string.calibration_done, false);
        awaitForeground(context.getPackageName());
        check(prefs.getBoolean("wizard_complete", false) && foreground(context.getPackageName()), "home calibration returns to home");
        press(activity[0], R.string.calibration_open);
        chooseCameraLabel(cameraLabel);
        await(R.string.calibration_done, true);
        getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        await(R.string.calibration_done, false);
        awaitForeground(context.getPackageName());
        check(!prefs.getBoolean("calibration_active", false) && foreground(context.getPackageName()), "Back interrupts calibration and cleans up controls");
    }

    private boolean foreground(String packageName) {
        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        try { return root != null && packageName.contentEquals(root.getPackageName()); }
        finally { if (root != null) root.recycle(); }
    }

    private void awaitForeground(String packageName) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            if (foreground(packageName)) return;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("expected foreground app after calibration: " + packageName);
    }

    private void press(MainActivity activity, int label) {
        onMain(() -> {
            android.widget.Button button = findButton(activity.getWindow().getDecorView(), getTargetContext().getString(label));
            check(button != null, "screen action: " + getTargetContext().getString(label));
            button.performClick();
        });
        waitForIdleSync();
    }

    private android.widget.Button findButton(View view, String text) {
        if (view instanceof android.widget.Button && text.contentEquals(((android.widget.Button) view).getText()))
            return (android.widget.Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.Button found = findButton(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void chooseCameraLabel(String label) {
        // Search and scroll the real system-style app list, rather than calling the launch helper.
        for (int attempt = 0; attempt < 40; attempt++) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            try {
                if (root != null) {
                    java.util.List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(label);
                    boolean clicked = false;
                    for (AccessibilityNodeInfo node : matches) {
                        if (!clicked && label.contentEquals(node.getText()) && node.isVisibleToUser()) {
                            clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        }
                        node.recycle();
                    }
                    if (clicked) { SystemClock.sleep(800); return; }
                    scrollList(root);
                }
            } finally { if (root != null) root.recycle(); }
            SystemClock.sleep(150);
        }
        throw new AssertionError("camera not found in app selection list");
    }

    private boolean scrollList(AccessibilityNodeInfo node) {
        if (node.isScrollable() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { if (scrollList(child)) return true; }
            finally { child.recycle(); }
        }
        return false;
    }

    private void drag(Rect from, float x, float y) {
        SystemClock.sleep(250);
        long time = SystemClock.uptimeMillis();
        for (int action : new int[]{android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE,
                android.view.MotionEvent.ACTION_UP}) {
            android.view.MotionEvent event = android.view.MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                    action == android.view.MotionEvent.ACTION_DOWN ? from.exactCenterX() : x,
                    action == android.view.MotionEvent.ACTION_DOWN ? from.exactCenterY() : y, 0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            check(getUiAutomation().injectInputEvent(event, true), "drag SAFE");
            event.recycle();
            SystemClock.sleep(100);
        }
        waitForIdleSync();
    }

    private void click(int description) {
        await(description, true);
        AccessibilityNodeInfo node = find(description);
        try {
            check(node != null, "overlay button exists");
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            SystemClock.sleep(250);
            long time = SystemClock.uptimeMillis();
            for (int action : new int[]{android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP}) {
                android.view.MotionEvent event = android.view.MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                        rect.exactCenterX(), rect.exactCenterY(), 0);
                event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                check(getUiAutomation().injectInputEvent(event, true), "screen tap on overlay");
                event.recycle();
                SystemClock.sleep(100);
            }
        }
        finally { if (node != null) node.recycle(); }
        waitForIdleSync();
    }

    private int screenBoundary() {
        SystemClock.sleep(250);
        Bitmap screen = getUiAutomation().takeScreenshot();
        try {
            for (int y = 100; y < screen.getHeight(); y++) {
                int pixel = screen.getPixel(screen.getWidth() / 2, y);
                int red = android.graphics.Color.red(pixel);
                int green = android.graphics.Color.green(pixel);
                int blue = android.graphics.Color.blue(pixel);
                if (red > 150 && red > green * 1.5f && red > blue * 1.2f && blue > 40) return y;
            }
            throw new AssertionError("Reels boundary missing on the actual screen");
        } finally { screen.recycle(); }
    }

    private void hold(int description, long duration) {
        await(description, true);
        Rect rect = bounds(description);
        SystemClock.sleep(250);
        long time = SystemClock.uptimeMillis();
        for (int action : new int[]{android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP}) {
            android.view.MotionEvent event = android.view.MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                    rect.exactCenterX(), rect.exactCenterY(), 0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            check(getUiAutomation().injectInputEvent(event, true), "hold arrow");
            event.recycle();
            if (action == android.view.MotionEvent.ACTION_DOWN) SystemClock.sleep(duration);
        }
        waitForIdleSync();
    }

    private Rect bounds(int description) {
        // A floating window can be briefly absent while WindowManager relays out the arrow group.
        for (int attempt = 0; attempt < 30; attempt++) {
            AccessibilityNodeInfo node = find(description);
            if (node != null) {
                try { Rect rect = new Rect(); node.getBoundsInScreen(rect); return rect; }
                finally { node.recycle(); }
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("Overlay control missing: " + getTargetContext().getString(description));
    }

    private void await(int description, boolean present) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            AccessibilityNodeInfo node = find(description);
            boolean found = node != null;
            if (node != null) node.recycle();
            if (found == present) return;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Overlay control visibility: " + getTargetContext().getString(description) + " expected " + present);
    }

    private AccessibilityNodeInfo find(int description) {
        String label = getTargetContext().getString(description);
        for (AccessibilityWindowInfo window : getUiAutomation().getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            try {
                if (root != null && root.getContentDescription() != null
                        && label.contentEquals(root.getContentDescription())) return AccessibilityNodeInfo.obtain(root);
            } finally { if (root != null) root.recycle(); window.recycle(); }
        }
        return null;
    }

    private CheckBox findFlag(View view, String text) {
        if (view instanceof CheckBox && text.contentEquals(((CheckBox) view).getText())) return (CheckBox) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                CheckBox flag = findFlag(group.getChildAt(i), text);
                if (flag != null) return flag;
            }
        }
        return null;
    }

    private void onMain(Runnable action) {
        Throwable[] error = new Throwable[1];
        runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error[0] = failure; } });
        if (error[0] != null) throw new AssertionError(error[0]);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
