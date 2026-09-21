package com.vsbox.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;

/**
 * 虚拟屏桌面：列出所有应用，点击即在虚拟屏上打开；带悬浮球，可一键回到本桌面。
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_OVERLAY = 1001;

    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView tvShizuku, tvVScreen, tvCount, tvScreenInfo;
    private View pageApps, pageScreen, pageLog;
    private Button tabApps, tabScreen, tabLog;
    private EditText etSearch, etW, etH, etDpi;
    private ListView lvApps, lvDisplays, lvLog;
    private Button btnUser, btnSys, btnCreate, btnDestroy, btnRefresh, btnHome, btnStopTop,
            btnClearLog, btnRefreshLog, btnBattery, btnScreenOff;
    private CheckBox cbAutoReturn, cbBall;

    private final List<Apps.Info> userApps = new ArrayList<>();
    private final List<Apps.Info> sysApps = new ArrayList<>();
    private final List<Apps.Info> shown = new ArrayList<>();
    private final List<VScreen.Disp> displays = new ArrayList<>();
    private boolean showSystem = false;
    private String query = "";
    private int selectedDisplay = -1;
    private boolean loadingApps = true;
    private boolean askedPermission = false;

    private AppAdapter appAdapter;
    private BaseAdapter dispAdapter, logAdapter;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.init(this);
        Prefs.init(this);
        setContentView(R.layout.activity_main);

        bindViews();
        initDefaults();
        initShizuku();
        initApps();
        initScreenPage();
        initLogPage();

        switchTab(0);
        refreshShizukuState();
        refreshDisplays();
        Logger.log("启动 " + getPackageName() + " / Android " + Build.VERSION.RELEASE);
        main.postDelayed(this::handleIntentExtras, 900);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        main.postDelayed(this::handleIntentExtras, 400);
    }

    /** 把本应用加入电池优化白名单 —— 挂机/常驻悬浮球的关键一步 */
    private void requestIgnoreBattery() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                toast("已在电池优化白名单中");
                return;
            }
            Intent it = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            it.setData(Uri.parse("package:" + getPackageName()));
            startActivity(it);
            Logger.log("请求加入电池优化白名单");
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t2) {
                toast("打开电池优化设置失败");
            }
        }
    }

    /**
     * 外部触发入口（adb / Tasker 都能用）：
     *   am start -n com.vsbox.launcher/.MainActivity --es vs_pkg <包名>   在虚拟屏打开
     *   am start -n com.vsbox.launcher/.MainActivity --ez vs_ball true    打开悬浮球
     *   am start -n com.vsbox.launcher/.MainActivity --ez vs_stop true    关闭悬浮球
     *   am start -n com.vsbox.launcher/.MainActivity --ez vs_create true  创建虚拟屏
     *   am start -n com.vsbox.launcher/.MainActivity --ez vs_destroy true 销毁全部虚拟屏
     */
    private void handleIntentExtras() {
        Intent it = getIntent();
        if (it == null) return;
        if (it.getBooleanExtra("vs_ball", false)) {
            if (!cbBall.isChecked()) cbBall.setChecked(true);
            startBall();
        }
        if (it.getBooleanExtra("vs_stop", false)) {
            if (cbBall.isChecked()) cbBall.setChecked(false);
            stopBall();
        }
        if (it.getBooleanExtra("vs_create", false)) {
            bg(() -> {
                int d = VScreen.create(Prefs.getInt(Prefs.K_W, 720), Prefs.getInt(Prefs.K_H, 1280),
                        Prefs.getInt(Prefs.K_DPI, 280));
                Logger.log("外部触发：创建虚拟屏 -> " + (d >= 0 ? ("display " + d) : "失败"));
                toast(d >= 0 ? "虚拟屏已创建：#" + d : "创建失败");
                if (d >= 0) selectedDisplay = d;
                refreshDisplays();
            });
        }
        if (it.getBooleanExtra("vs_destroy", false)) {
            bg(() -> {
                VScreen.destroyAll();
                Logger.log("外部触发：销毁全部虚拟屏");
                selectedDisplay = -1;
                refreshDisplays();
            });
        }
        String pkg = it.getStringExtra("vs_pkg");
        if (pkg != null && !pkg.isEmpty()) {
            startPkgOnVScreen(pkg);
        }
    }

    /** 只给包名也能在虚拟屏打开（自己查可启动 Activity） */
    private void startPkgOnVScreen(String pkg) {
        Apps.Info i = new Apps.Info();
        i.pkg = pkg;
        try {
            i.label = getPackageManager()
                    .getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            i.label = pkg;
        }
        i.comp = Apps.launcherComponent(getPackageManager(), pkg);
        Logger.log("外部触发：在虚拟屏打开 " + pkg);
        startOnVScreen(i);
    }

    private void bindViews() {
        tvShizuku = findViewById(R.id.tvShizuku);
        tvVScreen = findViewById(R.id.tvVScreen);
        pageApps = findViewById(R.id.pageApps);
        pageScreen = findViewById(R.id.pageScreen);
        pageLog = findViewById(R.id.pageLog);
        tabApps = findViewById(R.id.tabApps);
        tabScreen = findViewById(R.id.tabScreen);
        tabLog = findViewById(R.id.tabLog);
        etSearch = findViewById(R.id.etSearch);
        etW = findViewById(R.id.etW);
        etH = findViewById(R.id.etH);
        etDpi = findViewById(R.id.etDpi);
        lvApps = findViewById(R.id.lvApps);
        lvDisplays = findViewById(R.id.lvDisplays);
        lvLog = findViewById(R.id.lvLog);
        tvCount = findViewById(R.id.tvCount);
        tvScreenInfo = findViewById(R.id.tvScreenInfo);
        btnUser = findViewById(R.id.btnUser);
        btnSys = findViewById(R.id.btnSys);
        btnCreate = findViewById(R.id.btnCreate);
        btnDestroy = findViewById(R.id.btnDestroy);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnHome = findViewById(R.id.btnHome);
        btnStopTop = findViewById(R.id.btnStopTop);
        btnBattery = findViewById(R.id.btnBattery);
        btnScreenOff = findViewById(R.id.btnScreenOff);
        btnClearLog = findViewById(R.id.btnClearLog);
        btnRefreshLog = findViewById(R.id.btnRefreshLog);
        cbAutoReturn = findViewById(R.id.cbAutoReturn);
        cbBall = findViewById(R.id.cbBall);
    }

    private void initDefaults() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = Prefs.getInt(Prefs.K_W, 0);
        int h = Prefs.getInt(Prefs.K_H, 0);
        int dpi = Prefs.getInt(Prefs.K_DPI, 0);
        if (w <= 0) {
            w = Math.max(320, (dm.widthPixels * 2 / 3) / 2 * 2);
            h = Math.max(480, (dm.heightPixels * 2 / 3) / 2 * 2);
            dpi = dm.densityDpi;
        }
        etW.setText(String.valueOf(w));
        etH.setText(String.valueOf(h));
        etDpi.setText(String.valueOf(dpi));

        cbAutoReturn.setChecked(Prefs.getBool(Prefs.K_AUTO_RETURN, true));
        cbBall.setChecked(Prefs.getBool(Prefs.K_BALL, false));

        tabApps.setOnClickListener(v -> switchTab(0));
        tabScreen.setOnClickListener(v -> switchTab(1));
        tabLog.setOnClickListener(v -> switchTab(2));
    }

    private void switchTab(int i) {
        pageApps.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        pageScreen.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
        pageLog.setVisibility(i == 2 ? View.VISIBLE : View.GONE);
        tabApps.setActivated(i == 0);
        tabScreen.setActivated(i == 1);
        tabLog.setActivated(i == 2);
        if (i == 2) refreshLog();
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permListener);
            Shizuku.removeBinderReceivedListener(binderListener);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- shizuku

    private final Shizuku.OnRequestPermissionResultListener permListener =
            (requestCode, grantResult) -> {
                Logger.log("Shizuku 授权结果：" + (grantResult == PackageManager.PERMISSION_GRANTED ? "已授权" : "被拒绝"));
                refreshShizukuState();
            };

    private final Shizuku.OnBinderReceivedListener binderListener = this::refreshShizukuState;

    private void initShizuku() {
        try {
            Shizuku.addRequestPermissionResultListener(permListener);
            Shizuku.addBinderReceivedListener(binderListener);
        } catch (Throwable t) {
            Logger.log("Shizuku 监听注册失败：" + t);
        }
        tvShizuku.setOnClickListener(v -> {
            if (!ShizukuShell.binderAlive()) {
                openShizukuApp();
            } else if (!ShizukuShell.permissionGranted()) {
                requestShizukuPermission();
            } else {
                toast("Shizuku 已就绪");
            }
        });
    }

    private void refreshShizukuState() {
        main.post(() -> {
            boolean binder = ShizukuShell.binderAlive();
            boolean granted = binder && ShizukuShell.permissionGranted();
            if (granted) {
                int uid = -1, ver = -1;
                try {
                    uid = Shizuku.getUid();
                    ver = Shizuku.getVersion();
                } catch (Throwable ignored) {
                }
                tvShizuku.setText("Shizuku 已授权 · uid " + uid + " · v" + ver);
                tvShizuku.setTextColor(getColor(R.color.ok));
                Logger.log("[OK] Shizuku 就绪 uid=" + uid);
            } else if (binder) {
                tvShizuku.setText("Shizuku 未授权 · 点此申请");
                tvShizuku.setTextColor(getColor(R.color.warn));
                if (!askedPermission) {
                    askedPermission = true;
                    requestShizukuPermission();
                }
            } else {
                tvShizuku.setText("Shizuku 未运行 · 点此打开");
                tvShizuku.setTextColor(getColor(R.color.err));
            }
        });
    }

    private void requestShizukuPermission() {
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                Logger.log("Shizuku 权限曾被拒绝，不再自动申请");
                toast("请到 Shizuku 应用里为本应用授权");
                return;
            }
            Shizuku.requestPermission(REQ_OVERLAY);
            Logger.log("已发起 Shizuku 授权请求");
        } catch (Throwable t) {
            Logger.log("申请 Shizuku 权限失败：" + t);
        }
    }

    private void openShizukuApp() {
        try {
            Intent it = getPackageManager().getLaunchIntentForPackage("moe.shizuku.manager");
            if (it != null) startActivity(it);
            else toast("未安装 Shizuku");
        } catch (Throwable t) {
            toast("未安装 Shizuku");
        }
    }

    // ---------------------------------------------------------------- apps page

    private void initApps() {
        appAdapter = new AppAdapter();
        lvApps.setAdapter(appAdapter);

        lvApps.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= shown.size()) return;
            startOnVScreen(shown.get(position));
        });
        lvApps.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= shown.size()) return true;
            showAppMenu(shown.get(position));
            return true;
        });

        btnUser.setOnClickListener(v -> {
            showSystem = false;
            btnUser.setActivated(true);
            btnSys.setActivated(false);
            applyFilter();
        });
        btnSys.setOnClickListener(v -> {
            showSystem = true;
            btnUser.setActivated(false);
            btnSys.setActivated(true);
            applyFilter();
        });
        btnUser.setActivated(true);

        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s == null ? "" : s.toString().trim().toLowerCase();
                applyFilter();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });

        Apps.loadAsync(this, (user, system) -> {
            userApps.clear();
            userApps.addAll(user);
            sysApps.clear();
            sysApps.addAll(system);
            loadingApps = false;
            Logger.log("应用列表：用户 " + user.size() + " + 系统 " + system.size() + " = " + (user.size() + system.size()));
            applyFilter();
        });
    }

    private void applyFilter() {
        List<Apps.Info> src = showSystem ? sysApps : userApps;
        shown.clear();
        for (Apps.Info i : src) {
            if (TextUtils.isEmpty(query)
                    || i.label.toLowerCase().contains(query)
                    || i.pkg.toLowerCase().contains(query)) {
                shown.add(i);
            }
        }
        appAdapter.notifyDataSetChanged();
        tvCount.setText(loadingApps ? "加载中…"
                : ("用户 " + userApps.size() + " · 系统 " + sysApps.size()
                + " · 当前显示 " + shown.size()
                + (showSystem ? "（系统应用）" : "（用户应用）")));
    }

    private class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.item_app, parent, false);
            }
            Apps.Info info = shown.get(position);
            ImageView iv = convertView.findViewById(R.id.ivIcon);
            TextView name = convertView.findViewById(R.id.tvName);
            TextView pkg = convertView.findViewById(R.id.tvPkg);
            TextView tag = convertView.findViewById(R.id.tvTag);

            Drawable d = info.icon;
            if (d != null) iv.setImageDrawable(d);
            else iv.setImageDrawable(getDrawable(R.drawable.ic_launcher));
            name.setText(info.label);
            pkg.setText(info.pkg);
            tag.setText(info.comp == null ? "无入口" : "");
            return convertView;
        }
    }

    private void showAppMenu(Apps.Info info) {
        String[] items = {"在虚拟屏打开", "在主屏打开", "强制停止", "复制包名"};
        new AlertDialog.Builder(this)
                .setTitle(info.label + "\n" + info.pkg)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            startOnVScreen(info);
                            break;
                        case 1:
                            bg(() -> {
                                VScreen.launch(info.pkg, info.comp, 0);
                                Logger.log("主屏启动 " + info.label);
                            });
                            break;
                        case 2:
                            bg(() -> {
                                VScreen.forceStop(info.pkg);
                                Logger.log("强制停止 " + info.pkg);
                                toast("已停止 " + info.label);
                            });
                            break;
                        default:
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("pkg", info.pkg));
                            toast("已复制 " + info.pkg);
                            break;
                    }
                })
                .show();
    }

    /** 在虚拟屏上打开应用；没有屏就先建一块，然后（可选）自动回到本桌面 */
    private void startOnVScreen(Apps.Info info) {
        if (!ShizukuShell.isReady()) {
            toast("请先完成 Shizuku 授权");
            return;
        }
        if (info.comp == null) {
            toast("该应用没有可启动的界面");
            return;
        }
        toast("正在虚拟屏打开 " + info.label + " …");
        bg(() -> {
            int d = ensureDisplay();
            if (d < 0) {
                toast("虚拟屏创建失败，请检查 Shizuku 授权");
                return;
            }
            ShizukuShell.Result r = VScreen.launch(info.pkg, info.comp, d);
            Logger.log("启动 " + info.label + " -> 屏#" + d + " " + (r.ok() ? "[OK]" : "[失败] " + r.text()));
            if (!r.ok()) {
                toast("启动失败：" + firstLine(r.text()));
                return;
            }
            rememberLast(info.pkg);
            toast("已在虚拟屏 #" + d + " 打开 " + info.label);
            if (Prefs.getBool(Prefs.K_AUTO_RETURN, true)) {
                sleep(1200);
                VScreen.bringSelfHome(getPackageName());
                Logger.log("自动返回本桌面");
            }
            refreshDisplays();
        });
    }

    private void rememberLast(String pkg) {
        String cur = Prefs.get(Prefs.K_LAST, "");
        List<String> l = new ArrayList<>();
        l.add(pkg);
        for (String s : cur.split(",")) {
            if (!s.isEmpty() && !s.equals(pkg) && l.size() < 4) l.add(s);
        }
        Prefs.put(Prefs.K_LAST, TextUtils.join(",", l));
    }

    // ---------------------------------------------------------------- screen page

    private void initScreenPage() {
        dispAdapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return displays.size();
            }

            @Override
            public Object getItem(int position) {
                return displays.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = convertView;
                if (v == null) {
                    v = getLayoutInflater().inflate(android.R.layout.simple_list_item_2, parent, false);
                }
                VScreen.Disp d = displays.get(position);
                TextView t1 = v.findViewById(android.R.id.text1);
                TextView t2 = v.findViewById(android.R.id.text2);
                t1.setText("显示屏 #" + d.id + (d.isOverlay() ? "  ★ 本工具创建" : ""));
                t2.setText(d.kind() + " · " + d.w + "x" + d.h + " · " + d.uniqueId
                        + (d.name == null || d.name.isEmpty() ? "" : " · " + d.name));
                v.setActivated(d.id == selectedDisplay);
                return v;
            }
        };
        lvDisplays.setAdapter(dispAdapter);
        lvDisplays.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= displays.size()) return;
            selectedDisplay = displays.get(position).id;
            dispAdapter.notifyDataSetChanged();
            updateScreenInfo();
            toast("已选中显示屏 #" + selectedDisplay);
        });

        btnCreate.setOnClickListener(v -> {
            int w = parseInt(etW, 720), h = parseInt(etH, 1280), dpi = parseInt(etDpi, 280);
            Prefs.putInt(Prefs.K_W, w);
            Prefs.putInt(Prefs.K_H, h);
            Prefs.putInt(Prefs.K_DPI, dpi);
            toast("正在创建 " + w + "x" + h + " / " + dpi + "dpi …");
            bg(() -> {
                int d = VScreen.create(w, h, dpi);
                toast(d >= 0 ? "虚拟屏已创建：display " + d : "创建失败（看日志）");
                if (d >= 0) selectedDisplay = d;
                refreshDisplays();
            });
        });

        btnDestroy.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("销毁全部虚拟屏？")
                .setMessage("会清空 overlay_display_devices 设置，屏上的应用会回到主屏。")
                .setPositiveButton("销毁", (d, w) -> bg(() -> {
                    VScreen.destroyAll();
                    Logger.log("销毁全部虚拟屏");
                    selectedDisplay = -1;
                    refreshDisplays();
                }))
                .setNegativeButton("取消", null)
                .show());

        btnRefresh.setOnClickListener(v -> refreshDisplays());

        btnHome.setOnClickListener(v -> bg(() -> {
            VScreen.bringSelfHome(getPackageName());
            Logger.log("手动返回本桌面");
        }));

        btnStopTop.setOnClickListener(v -> bg(() -> {
            int d = selectedDisplay >= 0 ? selectedDisplay : overlayId();
            if (d < 0) {
                toast("没有可用的虚拟屏");
                return;
            }
            String pkg = VScreen.topOn(d);
            if (pkg == null || pkg.isEmpty()) {
                toast("屏 #" + d + " 上没有前台应用");
                return;
            }
            VScreen.forceStop(pkg);
            Logger.log("停止屏#" + d + " 上的 " + pkg);
            toast("已停止 " + pkg);
            refreshDisplays();
        }));

        btnBattery.setOnClickListener(v -> requestIgnoreBattery());

        btnScreenOff.setOnClickListener(v -> bg(() -> {
            int d = selectedDisplay >= 0 ? selectedDisplay : overlayId();
            if (d < 0) {
                toast("没有可用的虚拟屏");
                return;
            }
            String pkg = VScreen.topOn(d);
            if (pkg == null || pkg.isEmpty()) {
                toast("屏 #" + d + " 上没有前台应用");
                return;
            }
            VScreen.forceStop(pkg);
            Logger.log("关闭屏#" + d + " 上的 " + pkg);
            toast("已关闭 " + pkg);
        }));

        cbAutoReturn.setOnCheckedChangeListener((b, checked) -> {
            Prefs.putBool(Prefs.K_AUTO_RETURN, checked);
            Logger.log("自动返回本桌面：" + checked);
        });

        cbBall.setOnCheckedChangeListener((b, checked) -> {
            Prefs.putBool(Prefs.K_BALL, checked);
            if (checked) startBall();
            else stopBall();
        });
    }

    private void refreshDisplays() {
        bg(() -> {
            List<VScreen.Disp> l = VScreen.list();
            String spec = VScreen.currentSpec();
            main.post(() -> {
                displays.clear();
                displays.addAll(l);
                if (selectedDisplay >= 0) {
                    boolean still = false;
                    for (VScreen.Disp d : l) if (d.id == selectedDisplay) still = true;
                    if (!still) selectedDisplay = -1;
                }
                if (selectedDisplay < 0) selectedDisplay = overlayId();
                dispAdapter.notifyDataSetChanged();
                updateScreenInfo();
                int ov = 0;
                for (VScreen.Disp d : l) if (d.isOverlay()) ov++;
                tvVScreen.setText(ov > 0 ? ("虚拟屏：" + ov + " 块 · " + (!spec.isEmpty() ? spec : ""))
                        : "虚拟屏：未创建");
            });
        });
    }

    private int overlayId() {
        for (VScreen.Disp d : displays) if (d.isOverlay()) return d.id;
        return -1;
    }

    private void updateScreenInfo() {
        if (selectedDisplay < 0) {
            tvScreenInfo.setText("当前屏幕：无虚拟屏\n点「创建虚拟屏」或直接点应用会自动建屏");
            return;
        }
        final int id = selectedDisplay;
        String desc = "显示 #" + id;
        for (VScreen.Disp x : displays) {
            if (x.id == id) desc = x.desc();
        }
        tvScreenInfo.setText("当前选中：" + desc + "\n屏上前台应用：读取中…");
        bg(() -> {
            String p = VScreen.topOn(id);
            String d2 = "显示 #" + id;
            for (VScreen.Disp z : VScreen.list()) {
                if (z.id == id) d2 = z.desc();
            }
            final String fd = d2;
            main.post(() -> tvScreenInfo.setText("当前选中：" + fd
                    + "\n屏上前台应用：" + (p == null || p.isEmpty() ? "（无）" : p)));
        });
    }

    /** 返回可用的虚拟屏 id；没有就按输入参数新建一块 */
    private int ensureDisplay() {
        if (selectedDisplay >= 0 && VScreen.byId(selectedDisplay) != null
                && VScreen.byId(selectedDisplay).isOverlay()) {
            return selectedDisplay;
        }
        VScreen.Disp d = VScreen.pickOverlay();
        if (d != null) {
            selectedDisplay = d.id;
            return d.id;
        }
        int w = Prefs.getInt(Prefs.K_W, 720);
        int h = Prefs.getInt(Prefs.K_H, 1280);
        int dpi = Prefs.getInt(Prefs.K_DPI, 280);
        int id = VScreen.create(w, h, dpi);
        if (id >= 0) selectedDisplay = id;
        return id;
    }

    // ---------------------------------------------------------------- log page

    private void initLogPage() {
        logAdapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return Logger.snapshot().size();
            }

            @Override
            public Object getItem(int position) {
                return Logger.snapshot().get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = convertView instanceof TextView ? (TextView) convertView : null;
                if (tv == null) {
                    tv = new TextView(MainActivity.this);
                    tv.setTextSize(11.5f);
                    tv.setTextColor(getColor(R.color.text));
                    tv.setPadding(8, 6, 8, 6);
                }
                List<String> l = Logger.snapshot();
                tv.setText(position < l.size() ? l.get(position) : "");
                return tv;
            }
        };
        lvLog.setAdapter(logAdapter);
        btnClearLog.setOnClickListener(v -> {
            Logger.clear();
            refreshLog();
        });
        btnRefreshLog.setOnClickListener(v -> refreshLog());
    }

    private void refreshLog() {
        if (logAdapter != null) logAdapter.notifyDataSetChanged();
    }

    // ---------------------------------------------------------------- floating ball

    private void startBall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            toast("请先授予「显示在其他应用上层」权限");
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())), REQ_OVERLAY);
            } catch (Throwable t) {
                toast("无法打开悬浮窗权限设置");
            }
            return;
        }
        Intent it = new Intent(this, FloatingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(it);
        else startService(it);
        Logger.log("悬浮球：已启动");
    }

    private void stopBall() {
        stopService(new Intent(this, FloatingService.class));
        Logger.log("悬浮球：已停止");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            boolean ok = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
            if (ok && cbBall.isChecked()) startBall();
            else if (!ok) {
                cbBall.setChecked(false);
                Prefs.putBool(Prefs.K_BALL, false);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

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

    private static String firstLine(String s) {
        if (s == null || s.isEmpty()) return "无输出";
        String[] a = s.split("\n");
        return a[0].length() > 120 ? a[0].substring(0, 120) : a[0];
    }

    private static int parseInt(EditText et, int def) {
        try {
            return Integer.parseInt(et.getText().toString().trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
