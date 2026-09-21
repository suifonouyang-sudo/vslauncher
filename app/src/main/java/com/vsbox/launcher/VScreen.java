package com.vsbox.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 虚拟屏管理：枚举已有 overlay 虚拟屏、把应用启动到指定屏、销毁。
 *
 * 全部依赖 shell(uid 2000)：
 * - 枚举    dumpsys display 的 DisplayViewport 段（必须按段切，整行扫描会漏）
 * - 启动    am start --display <id> -n pkg/act
 * - 销毁    settings put global overlay_display_devices ""
 *
 * 注意：本类**不提供创建虚拟屏的能力**。虚拟屏需由外部手段先建好
 * （adb shell settings put global overlay_display_devices "WxH/dpi"，或 scrcpy 之类的工具），
 * 本应用只负责识别已有虚拟屏并在其上启动应用。
 */
public final class VScreen {

    public static class Disp {
        public int id = -1;
        public String uniqueId = "";
        public String type = "";
        public String name = "";
        public int w = 0;
        public int h = 0;

        public boolean isOverlay() {
            return uniqueId != null && uniqueId.startsWith("overlay:");
        }

        public String kind() {
            if (uniqueId == null) return "";
            if (uniqueId.startsWith("overlay:")) return "系统虚拟屏";
            if (uniqueId.startsWith("local:")) return "物理屏";
            return "第三方虚拟屏";
        }

        public String desc() {
            return "#" + id + "  " + kind() + "  " + w + "x" + h + "  " + uniqueId;
        }
    }

    private static final Pattern P_SEG = Pattern.compile("DisplayViewport\\{([^}]*)\\}");
    private static final Pattern P_ID = Pattern.compile("displayId=(\\d+)");
    private static final Pattern P_UID = Pattern.compile("uniqueId='([^']*)'");
    private static final Pattern P_TYPE = Pattern.compile("type=(\\w+)");
    private static final Pattern P_FRAME = Pattern.compile("logicalFrame=Rect\\((\\d+),\\s*(\\d+)\\s*-\\s*(\\d+),\\s*(\\d+)\\)");
    private static final Pattern P_DEV = Pattern.compile("DisplayDeviceInfo\\{\"([^\"]*)\"[^\\n]{0,300}?uniqueId=\"([^\"]*)\"");

    /** 当前 overlay_display_devices 的原始值（未创建时为 "null" 或空） */
    public static String currentSpec() {
        String v = ShizukuShell.exec("settings get global overlay_display_devices").text();
        v = v.trim();
        if ("null".equalsIgnoreCase(v)) return "";
        return v;
    }

    /** 枚举所有屏幕。overlay（自己建的）排前面 */
    public static List<Disp> list() {
        List<Disp> out = new ArrayList<>();
        String dump = ShizukuShell.exec("dumpsys display").out;
        if (dump == null) dump = "";

        // uniqueId -> 屏名
        java.util.Map<String, String> names = new java.util.HashMap<>();
        Matcher dm = P_DEV.matcher(dump);
        while (dm.find()) {
            names.put(dm.group(2), dm.group(1));
        }

        Matcher m = P_SEG.matcher(dump);
        while (m.find()) {
            String s = m.group(1);
            Disp d = new Disp();
            Matcher mi = P_ID.matcher(s);
            if (mi.find()) d.id = Integer.parseInt(mi.group(1));
            Matcher mu = P_UID.matcher(s);
            if (mu.find()) d.uniqueId = mu.group(1);
            Matcher mt = P_TYPE.matcher(s);
            if (mt.find()) d.type = mt.group(1);
            Matcher mf = P_FRAME.matcher(s);
            if (mf.find()) {
                d.w = Integer.parseInt(mf.group(3)) - Integer.parseInt(mf.group(1));
                d.h = Integer.parseInt(mf.group(4)) - Integer.parseInt(mf.group(2));
            }
            String nm = names.get(d.uniqueId);
            d.name = nm == null ? "" : nm;
            if (d.id >= 0) out.add(d);
        }
        // 去重（同 id 可能出现多次）
        List<Disp> uniq = new ArrayList<>();
        for (Disp d : out) {
            boolean dup = false;
            for (Disp e : uniq) if (e.id == d.id) dup = true;
            if (!dup) uniq.add(d);
        }
        uniq.sort((a, b) -> Boolean.compare(!b.isOverlay(), !a.isOverlay()) != 0
                ? Boolean.compare(!a.isOverlay(), !b.isOverlay())
                : Integer.compare(a.id, b.id));
        return uniq;
    }

    /** 优先返回自己建的 overlay 屏；没有则 null（绝不拿第三方 virtual: 屏冒充） */
    public static Disp pickOverlay() {
        for (Disp d : list()) if (d.isOverlay()) return d;
        return null;
    }

    /** 按 id 找屏 */
    public static Disp byId(int id) {
        for (Disp d : list()) if (d.id == id) return d;
        return null;
    }

    /** 销毁全部虚拟屏（只清 overlay 配置，不影响第三方建的屏） */
    public static boolean destroyAll() {
        ShizukuShell.Result r = ShizukuShell.exec("settings put global overlay_display_devices \"\"");
        if (r.error != null) return false;
        // 保险：值为空字符串写不进去时改用 delete
        String v = currentSpec();
        if (!v.isEmpty()) {
            ShizukuShell.exec("settings delete global overlay_display_devices");
        }
        return true;
    }

    /** 销毁指定序号的那一块（按 overlay_display_devices 里的顺序） */
    public static boolean destroyIndex(int index) {
        String cur = currentSpec();
        if (cur.isEmpty()) return false;
        String[] items = cur.split(";");
        if (index < 0 || index >= items.length) return false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.length; i++) {
            if (i == index) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(items[i]);
        }
        String next = sb.toString();
        if (next.isEmpty()) return destroyAll();
        ShizukuShell.exec("settings put global overlay_display_devices \"" + next + "\"");
        return true;
    }

    /** 把应用启动到指定屏。comp 为空时用 LAUNCHER 意图兜底 */
    public static ShizukuShell.Result launch(String pkg, String comp, int displayId) {
        String cmd;
        if (comp != null && !comp.isEmpty()) {
            cmd = "am start --display " + displayId + " -n " + comp;
        } else {
            cmd = "am start --display " + displayId
                    + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p " + pkg;
        }
        ShizukuShell.Result r = ShizukuShell.exec(cmd);
        // 部分 ROM 上 -n 启动失败，退回 LAUNCHER 意图
        if (!r.ok() && comp != null && !comp.isEmpty()) {
            ShizukuShell.Result r2 = ShizukuShell.exec("am start --display " + displayId
                    + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p " + pkg);
            Logger.log("VScreen launch fallback -> " + r2.text());
            return r2;
        }
        return r;
    }

    /** 某块屏上当前跑着的应用包名（取不到返回 ""） */
    public static String topOn(int displayId) {
        String dump = ShizukuShell.exec("dumpsys activity activities").out;
        if (dump == null || dump.isEmpty()) return "";
        String[] lines = dump.split("\n");
        int cur = -1;
        for (String ln : lines) {
            Matcher m = Pattern.compile("Display #(\\d+)").matcher(ln);
            if (m.find()) {
                cur = Integer.parseInt(m.group(1));
                continue;
            }
            if (cur != displayId) continue;
            Matcher c = Pattern.compile("cmp=\\{?([^}/\\s]+)/").matcher(ln);
            if (c.find()) return c.group(1);
            Matcher c2 = Pattern.compile("cmp=([A-Za-z0-9_.$]+)/").matcher(ln);
            if (c2.find()) return c2.group(1);
        }
        return "";
    }

    /** 把本应用带回 display 0 前台 —— "自动返回桌面软件" */
    public static ShizukuShell.Result bringSelfHome(String selfPkg) {
        return ShizukuShell.exec("am start --display 0 -n " + selfPkg + "/.MainActivity");
    }

    /** 强制停止某个应用（用于把虚拟屏上的应用收掉） */
    public static void forceStop(String pkg) {
        ShizukuShell.exec("am force-stop " + pkg);
    }

    /** 改虚拟屏分辨率（-d 必须在最后） */
    public static void size(int displayId, int w, int h) {
        ShizukuShell.exec("wm size " + w + "x" + h + " -d " + displayId);
    }

    public static void density(int displayId, int dpi) {
        ShizukuShell.exec("wm density " + dpi + " -d " + displayId);
    }

    /** 旋转（Android 12+ 与 11 命令不同，且 -d 位置不同） */
    public static void rotation(int displayId, int r, boolean api31plus) {
        if (api31plus) {
            ShizukuShell.exec("cmd window user-rotation -d " + displayId + " lock " + r);
            ShizukuShell.exec("cmd window set-ignore-orientation-request -d " + displayId + " true");
        } else {
            ShizukuShell.exec("cmd window set-user-rotation lock -d " + displayId + " " + r);
            ShizukuShell.exec("cmd window set-fix-to-user-rotation -d " + displayId + " enabled");
        }
    }

    public static void rotationFree(int displayId, boolean api31plus) {
        if (api31plus) {
            ShizukuShell.exec("cmd window user-rotation -d " + displayId + " free");
        } else {
            ShizukuShell.exec("cmd window set-user-rotation free -d " + displayId);
        }
    }
}
