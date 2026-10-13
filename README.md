# LogCam

Native Android camera app that records S-Log3 (S-Gamut3.Cine / S-Gamut3) video.

Pipeline: Camera2 (manual ISO / shutter / focus / WB, HLG10 10-bit stream when the phone offers it)
-> OpenGL shader (linearise -> gamut convert -> S-Log3 OETF) -> HEVC Main10 encoder -> MP4 in Movies/LogCam.

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
Builds are signed with the key in `app/logcam.jks`, so each new APK installs over the previous one and keeps
your settings. (The very first install of this version needs the old LogCam uninstalled once.)
Videos are saved to `DCIM/LogCam` and also appear in the in-app Recordings library.
