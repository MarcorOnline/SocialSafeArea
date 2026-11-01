# Community camera profiles

Our main calibration priority is correct vertical alignment for common camera
apps on real devices. Use portrait orientation and a **9:16 video preview**.

Edit [`camera-profiles.json`](../app/src/main/assets/camera-profiles.json).
It is a JSON array; each object has these six required fields and may include
the optional `mode_detection` object described below:

```json
{
  "manufacturer": "samsung",
  "model": "SM-S948B",
  "android_sdk": 37,
  "camera_package": "com.sec.android.app.camera",
  "camera_version": "17.0.00.43",
  "vertical_offset_dp": 0
}
```

This initial entry was tested on **Samsung Galaxy S26 Ultra, Android 17 / API 37**.

| Field | Meaning |
| --- | --- |
| `manufacturer` | `Build.MANUFACTURER`, ignoring case and surrounding whitespace. |
| `model` | `Build.MODEL`, not the marketing name; ignoring case and whitespace. |
| `android_sdk` | Integer Android API level, not its marketing version. |
| `camera_package` | Camera package identifier. |
| `camera_version` | Installed camera application's version name. |
| `vertical_offset_dp` | Integer slider adjustment, −360…+360 dp relative to the scaled reference; positive moves down. |

## Proximity rules

1. Prefer the same manufacturer **and model**. If no model matches, search other
   models of the same manufacturer. For an unknown manufacturer, use the
   **first valid profile** in the file.
2. Within that model group, choose the exact Android API, then the nearest API
   by absolute numerical difference.
3. At the same API distance, prefer the same camera package.
4. Then choose the nearest camera version. Compare numerical components from
   major to minor/build; missing components act as zero. An unavailable camera
   version does not prevent selection.
5. Equal scores retain file order. Preserve the first reference entry unless
   intentionally changing the fallback for unknown brands.

Thus camera differences are relaxed before Android differences, and Android
differences before changing model within a brand. The reference profile still
applies to hypothetical Camera `17.1.00.43`. A device whose manufacturer has no
entries receives the first valid baseline, allowing the user to adjust it.

Invalid entries are skipped. Missing metadata and empty/unreadable profile data
are handled without stopping the guide; the ultimate fallback is 0 dp. No
network, serial number or unique device identifier is used for detection.

## Contribute an offset

Select the camera in the app, adjust the slider until both top and bottom of
the real preview align, then open **ⓘ → Copy calibration JSON**. Add the copied
object to the array and open a PR against **develop**, describing the device,
OS, camera version, preview ratio and real-device check. Remove personal content
from any screenshots. A PR can consist only of this data-file addition.

`validateCameraProfiles` runs automatically before builds and rejects missing
fields, invalid offsets and duplicate device/API/package/version combinations.

The user's manual override always wins, including after updates. Reset removes
it and uses the current suggestion. Without a selected app, lookup uses the
default video camera. Profiles ship offline with the next app build.

Offset calibration only changes vertical position. It does not calibrate frame
width, height or landscape, and the base geometry remains 9:16. Do not mistake
the camera's rule-of-thirds grid for the actual video boundaries.

## Experimental camera-mode detection

The optional **Experimental features → Show the mask only in "Video" mode** setting is
off by default. It needs the selected camera and the accessibility service.
It reads accessible camera controls after UI events, without screenshots, OCR,
periodic polling, storing control text or sending it over the network. Event
bursts are coalesced into at most one node lookup per 200 ms. An unrecognized
state releases any previous Photo suppression after 1.5 seconds; manual ON/OFF
is never overwritten. The quick button remains available in Photo mode.

Unlike offset suggestions, mode detection requires an exact manufacturer,
model, Android API, camera package and camera version match. No similar-device
fallback is allowed. Rules also depend on the camera's own language and layout;
the initial Samsung profile was verified on the reference device in Italian,
English, French, Spanish and German, in portrait orientation. Camera language is
independent of Safe Area's language. Language alternatives sharing a view ID
reuse a single lookup within each detection pass.
Unsupported languages, orientations and modes fall back to manual control.

Example optional object, using the Samsung mode selector's accessible description:

```json
"mode_detection": {
  "orientation": "portrait",
  "rules": [
    {
      "mode": "video",
      "view_id": "com.sec.android.app.camera:id/shooting_mode_list",
      "content_description": "Video, Modalità"
    },
    {
      "mode": "photo",
      "view_id": "com.sec.android.app.camera:id/shooting_mode_list",
      "content_description": "Fotografia, Modalità"
    }
  ]
}
```

`orientation` is `portrait` or `landscape`. Supply 1–16 rules, including both
`video` and `photo`. Each rule requires a full `view_id` belonging to the same
camera package and at least one predicate:

| Predicate | Meaning |
| --- | --- |
| `text` | Exact accessible text, ignoring case and outer whitespace. |
| `content_description` | Exact accessible description, with the same comparison. |
| `selected` | If present, must be `true`; require the node's selected state. |
| `bounds` | `[left, top, right, bottom]` within 0–1 of the camera root window; the node's centre must lie inside it. |

All predicates on a rule must match a visible node. Conflicting Photo and Video
matches yield an unknown state. A Video label beside Photo is not enough: use a
selected state, a description of the active mode, or a calibrated bounding box.
Bounds filter node matches; they do not restrict the Android accessibility
permission. Prefer semantic descriptions or IDs when they distinguish the mode.

Test transitions in both directions, reopening the camera, manual OFF, missing
selectors and recording controls before contributing rules. Do not infer Photo
from the absence of a Video label. The calibration export preserves mode rules
only when they belong to the exact selected camera profile; it does not invent
rules from a nearby profile or learn them automatically.

### Collect controls for a contribution

The inspector works with a normal installed camera and does not require a
debug build of Safe Area. With Android platform-tools installed and USB debugging
authorized, open the camera in **Photo**, then run:

```powershell
./scripts/inspect-camera-controls.ps1 -Serial DEVICE_SERIAL -CameraPackage CAMERA_PACKAGE
```

Use `camera_package` from the copied calibration JSON for `CAMERA_PACKAGE`.
Repeat in **Video** and compare only the mode selector. The inspector prints
camera control IDs, text, descriptions, selected states and normalized bounds,
plus orientation. It captures no images, deletes its temporary XML on the phone,
and never prints the device serial. Inspect the output before sharing: camera
controls can still contain private information, so share only selector entries.
The output is observations, not a complete `mode_detection` object.

Choose a stable view ID with a description that changes between Photo and Video,
or a selected state. If the camera exposes only carousel labels, use the node's
position to distinguish the centred mode from adjacent ones. Do not copy
`selected: false` from the observations into a rule: omit the predicate instead.

Add the `mode_detection` object to the matching calibration entry, using the
example above. For another language on the same device/API/camera version, append
another Photo/Video pair to its `rules`; do not create a duplicate profile. Rules
are alternatives, so matching any Video rule recognizes Video regardless of the
Safe Area app language. Match the camera's actual accessible strings rather than
translating them by hand. A node matching both modes is treated as unknown.

Run `./gradlew.bat :app:validateCameraProfiles` (or `./gradlew` on Unix), then build
and install the APK to test it. Validation checks structure, allowed predicates,
coordinates and duplicate metadata; a successful build does not prove that the
rules match the real camera. In the PR state device/API/camera version, languages,
orientation and which transitions you actually checked.

For the Samsung profile with the camera in Italian, enable the experiment and accessibility,
turn the guide ON, then run this real-device transition check:

```powershell
./scripts/verify-camera-mode.ps1 -Serial DEVICE_SERIAL
```

For another supported camera language, supply its actual carousel labels:

| Camera language | Additional arguments |
| --- | --- |
| English | `-PhotoLabel PHOTO -VideoLabel VIDEO` |
| French | `-PhotoLabel PHOTO -VideoLabel VIDÉO` |
| Spanish | `-PhotoLabel FOTO -VideoLabel VÍDEO` |
| German | `-PhotoLabel FOTO -VideoLabel VIDEO` |

These labels control the test's taps; runtime detection still uses the community
profile's accessible descriptions. Do not change the phone's system language to
match Safe Area: only the camera's actual control strings matter.

It checks Video → Photo → Video, keeps manual ON, restores the initial camera
mode and returns Home. It does not start recording or take photos. Its diagnostic
node dumps are temporary; no control text is committed. The general instrumentation
test also covers default-off, exact matching, label/bounds predicates and preserving
manual OFF. These checks are functional tests, not battery or recording benchmarks.
