<div align="center">
  <img src="shared/src/main/res/drawable-nodpi/nevoplay_launcher.png" width="160" height="160" alt="NEVOPlay 应用图标" />
  <h1>NEVOPlay</h1>
  <p><strong>为长安启源 A07 打造的车载 CarPlay 接收端。</strong></p>
  <p><a href="README.md">English</a> · <a href="README.zh-CN.md">简体中文</a></p>
</div>

NEVOPlay 让 iPhone 将 CarPlay 投送到基于 Android 的车机屏幕上。项目的
重点是适配启源 A07 的车机系统与连接硬件。它由爱好者维护，**不是长安发布或
提供支持的软件，也不代表长安或 Apple。**

## 当前适配目标

开发参考设备为启源 A07，运行启源OS 2.2（Android 11），屏幕分辨率
2560 × 1600，使用 CH341 MFi 桥接，并通过 Wi-Fi P2P 建立无线会话。其他年款、
配置、系统版本和转接板尚未确认兼容。若在不同硬件上尝试，请把它视为实验，
并提前准备好恢复车机的办法。

## 项目能力

- 提供标准 Android 与 Android Automotive OS 两种应用目标。
- 支持有线及 Wi-Fi P2P CarPlay 连接路径。
- MFi 认证可使用 CH341 I²C 桥、车机板载 I²C 设备，或配置好的远程认证服务。
- 支持车机侧媒体、导航和语音音频路由，并可向手机回传位置信息。
- 提供诊断页面和会话日志，尽量通过应用自身观测车机状态，不依赖车机 shell。

代码分为四个主要模块：`mobile/` 和 `automotive/` 是两种应用入口；
`common/` 放置共用界面；`shared/` 包含 CarPlay、iAP2、连接传输、MFi 与媒体实现。

## 在车机上使用

安装与设备类型对应的 APK，先通过蓝牙配对 iPhone 和车机，再在 CarPlay 会话
启动前打开 NEVOPlay 设置。根据车上的连接硬件选择传输方式和 MFi 认证方式，
保存后重新连接。实际效果取决于车机系统和外接硬件；**能够成功编译不等于已在
你的设备上验证可用。**

### MFi 证书与私钥

可以通过 Android 文件选择器分别选择证书和对应的私钥。没有文件选择器的车机，
应用会在以下位置查找文件名固定为 `mfi.p7b` 和 `mfi.pk8` 的一对文件：

- `/sdcard/Download/nevoplay/`
- `/sdcard/Android/data/com.edd1e.nevoplay/files/mfi/`

Android 存储策略可能限制第一个目录；Android 11 及更新版本通常也不会在文件
管理器中显示应用专属目录。请使用车机支持的文件管理或文件传输方式。选择器
选中的文件不会被复制进应用偏好设置，应用只保留读取授权。

如部署环境必须把凭据打进 APK，可在 Gradle 中设置
`nevoPlay.mfi.certificate` 与 `nevoPlay.mfi.privateKey` 文件属性，也可以把这两个
属性放进本地且已被 Git 忽略的 `local.properties`。这样构建出的 APK **包含私钥**，
应限制 APK 的访问范围；不要把凭据提交或公开发布。

## 构建与验证

构建环境使用 JDK 25、Android SDK Platform 37 和 Android NDK
`28.2.13676358`。在项目根目录选择需要的目标运行验证：

```bash
./gradlew clean verifyAutomotive
```

```bash
./gradlew clean verifyMobile
```

每个验证任务都会运行共享模块单元测试和各模块 lint，再构建所选目标的 debug APK。
产物位于 `automotive/build/outputs/apk/debug/` 或
`mobile/build/outputs/apk/debug/`。两条命令请分开执行，并让 `clean` 位于本次
Gradle 调用的开头。

最低系统版本为 Android 9（API 28）。有线连接需要兼容的 USB Host 或车机板载
I²C 硬件；无线连接依赖车机的 Wi-Fi 实现。两种方式都需要实际硬件验证。

## 故障信息

在“设置 → 诊断”中可以查看当前日志路径和构建标识。通常日志写入共享存储：

```text
/sdcard/Download/nevoplay/nevoplay.log
```

如果 Android 不允许写入该位置，应用会退回到私有路径
`/sdcard/Android/data/com.edd1e.nevoplay/files/logs/nevoplay.log`。反馈问题时，
请附上日志和页面显示的构建标识；不要附上 MFi 证书、私钥或包含这些材料的 APK。

## 项目来源与许可

NEVOPlay 是针对启源 A07 的适配项目，技术基础来自开源
[xcertplay](https://github.com/shilapi/xcertplay)。本项目与长安、Apple 均无关联。
源码按 [GNU 通用公共许可证第 3 版](LICENSE) 发布；修改或再分发时请遵守许可证
及源文件中的声明。

部分实现也参考了 [LIVI](https://github.com/f-io/LIVI) 和
[showcase](https://github.com/amineross/showcase)，相关许可和声明请查看各自项目。
