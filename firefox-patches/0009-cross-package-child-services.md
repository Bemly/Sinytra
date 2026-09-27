# 0009 — child services 跨包可解析/可绑定

> 解决哪个 WebView API：所有——第三方宿主里 Gecko 起不来（XRE 无 child 即
> 死）。是 0008 的姊妹项：0008 修 GRE 资源定位，本项修进程模型。
> 为什么 public API 不够：`ServiceAllocator`/`ServiceUtils` 用启动上下文包名
> 解析服务，且 AAR manifest 里服务 `exported="false"`，跨包 bind 必被拒。
> 上游 bug：未提（同 0008，上游无此部署形态）。

## 1. 症状（0008 修复后暴露的下一层）

0008 后 XRE 主进程初始化全绿（`PROFILE_READY`/`RUNNING`），随即：

```text
System.err: java.lang.SecurityException: Not allowed to bind to service
  Intent { cmp=moe.bemly.geckowebview.debug/...GeckoChildProcessServices$gpu }
  at ServiceAllocator$InstanceInfo$DefaultBindDelegate.bindService
libc: Fatal signal 11 (SIGSEGV), fault addr 0x0 in tid (launcher)
```

child spawn 失败后 native launcher 线程解引用 null（上游健壮性缺口，
spawn 失败应走 tab 重试语义——这里直接崩，记录为已知上游行为）。

## 2. 两个断点

1. **包名解析**：`DefaultBindDelegate`/`IsolatedBindDelegate`
   `intent.setClassName(context=宿主, svc)`、`ServiceUtils.getServiceFlags`
   `new ComponentName(context, ...)`、`getServiceList`
   `getPackageInfo(context.getPackageName())`——全部指向宿主包，而服务只
   在 provider 包的 manifest 里。
2. **exported**：`AndroidManifest_overlay.jinja` 全部服务
   `android:exported="false"`，跨包 bind 被拒（SecurityException）。

## 3. 修复

- `ServiceUtils.getComponentPackage(context)`：classloader mozglue 路径
  （`…base.apk!/lib/<abi>/libmozglue.so`，镜像 GeckoLoader.getLibraryPath）
  推导 provider APK 路径 → 与 `context.getPackageResourcePath()` 快路径
  相等即宿主 = provider（普通 embedder 零变化）；不等时用
  `WebView.getCurrentWebViewPackage()` 且验证其 `sourceDir`/`publicSourceDir`
  匹配才采用，失败回退宿主包。静态缓存一次。
- 两个 bind delegate + `getServiceFlags`/`getServiceList` 改用该包名。
- jinja：child services `exported="true"`（本仓库部署形态即系统 provider，
  暴露面为已知并接受——任何 App 可 bind 出 provider uid 的 Gecko child；
  安全语义记录于 STATUS §1t）。

## 4. 验证

- MiniWV 最小宿主（自建，`moe.bemly.minimalwv`）：`3 successful binds`、
  5 个 child 进程挂在 **provider uid（u0_a227）** 下
  （`moe.bemly.geckowebview.debug:gpu/tab…`），宿主 uid 不变；example.com
  渲染 + onPageFinished title=Example Domain。
- 反射 harness 41 PASS + P0 GLUE PASS（自家 App 快路径无行为变化）。

## 5. 构建坑（复述 §1j ①）

`AndroidManifest_overlay.jinja` 属 **GENERATED_FILE**（export/binaries 层
之外的生成规则）：改完 jinja 后 `mach build binaries`/`export` 都**不会**
重生成，必须 `mach build`（增量全量）触发；否则 AAR manifest 维持旧值。
