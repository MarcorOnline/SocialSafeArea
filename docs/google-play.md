# Google Play preparation

Version **1.5.0** (version code **12**) updates the existing [Google Play app](https://play.google.com/store/apps/details?id=it.pistacchissimo.socialoverlay). Store availability depends on Google's review and rollout; Play Console is the source for the current submission status. This document describes the Play build, signing requirements and verification steps.

## Android requirements

- Compile and target SDK: 36. Minimum supported Android version remains API 26.
- Android Gradle Plugin 8.9.1, Gradle 8.11.1 and JDK 21.
- No third-party runtime libraries or native binaries are introduced.

The `play` build type is intended for Google Play: it disables the automatic GitHub update check and removes the Internet permission. Updates are handled by Google Play. External links still open in the user's browser on an explicit tap. The standard `release` and `debug` builds retain the existing GitHub update check. This distinction avoids unnecessary network traffic and data-disclosure ambiguity in the Play build without changing APK distribution behavior.

[Google Play's target API requirement](https://support.google.com/googleplay/android-developer/answer/11926878) was checked on 2026-11-01: app updates must target API 36 or higher. This build targets API 36. The build-tool setup was checked against [the minimum AGP versions](https://developer.android.com/build/releases/about-agp) on 2026-10-26.

## Privacy and optional accessibility

About includes an explicit privacy-policy button linking to the app-specific section of the existing article:

https://www.pistacchissimo.it/news/social-safe-area-lapp-per-girare-reels-e-tiktok-migliori-gratis/#privacy-safe-area

Before opening Android accessibility settings, the app explains which window and camera-control information it accesses, the local processing, the optional nature of the feature and how to disable it. Cancel leaves consent unset and does not open settings. The affirmative button records consent and opens Android settings; it does not enable the OS service itself.

The accessibility service ignores events and does not retrieve camera nodes until the in-app consent has been recorded. This also applies to users upgrading with the OS service already enabled: open Automatic opening and use Enable camera detection to review and accept the disclosure again. Experimental Photo/Video detection still requires its separate opt-in.

The Play accessibility declaration and foreground-service declaration describe this behavior. Guided setup now explains prerequisites before opening system settings, verifies the actual grants, and lets users skip optional accessibility and Photo/Video detection. Calibration itself only needs the existing overlay permission; it does not access camera images or record video. Google may require demonstration videos and review. Do not claim this is an accessibility tool intended for users with disabilities.

The standard APK's GitHub update check makes an HTTPS request; its privacy policy explains the connection metadata. Only the `play` build omits that check and Internet permission. Complete Play Data safety against the actual uploaded artifact; absence of analytics alone is not sufficient reason to assert that no data is collected.

## Signing and source availability

The public GitHub APK has an existing app-signing key. Play must use the same app-signing certificate if installations from both channels are to update each other. A matching upload key alone does not ensure this: Google re-signs downloaded APKs with the Play app-signing key.

Before rolling out a release, inspect Protected with Play and configure the existing app-signing key using Google's supported encrypted PEPK transfer. Private keys and credentials stay ignored and are never included in source archives. Transferring a private key to Google requires explicit approval. See [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756).

Publish the corresponding sources for the exact version being distributed, under GPL-3.0-only, before making the Play release available. Do not reuse an existing release tag for changed source code.

## Verification

Build and lint:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:bundlePlay :app:lintPlay :app:lintRelease
```

After installing the debug app and test APK in an isolated Android device or emulator:

```sh
adb shell am instrument -w it.pistacchissimo.socialoverlay.test/it.pistacchissimo.socialoverlay.PrivacyFlowTest
```

This test exercises the actual disclosure, cancellation, affirmative consent and the HTTPS privacy link. It intercepts external activity launches so it does not enable system permissions or open an external browser, and restores the original consent preference.
