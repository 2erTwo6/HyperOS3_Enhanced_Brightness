package hbr;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HyperOS Enhanced Brightness 模块设置界面（无资源文件，全部代码构建）：
 *  - 自动亮度增幅：100%–200% 滑块（×0.1% 步进），写 persist.hyperbrightness.autobr.pct，
 *    软重启 system_server 后生效（资源数组在 system_server 启动时读取）
 *  - 阳光上限：nit 模式滑块（刻度来自本机厂商标定表 dumpsys mBacklight/mNits），
 *    写 persist.hyperbrightness.sunlight.target；无标定表回落倍率模式
 *  - 读数面板 + 软重启按钮
 *
 * 界面为代码手绘的 Material 3 风格：设计 token 跟随系统深浅色，卡片圆角 + 描边 + 轻投影，
 * 自绘开关（不依赖 MIUI 皮肤），控件全部走本文件内的绘制/布局辅助方法。
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

    private static final int MP = LinearLayout.LayoutParams.MATCH_PARENT;
    private static final int WC = LinearLayout.LayoutParams.WRAP_CONTENT;

    // ---------- 设计 token（浅色 / 深色两套，跟随系统） ----------

    private boolean dark;
    private int cBg, cSurface, cSurfaceVar, cOutline, cText, cTextDim;
    private int cAccent, cAccentSoft, cSun, cSunSoft, cWarn;
    private float dens = 1f;
    private Typeface tfMed, tfMono;

    // ---------- 视图 ----------

    private LinearLayout abBody, slBody, readoutBox;
    private TextView abValue, slValue;
    private Pill abPill, slPill;
    private ProgressBar readoutSpin;

    // ---------- 状态 ----------

    private float[] tblBL, tblNit;       // 阳光滑块用厂商标定表（dumpsys mBacklight/mNits）
    private String knotsSpec = null;     // displayconfig XML 解析出的面板结点
    private String sliderSig = null;
    private float stockFloat = -1f;      // dumpsys 里的当前生效值（hook 后=目标值）
    private float stockResFloat = -1f;   // 资源里的出厂真值（不随 hook 改变）
    private int abEnable = 1, slEnable = 1, abPct = DEFAULT_AB_PCT;
    private String slTarget = null;

    // ---------- 读数模型（parse 填充 → renderReadout 渲染） ----------

    private String mOs = "?";
    private boolean mKnotsOk, mOldModule;
    private long mCur = -999999, mMax = 16383, mSkin = -999999;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dens = getResources().getDisplayMetrics().density;
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        resolveColors();
        tfMed = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        if (tfMed == null) tfMed = Typeface.DEFAULT_BOLD;
        tfMono = Typeface.MONOSPACE;
        requestWindowFeature(Window.FEATURE_NO_TITLE);   // 去掉系统 ActionBar
        buildUi();
        installSliders();
        refresh();
    }

    private void resolveColors() {
        if (dark) {
            cBg = 0xFF0E1116;
            cSurface = 0xFF161B22;
            cSurfaceVar = 0xFF1E242D;
            cOutline = 0xFF272E38;
            cText = 0xFFE9EEF5;
            cTextDim = 0xFF9AA6B4;
            cAccent = 0xFF6EA8FF;
            cAccentSoft = 0xFF17253C;
            cSun = 0xFFFFC24D;
            cSunSoft = 0xFF2E2413;
            cWarn = 0xFFFF8A80;
        } else {
            cBg = 0xFFF4F6FA;
            cSurface = 0xFFFFFFFF;
            cSurfaceVar = 0xFFEDF1F7;
            cOutline = 0xFFE1E7EF;
            cText = 0xFF111720;
            cTextDim = 0xFF525C6A;
            cAccent = 0xFF2A6DF4;
            cAccentSoft = 0xFFE8F0FE;
            cSun = 0xFFE08A00;
            cSunSoft = 0xFFFDF3E0;
            cWarn = 0xFFD93025;
        }
    }

    private int onAccent() {
        return dark ? 0xFF0B1220 : 0xFFFFFFFF;
    }

    // ---------- 界面搭建 ----------

    private void buildUi() {
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(cBg));
        w.setStatusBarColor(cBg);
        w.setNavigationBarColor(cBg);
        w.getDecorView().setSystemUiVisibility(dark ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(cBg);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, dp(20), pad, dp(30));
        scroll.addView(root);

        root.addView(titleBlock());
        root.addView(abCard(), marginTop(dp(18)));
        root.addView(slCard(), marginTop(dp(12)));
        root.addView(readoutCard(), marginTop(dp(12)));
        root.addView(filledButton("软重启 system_server", cAccent, onAccent(),
                v -> confirmRestart()), marginTop(dp(18)));
        root.addView(filledButton("刷新读数", cSurfaceVar, cAccent,
                v -> refresh()), marginTop(dp(10)));
        root.addView(footer(), marginTop(dp(18)));

        setContentView(scroll);
    }

    private View titleBlock() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = textView("HyperOS 增强亮度", 23, cText, tfMed);
        t.setLetterSpacing(0.01f);
        box.addView(t);
        TextView s = textView("阳光上限解锁 · 自动亮度曲线增幅", 12.5f, cTextDim, Typeface.DEFAULT);
        s.setPadding(0, dp(5), 0, 0);
        box.addView(s);
        return box;
    }

    private View abCard() {
        LinearLayout card = cardShell();
        abPill = new Pill(cAccent, dark ? 0xFF39414D : 0xFFC9D2DE);
        abPill.setLabel("自动亮度增强");
        abPill.setChecked(abEnable == 1, false);
        abPill.setToggleAction(() -> {
            int nv = abEnable == 1 ? 0 : 1;
            abEnable = nv;
            saveProp(P_AB_ENABLE, String.valueOf(nv));
            toast("已" + (nv == 1 ? "启用" : "停用") + "自动亮度增强（软重启生效）");
        });
        card.addView(headerRow("◐", cAccent, cAccentSoft,
                "自动亮度曲线增幅", "系统亮度曲线整体拔高", abPill));
        abValue = textView("—", 30, cText, tfMed);
        card.addView(valueRow(abValue, "倍率"));
        abBody = new LinearLayout(this);
        abBody.setOrientation(LinearLayout.VERTICAL);
        card.addView(abBody);
        return card;
    }

    private View slCard() {
        LinearLayout card = cardShell();
        slPill = new Pill(cSun, dark ? 0xFF39414D : 0xFFC9D2DE);
        slPill.setLabel("阳光上限解锁");
        slPill.setChecked(slEnable == 1, false);
        slPill.setToggleAction(() -> {
            int nv = slEnable == 1 ? 0 : 1;
            slEnable = nv;
            saveProp(P_SL_ENABLE, String.valueOf(nv));
            toast("已" + (nv == 1 ? "启用" : "停用") + "阳光上限解锁（软重启生效）");
        });
        card.addView(headerRow("☀", cSun, cSunSoft,
                "阳光模式手动上限", "mMaxManualBoostBrightness 解锁", slPill));
        slValue = textView("—", 30, cText, tfMed);
        card.addView(valueRow(slValue, null));
        slBody = new LinearLayout(this);
        slBody.setOrientation(LinearLayout.VERTICAL);
        card.addView(slBody);
        return card;
    }

    private View readoutCard() {
        LinearLayout card = cardShell();
        readoutSpin = new ProgressBar(this);
        readoutSpin.setIndeterminate(true);
        if (readoutSpin.getIndeterminateDrawable() != null) {
            readoutSpin.getIndeterminateDrawable().setTint(cAccent);
        }
        card.addView(headerRow("◎", cAccent, cAccentSoft,
                "设备读数", "实时读取 root 属性与面板状态", readoutSpin));
        readoutBox = new LinearLayout(this);
        readoutBox.setOrientation(LinearLayout.VERTICAL);
        readoutBox.setPadding(0, dp(6), 0, 0);
        card.addView(readoutBox);
        return card;
    }

    private View footer() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(textView("构建版本 " + BuildInfo.VERSION + " · " + BuildInfo.DATE,
                11.5f, cTextDim, Typeface.DEFAULT));
        TextView g = textView("GitHub 仓库 →", 12.5f, cAccent, tfMed);
        g.setPadding(0, dp(9), 0, 0);
        g.setOnClickListener(v -> openRepo());
        pressFeedback(g);
        box.addView(g);
        return box;
    }

    private void openRepo() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/2erTwo6/HyperOS3_Enhanced_Brightness")));
        } catch (Throwable t) {
            toast("无法打开浏览器");
        }
    }

    // ---------- 组件工厂 ----------

    private LinearLayout cardShell() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRect(cSurface, cOutline, 20));
        card.setElevation(dp(1.5f));
        int p = dp(16);
        card.setPadding(p, p, p, dp(14));
        return card;
    }

    private LinearLayout headerRow(String glyph, int accent, int soft,
                                   String title, String sub, View trailing) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView icon = textView(glyph, 16, accent, Typeface.DEFAULT);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(roundRect(soft, 0, 12));
        head.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(textView(title, 15, cText, tfMed));
        TextView s = textView(sub, 11.5f, cTextDim, Typeface.DEFAULT);
        s.setPadding(0, dp(3), 0, 0);
        titles.addView(s);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, WC, 1f);
        tlp.leftMargin = dp(12);
        head.addView(titles, tlp);

        if (trailing != null) {
            LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(WC, WC);
            xlp.leftMargin = dp(10);
            head.addView(trailing, xlp);
        }
        return head;
    }

    private LinearLayout valueRow(TextView value, String unit) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        row.setPadding(0, dp(12), 0, dp(4));
        row.addView(value);
        if (unit != null) {
            TextView u = textView(unit, 12, cTextDim, Typeface.DEFAULT);
            LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(WC, WC);
            ulp.leftMargin = dp(8);
            ulp.bottomMargin = dp(5);
            row.addView(u, ulp);
        }
        return row;
    }

    private View rangeRow(String lo, String hi) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(textView(lo, 11.5f, cTextDim, Typeface.DEFAULT),
                new LinearLayout.LayoutParams(0, WC, 1f));
        row.addView(textView(hi, 11.5f, cTextDim, Typeface.DEFAULT));
        return row;
    }

    private View hint(String s) {
        TextView t = textView(s, 11.5f, cTextDim, Typeface.DEFAULT);
        t.setPadding(0, dp(9), 0, 0);
        return t;
    }

    private View filledButton(String label, int bg, int fg, View.OnClickListener l) {
        TextView t = textView(label, 14.5f, fg, tfMed);
        t.setGravity(Gravity.CENTER);
        t.setBackground(roundRect(bg, 0, 14));
        t.setPadding(dp(12), dp(15), dp(12), dp(15));
        t.setOnClickListener(l);
        pressFeedback(t);
        return t;
    }

    private void pressFeedback(final View v) {
        v.setOnTouchListener((view, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) view.setAlpha(0.72f);
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) view.setAlpha(1f);
            return false;
        });
    }

    private void styleSeekBar(SeekBar bar, int accent) {
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(dark ? 0xFF2C333D : 0xFFDDE4EE));
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(0xFFFFFFFF);
        thumb.setStroke(dp(3), accent);
        thumb.setSize(dp(22), dp(22));
        bar.setThumb(thumb);
        bar.setSplitTrack(false);
        bar.setPadding(dp(4), dp(10), dp(4), dp(10));
    }

    private TextView textView(String s, float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        return t;
    }

    private GradientDrawable roundRect(int fill, int stroke, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != 0) d.setStroke(Math.max(1, dp(1)), stroke);
        return d;
    }

    private LinearLayout.LayoutParams marginTop(int m) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(MP, WC);
        p.topMargin = m;
        return p;
    }

    private int dp(float v) {
        return Math.round(v * dens);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------- 自绘开关（不依赖 MIUI 皮肤，观感统一） ----------

    private class Pill extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int onColor, offColor;
        private boolean checked;
        private float anim;              // 0 = 关，1 = 开
        private ValueAnimator running;
        private Runnable onToggle;
        private String label = "";

        Pill(int onColor, int offColor) {
            super(MainActivity.this);
            this.onColor = onColor;
            this.offColor = offColor;
            setClickable(true);
            setFocusable(true);
        }

        void setToggleAction(Runnable r) {
            onToggle = r;
        }

        void setLabel(String s) {
            label = s;
            announce();
        }

        private void announce() {
            if (!label.isEmpty()) {
                setContentDescription(label + (checked ? "：开" : "：关"));
            }
        }

        void setChecked(boolean v, boolean animate) {
            checked = v;
            announce();
            float target = v ? 1f : 0f;
            if (!animate) {
                if (running != null) running.cancel();
                anim = target;
                invalidate();
                return;
            }
            if (running != null) running.cancel();
            running = ValueAnimator.ofFloat(anim, target);
            running.setDuration(180);
            running.setInterpolator(new DecelerateInterpolator());
            running.addUpdateListener(a -> {
                anim = (float) a.getAnimatedValue();
                invalidate();
            });
            running.start();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(dp(50), dp(29));
        }

        @Override
        public boolean performClick() {
            super.performClick();
            setChecked(!checked, true);
            if (onToggle != null) onToggle.run();
            return true;
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            paint.setColor(blend(offColor, onColor, anim));
            cv.drawRoundRect(0, 0, w, h, r, r, paint);
            float pad = dp(3.5f);
            float kr = r - pad;
            float cx = pad + kr + (w - 2 * (pad + kr)) * anim;
            paint.setColor(0xFFFFFFFF);
            cv.drawCircle(cx, r, kr, paint);
        }
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    // ---------- 滑块 ----------

    private void installSliders() {
        installAbSlider();
        installSlSlider();
    }

    private void installAbSlider() {
        int pct = (abPct >= 1000 && abPct <= 2000) ? abPct : DEFAULT_AB_PCT;
        abPill.setChecked(abEnable == 1, false);
        abValue.setText(pctLabel(pct));
        abBody.removeAllViews();
        SeekBar bar = new SeekBar(this);
        styleSeekBar(bar, cAccent);
        bar.setMax(1000);   // 100.0% ~ 200.0%
        bar.setProgress(pct - 1000);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                abValue.setText(pctLabel(1000 + p));
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
                toast("已保存 " + pctLabel(v) + "，软重启后生效");
            }
        });
        abBody.addView(bar);
        abBody.addView(rangeRow("100%", "200%"));
        abBody.addView(hint("同一环境光下更亮 · 改动需软重启生效"));
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
        slPill.setChecked(slEnable == 1, false);
        if (sig.equals(sliderSig)) return;
        sliderSig = sig;
        slBody.removeAllViews();

        if ("nit".equals(mode)) {
            if (nitHi - nitLo < 10) {
                slValue.setText("≈ " + nitLo + " nit");
                slBody.addView(hint("出厂上限已接近面板峰值，无可调空间"));
                return;
            }
            final int fNitLo = nitLo, fNitHi = nitHi;
            final int cur = loadNit(fNitLo, fNitHi);
            slValue.setText("≈ " + cur + " nit");
            SeekBar bar = new SeekBar(this);
            styleSeekBar(bar, cSun);
            bar.setMax(fNitHi - fNitLo);
            bar.setProgress(cur - nitLo);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    slValue.setText("≈ " + (fNitLo + p) + " nit");
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
                    toast("已保存：上限 ≈ " + nit + " nit，软重启后生效");
                }
            });
            slBody.addView(bar);
            slBody.addView(rangeRow(fNitLo + " nit", fNitHi + " nit"));
            slBody.addView(hint("出厂上限 ≈ " + Math.round(stockNit > 0 ? stockNit : fNitLo)
                    + " nit · 改动需软重启生效"));
        } else {
            int pct = 1068;
            slValue.setText(pctLabel(pct));
            SeekBar bar = new SeekBar(this);
            styleSeekBar(bar, cSun);
            bar.setMax(1000);   // 100.0% ~ 200.0%
            bar.setProgress(pct - 1000);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    slValue.setText(pctLabel(1000 + p));
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                    saveProp(P_SL_PCT, String.valueOf(1000 + s.getProgress()));
                    toast("已保存 " + pctLabel(1000 + s.getProgress()) + "，软重启后生效");
                }
            });
            slBody.addView(bar);
            slBody.addView(rangeRow("100%", "200%"));
            slBody.addView(hint("本机未读到标定表，改用出厂倍率模式 · 改动需软重启生效"));
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
        readoutSpin.setVisibility(View.VISIBLE);
        readoutBox.removeAllViews();
        readoutBox.addView(hint("读取中…（需要 root 授权）"));
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
            parse(out);
            runOnUiThread(() -> {
                readoutSpin.setVisibility(View.GONE);
                renderReadout();
                installSliders();   // 拿到标定表后切换 nit 模式 / prop 读数
            });
        }).start();
    }

    private void parse(String out) {
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

        mCur = num(seg(out, "---CUR---", "---MAX---"));
        mMax = num(seg(out, "---MAX---", "---SKIN---"));
        if (mMax <= 0) mMax = 16383;
        mSkin = num(seg(out, "---SKIN---", "---BOOST---"));
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

        mOs = os.trim().isEmpty() ? "?" : os.trim();
        mKnotsOk = !knotsRaw.isEmpty();
        mOldModule = oldmod != null && oldmod.contains("com.sunlightboost.lsp");
    }

    // ---------- 读数渲染 ----------

    private void renderReadout() {
        readoutBox.removeAllViews();
        addRow("系统版本", mOs, 0);
        addRow("自动亮度增幅", (abEnable == 1 ? "已启用 · " : "已停用 · ")
                + pctLabel(abPct >= 1000 && abPct <= 3000 ? abPct : DEFAULT_AB_PCT), 0);
        addRow("阳光上限", slText(), 0);
        addRow("knots 属性", mKnotsOk ? "已写入 ✓" : "未写入", 0);
        if (mCur >= 0) {
            addRow("当前 DBV", mCur + " / " + mMax + "（" + (mCur * 100 / Math.max(1, mMax)) + "%）", 0);
        }
        if (mSkin > -100000 && mSkin != 0) {
            addRow("皮肤温度", (mSkin / 1000) + "." + ((mSkin % 1000) / 100) + " °C", 0);
        }
        if (stockResFloat > 0f) {
            addRow("出厂阳光上限", trimFloat(stockResFloat)
                    + (tblBL != null ? "（≈" + floatToNit(stockResFloat) + " nit）" : ""), 0);
        }
        if (stockFloat > 0f) {
            addRow("当前生效 boost", trimFloat(stockFloat)
                    + (tblBL != null ? "（≈" + floatToNit(stockFloat) + " nit）" : ""), 1);
        } else {
            addRow("当前生效 boost", "未读到（软重启后出现）", 0);
        }
        addRow("knots 来源", knotsSpec != null ? "本机 displayconfig" : "内置默认（dash）", 0);
        if (mOldModule) {
            addRow("冲突提醒", "旧模块 com.sunlightboost.lsp 仍安装，请在 LSPosed 停用", 2);
        }
    }

    /** kind：0 普通，1 强调，2 警告 */
    private void addRow(String label, String value, int kind) {
        if (readoutBox.getChildCount() > 0) {
            View div = new View(this);
            div.setBackgroundColor(cOutline);
            readoutBox.addView(div, new LinearLayout.LayoutParams(MP, Math.max(1, dp(0.7f))));
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));
        TextView l = textView(label, 12, cTextDim, Typeface.DEFAULT);
        l.setPadding(0, 0, dp(10), 0);
        row.addView(l, new LinearLayout.LayoutParams(WC, WC));
        TextView v = textView(value, 12.5f, kind == 2 ? cWarn : cText, kind == 0 ? tfMono : tfMed);
        v.setGravity(Gravity.END);
        row.addView(v, new LinearLayout.LayoutParams(0, WC, 1f));
        readoutBox.addView(row);
    }

    private String slText() {
        StringBuilder sb = new StringBuilder(slEnable == 1 ? "已启用 · " : "已停用 · ");
        if (slTarget != null && !slTarget.isEmpty()) {
            boolean compat = slTarget.endsWith("*");
            String val = compat ? slTarget.substring(0, slTarget.length() - 1) : slTarget;
            try {
                float f = Long.parseLong(val) / 1000000f;
                sb.append("target=").append(trimFloat(f));
                if (tblBL != null) sb.append("（≈").append(floatToNit(f)).append(" nit）");
                if (compat) sb.append("（旧属性兼容，保存后覆盖）");
            } catch (Throwable t) {
                sb.append(slTarget);
            }
        } else {
            sb.append("未设置（默认 106.8%）");
        }
        return sb.toString();
    }

    private static String trimFloat(float f) {
        String s = String.format(Locale.US, "%.6f", f);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
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

    private void saveProp(String name, String value) {
        final String cmd = "setprop " + name + " " + value;
        new Thread(() -> su(cmd)).start();
    }

    private static String su(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
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

    // ---------- 软重启 ----------

    private void confirmRestart() {
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("软重启")
                .setMessage("将重启 system_server：屏幕短暂黑屏、所有应用重新加载，"
                        + "等效于重启但更快。自动亮度曲线与阳光上限在此时生效。\n\n继续？")
                .setPositiveButton("重启", (dlg, w) -> su("killall system_server"))
                .setNegativeButton("取消", null)
                .create();
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(cAccent);
            d.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(cTextDim);
        });
        d.show();
    }
}
