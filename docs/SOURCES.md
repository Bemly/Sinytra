# SOURCES — Sinytra

> 上游语义引用。AOSP 一律看 `android14-release` 分支；master/main 只做未来版本参考。

## AOSP（android14-release）

- [WebViewFactoryProvider.java](https://android.googlesource.com/platform/frameworks/base/+/android14-release/core/java/android/webkit/WebViewFactoryProvider.java)
- [WebViewProvider.java](https://android.googlesource.com/platform/frameworks/base/+/android14-release/core/java/android/webkit/WebViewProvider.java)
- [WebView.java](https://android.googlesource.com/platform/frameworks/base/+/android14-release/core/java/android/webkit/WebView.java)
- [WebViewFactory.java](https://android.googlesource.com/platform/frameworks/base/+/android14-release/core/java/android/webkit/WebViewFactory.java)
- [WebViewLibraryLoader.java](https://android.googlesource.com/platform/frameworks/base/+/android14-release/core/java/android/webkit/WebViewLibraryLoader.java)
- [config_webview_packages.xml](https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/res/res/xml/config_webview_packages.xml)
- [WebViewGlueCommunicator.java（androidx support-library glue 入口）](https://android.googlesource.com/platform/frameworks/support/+/f3d75c56cc038c3e289e92575ee0cbfd9b8f1c10/webkit/webkit/src/main/java/androidx/webkit/internal/WebViewGlueCommunicator.java)

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

## AndroidX / AOSP 工具

- [WebViewCompat — Android Developers](https://developer.android.com/reference/androidx/webkit/WebViewCompat)
- [WebViewAssetLoader — Android Developers](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader)
- [Repo command reference — Android Open Source Project](https://source.android.com/docs/setup/reference/repo)
