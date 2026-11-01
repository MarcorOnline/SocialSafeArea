// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Marco Rinaldi (MarcorOnline)

package it.pistacchissimo.socialoverlay;

import android.content.Context;
import android.content.SharedPreferences;

/** Small persisted state for the composition guide. */
final class MaskMode {
    static final int REELS = 1;
    static final int TIKTOK = 2;
    static final int SUBTITLES = 4;
    static final int BOTH = REELS | TIKTOK;
    static final int SUBTITLE_OFFSET_LIMIT = 720;
    private static final String PREFERENCES = "social_overlay";
    private static final String KEY = "mask_mode";
    private static final String SELECTION = "mask_selection";
    private static final String BUTTON_X = "button_x";
    private static final String BUTTON_Y = "button_y";
    private static final String GUIDE_VISIBLE = "guide_visible";
    private static final String OPACITY = "opacity";
    private static final String VERTICAL_OFFSET = "vertical_offset";
    private static final String VERTICAL_FINE_PX = "vertical_fine_px";
    private static final String ADJUSTMENT_ARROWS = "adjustment_arrows";
    private static final String SUBTITLE_OFFSET = "subtitle_offset";
    private static final String AUTO_PACKAGE = "auto_package";
    private static final String DISMISSED = "dismissed";
    private static final String VIDEO_ONLY = "experimental_video_only";
    static final String ACCESSIBILITY_CONSENT = "accessibility_consent";

    private MaskMode() {
    }

    static int get(Context context) {
        SharedPreferences prefs = preferences(context);
        if (prefs.contains(SELECTION)) return prefs.getInt(SELECTION, BOTH) & (BOTH | SUBTITLES);
        // Preserve the platform chosen before independent mask switches were introduced.
        String legacy = prefs.getString(KEY, "both");
        if ("reels".equals(legacy)) return REELS;
        if ("tiktok".equals(legacy)) return TIKTOK;
        return BOTH;
    }

    static void set(Context context, int mode) {
        preferences(context).edit().putInt(SELECTION, mode & (BOTH | SUBTITLES)).apply();
    }

    static String label(Context context, int mode) {
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        if ((mode & REELS) != 0) labels.add(context.getString(R.string.reels));
        if ((mode & TIKTOK) != 0) labels.add(context.getString(R.string.tiktok));
        if ((mode & SUBTITLES) != 0) labels.add(context.getString(R.string.subtitles));
        return labels.isEmpty() ? context.getString(R.string.no_masks) : String.join(" + ", labels);
    }

    static int opacity(Context context) {
        return preferences(context).getInt(OPACITY, 23);
    }

    static void setOpacity(Context context, int value) {
        preferences(context).edit().putInt(OPACITY, Math.max(8, Math.min(70, value))).apply();
    }

    /** Rounded dp for the slider and community JSON; drawing keeps the exact pixel correction. */
    static int verticalOffset(Context context) {
        return Math.round(verticalOffsetPx(context) / context.getResources().getDisplayMetrics().density);
    }

    static float verticalOffsetPx(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        return preferences(context).getInt(VERTICAL_OFFSET, DeviceProfiles.defaultOffset(context)) * density
                + preferences(context).getInt(VERTICAL_FINE_PX, 0);
    }

    /** Use the same whole-dp position and limits as the slider. */
    static void nudgeVerticalOffset(Context context, int dp) {
        setVerticalOffset(context, verticalOffset(context) + dp);
    }

    static boolean adjustmentArrows(Context context) {
        SharedPreferences prefs = preferences(context);
        return prefs.contains(ADJUSTMENT_ARROWS) ? prefs.getBoolean(ADJUSTMENT_ARROWS, false)
                : !prefs.getBoolean("calibration_complete", false) && !DeviceProfiles.hasExactProfile(context);
    }

    static void setAdjustmentArrows(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(ADJUSTMENT_ARROWS, enabled).apply();
    }

    static boolean hasVerticalOffsetOverride(Context context) {
        return preferences(context).contains(VERTICAL_OFFSET) || preferences(context).getInt(VERTICAL_FINE_PX, 0) != 0;
    }

    static void resetVerticalOffset(Context context) {
        preferences(context).edit().remove(VERTICAL_OFFSET).remove(VERTICAL_FINE_PX).apply();
    }

    static void setVerticalOffset(Context context, int value) {
        preferences(context).edit()
                .putInt(VERTICAL_OFFSET, Math.max(-360, Math.min(360, value))).remove(VERTICAL_FINE_PX).apply();
    }

    /** Additional dp relative to the general guide position; zero preserves the original box. */
    static int subtitleOffset(Context context) {
        return preferences(context).getInt(SUBTITLE_OFFSET, 0);
    }

    static void setSubtitleOffset(Context context, int value) {
        preferences(context).edit().putInt(SUBTITLE_OFFSET,
                Math.max(-SUBTITLE_OFFSET_LIMIT, Math.min(SUBTITLE_OFFSET_LIMIT, value))).apply();
    }

    static void resetSubtitleOffset(Context context) {
        preferences(context).edit().remove(SUBTITLE_OFFSET).apply();
    }

    static boolean guideVisible(Context context) {
        return preferences(context).getBoolean(GUIDE_VISIBLE, true);
    }

    static void setGuideVisible(Context context, boolean visible) {
        preferences(context).edit().putBoolean(GUIDE_VISIBLE, visible).apply();
    }

    static String autoPackage(Context context) {
        return preferences(context).getString(AUTO_PACKAGE, "");
    }

    static boolean videoOnly(Context context) {
        return preferences(context).getBoolean(VIDEO_ONLY, false);
    }

    static boolean accessibilityConsent(Context context) {
        return preferences(context).getBoolean(ACCESSIBILITY_CONSENT, false);
    }

    static void acceptAccessibilityConsent(Context context) {
        preferences(context).edit().putBoolean(ACCESSIBILITY_CONSENT, true).apply();
    }

    static void setVideoOnly(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(VIDEO_ONLY, enabled).apply();
    }

    static void setAutoPackage(Context context, String packageName) {
        preferences(context).edit().putString(AUTO_PACKAGE, packageName).apply();
    }

    static boolean dismissed(Context context) {
        return preferences(context).getBoolean(DISMISSED, false);
    }

    static void setDismissed(Context context, boolean dismissed) {
        preferences(context).edit().putBoolean(DISMISSED, dismissed).apply();
    }

    static int buttonX(Context context, int maxX, int defaultInset) {
        float saved = preferences(context).getFloat(BUTTON_X, -1f);
        return saved < 0f ? Math.max(0, maxX - defaultInset) : Math.round(saved * maxX);
    }

    static int buttonY(Context context, int maxY) {
        float saved = preferences(context).getFloat(BUTTON_Y, -1f);
        return saved < 0f ? maxY / 2 : Math.round(saved * maxY);
    }

    static void saveButtonPosition(Context context, int x, int y, int maxX, int maxY) {
        preferences(context).edit()
                .putFloat(BUTTON_X, maxX == 0 ? 0f : (float) x / maxX)
                .putFloat(BUTTON_Y, maxY == 0 ? 0f : (float) y / maxY)
                .apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
