# COOLAPK — 酷安黑屏主要任务交接页

> 主要任务之一（2026-10-08 用户定）：酷安帖内外部链接进 WebViewActivity
> 长时间黑/白屏不绘出。注意：是主要任务、不是唯一任务——P-1→P0→P1→P2、
> CTS 对照、a11y v2 等路线照 `ROADMAP.md` 继续并行，本页只覆盖酷安这条线。
> WEBVIEW TEST 崩溃只是顺手修的（`490ec28`），不要和主任务混淆。
> 本页 = 测试方法 + 诊断现状，给新会话接手用。
> 进展 ledger 仍在 `STATUS.md` §1aa，本页只留"怎么做 + 到哪了"。

## 1. 测试方法（固定流程，直接照抄）

目标页：用户帖子"Kitsune Mask 轻度美化"里的`[链接]查看链接` →
`https://github.com/Bemly/kokoro-no-kitsune/releases/tag/27001b`
（重 JS 的 release 页，天然 slow-network 放大器）。

```bash
S=adb\ -s\ V885Q49L8TAMFEEE
# 0) 卫生（DEVICE.md §7）+ 防短信弹窗抢焦点（实测踩过两次）
$S shell "am force-stop com.coolapk.market; logcat -c; \
  am force-stop com.google.android.apps.messaging"
$S shell "monkey -p com.coolapk.market -c android.intent.category.LAUNCHER 1"
sleep 20
# 1) 我的 → 44动态 → 浏览历史首条 → 帖子详情（每步后建议 dump 核对，
#    坐标是 1080x2400 下的，换分辨率重取）
$S shell "input tap 913 2230"   # 我的（底部栏）
sleep 5
$S shell "input tap 130 400"    # 44 动态
sleep 6
$S shell "input tap 540 1990"   # 浏览历史首条卡片（y 按实际 dump 调）
sleep 6
$S shell "input tap 380 780"     # 查看链接（URLSpan，无独立节点，估算点）
```

判定坐标是否点中：`logcat | grep onLoadRequest` 3 秒内有没有
`coolapk.com/link?url=`。没有 = 点偏，重截屏定位（`screencap` 看链接
实际 y），不要盲扫。

## 2. 观测手段（一轮开一次 log，别反复清）

```bash
# 全量落盘（logcat 缓冲会转，关键轮必须 pull 回本地慢慢 grep）
$S shell "logcat -d -v time > /sdcard/cool.log"
$S pull /sdcard/cool.log /tmp/cool.log
# 三连截图（截图纪律：≥3 张、间隔 4–5s；a11y dump 判 DOM 有无）
$S shell "screencap -p /sdcard/c.png; uiautomator dump /sdcard/c.xml"
# 活体连接（uid 10037 = coolapk；状态机比 log 诚实）
$S shell "cat /proc/net/tcp 2>/dev/null | awk '\$8==10037'  #IPv4"
$S shell "cat /proc/net/tcp6 2>/dev/null | awk '\$8==10037' #IPv6/v4-mapped"
# 流量（整机级，只能定性不能归因到进程；要归因看上面按 uid 的）
$S shell "su -c 'cat /sys/class/net/wlan0/statistics/rx_bytes'"
```

关键 log 行（tag 全是 `Sinytra/<模块>`，pid 过滤宿主）：

| 看到什么 | 含义 |
|---|---|
| `onLoadRequest <url>` | 导航到达 Gecko（含 guard 重发，注意数次数） |
| `dispatching ResponseFilters` / `filters pushed: 1` | 拦截表下发成功与否（无 pushed = bring-up 输了 race） |
| `ShouldPrepare HIT <url>` → `pass-through/synthesize` → `OnSuccess` | **咨询与裁决，不是字节！** OnSuccess = 决策完成，零字节含义 |
| `PageStart uri=<http>` | commit（响应头到）。github 主文档无此行 = 头都没到 |
| `LocationChange` / `PageStop` / `ProgressChanged` | 有 Location 无 PageStart = 导航开始但未提交 |
| `cookie query timed out after 5000ms` | storage 答案没回来（5s 定时，看连续性：偶发 vs 连发） |
| `commit guard firing attempt=N` / `extending` / `exhausted` | guard 行为（新构建有 extending = 进展感知生效） |
| `ensureBuiltIn no ack yet, retrying` / `installed` | transport bring-up（首 ack 曾 32s 才到） |
| `flushing N pending cookie writes` | B 机制生效（预运行时写入入 jar） |
| `commit guard exhausted but transport not ready; waiting (cold M/4)` | 熄火分诊生效（等，不烧 reopen） |
| `ANR in ... coolapk` | 主线程被 park（M1 前是 storage latch，现应无） |
| a11y dump 零页面文本节点 | parser 饿死（DOM 空），不是合成器问题 |

## 3. 判定矩阵（别再从头推一遍）

- 点标签/帖子即崩 + `AbstractMethodError` → hidden Impl 缺失家族
  （已修：file-scheme 两枚，见 `490ec28`；以后 framework static 壳 +
  `*Impl` 的 API 一律先去 device framework.jar dexdump 核对）。
- 黑屏 + 标题栏有标题 + spinner：redirect 已提交，目标 commit 没到。
- 白屏 + 标题栏无标题：连 redirect 都没提交（多为 bring-up 全死轮）。
- 白屏 + `PageStart redirect` 有 + 目标无：目标主文档头没到。
- `OnSuccess` 成堆但无 `PageStop`/无像素：只是裁决快，字节没到（2026-10-08 全天最大误判点，已交学费）。
- 状态栏网速：瞬时采样，垃圾指标；以 wlan rx 差值（分级：KB/s 级
  trenches）+ 45 秒活体连接状态机为准。
- `app_webview/` 只有 14K = bromite 无盘缓存（对照有效）；我方 profile
  在 `/data/data/<host>/files/mozilla/*.default/`，cookies.sqlite 可
  `cp` 出来本地 python-sqlite3 查 jar 落盘（副作用鉴别法）。
- 切 WebView 对照（bromite）前先 `dumpsys webviewupdate` 复核 Current，
  跑完必须切回（2026-10-08 实锤忘切，被用户当场抓获）。

## 4. 已修（新会话不要重修，harness 47 PASS 行 0 FAIL / JVM 216 绿守着）

1. `490ec28` file-scheme gate（崩溃）。
2. `328a7b8` 主线程 void-set fire-and-forget（25s ANR）。
3. `8d0f537` pre-runtime 写排队（登录态）。
4. `dca400c` ensureBuiltIn bounded 重试（bring-up）。
5. `a910904` 0025 ged stub 不投毒（omni 三级解包验证）。
6. `cee3e7e` guard 咨询即延长（慢网不重下）。
7. `b8673b6` 熄火分诊（cold 4/4 等待，不烧 reopen）。
8. `ddfc6b9` filter 冷预算（15.5s→63.5s，对齐 install 60s 窗口；31s bring-up 轮实证救活）。
9. `66ee067` 隐式初始 about:blank 不派发 onPageStarted（酷安 getQueryParameter 崩→WebView 永不挂载→永久黑；修后 dumpsys 挂载确认，零 threw）。
10. `de44369` reload 加 commit-guard（harness 47 行 0 FAIL 验证；单测 210 绿）。
11. `428518d` churn 取证日志上机（LR 四字段 + override/loadUrl 配对；下轮绿管线直接判 R1/R2）。
12. `20b7f96` 预运行时后台读有界等首 controller（主线程保持诚实空；单测 216 绿）。
13. churn 判 R1（18 发全非 redirect、宿主 loadUrl 仅 1 发；R2 死；R5 早已证伪）。
14. bridge 拆分：`SessionRescue.java` 387 行，bridge 1238→972（harness 47 行 0 FAIL）。
15. `764750e` 死进程取证门上机（MOZ_LOG M1-M4 + geckoview.logging；Canary 双行验门通过）。

## 5. 未闭（新会话从这里开工，按序）

0. **像素 verdict（首位）**：修后轮视图已挂载但撞 trickle 窗（0.7KB/s），等一个"修复+带宽"双齐窗口（本轮保持加载中）。对照纪律：以后每次对照必带整机 rx delta + 交替顺序（此前"同分钟"对照无窗口会计是漏洞；时变带宽实锤 86 倍摆动）。
0b. **死进程捕获（取证门已开，就等一轮死的）**：fresh 轮若 25s 内无 install ack + guard 进 cold 扩展，即死轮嫌疑——落盘整轮 `-b main`（MOZ_LOG 体积大，只取 main buffer），对照 Canary 健康基线找第一个 diverge 点（M1 DocumentChannel 有无 ctor）。verdict 轮取证门保持 OFF，别忘关回去。

1. **部分 fresh 酷安进程 Java→Gecko-JS 全死**（extension/filter/storage
   零回答，0025 亦救不回；session 回声正常；同构建他轮全绿）。
   已排除：v6（main 表 `unreachable` 速败）、UA（curl 双 UA 同速）、
   缓存（bromite 盘空）、guard 谋杀（新代码只等不杀）。
   怀疑：冷进程 early-window 派发丢失的常驻态版本（重启/双 runtime/
   stale peer）。子 agent 行号级溯源结论在 STATUS §1aa（候选排序 +
   证伪预测可直接用）。
2. **release 页绘制**：管线绿轮（12:55：零超时/filter×3/PageStart/
   79 同秒裁决）仍 4 分钟黑，a11y 空，网 0.1–1KB/s。bromite 同分钟
   1.5MB 绘出 vs 我方 1.7MB 纯白（整机计数器，归因不完全干净）。
   终裁条件：健康网络 + fresh 轮一次绘出即关案；否则上 MOZ_LOG
   （necko channel 状态，config 加 pref 即可，不用重编 Gecko）。
3. **债务**：`GeckoSessionBridge.java` 1126→1190 行（超 1000 铁律，
   动前已超，拆分单独立项）；`:web` 后台线程预运行时 cookie 调用
   （§1z 项，B 只修了 void 写，读侧仍诚实空值）。
