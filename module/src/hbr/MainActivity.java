package hbr;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HyperOS Enhanced Brightness 模块设置界面（无资源文件，全部代码构建）：
 *  - 自动亮度增幅：100%–200% 滑块（×0.1% 步进），写 persist.hyperbrightness.autobr.pct，
 *    软重启 system_server 后生效（资源数组在 system_server 启动时读取）
 *  - 阳光上限：nit 模式滑块（刻度来自本机厂商标定表 dumpsys mBacklight/mNits），
 *    写 persist.hyperbrightness.sunlight.target；无标定表回落倍率模式
 *  - 两个功能各自的开关按钮
 *  - 读数面板 + 软重启按钮
 */
public class MainActivity extends Activity {
    private static final String P_AB_ENABLE = "persist.hyperbrightness.autobr.enable";
    private static final String P_AB_PCT = "persist.hyperbrightness.autobr.pct";
    private static final String P_SL_ENABLE = "persist.hyperbrightness.sunlight.enable";
    private static final String P_SL_TARGET = "persist.hyperbrightness.sunlight.target";
    private static final String P_SL_PCT = "persist.hyperbrightness.sunlight.pct";
    private static final String P_KNOTS = "persist.hyperbrightness.knots";
    private static final String PREFS = "hbr_prefs";
    private static final String KEY_NIT = "target_nit";          // UI 记忆：阳光目标 nit
    private static final int DEFAULT_AB_PCT = 1300;
    private static final Pattern BOOST_RE = Pattern.compile("mMaxManualBoostBrightness=([0-9.eE+-]+)");
    private static final Pattern BL_RE = Pattern.compile("mBacklight=\\[([^\\]]*)\\]");
    private static final Pattern NITS_RE = Pattern.compile("mNits=\\[([^\\]]*)\\]");
    private static final Pattern KNOT_RE =
            Pattern.compile("<value>([0-9.eE+-]+)</value>\\s*<nits>([0-9.eE+-]+)</nits>");

    private LinearLayout abBox, slBox;
    private TextView abLabel, slLabel, info;
    private String slMode = null;        // "nit" / "pct"
    private float[] tblBL, tblNit;       // 阳光滑块用厂商标定表（dumpsys mBacklight/mNits）
    private String knotsSpec = null;     // displayconfig XML 解析出的面板结点
    private String sliderSig = null;
    // props 当前值
    private int abEnable = 1, slEnable = 1, abPct = DEFAULT_AB_PCT;
    private String slTarget = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = dp(16);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("HyperOS Enhanced Brightness\n阳光上限 + 自动亮度增强（合并版）");
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        info = new TextView(this);
        info.setTypeface(Typeface.MONOSPACE);
        info.setTextSize(12);
        info.setPadding(0, dp(12), 0, dp(12));
        info.setText("读取中…（需要 root 授权）");
        root.addView(info);

        // ---- 自动亮度区 ----
        TextView abHead = new TextView(this);
        abHead.setText("① 自动亮度曲线增幅");
        abHead.setTypeface(Typeface.DEFAULT_BOLD);
        abHead.setPadding(0, dp(14), 0, dp(4));
        root.addView(abHead);
        abLabel = new TextView(this);
        abLabel.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(abLabel);
        abBox = new LinearLayout(this);
        abBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(abBox);

        // ---- 阳光区 ----
        TextView slHead = new TextView(this);
        slHead.setText("② 阳光模式手动上限");
        slHead.setTypeface(Typeface.DEFAULT_BOLD);
        slHead.setPadding(0, dp(14), 0, dp(4));
        root.addView(slHead);
        slLabel = new TextView(this);
        slLabel.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(slLabel);
        slBox = new LinearLayout(this);
        slBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(slBox);

        Button refresh = new Button(this);
        refresh.setText("刷新读数");
        refresh.setOnClickListener(v -> refresh());
        root.addView(refresh);

        Button restart = new Button(this);
        restart.setText("软重启（重启 system_server，应用所有改动）");
        restart.setOnClickListener(v -> new AlertDialog.Builder(MainActivity.this)
                .setTitle("软重启")
                .setMessage("将重启 system_server：屏幕短暂黑屏、所有应用重新加载，"
                        + "等效于重启但更快。自动亮度曲线与阳光上限在此时生效。\n\n继续？")
                .setPositiveButton("重启", (d, w) -> su("killall system_server"))
                .setNegativeButton("取消", null)
                .show());
        root.addView(restart);

        TextView note = new TextView(this);
        note.setTextSize(11);
        note.setTextColor(0xFF888888);
        note.setPadding(0, dp(14), 0, 0);
        note.setText("配置经 persist.hyperbrightness.* 属性传递（保存时需 root 授权一次）。"
                + "自动亮度：拦截 system_server 资源数组读取，背光表 ×K、nits 按面板样条重算，"
                + "等效于 autobrightness_boost 的 arsc 补丁；改动需软重启生效。"
                + "阳光上限：hook DisplayPowerControllerImpl.init 改 mMaxManualBoostBrightness。"
                + "若设备仍装有旧模块 Hyper-Sunlight-Unlocker，请在 LSPosed 停用它，避免双重修改。"
                + "非 OS3 系统模块自动不 Hook。");
        root.addView(note);

        setContentView(scroll);
        installSliders();
        refresh();
    }

    // ---------- 滑块 ----------

    private void installSliders() {
        installAbSlider();
        installSlSlider();
    }

    private void installAbSlider() {
        abBox.removeAllViews();
        int pct = (abPct >= 1000 && abPct <= 2000) ? abPct : DEFAULT_AB_PCT;
        abLabel.setText("增幅：" + pctLabel(pct) + "（同一环境光下自动亮度更亮，软重启生效）");
        SeekBar bar = new SeekBar(this);
        bar.setMax(1000);   // 100.0% ~ 200.0%
        bar.setProgress(pct - 1000);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                abLabel.setText("增幅：" + pctLabel(1000 + p));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                int v = 1000 + s.getProgress();
                abPct = v;
                saveProp(P_AB_PCT, String.valueOf(v));
                writeKnots();
                Toast.makeText(MainActivity.this,
                        "已保存 " + pctLabel(v) + "，软重启后生效", Toast.LENGTH_SHORT).show();
            }
        });
        abBox.addView(bar);
        Button tgl = new Button(this);
        tgl.setText("自动亮度增强：" + (abEnable == 1 ? "开" : "关"));
        tgl.setOnClickListener(v -> {
            int nv = abEnable == 1 ? 0 : 1;
            abEnable = nv;
            saveProp(P_AB_ENABLE, String.valueOf(nv));
            tgl.setText("自动亮度增强：" + (nv == 1 ? "开" : "关"));
            Toast.makeText(MainActivity.this, "已" + (nv == 1 ? "启用" : "停用")
                    + "（软重启生效）", Toast.LENGTH_SHORT).show();
        });
        abBox.addView(tgl);
    }

    private void installSlSlider() {
        boolean hasTable = tblBL != null && tblNit != null
                && tblBL.length == tblNit.length && tblBL.length >= 2;
        String mode = hasTable ? "nit" : "pct";
        int nitLo = 0, nitHi = 0;
        float stockNit = -1f;
        if (hasTable) {
            // 出厂上限必须用资源真值（hook 生效后 dumpsys 里的值已是目标值）
            float stock = stockResFloat > 0f ? stockResFloat : 0.593761f; // dash 出厂参考
            if (stock > 0f) stockNit = floatToNit(stock);
            nitLo = (stockNit > 0) ? Math.round(stockNit) : Math.round(tblNit[1]);
            nitHi = Math.round(tblNit[tblNit.length - 1]);
        }
        String sig = mode + ":" + nitLo + ":" + nitHi + ":" + slEnable;
        if (sig.equals(sliderSig)) return;
        sliderSig = sig;
        slMode = mode;
        slBox.removeAllViews();

        Button tgl = new Button(this);
        tgl.setText("阳光上限解锁：" + (slEnable == 1 ? "开" : "关"));
        tgl.setOnClickListener(v -> {
            int nv = slEnable == 1 ? 0 : 1;
            slEnable = nv;
            saveProp(P_SL_ENABLE, String.valueOf(nv));
            tgl.setText("阳光上限解锁：" + (nv == 1 ? "开" : "关"));
            Toast.makeText(MainActivity.this, "已" + (nv == 1 ? "启用" : "停用")
                    + "（软重启生效）", Toast.LENGTH_SHORT).show();
        });
        slBox.addView(tgl);

        if ("nit".equals(mode)) {
            if (nitHi - nitLo < 10) {
                slLabel.setText("出厂上限已接近面板峰值，无可调空间（≈" + nitLo + " nit）");
                return;
            }
            final int fNitLo = nitLo, fNitHi = nitHi;
            SeekBar bar = new SeekBar(this);
            bar.setMax(fNitHi - fNitLo);
            int cur = loadNit(fNitLo, fNitHi);
            bar.setProgress(cur - nitLo);
            slLabel.setText("上限：≈ " + cur + " nit");
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    slLabel.setText("上限：≈ " + (fNitLo + p) + " nit");
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                    int nit = fNitLo + s.getProgress();
                    float f = nitToFloat(nit);
                    saveProp(P_SL_TARGET, String.valueOf(Math.round(f * 1000000f)));
                    try {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putInt(KEY_NIT, nit).commit();
                    } catch (Throwable ignored) {
                    }
                    Toast.makeText(MainActivity.this,
                            "已保存：上限 ≈ " + nit + " nit，软重启后生效",
                            Toast.LENGTH_SHORT).show();
                }
            });
            slBox.addView(bar);
        } else {
            slLabel.setText("本机未读到标定表：改用倍率模式");
            SeekBar bar = new SeekBar(this);
            bar.setMax(1000);   // 100.0% ~ 200.0%
            bar.setProgress(1068 - 1000);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    slLabel.setText("出厂倍率：" + pctLabel(1000 + p));
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                    saveProp(P_SL_PCT, String.valueOf(1000 + s.getProgress()));
                    Toast.makeText(MainActivity.this,
                            "已保存 " + pctLabel(1000 + s.getProgress()) + "，软重启后生效",
                            Toast.LENGTH_SHORT).show();
                }
            });
            slBox.addView(bar);
        }
    }

    private static String pctLabel(int pct) {
        return (pct / 10) + "." + (pct % 10) + "%";
    }

    private int loadNit(int lo, int hi) {
        try {
            int v = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_NIT, lo);
            if (v >= lo && v <= hi) return v;
        } catch (Throwable ignored) {
        }
        return lo;   // 起步 = 阳光模式原厂上限
    }

    // ---------- 标定表换算（厂商分段线性） ----------

    private float nitToFloat(int nit) {
        float n = Math.max(tblNit[0], Math.min(tblNit[tblNit.length - 1], nit));
        for (int i = 0; i < tblNit.length - 1; i++) {
            if (n <= tblNit[i + 1]) {
                float t = (n - tblNit[i]) / (tblNit[i + 1] - tblNit[i]);
                return tblBL[i] + t * (tblBL[i + 1] - tblBL[i]);
            }
        }
        return tblBL[tblBL.length - 1];
    }

    private int floatToNit(float f) {
        float x = Math.max(tblBL[0], Math.min(tblBL[tblBL.length - 1], f));
        for (int i = 0; i < tblBL.length - 1; i++) {
            if (x <= tblBL[i + 1]) {
                float t = (x - tblBL[i]) / (tblBL[i + 1] - tblBL[i]);
                return Math.round(tblNit[i] + t * (tblNit[i + 1] - tblNit[i]));
            }
        }
        return Math.round(tblNit[tblNit.length - 1]);
    }

    // ---------- knots（面板 亮度→nits 结点，供 hook 的 nits 重算用） ----------

    private void writeKnots() {
        if (knotsSpec == null || knotsSpec.isEmpty()) return;
        saveProp(P_KNOTS, knotsSpec);
    }

    // ---------- su 读数 ----------

    private float stockFloat = -1f;      // dumpsys 里的当前生效值（hook 后=目标值）
    private float stockResFloat = -1f;   // 资源里的出厂真值（不随 hook 改变）

    /** GUI 侧直接读同一个出厂资源（无需 root）；失败再靠 hook 日志兜底。 */
    private float readStockFromResource() {
        // MIUI 会把 android.miui 扩展资源表挂进每个 app 的 AssetManager
        try {
            int id = getResources().getIdentifier("config_max_manual_brt_boost", "dimen", "android.miui");
            if (id != 0) {
                float v = getResources().getFloat(id);
                if (v > 0f && v <= 1.0f) return v;
            }
        } catch (Throwable ignored) {
        }
        return -1f;
    }

    private void refresh() {
        info.setText("读取中…（需要 root 授权）");
        new Thread(() -> {
            String out = su(
                    "getprop ro.mi.os.version.name; "
                            + "echo ---PROPS---; "
                            + "getprop " + P_AB_ENABLE + "; "
                            + "getprop " + P_AB_PCT + "; "
                            + "getprop " + P_SL_ENABLE + "; "
                            + "getprop " + P_SL_TARGET + "; "
                            + "getprop persist.sunlightboost.target; "
                            + "getprop " + P_KNOTS + "; "
                            + "echo ---CUR---; cat /sys/class/mi_display/disp-DSI-0/brightness_clone; "
                            + "echo ---MAX---; cat /sys/class/leds/lcd-backlight/max_brightness; "
                            + "echo ---SKIN---; cat /sys/class/thermal/thermal_message/board_sensor_temp; "
                            + "echo ---BOOST---; dumpsys display | "
                            + "grep -E 'mMaxManualBoostBrightness|mBacklight=\\['; "
                            + "echo ---XML---; cat /product/etc/displayconfig/display_id_*.xml 2>/dev/null; "
                            + "echo ---OLD---; pm list packages com.sunlightboost.lsp");
            show(parse(out));
        }).start();
    }

    private String parse(String out) {
        String os = seg(out, null, "---PROPS---");
        String props = seg(out, "---PROPS---", "---CUR---");
        String[] pv = props.split("\n");
        int i = 0;
        abEnable = intAt(pv, i++, 1);
        abPct = intAt(pv, i++, DEFAULT_AB_PCT);
        slEnable = intAt(pv, i++, 1);
        slTarget = (pv.length > i) ? pv[i].trim() : "";
        i++;
        String oldTarget = (pv.length > i) ? pv[i].trim() : "";
        i++;
        String knotsRaw = (pv.length > i) ? pv[i].trim() : "";
        if (slTarget.isEmpty() && !oldTarget.isEmpty()) slTarget = oldTarget + "*";

        long cur = num(seg(out, "---CUR---", "---MAX---"));
        long max = num(seg(out, "---MAX---", "---SKIN---"));
        if (max <= 0) max = 16383;
        long skin = num(seg(out, "---SKIN---", "---BOOST---"));
        String boost = seg(out, "---BOOST---", "---XML---");
        String xml = seg(out, "---XML---", "---OLD---");
        String oldmod = seg(out, "---OLD---", null);

        stockResFloat = readStockFromResource();
        stockFloat = -1f;
        if (boost != null) {
            Matcher m = BOOST_RE.matcher(boost);
            if (m.find()) {
                try {
                    stockFloat = Float.parseFloat(m.group(1));
                } catch (Throwable ignored) {
                }
            }
            Matcher mb = BL_RE.matcher(boost);
            Matcher mn = NITS_RE.matcher(boost);
            if (mb.find() && mn.find()) {
                float[] bl = parseFloatArray(mb.group(1));
                float[] ni = parseFloatArray(mn.group(1));
                if (bl != null && ni != null && bl.length == ni.length && bl.length >= 2) {
                    tblBL = bl;
                    tblNit = ni;
                }
            }
        }
        // displayconfig XML → 面板 亮度→nits 结点
        try {
            Matcher km = KNOT_RE.matcher(xml);
            List<String> pairs = new ArrayList<>();
            while (km.find()) {
                pairs.add(km.group(1) + ":" + km.group(2));
            }
            if (pairs.size() >= 2) knotsSpec = join(pairs);
        } catch (Throwable ignored) {
        }
        boolean hasOld = oldmod != null && oldmod.contains("com.sunlightboost.lsp");

        StringBuilder sb = new StringBuilder();
        sb.append("系统：").append(os.trim().isEmpty() ? "?" : os.trim()).append('\n');
        sb.append("自动亮度增幅 prop：").append(abEnable == 1 ? "启用，" : "停用，")
          .append(pctLabel(abPct >= 1000 && abPct <= 3000 ? abPct : DEFAULT_AB_PCT)).append('\n');
        sb.append("阳光上限 prop：").append(slEnable == 1 ? "启用，" : "停用，");
        if (!slTarget.isEmpty()) {
            boolean compat = slTarget.endsWith("*");
            String val = compat ? slTarget.substring(0, slTarget.length() - 1) : slTarget;
            try {
                float f = Long.parseLong(val) / 1000000f;
                sb.append("target=").append(f);
                if (tblBL != null) sb.append("（≈").append(floatToNit(f)).append(" nit）");
                if (compat) sb.append("（旧属性兼容，保存后覆盖）");
            } catch (Throwable t) {
                sb.append(slTarget);
            }
        } else {
            sb.append("未设置（默认 106.8%）");
        }
        sb.append('\n');
        if (!knotsRaw.isEmpty()) sb.append("knots：已写入 ✓\n");
        if (hasOld) sb.append("⚠ 旧模块 com.sunlightboost.lsp 仍安装——请在 LSPosed 停用，避免冲突\n");
        if (cur >= 0) {
            sb.append("当前 DBV：").append(cur).append(" / ").append(max)
              .append("（").append(cur * 100 / max).append("%）\n");
        }
        if (skin > -100000 && skin != 0) {
            sb.append("皮肤温度：").append(skin / 1000).append(".")
              .append((skin % 1000) / 100).append(" °C\n");
        }
        if (stockResFloat > 0f) {
            sb.append("出厂阳光上限：").append(stockResFloat)
              .append(tblBL != null ? "（≈" + floatToNit(stockResFloat) + " nit，滑块下限）" : "")
              .append('\n');
        }
        if (stockFloat > 0f) {
            sb.append("当前生效 mMaxManualBoostBrightness：").append(stockFloat)
              .append(tblBL != null ? "（≈" + floatToNit(stockFloat) + " nit）" : "")
              .append("；软重启后应等于阳光目标\n");
        } else {
            sb.append("dumpsys 未读到 mMaxManualBoostBrightness（软重启后出现）\n");
        }
        sb.append("knots 来源：").append(knotsSpec != null ? "本机 displayconfig" : "内置默认（dash）");
        return sb.toString();
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p);
        }
        return sb.toString();
    }

    private static int intAt(String[] arr, int idx, int def) {
        try {
            String s = arr[idx].trim();
            if (!s.isEmpty()) return Integer.parseInt(s);
        } catch (Throwable ignored) {
        }
        return def;
    }

    private static float[] parseFloatArray(String s) {
        try {
            String[] parts = s.split(",\\s*");
            float[] out = new float[parts.length];
            for (int i = 0; i < parts.length; i++) out[i] = Float.parseFloat(parts[i].trim());
            for (int i = 0; i < out.length - 1; i++) {
                if (out[i + 1] <= out[i]) return null;   // 必须单调递增
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private void show(final String text) {
        runOnUiThread(() -> {
            info.setText(text);
            installSliders();   // 拿到标定表后切换 nit 模式 / prop 读数
        });
    }

    private void saveProp(String name, String value) {
        final String cmd = "setprop " + name + " " + value;
        new Thread(() -> su(cmd)).start();
    }

    private static String su(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            p.waitFor();
            p.destroy();
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static long num(String s) {
        try {
            s = s.split("\n")[0].trim();
            if (!s.isEmpty()) return Long.parseLong(s);
        } catch (Throwable ignored) {
        }
        return -999999;
    }

    private static String seg(String out, String start, String end) {
        if (out == null) return "";
        int a = 0;
        if (start != null) {
            a = out.indexOf(start);
            if (a < 0) return "";
            a += start.length();
        }
        int b = (end == null) ? out.length() : out.indexOf(end, a);
        if (b < 0) b = out.length();
        return out.substring(a, b).trim();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}