# 0010 — ACCESS_NETWORK_STATE 缺失时降级而非崩宿主

> 解决哪个 WebView API：全局健壮性（WebView 语义：宿主 App 缺权限时
> provider 降级，不许杀宿主）。AGENTS §7「delegate 异常不许上抛崩 App」。
> 为什么 public API 不够：`GeckoNetworkManager` 主线程直调
> `ConnectivityManager.getActiveNetworkInfo()`，无权限即 SecurityException。
> 上游 bug：无（普通 GeckoView App 模板都声明该权限，不可见）。

## 1. 症状

任何未声明 `ACCESS_NETWORK_STATE` 的宿主（mywebview、自建 MiniWV v1 实测）：

```text
FATAL EXCEPTION: main
java.lang.SecurityException: ConnectivityService: Neither user 10229 nor
  current process has android.permission.ACCESS_NETWORK_STATE.
  at NetworkUtils.getConnectionType(NetworkUtils.java:108)
  at GeckoNetworkManager.updateNetworkStateAndConnectionType(:374)
  at GeckoAppShell.lambda$enableNetworkNotifications$0(...)
```

主线程死亡 → 宿主 ANR/被杀。Chromium 的 NetworkChangeNotifier 同场景
静默降级，WebView 文档未要求宿主持有该权限。

## 2. 修复（Java-only，GeckoNetworkManager.java）

`updateNetworkStateAndConnectionType` 入口加权限守卫：
`context.checkSelfPermission(ACCESS_NETWORK_STATE) != GRANTED` 时置
`ConnectionType.NONE / ConnectionSubType.UNKNOWN / NetworkStatus.UNKNOWN`
并 warn 一次（静态门），return。`CONNECTIVITY_ACTION` 广播接收不受影响
（接收不需要该权限），receiver 回调经同函数再次被守卫。

## 3. 验证

- mywebview（缺权限宿主）：`0 FATAL`、存活、log 出现
  `ACCESS_NETWORK_STATE not granted by the host app; network state reporting disabled.` 一次。
- MiniWV v2（带权限）：行为不变（网络状态上报照常）。
- 反射 harness 41 PASS + P0 GLUE PASS。
