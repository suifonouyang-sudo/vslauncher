import subprocess, re, time, sys

ADB = r"C:\Android\Sdk\platform-tools\adb.exe"
S = sys.argv[1] if len(sys.argv) > 1 else "1B677DECO1"
PKG = "com.vsbox.launcher"
APKD = r"C:\Users\随风\WorkBuddy\2026-09-21-17-46-36\VSLauncher\app\build\outputs\apk\debug"


def run(args, timeout=180, cwd=None):
    return subprocess.run([ADB, "-s", S] + args, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=timeout, cwd=cwd).stdout


def sh(cmd, timeout=180):
    return run(["shell", cmd], timeout)


def show(t, b):
    print("\n== %s ==" % t)
    print(b.strip())


def logs(n=8):
    return sh("run-as %s cat files/vs.log 2>/dev/null | tail -%d" % (PKG, n))


# 清场：销毁上一次测试残留的 overlay 屏（不动第三方 scrcpy 屏）
sh('settings put global overlay_display_devices ""')
time.sleep(4)
print(run(["install", "-r", "-t", "app-debug.apk"], cwd=APKD).strip())
sh("am force-stop " + PKG)
time.sleep(2)

# 1. 建屏
show("1. 创建虚拟屏", sh("am start --display 0 -n %s/.MainActivity --ez vs_create true" % PKG))
time.sleep(12)
show("overlay 配置", sh("settings get global overlay_display_devices"))
displays = sh("dumpsys display | grep -oE \"displayId=[0-9]+, uniqueId='[^']*'\"")
show("显示屏", displays)
ov_ids = [int(m.group(1)) for m in re.finditer(r"displayId=(\d+), uniqueId='overlay:", displays)]
print("overlay ids =", ov_ids)
if not ov_ids:
    print("!! 建屏失败")
    sys.exit(1)

# 2. 重复点创建 —— 不应再多出一块
show("1b. 再次点创建（应复用，不新增）", sh("am start --display 0 -n %s/.MainActivity --ez vs_create true" % PKG))
time.sleep(8)
displays2 = sh("dumpsys display | grep -oE \"displayId=[0-9]+, uniqueId='overlay:[^']*'\"")
show("overlay 屏（应仍是 1 块）", displays2)
show("日志", logs(6))

ov_id = ov_ids[0]

# 3. 在虚拟屏打开应用
show("2. 在虚拟屏打开 ADB WiFi", sh("am start --display 0 -n %s/.MainActivity --es vs_pkg com.reathin.adbwifi" % PKG))
time.sleep(16)
show("日志", logs(10))
show("Display #%d 上的任务" % ov_id, sh("dumpsys activity activities | grep -A5 'Display #%d'" % ov_id)[:800])
show("前台（应为本应用）", sh("dumpsys activity activities | grep -m1 ResumedActivity"))

# 4. 悬浮球
show("3. 悬浮球", sh("am start --display 0 -n %s/.MainActivity --ez vs_ball true" % PKG))
time.sleep(8)
show("悬浮球窗口数", sh("dumpsys window windows | grep -cE 'u0 %s\\}'" % PKG))
show("服务", sh("dumpsys activity services %s | grep -E 'ServiceRecord|startForegroundCount'" % PKG))

# 5. 销毁
show("4. 销毁虚拟屏", sh("am start --display 0 -n %s/.MainActivity --ez vs_destroy true" % PKG))
time.sleep(8)
show("overlay 配置（应为空）", repr(sh("settings get global overlay_display_devices").strip()))
show("显示屏（应只剩物理屏与第三方 scrcpy 屏）", sh("dumpsys display | grep -oE \"displayId=[0-9]+, uniqueId='[^']*'\""))
show("崩溃", sh("logcat -d -b crash | tail -6"))
