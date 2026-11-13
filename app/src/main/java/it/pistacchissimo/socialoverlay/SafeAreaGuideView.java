// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Marco Rinaldi (MarcorOnline)

package it.pistacchissimo.socialoverlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

/** Draws a tap-through, resolution-independent 9:16 social composition guide. */
final class SafeAreaGuideView extends View {
    private static final float FRAME_WIDTH = 1080f;
    private static final float FRAME_HEIGHT = 1920f;
    private static final float PREVIEW_TOP = 184f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] windowOrigin = new int[2];
    private final Point screenSize = new Point();
    private float frameScale = 1f;
    private final boolean tutorial;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { }
        @Override public void onDisplayRemoved(int displayId) { }
        @Override public void onDisplayChanged(int displayId) { invalidate(); }
    };

    SafeAreaGuideView(Context context) {
        this(context, false);
    }

    SafeAreaGuideView(Context context, boolean tutorial) {
        super(context);
        this.tutorial = tutorial;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getContext().getSystemService(DisplayManager.class).registerDisplayListener(displayListener, getHandler());
    }

    @Override protected void onDetachedFromWindow() {
        getContext().getSystemService(DisplayManager.class).unregisterDisplayListener(displayListener);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int rotation = readScreenSize();
        getLocationOnScreen(windowOrigin);
        boolean sideways = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
        int panelWidth = sideways ? screenSize.y : screenSize.x;
        // The 9:16 preview spans the panel width; fitting the reference screen height adds borders.
        frameScale = panelWidth / FRAME_WIDTH;
        float top = PREVIEW_TOP * frameScale
                + MaskMode.verticalOffsetPx(getContext());
        canvas.save();
        // Android may inset an overlay below the cutout/status bar. Convert screen to local pixels.
        canvas.translate(-windowOrigin[0], -windowOrigin[1]);
        // Keep the guide anchored to the physical panel, undoing Android's display rotation.
        switch (rotation) {
            case Surface.ROTATION_90:
                canvas.translate(0, screenSize.y);
                canvas.rotate(-90);
                break;
            case Surface.ROTATION_180:
                canvas.translate(screenSize.x, screenSize.y);
                canvas.rotate(180);
                break;
            case Surface.ROTATION_270:
                canvas.translate(screenSize.x, 0);
                canvas.rotate(90);
                break;
            default:
                break;
        }
        canvas.translate(0, top);
        canvas.scale(frameScale, frameScale);
        canvas.clipRect(0, 0, FRAME_WIDTH, FRAME_HEIGHT);
        int mode = tutorial ? MaskMode.BOTH : MaskMode.get(getContext());
        int reels = getContext().getColor(R.color.mask_reels);
        int tiktok = getContext().getColor(R.color.mask_tiktok);
        if ((mode & MaskMode.TIKTOK) != 0) {
            drawSafeZone(canvas, tiktok, 173, 767, 842, 1382, 1748);
        }
        if ((mode & MaskMode.REELS) != 0) {
            drawSafeZone(canvas, reels, 154, 806, 864, 1420, 1728);
        }
        if ((mode & MaskMode.SUBTITLES) != 0) {
            int subtitles = getContext().getColor(R.color.mask_subtitles);
            // Default: centred, with a 24-unit gap above TikTok's lower band.
            float subtitleLeft = (FRAME_WIDTH - 560f) / 2f;
            float subtitleRight = FRAME_WIDTH - subtitleLeft;
            canvas.save();
            canvas.translate(0, dp(MaskMode.subtitleOffset(getContext())) / frameScale);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(subtitles);
            paint.setAlpha(Math.round(MaskMode.opacity(getContext()) * 2.55f));
            canvas.drawRect(subtitleLeft, 1178, subtitleRight, 1358, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1) / frameScale);
            paint.setColor(subtitles);
            canvas.drawRect(subtitleLeft, 1178, subtitleRight, 1358, paint);
            canvas.restore();
            drawLabel(canvas, getContext().getString(R.string.subtitles), subtitles, 464, 60);
        }
        if ((mode & MaskMode.REELS) != 0) {
            drawLabel(canvas, getContext().getString(R.string.reels), reels, 24, 60);
        }
        if ((mode & MaskMode.TIKTOK) != 0) {
            drawLabel(canvas, getContext().getString(R.string.tiktok), tiktok, 244, 60);
        }
        canvas.restore();
    }

    /** Draws the four forbidden bands that leave the platform's reverse-L safe area clear. */
    private void drawSafeZone(Canvas canvas, int color, float topY, float sideY,
            float sideX, float lowerY, float bottomY) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(Math.round(MaskMode.opacity(getContext()) * 2.55f),
                Color.red(color), Color.green(color), Color.blue(color)));
        canvas.drawRect(0, 0, FRAME_WIDTH, topY, paint);
        canvas.drawRect(sideX, sideY, FRAME_WIDTH, bottomY, paint);
        canvas.drawRect(0, lowerY, sideX, bottomY, paint);
        canvas.drawRect(0, bottomY, FRAME_WIDTH, FRAME_HEIGHT, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1) / frameScale);
        paint.setColor(color);
        canvas.drawLine(0, topY, FRAME_WIDTH, topY, paint);
        canvas.drawLine(sideX, sideY, FRAME_WIDTH, sideY, paint);
        canvas.drawLine(sideX, sideY, sideX, bottomY, paint);
        canvas.drawLine(0, lowerY, sideX, lowerY, paint);
        canvas.drawLine(0, bottomY, FRAME_WIDTH, bottomY, paint);
    }

    private void drawLabel(Canvas canvas, String text, int color, float x, float baseline) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(FRAME_WIDTH * .03f);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        canvas.drawText(text, x, baseline, paint);
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @SuppressWarnings("deprecation")
    private int readScreenSize() {
        Display display = getDisplay();
        if (display == null) display = getContext().getSystemService(WindowManager.class).getDefaultDisplay();
        display.getRealSize(screenSize);
        return display.getRotation();
    }
}
