package com.vsbox.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 已安装应用列表：后台加载 + 中文排序 + 用户/系统分流 */
public final class Apps {

    public static class Info {
        public String pkg;
        public String label;
        public String comp;   // pkg/activity，可能为 null
        public boolean system;
        public Drawable icon;

        @Override
        public String toString() {
            return label;
        }
    }

    public interface Callback {
        void onLoaded(List<Info> user, List<Info> system);
    }

    public static void loadAsync(Context c, Callback cb) {
        new Thread(() -> {
            List<Info> all = load(c);
            List<Info> user = new ArrayList<>();
            List<Info> sys = new ArrayList<>();
            for (Info i : all) {
                if (i.system) sys.add(i);
                else user.add(i);
            }
            final List<Info> u = user;
            final List<Info> s = sys;
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(() -> cb.onLoaded(u, s));
        }).start();
    }

    public static List<Info> load(Context c) {
        List<Info> list = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        List<ApplicationInfo> ais = pm.getInstalledApplications(0);
        int hidden = 0;
        for (ApplicationInfo ai : ais) {
            Info i = new Info();
            i.pkg = ai.packageName;
            i.system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            try {
                CharSequence l = ai.loadLabel(pm);
                i.label = l == null ? i.pkg : l.toString();
            } catch (Throwable t) {
                i.label = i.pkg;
            }
            try {
                i.icon = ai.loadIcon(pm);
            } catch (Throwable ignored) {
            }
            i.comp = launcherComponent(pm, i.pkg);
            // 没有可启动入口（无桌面图标）的软件：直接隐藏，不在列表显示
            if (i.comp == null) {
                hidden++;
                continue;
            }
            list.add(i);
        }
        Collator col = Collator.getInstance(Locale.CHINA);
        list.sort((a, b) -> col.compare(a.label, b.label));
        if (hidden > 0) {
            Logger.log("已隐藏无入口软件 " + hidden + " 个（仅显示可启动的应用）");
        }
        return list;
    }

    /** 取可启动的 Activity（pkg/ClassName） */
    public static String launcherComponent(PackageManager pm, String pkg) {
        try {
            Intent it = new Intent(Intent.ACTION_MAIN);
            it.addCategory(Intent.CATEGORY_LAUNCHER);
            it.setPackage(pkg);
            List<ResolveInfo> rs = pm.queryIntentActivities(it, 0);
            if (rs != null && !rs.isEmpty() && rs.get(0).activityInfo != null) {
                return pkg + "/" + rs.get(0).activityInfo.name;
            }
        } catch (Throwable ignored) {
        }
        try {
            Intent it = pm.getLaunchIntentForPackage(pkg);
            if (it != null && it.getComponent() != null) {
                return it.getComponent().flattenToShortString();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
