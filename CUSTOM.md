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
- release **自动保留最近 3 个**，旧的连 tag 自动删除（workflow 内置）
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
| 云构建流水线 | release+debug 双包、Gradle cache、pre-release 自动清理 |
| bootstrap 下载前置 | ndk-build 的 `.incbin` 需要 zip 先就位（上游任务顺序 bug） |
| bootstrap 版本 | 对齐上游 `2026.02.12-r1` + `%2B`/`apt.android-7` 编码 |

## 测试方法

- `bash ~/perf_test.sh <标签>`：大输出压测 4 项（ASCII/CJK × 基线/渲染），结果写 `~/perf_results_<标签>.txt`
- **噪声认知**：跨轮 ±10%（温度/后台），同轮 <3%。验收跑两轮，提升必须超噪声
- 性能改动纪律：先测量定位 → 最小改动 → 等价性验证 → 双轮实测，无效即撤
- debug 包才能 logcat 调试；release 包 `/proc` 受 hidepid 限制、进程不可见

## 已知注意事项

- 官方插件（Termux:API/Boot/Styling/Widget 等）因签名不同**无法安装**（sharedUserId 同签限制）
- 压测满载时隐藏键盘掉帧属正常（主线程饱和），非 bug
- 退出 app 前的会话转录、resize 走 TerminalBuffer 快路径——勿轻易动共享模块
- 上游同步：改动集中在 `app/` 与 `terminal-*/` 少数文件，宜每月小步 `git merge upstream/master`，勿攒大
