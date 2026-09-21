## 虚拟屏桌面 v1.0

用 **Shizuku**（免 root）在 Android **虚拟屏**上运行任意应用的桌面工具。

### 下载

- `vslauncher-v1.0-debug.apk` — 调试签名的安装包，可直接安装

### 前置条件

- Android 7.0 (API 24) 及以上
- 设备已安装并启动 [Shizuku](https://shizuku.rikka.app/)，并在应用内完成授权
- 悬浮球需要 `SYSTEM_ALERT_WINDOW` 权限

### 主要能力

- **在虚拟屏打开任意应用**：选中即建屏 + `am start --display <id>` 启动
- **常驻悬浮球**：可拖动，任意界面呼出面板（回桌面 / 建屏 / 销毁屏 / 快速启动）
- **自动返回桌面**：启动应用后前台自动切回本桌面，屏内应用继续后台运行
- **多屏管理**：自定义分辨率与 DPI，查看每块屏的前台应用
- **应用列表**：中文名优先、搜索、用户/系统应用分流
- **命令日志**：所有 shell 命令与返回码可追溯
- **开机自启**、支持 adb / Tasker 外部触发

### 实机验证（Android 11）

| 项目 | 结果 |
|---|---|
| 建屏 | `overlay_display_devices=720x1280/280` → `displayId=13, uniqueId='overlay:1'` |
| 启动到虚拟屏 | `Display #13` 分段中确实存在目标应用 |
| 自动回桌面 | 前台变回 `com.vsbox.launcher/.MainActivity` |
| 重复点创建 | 复用已有屏，屏数不增加 |
| 销毁 | 只清理自身配置，不影响其他工具创建的虚拟屏 |
| 悬浮球 | 点击前后窗口数 1 → 2 → 1 |
| 崩溃 | `logcat -b crash` 无输出 |

### 说明

- 部分深度定制 ROM 可能屏蔽 `overlay_display_devices`，若 `dumpsys display` 中看不到 `uniqueId='overlay:*'` 的屏幕即为不支持。
- 测试设备上的第三方清理工具可能强制杀掉应用进程，建议加入电池优化白名单。
- 部分设备 Shizuku 为 fork 版本，代码已用 `Shizuku.getBinder()` 兜底。
