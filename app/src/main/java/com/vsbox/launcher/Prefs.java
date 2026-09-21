package com.vsbox.launcher;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {

    private static final String N = "vs";
    private static SharedPreferences sp;

    public static void init(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(N, Context.MODE_PRIVATE);
    }

    public static String get(String k, String def) {
        return sp == null ? def : sp.getString(k, def);
    }

    public static int getInt(String k, int def) {
        return sp == null ? def : sp.getInt(k, def);
    }

    public static boolean getBool(String k, boolean def) {
        return sp == null ? def : sp.getBoolean(k, def);
    }

    public static void put(String k, String v) {
        if (sp != null) sp.edit().putString(k, v).apply();
    }

    public static void putInt(String k, int v) {
        if (sp != null) sp.edit().putInt(k, v).apply();
    }

    public static void putBool(String k, boolean v) {
        if (sp != null) sp.edit().putBoolean(k, v).apply();
    }

    public static final String K_W = "w";
    public static final String K_H = "h";
    public static final String K_DPI = "dpi";
    public static final String K_AUTO_RETURN = "auto_return";
    public static final String K_BALL = "ball";
    public static final String K_LAST = "last_pkgs";
}
