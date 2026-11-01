// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Marco Rinaldi (MarcorOnline)

package it.pistacchissimo.socialoverlay;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.SeekBar;

import java.util.ArrayList;

/** Exercises the real home control while the overlay service is already running. */
final class QuickButtonTest {
    static void verify(Instrumentation test) {
        MainActivity activity = (MainActivity) test.startActivitySync(
                new Intent(test.getTargetContext(), MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        int originalOffset = test.getTargetContext().getSharedPreferences("social_overlay", Context.MODE_PRIVATE).getInt("vertical_offset", DeviceProfiles.defaultOffset(test.getTargetContext()));
        Integer originalFine = test.getTargetContext().getSharedPreferences("social_overlay", Context.MODE_PRIVATE).contains("vertical_fine_px")
                ? test.getTargetContext().getSharedPreferences("social_overlay", Context.MODE_PRIVATE).getInt("vertical_fine_px", 0) : null;
        boolean hadOverride = test.getTargetContext().getSharedPreferences("social_overlay", Context.MODE_PRIVATE).contains("vertical_offset");
        Instrumentation.ActivityMonitor aboutMonitor = test.addMonitor(AboutActivity.class.getName(), null, false);
        Activity[] about = new Activity[1];
        try {
            test.waitForIdleSync();
            boolean original = MaskMode.guideVisible(test.getTargetContext());
            MainActivity screen = activity;
            Throwable[] failure = new Throwable[1];
            test.runOnMainSync(() -> {
                try {
                    View root = screen.getWindow().getDecorView();
                    ArrayList<View> statuses = new ArrayList<>();
                    root.findViewsWithText(statuses, screen.getString(R.string.mask_status,
                            MaskMode.label(screen, MaskMode.get(screen))), View.FIND_VIEWS_WITH_TEXT);
                    int[] statusLocation = new int[2];
                    int[] rootLocation = new int[2];
                    statuses.get(0).getLocationOnScreen(statusLocation);
                    root.getLocationOnScreen(rootLocation);
                    int safeTop = rootLocation[1] + root.getRootWindowInsets().getSystemWindowInsetTop();
                    int padding = Math.round(28 * screen.getResources().getDisplayMetrics().density);
                    if (statusLocation[1] < safeTop + padding) {
                        throw new AssertionError("Home top padding must start below system bars: actual="
                                + statusLocation[1] + ", minimum=" + (safeTop + padding));
                    }
                    String showText = screen.getString(R.string.show_quick_button);
                    Button show = findButton(root, showText);
                    if (show == null) throw new AssertionError("Home must keep Mostra bottone rapido");
                    Button customize = findButton(root, screen.getString(R.string.customize_mask));
                    Button automatic = findButton(root, screen.getString(R.string.automatic_opening));
                    Button experimental = findButton(root, screen.getString(R.string.experimental_features));
                    ViewGroup sections = (ViewGroup) customize.getParent();
                    View maskSection = sections.getChildAt(sections.indexOfChild(customize) + 1);
                    View autoSection = sections.getChildAt(sections.indexOfChild(automatic) + 1);
                    View experimentalSection = sections.getChildAt(sections.indexOfChild(experimental) + 1);
                    if (maskSection.getVisibility() != View.GONE || autoSection.getVisibility() != View.GONE
                            || experimentalSection.getVisibility() != View.GONE
                            || !show.isShown()) {
                        throw new AssertionError("Home must start compact with primary action visible");
                    }
                    customize.performClick();
                    if (maskSection.getVisibility() != View.VISIBLE || autoSection.getVisibility() != View.GONE) {
                        throw new AssertionError("Customize must open its own section");
                    }
                    automatic.performClick();
                    if (maskSection.getVisibility() != View.GONE || autoSection.getVisibility() != View.VISIBLE) {
                        throw new AssertionError("Opening automatic must close customize");
                    }
                    experimental.performClick();
                    if (autoSection.getVisibility() != View.GONE || experimentalSection.getVisibility() != View.VISIBLE) {
                        throw new AssertionError("Experimental must open as the third exclusive accordion");
                    }
                    ArrayList<View> help = new ArrayList<>();
                    experimentalSection.findViewsWithText(help, screen.getString(R.string.experimental_video_help_description),
                            View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
                    if (help.size() != 1 || !"?".contentEquals(((Button) help.get(0)).getText())) {
                        throw new AssertionError("Experimental option needs its adjacent accessible help button");
                    }
                    customize.performClick();
                    if (maskSection.getVisibility() != View.VISIBLE || autoSection.getVisibility() != View.GONE
                            || experimentalSection.getVisibility() != View.GONE) {
                        throw new AssertionError("Opening customize must close automatic");
                    }
                    customize.performAccessibilityAction(AccessibilityNodeInfo.ACTION_COLLAPSE, null);
                    if (maskSection.getVisibility() != View.GONE || autoSection.getVisibility() != View.GONE) {
                        throw new AssertionError("Expanded section must also collapse independently");
                    }
                    customize.performAccessibilityAction(AccessibilityNodeInfo.ACTION_EXPAND, null);
                    if (maskSection.getVisibility() != View.VISIBLE || !show.isShown()) {
                        throw new AssertionError("Accessible expand must preserve primary action");
                    }
                    show.performClick();
                    show.performClick();
                    if (!showText.contentEquals(show.getText())) {
                        throw new AssertionError("Home changed into mask toggle");
                    }
                    ArrayList<View> positions = new ArrayList<>();
                    root.findViewsWithText(positions, screen.getString(R.string.vertical_picker_description),
                            View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
                    SeekBar position = (SeekBar) positions.get(0);
                    MaskMode.setVerticalOffset(screen, 60);
                    position.setProgress(420);
                    findButton(root, screen.getString(R.string.reset)).performClick();
                    int expected = DeviceProfiles.defaultOffset(screen);
                    if (position.getProgress() != 360 + expected
                            || MaskMode.verticalOffset(screen) != expected
                            || MaskMode.hasVerticalOffsetOverride(screen)) {
                        throw new AssertionError("Reset did not restore slider and saved default");
                    }
                    ArrayList<View> info = new ArrayList<>();
                    root.findViewsWithText(info, screen.getString(R.string.information),
                            View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
                    if (info.isEmpty()) throw new AssertionError("Missing information button");
                    info.get(0).performClick();
                } catch (Throwable error) { failure[0] = error; }
            });
            if (failure[0] != null) throw new AssertionError(failure[0]);
            about[0] = test.waitForMonitorWithTimeout(aboutMonitor, 5000);
            if (about[0] == null) throw new AssertionError("Information did not open attribution page");
            test.waitForIdleSync();
            test.runOnMainSync(() -> {
                ArrayList<View> attribution = new ArrayList<>();
                View root = about[0].getWindow().getDecorView();
                int[] backLocation = new int[2];
                findButton(root, about[0].getString(R.string.back)).getLocationOnScreen(backLocation);
                int[] rootLocation = new int[2];
                root.getLocationOnScreen(rootLocation);
                int minimum = rootLocation[1] + root.getRootWindowInsets().getSystemWindowInsetTop()
                        + Math.round(24 * about[0].getResources().getDisplayMetrics().density);
                if (backLocation[1] < minimum) {
                    failure[0] = new AssertionError("About top padding must also start below system bars");
                }
                root.findViewsWithText(attribution, about[0].getString(R.string.about_creator), View.FIND_VIEWS_WITH_TEXT);
                if (attribution.isEmpty() || findButton(root, about[0].getString(R.string.project_github)) == null
                        || findButton(root, about[0].getString(R.string.pistacchissimo_article)) == null
                        || findButton(root, about[0].getString(R.string.pistacchissimo_instagram)) == null
                        || findButton(root, about[0].getString(R.string.contact_instagram)) == null) {
                    failure[0] = new AssertionError("Missing creator or source attribution");
                }
            });
            if (failure[0] != null) throw new AssertionError(failure[0]);
            test.waitForIdleSync();
            if (MaskMode.guideVisible(test.getTargetContext()) != original) {
                throw new AssertionError("Home changed saved mask on/off state");
            }
        } finally {
            test.removeMonitor(aboutMonitor);
            test.runOnMainSync(() -> {
                if (about[0] != null) about[0].finish();
                if (hadOverride) MaskMode.setVerticalOffset(test.getTargetContext(), originalOffset);
                else MaskMode.resetVerticalOffset(test.getTargetContext());
                if (originalFine == null) test.getTargetContext().getSharedPreferences("social_overlay", Context.MODE_PRIVATE).edit().remove("vertical_fine_px").apply();
                else test.getTargetContext().getSharedPreferences("social_overlay", Context.MODE_PRIVATE).edit().putInt("vertical_fine_px", originalFine).apply();
                activity.finish();
            });
        }
    }

    private static Button findButton(View view, String text) {
        if (view instanceof Button && text.contentEquals(((Button) view).getText())) {
            return (Button) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findButton(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
