<div align="center">
  <img src="shared/src/main/res/drawable-nodpi/nevoplay_launcher.png" width="160" height="160" alt="NEVOPlay app icon" />
  <h1>NEVOPlay</h1>
  <p><strong>A vehicle-focused CarPlay receiver for the Changan Qiyuan A07.</strong></p>
  <p><a href="README.md">English</a> · <a href="README.zh-CN.md">简体中文</a></p>
</div>

NEVOPlay connects an iPhone to an Android-based head unit and renders the
CarPlay experience on the vehicle display. This codebase concentrates on the
hardware and system behavior of the Qiyuan A07; it is an enthusiast project,
not software published or supported by Changan.

## The target vehicle

The current integration reference is a Qiyuan A07 running QiyuanOS 2.2
(Android 11), with a 2560 × 1600 display, a CH341-based MFi bridge and Wi-Fi
P2P for wireless sessions. Other model years, trims, system images and adapter
boards have not been established as compatible. Treat installation on other
hardware as an experiment and keep a recovery path for the head unit.

## What the app brings together

- Two Android hosts: a standard Android app and an Android Automotive OS app.
- Wired and Wi-Fi P2P CarPlay session paths.
- MFi authentication through a CH341 I²C bridge, a board-provided I²C device,
  or a configured remote authentication service.
- Vehicle-side audio routing for media, navigation and voice, plus optional
  location reporting to the connected phone.
- A diagnostics screen and session log to make on-device behavior inspectable
  without depending on a shell on the head unit.

The implementation is split into `mobile/` and `automotive/` app targets,
`common/` for their shared interface, and `shared/` for the CarPlay, iAP2,
transport, MFi and media code.

## Set up a head unit

Install the APK that matches the device, pair the iPhone with the head unit over
Bluetooth, and open NEVOPlay settings before starting a CarPlay session. Select
the connection and MFi authentication methods available in your installation,
save the settings, then reconnect. Exact behavior depends on the vehicle image
and attached hardware; a successful build alone does not confirm compatibility.

### MFi credentials

The app can use a certificate and its matching private key selected with the
Android document picker. On head units without a picker, it looks for files
named `mfi.p7b` and `mfi.pk8` in either location:

- `/sdcard/Download/nevoplay/`
- `/sdcard/Android/data/com.edd1e.nevoplay/files/mfi/`

The first location may be restricted by the Android storage policy; the app's
own data directory is hidden from many file managers on Android 11 and later.
Use the vehicle's supported file-transfer or file-management method. The app
stores document access, not copies of picker-selected credentials.

For deployments that need credentials packaged into an APK, Gradle accepts
`nevoPlay.mfi.certificate` and `nevoPlay.mfi.privateKey` file properties (or
the same keys in the local, git-ignored `local.properties`). An APK built this
way contains the private key: limit access to the artifact and never commit or
publish the credentials themselves.

## Build and verify

The project uses JDK 25, Android SDK Platform 37 and Android NDK
`28.2.13676358`. From the repository root, run the verification task for the
target you need:

```bash
./gradlew clean verifyAutomotive
```

```bash
./gradlew clean verifyMobile
```

Each task runs the shared unit tests and module lint checks before assembling
the selected debug APK. The outputs are written under
`automotive/build/outputs/apk/debug/` and `mobile/build/outputs/apk/debug/`.
Run each command separately; `clean` should lead the build invocation.

Minimum Android version: Android 9 (API 28). Wired operation needs compatible
USB Host or board-level I²C hardware; wireless operation depends on the
head unit's Wi-Fi implementation. Hardware testing is required for either
path.

## Diagnostics

Settings → Diagnostics shows the active log location and build identifier. The
usual shared-storage path is:

```text
/sdcard/Download/nevoplay/nevoplay.log
```

If Android does not allow that location, the app falls back to its private
files directory:
`/sdcard/Android/data/com.edd1e.nevoplay/files/logs/nevoplay.log`. Include the
log and the displayed build identifier when reporting a problem; do not include
MFi credentials.

## Project origin and license

NEVOPlay is a vehicle-specific adaptation built on the open-source
[xcertplay project](https://github.com/shilapi/xcertplay). It is not affiliated
with Changan or Apple. Source is distributed under the
[GNU General Public License v3.0](LICENSE); consult the license and source
notices when modifying or redistributing the project.

Parts of the implementation also draw on the [LIVI](https://github.com/f-io/LIVI)
and [showcase](https://github.com/amineross/showcase) projects; see their
respective repositories for their terms and notices.
