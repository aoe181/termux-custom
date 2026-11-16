# termux-custom 维护手册

自定义 Termux fork。包名 `com.termux`（官方 pkg/apt 源完全可用），自有 keystore（`123.jks`）签名。
基线：termux/termux-app `v0.119.0-beta.3` + 上游 master（2026.02 版）合并。

---

## ⚠️ 第一要务：签名密钥备份（单点故障）

`123.jks`（alias / 密码均为 `123`）是整个项目的命根子：
**丢失 = 新版 APK 无法覆盖安装 = 只能卸载重装 = $HOME 全部丢失。**

- [ ] 至少两处异地备份（网盘 / 电脑 / 加密邮件附件），不要只放在手机上
- [ ] GitHub Secrets 已存一份（SIGNING_KEYSTORE_BASE64 等 4 个），但 Secrets 不可导出，不能当备份

**恢复 SOP（手机丢失 / 换机）**：
1. 从异地备份取回 `123.jks`
2. 在仓库 Settings → Secrets 还原 4 个签名 Secrets
3. 下载最新 release 的 APK 覆盖安装
4. 恢复 `$HOME` 备份（平时用 `tar` 定期打包）

---

## 构建与发版

- `git push origin master` → GitHub Actions 自动构建（约 3 分钟）→ 自动发 pre-release
- 产物两个 arm64 APK，**同签名可互相覆盖安装**，但不可与官方包共存：
  - `..._github-release_arm64-v8a.apk`：R8、非 debuggable、省内存（日常用）
  - `..._github-debug_arm64-v8a.apk`：debuggable，`htop`/`ps`/`logcat` 可见本体进程（调试用）
- release **自动保留最近 8 个**，旧的连 tag 自动删除（workflow 内置）
- 网络问题：push 偶发 `SSL_read: unexpected eof`，重试即可

## 改动清单（相对上游）

### 自研功能
| 改动 | 位置 |
|---|---|
| 快捷工具抽屉：右侧 30% 边缘滑出、ZDY.json 存储、增删改、标题栏按钮进入拖拽排序模式、镜像切换对话框（官方/清华/中科大/阿里云）、TUI 智能路由（busy 会话自动开新会话）、自动回车执行 | `TermuxActivity` / `ToolsAdapter` / `ToolStore` / `ToolItem` |
| 会话长按菜单（重命名 / 关闭会话） | `TermuxSessionsListViewController` |
| `forceRemoveTermuxSession`：绕过上游 `TermuxSession.finish()` 在 failed 状态提前返回的 bug（关闭会话因此真正生效） | `TermuxService` |
| 中文本地化 176 条（两模块，含 DOCTYPE 实体块） | `values-zh-rCN/strings.xml` ×2 |
| `fullscreen-mode=no\|status\|immersive` 属性（沉浸式含软键盘 workaround） | `TermuxPropertyConstants` / `TermuxActivity` |

### Android 15/16 适配
| 改动 | 说明 |
|---|---|
| 预测性返回 | `OnBackPressedCallback` + `enableOnBackInvokedCallback`，右抽屉也响应返回键 |
| 16KB 页大小 | `-Wl,-z,max-page-size=16384`（Android.mk + build.gradle） |
| 会话列表 ListView→RecyclerView | 含 `checkAndScrollToSession` 强转崩溃修复 |
| Edge-to-edge | 根布局 `fitsSystemWindows` 处理间距——**勿再加 setPadding，会双倍间距**（踩过坑） |

### 性能（实测：2 万行中文输出 1470ms → 500ms）
| 改动 | 说明 |
|---|---|
| WcWidth BMP O(1) 查表 | 替代二分查找；Python 全量验证 65536 码点等价 |
| getType 快速通道 | CJK/扩展A/韩文全分配块跳过 Unicode 库查询 |
| sixtel GC 逐字符→逐块 | 语义不变（自带 5 分钟节流） |
| 渲染 run 合并 | 宽度状态变化才断 run，连续中文整段一次绘制 |
| 抽屉反射 Field 缓存 | onGlobalLayout 每次布局不再重复 getDeclaredField |

### 渲染/布局再优化（2026-11-17，实测纯渲染 661 → 400 ms/s）

| 改动 | 提交 | 说明 |
|---|---|---|
| 非 ASCII 宽度缓存 | `863e9756` | `asciiMeasures` 只覆盖 0–126，中文每个码点每帧都调 `Paint.measureText`（JNI）。加 `SparseIntArray` 按码点缓存；`TerminalRenderer` 随字体/字号重建，缓存不会失效错位 |
| 跳过纯空白行 | `b6d6ad08` | `TerminalRow.isBlankForColumns()` 判定该行是否只有默认样式的空格；是则整行 `continue`。**必须排除**：光标行、选中行、反色屏幕、非默认背景/效果、**Sixel 位图**（`TERMINAL_BITMAP` 是 bit 15，而 `decodeEffect()` 只掩码 bit 0–8，看不到它，位图会消失——见 `b38a312d`） |
| 方向不再查 binder | `a01dcf6c` | `getDisplayOrientation()` 原来走 `WindowMetricsCalculator.computeMaximumWindowMetrics()`（binder IPC），从 `onGlobalLayout()` 每次布局都调（键盘弹收、抽屉开合、旋转）。改用 `Configuration.orientation` |

**实测方法（重要）**：必须把渲染从解析里**隔离**出来。
`printf > /dev/tty` 的速率被终端读入速率限制，而解析和绘制都在主线程，
采样 `/proc/<pid>/stat` 得到的是**两者之和**——用它对比新旧包会得出「无效」的错误结论。

- 测纯渲染：先刷满屏中文，然后**不再产生输出**，只反复触发重绘
  （`\e[C`/`\e[D` 光标移动，或 `\e[5~`/`\e[6~` 翻历史），**且必须限速**
  （sleep 5ms），否则同帧内多次 `invalidate()` 会被合并成一次 `onDraw`
- 采样：`/proc/<pid>/stat` 的 utime+stime，本机 `SC_CLK_TCK=100` → 1 jiffy = 10ms
- 单项必须跑够长（≥3 秒 / ≥300 jiffy），否则 10ms 粒度下噪声吞掉信号

结果：光标移动 661 → 411 ms/s（-37.8%），历史滚动 657 → 384 ms/s（-41.5%），
两次测量一致，远大于噪声。

### 已排除的性能候选（勿重复调查）

| 候选 | 实测/代码依据 |
|---|---|
| `TerminalRow.findStartOfColumn` 线性扫描 | 专项测量：写第 90 列 vs 第 2 列比值 0.93 / 1.06 ≈ 1.0，**不是瓶颈** |
| 外层 `switch (b)` 跳表 | 13 个 case、值域 0–27，ART 编译成 `tableswitch`，已是 O(1) |
| `WcWidth.width` | BMP 已是 O(1) 数组查表 |
| `terminal-transcript-rows` 调小 | 行对象懒分配（`allocateFullLineIfNecessary`），只影响 resize 遍历，收益极小 |
| `ByteQueue` | 64KB 环形缓冲，`System.arraycopy` 搬运，无额外分配 |
| Message 调度 | 每 4KB 一次 `sendEmptyMessage`，24000 字节仅 6 次调度 ≈ 18µs |
| 会话列表 `notifyDataSetChanged` | 最多 8 项，全量 rebind 可忽略 |
| 光标闪烁 / 无障碍 | 默认值分别是 `0`（禁用）和未开启，已是最优 |
| `render()` 里的对象分配 | `onDraw` 已是零分配 |

**剩余真实开销**（改不动或代价高）：解析 180 ns/字节 ≈ 5.6 MB/s 吞吐（健康水平），
纯渲染 400 ms/s。「输出时停顿几秒」的成因是一次性输出几 MB 把主线程队列填满，
属于处理该数据量的固有成本；唯一可动的是降低重绘频率，但会牺牲实时性。

### 构建/工程
| 改动 | 说明 |
|---|---|
| 云构建流水线 | release+debug 双包、Gradle cache、pre-release 自动保留最近 8 个 |
| bootstrap 下载前置 | ndk-build 的 `.incbin` 需要 zip 先就位（上游任务顺序 bug） |
| bootstrap 版本 | 对齐上游 `2026.02.12-r1` + `%2B`/`apt.android-7` 编码 |

### 工具抽屉强化（2026-11-15）
| 改动 | 说明 |
|---|---|
| 状态开关工具 | `checkCommand`（退出码 0=开）+ 三色状态角标；检查进程注入 PATH/HOME/PREFIX |
| 镜像对话框修复 | 按主机名检测当前源（🟢 标记）、修正阿里云 404 路径、切换 sed 兼容路径变体、锚定 `deb` 防注释干扰 |
| 容错加固 | ZDY.json 单条损坏只跳过该条（不整体重置）；整文件损坏保留现场不覆盖 |
| 执行路由 | `runDrawerCommand()` 统一 TUI 智能路由；死会话（已结束）自动开新会话 |
| 拖拽修复 | 非相邻拖动乱序（swap → 逐步交换） |
| 关闭会话 | 非当前会话关闭不再跳转视图；通知栏会话计数即时刷新 |

### 工具抽屉交互（2026-11-16）
| 改动 | 说明 |
|---|---|
| 管理模式 | 标题栏 ≡ 进入：**点图标=编辑**、**长按=拖动排序**、按钮变 ✓；关闭抽屉自动退出并落盘。正常模式点=运行，长按不响应 |
| 角标不闪 | 开抽屉重读 ZDY.json 时按 `(id, checkCommand)` 把旧 `state` 搬过去，不再全变灰 |
| 检查去重 | 按工具 token 去重：在途检查复用不叠加进程；点开关只作废该工具的旧结果 |

### 稳定性与数据安全（2026-11-16）
| 修复 | 说明 |
|---|---|
| 编辑框不丢输入 | `setPositiveButton(null)` + `setOnShowListener`，校验失败时对话框不关闭（默认行为会关并丢光输入） |
| ZDY.json 损坏备份 | 解析失败时先存 `ZDY.json.corrupt`，避免下一次 save 永久覆盖原内容 |
| 原子写盘 | 写 `.tmp` + `renameTo()`，中断写不会留下半截文件 |
| 会话上限保护 | 会话满 8 个时 `addNewSession` 静默失败，原逻辑会把命令打进当前 TUI；加 `newSession == session` 判定 |
| 检查去重(按工具) | token 按工具而非全局，点一个开关不再作废其它工具的检查结果；在途检查复用不叠加进程 |
| 删除确认 | 删工具二次确认（命令可能是很长的单行） |
| 添加后滚动 | 新工具追加到末尾并滚动到可见 |
| 保存失败提示 | `ToolStore.save()` 返回 false 时 Toast 提示 |
| API 24 兼容 | 移除 `java.nio.file.Files`（API 26），改用 `java.io`/`FileUtils`；`NoClassDefFoundError` 不被 `catch(Exception)` 捕获 |
| 慢启动宽限 | 点开启后 10s 内"检测为关"显示灰色而非红色；仅开启方向生效，10s 后安排一次纯重绘 |
| 单条损坏不再丢失 | `fromJson` 补 `id`/`icon`/`checkCommand` 默认值，只有 name+command 都空才丢弃（原行为：跳过该条 → 下次 save 永久消失） |
| 检查零孤儿 | 检查命令用 `timeout -k 1 2` 外包，超时杀整个进程组；避免 `Process.pid()`/`destroyForcibly()`（API 26，minSdk 24） |
| 命令 trim | 末尾换行会被 `paste()` 转成 `\r` 导致提前执行；`command` 与 `name` 一起 trim |
| 空命令不加回车 | `runDrawerCommand` 对空命令早退，否则 `paste("")` + `write("\r")` 会在 TUI 里误确认 |
| 全屏冲突修复 | `fullscreen-mode=no` 时若已有 `FLAG_FULLSCREEN` 则跳过 `show(bars)`，避免两个设置互相抵消 |
| Handler 复用 | 单个 `mMainHandler` + `onDestroy` 里 `removeCallbacksAndMessages(null)` |
| 拖拽保存提示 | 排序落盘失败时 Toast（原来静默） |
| 角标不再卡灰 | 检查结果写进**当前显示的实例**（`findLiveTool(id)`）；原逻辑写进 lambda 捕获的旧实例，而开抽屉会重载列表换掉实例 → 角标永远灰 |
| 宽限期迁移修正 | 宽限期与 `state` **解耦**分别记录；原判断 `state != null` 恰好排除了唯一需要迁移宽限期的工具（刚点的开关 state 就是 null） |
| 新建工具无删除键 | 新建对话框的"删除"按钮隐藏（原为可见的死按钮） |
| **API 24 崩溃修复** | `Process.waitFor(long, TimeUnit)` 是 **API 26**（与 `destroyForcibly()`/`isAlive()` 同批），minSdk 24 上抛 `NoSuchMethodError`（`Error`，`catch(Exception)` 抓不到）→ 检查线程未捕获异常 → **Android 杀进程，App 闪退**。改用 `exitValue()` 轮询（API 1），并把 `catch` 改为 `catch (Throwable)` 让任何链接错误降级为"状态未知" |
| 语义损坏也备份 | `listFromJson` 在"数组非空但一条都没解析出来"时返回 null → 走 `.corrupt` 备份路径；原行为返回空列表 → 抽屉空白 → 下次 save 把用户原文件覆盖成 `[]`，**无任何备份** |
| 改检查命令后重新检查 | 编辑开关工具的 checkCommand 时清掉在途检查槽位，否则旧命令的结果被丢弃、新命令又不检查 → 角标灰到下次开抽屉 |
| 销毁后不碰视图 | 检查结果用 `mMainHandler.post` 而非 `Activity.runOnUiThread`（后者走私有 handler，`onDestroy` 清不掉） |
| 管理模式按钮语义 | 进入管理模式时 contentDescription 改为"退出管理模式"（原来一直是"管理工具"） |
| 死代码清理 | 删除无调用方的 `TermuxSessionsListViewController.notifyDataSetChangedSafely()` |

## 已踩过的坑（勿重犯）

### 抽屉边缘尺寸：为什么必须每次布局重应用（已用源码证实）

`androidx.drawerlayout:drawerlayout:1.2.0` 的 `DrawerLayout.onLayout()` 末尾：

```java
if (sEdgeSizeUsingSystemGestureInsets) {        // Build.VERSION.SDK_INT >= 29
    Insets gestureInsets = rootInsets.getSystemGestureInsets();
    mLeftDragger.setEdgeSize(Math.max(mLeftDragger.getDefaultEdgeSize(), gestureInsets.left));
    mRightDragger.setEdgeSize(Math.max(mRightDragger.getDefaultEdgeSize(), gestureInsets.right));
}
```

`ViewDragHelper`（`androidx.customview:customview:1.1.0`）里有**两个独立字段**：

- `mDefaultEdgeSize` —— 内置默认值，`setEdgeSize()` 永远改不动它
- `mEdgeSize` —— 当前值，`setEdgeSize()` 改的是它；`getEdgeSize()` 返回它

所以每次布局都会把 `mEdgeSize` **重置回** `max(内置默认值, 手势区 inset)`，我们设置的大边缘区被彻底抹掉。
**因此"每次布局重新 apply"是必需的，不是冗余。**

验证方式（无需联网源码，随时可复现）：

```bash
# 取我们实际使用的版本源码
curl -sLO https://dl.google.com/dl/android/maven2/androidx/drawerlayout/drawerlayout/1.2.0/drawerlayout-1.2.0-sources.jar
unzip -o -q drawerlayout-1.2.0-sources.jar && grep -n -B4 -A12 setEdgeSize androidx/drawerlayout/widget/DrawerLayout.java
```

| 改动 | 后果 |
|---|---|
| 给 `setDraggerEdgeSize` 加"已应用则跳过"缓存 | **DrawerLayout 每次布局都会重置 edgeSize**，缓存会让侧栏边缘滑动永久失效（已用 1.2.0 源码证实，见下） |
| 抽屉打开时暂停终端绘制 | 只能整块暂停（渲染器无"只画前 N 列"参数），`clipRect` 省不了 CPU |
| 在 `onDrawerOpened` 里 `clear()` + `addAll()` 重载列表 | **ToolItem 实例会被整体换掉**；任何在旧实例上保存的状态（角标、宽限期）都会写进"死对象"，必须按 id 重新查找当前实例 |
| 用 `state != null` 作为"这个工具有有效状态"的守卫 | 刚点开关的工具 `state` **就是** null（设计上如此），用状态是否为空来判断要不要迁移宽限期，会把最需要迁移的情况排除掉 |
| 只改 `state` 的迁移、不改 `startGraceUntil` 的迁移 | 两个字段独立演进，必须分别处理，否则角标颜色不一致（灰色 vs 红色） |
| 注释里写"避开了 API 26 的 `destroyForcibly()`"就以为安全 | **同一批还有 `waitFor(long, TimeUnit)`/`isAlive()`**。`desugar_jdk_libs` **不覆盖 `java.lang.Process`**。校验红线必须**剥离注释后**扫描真实调用 |
| `catch (Exception e)` 当兜底 | `NoSuchMethodError` 是 `Error`，不是 `Exception`。任何"可能链接失败"的调用都要 `catch (Throwable)` |
| 用 `pkill -f "sleep 30"` 清理残留 | `-f` 会匹配到**执行 pkill 的 bash 自身** → 把自己杀掉（exit 137）。必须用 `[s]leep 30` 规避 |
| 用「刷屏时 `/proc/<pid>/stat` 的 CPU」验证渲染优化 | 解析和渲染**都在主线程**，采到的是两者之和。渲染只占一部分，相对改善被背景量淹没 → 必然得出「无效」的错误结论。必须隔离：静止画面下反复触发重绘 |
| 不限速地连续发 `\e[C` 触发重绘 | Android 会把**同一帧内**的多次 `invalidate()` 合并成一次 `onDraw`。不限速测到的是「解析几千个光标移动 + 画很少几次」。每次之间 `sleep 5ms` |
| 用 10ms 粒度（1 jiffy）采样却只跑 100ms | 只有 10 个刻度，噪声 ±1 = ±10%，实测波动可达 80%。单项必须跑够 ≥3 秒（≥300 jiffy） |
| 判定 `isBlankForColumns()` 时只看 `decodeEffect()` | **`TERMINAL_BITMAP` 是 bit 15，而 `decodeEffect()` 只掩码 bit 0–8**，看不到位图标记；位图样式把 `(num,x,y)` 塞在颜色位里，可能正好等于默认前景/背景 → 整行被跳过、**Sixel 图片消失**。必须先 `isTerminalBitmap()` 显式排除 |
| 凭代码推断「三次线性扫描 = O(n²) 是瓶颈」 | 实测写第 90 列 vs 第 2 列比值 ≈1.0。一行只有 100 列，扫描 100 个元素是纳秒级，相对每次写入的固定开销可忽略。**代码里看起来慢的，在真实数据量下未必慢；先测再改** |
| 对比「短行 vs 长行」时只对齐字节数 | 还漏了两个变量：中文字数（长行 1.32x）和换行次数（短行 19x）。三者方向还互相矛盾。控制变量必须逐项核对，不能只挑一个看起来关键的 |

## 测试方法

- 性能回归用大输出压测（2 万行中文过终端 vs /dev/null 基线），历史脚本已随手清理，需要时按此思路重写即可
- **噪声认知**：跨轮 ±10%（温度/后台），同轮 <3%。验收跑两轮，提升必须超噪声
- 性能改动纪律：先测量定位 → 最小改动 → 等价性验证 → 双轮实测，无效即撤
- debug 包才能 logcat 调试；release 包 `/proc` 受 hidepid 限制、进程不可见

### 采样终端 CPU 的正确姿势

本机无 `adb`/`dumpsys gfxinfo`，只能用 `/proc/<pid>/stat`：

```sh
pid=$(pidof com.termux | awk '{print $1}')
awk '{print $14 + $15}' /proc/$pid/stat   # utime+stime, 单位 jiffy
```

- 本机 `SC_CLK_TCK=100` → 1 jiffy = 10ms，**分辨率很粗**
- 单项必须跑够 ≥3 秒（≥300 jiffy），否则噪声吞掉信号
- 只能读到 `com.termux` **进程**的 CPU，含解析+渲染+UI，无法细分
- **必须在真实终端里跑**：通过其他程序（如 opencode 的 shell）发送输出时，
  Termux 不绘制，采到的 CPU 会是 0

**隔离被测阶段**是这类测量的关键：

| 想测 | 做法 |
|---|---|
| 纯渲染 | 先刷满屏，再**停止输出**，只反复触发重绘（`\e[C`/`\e[D`、`\e[5~`/`\e[6~`），每次 `sleep 5ms` 避开帧合并 |
| 纯解析 | 固定字节数输出，对比「有无换行」「行长短」；每项总字节数必须相同 |
| 单位成本 | 固定输出 500KB，算 ns/字节（ASCII 无换行实测 180 ns/字节 ≈ 5.6 MB/s）|

## 上游同步指南

本地已挂好 remote：`upstream` = termux/termux-app。

**改动结构与冲突面**：新增 8 文件（零冲突）+ 修改 21 文件，真正的冲突热点约 8 个：
`TermuxActivity.java`、`activity_termux.xml`、`TerminalEmulator.java`、`TerminalRenderer.java`、
`WcWidth.java`、`strings.xml`、`app/build.gradle`、`AndroidManifest.xml`。

- **小提交**：不碰上述文件的直接 `git cherry-pick` 或 merge（零冲突）；碰了的冲突也只限该提交的改动块。
  注意 cherry-pick 后历史分叉，之后全量 merge 会再冲突一次——紧急修复才用，用完尽快 merge 归拢。
- **大版本**：预估冲突 3-8 个文件、每个几行到几十行，半天量级。只有上游大重构（重写核心类）才会"改特别多"。
- **降痛铁律**：每月小步 merge、别攒大——冲突量和分叉时长成正比。

**标准流程**：
```sh
git fetch upstream
git log --oneline HEAD..upstream/master -- app/src/main/java/com/termux/app/TermuxActivity.java  # 侦察重叠
git merge upstream/master        # 解冲突对照下方改动清单（每处"为什么改"都有记录）
# 回归: 工具抽屉/开关角标/会话管理/镜像 + bash ~/perf_test.sh <标签>
```

## 已知注意事项

- 官方插件（Termux:API/Boot/Styling/Widget 等）因签名不同**无法安装**（sharedUserId 同签限制）
- 压测满载时隐藏键盘掉帧属正常（主线程饱和），非 bug
- 退出 app 前的会话转录、resize 走 TerminalBuffer 快路径——勿轻易动共享模块
- 上游同步：改动集中在 `app/` 与 `terminal-*/` 少数文件，宜每月小步 `git merge upstream/master`，勿攒大
