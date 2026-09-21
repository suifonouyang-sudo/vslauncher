## 虚拟屏桌面 v1.1

### ⚠️ 破坏性变更：本版本不再创建虚拟屏

应用定位收窄为「**把应用启动到已有屏幕上**」。原先会自动/手动创建 overlay 虚拟屏的能力已全部移除，
应用不再改动 `overlay_display_devices` 这个全局设置（**销毁**功能仍保留）。

虚拟屏请先自行创建：

```bash
adb shell settings put global overlay_display_devices "720x1280/280"
```

多块屏用分号分隔，例如 `"720x1280/280;1280x720/280"`。

### 新增：打开应用前选择目标屏幕

点击任意应用后不再直接启动，而是弹出屏幕列表让你选：

- **物理屏**（`local:0`）
- **系统虚拟屏**（`overlay:*`）
- **第三方虚拟屏**（如 scrcpy 建的 `virtual:*`）

选中哪块就在哪块启动，当前选中的屏幕会标注「← 当前」。

### 其他变化

| 项目 | 变化 |
|---|---|
| 虚拟屏页 | 移除宽/高/DPI 输入框和「创建虚拟屏」按钮，改为展示建屏命令（可长按复制，应用不执行） |
| 悬浮球面板 | 移除「新建虚拟屏」按钮，保留回到本桌面 / 销毁虚拟屏 / 最近应用 |
| adb 入口 | 移除 `--ez vs_create`，新增 `--ei vs_display <id>` 直接指定屏幕 |
| 文档 | README 与《常见问题》更新，「如何建屏」提到最前面 |

### adb 调用示例

```bash
# 弹出屏幕选择框
adb shell am start -n com.vsbox.launcher/.MainActivity --es vs_pkg com.example.app

# 直接指定屏幕（适合 Tasker 等自动化）
adb shell am start -n com.vsbox.launcher/.MainActivity --es vs_pkg com.example.app --ei vs_display 13
```

### 真机验证（Android 11 / R10D）

| 项目 | 结果 |
|---|---|
| 界面无建屏入口 | uiautomator dump 确认；「销毁全部虚拟屏」保留 |
| 外部建屏后可识别 | 列出 `#0 local:0`、`#20 overlay:1` 等 |
| 选择屏幕后启动 | `Display #20` / `#21` 分段中确实存在目标应用 |
| 不指定屏时弹窗 | 触发后间隔 12s 才出现启动记录（等待选择），证实弹窗生效 |
| 自动回桌面 | 前台变回 `com.vsbox.launcher/.MainActivity` |
| 崩溃 | `logcat -b crash` 无输出 |

### 安装

- `vslauncher-v1.1-debug.apk` — 调试签名，可直接覆盖安装（versionCode 2）

要求 Android 7.0+ 且已安装并授权 [Shizuku](https://shizuku.rikka.app/)。
