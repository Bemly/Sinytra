# SOURCES — Sinytra

> 上游语义引用。AOSP 一律看 `android14-release` 分支；master/main 只做未来版本参考。

## AOSP（android14-release）

- [WebViewFactoryProvider.java](https://android.googlesource.com/platform/frameworks/base/+/android14-release/core/java/android/webkit/WebViewFactoryProvider.java)
- [WebViewProvider.java](https://android.googlesource.com/platform/frameworks/base/+/android14-release/core/java/android/webkit/WebViewProvider.java)
- [WebView.java](https://android.googlesource.com/platform/frameworks/base/+/android14-release/core/java/android/webkit/WebView.java)
- [WebViewFactory.java](https://android.googlesource.com/platform/frameworks/base/+/android14-release/core/java/android/webkit/WebViewFactory.java)
- [WebViewLibraryLoader.java](https://android.googlesource.com/platform/frameworks/base/+/android14-release/core/java/android/webkit/WebViewLibraryLoader.java)
- [config_webview_packages.xml](https://android.googlesource.com/platform/frameworks/base/+/android14-release/core/res/res/xml/config_webview_packages.xml)（候选/签名名单；本项目不改它，由 AnyWebView 放行）
- [services/core/.../server/webkit/（WebViewUpdateService validity 判定）](https://android.googlesource.com/platform/frameworks/base/+/android14-release/services/core/java/com/android/server/webkit/)
- [WebViewGlueCommunicator.java（androidx support-library glue 入口）](https://android.googlesource.com/platform/frameworks/support/+/f3d75c56cc038c3e289e92575ee0cbfd9b8f1c10/webkit/webkit/src/main/java/androidx/webkit/internal/WebViewGlueCommunicator.java)

## 切换工具（唯一部署路线，BOOTSTRAP §2）

- AnyWebView v1.3（LSPosed 模块，包 `com.thinkdifferent.anywebview`；v1.4.x 需 libxposed API 101，本机 LSPosed v1.11.0 不支持）
- LSPosed v1.11.0（zygisk）

## Chromium 文档（provider 机制参考）

- [WebView providers](https://chromium.googlesource.com/chromium/src/+/main/android_webview/docs/webview-providers.md)
- [AOSP system integration](https://chromium.googlesource.com/chromium/src/+/main/android_webview/docs/aosp-system-integration.md)

## GeckoView

- [GeckoView Architecture — Firefox Source Docs](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/contributor/geckoview-architecture.html)
- [Getting Started with GeckoView](https://mozilla.github.io/geckoview/consumer/docs/geckoview-quick-start)
- [Substituting a local GeckoView](https://firefox-source-docs.mozilla.org/mobile/android/fenix/substituting-local-gv.html)
- [substitute-local-geckoview.gradle — searchfox](https://searchfox.org/firefox-main/source/substitute-local-geckoview.gradle)
- [GeckoView junit Test Framework](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/contributor/junit.html)

## GeckoView javadoc（以 pin 住的 GECKOVIEW_VERSION 对应版本为准）

- [GeckoSession](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.html)
- [GeckoRuntime](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoRuntime.html)
- [StorageController](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/StorageController.html)
- [SessionFinder](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/SessionFinder.html)
- [SessionState](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.SessionState.html)
- [HistoryDelegate](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.HistoryDelegate.html)
- [ProgressDelegate](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.ProgressDelegate.html)

## Bugzilla（纪律：Gecko 侧疑难先翻 bugzilla 再动手，2026-10-04 用户立规）

> Fission / process-switch / progress-listener / actor-lifecycle 类问题，
> 上游大概率已有公开讨论。动手前按关键词搜一遍，把相关 bug 号记在这里。

- [1673953](https://bugzilla.mozilla.org/show_bug.cgi?id=1673953)
  `[gv-junit-fis] Run GeckoSessionTestRuleTest on Fission`（NEW，assignee
  m_kato；blocking 1673729；depends on 1657028/1892884/1676531）。
  与我方 0024 §10 直接同构：Fission 下 process switching 时
  `BrowserChild::DestroyWindow` 摘掉 progress listener → `NS_BINDING_ABORTED`
  丢 → 新窗口 PageStart 被压住（comment 12，m_kato 原话 "may seem to be
  a timing issue"）；受影响的正是跨 session 建页计数测试
  （`waitForPageStops_acrossSessionCreation` /
  `createOpenSession_canInterleaveOtherCalls`——与 CTS WebViewSyncLoader
  三条件门同族）；单跑不换进程、全量跑才换（comment 13，与我方绿挂交替
  同形）；复现法是 `--run-until-failure` 循环（comment 11）。
  nika（comment 15）：多进程竞态下多个 load 会被 mash 在一起，fission
  逻辑会刻意压住"前一个没见到 STATE_STOP 就开下一个"的 progress 通知。
  结论：我方 ALLOW→PageStart 静默优先按此机制查，不再当环境问题。

## 相关第三方实现（比对参考，非上游语义来源）

> 同题独立实现，用于交叉验证与借鉴；取用处与结论见 `RELATED-PROJECTS.md`。
> 个人实验性质仓库，引用前自行复核。

- [wszgrcy/firefox（build 分支：请求拦截器 + 构建裁剪，基线 156.0a1）](https://github.com/wszgrcy/firefox)
- [wszgrcy/capacitor-geckoview（Capacitor Android runtime 的 GeckoView 化）](https://github.com/wszgrcy/capacitor-geckoview)
- [wszgrcy/capacitor-geckoview-plugins（官方插件 fork 适配）](https://github.com/wszgrcy/capacitor-geckoview-plugins)
- [wszgrcy/capacitor-geckoview-starter（模板工程，local/production 双轨）](https://github.com/wszgrcy/capacitor-geckoview-starter)

## AndroidX / AOSP 工具

- [WebViewCompat — Android Developers](https://developer.android.com/reference/androidx/webkit/WebViewCompat)
- [WebViewAssetLoader — Android Developers](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader)
- [Repo command reference — Android Open Source Project](https://source.android.com/docs/setup/reference/repo)
