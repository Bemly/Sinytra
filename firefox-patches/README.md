# firefox-patches — Sinytra

> 相对 Firefox 源码树的 patch stack。纪律见 AGENTS.md §3/§5 +
> `docs/BOOTSTRAP.md` §3：**每个 patch 只做一件事、独立测试、rebase 不解不升级**。

## Pin

- 基线：**mozilla-central 158.0a1 最后一刻**
  `34ed69f161676c3ac7ca5f201fade6d081e5bcd2`（2026-09-25 定稿；158.0 正式
  tag 未发布，出来后再评估切 release；`browser/config/version.txt`=158.0a1
  已核，下一个 version bump commit 即翻 159.0a1）
- 对齐 AAR：`org.mozilla.geckoview:geckoview-nightly:158.0.20260924093433`
  （daily 走 maven.mozilla.org）。注意 158 树本地 publish 产物的
  artifactId 是 **geckoview-default**（computeArtifactId 新规则），且
  `substitute-local-geckoview.gradle` 把 nightly 坐标换成本地
  geckoview-default——已验证咬合
- sibling checkout 位置：`/Volumes/（U+F8FF 卷）/Projects/firefox`。
  **卷名含 U+F8FF 字面路径经 Bash 传递编码不稳定（实测反复踩）**，一律走
  `~/sinytra-vol` 符号链接或 python 探测；磁盘以该卷为准（非系统盘）
- 分支：`sinytra-pin-158`（158 线，活跃）；`sinytra-pin`（153 线冻结，
  0001-0003 的 153 版 patch 历史，objdir-opt 保留可回退）
- 构建：`mozconfig-158`（独立 `objdir-158`，内容同 mozconfig 仅 objdir 不同；
  每线一个 mozconfig/objdir，互不覆盖）

## Stack（自下而上，编号即应用顺序）

| # | 文件 | 解决哪个 WebView API | 为什么 public API 不够 | 状态 |
|---|---|---|---|---|
| 0001 | response-body 拦截（导航级） | `shouldInterceptRequest` 返回自定义 body | GV 无 Java→Gecko 响应体通道（详见 0001 设计文档） | **已定稿**：`0001-response-body-interception.patch`（7 commits）；153 线真机打通（2026-09-24 29 PASS），**158 重放 @ `e79d4c7e1362`** |
| 0002 | 请求信息保真 + 子帧 DENY 让位 | `shouldInterceptRequest` 的 method/headers 语义、filter 命中子帧不被近似杀掉 | 0001 查询面 v1 只有 uri（详见 0002 设计文档） | **已定稿**：`0002-request-info-and-subframe-standdown.patch`；**158 重放 @ `2985d67b9eaa`** |
| 0003 | 流式响应体 | 替身体流式读取，无 16MB 上限 | base64+cap 基于过时假设（详见 0003 设计文档） | **已定稿**：`0003-response-body-streaming.patch`；**158 重放 @ `8efda7b417c0`** |
| 0004 | 合成响应 SW tainting（ORB 加固） | 顶层导航合成响应无 loading principal，ORB/跨源检查会拒 | `InterceptedHttpChannel` 的 SW 路径会 SynthesizeServiceWorkerTainting，自研路径不会——借鉴 wszgrcy 线（RELATED-PROJECTS §2.1） | **已定稿**：`0004-synthesized-sw-tainting.patch`（@ `bc58ea7cf4df`）；153 上不需要（真机已通），158 起防御性补上 |
| 0005 | 插桩日志 debug 门 | debug 构建多打、release 零输出（README「日志纪律」） | AAR debug/release 变体共用一个 libxul，编译期门做不到按变体区分 → 运行时 pref `sinytra.log.enabled`（默认 false） | **已定稿**：`0005-debug-only-instrumentation.patch`（@ `b43672422aa1`）；provider debug 构建经 `src/debug/assets/geckoview-config.yaml` 打开 |
| 0007 | CookieManager cookie-jar 原语 | `CookieManager` 逐 cookie get/set/removeSessionCookies/hasCookies | GV 无任何逐 cookie API（jar 在 Necko；0006 编号预留给 download/SSL-proceed 排查）→ `StorageController` 四原语 + JS 模块 `nsICookieManager` 直控（设计文档 `0007-cookie-jar.md`） | **已定稿**：`0007-cookie-jar.patch`（@ `dfcc04709482`）；设备 cookieJar 探针 + 38 探针回归 |

设计文档：`0001/0002/0003-*.md`（树内勘察、地基选型、边界、测试计划）。

## 工作流

```bash
cd ~/sinytra-vol/Projects/firefox        # U+F8FF 卷名，见 Pin 节
git checkout sinytra-pin-158             # 158 线（153 线在 sinytra-pin）
export MOZCONFIG="$HOME/sinytra-vol/Projects/firefox/mozconfig-158"
export MOZBUILD_STATE_PATH="$HOME/sinytra-vol/Projects/mozbuild"
export PATH="$HOME/.cargo/bin:$PATH"
#（逐个应用/修改 patch 后）
./mach build binaries                    # 增量；新增 GeneratedJNI 头先 build export
./mach gradle geckoview:publishDebugPublicationToMavenRepository
# 本地产物 artifactId=geckoview-default；substitute 脚本换 nightly 坐标
cd ~/sinytra-vol/Projects/Sinytra
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
  ./gradlew :provider:assembleDebug -PsinytraLocalGecko=true
# 真机回归：P0RenderActivity 金丝雀 + P0GlueActivity 32 探针
```

环境备忘：rust 用 rustup 工具链（homebrew rustc 缺 android target 的
std；`~/.cargo/bin` 已放 rustc/cargo/rustdoc shim，构建 PATH 需前置）。
后台跑 mach 时**不要给命令设短超时**（libxul 链接 >10min，超时会静默
杀掉链接且管道吞退出码——2026-09-25 踩过）；管道后必须显式 `echo $?`
核对 mach 退出码。

升级 Firefox 版本时：逐个 rebase，冲突不解决不许升级（AGENTS.md §5）。
2026-09-25 153→158 重放实录：9 commits 仅 1 冲突
（`ParentChannelListener.cpp` include 区重排），`GeckoSession.java`/
`components.conf`/`widget moz.build`/JS 两处全部自动合并。

## 日志纪律（2026-09-25 定）

插桩日志的目标是**排查**：debug 构建要多打（入口/出口/关键分支），release
构建不启用。机制按层：

- C++：`__android_log_print` 包 `#ifdef DEBUG`（moz debug 构建定义 DEBUG，
  release 编译期剔除）；
- 树内 Java（geckoview 模块）：`BuildConfig.DEBUG` 门；
- provider Java：`src/debug` sourceSet / `BuildConfig.DEBUG`（既有做法）；
- JS：debug flag。

tag 一律 `Sinytra/<模块>`；上游共享文件里的插桩必须在定稿时收敛到
`ifdef DEBUG` 门内或删除，不许裸奔进 release。
