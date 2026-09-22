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

（空——首个 patch 是 response-body 拦截，等 objdir 构建通过后再落文件；

| # | 文件 | 解决哪个 WebView API | 为什么 public API 不够 | 上游对应 |
|---|---|---|---|---|
| （拟）0001 | necko response-body 拦截 | `shouldInterceptRequest` 返回自定义 body | GV153 `onLoadRequest` 只有 ALLOW/DENY，`LoadRequest` 无 method/headers/body 替换 primitive（AAR javap 已核） | 待查 searchfox（nsIInterceptedChannel 一族） |

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
