// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Marco Rinaldi (MarcorOnline)

package it.pistacchissimo.socialoverlay;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CheckBox;
import android.widget.SeekBar;

/** Checks upgrades and the actual independent controls without clearing other user settings. */
final class MaskSelectionTest {
    static void verify(Instrumentation test) {
        Context context = test.getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("social_overlay", Context.MODE_PRIVATE);
        boolean hadSelection = prefs.contains("mask_selection");
        int original = MaskMode.get(context);
        boolean hadLegacy = prefs.contains("mask_mode");
        String legacy = prefs.getString("mask_mode", "both");
        boolean hadSubtitleOffset = prefs.contains("subtitle_offset");
        int originalSubtitleOffset = MaskMode.subtitleOffset(context);
        boolean hadGeneralOffset = context.getSharedPreferences("social_overlay", Context.MODE_PRIVATE).contains("vertical_offset");
        int originalGeneralOffset = context.getSharedPreferences("social_overlay", Context.MODE_PRIVATE).getInt("vertical_offset", DeviceProfiles.defaultOffset(context));
        Integer originalFine = context.getSharedPreferences("social_overlay", Context.MODE_PRIVATE).contains("vertical_fine_px")
                ? context.getSharedPreferences("social_overlay", Context.MODE_PRIVATE).getInt("vertical_fine_px", 0) : null;
        MainActivity[] activity = new MainActivity[1];
        try {
            MaskMode.resetSubtitleOffset(context);
            check(MaskMode.subtitleOffset(context) == 0, "existing installs keep the original subtitle position");
            MaskMode.setSubtitleOffset(context, -9999);
            check(MaskMode.subtitleOffset(context) == -MaskMode.SUBTITLE_OFFSET_LIMIT, "lower subtitle limit");
            MaskMode.setSubtitleOffset(context, 9999);
            check(MaskMode.subtitleOffset(context) == MaskMode.SUBTITLE_OFFSET_LIMIT, "upper subtitle limit");
            MaskMode.resetSubtitleOffset(context);
            prefs.edit().remove("mask_selection").remove("mask_mode").commit();
            check(MaskMode.get(context) == MaskMode.BOTH, "fresh install enables only Reel and TikTok");
            String[] oldModes = {"reels", "tiktok", "both", "unknown"};
            int[] expected = {MaskMode.REELS, MaskMode.TIKTOK, MaskMode.BOTH, MaskMode.BOTH};
            for (int i = 0; i < oldModes.length; i++) {
                prefs.edit().putString("mask_mode", oldModes[i]).commit();
                check(MaskMode.get(context) == expected[i], "preserve legacy selection");
            }
            MaskMode.set(context, MaskMode.BOTH);
            activity[0] = open(test, context);
            for (int selection = 0; selection < 8; selection++) {
                int mode = selection;
                onMain(test, () -> {
                    View root = activity[0].getWindow().getDecorView();
                    setCheckbox(root, context.getString(R.string.reels), (mode & MaskMode.REELS) != 0);
                    setCheckbox(root, context.getString(R.string.tiktok), (mode & MaskMode.TIKTOK) != 0);
                    setCheckbox(root, context.getString(R.string.subtitles), (mode & MaskMode.SUBTITLES) != 0);
                    check(MaskMode.get(context) == mode, "checkboxes save every combination, including none");
                    checkSubtitleControls(root, context, (mode & MaskMode.SUBTITLES) != 0);
                    activity[0].finish();
                });
                activity[0] = open(test, context);
                onMain(test, () -> {
                    View root = activity[0].getWindow().getDecorView();
                    check(find(root, context.getString(R.string.reels)).isChecked()
                            == ((mode & MaskMode.REELS) != 0), "Reel restored");
                    check(find(root, context.getString(R.string.tiktok)).isChecked()
                            == ((mode & MaskMode.TIKTOK) != 0), "TikTok restored");
                    check(find(root, context.getString(R.string.subtitles)).isChecked()
                            == ((mode & MaskMode.SUBTITLES) != 0), "subtitles restored");
                    checkSubtitleControls(root, context, (mode & MaskMode.SUBTITLES) != 0);
                });
            }
            onMain(test, () -> {
                View root = activity[0].getWindow().getDecorView();
                SeekBar slider = (SeekBar) described(root, context, R.string.subtitle_vertical_picker_description);
                Bundle value = new Bundle();
                value.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, MaskMode.SUBTITLE_OFFSET_LIMIT - 400);
                check(slider.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), value),
                        "subtitle slider accepts accessible user input");
                check(MaskMode.subtitleOffset(context) == -400, "subtitle slider saves relative dp");
                described(root, context, R.string.reset_description).performClick();
                check(MaskMode.subtitleOffset(context) == -400, "general reset preserves subtitle offset");
                setCheckbox(root, context.getString(R.string.subtitles), false);
                checkSubtitleControls(root, context, false);
                setCheckbox(root, context.getString(R.string.subtitles), true);
                check(slider.getProgress() == MaskMode.SUBTITLE_OFFSET_LIMIT - 400, "toggle preserves subtitle position");
                activity[0].finish();
            });
            activity[0] = open(test, context);
            onMain(test, () -> {
                View root = activity[0].getWindow().getDecorView();
                SeekBar slider = (SeekBar) described(root, context, R.string.subtitle_vertical_picker_description);
                check(slider.getProgress() == MaskMode.SUBTITLE_OFFSET_LIMIT - 400, "subtitle position restored on reopen");
                MaskMode.setVerticalOffset(context, 57);
                described(root, context, R.string.subtitle_reset_description).performClick();
                check(MaskMode.subtitleOffset(context) == 0 && slider.getProgress() == MaskMode.SUBTITLE_OFFSET_LIMIT
                        && MaskMode.verticalOffset(context) == 57, "subtitle reset leaves general position alone");
            });
        } finally {
            test.runOnMainSync(() -> {
                if (activity[0] != null) activity[0].finish();
            });
            SharedPreferences.Editor restore = prefs.edit();
            if (hadSelection) restore.putInt("mask_selection", original);
            else restore.remove("mask_selection");
            if (hadLegacy) restore.putString("mask_mode", legacy);
            else restore.remove("mask_mode");
            if (hadSubtitleOffset) restore.putInt("subtitle_offset", originalSubtitleOffset);
            else restore.remove("subtitle_offset");
            if (hadGeneralOffset) restore.putInt("vertical_offset", originalGeneralOffset);
            else restore.remove("vertical_offset");
            if (originalFine != null) restore.putInt("vertical_fine_px", originalFine);
            else restore.remove("vertical_fine_px");
            restore.commit();
        }
    }

    private static MainActivity open(Instrumentation test, Context context) {
        MainActivity activity = (MainActivity) test.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        test.waitForIdleSync();
        onMain(test, () -> {
            java.util.ArrayList<View> headers = new java.util.ArrayList<>();
            activity.getWindow().getDecorView().findViewsWithText(headers,
                    context.getString(R.string.customize_mask), View.FIND_VIEWS_WITH_TEXT);
            headers.removeIf(view -> !(view instanceof android.widget.Button));
            check(headers.size() == 1, "customize accordion header available");
            headers.get(0).performClick();
        });
        return activity;
    }

    private static View described(View root, Context context, int description) {
        java.util.ArrayList<View> matches = new java.util.ArrayList<>();
        root.findViewsWithText(matches, context.getString(description), View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        check(matches.size() == 1, "missing or ambiguous control");
        return matches.get(0);
    }

    private static void checkSubtitleControls(View root, Context context, boolean enabled) {
        check(described(root, context, R.string.subtitle_vertical_picker_description).isEnabled() == enabled
                && described(root, context, R.string.subtitle_reset_description).isEnabled() == enabled,
                "subtitle slider and reset enabled only with subtitles selected");
    }

    private static void setCheckbox(View root, String text, boolean checked) {
        CheckBox checkbox = find(root, text);
        check(checkbox != null, "missing checkbox: " + text);
        if (checkbox.isChecked() != checked) checkbox.performClick();
    }

    private static CheckBox find(View view, String text) {
        if (view instanceof CheckBox && text.contentEquals(((CheckBox) view).getText())) return (CheckBox) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                CheckBox found = find(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void onMain(Instrumentation test, Runnable action) {
        Throwable[] failure = new Throwable[1];
        test.runOnMainSync(() -> {
            try { action.run(); } catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
