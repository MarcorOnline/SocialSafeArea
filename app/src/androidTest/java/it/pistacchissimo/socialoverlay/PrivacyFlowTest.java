// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Marco Rinaldi (MarcorOnline)

package it.pistacchissimo.socialoverlay;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.PatternMatcher;
import android.os.SystemClock;
import android.provider.Settings;
import android.content.pm.ResolveInfo;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;

/** Exercises cancellation, affirmative consent and the real privacy link without leaving the app. */
public final class PrivacyFlowTest extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Context context = getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("social_overlay", Context.MODE_PRIVATE);
        boolean hadConsent = prefs.contains(MaskMode.ACCESSIBILITY_CONSENT);
        boolean originalConsent = MaskMode.accessibilityConsent(context);
        boolean originalRunning = OverlayService.isRunning();
        boolean hadCamera = prefs.contains("auto_package");
        String originalCamera = MaskMode.autoPackage(context);
        boolean hadVideoOnly = prefs.contains("experimental_video_only");
        boolean originalVideoOnly = MaskMode.videoOnly(context);
        java.util.Map<String, ?> originalWizard = new java.util.HashMap<>(prefs.getAll());
        String[] wizardKeys = {"wizard_complete", "wizard_page", "wizard_demo"};
        Activity[] activity = new Activity[1];
        IntentFilter settings = new IntentFilter("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
        settings.addAction(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        ActivityMonitor settingsMonitor = addMonitor(settings,
                new ActivityResult(Activity.RESULT_CANCELED, null), true);
        IntentFilter appInfo = new IntentFilter(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        appInfo.addDataScheme("package");
        ActivityMonitor appInfoMonitor = addMonitor(appInfo,
                new ActivityResult(Activity.RESULT_CANCELED, null), true);
        IntentFilter overlay = new IntentFilter(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
        overlay.addDataScheme("package");
        ActivityMonitor overlayMonitor = addMonitor(overlay,
                new ActivityResult(Activity.RESULT_CANCELED, null), true);
        IntentFilter privacy = new IntentFilter(Intent.ACTION_VIEW);
        privacy.addDataScheme("https");
        privacy.addDataAuthority("www.pistacchissimo.it", null);
        privacy.addDataPath("/news/social-safe-area-lapp-per-girare-reels-e-tiktok-migliori-gratis/",
                PatternMatcher.PATTERN_LITERAL);
        ActivityMonitor privacyMonitor = addMonitor(privacy,
                new ActivityResult(Activity.RESULT_CANCELED, null), true);
        int resultCode = Activity.RESULT_CANCELED;
        try {
            prefs.edit().remove(MaskMode.ACCESSIBILITY_CONSENT).remove("auto_package")
                    .putBoolean("experimental_video_only", false).commit();
            prefs.edit().remove("wizard_complete").remove("wizard_page").remove("wizard_demo").commit();
            onMain(() -> context.stopService(new Intent(context, OverlayService.class)));
            waitForIdleSync();
            activity[0] = openHome(context);
            checkpoint("dedicated wizard, permission gate");
            assertNoHome(activity[0]);
            check(!OverlayService.isRunning(), "permission screen has no overlay");
            if (!Settings.canDrawOverlays(context)) {
                clickControl(activity[0], R.string.setup_open_settings);
                check(overlayMonitor.getHits() == 1, "instructions precede the intercepted Android settings");
                assertNoHome(activity[0]);
                onMain(() -> {
                    View root = activity[0].getWindow().getDecorView();
                    check(findButton(root, context.getString(R.string.wizard_next)) == null,
                            "cannot continue without the actual overlay permission");
                });
                check(!prefs.getBoolean("wizard_complete", false), "denied permission does not complete setup");
                onMain(() -> activity[0].finish());
                waitForIdleSync();
                activity[0] = openHome(context);
                assertNoHome(activity[0]);
                result.putString("result", "PASS: dedicated first launch and mandatory real-permission gate, including restart");
                resultCode = Activity.RESULT_OK;
                return;
            }
            // The practice must work even with every normal mask disabled, and must not alter them.
            int savedMask = MaskMode.get(context);
            boolean savedGuide = MaskMode.guideVisible(context);
            MaskMode.set(context, 0);
            try {
                clickControl(activity[0], R.string.wizard_next);
                assertNoHome(activity[0]);
                onMain(() -> check(!findButton(activity[0].getWindow().getDecorView(),
                        context.getString(R.string.wizard_next)).isEnabled(), "practice cannot be skipped"));
                checkpoint("real floating SAFE button: show mask");
                clickSafe(context);
                awaitText(context.getString(R.string.wizard_demo_wait_hide)).recycle();
                check(prefs.getInt("wizard_demo", 0) == 1, "real SAFE click advances the lesson");
                onMain(() -> activity[0].finish());
                waitForIdleSync();
                activity[0] = openHome(context);
                awaitText(context.getString(R.string.wizard_demo_wait_hide)).recycle();
                checkpoint("practice resumes after restart: hide mask");
                clickSafe(context);
                awaitText(context.getString(R.string.wizard_demo_verified)).recycle();
                check(MaskMode.guideVisible(context) == savedGuide && MaskMode.get(context) == 0,
                        "practice does not change the saved visibility or mask selection");
                clickControl(activity[0], R.string.wizard_next);
                awaitText(context.getString(R.string.wizard_height_title)).recycle();
            } finally {
                MaskMode.set(context, savedMask);
            }
            clickControl(activity[0], R.string.calibration_now);
            checkpoint("camera chooser cancellation");
            awaitText(context.getString(R.string.calibration_choose_camera)).recycle();
            click(context.getString(android.R.string.cancel));
            check(MaskMode.autoPackage(context).isEmpty() && settingsMonitor.getHits() == 0,
                    "cancelled camera choice neither selects an app nor requests accessibility");
            clickControl(activity[0], R.string.calibration_skip);
            clickControl(activity[0], R.string.wizard_choose_camera);
            click(context.getString(android.R.string.cancel));
            clickControl(activity[0], R.string.wizard_manual);
            awaitText(context.getString(R.string.wizard_mode_requires_detection)).recycle();
            clickControl(activity[0], R.string.wizard_next);
            clickControl(activity[0], R.string.wizard_finish);
            checkpoint("manual route completes without optional permissions");
            check(prefs.getBoolean("wizard_complete", false) && !MaskMode.accessibilityConsent(context),
                    "only overlay permission and real practice are mandatory");
            clickControl(activity[0], R.string.wizard_reopen);
            clickControl(activity[0], R.string.wizard_next);
            clickSafe(context);
            awaitText(context.getString(R.string.wizard_demo_wait_hide)).recycle();
            clickSafe(context);
            awaitText(context.getString(R.string.wizard_demo_verified)).recycle();
            clickControl(activity[0], R.string.wizard_next);
            clickControl(activity[0], R.string.calibration_skip);
            ResolveInfo camera = context.getPackageManager().resolveActivity(
                    new Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA), 0);
            check(camera != null && camera.activityInfo != null, "test needs an installed camera");
            MaskMode.setAutoPackage(context, camera.activityInfo.packageName);
            onMain(() -> activity[0].finish());
            waitForIdleSync();
            activity[0] = openHome(context);
            clickControl(activity[0], R.string.wizard_next);
            clickControl(activity[0], R.string.enable_detection);
            checkpoint("wizard accessibility disclosure and cancellation");
            awaitText(context.getString(R.string.accessibility_disclosure_title)).recycle();
            check(settingsMonitor.getHits() == 0 && !MaskMode.accessibilityConsent(context),
                    "disclosure precedes consent and Android settings");
            click(context.getString(android.R.string.cancel));
            check(!MaskMode.accessibilityConsent(context), "cancel does not grant consent");
            clickControl(activity[0], R.string.enable_detection);
            click(context.getString(R.string.accessibility_continue));
            check(settingsMonitor.getHits() == 1 && MaskMode.accessibilityConsent(context),
                    "explicit consent opens Android settings");
            clickControl(activity[0], R.string.setup_blocked_title);
            awaitText(context.getString(R.string.setup_blocked_help, context.getString(R.string.app_name))).recycle();
            check(appInfoMonitor.getHits() == 0, "restricted settings are explained first");
            click(context.getString(R.string.setup_app_info));
            check(appInfoMonitor.getHits() == 1, "App info follows affirmative action");
            clickControl(activity[0], R.string.wizard_not_now);
            check(!MaskMode.videoOnly(context), "optional Video-only mode is not silently enabled");
            clickControl(activity[0], R.string.wizard_next);
            clickControl(activity[0], R.string.wizard_finish);
            // Existing contextual permission guidance remains available after the wizard.
            clickControl(activity[0], R.string.automatic_opening);
            clickControl(activity[0], R.string.enable_detection);
            checkpoint("accessibility disclosure and cancellation");
            awaitText(context.getString(R.string.accessibility_disclosure_title)).recycle();
            awaitText(context.getString(R.string.accessibility_disclosure) + "\n\n"
                    + context.getString(R.string.setup_accessibility_help, context.getString(R.string.watcher_name))).recycle();
            click(context.getString(android.R.string.cancel));
            check(settingsMonitor.getHits() == 1, "contextual instructions do not reopen settings on cancel");
            onMain(() -> activity[0].finish());
            activity[0] = startActivitySync(new Intent(context, AboutActivity.class)
                    .setAction(Intent.ACTION_MAIN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            checkpoint("privacy link");
            onMain(() -> {
                Button link = findButton(activity[0].getWindow().getDecorView(),
                        context.getString(R.string.privacy_policy));
                check(link != null, "privacy link is present in About");
                link.performClick();
            });
            check(privacyMonitor.getHits() == 1, "privacy link opens the published HTTPS policy");
            result.putString("result", "PASS: permission gate, real SAFE practice, restart, optional routes, consent, restricted settings and privacy link");
            resultCode = Activity.RESULT_OK;
        } catch (Throwable error) {
            checkpoint("failure: " + error);
            result.putString("result", "FAIL: " + error);
        } finally {
            removeMonitor(settingsMonitor);
            removeMonitor(appInfoMonitor);
            removeMonitor(overlayMonitor);
            removeMonitor(privacyMonitor);
            onMain(() -> {
                if (activity[0] != null) activity[0].finish();
            });
            SharedPreferences.Editor restore = prefs.edit();
            if (hadConsent) restore.putBoolean(MaskMode.ACCESSIBILITY_CONSENT, originalConsent);
            else restore.remove(MaskMode.ACCESSIBILITY_CONSENT);
            if (hadCamera) restore.putString("auto_package", originalCamera);
            else restore.remove("auto_package");
            if (hadVideoOnly) restore.putBoolean("experimental_video_only", originalVideoOnly);
            else restore.remove("experimental_video_only");
            restore.commit();
            SharedPreferences.Editor restoreIntro = prefs.edit();
            for (String key : wizardKeys) {
                Object value = originalWizard.get(key);
                if (value instanceof Boolean) restoreIntro.putBoolean(key, (Boolean) value);
                else if (value instanceof Integer) restoreIntro.putInt(key, (Integer) value);
                else restoreIntro.remove(key);
            }
            restoreIntro.commit();
            onMain(() -> {
                if (originalRunning && Settings.canDrawOverlays(context)) OverlayService.start(context);
                else context.stopService(new Intent(context, OverlayService.class));
            });
            finish(resultCode, result);
        }
    }

    private Activity openHome(Context context) {
        Activity activity = startActivitySync(new Intent(context, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitForIdleSync();
        return activity;
    }

    private void assertNoHome(Activity activity) {
        onMain(() -> check(findButton(activity.getWindow().getDecorView(),
                activity.getString(R.string.show_quick_button)) == null, "home is hidden until setup completes"));
    }

    private void clickSafe(Context context) {
        android.accessibilityservice.AccessibilityServiceInfo info = getUiAutomation().getServiceInfo();
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        getUiAutomation().setServiceInfo(info);
        long deadline = SystemClock.uptimeMillis() + 10000;
        do {
            for (android.view.accessibility.AccessibilityWindowInfo window : getUiAutomation().getWindows()) {
                AccessibilityNodeInfo root = window.getRoot();
                try {
                    AccessibilityNodeInfo button = described(root, context.getString(R.string.quick_button_description));
                    if (button != null) {
                        try { check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK), "click the real overlay button"); }
                        finally { button.recycle(); }
                        waitForIdleSync();
                        return;
                    }
                } finally {
                    if (root != null) root.recycle();
                    window.recycle();
                }
            }
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Real SAFE overlay button missing");
    }

    private AccessibilityNodeInfo described(AccessibilityNodeInfo node, String description) {
        if (node == null) return null;
        if (description.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())) {
            return AccessibilityNodeInfo.obtain(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            try {
                AccessibilityNodeInfo found = described(child, description);
                if (found != null) return found;
            } finally { if (child != null) child.recycle(); }
        }
        return null;
    }

    private void onMain(Runnable action) {
        Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> {
            try { action.run(); }
            catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    private void checkpoint(String step) {
        Bundle status = new Bundle();
        status.putString("step", step);
        sendStatus(0, status);
    }

    private void clickControl(Activity activity, int label) {
        onMain(() -> {
            Button button = findButton(activity.getWindow().getDecorView(), activity.getString(label));
            check(button != null, "missing control " + label);
            button.performClick();
        });
        waitForIdleSync();
    }

    private void click(String text) {
        AccessibilityNodeInfo node = awaitText(text);
        try {
            check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "click " + text);
        } finally {
            node.recycle();
        }
        waitForIdleSync();
    }

    private AccessibilityNodeInfo awaitText(String text) {
        long deadline = SystemClock.uptimeMillis() + 10000;
        do {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                try {
                    for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
                        if (node.getText() != null && text.equalsIgnoreCase(node.getText().toString())) return node;
                        node.recycle();
                    }
                } finally {
                    root.recycle();
                }
            }
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Visible text missing: " + text);
    }

    private static Button findButton(View view, String text) {
        if (view instanceof Button && text.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findButton(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
