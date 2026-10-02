<div align="center">
  <img src="https://raw.githubusercontent.com/shilapi/xcertplay/refs/heads/master/asset/xcertplay_small.png" width="180" height="180" alt="xcertplay icon" />
<h1><strong><font size="6">xcertplay</font></strong></h1>
  <a href="README.md">English</a> | <a href="README.zh-CN.md">中文</a>
  <p>xcertplay 是面向 Android 车机的 CarPlay 接收端项目。支持通过 CH341 I2C 桥接到 MFi 芯片，亦可通过板载 I2C 控制器直连，支持 CarPlay 有线和无线连接。</p>
</div>

> [!WARNING]
> **本仓库是个人 fork，只为一台车机调校，请不要当作正式发布版使用。**
>
> 这里的所有改动都是为了让 CarPlay 在一台特定车机上可用：**长安启源 A07**，启源OS 2.2
> （Android 11），CH341 MFi 桥接，2560×1600 屏幕，无线 CarPlay 走 Wi-Fi P2P。那台车机的行为
> 被写进了代码：它如何强制显示自己的状态栏、如何改变窗口尺寸、如何命名 P2P 与热点网卡、
> 以及它的 MFi 协处理器如何应答（或不应答）。**这些在其他车机上都没有验证过。**
>
> **不了解风险请不要安装。** 它可能让你的车机没有画面、连不上，甚至需要重装才能恢复，
> 而本项目没有任何支持渠道。也不要去关闭你不理解的保护：MFi 认证、凭据守卫、状态栏处理。
>
> 如果你是想给自己的车找 CarPlay 接收端，请关注上游项目：
> **<https://github.com/shilapi/xcertplay>**。这个 fork 只是为了让作者能在一台车上迭代，
> 并不是它的替代品。本 fork 的协作约定见 [AGENTS.md](AGENTS.md)。

## Features

- 面向 Android 和 Android Automotive OS 的 CarPlay 主机应用。
- 支持 CH341 桥接 MFI 芯片、原生 `/dev/i2c-N` 设备连接的 MFI 芯片、本地证书/私钥文件和 Remote MFI 认证（API 见下）。
- 支持 CarPlay 有线或无线连接。
- 支持触发 CarPlay Ultra （未测试/未完成的协议栈，但是确实可以在 iPhone 上触发 CarPlay Ultra 的提示）。
- 支持语音、导航、音乐多通道音频输出并 mapping 至 Android 的对应通道。
- 支持动态 Activity resize ，并自动重新握手至新的分辨率。
- 支持车机位置回传。
- 支持 Android 9 (API 28) 。

## 使用方法

1. 通过蓝牙将 iPhone 与车机配对。
2. CarPlay 视频流尚未启动时，点击左下角的设置按钮；在 CarPlay 画面上用三指连续点两下也可以打开设置页面。
3. 确认所有设置均已按需配置。
   如需启用另一种手势，打开 `More gestures to Settings page`：单指从屏幕左侧 1/8 区域的上 1/4 开始，沿左侧下滑，在下 1/4 区域抬起。
4. 滑动到底部，选择 `Save & Reconnect`。
5. 按照你选择的方式连接 MFi 芯片。
6. 等待连接完成，然后开始使用。

## 当前进度

他运转👍，已在车机/手机平台测试，如果出现部分车机不适配的情况欢迎 issue （并附上你的 log ，位于 `/sdcard/Download/xcertplay/xcertplay.log`，设置页会显示确切路径；Android 9 上会退回 `/sdcard/Android/data/com.shilapi.xcertplay/files/logs/xcertplay.log`）

转接板：[CH341-to-MFI](https://github.com/shilapi/ch341-to-mfi-chip)

## 本地 MFI 文件

在 `MFI certificate & signing target` 中选择 `Local files`，然后通过两个 `Choose`
按钮使用 Android 系统文件选择器选择证书和私钥。当前支持 DER PKCS#7 证书（`.p7b`）
及与之匹配的、未加密 DER PKCS#8 私钥（`.pk8`）。应用会在开始连接手机前校验两者是否
匹配，并在 MFI 重连时重新读取文件。

建议把私钥放在受保护的位置。应用不会把证书或私钥复制到偏好设置，只会保存 Android
授予的持久读取权限和文档 URI。

### 没有文件选择器的车机

不少车机根本没有文档选择器，`Choose` 无从发起。此时应用会从固定目录读取这一对文件，
文件名必须正好是 `mfi.p7b` 和 `mfi.pk8`：

| 目录 | 能否读取 |
| --- | --- |
| `/sdcard/Download/xcertplay/` | 与会话日志同目录，文件管理器可见。但 Android 只对持有存储权限的应用开放他人写入的文档，较新版本可能读不到。 |
| `/sdcard/Android/data/com.shilapi.xcertplay/files/mfi/` | 应用专属目录：不需要任何权限，各版本 Android 都能读；Android 11+ 对文件管理器隐藏。 |

通过选择器选中的两个文档优先于固定目录；固定目录内部，`Download/xcertplay` 先于应用
专属目录。`Local files` 设置项会显示实际将读取的文件，推完文件可点 `Refresh files`
重新检查。

```bash
adb push mfi.p7b /sdcard/Download/xcertplay/
adb push mfi.pk8 /sdcard/Download/xcertplay/
```

### 把证书编译进 APK

若车机既没有选择器、两个 `/sdcard` 目录又都读不到，可以把证书直接编进 APK。构建只在
命令行或 gitignore 的 `local.properties` 里拿到两个路径，仓库本身不引用任何证书材料：

```bash
./gradlew :automotive:assembleDebug \
  -Pxcertplay.mfi.certificate=/绝对路径/certificate.p7b \
  -Pxcertplay.mfi.privateKey=/绝对路径/identity.pk8
```

两个文件会被拷成 `assets/mfi/mfi.p7b` 与 `assets/mfi/mfi.pk8`，并且优先于两个目录被读取。
不带这两个参数的构建完全不受影响、不含任何证书。这样的 APK 里带着私钥，拿到它的人就拿到了
私钥，不要外传。

## 工程结构

| 路径 | 用途 |
| --- | --- |
| `common/` | 两个目标共用的 CarPlay 宿主界面、设置、持久化和应用资源。 |
| `mobile/` | 使用共享 CarPlay 主机界面的 Android 应用。 |
| `automotive/` | 使用共享主机界面并支持高级音频通道映射的 Android Automotive OS 应用。 |
| `shared/` | Car App Library 代码，以及 CH341、I2C、MFi、iPhone、iAP2、NCM、VPN、AirPlay 和媒体实现。 |

## Remote MFI 功能

Remote MFi 客户端把远程服务当作一块 MFi 芯片远程调用，抑或是采用 BAA 认证，通过远程进行认证免去了本地连接 MFI 芯片进行认证的流程。

### 端点

| Method | Path | 用途 | Request body | Success response | 失败 response |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/mfi/certificate` | 获取 MFI 芯片版本、证书类型和证书内容，客户端首次调用后缓存 | 无 | 证书 JSON | `{"detail":"..."}` |
| `POST` | `/mfi/sign` | 对 challenge 签名 | `{"challenge":"...","requestId":"..."}` | `{"signature":"..."}` | `{"detail":"..."}` |
| `POST` | `/mfi/reset` | 请求重置远程 MFI 芯片 | `{}` | `{"detail":""}` | `{"detail":"..."}` |

（可选）采用标准 Bearer Authentication 进行验证。

**当前仅测试了 BAA Authentication**

## 环境要求

- 启动 Gradle 需要 JDK 17 或更高版本；daemon 通过 Gradle toolchain 解析 Java 25。
- Android SDK Platform 37。
- Android 9（API 28）或更高版本。
  在 Android 9 上不可用 Wi-Fi P2P 5 GHz 模式，应用会改用 LocalOnlyHotspot。
- Android NDK `28.2.13676358`。
- 硬件验证需要支持 USB Host/OTG 的 Android 设备以及 MFi 硬件。

## 构建

在 Windows PowerShell 中：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :shared:testDebugUnitTest :common:lintDebug :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug
```

在 macOS 或 Linux 中：

```bash
./gradlew :shared:testDebugUnitTest :common:lintDebug :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug
```

构建未签名 release APK：

```powershell
.\gradlew.bat :mobile:assembleRelease :automotive:assembleRelease
```

## 致谢

感谢 [LIVI](https://github.com/f-io/LIVI) 项目为本项目提供了重要参考。
感谢 [showcase](https://github.com/amineross/showcase) 项目为本项目的 BAA 认证提供重要参考。

## 许可证

本项目采用 [GNU General Public License v3.0](LICENSE) 许可。
