# K Live Launchers — Android app

Turn any image or icon into a living, animated Android home screen.
The app has two modes:

- **Studio** — upload images, pick colours, 10–20 screens, icon shapes and finishes,
  transitions, particle effects and app-open animations, with a live phone preview.
- **Launcher** — your design becomes your real home screen, using the apps installed on your phone.

## Features

**Real launcher**
- Can be set as the phone's default home screen (Home button returns to it)
- Shows your installed apps, with icons themed from your palette (Material You style) or kept original
- App drawer with search and a "Search the web" fallback
- Long-press an app: Open, Add to dock, App info, Uninstall
- Long-press empty space: Customise, Edit mode, Surprise me, Next wallpaper, Light/Dark, Lock screen preview
- Swipe up for the app drawer, swipe down for notifications, double-tap for the lock screen preview
- App list refreshes automatically when you install or remove apps
- Back button closes overlays, then returns to the first screen

**Design and effects**
- Palette extraction and "vibe" detection from your image auto-pick matching effects
- 20 screen types (Home, App grid, Music, Weather, Calendar, Memories, Buddy, To-do, Activity, Smart home,
  Focus timer, Mood, Friends, Wallet, Collage, Trips, News, Games, Device, Quick settings)
- 12 page transitions, 12 particle effects, tap bursts and finger trails, 5 app-open animations
- 8 icon shapes, 7 finishes, idle and entrance animations
- Light, dark or automatic night mode; tilt 3D parallax; daily wallpaper rotation through your images

**Phone integration**
- Real battery level in the status bar and Device screen
- Haptic feedback on taps, swipes and long-presses
- Pick images straight from your gallery
- Set your image as the system wallpaper (home and lock screen)
- Save the theme as a file or share it with the Android share sheet
- Works fully offline (fonts are bundled)

## Build the APK

### Option A — Android Studio (easiest on a computer)
1. Install Android Studio (Ladybug or newer).
2. **File → Open** and choose this `KLiveLaunchers` folder. Let Gradle sync finish.
3. Plug in your phone with USB debugging on and press **Run**, or use
   **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
4. The APK appears in `app/build/outputs/apk/`.

### Option B — GitHub (no install, gives you a download link)
1. Create a new GitHub repository and upload the contents of this folder (keep the `.github` folder).
2. GitHub Actions builds automatically. Open the **Actions** tab → latest run → download
   **k-live-launchers-apk**.
3. For a permanent public link, create a tag such as `v2.0`
   (Releases → Draft a new release → tag `v2.0` → Publish). The APK is attached to that release.

### Option C — command line
```
./gradlew assembleRelease
```
Needs JDK 17 and the Android SDK (set `ANDROID_HOME` or create `local.properties` with `sdk.dir=...`).

## Install on your phone
1. Copy `k-live-launchers.apk` to the phone and open it.
2. Allow "Install unknown apps" for your file manager or browser when asked.
3. Open K Live Launchers, design your launcher, then tap **Finish → Use as my home screen**.

The release APK is signed with the debug key so it installs straight away.
Use your own keystore before publishing to Google Play.

## Project layout
```
app/src/main/java/com/klive/launchers/MainActivity.kt   WebView host, home-screen role, insets, files
app/src/main/java/com/klive/launchers/NativeBridge.kt   window.KLiveNative: apps, launch, haptics, battery, wallpaper, share
app/src/main/assets/www/index.html                      The studio and launcher UI
app/src/main/assets/www/fonts/                          Bundled fonts for offline use
.github/workflows/build-apk.yml                         Cloud build that produces the APK
```
Requirements: Android 8.0 (API 26) or newer.
