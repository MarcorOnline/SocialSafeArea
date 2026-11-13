// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Marco Rinaldi (MarcorOnline)

package it.pistacchissimo.socialoverlay;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Point;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowManager;

/** Compares the real guide in physical panel coordinates through all display rotations. */
public final class GuideRotationTest extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override @SuppressWarnings("deprecation") public void onStart() {
        Context context = getTargetContext();
        WindowManager manager = context.getSystemService(WindowManager.class);
        SafeAreaGuideView[] guide = new SafeAreaGuideView[1];
        int originalMode = MaskMode.get(context);
        int originalOffset = context.getSharedPreferences("social_overlay", Context.MODE_PRIVATE).getInt("vertical_offset", DeviceProfiles.defaultOffset(context));
        Integer originalFine = context.getSharedPreferences("social_overlay", Context.MODE_PRIVATE).contains("vertical_fine_px")
                ? context.getSharedPreferences("social_overlay", Context.MODE_PRIVATE).getInt("vertical_fine_px", 0) : null;
        boolean hadOverride = context.getSharedPreferences("social_overlay", Context.MODE_PRIVATE).contains("vertical_offset");
        int originalOpacity = MaskMode.opacity(context);
        android.content.SharedPreferences prefs = context.getSharedPreferences("social_overlay", Context.MODE_PRIVATE);
        boolean hadWizard = prefs.contains("wizard_complete");
        boolean savedWizard = prefs.getBoolean("wizard_complete", false);
        boolean hadSubtitleOffset = prefs.contains("subtitle_offset");
        int originalSubtitleOffset = MaskMode.subtitleOffset(context);
        boolean autoRotation = Settings.System.getInt(context.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, 1) != 0;
        int userRotation = Settings.System.getInt(context.getContentResolver(),
                Settings.System.USER_ROTATION, 0);
        Bundle result = new Bundle();
        try {
            prefs.edit().putBoolean("wizard_complete", true).commit();
            startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            rotate(manager, 0);
            runOnMainSync(() -> {
                MaskMode.set(context, MaskMode.BOTH | MaskMode.SUBTITLES);
                MaskMode.setOpacity(context, 23);
                MaskMode.setVerticalOffset(context, 42);
                MaskMode.setSubtitleOffset(context, -240);
                guide[0] = new SafeAreaGuideView(context);
                manager.addView(guide[0], OverlayService.guideLayoutParams());
            });
            waitForIdleSync();
            Bitmap reference = render(guide[0], manager);
            check(reference.getWidth() == 1080 && reference.getHeight() == 2340,
                    "requires the 1080x2340 reference panel");
            check(android.graphics.Color.alpha(reference.getPixel(500, 410)) > 0,
                    "reference mask must be drawn");
            float frameTop = 184 + 42 * context.getResources().getDisplayMetrics().density;
            for (int rotation : new int[] {1, 3, 2, 0}) {
                rotate(manager, rotation);
                Bitmap actual = render(guide[0], manager);
                for (int y = 210; y < 2240; y += 40) {
                    // Rotated text uses different glyph antialiasing; compare the mask's bands.
                    if (y >= frameTop + 20 && y < frameTop + 80) continue;
                    for (int x = 20; x < 1080; x += 40) {
                        int px = rotation == 1 ? y : rotation == 2 ? 1079 - x
                                : rotation == 3 ? 2339 - y : x;
                        int py = rotation == 1 ? 1079 - x : rotation == 2 ? 2339 - y
                                : rotation == 3 ? x : y;
                        check(reference.getPixel(x, y) == actual.getPixel(px, py),
                                "mask moved on physical panel at rotation=" + rotation
                                        + ", portrait pixel=" + x + "," + y
                                        + ", expected=" + Integer.toHexString(reference.getPixel(x, y))
                                        + ", actual=" + Integer.toHexString(actual.getPixel(px, py)));
                    }
                }
                actual.recycle();
            }
            reference.recycle();
            result.putString("stream", "PASS: guide stays fixed on panel at 0/90/180/270 degrees, including general and subtitle offsets.\n");
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error + "\n");
        } finally {
            android.content.SharedPreferences.Editor restoreWizard = prefs.edit();
            if (hadWizard) restoreWizard.putBoolean("wizard_complete", savedWizard);
            else restoreWizard.remove("wizard_complete");
            restoreWizard.commit();
            getUiAutomation().setRotation(0);
            runOnMainSync(() -> {
                if (guide[0] != null && guide[0].isAttachedToWindow()) manager.removeViewImmediate(guide[0]);
                MaskMode.set(context, originalMode);
                MaskMode.setOpacity(context, originalOpacity);
                if (hadOverride) MaskMode.setVerticalOffset(context, originalOffset);
                else MaskMode.resetVerticalOffset(context);
                if (originalFine == null) context.getSharedPreferences("social_overlay", Context.MODE_PRIVATE).edit().remove("vertical_fine_px").apply();
                else context.getSharedPreferences("social_overlay", Context.MODE_PRIVATE).edit().putInt("vertical_fine_px", originalFine).apply();
                if (hadSubtitleOffset) MaskMode.setSubtitleOffset(context, originalSubtitleOffset);
                else MaskMode.resetSubtitleOffset(context);
            });
            getUiAutomation().setRotation(autoRotation ? -2 : userRotation);
        }
        finish(result.getString("stream", "").startsWith("PASS") ? -1 : 0, result);
    }

    @SuppressWarnings("deprecation") private void rotate(WindowManager manager, int rotation)
            throws InterruptedException {
        check(getUiAutomation().setRotation(rotation), "rotation request accepted");
        for (int i = 0; i < 50; i++) {
            Thread.sleep(100);
            if (manager.getDefaultDisplay().getRotation() == rotation) {
                waitForIdleSync();
                Thread.sleep(400); // Allow the overlay's layout to follow the display configuration.
                return;
            }
        }
        throw new AssertionError("display did not rotate to " + rotation);
    }

    @SuppressWarnings("deprecation") private Bitmap render(SafeAreaGuideView guide, WindowManager manager) {
        Point size = new Point();
        manager.getDefaultDisplay().getRealSize(size);
        Bitmap bitmap = Bitmap.createBitmap(size.x, size.y, Bitmap.Config.ARGB_8888);
        runOnMainSync(() -> {
            Canvas canvas = new Canvas(bitmap);
            int[] origin = new int[2];
            guide.getLocationOnScreen(origin);
            canvas.translate(origin[0], origin[1]);
            guide.draw(canvas);
        });
        return bitmap;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
