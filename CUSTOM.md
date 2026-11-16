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

| 改动 | 后果 |
|---|---|
| 给 `setDraggerEdgeSize` 加"已应用则跳过"缓存 | **DrawerLayout 每次布局都会重置 edgeSize**，缓存会让侧栏边缘滑动永久失效 |
| 抽屉打开时暂停终端绘制 | 只能整块暂停（渲染器无"只画前 N 列"参数），`clipRect` 省不了 CPU |
| 在 `onDrawerOpened` 里 `clear()` + `addAll()` 重载列表 | **ToolItem 实例会被整体换掉**；任何在旧实例上保存的状态（角标、宽限期）都会写进"死对象"，必须按 id 重新查找当前实例 |
| 用 `state != null` 作为"这个工具有有效状态"的守卫 | 刚点开关的工具 `state` **就是** null（设计上如此），用状态是否为空来判断要不要迁移宽限期，会把最需要迁移的情况排除掉 |
| 只改 `state` 的迁移、不改 `startGraceUntil` 的迁移 | 两个字段独立演进，必须分别处理，否则角标颜色不一致（灰色 vs 红色） |
| 注释里写"避开了 API 26 的 `destroyForcibly()`"就以为安全 | **同一批还有 `waitFor(long, TimeUnit)`/`isAlive()`**。`desugar_jdk_libs` **不覆盖 `java.lang.Process`**。校验红线必须**剥离注释后**扫描真实调用 |
| `catch (Exception e)` 当兜底 | `NoSuchMethodError` 是 `Error`，不是 `Exception`。任何"可能链接失败"的调用都要 `catch (Throwable)` |
| 用 `pkill -f "sleep 30"` 清理残留 | `-f` 会匹配到**执行 pkill 的 bash 自身** → 把自己杀掉（exit 137）。必须用 `[s]leep 30` 规避 |

## 测试方法

- 性能回归用大输出压测（2 万行中文过终端 vs /dev/null 基线），历史脚本已随手清理，需要时按此思路重写即可
- **噪声认知**：跨轮 ±10%（温度/后台），同轮 <3%。验收跑两轮，提升必须超噪声
- 性能改动纪律：先测量定位 → 最小改动 → 等价性验证 → 双轮实测，无效即撤
- debug 包才能 logcat 调试；release 包 `/proc` 受 hidepid 限制、进程不可见

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
