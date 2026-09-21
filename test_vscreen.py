import subprocess, re, time, sys

ADB = r"C:\Android\Sdk\platform-tools\adb.exe"
S = sys.argv[1] if len(sys.argv) > 1 else "1B677DECO1"


def run(args, timeout=120):
    return subprocess.run([ADB, "-s", S] + args, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=timeout).stdout


def sh(cmd, timeout=120):
    return run(["shell", cmd], timeout)


def dump():
    for _ in range(4):
        sh("uiautomator dump --compressed /sdcard/ui.xml >/dev/null 2>&1")
        r = sh("cat /sdcard/ui.xml")
        if len(r) > 500:
            return r
        time.sleep(3)
    return ""


def bounds_of(xml, rid, pkg="com.vsbox.launcher"):
    m = re.search(r'resource-id="%s:id/%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % (pkg, rid), xml)
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(x, y):
    sh("input tap %d %d" % (x, y))


print("== 前台 ==")
print(sh("dumpsys activity activities | grep -m1 ResumedActivity"))
print("== 启动本应用 ==")
sh("am start --display 0 -n com.vsbox.launcher/.MainActivity")
time.sleep(3)

xml = dump()
print("== 界面断言 ==")
print("页面根:", "pageApps" if "pageApps" in xml else "?",
      "| 应用项:", len(re.findall(r'id/tvName', xml)),
      "| 计数:", (re.search(r'id/tvCount[^>]*text="([^"]*)"', xml) or [None, "?"])[1])
print("Shizuku:", (re.search(r'id/tvShizuku[^>]*text="([^"]*)"', xml) or [None, "?"])[1])
print("虚拟屏:", (re.search(r'id/tvVScreen[^>]*text="([^"]*)"', xml) or [None, "?"])[1])

b = bounds_of(xml, "tvName")
if not b:
    print("!! 找不到应用列表项")
    sys.exit(1)
print("== 点击第一个应用项 (中文排序首位) ==", b)
tap(*b)

for i in range(6):
    time.sleep(4)
    log = sh("run-as com.vsbox.launcher cat files/vs.log 2>/dev/null | tail -6")
    if "启动 " in log and ("[OK]" in log or "[失败]" in log):
        break

print("== 日志 ==")
print(sh("run-as com.vsbox.launcher cat files/vs.log 2>/dev/null | tail -14"))
print("== overlay 配置 ==", sh("settings get global overlay_display_devices").strip())
print("== 显示屏 ==")
print(sh("dumpsys display | grep -oE \"displayId=[0-9]+, uniqueId='[^']*'\""))
print("== 各屏前台任务 ==")
acts = sh("dumpsys activity activities | grep -E '^Display #|mResumedActivity'")
print(acts[:2000])
print("== 崩溃 ==")
print(sh("logcat -d -b crash | tail -5"))
print("== 悬浮球 app 日志尾部 ==")
print(sh("run-as com.vsbox.launcher cat files/vs.log 2>/dev/null | tail -3"))
