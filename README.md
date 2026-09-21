# 虚拟屏桌面 (VSLauncher)

一个用 **Shizuku** 授权、把任意应用启动到 **指定屏幕**（物理屏 / 系统虚拟屏 / 第三方屏幕）的轻量桌面工具，并配一个常驻悬浮球随时切换。

不需要 root，只靠 Shizuku 提供的 shell(uid 2000) 权限。

## ⚠️ 本应用不创建虚拟屏

虚拟屏需要**先由外部手段建好**，应用只负责识别已有屏幕、把应用启动过去。建屏命令（任选一种执行）：

```bash
# 方式一：adb（USB 或无线调试）
adb shell settings put global overlay_display_devices "720x1280/280"

# 方式二：用 scrcpy 之类的工具创建
```

建好后回到应用的「虚拟屏」页点「刷新」，就能在列表里看到它。

这样设计的原因：改 `overlay_display_devices` 是全局系统设置，交给用户显式控制，应用不去动它。

## 它能做什么

| 功能 | 说明 |
|---|---|
| **打开应用前选屏幕** | 点应用 → 弹出屏幕列表（物理屏 / 系统虚拟屏 / 第三方屏）→ 选哪块就在哪块启动 |
| **悬浮球** | 可拖动的常驻悬浮窗，任意界面下都能呼出面板 |
| **自动返回桌面** | 启动应用后自动把前台切回本桌面，屏上的应用继续在后台跑 |
| **屏幕管理** | 列出全部屏幕、查看每块屏的前台应用、停掉屏上应用、销毁虚拟屏 |
| **应用列表** | 读取已安装应用（自动隐藏无启动入口的软件），中文名优先，支持搜索、用户/系统分流、一键刷新重新加载 |
| **命令日志** | 所有 shell 命令与返回码都记录，出问题可自查 |
| **自启动** | 开机广播拉起，悬浮球常驻 |

> 界面顺序：**虚拟屏**（首页）→ 应用 → 日志。应用列表只显示有桌面图标（可启动）的应用，无入口的系统组件/服务类软件默认隐藏；点「刷新」会重新扫描已安装应用。

## 原理

核心只有两条命令，全部通过 Shizuku 以 shell 权限执行：

```bash
# 1. 把应用启动到指定屏
am start --display 13 -n <package>/<activity>

# 2. 回桌面
am start -n com.vsbox.launcher/.MainActivity
```

可选屏幕列表来自 `dumpsys display`（解析 `DisplayViewport` 段，整行扫描会漏），
启动到哪一块由你在弹窗里选择。

`ShizukuShell` 通过 `IShizukuService.newProcess()` 起一个 shell 进程执行命令并回收 stdout / 返回码，等价于 `adb shell`。

## 环境要求

- Android 7.0 (API 24) 及以上
- 设备上需安装并启动 [Shizuku](https://shizuku.rikka.app/)，且在 App 内完成授权
- 悬浮球需要 `SYSTEM_ALERT_WINDOW` 权限

> 注意：`overlay_display_devices` 是 AOSP 的开发者选项机制，部分深度定制 ROM（如某些国产 ROM）可能屏蔽该设置项。若 `dumpsys display` 中看不到 `uniqueId='overlay:*'` 的屏幕，说明该 ROM 不支持。

## 编译

```bash
git clone https://github.com/suifonouyang-sudo/vslauncher.git
cd vslauncher
# 在 local.properties 里写你的 SDK 路径
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

要求 JDK 17+、Android SDK Platform 34。

## 使用

1. 先用上面的命令建好虚拟屏（**本应用不建屏**）
2. 安装 APK，打开应用
3. 右上角点「Shizuku 未授权」→ 申请授权（Shizuku 会弹窗确认）
4. 「虚拟屏」页点「刷新」，确认能列出你的虚拟屏
5. 「应用」页点任意应用 → **弹出屏幕列表，选一块** → 应用就在那块屏上启动 → 自动回桌面
6. 勾选「显示悬浮球」，之后任意界面点球即可呼出面板

### 悬浮球面板

- 回到本桌面
- 销毁虚拟屏
- 快速打开最近用过的应用
- 收起面板 / 关闭悬浮球

### adb / Tasker 外部调用

```bash
# 弹出屏幕选择框后再打开
adb shell am start -n com.vsbox.launcher/.MainActivity --es vs_pkg com.example.app

# 直接指定屏幕（跳过选择框，适合 Tasker 等自动化场景）
adb shell am start -n com.vsbox.launcher/.MainActivity --es vs_pkg com.example.app --ei vs_display 13

# 其他开关
adb shell am start -n com.vsbox.launcher/.MainActivity --ez vs_ball true      # 开悬浮球
adb shell am start -n com.vsbox.launcher/.MainActivity --ez vs_stop true      # 关悬浮球
adb shell am start -n com.vsbox.launcher/.MainActivity --ez vs_destroy true   # 销毁虚拟屏
```

## 已验证行为

在 Android 11 真机上实测（读数取自 `dumpsys`，非界面假象）：

| 项目 | 结果 |
|---|---|
| 屏幕枚举 | 解析出 `#0 local:0`（物理屏）、`#19 overlay:1`（虚拟屏）等 |
| 选择屏幕后启动 | `am start --display 19` 后 `Display #19` 分段中确实存在目标应用 |
| 自动回桌面 | 前台变回 `com.vsbox.launcher/.MainActivity` |
| 销毁 | 只清 `overlay_display_devices`，第三方工具建的屏幕不受影响 |
| 悬浮球 | 点击前后窗口数 1 → 2 → 1 |
| 崩溃 | `logcat -b crash` 无输出 |

## 已知问题

- 设备上的第三方清理工具可能强制杀掉本应用进程，建议加入电池优化白名单（应用内提供入口）。
- 部分设备的 Shizuku 是 fork 版本，若 `SystemServiceHelper.getSystemService("shizuku")` 拿不到 binder，代码已改用 `Shizuku.getBinder()` 兜底。

## 目录结构

```
app/src/main/java/com/vsbox/launcher/
├── MainActivity.java      # 主界面：应用列表 / 屏幕 / 日志，含屏幕选择弹窗
├── FloatingService.java   # 悬浮球前台服务
├── VScreen.java           # 屏幕枚举、启动到指定屏、销毁
├── ShizukuShell.java      # Shizuku shell 执行层
├── Apps.java              # 已安装应用读取与中文名解析
├── Prefs.java             # 配置存储
├── Logger.java            # 命令日志
└── BootReceiver.java      # 开机自启
```

## License

MIT
