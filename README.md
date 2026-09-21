# 虚拟屏桌面 (VSLauncher)

一个用 **Shizuku** 授权、在 **Android 虚拟屏（Virtual Display）** 上运行任意应用的桌面工具。

不需要 root，只靠 Shizuku 提供的 shell(uid 2000) 权限，就能创建 overlay 虚拟屏、把应用启动到指定屏幕上，并配一个常驻悬浮球随时切换。

## 它能做什么

| 功能 | 说明 |
|---|---|
| **在虚拟屏打开任意应用** | 选中应用 → 自动创建虚拟屏 → `am start --display <id>` 把应用丢进去运行 |
| **悬浮球** | 可拖动的常驻悬浮窗，任意界面下都能呼出面板 |
| **自动返回桌面** | 启动应用后自动把前台切回本桌面，虚拟屏里的应用继续在后台跑 |
| **多屏管理** | 查看/创建/销毁虚拟屏，自定义分辨率与 DPI，查看每块屏的前台应用 |
| **应用列表** | 读取全部已安装应用，中文名优先，支持搜索与用户/系统应用分流 |
| **命令日志** | 所有 shell 命令与返回码都记录，出问题可自查 |
| **自启动** | 开机广播拉起，悬浮球常驻 |

## 原理

核心只有一条命令链，全部通过 Shizuku 以 shell 权限执行：

```bash
# 1. 创建虚拟屏
settings put global overlay_display_devices "720x1280/280"

# 2. 把应用启动到这块屏
am start --display 13 -n <package>/<activity>

# 3. 回桌面
am start -n com.vsbox.launcher/.MainActivity
```

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

1. 安装 APK，打开应用
2. 右上角点「Shizuku 未授权」→ 申请授权（Shizuku 会弹窗确认）
3. 在「应用」页点任意应用 → 自动建屏并启动 → 自动回桌面
4. 勾选「显示悬浮球」，之后任意界面点球即可呼出面板

### 悬浮球面板

- 回到本桌面
- 新建虚拟屏 / 销毁虚拟屏
- 快速打开最近用过的应用
- 收起面板 / 关闭悬浮球

### adb / Tasker 外部调用

```bash
# 在虚拟屏上打开指定应用
adb shell am start -n com.vsbox.launcher/.MainActivity --es vs_pkg com.example.app

# 其他开关
adb shell am start -n com.vsbox.launcher/.MainActivity --ez vs_create true   # 建屏
adb shell am start -n com.vsbox.launcher/.MainActivity --ez vs_ball true      # 开悬浮球
adb shell am start -n com.vsbox.launcher/.MainActivity --ez vs_destroy true   # 销毁虚拟屏
```

## 已验证行为

在 Android 11 真机上实测（读数取自 `dumpsys`，非界面假象）：

| 项目 | 结果 |
|---|---|
| 建屏 | `overlay_display_devices=720x1280/280` → `displayId=13, uniqueId='overlay:1'` |
| 启动到虚拟屏 | `Display #13` 分段中确实存在目标应用 |
| 自动回桌面 | 前台变回 `com.vsbox.launcher/.MainActivity` |
| 重复点创建 | 复用已有屏，屏数不增加 |
| 销毁 | 只清自己的配置，其他工具创建的虚拟屏不受影响 |
| 悬浮球 | 点击前后窗口数 1 → 2 → 1 |
| 崩溃 | `logcat -b crash` 无输出 |

## 已知问题

- 设备上的第三方清理工具可能强制杀掉本应用进程，建议加入电池优化白名单（应用内提供入口）。
- 部分设备的 Shizuku 是 fork 版本，若 `SystemServiceHelper.getSystemService("shizuku")` 拿不到 binder，代码已改用 `Shizuku.getBinder()` 兜底。

## 目录结构

```
app/src/main/java/com/vsbox/launcher/
├── MainActivity.java      # 主界面：应用列表 / 虚拟屏 / 日志
├── FloatingService.java   # 悬浮球前台服务
├── VScreen.java           # 虚拟屏创建、销毁、枚举、屏上应用
├── ShizukuShell.java      # Shizuku shell 执行层
├── Apps.java              # 已安装应用读取与中文名解析
├── Prefs.java             # 配置存储
├── Logger.java            # 命令日志
└── BootReceiver.java      # 开机自启
```

## License

MIT
