// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Marco Rinaldi (MarcorOnline)

package it.pistacchissimo.socialoverlay;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.MediaStore;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Offline proximity matching. Every detection failure falls back to a usable offset. */
final class DeviceProfiles {
    private static JSONArray profiles;

    private static synchronized JSONArray load(Context context) {
        if (profiles == null) {
            try (InputStream input = context.getAssets().open("camera-profiles.json");
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                profiles = new JSONArray(bytes.toString(StandardCharsets.UTF_8.name()));
            } catch (IOException | JSONException | RuntimeException error) {
                profiles = new JSONArray();
            }
        }
        return profiles;
    }

    static String cameraPackage(Context context) {
        try {
            String selected = MaskMode.autoPackage(context);
            if (!selected.isEmpty()) return selected;
            ResolveInfo camera = context.getPackageManager().resolveActivity(
                    new Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA), 0);
            return camera == null || camera.activityInfo == null ? "" : camera.activityInfo.packageName;
        } catch (RuntimeException error) {
            return "";
        }
    }

    static String cameraVersion(Context context, String camera) {
        try {
            String version = context.getPackageManager().getPackageInfo(camera, 0).versionName;
            return version == null ? "" : version;
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            return "";
        }
    }

    /** Model > nearest Android API > camera package > nearest numeric camera version.
     *  Unknown brands deliberately use the first valid entry, rather than an invented similarity.
     *  Equal scores retain file order so community contributions remain deterministic.
     */
    static JSONObject select(JSONArray entries, String manufacturer, String model, int sdk,
            String camera, String version) {
        if (entries == null) return null;
        JSONObject fallback = null;
        JSONObject best = null;
        long[] bestScore = null;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (!valid(entry)) continue;
            if (fallback == null) fallback = entry;
            if (!normalize(manufacturer).equals(normalize(entry.optString("manufacturer")))) continue;
            long[] score = new long[9];
            score[0] = normalize(model).equals(normalize(entry.optString("model"))) ? 0 : 1;
            score[1] = Math.abs((long) sdk - entry.optInt("android_sdk"));
            score[2] = camera != null && camera.equals(entry.optString("camera_package")) ? 0 : 1;
            long[] current = versionParts(version);
            long[] candidate = versionParts(entry.optString("camera_version"));
            for (int part = 0; part < 6; part++) {
                score[part + 3] = version == null || version.isEmpty()
                        ? 0 : Math.abs(current[part] - candidate[part]);
            }
            if (bestScore == null || compare(score, bestScore) < 0) {
                best = entry;
                bestScore = score;
            }
        }
        return best == null ? fallback : best;
    }

    private static boolean valid(JSONObject entry) {
        if (entry == null || entry.optString("manufacturer").trim().isEmpty()
                || entry.optString("model").trim().isEmpty()
                || entry.optString("camera_package").trim().isEmpty()
                || entry.optString("camera_version").trim().isEmpty()) return false;
        Object api = entry.opt("android_sdk");
        Object offset = entry.opt("vertical_offset_dp");
        if (!(api instanceof Number) || !(offset instanceof Number)) return false;
        double sdk = ((Number) api).doubleValue();
        double dp = ((Number) offset).doubleValue();
        return sdk >= 26 && sdk <= 1000 && sdk == Math.rint(sdk)
                && dp >= -360 && dp <= 360 && dp == Math.rint(dp);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static long[] versionParts(String version) {
        long[] parts = new long[6];
        if (version == null) return parts;
        String[] numbers = version.replaceFirst("^[^0-9]+", "").split("[^0-9]+");
        for (int i = 0; i < Math.min(numbers.length, parts.length); i++) {
            try {
                parts[i] = Math.min(1_000_000_000L, Long.parseLong(numbers[i]));
            } catch (NumberFormatException ignored) {
                parts[i] = 1_000_000_000L;
            }
        }
        return parts;
    }

    private static int compare(long[] a, long[] b) {
        for (int i = 0; i < a.length; i++) {
            int result = Long.compare(a[i], b[i]);
            if (result != 0) return result;
        }
        return 0;
    }

    static JSONObject detectedProfile(Context context) {
        try {
            String camera = cameraPackage(context);
            return select(load(context), Build.MANUFACTURER, Build.MODEL,
                    Build.VERSION.SDK_INT, camera, cameraVersion(context, camera));
        } catch (RuntimeException error) {
            return null;
        }
    }

    static Integer detectedOffset(Context context) {
        JSONObject selected = detectedProfile(context);
        return selected == null ? null : selected.optInt("vertical_offset_dp", 0);
    }

    static boolean isExactProfile(JSONObject profile, String manufacturer, String model, int sdk,
            String camera, String version) {
        return valid(profile) && normalize(manufacturer).equals(normalize(profile.optString("manufacturer")))
                && normalize(model).equals(normalize(profile.optString("model")))
                && sdk == profile.optInt("android_sdk")
                && camera != null && !camera.isEmpty() && camera.equals(profile.optString("camera_package"))
                && version != null && !version.isEmpty() && version.equals(profile.optString("camera_version"));
    }

    static boolean hasExactProfile(Context context) {
        String camera = cameraPackage(context);
        return isExactProfile(detectedProfile(context), Build.MANUFACTURER, Build.MODEL,
                Build.VERSION.SDK_INT, camera, cameraVersion(context, camera));
    }

    /** Mode rules must never inherit the approximate matching used for offsets. */
    static JSONObject modeDetection(JSONArray entries, String manufacturer, String model, int sdk,
            String camera, String version) {
        if (camera == null || camera.isEmpty() || version == null || version.isEmpty()) return null;
        for (int i = 0; entries != null && i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (isExactProfile(entry, manufacturer, model, sdk, camera, version)) {
                return entry.optJSONObject("mode_detection");
            }
        }
        return null;
    }

    static JSONObject modeDetection(Context context) {
        String camera = MaskMode.autoPackage(context);
        return modeDetection(load(context), Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT,
                camera, cameraVersion(context, camera));
    }

    static int defaultOffset(Context context) {
        Integer detected = detectedOffset(context);
        return detected == null ? 0 : detected;
    }

    static String calibrationJson(Context context) {
        try {
            String camera = cameraPackage(context);
            JSONObject exported = new JSONObject().put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL).put("android_sdk", Build.VERSION.SDK_INT)
                    .put("camera_package", camera).put("camera_version", cameraVersion(context, camera))
                    .put("vertical_offset_dp", MaskMode.verticalOffset(context));
            JSONObject detection = modeDetection(context);
            if (detection != null) exported.put("mode_detection", detection);
            return exported.toString(2);
        } catch (JSONException | RuntimeException error) {
            return "{}";
        }
    }
}
