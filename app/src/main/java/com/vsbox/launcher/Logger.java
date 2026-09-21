package com.vsbox.launcher;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 命令日志：同时写内存（给日志页）和 files/vs.log（给 run-as 排查） */
public final class Logger {

    private static final int MAX = 400;
    private static final List<String> MEM = new ArrayList<>();
    private static Context ctx;
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    public static void init(Context c) {
        ctx = c.getApplicationContext();
    }

    public static synchronized void log(String s) {
        String line = FMT.format(new Date()) + "  " + s;
        MEM.add(0, line);
        if (MEM.size() > MAX) MEM.remove(MEM.size() - 1);
        if (ctx != null) {
            try {
                File f = new File(ctx.getFilesDir(), "vs.log");
                FileOutputStream fo = new FileOutputStream(f, true);
                fo.write((line + "\n").getBytes("UTF-8"));
                fo.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public static synchronized List<String> snapshot() {
        return new ArrayList<>(MEM);
    }

    public static synchronized void clear() {
        MEM.clear();
        if (ctx != null) {
            try {
                File f = new File(ctx.getFilesDir(), "vs.log");
                if (f.exists()) f.delete();
            } catch (Throwable ignored) {
            }
        }
    }
}
