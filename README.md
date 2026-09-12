# Parallax Home — minimal Android launcher prototype

This repository is a working, dependency-light Android Home app written in Kotlin. Install it, choose **Parallax Home** as the Home app, hold the phone at a comfortable angle, tap **RECENTER**, and gently tilt it left or right. The foreground applies a smooth inverse horizontal rotation while the atmosphere stays fixed, producing a floating-layer illusion.

Version 0.8 adds a persistent **PARALLAX EFFECT: ON/OFF** control. Blur stays completely off below 10 degrees of side tilt, then rises gently until the 15-degree recenter point; its maximum strength is intentionally light enough to keep text and icons readable. The launcher also includes persistent user-selected app shortcuts, Android home-screen widgets, and the system wallpaper picker.

## What software can and cannot do

The phone's rotation sensors know how the **phone** moves relative to its calibrated pose. They cannot know where your eyes are. This prototype therefore keeps the UI aligned to the pose captured at startup or when you tap **RECENTER**; it is not eye/head tracking. Software also cannot change the physical angle of the glass, so this is a convincing perspective illusion rather than literal optical compensation.

## 1. Install the required software

You need:

- VS Code for editing.
- JDK 17 or newer. JDK 17 is the conservative choice. Set `JAVA_HOME` to it.
- Android SDK Command-line Tools.
- Android SDK Platform 34, Build Tools 34.0.0, and current Platform Tools (ADB).
- A USB data cable, unless you use Android's wireless debugging.

The easiest setup is to install Android Studio once, use its **SDK Manager** to install those SDK packages, and then do all editing/building in VS Code. Android Studio does not need to be running. For a CLI-only setup, download the command-line tools from [Android Developers](https://developer.android.com/tools), put them under your Android SDK, and run:

```powershell
sdkmanager.bat --licenses
sdkmanager.bat "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

Newer command-line tool releases may present the replacement `android sdk` command; `sdkmanager` remains suitable if it is what your installed tools provide. Android documents both the [command-line tool packages](https://developer.android.com/tools) and [`sdkmanager`](https://developer.android.com/tools/sdkmanager).

Optional VS Code extensions: a Kotlin language extension and **Gradle for Java**. Extensions improve navigation but are not part of the build.

### Environment variables on Windows

Adjust the paths for your installation. These commands affect the current PowerShell window:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:Path"
java -version
adb version
```

On macOS/Linux, use `export JAVA_HOME=...`, `export ANDROID_HOME=...`, and add the equivalent directories to `PATH`.

## 2. Open and build the VS Code project

Open this folder in VS Code:

```powershell
code C:\path\to\ParallaxLauncher
```

If Gradle cannot find the SDK, create `local.properties` beside this README. Use forward slashes even on Windows:

```properties
sdk.dir=C:/Users/YourName/AppData/Local/Android/Sdk
```

Build from VS Code's integrated terminal:

```powershell
.\gradlew.bat assembleDebug
```

macOS/Linux:

```bash
./gradlew assembleDebug
```

The first connected build downloads Gradle 9.5 and Android Gradle Plugin 9.3.1. AGP 9 has built-in Kotlin support, so there is intentionally no separate Kotlin Gradle plugin. The resulting, debug-signed APK is:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Android's official command-line build guide also uses the Gradle wrapper and `assembleDebug`: [Build your app from the command line](https://developer.android.com/build/building-cmdline).

### If VS Code says “supplied phased action failed”

That message comes from VS Code's Gradle project importer and hides the useful underlying error. Configure your local `.vscode/settings.json` to select Android Studio's bundled JDK, your Android SDK location, and a writable Gradle user-home directory. This file is intentionally ignored because those paths differ between computers.

After pulling/opening these changes:

1. Press `Ctrl+Shift+P` and run **Developer: Reload Window**.
2. If an old error remains, run **Java: Clean Java Language Server Workspace**, choose **Restart and delete**, and let this project re-import.
3. Open a **new** VS Code terminal so it receives the corrected environment variables.
4. Run the real build command:

```powershell
.\gradlew.bat --stop
.\gradlew.bat assembleDebug
```

You can instead press `Ctrl+Shift+B` and select **Android: Build debug APK**. The included `.vscode/tasks.json` supplies the same paths automatically.

The Gradle configuration itself can be checked without compiling the app:

```powershell
.\gradlew.bat :app:tasks
```

If that command works but VS Code still underlines `android { ... }`, it is an editor-import limitation rather than a Kotlin DSL syntax error. Microsoft's Gradle extension primarily targets Java Gradle projects and does not provide full Android IDE support; the wrapper command is the authoritative result.

## 3. Project map

```text
ParallaxLauncher/
├── settings.gradle.kts
├── build.gradle.kts                 # AGP 9.3.1
├── gradle.properties
├── gradlew / gradlew.bat            # reproducible Gradle 9.5 wrapper
└── app/
    ├── build.gradle.kts             # app ID, SDK levels, Java 17
    └── src/main/
        ├── AndroidManifest.xml       # HOME + normal LAUNCHER intent filters
        ├── java/com/example/parallaxlauncher/
        │   ├── MainActivity.kt       # lifecycle and edge-to-edge window
        │   ├── OrientationTracker.kt # sensors, calibration, screen remapping
        │   └── LauncherScene.kt      # UI, smoothing, inverse 3D transform
        └── res/
            ├── drawable/ic_launcher.xml
            └── values/styles.xml
```

There are no Compose, AndroidX, or third-party dependencies. This makes the prototype easier to understand and faster to build.

## 4. How it becomes an Android Home app

The important declaration is already in `app/src/main/AndroidManifest.xml`:

```xml
<intent-filter>
    <action android:name="android.intent.action.MAIN" />
    <category android:name="android.intent.category.HOME" />
    <category android:name="android.intent.category.DEFAULT" />
</intent-filter>
```

`CATEGORY_HOME` identifies the activity as a home screen. A separate `MAIN` + `LAUNCHER` filter leaves a normal icon available during development. Android's Intent reference defines `ACTION_MAIN` + `CATEGORY_HOME` as the home-screen activity: [Intent API reference](https://developer.android.com/reference/android/content/Intent#CATEGORY_HOME).

Android does not allow this app to transform the Pixel, Samsung, Xiaomi, or another vendor launcher's interface. To see the effect on the actual Home screen, **Parallax Home itself must be selected as the default Home app**. It is a replacement launcher, not an overlay on the stock launcher. A live wallpaper could animate behind the stock launcher, but it could not tilt its icons, widgets, or controls. The fixed Home-app control opens Android's protected Home-role chooser or Home-app settings; Android deliberately requires you to approve both selecting Parallax Home and switching back to another launcher.

No sensor runtime permission is needed. The manifest marks accelerometer and gyroscope hardware as optional so installation is not blocked on unusual devices; the code decides what is actually available.

## 5. How sensor reading works

`OrientationTracker.kt` chooses sensors in this order:

1. `TYPE_GAME_ROTATION_VECTOR`: gyro + accelerometer fusion, no magnetic-north dependency. Best for a responsive visual effect, though yaw can slowly drift.
2. `TYPE_ROTATION_VECTOR`: fused absolute orientation, often including magnetometer data.
3. `TYPE_ACCELEROMETER`: reduced tilt-only fallback. It cannot observe rotation around the gravity vector, so it is not full 3D.

Rotation matrices are remapped for portrait, both landscape directions, and upside-down portrait. At startup the status says **Hold steady — calibrating**. The tracker waits for about half a second and requires a run of stable sensor samples before accepting the neutral pose, so an opening animation or hand movement does not become the reference. **RECENTER** immediately replaces that reference with the current pose. See Android's [position-sensor documentation](https://developer.android.com/develop/sensors-and-location/sensors/sensors_position) for the rotation-vector coordinate model.

## 6. From sensor pose to smooth compensation

For rotation-vector sensors, the tracker calculates:

```text
relativeRotation = transpose(referenceRotation) × currentRotation
```

It converts that relative matrix to pitch, roll, and yaw. Version 0.8 deliberately uses only left/right roll. `LauncherScene.setTargetOrientation()` ignores pitch and yaw and applies 90% inverse compensation. Whenever relative roll reaches +15 or -15 degrees, `OrientationTracker` immediately makes that pose the new center, so the visible layer returns smoothly toward zero.

Sensor callbacks only update targets. Rendering happens once per display frame through `Choreographer`. A time-based exponential low-pass filter avoids jitter while behaving consistently on 60, 90, and 120 Hz screens:

```text
blend = 1 - exp(-frameTime / smoothingTime)
smoothed += (target - smoothed) × blend
```

## 7. The perspective/parallax layer

`AtmosphereView` is the rear depth layer. The foreground is a separate Android `View` layer with:

- horizontal `rotationY` for perspective; vertical and twisting rotations stay at zero;
- a long `cameraDistance` so perspective remains subtle;
- foreground and background translation in opposite directions to sell separation;
- 1.035× scale so tiny blank corners do not appear during rotation.

The effect is GPU-composited by Android; the app does not redraw the whole UI on every sensor event. On Android 12 and newer, version 0.8 applies a light GPU `RenderEffect` to the entire launcher only after side tilt exceeds 10 degrees and also requests a small background-window blur for the wallpaper. Android 8-11 use a light whole-screen frosted fallback because the platform blur API is unavailable. Tap **PARALLAX EFFECT: OFF** to immediately remove both motion and blur; the setting is remembered after restarts.

## 8. Install the APK on a physical phone

### Download a ready-built APK

Open the project's [GitHub Releases page](https://github.com/kemuul/parallax-home-android/releases/latest) on the Android phone, expand **Assets**, and download `Parallax-Home-v0.8.0-beta.apk`. If Android asks, allow the browser or file manager to **Install unknown apps**, then open the downloaded APK and tap **Install**. After installation, open **Settings -> Apps -> Default apps -> Home app** and select **Parallax Home**.

Release APKs use the permanent application ID `io.github.kemuul.parallaxhome`. Every update must keep that ID, increase `versionCode`, and use the same private signing key.

### Which USB cable do I need?

Most current Android phones have a USB-C port. Use either:

- **USB-C to USB-C** if your computer has a USB-C port, or
- **USB-A to USB-C** if your computer has the older rectangular USB-A port.

The important part is that the cable supports **data**, not merely charging. A charge-only USB-C cable will power the phone but `adb devices` will not see it. You do not need an OTG adapter or any custom hardware. Micro-USB phones work too—use a data-capable cable matching that phone.

On the phone:

1. Open **Settings → About phone**.
2. Tap **Build number** seven times.
3. Open **Developer options** and enable **USB debugging**.
4. Connect the phone and accept its RSA debugging prompt.

Then run:

```powershell
adb devices
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n io.github.kemuul.parallaxhome/com.example.parallaxlauncher.MainActivity
```

Or use the included VS Code tasks:

1. Press `Ctrl+Shift+P`.
2. Choose **Tasks: Run Task**.
3. Choose **Android: Install debug APK**. It builds first, then runs `adb install -r`.

If you only want to try the APK already built in this workspace, you can skip the build and run:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r ".\app\build\outputs\apk\debug\app-debug.apk"
```

If `adb devices` says `unauthorized`, unlock the phone and accept the prompt. If no device appears, change the USB mode from charge-only to file transfer and install the manufacturer's Windows USB driver if needed.

### No cable: wireless debugging

On Android 11 or newer, you can use **Developer options → Wireless debugging** while the computer and phone are on the same Wi-Fi network:

1. Choose **Pair device with pairing code** on the phone.
2. Run `adb pair PHONE_IP:PAIRING_PORT` and enter the displayed code.
3. Run `adb connect PHONE_IP:DEBUG_PORT` using the separate debugging port shown by the phone.
4. Confirm with `adb devices`, then use the same `adb install -r ...` command.

USB is usually simpler for the first installation, but it is not mandatory on devices that support wireless debugging.

Press the physical/gesture **Home** control. Select **Parallax Home** and initially choose **Just once**. Once satisfied, choose **Always**, or use **Settings → Apps → Default apps → Home app**. The exact menu name varies by manufacturer.

The fixed Home-app control says **SET AS HOME** before Parallax Home is selected. Once it is the default, the control changes to **CHANGE HOME APP**; tap it and select your original launcher in Android's Home-app settings to switch back. Android deliberately requires the user to approve either choice.

To return to the original launcher, select it again in **Default apps → Home app**. Keep the stock launcher installed.

## 9. Test and calibrate

1. Hold the phone as you normally would while looking at it.
2. Open or return to Parallax Home and keep still while **Hold steady — calibrating** is displayed. It centers automatically.
3. Tilt slowly left and right. The foreground should move oppositely while the atmosphere moves a smaller distance in the other direction. Forward/back movement should have no visual effect.
4. If the initial pose is uncomfortable, tap the fixed **RECENTER** control. It no longer moves with the parallax layer, so it remains easy to hit while tilted.
5. Tilt until the sensor reaches +15 or -15 degrees. That pose becomes the new center immediately; this is separate from the five-second near-neutral drift correction.
6. Below 10 degrees, verify that the screen remains sharp. From 10 to 15 degrees, the entire launcher should gain only a soft blur and remain readable.
7. Tap **PARALLAX EFFECT: OFF**. Rotation and blur should clear immediately. Return Home or restart the launcher to verify that OFF is remembered; tap it again to enable and recenter at the current pose.
8. Tap **CHANGE HOME APP** and verify that Android lets you select your original launcher. You can return to Parallax Home from that same settings screen.
9. Rotate between portrait and landscape. Android may recreate the activity and automatically establish a new neutral pose for the new screen axes.
10. Watch the SIDE readout. Saturation at the configured limit is expected and prevents nausea-inducing motion.

Tune the constants at the top of `LauncherScene.kt`:

| Constant | Effect |
|---|---|
| `BLUR_START_DEGREES` | Blur is completely off until 10 degrees of side tilt |
| `RECENTER_THRESHOLD_DEGREES` | Light blur reaches its maximum by 15 degrees; the tracker also recenters at ±15 |
| `COMPENSATION_STRENGTH` | Fraction of physical roll applied inversely; currently 0.90 |
| `MAX_UI_ROTATION_DEGREES` | Visible perspective cap; currently 42 degrees |
| `SMOOTHING_TIME_SECONDS` | Higher is smoother but laggier; try 0.10–0.20 |

If one axis feels reversed on a vendor device, remove or add the minus sign for that target in `setTargetOrientation()`. Change one axis at a time, rebuild with `assembleDebug`, and reinstall with `adb install -r`.

For less battery use, change `SENSOR_DELAY_GAME` to `SENSOR_DELAY_UI`. The current code already unregisters the sensor and frame callback in `onPause()`, so it stops working when the launcher is not visible.

Useful diagnostics:

```powershell
adb shell dumpsys sensorservice
adb logcat | Select-String "AndroidRuntime|parallaxlauncher"
adb shell pm resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME
```

## 10. Customize the launcher

- **WALLPAPER** opens Android's system wallpaper picker. The selected system wallpaper is visible behind the translucent depth shading.
- **ADD APP** lists installed launchable activities. Tap one to add a persistent shortcut; long-press a shortcut to remove it without uninstalling the app.
- **ADD WIDGET** opens Android's widget picker. Selected widget IDs persist through launcher restarts; long-press a widget to remove it.

## 11. Continue expanding the launcher

A sensible expansion order is:

1. Replace the horizontal favorites strip with a paged grid and drag-to-reorder positions.
2. Add an all-apps drawer with search and alphabetic indexing.
3. Add widget resize handles and free-form placement.
4. Add gestures, folders, long-press app info/uninstall shortcuts, and accessibility descriptions.
5. Add rotation lock options, per-axis tuning controls, and stored calibration.
6. Profile with `adb shell dumpsys gfxinfo io.github.kemuul.parallaxhome` and Android Studio's profilers as more animated layers are added.

Android 11+ package visibility rules matter when building the app drawer. Prefer a manifest `<queries>` block for the launcher intent rather than immediately requesting broad package visibility. Publishing a full launcher also requires proper release signing, privacy disclosures, crash handling, backup/restore decisions, and extensive testing across OEM home-app pickers.

## Troubleshooting

- **`JAVA_HOME` error:** point it to a JDK directory, not its `bin` folder. Confirm with `& "$env:JAVA_HOME\bin\java.exe" -version`.
- **SDK not found:** fix `local.properties`; do not commit that machine-specific file.
- **Platform 34 missing:** install `platforms;android-34` and `build-tools;34.0.0`.
- **Gradle cannot download:** the first build needs access to `services.gradle.org`, Google's Maven repository, and Maven Central.
- **No movement:** check the status at top right. “Accelerometer fallback” is intentionally limited; “No motion sensor” means the device exposes none of the supported sensors.
- **Slow drift:** expected primarily on game rotation vector yaw. Tap **RECENTER**, or temporarily prefer `TYPE_ROTATION_VECTOR` in `OrientationTracker.start()`.
- **Corners show while tilting:** increase the layer scale slightly or reduce the maximum angles.
- **Motion feels sickening:** reduce strengths and maximums; never treat larger rotation as automatically better.
