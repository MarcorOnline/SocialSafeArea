// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Marco Rinaldi (MarcorOnline)

package it.pistacchissimo.socialoverlay;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowManager;

/** Checks real overlay coverage on any panel size, including shorter and rotated displays. */
public final class GuideViewportTest extends Instrumentation {
    private Integer expectedFrameTop;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (arguments != null && arguments.containsKey("frame_top")) {
            expectedFrameTop = Integer.parseInt(arguments.getString("frame_top"));
        }
        start();
    }

    @Override @SuppressWarnings("deprecation") public void onStart() {
        Context context = getTargetContext();
        WindowManager manager = context.getSystemService(WindowManager.class);
        SharedPreferences prefs = context.getSharedPreferences("social_overlay", Context.MODE_PRIVATE);
        Integer offset = prefs.contains("vertical_offset") ? prefs.getInt("vertical_offset", 0) : null;
        Integer fine = prefs.contains("vertical_fine_px") ? prefs.getInt("vertical_fine_px", 0) : null;
        boolean autoRotation = Settings.System.getInt(context.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, 1) != 0;
        int userRotation = Settings.System.getInt(context.getContentResolver(), Settings.System.USER_ROTATION, 0);
        SafeAreaGuideView[] guide = new SafeAreaGuideView[1];
        Bundle result = new Bundle();
        try {
            onMain(() -> {
                MaskMode.resetVerticalOffset(context);
                guide[0] = new SafeAreaGuideView(context, true);
                manager.addView(guide[0], OverlayService.guideLayoutParams());
            });
            for (int rotation : new int[] {0, 1, 3, 2}) {
                check(getUiAutomation().setRotation(rotation), "rotation request accepted");
                for (int attempt = 0; attempt < 50; attempt++) {
                    Thread.sleep(100);
                    if (manager.getDefaultDisplay().getRotation() == rotation) break;
                }
                check(manager.getDefaultDisplay().getRotation() == rotation, "display rotation did not settle");
                waitForIdleSync();
                Thread.sleep(400);
                Point size = new Point();
                manager.getDefaultDisplay().getRealSize(size);
                onMain(() -> verify(guide[0], size, rotation));
            }
            result.putString("stream", "PASS: guide reaches both physical panel edges and its window covers the full display at 0/90/180/270 degrees.\n");
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error + "\n");
        } finally {
            onMain(() -> {
                if (guide[0] != null && guide[0].isAttachedToWindow()) manager.removeViewImmediate(guide[0]);
                SharedPreferences.Editor restore = prefs.edit();
                if (offset == null) restore.remove("vertical_offset"); else restore.putInt("vertical_offset", offset);
                if (fine == null) restore.remove("vertical_fine_px"); else restore.putInt("vertical_fine_px", fine);
                restore.commit();
            });
            getUiAutomation().setRotation(autoRotation ? -2 : userRotation);
        }
        finish(result.getString("stream", "").startsWith("PASS") ? -1 : 0, result);
    }

    private void verify(SafeAreaGuideView guide, Point size, int rotation) {
        int[] origin = new int[2];
        guide.getLocationOnScreen(origin);
        Bitmap render = Bitmap.createBitmap(size.x, size.y, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(render);
            canvas.translate(origin[0], origin[1]);
            canvas.clipRect(0, 0, guide.getWidth(), guide.getHeight());
            guide.draw(canvas);
            boolean sideways = rotation == 1 || rotation == 3;
            int width = sideways ? size.y : size.x;
            float scale = width / 1080f;
            float top = 184 * scale + MaskMode.verticalOffsetPx(getTargetContext());
            if (expectedFrameTop != null) {
                // Profiles store whole dp, so the closest alignment can differ by half a dp.
                float tolerance = Math.max(1f, getTargetContext().getResources().getDisplayMetrics().density / 2f);
                check(Math.abs(top - expectedFrameTop) <= tolerance, "camera profile must align frame top to " + expectedFrameTop
                        + ", actual=" + top + ", width=" + width + ", offset=" + MaskMode.verticalOffsetPx(getTargetContext())
                        + ", rotation=" + rotation);
            }
            int topBandY = Math.round(top + 3);
            int bottomBandY = Math.round(top + 1920 * scale - 4);
            for (int y : new int[] {topBandY, bottomBandY}) {
                for (int x : new int[] {0, width - 1}) {
                    int px = rotation == 1 ? y : rotation == 2 ? size.x - 1 - x
                            : rotation == 3 ? size.x - 1 - y : x;
                    int py = rotation == 1 ? size.y - 1 - x : rotation == 2 ? size.y - 1 - y
                            : rotation == 3 ? x : y;
                    check(Color.alpha(render.getPixel(px, py)) > 0,
                            "mask leaves an unpainted edge at rotation=" + rotation + ", panel pixel=" + x + "," + y);
                }
            }
            check(origin[0] == 0 && origin[1] == 0 && guide.getWidth() == size.x && guide.getHeight() == size.y,
                    "overlay window is inset: origin=" + origin[0] + "," + origin[1]
                            + ", window=" + guide.getWidth() + "x" + guide.getHeight() + ", panel=" + size);
        } finally {
            render.recycle();
        }
    }

    private void onMain(Runnable action) {
        Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> {
            try { action.run(); } catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
