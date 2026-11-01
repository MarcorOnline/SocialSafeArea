// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Marco Rinaldi (MarcorOnline)

package it.pistacchissimo.socialoverlay;

import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

final class DeviceProfilesTest {
    static void verify(Instrumentation test) throws Exception {
        JSONArray entries = new JSONArray()
                .put(profile("samsung", "SM-S948B", 37, "example.camera", "17.0.00.43", 12))
                .put(profile("samsung", "SM-S948B", 37, "example.camera", "17.2.00.43", 22))
                .put(profile("samsung", "SM-S948B", 36, "example.camera", "16.0", 32))
                .put(profile("samsung", "SM-S948B", 37, "other.camera", "99.0", 42))
                .put(profile("samsung", "OtherModel", 37, "example.camera", "17.1.00.43", 52));
        check(offset(entries, "Samsung", "sm-s948b", 37, "example.camera", "17.0.00.43") == 12, "exact match");
        check(offset(entries, "samsung", "SM-S948B", 37, "example.camera", "17.1.00.43") == 12, "near camera version; stable tie");
        check(offset(entries, "samsung", "SM-S948B", 37, "example.camera", "17.3") == 22, "nearest camera version");
        check(offset(entries, "samsung", "SM-S948B", 36, "example.camera", "17.2") == 32, "approximate camera before OS");
        check(offset(entries, "samsung", "SM-S948B", 38, "example.camera", "17.0") == 12, "nearest OS before another model");
        check(offset(entries, "samsung", "SM-S948B", 37, "other.camera", "17.0") == 42, "camera package identity");
        check(offset(entries, "samsung", "UnknownModel", 37, "example.camera", "17.1.00.43") == 52, "same brand fallback");
        check(offset(entries, "realme", "UnknownModel", 37, "example.camera", "17.2") == 12, "unknown brand uses first valid default");
        check(DeviceProfiles.select(null, null, null, 0, null, null) == null, "missing detection data");
        check(DeviceProfiles.select(new JSONArray(), "", "", 0, "", "") == null, "empty profiles");
        JSONArray damaged = new JSONArray().put("not an object").put(new JSONObject()).put(entries.getJSONObject(0));
        check(offset(damaged, null, null, 0, null, "999999999999999999999999") == 12, "skip damaged entries and tolerate unusual versions");

        Context context = test.getTargetContext();
        boolean custom = context.getSharedPreferences("social_overlay", Context.MODE_PRIVATE).contains("vertical_offset");
        int original = context.getSharedPreferences("social_overlay", Context.MODE_PRIVATE).getInt("vertical_offset", DeviceProfiles.defaultOffset(context));
        Integer originalFine = context.getSharedPreferences("social_overlay", Context.MODE_PRIVATE).contains("vertical_fine_px")
                ? context.getSharedPreferences("social_overlay", Context.MODE_PRIVATE).getInt("vertical_fine_px", 0) : null;
        try {
            MaskMode.setVerticalOffset(context, 57);
            DeviceProfiles.defaultOffset(context);
            check(MaskMode.verticalOffset(context) == 57, "detection preserves manual adjustment");
            MaskMode.resetVerticalOffset(context);
            check(!MaskMode.hasVerticalOffsetOverride(context)
                    && MaskMode.verticalOffset(context) == DeviceProfiles.defaultOffset(context), "reset restores profile");
            JSONObject copied = new JSONObject(DeviceProfiles.calibrationJson(context));
            check(copied.has("camera_version") && copied.has("vertical_offset_dp"), "community calibration export");
        } finally {
            if (custom) MaskMode.setVerticalOffset(context, original);
            else MaskMode.resetVerticalOffset(context);
            if (originalFine == null) context.getSharedPreferences("social_overlay", Context.MODE_PRIVATE).edit().remove("vertical_fine_px").apply();
            else context.getSharedPreferences("social_overlay", Context.MODE_PRIVATE).edit().putInt("vertical_fine_px", originalFine).apply();
        }

        String[] languages = {"en", "it", "fr", "es", "de", "ja"};
        String[] labels = {"Show quick button", "Mostra bottone rapido", "Afficher le bouton rapide",
                "Mostrar botón rápido", "Schnelltaste anzeigen", "Show quick button"};
        for (int i = 0; i < languages.length; i++) {
            Configuration config = new Configuration(context.getResources().getConfiguration());
            config.setLocale(Locale.forLanguageTag(languages[i]));
            Context translated = context.createConfigurationContext(config);
            check(labels[i].equals(translated.getString(R.string.show_quick_button)), "language " + languages[i]);
            translated.getString(R.string.device_details, "brand", "model", "17", 37, "example.camera", 0, 42);
            translated.getString(R.string.opacity_value, 23);
        }
    }

    private static JSONObject profile(String brand, String model, int sdk, String camera, String version, int dp) throws Exception {
        return new JSONObject().put("manufacturer", brand).put("model", model).put("android_sdk", sdk)
                .put("camera_package", camera).put("camera_version", version).put("vertical_offset_dp", dp);
    }

    private static int offset(JSONArray entries, String brand, String model, int sdk, String camera, String version) {
        JSONObject selected = DeviceProfiles.select(entries, brand, model, sdk, camera, version);
        return selected == null ? 0 : selected.optInt("vertical_offset_dp");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
