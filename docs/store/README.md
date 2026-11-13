# Store assets for 1.5.1

- `screenshots/01-home-it.png`: current home screen in Italian, captured on the
  Pixel 2 XL with version 1.5.1.
- `screenshots/01-home-en.png`: the same home screen in English.
- `screenshots/02-camera-it.png`: the real camera screenshot with the jar photo
  already used in the README cover. The photo, camera controls and overlay are
  preserved; 22 pixels of excess black margin below the camera UI are cropped.
- `release-notes-v1.5.1.txt`: Play changelog in Italian, English, French, Spanish
  and German, with each language below the 500-character limit.

The home exports omit Android's status/navigation bars and their notification
icons. All images are opaque RGB PNGs, with only an sRGB color profile and no
EXIF data. Exports meet the 320–3840 pixel side limits and maximum 2:1 aspect
ratio in the [Google Play preview-asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151).

The signed Play bundle is generated locally under the ignored
`release-artifacts/` directory. Its existence and these assets do not establish
that Play Console upload, submission or review has completed.
