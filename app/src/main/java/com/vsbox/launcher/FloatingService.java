package com.vsbox.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 常驻悬浮球：任何界面下都能
 *  - 一键回到本桌面（把虚拟屏上的应用留在虚拟屏，自己回到 display 0）
 *  - 新建 / 销毁虚拟屏
 *  - 直接在虚拟屏打开「最近用过的应用」
 */
public class FloatingService extends Service {

    private static final String CH_ID = "vs_ball";

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private WindowManager.LayoutParams ballLp, panelLp;
    private View ball;
    private LinearLayout panelBox;
    private TextView tvPanelStatus;
    private boolean panelShown = false;
    private int screenW, screenH;

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.init(this);
        Prefs.init(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        startForeground(1001, buildNotification());
        createBall();
        createPanel();
        Logger.log("悬浮球服务已创建");
    }

    @Override
    public void onDestroy() {
        try {
            if (panelShown) wm.removeView(panelBox);
            if (ball != null) wm.removeView(ball);
        } catch (Throwable ignored) {
        }
        Logger.log("悬浮球服务已销毁");
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private int type() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "虚拟屏悬浮球",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("虚拟屏桌面")
                .setContentText("悬浮球运行中")
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .build();
    }

    // ---------------------------------------------------------------- 悬浮球

    private void createBall() {
        int size = dp(46);
        TextView tv = new TextView(this);
        tv.setText("屏");
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(15);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundResource(R.drawable.bg_ball);
        tv.setAlpha(0.92f);

        ballLp = new WindowManager.LayoutParams(size, size, type(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        ballLp.gravity = Gravity.TOP | Gravity.START;
        ballLp.x = Math.max(0, screenW - size - dp(6));
        ballLp.y = Math.max(0, screenH / 3);

        final int[] startXY = {ballLp.x, ballLp.y};
        final float[] downAt = {0f, 0f};
        final boolean[] moved = {false};

        tv.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startXY[0] = ballLp.x;
                    startXY[1] = ballLp.y;
                    downAt[0] = e.getRawX();
                    downAt[1] = e.getRawY();
                    moved[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dx = (int) (e.getRawX() - downAt[0]);
                    int dy = (int) (e.getRawY() - downAt[1]);
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved[0] = true;
                    ballLp.x = Math.max(0, Math.min(startXY[0] + dx, screenW - dp(46)));
                    ballLp.y = Math.max(0, Math.min(startXY[1] + dy, screenH - dp(46)));
                    try {
                        wm.updateViewLayout(ball, ballLp);
                    } catch (Throwable ignored) {
                    }
                    if (panelShown) placePanel();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!moved[0]) togglePanel();
                    return true;
                default:
                    return false;
            }
        });

        ball = tv;
        try {
            wm.addView(ball, ballLp);
        } catch (Throwable t) {
            Logger.log("悬浮球添加失败：" + t);
        }
    }

    // ---------------------------------------------------------------- 面板

    private void createPanel() {
        panelBox = new LinearLayout(this);
        panelBox.setOrientation(LinearLayout.VERTICAL);
        panelBox.setBackgroundResource(R.drawable.bg_card);
        panelBox.setPadding(dp(10), dp(10), dp(10), dp(10));

        panelLp = new WindowManager.LayoutParams(dp(232),
                WindowManager.LayoutParams.WRAP_CONTENT, type(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
    }

    private void placePanel() {
        int pw = dp(232);
        // 面板放在球左边、且不与球重叠（球不会被面板盖住，随时能再点一次收起）
        int x = ballLp.x - pw - dp(8);
        if (x < dp(4)) x = ballLp.x + dp(46) + dp(8);
        panelLp.x = Math.max(dp(4), Math.min(x, screenW - pw - dp(4)));
        panelLp.y = Math.max(dp(4), Math.min(ballLp.y - dp(10), screenH - dp(380)));
        try {
            wm.updateViewLayout(panelBox, panelLp);
        } catch (Throwable ignored) {
        }
    }

    private void togglePanel() {
        if (panelShown) {
            hidePanel();
            return;
        }
        panelBox.removeAllViews();
        buildPanelContent();
        placePanel();
        try {
            wm.addView(panelBox, panelLp);
            panelShown = true;
        } catch (Throwable t) {
            Logger.log("面板添加失败：" + t);
        }
    }

    private void hidePanel() {
        try {
            wm.removeView(panelBox);
        } catch (Throwable ignored) {
        }
        panelShown = false;
    }

    private void buildPanelContent() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("虚拟屏桌面");
        title.setTextSize(14);
        title.setTextColor(getColor(R.color.text));
        head.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button hide = new Button(this);
        hide.setText("收起");
        hide.setTextSize(11);
        hide.setAllCaps(false);
        hide.setMinWidth(0);
        hide.setMinimumWidth(0);
        hide.setPadding(dp(8), 0, dp(8), 0);
        hide.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)));
        hide.setOnClickListener(v -> hidePanel());
        head.addView(hide);

        panelBox.addView(head);

        tvPanelStatus = new TextView(this);
        tvPanelStatus.setTextSize(11.5f);
        tvPanelStatus.setTextColor(getColor(R.color.text_sub));
        tvPanelStatus.setPadding(0, 0, 0, dp(6));
        panelBox.addView(tvPanelStatus);

        panelBox.addView(mkBtn("回到本桌面", v -> {
            hidePanel();
            bg(() -> {
                VScreen.bringSelfHome(getPackageName());
                Logger.log("悬浮球：回到本桌面");
            });
        }));

        panelBox.addView(mkBtn("销毁虚拟屏", v -> {
            hidePanel();
            bg(() -> {
                VScreen.destroyAll();
                Logger.log("悬浮球：销毁全部虚拟屏");
                toast("已销毁虚拟屏");
            });
        }));

        // 最近应用
        String last = Prefs.get(Prefs.K_LAST, "");
        if (!TextUtils.isEmpty(last)) {
            TextView h = new TextView(this);
            h.setText("最近打开");
            h.setTextSize(11.5f);
            h.setTextColor(getColor(R.color.text_sub));
            h.setPadding(0, dp(8), 0, dp(2));
            panelBox.addView(h);
            for (String pkg : last.split(",")) {
                if (TextUtils.isEmpty(pkg)) continue;
                String label = pkg;
                try {
                    label = getPackageManager().getApplicationLabel(
                            getPackageManager().getApplicationInfo(pkg, 0)).toString();
                } catch (Throwable ignored) {
                }
                final String p = pkg;
                panelBox.addView(mkBtn("▸ " + label, v -> {
                    hidePanel();
                    launchRecent(p);
                }));
            }
        }

        panelBox.addView(mkBtn("关闭悬浮球", v -> {
            hidePanel();
            Prefs.putBool(Prefs.K_BALL, false);
            stopSelf();
        }));

        refreshPanelStatus();
    }

    private void refreshPanelStatus() {
        bg(() -> {
            int ov = 0;
            String spec = VScreen.currentSpec();
            for (VScreen.Disp d : VScreen.list()) if (d.isOverlay()) ov++;
            final String s = ShizukuShell.isReady()
                    ? (ov > 0 ? ("虚拟屏 " + ov + " 块 · " + spec) : "虚拟屏未创建")
                    : "Shizuku 未就绪，点悬浮球重试";
            main.post(() -> {
                if (tvPanelStatus != null) tvPanelStatus.setText(s);
            });
        });
    }

    private Button mkBtn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        lp.topMargin = dp(5);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    // ---------------------------------------------------------------- 动作

    private void launchRecent(String pkg) {
        if (!ShizukuShell.isReady()) {
            toast("Shizuku 未授权");
            return;
        }
        bg(() -> {
            int d = currentOverlayDisplay();
            if (d < 0) {
                Logger.log("悬浮球取消启动 " + pkg + "：没有可用虚拟屏（需先手动创建）");
                toast("还没有虚拟屏，请先在应用里创建一块");
                return;
            }
            String comp = Apps.launcherComponent(getPackageManager(), pkg);
            ShizukuShell.Result r = VScreen.launch(pkg, comp, d);
            Logger.log("悬浮球启动 " + pkg + " -> 屏#" + d + (r.ok() ? " [OK]" : " [失败]"));
            toast(r.ok() ? "已在屏#" + d + " 打开" : "启动失败");
            if (r.ok() && Prefs.getBool(Prefs.K_AUTO_RETURN, true)) {
                sleep(1200);
                VScreen.bringSelfHome(getPackageName());
            }
        });
    }

    /** 当前虚拟屏 id；没有则 -1。不自动建屏，与主界面保持一致 */
    private int currentOverlayDisplay() {
        VScreen.Disp d = VScreen.pickOverlay();
        return d != null ? d.id : -1;
    }

    private void bg(Runnable r) {
        new Thread(r).start();
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void toast(String s) {
        main.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }
}
