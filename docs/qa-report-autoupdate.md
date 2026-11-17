# QA 验证报告：OneTV Live 信号源自动更新（T01–T05）

| 项 | 内容 |
|---|---|
| QA | 严过关（Yan / software-qa-engineer） |
| 验证对象 | `onetv-live-apk`，工程师报告 T01–T05 `IS_PASS: YES` |
| 依据 | `architecture-autoupdate.md` v1.1（§3/§4.3/§6）、`prd-autoupdate.md`、实际源码逐文件阅读 |
| 环境 | 会话中断期间 Bash/Grep 故障；**恢复后已完整执行** `gradle testDebugUnitTest`（JDK/SDK/Gradle 路径按 team-lead 指定，带 `mirror-init.gradle`，`--no-daemon`） |
| **单测结果** | **BUILD SUCCESSFUL — 85/85 通过（100%）**；新增测试类 **8 个 / 37 用例全绿** |
| **静态审查结论** | **PASS** — 未见编译阻断错误、引用缺失或核心验收点未落地 |
| **智能路由判定** | **NoOne（放行）** — 全部问题均为测试代码问题，已由 QA 自修；**工程源码一行未改** |

---

## 1. 命令服务恢复后的验证总览

- `echo test` 返回 0，Bash/Grep 均恢复；未反复重试故障命令。
- 工程**无 git 仓库**（`fatal: not a git repository`），因此 JNI/so 零改动采用「mtime 权威对比 + 内容审查」取证。
- 全量测试最终输出：`BUILD SUCCESSFUL in 46s`，`85 tests completed`，`failures=0, errors=0`（21 个 XML 报告逐一核对）。
- 应 team-lead 复核要求，又执行了一次 **`--rerun-tasks` 强制全量重建**（29/29 task 全部重新执行，零缓存）：`BUILD SUCCESSFUL in 1m 20s`，XML 统计 `classes=21 tests=85 skipped=0 failures=0 errors=0`。此为主理人转来的 3 个失败之后的权威结论，证明那 3 个失败是**修复前状态**。
- 测试轮次控制在 2 轮逻辑内：R1 编译失败（测试代码 1 处）→ 自修；R2 运行 3 失败（测试代码 3 处）→ 自修；随后一次最终回归确认全绿。**未修改任何 `src/main` 源码**。

---

## 2. 工程师最后修正的 3 个文件 — **PASS**

三文件均已落盘，mtime 均为 `2026-11-17 11:30`；逐行复读确认符合 v1.1 §4.3：

| 文件 | 核验结论 |
|---|---|
| `UpdateSettingsUi.kt` | `show(activity, onEditCatalogUrl, onChanged)` 签名与 `LiveActivity` 调用一致；**地址行只跳转不写 pref**（:97-106），唯一的 `catalog_url` 生产写入方是 `SignalUpdateSettings.setCatalogUrl()`；状态区含上次更新时间/订阅增减/在线表状态/连续失败提示（:150-178）；"清除流缓存并重试"调 `invalidateAll()`（:127-141） |
| `LiveActivity.kt` | `showLiveSetup()` 第 4 项（索引 3）= 频道表在线地址 → `promptCatalogUrl()`，第 5 项（索引 4）= 返回直播（:94-105）；`promptCatalogUrl()` 是唯一填写入口并调用 `setCatalogUrl()`（:172-185）；`showUpdateSettings()` 把 `onEditCatalogUrl` 回指到同一入口（:187-194）；`LivePanel` 6 参构造调用正确（:72-79）；冷启动顺序 attach→bind→loadFromDisk→enqueue→listener（:43-53） |
| `YangshipinCatalog.kt` | `loadFromDisk()` 三层保险（URL 空 / atCache 不等 / 解析异常）全部就位（:109-126）；`current() = onlineCached ?: channels(assets)`（:78）；`refreshOnline()` 8s/10s、≤3 跳转、≤2MB、版本单调、写盘成功才更新 atCache/version（:133-216）；`invalidate()` 清内存缓存（:60-64） |

---

## 3. 单元测试验证 — **PASS（100%）**

执行环境：
```
JAVA_HOME=/Users/licheng/WorkBuddy/2026-11-16-18-40-24/tools/jdk/Contents/Home
ANDROID_HOME=.../tools/android-sdk
gradle testDebugUnitTest --no-daemon --init-script .../tools/mirror-init.gradle
```

### 3.1 总览

| 指标 | 数值 |
|---|---|
| 测试类 | **21 个**（新增 8 + 存量 13），全部生成 XML 报告 |
| 用例总数 | **85** |
| 通过 / 失败 / 跳过 | **85 / 0 / 0** |
| 通过率 | **100%** |
| 新增测试类（本次功能） | **8 个类 / 37 个用例，37/37 通过** |
| 构建结果 | `BUILD SUCCESSFUL in 46s` |

> 口径说明：任务描述中的"15 个新测试类"与实际不符。实际新增 **8 个**测试类（`LiveRepositoryAutoRefreshTest`、`ChannelIdMigratorTest`、`StreamCacheInvalidationTest`、`BackoffTest`、`OfficialRouteMemoryTest`、`UpdateSettingsUiTest`、`OnlineCatalogParserTest`、`YangshipinCatalogOnlineTest`），共 37 用例；存量 13 类 48 用例。请 team-lead 以本报告口径为准。

### 3.2 新增测试类逐类结果（全部 0 failure / 0 error）

| 测试类 | 用例数 | 结果 |
|---|---|---|
| `OnlineCatalogParserTest` | 10 | PASS |
| `ChannelIdMigratorTest` | 6 | PASS |
| `LiveRepositoryAutoRefreshTest` | 5 | PASS |
| `YangshipinCatalogOnlineTest` | 5 | PASS |
| `UpdateSettingsUiTest` | 4 | PASS |
| `StreamCacheInvalidationTest` | 3 | PASS |
| `BackoffTest` | 2 | PASS |
| `OfficialRouteMemoryTest` | 2 | PASS |
| **合计** | **37** | **100%** |

存量 13 类 48 用例亦全部通过（含 `YangshipinCatalogTest` 5/5、`YangshipinPlaybackCoordinatorTest` 6/6、`PlaylistParserTest` 11/11、`PlaybackAttemptsTest` 7/7 等），证明本次改动**未引入存量功能回归**。

### 3.3 测试代码修复记录（QA 自修，未动源码）

| # | 现象 | 根因判定 | 修复 |
|---|---|---|---|
| 1 | `compileDebugUnitTestKotlin` 失败：`YangshipinCatalogTest.kt:42/64/81` 三处尾 lambda 被 Kotlin 解析到新增的第 6 参 `onSettings: (() -> Unit)?`，而 lambda 是 3 参 | **测试代码 bug**（源码 6 参构造正确，第 6 参默认 null） | 改为显式命名实参 `pageFactory={c,tap,state->...}` |
| 2 | `StreamCacheInvalidationTest:43` `ClassCastException: Long cannot be cast to String` | **测试代码 bug**：对 `putLong` 写入的 `time_*` 调了 `getString` | 改为 `prefs.getLong("time_600001800_fhd",0L) > 0L` |
| 3 | `OfficialRouteMemoryTest:50` expected 200 but was 0；`YangshipinCatalogOnlineTest:73` 在线表未生效 | **测试隔离 bug**：两处 `attach()` 为 first-wins，跨 Robolectric 测试类共享静态 `appContext`，prefs 读错实例。单独运行两用例均 PASS，可确认非源码问题 | `@Before` 中反射清空静态 `appContext` 再 `attach(app)` |
| 4 | 修复 #3 后 `YangshipinCatalogTest` 3 例失败（expected 55 but was 1、分组出现"在线组"） | **测试隔离 bug**：`YangshipinCatalogOnlineTest` 遗留 1 频道在线表到内存/磁盘，污染后续类 | 给 `YangshipinCatalogOnlineTest` 增加 `@After` 对称清理（`invalidate()` + 清 pref + 删 online json） |

修改的测试文件仅 4 个：`YangshipinCatalogTest.kt`、`StreamCacheInvalidationTest.kt`、`OfficialRouteMemoryTest.kt`、`YangshipinCatalogOnlineTest.kt`。**`app/src/main/**` 源码零改动。**

---

## 4. 静态审查逐项结论

### 4.1 JNI / so 零改动 — **PASS**

权威 mtime 对比（`stat -f "%Sm"`）：

| 文件组 | mtime | 与新增代码时间差 |
|---|---|---|
| `com.bu.cc.tv/*.java`（3 个） | 2026-11-16 19:00:22 | 早 ~15.6h |
| `jniLibs/*/*.so`（4 个） | 2026-11-16 19:00:18 | 早 ~15.6h |
| `assets/yangshipin-channels.json`、`legacy-ca.pem` | 2026-11-16 19:00:16 | 早 ~15.6h |
| `res/values/theme.xml` | 2026-11-16 19:00:13 | 早 ~15.6h |
| **基准**：`SignalUpdateSettings.kt`（新增） | **2026-11-17 10:37:46** | — |

内容审查：`NativeYspSigner.java` 仅 `System.loadLibrary("yangshipin")` + `native tokenRnd/signature` 声明；`HlsProxyServer.kt`（845 行）逐行阅读确认 `SegmentCache`、JNI CMG 运行时状态与 `close()` 清理逻辑原样，新代码只调用其公开 `configureChannel()`(:518)/`transformFor()`(:523)。`YangshipinApiPayload/LegacyTls/CmgTsStream/CmgBytePipe/ProgressiveMediaBuffer` 均未被新逻辑触碰。

### 4.2 引用完整性 — **PASS**

Grep 全量核对，新增/改动文件引用的方法均定位到定义：
`PlaylistParser.digest/httpUrl/playlistAddress/MAX_BYTES`、`LiveChannel(id,name,group,streams)`（`PlaylistParser.kt:10,16,23,24,30`）；`AppThreads.onIo`（:28）；`NativeUi.show/notice/textSize/optionButton/dismissDialogs`、`GlassUi.colors`；`HlsProxyServer.configureChannel/transformFor`（:518/523）；`BuildConfig.CATALOG_URL`（`app/build.gradle:23`）；WorkManager 全套类（`work-runtime-ktx:2.9.1`，build.gradle:78）。**无未解析符号**，且实际编译已通过。

### 4.3 LivePanel 构造一致性 — **PASS**

定义 `LivePanel.kt:24` 为 6 参（第 5 `pageFactory`、第 6 `onSettings` 均默认 null）。Grep 全部调用点：生产代码 `LiveActivity.kt:72-79` 传满 6 参且顺序正确；存量测试 `YangshipinCatalogTest.kt` 3 处 4 参调用在显式指定 `pageFactory=` 后通过编译与运行。旧 `maybeAutoRefreshSubscription()` / `autoRefreshTried` 已无残留（Grep 0 hit）。

### 4.4 A 订阅源自动刷新 — **PASS**

`applySubscription(activate=false)`（`LiveRepository.kt:250-284`）只原地 `sources[index]=fresh; saveAsync()`，不调 `replace()` → 不改 active/不写 lastMessage；digest 相同仅刷新 `updated`。`refreshAllSubscriptions()`（:312-341）过滤 ≤10 订阅源、逐源 try/catch 隔离、统计 added/removed。`snapshotStore()`(:344) 备份 + `restoreSnapshot()`(:163) 恢复并落盘。`ChannelIdMigrator.migrate()`(:26-55) 三级命中（ID→流摘要→规范化名称）、孤儿保留。`UpdateCoordinator.refresh()`(:79-94) 串联备份→刷新→迁移→全失败回滚。

### 4.5 B 在线频道表 — **PASS**

`catalog_url` 默认空、唯一写入口 `setCatalogUrl()`（Grep 确认生产代码仅 `SignalUpdateSettings.kt:125` 写 `KEY_CATALOG_URL`；测试中的直写是 fixture）。三层兜底：Coordinator `if(catalogEnabled)`、`loadFromDisk()` 空 URL/atCache 不符即 return、`current()=onlineCached ?: assets`。换地址清缓存：`setCatalogUrl()` 清 `catalogVersion=0` + `catalog_url_at_cache=""`（:129-132）并 `YangshipinCatalog.invalidate()`；`refreshOnline()` 再判 atCache 不符清内存（:138-144）。`OnlineCatalogParser` 校验 schema/version/1..2000 数量/pid 唯一/name 非空/sha256 checksum（:25-70）。`refreshOnline()` 失败只返回 reason，绝不写空缓存。**无硬编码频道表 URL**：Grep `https?://` 在新增文件中仅见既有播放器 API/Referer、本地代理 127.0.0.1、`example.com` 输入框提示；`YangshipinCatalog.SOURCE` 为改造前既有播放器页常量。

### 4.6 C 播放失效恢复 — **PASS**

`invalidatePid()`（`YangshipinStreamResolver.kt:72-80`）按 `canonicalPid` 同时清 memoryCache 与 prefs 的 `url_*/time_*`；`invalidate()`/`invalidateAll()` 同样双清。签名逻辑未动（`tryFetchYangshipinLiveUrl` 仍每次现调 `NativeYspSigner.tokenRnd/signature`）。退避 `PlaybackAttempts.backoffDelayMs`(:14-18) = `min(8000, 1200<<shift + jitter)`，shift 封顶 3 → 1200/2400/4800/8000；`YangshipinPlayer:116` 与 `LivePanel:386` 均已改用。403/404 额外清缓存（:238-245）、`resume()` 清缓存重连（:337）、`reportRoute()` 记忆实际成功线路（:197-204）、`open()` 首次读记忆（:139-145）。

### 4.7 配置双入口 — **PASS**

`showLiveSetup` 索引 3 → `promptCatalogUrl()`（`LiveActivity.kt:94-105`）；LivePanel"更新设置" → `UpdateSettingsUi` 地址行 → `onEditCatalogUrl` → 同一 `promptCatalogUrl()`；两入口均汇入 `setCatalogUrl()`（Grep 全链路确认）。

### 4.8 编译可行性 / 配置 — **PASS**

`work-runtime-ktx:2.9.1`（build.gradle:78）、`buildConfigField CATALOG_URL '""'`（:23）、Manifest `RECEIVE_BOOT_COMPLETED`（:17）全部就位；`compileDebugKotlin` 与 `compileDebugUnitTestKotlin` 均已实际成功执行。`assembleDebug` 由 team-lead 并行执行，本报告不重复声明其产物。

### 4.9 残留文件 — **PASS**

`find app -iname '*checksumTemp*'` → **无输出**。`OnlineCatalogParser.checksumTemp` 不存在，无残留风险。

---

## 5. 遗留问题分级（不阻塞交付）

| ID | 级别 | 状态 | 问题 | 位置 | 修复方式 / 复核结论 |
|---|---|---|---|---|---|
| L1 | 中（P2） | **已关闭（本轮修复 + team-lead 逐行复核）** | `refresh()` catch 分支只置 `rolledBack=true`，未真正调用 `restoreSnapshot(before)`（`before` 原定义在 try 内） | `UpdateCoordinator.kt:78-106` | `var before = ""` 已提到 try 外（:79）并在 try 内赋值（:81）；catch 分支 :98-100 为 `if (before.isNotEmpty()) { try { repo.restoreSnapshot(before) } catch (_: Throwable) {} }`，随后 `rolledBack = true`（:105）。QA 复读源码 + team-lead 逐行复核确认：异常路径真正回滚，闭合 |
| L2 | 低（P3） | **已关闭（本轮修复 + team-lead 逐行复核）** | `setCatalogUrl()` 空值时也调 `enqueueNow()`，与架构 §4.3.3"仅非空分支入队"不符 | `SignalUpdateSettings.kt:134-142` | `SignalRefreshWorker.enqueueNow(ctx)` 与 `AppThreads.onIo { UpdateCoordinator.refreshCatalogNow(ctx) }` 已被 `if (next.isNotBlank()) { ... }`（:137-141）完整包住，大括号闭合正确；清空地址仅保留 `YangshipinCatalog.invalidate()` + `UpdateCoordinator.reconfigure()`，Coordinator 的 B 步骤因 URL 空直接跳过，零 B 网络请求。QA 复读源码 + team-lead 逐行复核确认闭合 |
| L3 | 低（P3） | 遗留（非阻塞） | `YangshipinPlayer.open()` 仅每实例首次读线路记忆 | `YangshipinPlayer.kt:139-145` | 可按 pid 每次读取以增强 R-C2 |
| L4 | 低（P3） | 遗留（非阻塞） | `refreshAllSubscriptions()` 为 `@Synchronized`，持锁期间做最多 10 源网络 I/O，主线程 `sourceList()` 可能阻塞 | `LiveRepository.kt:201,312` | 收窄锁范围到内存写段 |
| L5 | 低（流程） | 遗留（非阻塞） | 测试中 `attach()` first-wins + 静态缓存跨类泄漏，需要反射重置与 `@After` 清理 | 4 个测试文件 | 后续可考虑给 `attach()` 增加可重入语义或统一 TestRule |

L1/L2 已在本轮关闭；L3–L5 为**非阻塞健壮性/可维护性改进项**，未违反核心验收点；按 2 轮上限原则不再展开修复循环。

---

## 6. 智能路由判定

**Send To: NoOne — 放行**

- 源码层面：18 个新增/改动文件引用闭环、LivePanel 6 参构造兼容、A/B/C 与配置双入口全部按 v1.1 落地、JNI/so/res/assets 零改动、无硬编码地址、无残留文件，**且 85 个单测 100% 通过、存量测试无回归**。
- 全部失败项均判定为**测试代码问题**，已由 QA 自修并复验通过，无需回传工程师。
- L1/L2 已由工程师本轮修复（异常路径真正回滚、清空地址零 B 网络请求），经 QA 复读源码与 team-lead 逐行复核确认闭合；剩余 L3/L4/L5 为供后续迭代参考的非阻塞遗留。

**交付建议**：主理人可据 `assembleDebug` 结果 + 本报告 85/85 单测结论出包；L1/L2 已关闭，剩余 L3/L4/L5 供后续迭代按需处理。
