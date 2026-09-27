# 0008 — greomni 必须指向装载 omni.ja 的 APK（provider APK）

> 解决哪个 WebView API：**所有** API——第三方宿主进程里 Gecko 根本起不来
> （native 崩溃），这是切换后第三方 App 全灭的根因。
> 为什么 public API 不够：`GeckoThread.getMainProcessArgs()` 无条件用启动
> 上下文的 `getPackageResourcePath()` 拼 `-greomni`；provider 侧唯一能传
> 参的口子（`GeckoRuntimeSettings.Builder.args()`）追加在内置 greomni
> 之后，XRE `CheckArg` 取**首个**匹配，追加参数永远输。
> 上游 bug：未提（上游不存在"GeckoView 类与宿主 APK 不同文件"的部署形态，
> 普通 embedder 下两个路径恒等，bug 不可见）。

## 1. 症状（2026-09-27，切换后第三方冒烟）

任何**非 provider 包**的宿主 App 进程创建 WebView 时：

```text
GeckoConsole: Could not read chrome manifest 'jar:file://<宿主APK>!/chrome.manifest'
GeckoThread: State changed to JNI_READY
libc: Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x8 in tid (Gecko)
```

确定性复现（mywebview、Obsidian 同签名；间隔 5ms~1.2s 不定）。
provider 自己的 App（宿主 APK == provider APK）永远正常。

## 2. 根因链（tombstone + llvm-symbolizer 实证）

1. `GeckoThread.getMainProcessArgs()`：
   `args.add("-greomni"); args.add(context.getPackageResourcePath())`，
   context = `GeckoAppShell.getApplicationContext()` = **宿主 App**。
2. 第三方宿主 APK 没有 omni.ja → XRE/Omnijar 指向无资源的 APK。
3. `nsComponentManagerImpl::Init` 读静态组件清单
   （components.conf 生成的 Preferences 等注册全在里面）失败：
   `Could not read chrome manifest 'jar:<宿主APK>!/chrome.manifest'`
   （xpcom/components/nsComponentManager.cpp:431）→ **组件注册表为空**。
4. `XREMain::XRE_mainRun`（nsAppRunner.cpp:6160 附近）
   `do_GetService("@mozilla.org/preferences-service;1")` 失败被静默跳过
   → `Preferences::sPImpl` 永不创建。
5. nsAppRunner.cpp:6206 `mDirProvider.InitializeUserPrefs()` →
   `Preferences::InitializeUserPrefs()`（Preferences.cpp:5029）对
   null `sPImpl` 的 `mCurrentFile` 成员做 `nsCOMPtr::operator=` →
   读 [null+8]（tombstone：x0=0, fault addr 0x8, SEGV_MAPERR）。
6. MO 的 release 构建 MOZ_ASSERT 全编译出局，null 直接解引用。

符号化方法：本地 objdir-158 的 libxul.so（BuildId 与 tombstone 一致）
+ mozbuild clang/bin/llvm-symbolizer 直接解 PC（4b4ed80/9ca159c/9ca22fc/
9ca2764 → assign_assuming_AddRef → InitializeUserPrefs → XRE_mainRun）。

## 3. 排除记录（教训，防回潮）

- **hidden API "denied" 警告**：Boolean/Integer/Long/Double.value 四条
  JNI 字段拦截，我们 App 与第三方 App **完全一致**，良性（ denial 返回
  默认值不致命）。不是根因。
- **WebView Zygote fork**：webview_zygote 未映射 libxul（`/proc/<pid>/maps`
  0 条），崩溃进程 ppid=zygote64，出生时无 libxul 映射。不是 fork 态污染。
- **双 libxul 映射**：tombstone 全部栈帧同一映射（apk!libxul.so 一份）。
- **App 代码差异**：mywebview（纯 WebView 壳）与 Obsidian（Capacitor 重度
  用户）同签名崩；provider 自己的 App 反射注入 + 真实路径双口径全绿。

## 4. 修复（Java-only，GeckoThread.java）

`getMainProcessArgs()` 的 greomni 取值改为 `getOmnijarApk(context)`：
经 `((BaseDexClassLoader) GeckoAppShell.class.getClassLoader())
.findLibrary("mozglue")` 解析 GeckoView 类所在 APK（镜像
GeckoLoader.getLibraryPath 的既有手法，同一 classloader 事实源），路径含
`!/lib/` 时取 `!/` 前缀即 provider APK；**任何失败/相等/提取目录形态都
回退 `context.getPackageResourcePath()`**（普通 embedder 行为零变化）。
不等价于改 GRE_HOME（那是 dataDir，目录不是 APK）；也不等价于追加 args
（首匹配必输，见上）。

## 5. 测试计划

- 真机：切换态下 Obsidian 冷启（无 SEGV、无 manifest 警告、页面渲染
  screencap）+ mywebview 冷启 + `GeckoThread` 日志出现
  `greomni: using provider apk`。
- 回归：provider 反射 harness 全量 41 PASS + P0 GLUE PASS
  （greomni 在 provider 自家 App 上应恒等原值，无行为变化）。
- 单元：greomni 逻辑纯 Java 侧无 JVM 可测性（classloader/findLibrary 依赖
  运行期），归设备探针。
