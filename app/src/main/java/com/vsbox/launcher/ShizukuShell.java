package com.vsbox.launcher;

import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/**
 * 以 shell(uid 2000) 身份执行命令，等价于 adb shell。
 *
 * 关键点：
 * - 13.x 没有公开的 newProcess，必须走 AIDL：IShizukuService.Stub.asInterface(ShizukuBinderWrapper)
 * - IRemoteProcess 的 getInputStream() 返回 ParcelFileDescriptor，要自己包 FileInputStream
 * - 读流必须并发（子进程写满管道就会卡住），等待退出码必须带超时
 * - 会 fork 后台子进程的命令（pm install 等）**千万不要读流**，改用 execQuiet
 */
public final class ShizukuShell {

    public static class Result {
        public String cmd = "";
        public String out = "";
        public String err = "";
        public int code = -1;
        public boolean timeout = false;
        public String error = null;

        public String text() {
            String s = out == null ? "" : out.trim();
            String e = err == null ? "" : err.trim();
            if (!e.isEmpty()) s = s.isEmpty() ? e : (s + "\n" + e);
            return s;
        }

        public boolean ok() {
            return error == null && code == 0;
        }
    }

    private ShizukuShell() {
    }

    public static boolean binderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean permissionGranted() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isReady() {
        return binderAlive() && permissionGranted();
    }

    private static IShizukuService service() throws Exception {
        android.os.IBinder b = null;
        try {
            b = Shizuku.getBinder();
        } catch (Throwable ignored) {
        }
        if (b == null) {
            try {
                b = SystemServiceHelper.getSystemService("shizuku");
            } catch (Throwable ignored) {
            }
        }
        if (b == null) throw new IllegalStateException("拿不到 Shizuku binder");
        IShizukuService s = IShizukuService.Stub.asInterface(new ShizukuBinderWrapper(b));
        if (s == null) throw new IllegalStateException("Shizuku service null");
        return s;
    }

    public static Result exec(String cmd) {
        return run(cmd, 15, true);
    }

    public static Result exec(String cmd, int timeoutSec) {
        return run(cmd, timeoutSec, true);
    }

    /** 同 exec，但不把非零退出码当作失败（调用方自己判断），用于 cmd xxx -h 这类探测 */
    public static Result execLenient(String cmd) {
        return run(cmd, 15, true);
    }

    /** 只等退出码、完全不读输出流。给 pm install / pm uninstall 这类会派生子进程的命令用 */
    public static Result execQuiet(String cmd, int timeoutSec) {
        return run(cmd, timeoutSec, false);
    }

    private static Result run(String cmd, int timeoutSec, boolean readStreams) {
        Result r = new Result();
        r.cmd = cmd;
        IRemoteProcess proc = null;
        try {
            proc = service().newProcess(new String[]{"sh", "-c", cmd}, null, null);
        } catch (Throwable t) {
            r.error = describe(t);
            Logger.log("[ERR] Shizuku 执行失败：" + describe(t) + "  cmd=" + brief(cmd));
            return r;
        }

        final StringBuilder so = new StringBuilder();
        final StringBuilder se = new StringBuilder();
        Thread outT = null, errT = null;
        if (readStreams) {
            final IRemoteProcess p1 = proc;
            outT = new Thread(() -> so.append(readAll(stream(p1, true))));
            errT = new Thread(() -> se.append(readAll(stream(p1, false))));
            outT.setDaemon(true);
            errT.setDaemon(true);
            outT.start();
            errT.start();
        }

        final int[] code = {-1};
        final IRemoteProcess p2 = proc;
        Thread waitT = new Thread(() -> {
            try {
                code[0] = p2.waitFor();
            } catch (Throwable ignored) {
            }
        });
        waitT.setDaemon(true);
        waitT.start();

        try {
            waitT.join(Math.max(1, timeoutSec) * 1000L);
        } catch (InterruptedException ignored) {
        }

        if (waitT.isAlive()) {
            r.timeout = true;
            try {
                proc.destroy();
            } catch (Throwable ignored) {
            }
            try {
                waitT.join(1500);
            } catch (InterruptedException ignored) {
            }
        } else {
            r.code = code[0];
        }

        if (outT != null) {
            try {
                outT.join(2000);
                errT.join(2000);
            } catch (InterruptedException ignored) {
            }
            r.out = so.toString();
            r.err = se.toString();
        }
        Logger.log("[cmd] " + brief(cmd) + "  -> " + (r.timeout ? "TIMEOUT" : ("code=" + r.code)));
        return r;
    }

    private static InputStream stream(IRemoteProcess p, boolean stdout) {
        try {
            ParcelFileDescriptor pfd = stdout ? p.getInputStream() : p.getErrorStream();
            if (pfd == null) return null;
            return new FileInputStream(pfd.getFileDescriptor());
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        } catch (Throwable ignored) {
        }
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String describe(Throwable t) {
        StackTraceElement[] st = t.getStackTrace();
        String at = (st != null && st.length > 0) ? (" @" + st[0]) : "";
        return t.getClass().getSimpleName() + ": " + t.getMessage() + at;
    }

    private static String brief(String cmd) {        if (cmd == null) return "";
        String s = cmd.replace('\n', ' ');
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}
