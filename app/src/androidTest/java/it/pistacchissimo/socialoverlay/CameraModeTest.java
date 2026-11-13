// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Marco Rinaldi (MarcorOnline)

package it.pistacchissimo.socialoverlay;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Checks ambiguous labels, exact profile matching and temporary visibility independently of manual state. */
final class CameraModeTest {
    static void verify(Instrumentation test) throws Exception {
        Context context = test.getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("social_overlay", Context.MODE_PRIVATE);
        boolean hadSetting = prefs.contains("experimental_video_only");
        boolean enabled = MaskMode.videoOnly(context);
        boolean manual = MaskMode.guideVisible(context);
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        try {
            prefs.edit().remove("experimental_video_only").commit();
            check(!MaskMode.videoOnly(context), "experimental detection defaults to off");
            JSONObject rule = new JSONObject().put("mode", "video")
                    .put("view_id", "example.camera:id/mode")
                    .put("content_description", "Video, Mode");
            JSONObject detection = new JSONObject().put("orientation", "portrait")
                    .put("rules", new JSONArray().put(rule));
            JSONObject entry = new JSONObject().put("manufacturer", "brand").put("model", "model")
                    .put("android_sdk", 35).put("camera_package", "example.camera")
                    .put("camera_version", "1.0").put("vertical_offset_dp", 0)
                    .put("mode_detection", detection);
            JSONArray entries = new JSONArray().put(entry);
            check(DeviceProfiles.modeDetection(entries, "BRAND", "MODEL", 35, "example.camera", "1.0") == detection,
                    "exact profile supports mode detection");
            check(DeviceProfiles.modeDetection(entries, "other", "model", 35, "example.camera", "1.0") == null
                    && DeviceProfiles.modeDetection(entries, "brand", "other", 35, "example.camera", "1.0") == null
                    && DeviceProfiles.modeDetection(entries, "brand", "model", 36, "example.camera", "1.0") == null
                    && DeviceProfiles.modeDetection(entries, "brand", "model", 35, "other.camera", "1.0") == null
                    && DeviceProfiles.modeDetection(entries, "brand", "model", 35, "example.camera", "1.1") == null,
                    "mode rules never inherit approximate profiles");
            node.setVisibleToUser(true);
            node.setContentDescription("Fotografia, Modalità");
            Rect window = new Rect(0, 0, 1080, 2340);
            check(!CameraModeDetector.matches(node, rule, window), "photo is not video");
            node.setContentDescription("Video, Mode");
            check(CameraModeDetector.matches(node, rule, window), "matching selector description");
            rule.remove("content_description");
            rule.put("text", "VIDEO").put("selected", true);
            node.setText("VIDEO");
            check(!CameraModeDetector.matches(node, rule, window), "adjacent video label is not selected");
            node.setSelected(true);
            check(CameraModeDetector.matches(node, rule, window), "selected video label");
            rule.remove("selected");
            rule.put("bounds", new JSONArray("[0.45,0.9,0.55,0.95]"));
            node.setBoundsInScreen(new Rect(720, 2119, 846, 2197));
            check(!CameraModeDetector.matches(node, rule, window), "adjacent carousel item outside box");
            node.setBoundsInScreen(new Rect(473, 2117, 608, 2197));
            check(CameraModeDetector.matches(node, rule, window), "centered carousel item inside box");
            node.setVisibleToUser(false);
            check(!CameraModeDetector.matches(node, rule, window), "invisible controls ignored");

            JSONArray shipped;
            try (InputStream input = context.getAssets().open("camera-profiles.json");
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                shipped = new JSONArray(output.toString("UTF-8"));
            }
            JSONObject samsung = DeviceProfiles.modeDetection(shipped, "samsung", "SM-S948B", 37,
                    "com.sec.android.app.camera", "17.0.00.43");
            check(samsung != null, "Samsung language profile exists");
            JSONArray localizedRules = samsung.getJSONArray("rules");
            String[][] descriptions = {
                {"Video, Modalità", "Fotografia, Modalità"}, {"Video, Mode", "Photo, Mode"},
                {"Vidéo, Mode", "Photo, Mode"}, {"Vídeo, Modo", "Foto, Modo"},
                {"Video, Modus", "Foto, Modus"}, {"Unsupported language", "Unsupported language"}
            };
            node.setVisibleToUser(true);
            for (int language = 0; language < descriptions.length; language++) {
                for (int mode = 0; mode < 2; mode++) {
                    node.setContentDescription(descriptions[language][mode]);
                    String found = "unknown";
                    for (int i = 0; i < localizedRules.length(); i++) {
                        JSONObject localizedRule = localizedRules.getJSONObject(i);
                        if (CameraModeDetector.matches(node, localizedRule, window)) {
                            check(found.equals("unknown") || found.equals(localizedRule.getString("mode")),
                                    "localized rules must not conflict");
                            found = localizedRule.getString("mode");
                        }
                    }
                    check(found.equals(language == 5 ? "unknown" : mode == 0 ? "video" : "photo"),
                            "Samsung language " + language + " mode " + mode);
                }
            }

            JSONObject google = DeviceProfiles.modeDetection(shipped, "Google", "Pixel 2 XL", 30,
                    "com.google.android.GoogleCamera", "8.2.204.362396359.12");
            check(google != null, "verified Google Camera profile exists");
            JSONArray googleRules = google.getJSONArray("rules");
            String[][] googleDescriptions = {
                {"Avvia video", "Scatta foto"}, {"Start video", "Take photo"},
                {"Démarrer la vidéo", "Prendre une photo"}, {"Iniciar vídeo", "Hacer una foto"},
                {"Video starten", "Foto aufnehmen"}, {"Video", "Unsupported language"}
            };
            for (int language = 0; language < googleDescriptions.length; language++) {
                for (int mode = 0; mode < 2; mode++) {
                    node.setContentDescription(googleDescriptions[language][mode]);
                    String found = "unknown";
                    for (int i = 0; i < googleRules.length(); i++) {
                        JSONObject googleRule = googleRules.getJSONObject(i);
                        check(googleRule.getString("view_id").equals("com.google.android.GoogleCamera:id/shutter_button"),
                                "Google rules only match the shutter control");
                        if (CameraModeDetector.matches(node, googleRule, window)) {
                            check(found.equals("unknown") || found.equals(googleRule.getString("mode")),
                                    "Google language rules must not conflict");
                            found = googleRule.getString("mode");
                        }
                    }
                    check(found.equals(language == 5 ? "unknown" : mode == 0 ? "video" : "photo"),
                            "Google language " + language + " mode " + mode);
                }
            }

            test.runOnMainSync(() -> {
                MaskMode.setVideoOnly(context, true);
                MaskMode.setGuideVisible(context, true);
                OverlayService.setPhotoMode(context, false);
                OverlayService.start(context);
            });
            test.waitForIdleSync();
            check(awaitGuide(true), "video preserves manually enabled guide");
            test.runOnMainSync(() -> OverlayService.setPhotoMode(context, true));
            test.waitForIdleSync();
            check(awaitGuide(false) && MaskMode.guideVisible(context), "photo hides without saving manual OFF");
            test.runOnMainSync(() -> OverlayService.setPhotoMode(context, false));
            test.waitForIdleSync();
            check(awaitGuide(true), "video restores guide");
            test.runOnMainSync(() -> {
                MaskMode.setGuideVisible(context, false);
                OverlayService.setPhotoMode(context, true);
                OverlayService.setPhotoMode(context, false);
            });
            test.waitForIdleSync();
            check(awaitGuide(false) && !MaskMode.guideVisible(context), "automatic changes preserve manual OFF");
            test.runOnMainSync(() -> {
                MaskMode.setGuideVisible(context, true);
                OverlayService.setPhotoMode(context, true);
                MaskMode.setVideoOnly(context, false);
                OverlayService.refreshMask(context);
            });
            test.waitForIdleSync();
            check(awaitGuide(true), "disabling experiment immediately releases photo suppression");
        } finally {
            node.recycle();
            test.runOnMainSync(() -> {
                MaskMode.setGuideVisible(context, manual);
                if (hadSetting) MaskMode.setVideoOnly(context, enabled);
                else prefs.edit().remove("experimental_video_only").apply();
                OverlayService.setPhotoMode(context, false);
                OverlayService.refreshMask(context);
            });
        }
    }

    private static boolean guideVisible() throws Exception {
        Field field = OverlayService.class.getDeclaredField("guideVisible");
        field.setAccessible(true);
        return field.getBoolean(null);
    }

    private static boolean awaitGuide(boolean expected) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (guideVisible() == expected) return true;
            Thread.sleep(100);
        }
        return false;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
