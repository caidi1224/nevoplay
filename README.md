<div align="center">
  <img src="https://raw.githubusercontent.com/shilapi/xcertplay/refs/heads/master/asset/xcertplay_small.png" width="180" height="180" alt="xcertplay icon" />
<h1><strong><font size="6">xcertplay</font></strong></h1>
  <a href="README.md">English</a> | <a href="README.zh-CN.md">中文</a>
  <p>An Android head-unit CarPlay receiver. It supports connecting to the MFi chip through a CH341 I2C bridge or directly through the board's I2C controller, and supports both wired and wireless CarPlay connections.</p>
</div>

> [!WARNING]
> **This repository is a personal fork, tuned for one car. Do not treat it as a release.**
>
> Everything here was changed to make CarPlay work on a single head unit — a **Changan Qiyuan A07**
> running 启源OS 2.2 (Android 11), with a CH341 MFi bridge, a 2560×1600 panel and Wi-Fi P2P for
> wireless CarPlay. That unit's behaviour is baked into the code: how it forces its own status bar,
> how it resizes the window, how it names its P2P and hotspot interfaces, and how its MFi
> coprocessor answers (or does not). None of it is verified anywhere else.
>
> **Do not install this unless you understand the risk.** It can leave you with a head unit that
> shows no picture, will not connect, or needs a reinstall to recover, and there is no support
> channel for it. Do not disable protections you do not understand — MFi authentication, the
> credential guard, or the system-bar handling.
>
> If you want a CarPlay receiver for your own car, use the upstream project instead:
> **<https://github.com/shilapi/xcertplay>**. This fork exists so its owner can iterate on one
> vehicle; it is not a replacement for it. See [AGENTS.md](AGENTS.md) for how this fork is run.

## Features

- CarPlay host applications for Android and Android Automotive OS.
- Support for MFI chips connected through a CH341 bridge or native
  `/dev/i2c-N` devices, local certificate/private-key files, and Remote MFI
  authentication (see the API below).
- Wired and wireless CarPlay connections.
- CarPlay Ultra triggering (the protocol stack is untested/incomplete, but it
  can trigger the CarPlay Ultra prompt on an iPhone).
- Voice, navigation, and music multi-channel audio output mapped to the
  corresponding Android channels.
- Dynamic Activity resizing with automatic re-handshaking to the new
  resolution.
- Vehicle head-unit location reporting.
- Android 9 (API 28) support.

## Usage

1. Pair your iPhone with the head unit via Bluetooth.
2. Before a CarPlay video stream starts, tap the Settings button in the lower-left corner. You can also tap twice in a row with three fingers to open Settings on the CarPlay screen.
3. Make sure all the settings are configured as desired.
   To enable another entry gesture, turn on `More gestures to Settings page`. Start with one finger in the upper quarter of the left eighth of the screen, slide down along that strip, and lift in the lower quarter.
4. Scroll to the bottom and select `Save & Reconnect`.
5. Connect your MFi chip using the method you selected.
6. Wait for the connection to complete, then enjoy.

## Current progress

It works 👍. It has been tested on car head units and phones. If you encounter
an incompatible car head unit, please open an issue and attach your log from
`/sdcard/Download/xcertplay/xcertplay.log` (Settings shows the exact path; on
Android 9 it falls back to
`/sdcard/Android/data/com.shilapi.xcertplay/files/logs/xcertplay.log`).

Adapter board: [CH341-to-MFI](https://github.com/shilapi/ch341-to-mfi-chip)

## Local MFI files

Choose `Local files` under `MFI certificate & signing target`, then use the two
`Choose` buttons to select the certificate and private key with Android's system
document picker. The supported formats are a DER PKCS#7 certificate (`.p7b`)
and its matching, unencrypted DER PKCS#8 private key (`.pk8`). The app validates
that the files match before starting the phone connection and reloads them on
MFI reconnect.

Store the private key in a protected location. Neither file is copied into app
preferences; only Android's persistent read permission and document URI are
saved.

## Project structure

| Path | Purpose |
| --- | --- |
| `common/` | Shared CarPlay host activity, settings UI, persistence, and app resources used by both targets. |
| `mobile/` | Standard Android target using the shared CarPlay host UI. |
| `automotive/` | Android Automotive OS target with the shared host UI and advanced audio channel mapping. |
| `shared/` | Car App Library code plus the CH341, I2C, MFi, iPhone, iAP2, NCM, VPN, AirPlay, and media implementations. |

## Remote MFI

The Remote MFi client treats a remote service as an MFi chip for remote calls,
or uses BAA authentication. Remote authentication avoids the process of
connecting to a local MFi chip for authentication.

### Endpoints

| Method | Path | Purpose | Request body | Success response | Failure response |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/mfi/certificate` | Get the MFi chip version, certificate type, and certificate contents; cached by the client after the first call | None | Certificate JSON | `{"detail":"..."}` |
| `POST` | `/mfi/sign` | Sign the challenge | `{"challenge":"...","requestId":"..."}` | `{"signature":"..."}` | `{"detail":"..."}` |
| `POST` | `/mfi/reset` | Request a reset of the remote MFi chip | `{}` | `{"detail":""}` | `{"detail":"..."}` |

(Optional) Standard Bearer Authentication can be used for verification.

**Currently, only BAA Authentication has been tested.**

## Requirements

- JDK 17 or newer to launch Gradle. The daemon resolves Java 25 through the
  Gradle toolchain.
- Android SDK Platform 37.
- Android 9 (API 28) or newer.
  On Android 9, Wi-Fi P2P 5 GHz mode is unavailable and LocalOnlyHotspot is used instead.
- Android NDK `28.2.13676358`.
- A physical USB Host/OTG Android device and MFi hardware are required for
  hardware validation.

## Build

On Windows PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :shared:testDebugUnitTest :common:lintDebug :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug
```

On macOS or Linux:

```bash
./gradlew :shared:testDebugUnitTest :common:lintDebug :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug
```

Unsigned release APKs:

```powershell
.\gradlew.bat :mobile:assembleRelease :automotive:assembleRelease
```

## Acknowledgements

Thanks to [LIVI](https://github.com/f-io/LIVI) for providing important
reference for this project.
Thanks to the [showcase](https://github.com/amineross/showcase) project for
providing important reference for the BAA authentication in this project.

## License

Licensed under the [GNU General Public License v3.0](LICENSE).
