# LogCam 2.0

Native Android filmmaking camera with manual Camera2 controls, preview-only exposure/focus assists, framing guides, selectable camera profiles, Rec.709 monitoring, and an in-app recording library.

Pipeline: Camera2 (manual ISO / shutter / focus / WB where the selected camera supports them; HLG10 input where available) -> OpenGL conversion/monitoring -> HEVC encoder -> MP4 in `DCIM/LogCam`.

**Colour note:** the app applies an S-Log3 encoding transform to the Camera2 source. This is not proof of sensor-native Sony S-Log capture; input characteristics vary by device, and footage should be tested and graded accordingly.

## Get the APK (no Android Studio needed)
1. Create an empty GitHub repo, upload everything in this folder (keep the `.github` folder).
2. Open the repo's **Actions** tab -> "Build APK" -> Run workflow (it also runs on every push).
3. When it finishes, download the **LogCam-apk** artifact, unzip, copy `app-debug.apk` to your phone, install.

## Or build locally
Open this folder in Android Studio (Koala or newer), let it sync, then Build > Build APK(s).

## In DaVinci Resolve
Set the clip's input colour space to Sony S-Gamut3.Cine / S-Log3 (or S-Gamut3 / S-Log3 if you chose that gamut),
or use a Color Space Transform / ACES IDT with the same settings. Clips are tagged full range.

## Updating
The release workflow publishes versioned APKs on GitHub Releases. Android requires user confirmation to install downloaded APK updates; updates are not silently installed by the app. The APK must be signed with the same key as the installed build to update in place.

Camera profiles are starting points rather than guarantees: available frame rates and manual controls depend on the selected physical camera. Zebras, false colour and focus peaking are preview-only and are not burned into recorded footage.

Videos are saved to `DCIM/LogCam` and appear in the in-app Recordings library.
