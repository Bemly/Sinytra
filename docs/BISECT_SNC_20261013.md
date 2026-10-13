# SNC WebView 黑屏二分排查报告（2026-10-13）

> 问题：HEAD（`797c240`）在 WEBVIEW TEST（`com.snc.test.webview2`，下称 SNC）
> 里打不开 example.com / bing.com（黑屏），而 `490ec28`（10-08）可以。
> 方法：固定引擎、固定手法、逐轮二分 provider 提交。
> 结论（先行）：**没有找到 provider 回归**。二分算术一度指向 `77ceb11`，
> 但 R8/R10 两轮证伪（无该代码照黑）；最终定位到版本无关的
> **`override=true → DENY 死锁** + SNC 侧 SOE 后行为分叉。
> 0035/0036（worker 解毒/async resume）的机械效果已验证（此后零 kill、
> 零 ANR、零 10 秒 park）。
> 本文档是证据底稿：时间戳、log 文件名、关键行号均可复核
> （证据包见 §9，`Sinytra-apks/`）。

## 1. 控制变量（违反则本轮作废）

- **引擎固定**：全部轮次同一本地 AAR 快照（`geckoview-default
  ...-26/-27/-28/-29`，随当天 publish 前进；R0 起为 `-29` 含 0035/36/37）。
  verdict 差异只归因 provider 代码。`libxul.so` 逐字节比对过（R0 构建
  `222681496` vs 当天构建，见 §4）。
- **手法固定**：用户手输 URL（example.com 或 bing.com，二分轮统一 bing），
  fresh 进程（双 force-stop 确认零残留），`logcat -c` 后点，点后 2 分钟判。
- **判据**：像素为准（screencap 亲眼）+ `PageStart/PageStop`；60 秒内不判死
  （2026-10-13 当晚两次过早误判：20:12 黑屏轮实为慢提交，见 §7.7）。
- **熔断**：GOOD 端点黑 → 停（引擎/环境问题）；BAD 端点绿 → 停（回归已不在）。

## 2. 区间与中点表

- 区间：`490ec28`（GOOD）.. `797c240`（BAD），128 commits。
- 其中 79 个为 docs/firefox-patches（引擎固定，行为不相关，验过 stat），
  14 个为 debug-only/杂项（`6748ac1` 查过：仅 debug activities + assets），
  **35 个 provider/src/main + tests，进入二分**（顺序见下表 idx0–34）。

| idx | commit | 内容 | 行为/日志 |
|---|---|---|---|
| 0 | 328a7b8 | 主线程 void setCookie 改 fire-and-forget | 行为 |
| 1 | 8d0f537 | pre-runtime void 写排队待 flush | 行为 |
| 2 | dca400c | ensureBuiltIn 装不上 bounded 重试至 ack | 行为 |
| 3 | cee3e7e | commit-guard 按 necko 进展决策 | 行为 |
| 4 | b8673b6 | guard 熄火前按 transport 就绪分诊 | 行为 |
| 5 | ddfc6b9 | filter 重试冷等待独立预算 | 行为 |
| 6 | 66ee067 | 隐式初始 about:blank 不派发 onPageStarted | 行为 |
| 7 | de44369 | reload 加 commit-guard | 行为 |
| 8 | 428518d | churn 取证日志 | 日志 |
| 9 | 20b7f96 | 预运行时后台读有界等首个 controller | 行为 |
| 10 | e507a88 | 拆出 SessionRescue（纯搬迁） | 重构（零行为） |
| 11 | d26f514 | redirect leg 提交后有界 resume | 行为 |
| 12 | 2ef0d14 | 首个 provider 构造时预热 GPU+CONTENT | 行为（后 A/B 洗脱） |
| 13 | 108af90 | 预载 marker 日志 | 日志 |
| 14 | fe71c77 | guard 总重发封顶 | 行为 |
| 15 | 058afab | 删预载 marker 临时日志 | 日志 |
| 16 | 8d82ff4 | consult 闩（见过 necko 咨询只等不重发） | 行为 |
| 17 | abea95f | 可观测性日志 | 日志 |
| 18 | 45d5b72 | 中央分级日志门 SinytraLog | 日志门（factory 两处 init 调用，try 包住，行为不变，已读 diff） |
| 19–23 | 74fb08b e14f877 267f698 21880c4 1827bf4 | 日志切 SinytraLog 门（5 文件） | 纯机械迁移（逐 hunk 验：仅 Log→SinytraLog，无逻辑行） |
| 24 | f4710cf | 静默决策点补日志 | 纯加日志行 |
| 25 | 5fc8b60 | setprop override 改名 | 注释+属性名 |
| 26 | c83e72a | 20 处空 catch 补 fallback 日志 | 纯加（catch 内控制流不变，逐项验） |
| 27 | b6648b2 | 日志门跟 provider 自身 debuggable | 行为不变（`initLogGate` try 包住；新增一次 `getCurrentWebViewPackage` IPC，只影响时机，验过 diff） |
| 28 | 262a3ec | finish/title/progress 决策点补 D 日志 | 纯加 |
| 29 | 55ad663 | onAttachedToWindow 入口补 D 日志 | 纯加（1 行） |
| 30 | 77ceb11 | 闩住静默 16s 救援重发再转重开 | 行为（rescue/reopen，仅静默时开火） |
| 31 | 78aa07d | 隐式 blank 不再掐 guard 链只补预算 | 行为（删 blank 处 disarm） |
| 32 | 17463df | reopen 后重断言视图绑定 | 行为（仅 reopen 路径） |
| 33 | 8eb67b0 | 同 URL 连发合并（machine-gun 吸收） | 行为 |
| 34 | 79f0edd | shouldOverride 改走 Request overload | 行为 |

## 3. 逐轮记录（二分轮 R0–R10，用户点测）

全轮 `-29` 引擎（R0 起）、clean 构建、`install -r`、双 force-stop、
`MemAvailable` ≥ 4.4G、logcat 清零后点。URL 以 log 中 `loadUrl` 行为准。

| 轮 | 包（provider） | URL | 点测 | 判定 | 证据 log |
|---|---|---|---|---|---|
| R0 | `490ec28` | bing | 1 | **GREEN**（壁纸像素验过，PageStart cn.bing+PageStop+resumed=1） | `BISECT-R0-20261013-211958.log`（11M） |
| R1 | `797c240` | bing | 3 | **BLACK**（跳 cn.bing 可见，黑；仅 ANR 留存，余 rotation） | `BISECT-R1-20261013-212553.log`（1.4M） |
| R2 | `abea95f` [17/35] | example+bing | 双 | **GREEN** | `BISECT-R2-20261013-213051.log`（11M） |
| R3 | `17463df` [32/35] | bing | 3 | **BLACK**（跳 cn.bing 可见，黑） | `BISECT-R3-20261013-213630.log`（11M） |
| R4 | `78aa07d` [31/35] | bing | 3 | **BLACK**（同上；另 1 次系统 ANR） | `BISECT-R4-20261013-214050.log` |
| R5 | `77ceb11` [30/35] | bing | 3 | **BLACK**（同上） | `BISECT-R5-20261013-214452.log`（11M） |
| R6 | `abea95f` 复测 | bing | 2 | **GREEN**（PageStart+subresources；flake 排除轮） | `BISECT-R6-20261013-220045.log`（11M） |
| R8 | master 去 rescue（`dbb87c3`） | bing+example | 3 | **BLACK**（定罪失败轮，见 §5） | `BISECT-R8-20261013-220923.log`（1.3M） |
| R9 | R8 回退 `79f0edd` | — | 3 | **作废**（第三轮无 `loadUrl`，空轮；前两轮 rotation） | `BISECT-R9-20261013-233541.log`（1.4M） |
| R9b | 同 R9 包 | bing×3+example | 4 导航 | **BLACK**（coalesce 吃 reload 全程可见，见 §6） | `BISECT-R9b-20261013-233911.log` |
| R10 | R9b 回退 `8eb67b0` | bing | 3 | **BLACK**（machine-gun 回归 + SOE +  post-SOE DENY-lock，见 §6） | `BISECT-R10-20261013-235208.log`（1.3M） |
| R7 | （预留，未跑） | — | — | — | — |

注：R7（`77ceb11` 复测）在 R8 定罪失败后失去意义，未跑。
`17463df` 的 rebindView 与 kick（`15e707c/4b6d83a`）在全部黑轮 log 里
零开火（无 reopen 即无执行），已排除。

## 4. 关键证据摘录（行号级，可复核）

### 4.1 machine-gun 与 SOE（R0/R2/R6/R10 共有，成功与失败两边都有）

- SNC 点一次 GO，`onLoadRequest` 同 URL 同毫秒连发（R0：11717 行；R2：
  10137 行；R10-round-3：单轮 4114 周期的 SOE trace）。
- 栈：`ft.shouldOverrideUrlLoading → WebViewFragment.r0 → CSWebView.loadUrl
  → provider → bridge → NavigationBridge.onLoadRequest → ClientFanOut →
  ft.shouldOverrideUrlLoading → …`，8188KB 栈爆（`StackOverflowError`），
  每周期约 2KB（R0：4043 cycles；R5：3744；R10：4114——三者基本相等，
  per-cycle 开销无版本差，`per-cycle-stack` 假说已证伪）。
- SNC 反编译实锤（`snc.apk` pull + `dexdump`，见 §9）：
  `ft.shouldOverrideUrlLoading(WebView, WebResourceRequest)`（Request overload，
  日志 wrapper）→ `WebViewFragment.r0(String, Map, Z)` → 
  `WebView.loadUrl(String, Map)`。r0 内还有 `reload/stopLoading/destroy`，
  SNC 接管逻辑自洽：return true + 自己 reload。
- SOE 起点特征：死在 **打日志** 里（`SinytraLog.w/Log.w → printStackTrace →
  String.getChars`），即先有原异常，打日志时栈余量耗尽。原异常不可恢复
  （打不出来），只能从行为推断。

### 4.2 绿的形状（R0/R2/R6）：SOE → SNC 重入 → 1 秒 commit

R6-round-2（`BISECT-R6…log`）：
```
22:00:07.353 SOE
22:00:07.875 consult-latched cn.bing + pass-through
22:00:07.877 OnStartResponse bing.com 301
22:00:08.129 OnStartResponse cn.bing.com 200
22:00:11.743 PageStart cn.bing.com → subresources → commit
```
SOE 后 SNC 继续重入（R0：7240 行 post-SOE `loadUrl`），残通道 1–4 秒提交。
模型：`latch 冻结重发 → 存活通道提交`（R2/R6 的 guard 行为）。

### 4.3 黑的形状-A（R3/R4/R5）：SOE → 全停

以 R5-round-3（`BISECT-R5…log`，26677）为例：SOE 21:44:07.856 后 44 秒
零 consult、零 guard 行、零 PageStart；App 活着（主线程 epoll idle，
无 ANR、无崩溃）。SNC 不再重入。guard 无任何输出（连 base 的
refire/exhaust 都没有——arm 链已死，原因未明，见 §7.5）。

### 4.4 黑的形状-B（R1/R8/R9b/R10）：无 SOE，DENY-lock

- R1（`797c240`，含 `79f0edd`）：全轮仅 5 次 onLoadRequest，零 churn、
  零 SOE（Request 改道后 SNC string 实现永不触发，无 reload）。
- R8-round-3（`TYPED3…log`，18678，有完整窗口）：redirect（`isRedirect=true`）
  被 SNC override=true → `app override DENY` + disarm → 新 dispatch →
  200 → `OpenPromise resolving` → 静默。redirect-DENY 杀死 docshell 提交。
- R9b（回退 `79f0edd`、保留 coalesce）：`loadUrl coalesced duplicate` +
  `override=true → DENY`，零 SOE（coalesce 把递归掐在 2 次），零 commit。
  **coalesce 吃掉 SNC 补偿 reload 的全程可见证据。**
- R10-round-3（coalesce 也回退）：machine-gun 回归（4114 cycles）→ SOE →
  SNC 不重入 → Gecko 内部 consult 风暴（3624 次/228ms，全 true→DENY）→
  静默。DENY 风暴本身又通过 `onAppOverrideDeny → disarm` 把 guard 链
  毒死（每 consult 一次 disarm）。

### 4.5 启动时间线对照（HEAD 黑轮三轮，健康一致）

| | 32605 | 30664 | 10736 |
|---|---|---|---|
| proc 起 → provider create | +1.4s | +1.5s | +1.4s |
| create → runtime 就绪 | +1.8s | +3.4s | +1.9s |
| runtime → 首 handoff | +1.4s | +1.4s | +1.5s |
| 首 handoff → 点 URL | +7.4s | +7.3s | +7.6s |
| 死点 | nav-time handoff | nav-time handoff | nav-time handoff |

（旧包启动时间线缺失：31299 转储太晚，30731 未保住——这是本晚最大的
证据缺口，bisect 只能靠端点行为而非时序归因。）

## 5. 定罪与反转记录（按时间）

1. **`77ceb11` 定罪（R2 绿 → R3/R4/R5 黑）→ R8 反转**：R8 =
   master 去 rescue（`dbb87c3`，恢复 `abea95f` 版 guard），照黑。
   且 R8 黑轮里 rescue/reopen/rebind/kick 全零开火。77ceb11 洗脱。
2. **`79f0edd` 定罪（R1/R8 状）→ R10 部分反转**：回退它恢复 string 路由
   与 machine-gun，但 R10 照黑（post-SOE SNC 不重入 + DENY-lock）。
   它确实改变了行为（R1 的零 churn 就是它干的），但不是黑的充分条件。
3. **`8eb67b0`（coalesce）定罪（R9b 全程可见）**： busting 中。
   它把 SNC reload 压成单通道；单通道被 DENY 后永黑。这是全晚唯一
   **log 全程可见、无需推断** 的杀人链。R10 回退它之后 machine-gun
   回归，符合预测上半；下半（commit）未出现。
4. **`2ef0d14`（prewarm）洗脱**：A/B 开关对照轮死法逐帧复刻，已 revert
   （`43c0a36`），残留清零。
5. **GPU 洗脱（作为黑白判别因子）**：R0–R10 六轮 GPU 全部僵尸
   （各 2 行：出生 + attach 拒绝，零 Gecko 行），绿黑不分。
   GPU 健康与否不决定本系列 verdict（in-process 回退覆盖了渲染）。
   但 GPU 侧另有真问题未闭：fresh GPU transact wedge（早轮全 trace）、
   kill 后重建失败（`Failed to launch GPU process after 2 attempts`）、
   ART 初始化卡住（10888/15357 nativeloader 后静默），见 §8。

## 6. 最终机制模型（截至 2026-10-14 00:00，R10 之后）

```
SNC 点 GO → loadUrl → onLoadRequest → consult SNC string 实现
  → SNC return true + 自己 reload（machine-gun，递归）
  → 我们的 DENY 掉每一份拷贝（override=true）
  → ~4000 cycles 后 SOE（8188KB）
  → SOE unwind（经 ClientFanOut catch→false 的 ALLOW 尝试）
  → 分叉：
     A. SNC 继续重入（R0/R2/R6）→ 残通道 consult→200→commit（1–4s）→ 绿
     B. SNC 不再重入（R3/R4/R5/R8/R10）→ Gecko 内部 consult 风暴
        → 全 true → 全 DENY（+ 每次 DENY 附带 disarm 毒死 guard）
        → 静默 → 黑
```

- 分叉点（A vs B）在 **SNC 侧**（重入与否），我们的树里无对应代码；
  SOE unwind 落点决定 SNC 看到什么：落到 SNC tap handler（重试）vs
  落到别处（收手）。per-cycle 开销三轮基本相等，未找到系统性偏移。
- `override=true → DENY`（`3c74e5e`，全部版本共有）是黑的**必要**条件
  （没有它，风暴 consult 也会放行提交）；SNC 不重入是**充分**条件。
  修法方向（未实施）：override-true 后若 App 在 N 秒内无实际接管
  （无 reload、无 commit），按 allow 继续走自己的通道（"无人认领的
  takeover 不应等于黑屏"），只动 consult 决策，不碰引擎。
- 0035/0036 与本表正交：它们修的是 compositor 路径（wedge→park→kill→ANR），
  自落地后 log 里零 kill、零 ANR、零 10 秒 park（多轮验证），保留。

## 7. 理论证伪链（本晚踩过的坑，留档防重犯）

1. **撞运论**（n=1 下"暖/死"结论）：已 retract。`de926c1` 删概率叙事；
   本人另删两处（`21059f8`）：prewarm 注释 "~50%" 与 STATUS
   "同构建他轮全绿"——后者把"同包"偷换成"同条件"，是误读根源。
2. **僵尸堵路论**（limbo GPU 堵死绑定）：6093 反例（limbo 照渲染）证伪。
3. **prewarm 致死论**：A/B 开关逐帧复刻死法，洗脱，已 revert。
4. **wall-cap 有效论**：从未轮到开火（老 ladder 先到），留树未验证。
5. **kick 有效论**：post→postDelayed 修过 coalesce bug，但 reopen 本身
   在 verdict 轮里只出现过一次（typed02），像素未恢复；未验证。
   （`15e707c/4b6d83a` 在机，无验证——见 §8 遗留。）
6. **77ceb11 定罪**：R8 反转（去掉它照黑）。 arithmetic 曾干净，
   mechanism 从未成立（加法代码，零开火记录）。
7. **60 秒判死**：两次过早误判（20:12 黑屏轮实为慢提交；typed02 轮
   reopen 后 1 秒 commit）。 verdict 轮统一 2 分钟 + 像素为准。
8. **拒接恐慌**（`Ignoring finishAttachApplication`）：全天各轮都有，
   绿轮里更多；被拒进程照跑照绿。良性 race 噪音，不再追。

## 8. 遗留与建议（按优先级）

1. **P0——override-recovery**（§6 修法）：override-true + N 秒无接管
   → 按 allow 继续。覆盖 R1/R3/R4/R5/R8/R10 全部黑形状；绿轮不受影响
   （它们本就 commit）。1 轮验证。
2. **回滚评估**：`dbb87c3`（去 rescue）基于已死的定罪，建议 revert
   （恢复 MiniWV 黑洞恢复能力）；`3f87dbd`（去 Request 改道）与
   `1ef16e8`（去 coalesce）二选一重审——R10 证明单去 coalesce 不够，
   但两者叠加是否回到 R0 行为未知（R10 仍黑说明不够）。
   注意：R10 包 = abea95f-guard + {78aa07d,17463df} + kick，
   仍黑——`{78aa07d,17463df}` 从未被单独验证，悬置。
3. **Gecko 侧真问题**（与 SNC 黑屏正交，另案）：fresh-GPU transact
   wedge（0021/0035 已缓解毒化，未除根）、kill 后重建失败、
   ART 初始化卡住（10888/15357）。0035/36/37 保留（行为verified，
   日志门内，release 零影响）。
4. **方法论**：bisect 前先定"单死因"（本晚至少 3 个死法混跑：compositor
   wedge、redirect-DENY、post-SOE 静默）；SOE 高噪轮 dump 必须 60 秒内
   （spam 65 秒滚掉窗口）；用户轮统一 bing（R0 起）；空轮当场验
   `loadUrl`（R9 教训）。

## 9. 证据包清单（`Sinytra-apks/`，时间戳命名）

- `BISECT-R0…R10-*.log`：12 轮全量 logcat（含 3 轮会话过滤 `-<pid>.log`）。
- `OLD-solo-snc-20261013-201150.log`：旧包 solo 黑（machine-gun+kill）。
- `HEAD/WALLCAP/NOPREWARM/0035/0036/0037/NOZOMBIE/SLOW/TYPED*/KICK-*.log`：
  机制轮（0035 worker 解毒验证、0036 async 验证、0037 判别器验证、
  tombstone_11 符号化：3395 崩溃 = 上游 NEW_SURFACE fallback MOZ_CRASH，
  零补丁帧）。
- `provider-debug-490ec28-filescheme.apk`：旧包归档（5 包其一）。
- 截图：`r0.png`（R0 bing 壁纸像素）、`oldsolo01/now01`（旧包黑→转绿）、
  `typed0*.png`、`vfy60/slow01`（黑）、`refusal01/decouple01/async01/
  discrim01/canary-*.png`（canary 绿像素 ×6）。
- SNC 反编译：`snc.apk` + `snc-dex.txt`（679M）+ 结论：
  `ft.shouldOverride(Request)` → `WebViewFragment.r0(String,Map,Z)` →
  `WebView.loadUrl`（machine-gun 完整调用链，r8 混淆名已对上栈）。
- firefox 侧：`0035-handoff-worker-decouple{.md,.patch}`、
  `0036-resume-async{.md,.patch}`、`0037-handoff-discriminator{.md,.patch}`、
  快照 `-27/-28/-29`（`objdir-158/gradle/maven`）。
- 本仓提交（按序）：`2eb8f4f` wall-cap → `3de6c46/43c0a36` prewarm A/B+revert
  → `6b83052/9687f5b` 0035/36/37 patch 文件 → `21059f8` docs 去 flake 叙事
  → `15e707c/4b6d83a` kick → `dbb87c3` 去 rescue → `3f87dbd` 去 Request 改道
  → `1ef16e8` 去 coalesce（机上现版）。

## 10. 计分板（冻结，2026-10-14 00:10）

| 包 | SNC verdict |
|---|---|
| `490ec28` | 绿×4（31299 全 trace、用户自测×2、15774 十三秒）+ 黑×1（solo，慢/死未定） |
| `797c240` | 黑×3 |
| `abea95f` | 绿×3（R2 双 URL、R6×2） |
| `17463df/78aa07d/77ceb11` | 黑×3/×3/×3 |
| master 去 rescue（`dbb87c3`） | 黑（R8：redirect-DENY 状） |
| master 去 rescue+Request 改道 | 黑（R9 作废 1 + R9b：coalesce 吃 reload 实锤） |
| master 去 rescue+Request 改道+coalesce（`1ef16e8`，现版） | 黑（R10：machine-gun 回归 + SOE + DENY-lock） |
| canary（各包） | 绿×6+（像素验过；静态布局，不触发 SNC 状） |
