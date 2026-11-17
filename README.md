# TV Live

从 OneTV 桌面应用中剥离出来的独立电视直播 APK。面向 Android TV / 电视盒子，内置央视频道（含加密流解密）、自定义 m3u 节目源、频道收藏、清晰度切换、数字选台、线路记忆，并新增信号源自动更新。

> 本仓库为私有仓库。源码工程完整可编译，同时提供三个已签名的安装包。

## 目录结构

| 目录 | 内容 |
|---|---|
| `source/` | 完整可编译的 Android 源码工程（Gradle 项目） |
| `releases/` | 三个安装包：arm64-v8a / armeabi-v7a / 通用版 |
| `docs/` | 产品需求、架构设计、QA 报告、安装说明 |
| `site/` | 软件介绍 / 下载页 `index.html` |

## 安装包

| 文件 | 适用设备 | 大小 |
|---|---|---|
| `releases/TV_Live_arm64-v8a.apk` | 64 位设备（大多数近 5 年的电视/盒子/投影） | 13.70 MB |
| `releases/TV_Live_armeabi-v7a.apk` | 32 位老设备 | 14.35 MB |
| `releases/TV_Live_通用版.apk` | 不确定架构时用这个，含两套原生库 | 17.59 MB |

三个包功能完全一致，仅架构不同。安装到电视：把 APK 拷到 U 盘用设备文件管理器打开，或 `adb install`，首次需允许「安装未知来源应用」。应用包名 `com.onetv.live`，可与原 OneTV 桌面共存。详见 [docs/安装说明.md](docs/安装说明.md)。

## 功能

- **内置央视频道**：随应用自带，含加密流解密，内置备用线路兜底。
- **自定义节目源**：导入 m3u、添加直播源或订阅 URL。
- **频道收藏 / 清晰度切换 / 数字选台**：遥控器友好，大字体高对比。
- **线路记忆与恢复**：记住成功线路，失效时自动清缓存、退避重连。
- **信号源自动更新**：订阅源默认每 6 小时后台刷新，不打断当前播放；可选在线频道表；留空地址时不产生网络请求。

## 自行编译

工程用 Gradle + Android Gradle Plugin，源码包 `com.tvbridge.app`，`applicationId` 为 `com.onetv.live`。

```bash
cd source
./gradlew assembleDebug
# 产物在 source/app/build/outputs/apk/debug/
```

要求 JDK 17、Android SDK platform 35 / build-tools 35、Gradle 8.9（含 wrapper）。需在 `source/local.properties` 配置 `sdk.dir`。

**重要**：JNI 包名 `com.bu.cc.tv` 与源码包 `com.tvbridge.app` 不可更改——原生库 `libyangshipin.so` / `libh5estream.so` 与 JNI 静态符号绑定，改名会导致加密频道失效。

## 注意事项

- 央视频道依赖官方私有接口与内置原生库的签名算法；若官方变更接口或签名，内置频道可能失效，需更新原生库才能修复。**自定义 m3u 源不受影响**。
- 原生库仅提供 arm64-v8a 与 armeabi-v7a，x86 设备 / 模拟器无法播放加密频道。
- 安装包为 debug 签名版，可直接安装；上架应用商店需另做 release 签名。

## 文档

- [安装说明](docs/安装说明.md)
- [产品需求 — 自动更新](docs/prd-autoupdate.md)
- [架构设计 — 初始剥离](docs/architecture-design.md)
- [架构设计 — 自动更新](docs/architecture-autoupdate.md)
- [QA 报告 — 初始版本](docs/qa-report.md)
- [QA 报告 — 自动更新](docs/qa-report-autoupdate.md)
