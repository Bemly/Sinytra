# firefox-patches — Sinytra

> 相对 Firefox 源码树的 patch stack。纪律见 AGENTS.md §3/§5 +
> `docs/BOOTSTRAP.md` §3：**每个 patch 只做一件事、独立测试、rebase 不解不升级**。

## Pin

- 基线 tag：`FIREFOX_153_0_RELEASE`（commit `f1b6c0f86b96b7e0688c26f65803576f27cdaf88`）
- 对齐 Maven AAR：`org.mozilla.geckoview:geckoview:153.0.20260810162159`（stable，
  buildid 20260810162159 应即该 release 构建产物；本地首次构建后用
  `GeckoSession.getDefaultUserAgent()`/版本号与 AAR 对照复核）
- sibling checkout 位置（本机实况，偏离 BOOTSTRAP §4 推荐的 ~/src——内置盘仅 7GB）：
  `/Volumes//Projects/firefox`

## Stack（自下而上，编号即应用顺序）

| # | 文件 | 解决哪个 WebView API | 为什么 public API 不够 | 状态 |
|---|---|---|---|---|
| 0001 | response-body 拦截（导航级） | `shouldInterceptRequest` 返回自定义 body | GV153 `onLoadRequest` 只返回 AllowOrDeny；决策在 docshell 层，无 Java→Gecko 响应体通道（树内核实，详见 0001 设计文档） | **已定稿**：`0001-response-body-interception.patch`（7 commits，@ `59aa103b6d50`，日志已降 DEBUG），真机端到端打通（2026-09-24 全量 harness 29 PASS） |
| 0002 | 请求信息保真 + 子帧 DENY 让位 | `shouldInterceptRequest` 的 method/headers 语义、filter 命中的子帧不被 P2-4 近似杀掉 | 0001 查询面只有 uri+isNavigation（C++ 硬编码 isNavigation=true）；LoadRequest DENY 与 necko 替身撞车（勘察详见 0002 设计文档） | **已定稿**：`0002-request-info-and-subframe-standdown.patch`（@ `539b7ff6f329`）；Group A 在 Sinytra glue（`9244efb`/`812b220`）；真机 31 PASS（2026-09-24，含子资源/子帧探针） |
| 0003 | 流式响应体 | 替身体按 Chromium 语义流式读取，无 16MB 上限 | 0001 的 base64+cap 基于"流跨不了 JVM"的过时假设——0002 钉死查询全程在 app 进程，是 JNI 边界不是进程边界（详见 0003 设计文档） | **已定稿**：`0003-response-body-streaming.patch`（@ `62b7b46e280e`）；真机 32 PASS（2026-09-25，interceptLargeBody 17MB 裁决实验过） |

设计文档：`0001-response-body-interception.md`（含树内勘察、地基选型、
边界与测试计划）。

## 工作流

```bash
cd /Volumes//Projects/firefox
git checkout sinytra-pin                  # FIREFOX_153_0_RELEASE 的分支
#（逐个应用/修改 patch 后）
MOZBUILD_STATE_PATH=/Volumes//Projects/mozbuild PATH="$HOME/.cargo/bin:$PATH" \
  ./mach build binaries                   # 增量
MOZBUILD_STATE_PATH=/Volumes//Projects/mozbuild PATH="$HOME/.cargo/bin:$PATH" \
  ./mach gradle geckoview:publishDebugPublicationToMavenRepository
# 153 树的任务名：publishWithGeckoBinaries* 不存在（脚本文档过时）
cd /Volumes//Projects/Sinytra
./gradlew :provider:assembleDebug -PsinytraLocalGecko=true   # 替换构建
# 真机回归：P0RenderActivity 金丝雀 + P0GlueActivity 29 探针
```

环境备忘：rust 用 rustup 工具链（homebrew rustc 缺 android target 的
std；`~/.cargo/bin` 已放 rustc/cargo/rustdoc shim，构建 PATH 需前置）。

升级 Firefox 版本时：逐个 rebase，冲突不解决不许升级（AGENTS.md §5）。

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
