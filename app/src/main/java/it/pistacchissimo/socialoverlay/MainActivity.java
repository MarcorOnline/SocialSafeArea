// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Marco Rinaldi (MarcorOnline)

package it.pistacchissimo.socialoverlay;

import android.app.Activity;
import android.app.AlertDialog;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.provider.Settings;
import android.graphics.Paint;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

/** The deliberately small control screen: Android owns the overlay permission flow. */
public final class MainActivity extends Activity {
    private static final int OVERLAY_PERMISSION_REQUEST = 100;
    private Button showButton;
    private TextView modeStatus;
    private TextView opacityStatus;
    private TextView positionStatus;
    private SeekBar verticalPosition;
    private CheckBox adjustmentArrows;
    private TextView subtitlePositionStatus;
    private SeekBar subtitlePosition;
    private Button resetSubtitlePosition;
    private TextView autoStatus;
    private Button updateButton;
    private Button expandedHeader;
    private LinearLayout expandedSection;
    private TextView experimentalStatus;
    private TextView setupStatus;
    private Button detectionButton;
    private Spinner appPicker;
    private List<TargetApp> apps;
    private boolean showAfterPermission;
    private boolean inSetup;
    private int setupPage;
    private int demoPhase;
    private TextView demoInstructions;
    private TextView demoStatus;
    private Button demoNext;
    private final ResultReceiver tutorialResult = new ResultReceiver(new Handler(Looper.getMainLooper())) {
        @Override protected void onReceiveResult(int visible, Bundle data) {
            if (!inSetup || setupPage != 1 || demoPhase >= 2) return;
            if ((visible == 1) == (demoPhase == 0)) {
                demoPhase++;
                getSharedPreferences("social_overlay", MODE_PRIVATE).edit()
                        .putInt("wizard_demo", demoPhase).apply();
                updateDemo();
            }
        }
    };
    private final Consumer<UpdateChecker.Release> updateResult = this::showUpdate;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        showAfterPermission = state != null && state.getBoolean("show_after_permission", false);
        DeviceProfiles.defaultOffset(this);
        android.content.SharedPreferences prefs = getSharedPreferences("social_overlay", MODE_PRIVATE);
        inSetup = !prefs.getBoolean("wizard_complete", false);
        setupPage = Math.max(0, Math.min(6, prefs.getInt("wizard_page", 0)));
        demoPhase = Math.max(0, Math.min(2, prefs.getInt("wizard_demo", 0)));
        if (inSetup) {
            stopService(new Intent(this, OverlayService.class));
            if (!Settings.canDrawOverlays(this)) setupPage = 0;
            renderSetup();
        } else {
            setContentView(createContent());
            if (Settings.canDrawOverlays(this) && !prefs.getBoolean("calibration_active", false)
                    && !prefs.getBoolean("calibration_finished", false)) OverlayService.start(this);
        }
    }

    @Override protected void onPause() {
        if (inSetup && setupPage == 1) stopService(new Intent(this, OverlayService.class));
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("show_after_permission", showAfterPermission);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onResume() {
        super.onResume();
        android.content.SharedPreferences prefs = getSharedPreferences("social_overlay", MODE_PRIVATE);
        if (prefs.getBoolean("calibration_active", false) || prefs.getBoolean("calibration_finished", false)) {
            boolean finished = prefs.getBoolean("calibration_finished", false);
            boolean fromWizard = prefs.getBoolean("calibration_from_wizard", false);
            stopService(new Intent(this, OverlayService.class));
            prefs.edit().remove("calibration_active").remove("calibration_finished")
                    .remove("calibration_from_wizard").apply();
            if (finished && fromWizard && inSetup) {
                goToSetupPage(3);
                return;
            }
        }
        if (inSetup) {
            if (!Settings.canDrawOverlays(this) && setupPage > 0) goToSetupPage(0);
            else renderSetup();
            if (setupPage == 1 && Settings.canDrawOverlays(this)) {
                OverlayService.startTutorial(this, demoPhase == 1, tutorialResult);
            }
            return;
        }
        refresh();
        if (getResources().getBoolean(R.bool.check_github_updates)) {
            UpdateChecker.check(this, updateResult);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (Settings.canDrawOverlays(this)) {
                if (inSetup) {
                    goToSetupPage(1);
                    return;
                }
                if (showAfterPermission) OverlayService.start(this);
            } else if (!inSetup) {
                Toast.makeText(this, R.string.setup_overlay_denied, Toast.LENGTH_LONG).show();
            }
            showAfterPermission = false;
        }
        refresh();
    }

    private View createContent() {
        int padding = dp(28);
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.surface));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.TOP);
        content.setPadding(padding, padding, padding, padding);
        content.setBackgroundColor(getColor(R.color.surface));

        modeStatus = new TextView(this);
        modeStatus.setTextColor(getColor(R.color.on_surface));
        modeStatus.setTextSize(12);
        content.addView(modeStatus);

        TextView title = new TextView(this);
        title.setText(R.string.home_title);
        title.setTextColor(getColor(R.color.brand));
        title.setTextSize(34);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        content.addView(title);

        TextView description = new TextView(this);
        description.setText(R.string.description);
        description.setTextColor(getColor(R.color.on_surface));
        description.setTextSize(17);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
        descriptionParams.topMargin = dp(18);
        content.addView(description, descriptionParams);

        setupStatus = sectionLabel(content, "");
        setupStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        showButton = new Button(this, null, 0, R.style.SafeAreaButton_Primary);
        showButton.setAllCaps(false);
        showButton.setTextSize(17);
        showButton.setText(R.string.show_quick_button);
        showButton.setOnClickListener(ignored -> showQuickButton());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, -2);
        buttonParams.topMargin = dp(32);
        content.addView(showButton, buttonParams);

        updateButton = new Button(this, null, 0, R.style.SafeAreaButton_Text);
        updateButton.setAllCaps(false);
        updateButton.setVisibility(View.GONE);
        content.addView(updateButton, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout maskSettings = addAccordion(content, R.string.customize_mask);
        TextView modeLabel = new TextView(this);
        modeLabel.setText(R.string.mask_label);
        modeLabel.setTextColor(getColor(R.color.on_surface));
        modeLabel.setTextSize(15);
        LinearLayout.LayoutParams modeLabelParams = new LinearLayout.LayoutParams(-1, -2);
        modeLabelParams.topMargin = dp(12);
        maskSettings.addView(modeLabel, modeLabelParams);

        addMaskCheckbox(maskSettings, R.string.reels, R.color.mask_reels, MaskMode.REELS);
        addMaskCheckbox(maskSettings, R.string.tiktok, R.color.mask_tiktok, MaskMode.TIKTOK);
        addMaskCheckbox(maskSettings, R.string.subtitles, R.color.mask_subtitles, MaskMode.SUBTITLES);

        opacityStatus = sectionLabel(maskSettings, getString(R.string.opacity_label));
        SeekBar opacity = new SeekBar(this);
        opacity.setMax(62);
        opacity.setProgress(MaskMode.opacity(this) - 8);
        opacity.setContentDescription(getString(R.string.opacity_picker_description));
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                MaskMode.setOpacity(MainActivity.this, progress + 8);
                OverlayService.refreshMask(MainActivity.this);
                refresh();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        maskSettings.addView(opacity, new LinearLayout.LayoutParams(-1, -2));

        positionStatus = sectionLabel(maskSettings, getString(R.string.vertical_label));
        verticalPosition = new SeekBar(this);
        verticalPosition.setMax(720);
        verticalPosition.setProgress(MaskMode.verticalOffset(this) + 360);
        verticalPosition.setContentDescription(getString(R.string.vertical_picker_description));
        verticalPosition.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    MaskMode.setVerticalOffset(MainActivity.this, progress - 360);
                    OverlayService.refreshMask(MainActivity.this);
                    refresh();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        LinearLayout positionControls = new LinearLayout(this);
        positionControls.setGravity(Gravity.CENTER_VERTICAL);
        positionControls.addView(verticalPosition, new LinearLayout.LayoutParams(0, -2, 1f));
        Button resetPosition = new Button(this, null, 0, R.style.SafeAreaButton_Text);
        resetPosition.setText(R.string.reset);
        resetPosition.setTextSize(14);
        resetPosition.setAllCaps(false);
        resetPosition.setMinWidth(dp(64));
        resetPosition.setMinHeight(dp(48));
        resetPosition.setContentDescription(getString(R.string.reset_description));
        resetPosition.setOnClickListener(ignored -> {
            MaskMode.resetVerticalOffset(MainActivity.this);
            verticalPosition.setProgress(MaskMode.verticalOffset(MainActivity.this) + 360);
            OverlayService.refreshMask(MainActivity.this);
            refresh();
        });
        positionControls.addView(resetPosition, new LinearLayout.LayoutParams(-2, -2));
        maskSettings.addView(positionControls, new LinearLayout.LayoutParams(-1, -2));
        setupButton(maskSettings, this::chooseCalibrationCamera).setText(R.string.calibration_open);

        adjustmentArrows = new CheckBox(this);
        adjustmentArrows.setText(R.string.adjustment_arrows);
        adjustmentArrows.setTextColor(getColor(R.color.on_surface));
        adjustmentArrows.setMinHeight(dp(48));
        adjustmentArrows.setChecked(MaskMode.adjustmentArrows(this));
        adjustmentArrows.setOnClickListener(ignored -> {
            MaskMode.setAdjustmentArrows(this, adjustmentArrows.isChecked());
            OverlayService.refreshMask(this);
        });
        maskSettings.addView(adjustmentArrows, new LinearLayout.LayoutParams(-1, -2));
        sectionLabel(maskSettings, getString(R.string.adjustment_arrows_hint));

        subtitlePositionStatus = sectionLabel(maskSettings, getString(R.string.subtitle_vertical_label));
        subtitlePosition = new SeekBar(this);
        subtitlePosition.setMax(MaskMode.SUBTITLE_OFFSET_LIMIT * 2);
        subtitlePosition.setProgress(MaskMode.subtitleOffset(this) + MaskMode.SUBTITLE_OFFSET_LIMIT);
        subtitlePosition.setContentDescription(getString(R.string.subtitle_vertical_picker_description));
        subtitlePosition.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) MaskMode.setSubtitleOffset(MainActivity.this, progress - MaskMode.SUBTITLE_OFFSET_LIMIT);
                OverlayService.refreshMask(MainActivity.this);
                refresh();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        LinearLayout subtitleControls = new LinearLayout(this);
        subtitleControls.setGravity(Gravity.CENTER_VERTICAL);
        subtitleControls.addView(subtitlePosition, new LinearLayout.LayoutParams(0, -2, 1f));
        resetSubtitlePosition = new Button(this, null, 0, R.style.SafeAreaButton_Text);
        resetSubtitlePosition.setText(R.string.reset);
        resetSubtitlePosition.setTextSize(14);
        resetSubtitlePosition.setAllCaps(false);
        resetSubtitlePosition.setMinWidth(dp(64));
        resetSubtitlePosition.setMinHeight(dp(48));
        resetSubtitlePosition.setContentDescription(getString(R.string.subtitle_reset_description));
        resetSubtitlePosition.setOnClickListener(ignored -> {
            MaskMode.resetSubtitleOffset(MainActivity.this);
            subtitlePosition.setProgress(MaskMode.SUBTITLE_OFFSET_LIMIT);
            OverlayService.refreshMask(MainActivity.this);
            refresh();
        });
        subtitleControls.addView(resetSubtitlePosition, new LinearLayout.LayoutParams(-2, -2));
        maskSettings.addView(subtitleControls, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout autoSettings = addAccordion(content, R.string.automatic_opening);
        sectionLabel(autoSettings, getString(R.string.setup_camera_help));
        autoStatus = sectionLabel(autoSettings, getString(R.string.auto_label));
        appPicker = new Spinner(this);
        apps = launchableApps();
        ArrayAdapter<TargetApp> appAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, apps);
        appPicker.setAdapter(appAdapter);
        appPicker.setSelection(targetSelection(apps, MaskMode.autoPackage(this)));
        appPicker.setContentDescription(getString(R.string.auto_picker_description));
        appPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                MaskMode.setAutoPackage(MainActivity.this, apps.get(position).packageName);
                verticalPosition.setProgress(MaskMode.verticalOffset(MainActivity.this) + 360);
                OverlayService.refreshMask(MainActivity.this);
                refresh();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        autoSettings.addView(appPicker, new LinearLayout.LayoutParams(-1, -2));

        detectionButton = new Button(this, null, 0, R.style.SafeAreaButton);
        detectionButton.setAllCaps(false);
        detectionButton.setText(R.string.enable_detection);
        detectionButton.setOnClickListener(ignored -> enableDetection());
        LinearLayout.LayoutParams accessibilityParams = new LinearLayout.LayoutParams(-1, -2);
        accessibilityParams.topMargin = dp(16);
        autoSettings.addView(detectionButton, accessibilityParams);
        setupButton(autoSettings, this::showRestrictedSettingsHelp).setText(R.string.setup_blocked_title);

        LinearLayout experimental = addAccordion(content, R.string.experimental_features);
        LinearLayout videoOnlyRow = new LinearLayout(this);
        videoOnlyRow.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox videoOnly = new CheckBox(this);
        videoOnly.setText(R.string.experimental_video_only);
        videoOnly.setTextColor(getColor(R.color.on_surface));
        videoOnly.setMinHeight(dp(48));
        videoOnly.setChecked(MaskMode.videoOnly(this));
        videoOnly.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) {
                MaskMode.setVideoOnly(this, false);
                OverlayService.refreshMask(this);
                refresh();
            } else if (!MaskMode.videoOnly(this)) {
                // Leave the option off until its requirements and disclosure are satisfied.
                videoOnly.setChecked(false);
                if (!Settings.canDrawOverlays(this)) explainOverlayPermission();
                else if (MaskMode.autoPackage(this).isEmpty()) chooseCamera(false);
                else if (!isWatcherEnabled()) openAccessibilitySettings();
                else if (DeviceProfiles.modeDetection(this) == null) showVideoModeInfo(null);
                else showVideoModeInfo(() -> videoOnly.setChecked(true));
            }
        });
        videoOnlyRow.addView(videoOnly, new LinearLayout.LayoutParams(0, -2, 1f));
        Button help = new Button(this, null, 0, R.style.SafeAreaButton_Text);
        help.setText("?");
        help.setContentDescription(getString(R.string.experimental_video_help_description));
        help.setOnClickListener(ignored -> showVideoModeInfo(null));
        videoOnlyRow.addView(help, new LinearLayout.LayoutParams(dp(48), -2));
        experimental.addView(videoOnlyRow, new LinearLayout.LayoutParams(-1, -2));
        experimentalStatus = sectionLabel(experimental, "");
        experimentalStatus.setMinHeight(dp(48));

        setupButton(content, () -> {
            inSetup = true;
            demoPhase = 0;
            getSharedPreferences("social_overlay", MODE_PRIVATE).edit()
                    .putBoolean("wizard_complete", false).putInt("wizard_demo", 0).apply();
            goToSetupPage(0);
        }).setText(R.string.wizard_reopen);

        Button information = new Button(this, null, 0, R.style.SafeAreaButton_Text);
        information.setText("ⓘ");
        information.setAllCaps(false);
        information.setTextSize(24);
        information.setMinWidth(dp(48));
        information.setMinHeight(dp(48));
        information.setContentDescription(getString(R.string.information));
        information.setOnClickListener(ignored -> startActivity(new Intent(this, AboutActivity.class)));
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(-2, -2);
        infoParams.gravity = Gravity.CENTER_HORIZONTAL;
        infoParams.topMargin = dp(12);
        content.addView(information, infoParams);
        scroll.addView(content);
        return scroll;
    }

    private void goToSetupPage(int page) {
        stopService(new Intent(this, OverlayService.class));
        setupPage = page;
        if (!Settings.canDrawOverlays(this)) setupPage = 0;
        getSharedPreferences("social_overlay", MODE_PRIVATE).edit().putInt("wizard_page", setupPage).apply();
        renderSetup();
        if (setupPage == 1) OverlayService.startTutorial(this, demoPhase == 1, tutorialResult);
    }

    @Override public void onBackPressed() {
        if (inSetup && setupPage > 0) goToSetupPage(setupPage - 1);
        else super.onBackPressed();
    }

    private boolean cameraAvailable() {
        return targetSelection(launchableApps(), MaskMode.autoPackage(this)) > 0;
    }

    private void finishSetup() {
        if (!Settings.canDrawOverlays(this)) { goToSetupPage(0); return; }
        if (demoPhase < 2) { goToSetupPage(1); return; }
        stopService(new Intent(this, OverlayService.class));
        getSharedPreferences("social_overlay", MODE_PRIVATE).edit()
                .putBoolean("wizard_complete", true).remove("wizard_page").remove("wizard_demo").apply();
        inSetup = false;
        expandedHeader = null;
        expandedSection = null;
        setContentView(createContent());
        refresh();
    }

    private void renderSetup() {
        if (!Settings.canDrawOverlays(this) && setupPage != 0) {
            setupPage = 0;
            stopService(new Intent(this, OverlayService.class));
            getSharedPreferences("social_overlay", MODE_PRIVATE).edit().putInt("wizard_page", 0).apply();
        }
        LinearLayout window = new LinearLayout(this);
        window.setFitsSystemWindows(true);
        window.setBackgroundColor(getColor(R.color.surface));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(16));
        window.addView(root, new LinearLayout.LayoutParams(-1, -1));

        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        navigation.setMinimumHeight(dp(48));
        root.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        TextView progress = wizardText(navigation, getString(R.string.wizard_progress, setupPage + 1, 7), 14);
        progress.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        progress.setTextColor(getColor(R.color.brand));
        if (setupPage > 0) {
            Button back = wizardAction(navigation, R.string.wizard_back, false, () -> goToSetupPage(setupPage - 1));
            back.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        }
        android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(7);
        bar.setProgress(setupPage + 1);
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, dp(4));
        barParams.topMargin = dp(12);
        barParams.bottomMargin = dp(16);
        root.addView(bar, barParams);

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, dp(24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        int[] titles = {R.string.wizard_overlay_title, R.string.wizard_demo_title,
                R.string.wizard_height_title, R.string.wizard_camera_title,
                R.string.wizard_detection_title, R.string.wizard_mode_title, R.string.wizard_done_title};
        TextView title = wizardText(body, getString(titles[setupPage]), 28);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        root.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        boolean camera = cameraAvailable();
        boolean detection = isWatcherEnabled();
        switch (setupPage) {
            case 0:
                wizardParagraph(body, getString(R.string.wizard_overlay_body));
                boolean allowed = Settings.canDrawOverlays(this);
                if (!allowed) wizardParagraph(body, getString(R.string.wizard_overlay_steps, getString(R.string.app_name)));
                wizardStatus(body, getString(allowed ? R.string.wizard_permission_ready : R.string.wizard_permission_waiting));
                wizardAction(actions, allowed ? R.string.wizard_next : R.string.setup_open_settings,
                        true, () -> {
                            if (Settings.canDrawOverlays(this)) goToSetupPage(1);
                            else launchOverlaySettings();
                        });
                break;
            case 1:
                demoInstructions = wizardParagraph(body, "");
                demoStatus = wizardStatus(body, "");
                demoNext = wizardAction(actions, R.string.wizard_next, true, () -> {
                    if (demoPhase == 2) goToSetupPage(2);
                });
                updateDemo();
                wizardAction(actions, R.string.wizard_demo_restart, false, () -> {
                    demoPhase = 0;
                    getSharedPreferences("social_overlay", MODE_PRIVATE).edit().putInt("wizard_demo", 0).apply();
                    goToSetupPage(1);
                });
                break;
            case 2:
                TextView arrows = wizardText(body, "↕", 64);
                arrows.setTextColor(getColor(R.color.brand));
                arrows.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                wizardParagraph(body, getString(R.string.wizard_height_body));
                wizardAction(actions, R.string.calibration_now, true, this::chooseCalibrationCamera);
                wizardAction(actions, R.string.calibration_skip, false, () -> goToSetupPage(3));
                break;
            case 3:
                wizardParagraph(body, getString(R.string.wizard_camera_body));
                if (camera) wizardStatus(body, getString(R.string.wizard_camera_selected, appLabel(MaskMode.autoPackage(this))));
                wizardAction(actions, camera ? R.string.wizard_next : R.string.wizard_choose_camera,
                        true, () -> { if (cameraAvailable()) goToSetupPage(4); else chooseCamera(false); });
                if (camera) wizardAction(body, R.string.wizard_change_camera, false, () -> chooseCamera(false));
                wizardAction(actions, R.string.wizard_manual, false, () -> {
                    MaskMode.setAutoPackage(this, "");
                    MaskMode.setVideoOnly(this, false);
                    goToSetupPage(5);
                });
                break;
            case 4:
                wizardParagraph(body, getString(R.string.wizard_detection_body, getString(R.string.watcher_name)));
                wizardStatus(body, getString(detection && camera ? R.string.wizard_detection_ready : R.string.wizard_detection_waiting));
                wizardAction(actions, detection && camera ? R.string.wizard_next : R.string.enable_detection,
                        true, () -> {
                            if (!cameraAvailable()) goToSetupPage(3);
                            else if (isWatcherEnabled()) goToSetupPage(5);
                            else openAccessibilitySettings();
                        });
                if (!detection) {
                    wizardAction(actions, R.string.wizard_not_now, false, () -> goToSetupPage(5));
                    wizardAction(body, R.string.setup_blocked_title, false, this::showRestrictedSettingsHelp);
                }
                break;
            case 5:
                wizardParagraph(body, getString(R.string.wizard_mode_body));
                boolean prerequisites = camera && detection;
                boolean compatible = DeviceProfiles.modeDetection(this) != null;
                boolean enabled = prerequisites && compatible && MaskMode.videoOnly(this);
                wizardStatus(body, getString(!prerequisites ? R.string.wizard_mode_requires_detection
                        : !compatible ? R.string.wizard_mode_unavailable
                        : enabled ? R.string.wizard_mode_ready : R.string.wizard_mode_available));
                wizardAction(actions, prerequisites && compatible && !enabled
                        ? R.string.wizard_mode_enable : R.string.wizard_next, true, () -> {
                    if (cameraAvailable() && isWatcherEnabled() && DeviceProfiles.modeDetection(this) != null
                            && !MaskMode.videoOnly(this)) showVideoModeInfo(() -> { });
                    else goToSetupPage(6);
                });
                if (prerequisites && compatible && !enabled) {
                    wizardAction(actions, R.string.wizard_not_now, false, () -> goToSetupPage(6));
                }
                if (!prerequisites) wizardAction(body, R.string.wizard_configure_detection, false, () -> goToSetupPage(3));
                break;
            default:
                wizardStatus(body, getString(R.string.wizard_summary_overlay));
                wizardParagraph(body, getString(camera && detection ? R.string.wizard_summary_auto : R.string.wizard_summary_manual));
                wizardParagraph(body, getString(camera && detection && MaskMode.videoOnly(this)
                        && DeviceProfiles.modeDetection(this) != null ? R.string.wizard_summary_video : R.string.wizard_summary_all_modes));
                wizardParagraph(body, getString(R.string.wizard_finish_body));
                wizardAction(actions, R.string.wizard_finish, true, this::finishSetup);
                break;
        }
        setContentView(window);
    }

    private TextView wizardText(LinearLayout parent, String text, int size) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(getColor(R.color.on_surface));
        label.setTextSize(size);
        label.setLineSpacing(dp(3), 1f);
        parent.addView(label, new LinearLayout.LayoutParams(-1, -2));
        return label;
    }

    private void updateDemo() {
        demoInstructions.setText(demoPhase == 0 ? R.string.wizard_demo_show
                : demoPhase == 1 ? R.string.wizard_demo_hide : R.string.wizard_demo_done);
        demoStatus.setText(demoPhase == 0 ? R.string.wizard_demo_wait_show
                : demoPhase == 1 ? R.string.wizard_demo_wait_hide : R.string.wizard_demo_verified);
        demoNext.setEnabled(demoPhase == 2);
    }

    private TextView wizardParagraph(LinearLayout parent, String text) {
        TextView label = wizardText(parent, text, 17);
        ((LinearLayout.LayoutParams) label.getLayoutParams()).topMargin = dp(24);
        return label;
    }

    private TextView wizardStatus(LinearLayout parent, String text) {
        TextView label = wizardParagraph(parent, text);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return label;
    }

    private Button wizardAction(LinearLayout parent, int label, boolean primary, Runnable action) {
        Button button = new Button(this, null, 0, primary ? R.style.SafeAreaButton_Primary : R.style.SafeAreaButton_Text);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(ignored -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        parent.addView(button, params);
        return button;
    }

    private void showQuickButton() {
        if (!Settings.canDrawOverlays(this)) {
            showAfterPermission = true;
            explainOverlayPermission();
            return;
        }
        OverlayService.start(this);
        refresh();
    }

    private Button setupButton(LinearLayout parent, Runnable action) {
        Button button = new Button(this, null, 0, R.style.SafeAreaButton);
        button.setAllCaps(false);
        button.setOnClickListener(ignored -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        parent.addView(button, params);
        return button;
    }

    private void explainOverlayPermission() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.setup_overlay_title)
                .setMessage(getString(R.string.setup_overlay_help, getString(R.string.app_name)))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> showAfterPermission = false)
                .setOnCancelListener(dialog -> showAfterPermission = false)
                .setPositiveButton(R.string.setup_open_settings, (dialog, which) -> launchOverlaySettings()).show();
    }

    private void launchOverlaySettings() {
        try {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), OVERLAY_PERMISSION_REQUEST);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            showAfterPermission = false;
            Toast.makeText(this, R.string.setup_settings_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void chooseCamera(boolean enableAfterSelection) {
        chooseCamera(R.string.setup_camera_title, selected -> {
            MaskMode.setAutoPackage(this, selected);
            if (!inSetup) appPicker.setSelection(targetSelection(apps, selected));
            refresh();
            if (enableAfterSelection) openAccessibilitySettings();
        });
    }

    private void chooseCalibrationCamera() {
        if (!Settings.canDrawOverlays(this)) { explainOverlayPermission(); return; }
        chooseCamera(R.string.calibration_choose_camera, selected -> {
            Intent camera = getPackageManager().getLaunchIntentForPackage(selected);
            if (camera == null) {
                Toast.makeText(this, R.string.setup_no_camera, Toast.LENGTH_LONG).show();
                return;
            }
            MaskMode.setAutoPackage(this, selected);
            if (!inSetup) appPicker.setSelection(targetSelection(apps, selected));
            getSharedPreferences("social_overlay", MODE_PRIVATE).edit()
                    .putBoolean("calibration_active", true).putBoolean("calibration_from_wizard", inSetup)
                    .remove("calibration_finished").apply();
            try {
                OverlayService.startCalibration(this);
                startActivity(camera);
                Toast.makeText(this, R.string.calibration_instructions, Toast.LENGTH_LONG).show();
            } catch (ActivityNotFoundException | SecurityException unavailable) {
                stopService(new Intent(this, OverlayService.class));
                getSharedPreferences("social_overlay", MODE_PRIVATE).edit()
                        .remove("calibration_active").remove("calibration_from_wizard").apply();
                Toast.makeText(this, R.string.setup_no_camera, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void chooseCamera(int title, Consumer<String> onSelected) {
        List<TargetApp> choices = launchableApps();
        choices.remove(0); // "No app" is still available in the regular picker for manual use.
        if (choices.isEmpty()) {
            new AlertDialog.Builder(this).setTitle(R.string.setup_camera_title)
                    .setMessage(R.string.setup_no_camera).setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        CharSequence[] labels = new CharSequence[choices.size()];
        for (int i = 0; i < choices.size(); i++) labels[i] = choices.get(i).label;
        new AlertDialog.Builder(this).setTitle(title)
                .setItems(labels, (dialog, which) -> {
                    onSelected.accept(choices.get(which).packageName);
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void enableDetection() {
        if (MaskMode.autoPackage(this).isEmpty()) chooseCamera(true);
        else openAccessibilitySettings();
    }

    private void showVideoModeInfo(Runnable onEnabled) {
        SpannableString message = new SpannableString(getString(R.string.experimental_video_help));
        int github = message.toString().lastIndexOf("GitHub");
        if (github >= 0) {
            message.setSpan(new ClickableSpan() {
                @Override public void onClick(View widget) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(AboutActivity.PROJECT_URL
                                + "/blob/develop/CONTRIBUTING.md#contribute-experimental-mode-detection")));
                    } catch (ActivityNotFoundException error) {
                        Toast.makeText(MainActivity.this, R.string.no_browser, Toast.LENGTH_SHORT).show();
                    }
                }
            }, github, github + "GitHub".length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.experimental_video_only).setMessage(message);
        if (onEnabled == null) builder.setPositiveButton(android.R.string.ok, null);
        else builder.setPositiveButton(R.string.experimental_enable, (dialog, which) -> {
                    MaskMode.setVideoOnly(this, true);
                    onEnabled.run();
                    OverlayService.refreshMask(this);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null);
        TextView text = builder.show().findViewById(android.R.id.message);
        text.setText(message, TextView.BufferType.SPANNABLE);
        text.setMovementMethod(LinkMovementMethod.getInstance());
        text.setLinksClickable(true);
        text.setClickable(true);
        text.setLinkTextColor(getColor(R.color.brand));
    }

    private void openAccessibilitySettings() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.accessibility_disclosure_title)
                .setMessage(getString(R.string.accessibility_disclosure) + "\n\n"
                        + getString(R.string.setup_accessibility_help, getString(R.string.watcher_name)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.accessibility_continue, (dialog, which) -> {
                    MaskMode.acceptAccessibilityConsent(this);
                    launchAccessibilitySettings();
                })
                .show();
    }

    private void showRestrictedSettingsHelp() {
        new AlertDialog.Builder(this).setTitle(R.string.setup_blocked_title)
                .setMessage(getString(R.string.setup_blocked_help, getString(R.string.app_name)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.setup_app_info, (dialog, which) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (ActivityNotFoundException | SecurityException unavailable) {
                        Toast.makeText(this, R.string.setup_settings_unavailable, Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void launchAccessibilitySettings() {
        String component = new ComponentName(this, AppWatchAccessibilityService.class).flattenToString();
        try {
            // This settings intent is provided by AOSP and some OEMs, but is not a public SDK constant.
            startActivity(new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, component));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            try {
                Bundle arguments = new Bundle();
                arguments.putString(":settings:fragment_args_key", component);
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .putExtra(":settings:fragment_args_key", component)
                        .putExtra(":settings:show_fragment_args", arguments));
            } catch (ActivityNotFoundException | SecurityException error) {
                Toast.makeText(this, R.string.experimental_enable_accessibility, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showUpdate(UpdateChecker.Release release) {
        if (isFinishing() || isDestroyed() || inSetup) return;
        String installed;
        try {
            installed = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            return;
        }
        boolean available = release != null && release.isNewerThan(installed);
        updateButton.setVisibility(available ? View.VISIBLE : View.GONE);
        if (!available) return;
        updateButton.setText(getString(R.string.update_available, release.versionName));
        updateButton.setOnClickListener(ignored -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(release.releaseUrl)));
            } catch (ActivityNotFoundException error) {
                Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void refresh() {
        if (inSetup) {
            renderSetup();
            return;
        }
        boolean overlayAllowed = Settings.canDrawOverlays(this);
        boolean cameraChosen = !MaskMode.autoPackage(this).isEmpty();
        boolean detectionEnabled = isWatcherEnabled();
        if (setupStatus != null) {
            setupStatus.setText(!overlayAllowed ? R.string.setup_overlay_missing
                    : !cameraChosen ? R.string.setup_manual_ready
                    : !detectionEnabled ? R.string.setup_detection_missing : R.string.setup_ready);
        }
        if (modeStatus != null) {
            modeStatus.setText(getString(R.string.mask_status, MaskMode.label(this, MaskMode.get(this))));
        }
        if (opacityStatus != null) opacityStatus.setText(getString(R.string.opacity_value, MaskMode.opacity(this)));
        if (positionStatus != null) {
            int offset = MaskMode.verticalOffset(this);
            verticalPosition.setProgress(offset + 360);
            positionStatus.setText(getString(MaskMode.hasVerticalOffsetOverride(this)
                    ? R.string.vertical_value : R.string.vertical_default, offset));
        }
        if (adjustmentArrows != null) adjustmentArrows.setChecked(MaskMode.adjustmentArrows(this));
        if (subtitlePositionStatus != null) {
            boolean enabled = (MaskMode.get(this) & MaskMode.SUBTITLES) != 0;
            subtitlePositionStatus.setText(getString(R.string.subtitle_vertical_value, MaskMode.subtitleOffset(this)));
            subtitlePositionStatus.setEnabled(enabled);
            subtitlePositionStatus.setAlpha(enabled ? 1f : .38f);
            subtitlePosition.setEnabled(enabled);
            resetSubtitlePosition.setEnabled(enabled);
        }
        if (autoStatus != null) {
            String target = MaskMode.autoPackage(this);
            autoStatus.setText(target.isEmpty() ? getString(R.string.auto_disabled)
                    : getString(R.string.auto_selected, appLabel(target))
                    + (detectionEnabled ? "" : getString(R.string.auto_enable_hint)));
            detectionButton.setText(detectionEnabled ? R.string.setup_manage_detection : R.string.enable_detection);
        }
        if (experimentalStatus != null) {
            experimentalStatus.setText(!overlayAllowed ? R.string.setup_overlay_missing : !cameraChosen
                    ? R.string.experimental_choose_camera : !detectionEnabled
                    ? R.string.experimental_enable_accessibility : DeviceProfiles.modeDetection(this) == null
                    ? R.string.experimental_no_profile : R.string.experimental_profile_ready);
            Runnable help = !overlayAllowed ? this::explainOverlayPermission
                    : !cameraChosen ? () -> chooseCamera(false)
                    : !detectionEnabled ? this::openAccessibilitySettings : null;
            experimentalStatus.setOnClickListener(help == null ? null : ignored -> help.run());
            experimentalStatus.setClickable(help != null);
            experimentalStatus.setFocusable(help != null);
            experimentalStatus.setTextColor(getColor(help == null ? R.color.on_surface : R.color.brand));
            experimentalStatus.setPaintFlags(help == null
                    ? experimentalStatus.getPaintFlags() & ~Paint.UNDERLINE_TEXT_FLAG
                    : experimentalStatus.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        }
    }

    private LinearLayout addAccordion(LinearLayout content, int title) {
        Button header = new Button(this, null, 0, R.style.SafeAreaButton_Accordion);
        header.setText(title);
        header.setAllCaps(false);
        header.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_expand, 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(16);
        content.addView(header, params);
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(32), dp(8), dp(32), dp(20));
        content.addView(section, new LinearLayout.LayoutParams(-1, -2));
        setAccordionExpanded(header, section, false);
        header.setOnClickListener(ignored -> {
            boolean expand = section.getVisibility() != View.VISIBLE;
            if (expandedSection != null) setAccordionExpanded(expandedHeader, expandedSection, false);
            setAccordionExpanded(header, section, expand);
            expandedHeader = expand ? header : null;
            expandedSection = expand ? section : null;
        });
        header.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(section.getVisibility() == View.VISIBLE
                        ? AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE
                        : AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
            }
            @Override public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
                if (action == AccessibilityNodeInfo.ACTION_EXPAND || action == AccessibilityNodeInfo.ACTION_COLLAPSE) {
                    if ((action == AccessibilityNodeInfo.ACTION_EXPAND) != (section.getVisibility() == View.VISIBLE)) {
                        return header.performClick();
                    }
                    return false;
                }
                return super.performAccessibilityAction(host, action, arguments);
            }
        });
        return section;
    }

    private void setAccordionExpanded(Button header, LinearLayout section, boolean expanded) {
        section.setVisibility(expanded ? View.VISIBLE : View.GONE);
        header.setSelected(expanded);
    }

    private TextView sectionLabel(LinearLayout content, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(getColor(R.color.on_surface));
        label.setTextSize(15);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(24);
        content.addView(label, params);
        return label;
    }

    private List<TargetApp> launchableApps() {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = getPackageManager().queryIntentActivities(launcher, 0);
        ArrayList<TargetApp> apps = new ArrayList<>();
        apps.add(new TargetApp("", getString(R.string.no_app)));
        HashSet<String> packages = new HashSet<>();
        for (ResolveInfo info : resolved) {
            String packageName = info.activityInfo.packageName;
            if (getPackageName().equals(packageName) || !packages.add(packageName)) continue;
            apps.add(new TargetApp(packageName, String.valueOf(info.loadLabel(getPackageManager()))));
        }
        Collections.sort(apps.subList(1, apps.size()), Comparator.comparing(app -> app.label, String.CASE_INSENSITIVE_ORDER));
        return apps;
    }

    private int targetSelection(List<TargetApp> apps, String packageName) {
        for (int i = 0; i < apps.size(); i++) if (apps.get(i).packageName.equals(packageName)) return i;
        return 0;
    }

    private String appLabel(String packageName) {
        for (TargetApp app : launchableApps()) if (app.packageName.equals(packageName)) return app.label;
        return packageName;
    }

    private boolean isWatcherEnabled() {
        if (!MaskMode.accessibilityConsent(this)) return false;
        AccessibilityManager manager = getSystemService(AccessibilityManager.class);
        ComponentName expected = new ComponentName(this, AppWatchAccessibilityService.class);
        if (manager == null) return false;
        for (AccessibilityServiceInfo service : manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (expected.equals(ComponentName.unflattenFromString(service.getId()))) return true;
        }
        return false;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void addMaskCheckbox(LinearLayout content, int label, int color, int mask) {
        CheckBox checkbox = new CheckBox(this);
        checkbox.setText(label);
        checkbox.setTextColor(getColor(R.color.on_surface));
        checkbox.setButtonTintList(android.content.res.ColorStateList.valueOf(getColor(color)));
        checkbox.setMinHeight(dp(48));
        checkbox.setChecked((MaskMode.get(this) & mask) != 0);
        checkbox.setOnCheckedChangeListener((button, checked) -> {
            int selection = MaskMode.get(this);
            MaskMode.set(this, checked ? selection | mask : selection & ~mask);
            OverlayService.refreshMask(this);
            refresh();
        });
        content.addView(checkbox, new LinearLayout.LayoutParams(-1, -2));
    }

    private static final class TargetApp {
        final String packageName;
        final String label;
        TargetApp(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
        @Override public String toString() { return label; }
    }
}
