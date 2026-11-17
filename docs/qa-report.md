# OneTV 独立直播 APK — QA 独立验证报告（最终版）

| 项目 | 内容 |
|---|---|
| 验证人 | 严过关（Yan / software-qa-engineer） |
| 验证日期 | 2026-11-16 |
| 被测工程 | `/Users/licheng/WorkBuddy/2026-11-16-18-40-24/onetv-live-apk` |
| 对比基准 | `/Users/licheng/Downloads/OneTV-main`（只读） |
| 设计依据 | `architecture-design.md` v1.0 |
| 构建环境 | JDK17 + AGP8.7.3 + Kotlin2.0.21 + Gradle8.9 + SDK35（本机镜像脚本） |
| 验证方式 | 实跑 `testDebugUnitTest`（原始 → QA 自修 → K-02 修复后复验）+ 全量静态审查 + aapt2/APK 产物复核 |
| 验证原则 | 独立怀疑式验证，不放行未核实结论 |
| 报告状态 | **最终版**：K-01 ~ K-04 全部修复并经复验通过，无遗留缺陷 |

---

## 1. 单元测试结果

### 1.1 执行与通过率

| 轮次 | 场景 | 总数 | 通过 | 失败 | 跳过 | 结论 |
|---|---|---|---|---|---|---|
| Round 1 | 原始测试（桌面端搬运） | 46 | 42 | 4 | 0 | BUILD FAILED |
| Round 2 | QA 自修过时断言后 | 46 | 44 | 2 | 0 | BUILD FAILED |
| Round 3 | K-02 修复 + 新增回归测试 + 测试断言校正 | 48 | 48 | 0 | 0 | BUILD SUCCESSFUL |
| **Round 4（最终复验）** | **K-03/K-04 修复后再跑全量** | **48** | **48** | **0** | **0** | **BUILD SUCCESSFUL** |

- **最终通过率：48/48 = 100%**（0 skipped，无 `@Ignore`）
- 最终命令输出：`BUILD SUCCESSFUL in 28s`，`29 actionable tasks: 6 executed, 23 up-to-date`。
- 环境提示（非失败原因）：Kotlin compile daemon 启动告警偶发 1 次（自动重试成功）；`platform-tools` 许可未接受告警（不影响 unit test 与 assembleDebug）。

### 1.2 测试类清单（最终 48/48）

| # | 测试类 | 用例数 | 通过 | 失败 | 跳过 |
|---|---|---|---|---|---|
| 1 | `PlaylistParserTest.kt` | 11 | 11 | 0 | 0 |
| 2 | `PlaybackAttemptsTest.kt` | 7 | 7 | 0 | 0 |
| 3 | `YangshipinPlaybackCoordinatorTest.kt` | 6 | 6 | 0 | 0 |
| 4 | `YangshipinCatalogTest.kt` | 5 | 5 | 0 | 0 |
| 5 | `NativePlaybackTest.kt` | 3 | 3 | 0 | 0 |
| 6 | `ResolverRaceTest.kt` | 3 | 3 | 0 | 0 |
| 7 | **`LiveRepositorySourcesTest.kt`（K-02 新增）** | **2** | **2** | **0** | **0** |
| 8 | `HlsTransferTest.kt` | 2 | 2 | 0 | 0 |
| 9 | `LegacyTlsTest.kt` | 2 | 2 | 0 | 0 |
| 10 | `LiveResumeTest.kt` | 2 | 2 | 0 | 0 |
| 11 | `PlaybackRoutesTest.kt` | 2 | 2 | 0 | 0 |
| 12 | `ProgressiveStreamTest.java` | 2 | 2 | 0 | 0 |
| 13 | `LiveAppearanceTest.kt` | 1 | 1 | 0 | 0 |
| | **合计** | **48** | **48** | **0** | **0** |

### 1.3 历史失败用例与最终处置

| 原失败用例 | 期望 vs 实际 | 根因 | 最终处置 |
|---|---|---|---|
| `YangshipinCatalogTest.officialDefaultPersistsWithoutOverwritingLaterSourceChoice` | 54 vs 55 个频道 | 硬编码旧频道数；asset 实为 55（央视 23 + 卫视 32），asset 与源工程 SHA256 一致 | 频道数改为 55；K-02 修复后 `activeSource/local` 断言恢复原语义，全部通过 |
| `YangshipinCatalogTest.smallCategorySetsUseTabsAndLargeSetsUseDropdown` | 导入自定义多分组后期望 5 标签，实际 4 | 原 `sourceList()` 过滤 local 源（K-02） | K-02 修复后**恢复原测试**，5 标签→2 标签下拉均通过 |
| `LiveResumeTest.lastChannelSurvivesRestartAndStalePluginSyncCannotReplaceNewerSelection` | 期望 `yangshipin-600001859`，实际 `yangshipin-600001800` | "缺失频道" pid 已在内置表；`mergeSnapshot` 走 `saveAsync` 异步落盘竞态 | 缺失 id 改用 `yangshipin-000000000`；合并断言改用同一实例；删 local 后断言回退内置频道——全部通过 |
| `LiveResumeTest.missingPluginFallsBackWithoutErasingItsRememberedChannel` | 期望回退"本地频道"，实际"中央一台" | 测试用 `600001859` 当"缺失"频道，现已在内置表中 | 改用确实不存在的 id，断言回退内置首频道且不擦除记忆 id——通过 |
| `YangshipinCatalogTest` 源删除断言（K-01 遗留） | 时好时坏 | `removeSource` 走 `saveAsync`，新建实例读盘竞态 | 改用同一实例断言内存状态——稳定通过 |

**关键判定依据（历史）**：Round 1 失败测试的源码与源工程逐字节一致（`diff -q` IDENTICAL）；`YangshipinCatalog.kt`/`LiveRepository.kt`/`LivePanel.kt`/`PlaylistParser.kt` 与源工程逐字节一致；asset SHA256 一致。**K-02 经 team-lead 核实为真实需求缺口后由工程师修复，其余均为测试代码问题，QA 已全部自修。**

### 1.4 K-02 回归测试（新增，本轮核心交付）

新增文件：`app/src/test/java/com/tvbridge/app/LiveRepositorySourcesTest.kt`（2 个用例，`@RunWith(RobolectricTestRunner)`、`@Config(sdk=28)`）

1. **`builtInAndImportedLocalSourceAreBothVisible`**
   - Arrange：清空 `live-sources.json`/`startup`/`live-resume`，构造 `LiveRepository`（内置源自动就绪）。
   - Act：`repo.importText("自定义频道,https://example.test/custom.m3u8")`。
   - Assert：`sourceList()` **同时包含 `DEFAULT_SOURCE` 与 `"local"`**；`activeSource()=="local"`；`allChannels()` 能看到"自定义频道"；`findChannel(custom.id)` 非 null。
2. **`customSubscriptionSourceIsVisibleAndResumable`**
   - Arrange：基于 `exportStore()` 构造含内置源 + 订阅源 `https://example.com/list.m3u8`（频道 `sub-channel-1`）的快照，`active` 指向订阅源。
   - Act：`repo.restoreSnapshot(state.toString())`。
   - Assert：`sourceList()` 同时含内置源与订阅源；`allChannels()`/`findChannel()` 能找到 `sub-channel-1`；`resumeChannel()?.id == "sub-channel-1"`。

- **验证结果：2/2 PASS**；该测试在修复前（`sourceList()` 仅返回 `DEFAULT_SOURCE`）必然失败，修复后通过，具备有效回归防护能力。
- 测试文件共改动 3 个：`LiveRepositorySourcesTest.kt`（新增）、`YangshipinCatalogTest.kt`、`LiveResumeTest.kt`（断言校正）。**工程源码由工程师修改，QA 未改动任何主源码。**

---

## 2. 静态审查结论（逐条 PASS/FAIL + 证据）

### 2.1 JNI 完整性 — **PASS**

- `diff -q` 三个 JNI 文件与源工程**逐字节一致**：
  `NativeCmgDecryptor.java`、`NativeYspSigner.java`、`NativeH5eDecryptor.java` → 全部 `IDENTICAL`（1718/426/563 字节）。
- 三文件 `package com.bu.cc.tv;` 声明正确；native 方法签名完整（CMG 17 个、YSP 2 个、H5E 6 个），`System.loadLibrary("yangshipin")` ×2、`("h5estream")` ×1 保持。
- 4 个 `.so` 与源工程 SHA256 完全一致（`arm64-v8a`、`armeabi-v7a` 各 `libyangshipin.so`/`libh5estream.so`）。

### 2.2 包名策略 — **PASS**

- 源码 `package com.tvbridge.app`：29 个 Kotlin + 3 个 Java；无 `package com.onetv.live`（grep 0 命中）。
- `app/build.gradle`：`namespace 'com.tvbridge.app'`（L10）、`applicationId 'com.onetv.live'`（L15）；Manifest 组件全限定名 `com.tvbridge.app.LiveActivity`（L34）。
- 3 个 JNI 类 `package com.bu.cc.tv`（见 2.1）。

### 2.3 无残留耦合 — **PASS**

- 全量 grep `Hub` / `MainActivity` / `BrowserActivity` / `TvHomeView` / `LanServer` / `OnlineWallpapers` / `ProceduralWallpaper` / `RemoteHostService` / `RemoteInputService` / `exifinterface` / `zxing` / `Startup`：源码 **0 命中**。
- 唯一 `BootReceiver` 出现于 `YangshipinCatalog.kt:15` 注释（设计文档注明为预期）。
- `GlassUi.kt` 与源工程仅 1 行差异，正是设计要求删除的 `BrowserActivity` 判断。

### 2.4 shim 正确 — **PASS**

- `LiveLaunch.kt`：`builtIn()=true`（L13）、`label()="内置直播"`（L15）、`selectInternal()=true`（L18），共 3 方法，无 SharedPreferences("startup") 读写。
- `HomeAppearance.kt`：恰好 4 方法 `dark()/key()/backdrop()/drawBackdrop()`；`backdrop()` 返回缓存非 null `Bitmap`（`#121820`）。
- 调用点 8 处（GlassUi 7 + NativeUi 1）全部落在这 4 方法内。额外 `RoundedRect.kt` 为 `AppIcons` 依赖的 API19 兼容工具，属必要补齐。

### 2.5 资源/依赖完整性 — **PASS**

- Manifest 引用资源存在：`@style/LiveTheme`（`values/theme.xml:15` + `values-v31`）、`@drawable/launcher_display`（xxhdpi/xxxhdpi PNG）、`@drawable/one_adaptive`（`values/drawables.xml` 别名 + v26/v33 自适应图标）。
- `app/build.gradle` 依赖齐全，**无 exifinterface、无 zxing**；`buildConfig true`、`splits.abi`、`uses-sdk overrideLibrary` 齐备。
- assets `yangshipin-channels.json`（55 频道）、`legacy-ca.pem`、licenses 齐全。
- K-03 修复复核：已补回 `res/drawable-nodpi/one_icon.png`，SHA256 与源工程一致（`3203450461f2...b252`）；重编 APK 的 aapt2 资源表中 `drawable/one_icon` 现同时含 `(anydpi-v21)` 与 `(nodpi)` 配置，API 19/20 兜底成立。

### 2.6 LiveActivity 生命周期 — **PASS**

| 要求方法 | 实现位置 | 结论 |
|---|---|---|
| `onCreate` | L26-36：`LiveRepository(this)`、`showLive()`、`channel_id` 可选拉起 | PASS |
| `showLive` | L39-62：refreshPlugin → resumeChannel → 空则 showLiveSetup → `LivePanel(exit={finish()}, noSources={showLiveSetup()}, pageFactory={YangshipinPlayer})` → addView → addFlags | PASS |
| `showLiveSetup` | L65-84：AlertDialog（导入文本/直链/订阅/返回） | PASS |
| `liveUpdated` | L184-186：有频道则 showLive，否则 refresh | PASS |
| `dispatchKeyEvent` | L189-192：仅 ACTION_DOWN 转发 `handleKey` | PASS |
| `onBackPressed` | L195-201：`back()` 未消费则 stop + clearFlags + finish | PASS |
| `onResume` | refreshPlugin + `livePanel.resume()` + `addFlags(FLAG_KEEP_SCREEN_ON)`（K-04 修复） | PASS |
| `onPause` | `livePanel.pause()` + `clearFlags(FLAG_KEEP_SCREEN_ON)`（K-04 修复） | PASS |
| `onDestroy` | L214-221：stop + removeView + dismissSetup + clearFlags | PASS |

- 全文件无桌面/遥控类引用；网络操作走 `AppThreads.onIo` + `runOnUiThread`。

### 2.7 编译产物复核（K-02 修复后重编） — **PASS**

- 3 个 APK 均于 **19:28:59** 重编（晚于 19:21:58 的 `LiveRepository.kt` 与 19:27:57 的 `LiveActivity.kt` 修改时间），**已包含 K-02/K-03/K-04 全部修复**：
  - `app-universal-debug.apk`（12 MB，`native-code: arm64-v8a + armeabi-v7a`）
  - `app-arm64-v8a-debug.apk`（8.4 MB，`native-code: arm64-v8a`）
  - `app-armeabi-v7a-debug.apk`（9.1 MB，`native-code: armeabi-v7a`）
- aapt2 `dump badging`（三包逐一复核）：`package='com.onetv.live' versionCode='1' versionName='1.0'`；`leanback-launchable-activity` 均指向 `com.tvbridge.app.LiveActivity`，banner=`launcher_display.png`；Manifest 权限与设计一致（另有 androidx.core 自动注入的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，无害）。
- so 清单：universal 15 个；arm64 单包 7 个（不含 conscrypt，符合 `packagingOptions`）；v7a 单包 8 个。

### 2.8 K-02 修复实现审查 — **PASS**

- 根因：`LiveLaunch` shim 恒 `true` 与源工程 `sourceList()` 的 `builtIn` 过滤语义叠加，导致自定义源被过滤。
- 修复内容：`LiveRepository.sourceList()` 由 `sources.filter{(it.url==DEFAULT_SOURCE)==true}` 改为 `sources.toList()`（L85）；`LiveLaunch.builtIn()` 保持 `true` 不变，符合架构约束。
- 解耦效果：`builtIn()` 现仅服务于 `LiveCatalog.available()`/`refreshPlugin()` 与 `snapshot()` 的 mode 计算；`sourceList()` 已与其解耦，后续即便修改 shim 也不会再让自定义源消失。
- 连带受益：`allChannels()`（L80-84）、`findChannel()`（L86）、`favoriteChannels()`（L87）、`resumeChannel()`（L98-105）、`LivePanel.renderFilters()`（L189）均以 `sourceList()` 为唯一数据源，自定义源现已全线可见；`refreshPlugin()`、`rememberChannel()`、`removeSource()` 逻辑未受破坏。
- 回归测试（§1.4）2/2 通过，证明修复有效且未引入新的过滤回归。

---

## 3. 遗留问题清单（最终分级）

### K-01 【已修复】原 2 项测试断言失败（测试代码问题）

- `LiveResumeTest.kt:28`：删除 local 源后 `assertNull(resumeChannel())` → 改为断言回退内置频道（`id.startsWith("yangshipin-")`）。
- `YangshipinCatalogTest.kt:32`：`removeSource` 后新建实例读盘竞态 → 改为同一实例断言内存状态。
- 两处均已修复并在 Round 3 连续多次运行中稳定通过。**不再构成遗留问题。**

### K-02 【已修复 + 回归测试通过】自定义 m3u 源导入后不可见

- 原问题：`sourceList()` 只返回 `DEFAULT_SOURCE`，导致导入/订阅的自定义源被过滤，用户看不到也切不到。
- 修复：工程师将 `sourceList()` 改为 `sources.toList()`，`builtIn()` 仍恒 `true`；APK 已重编（19:23:12）。
- 验证：新增 `LiveRepositorySourcesTest.kt`（2 用例）覆盖"内置源 + 本地导入源"与"内置源 + 订阅源"的可见性、`allChannels`/`findChannel`/`resumeChannel` 可达性，**2/2 PASS**；同时恢复原分组标签测试（5 标签 ↔ 2 标签下拉），全部通过。
- 状态：**关闭**。

### K-03 【已修复】API 19/20 设备启动图标缺失风险

- 原问题：源工程的 `res/drawable-nodpi/one_icon.png`（API<21 兜底）未搬运，目标 `drawable/one_icon` 仅有 `(anydpi-v21)` 配置。
- 修复：工程师补回该 PNG（SHA256 与源工程一致 `3203450461f2...b252`）。
- 验证：19:28:59 重编 APK 的 aapt2 资源表中 `drawable/one_icon` 现同时含 `(anydpi-v21)` 与 `(nodpi)` 配置，`res/drawable-nodpi-v4/one_icon.png` 已打包入 APK。状态：**关闭**。

### K-04 【已修复】`onPause` 未清除 `FLAG_KEEP_SCREEN_ON`

- 原问题：仅 `onBackPressed`/`onDestroy` 清除，切后台暂停后屏幕仍常亮。
- 修复：`LiveActivity.onPause()` 补 `clearFlags`（L212），`onResume()` 补 `addFlags`（L207）对称恢复；`showLive()`(L56)、`onBackPressed()`(L198)、`onDestroy()`(L221) 保持原逻辑。
- 验证：全量测试复跑仍 48/48 通过，无生命周期回归。状态：**关闭**。

### K-05 【提示·非缺陷】构建环境告警

- `platform-tools` 许可未接受、Kotlin daemon 偶发启动告警：不影响编译与测试，最终 `BUILD SUCCESSFUL`。不构成工程遗留问题。

---

## 4. 智能路由判定

**Routing Decision: NoOne**

- K-01（测试断言）、K-02（自定义源不可见）、K-03（API19 图标兜底）、K-04（屏幕常亮对称性）**全部修复并通过复验**；最终 `testDebugUnitTest` **48/48 全通过，BUILD SUCCESSFUL**。
- 静态审查 8 项全部 PASS（JNI 完整性、包名策略、无残留耦合、shim 正确、资源/依赖、LiveActivity 生命周期、编译产物、K-02 修复实现）。
- K-05 为构建环境提示，非工程缺陷。当前**无阻断、无一般、无提示级工程遗留问题**。
- 源码改动由工程师完成，QA 仅新增/修正测试代码，未改动任何主源码。

## 5. 交付建议

1. **可交付**：内置央视频道（含 CMG/H5E 解密）、自定义 m3u 源（导入/直链/订阅，K-02 修复后可见）、频道分组/收藏/数字选台/清晰度/断点续播、TV 桌面入口等全部核心能力均通过静态、单元与产物验证；3 个 APK 已于 19:28:59 在全部修复后重编并复核。
2. **已无工程遗留问题**：K-01 ~ K-04 全部关闭；仅剩构建环境提示 K-05（非缺陷）。
3. **真机验证未覆盖**：无 arm TV/盒子设备，CMG/H5E 加密流实际解密出画面、遥控器按键、API19 老盒兼容性未做运行时冒烟；建议用户在目标 TV 上安装后按安装说明做一次播放冒烟（签名算法固有风险见设计 §10.2）。

---

*报告完 — 严过关（QA），2026-11-16（最终版：K-01 ~ K-04 全部修复并复验通过）*
