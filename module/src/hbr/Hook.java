package hbr;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * HyperOS 3（OS3）亮度增强 LSPosed 模块 —— 两个参考项目的合并：
 *
 * 1) 阳光模式手动亮度上限解锁（借鉴 Hyper-Sunlight-Unlocker）：
 *    hook system_server 里 miui DisplayPowerControllerImpl.init()，只改字段
 *    mMaxManualBoostBrightness（DBV 满量程比例 float，出厂值运行时从
 *    android.miui:dimen/config_max_manual_brt_boost 读取）。
 *
 * 2) 自动亮度曲线整体拔高（借鉴 HyperOS_autobrightness_boost 的 resources.arsc
 *    字节补丁逻辑，改为进程内拦截）：同一资源数组在 system_server 读取时改值 ——
 *      config_autoBrightnessLcdBacklightValues   整数 ×K（1..16383 封顶）
 *      config_autoBrightnessDisplayValuesNits    按面板 亮度→nits 单调三次样条重算
 *      config_autoBrightnessLevels (lux)         不动
 *    等效于对 AospFrameworkResOverlay.apk 的 arsc 补丁，但免刷 Magisk、K 可随时调。
 *
 * 配置通道：persist.hyperbrightness.* 属性（GUI 经 su 写入；system_server 可读、重启不丢）。
 * 旧 Hyper-Sunlight-Unlocker 的 persist.sunlightboost.* 属性做只读兼容兜底。
 *
 * 门卫与安全设计（沿用两仓的做法）：
 *  - 非 OS3 一律不 Hook（连类查找都不做），只打一条日志；
 *  - 阳光出厂值 ≤0（OS4 哨兵 -1.0）或 >1.0 → 判定功能未启用，不修改；
 *  - 全部回调 try/catch，异常只进 LSPosed 日志；
 *  - 出厂值优先读资源，init 重入不会把已改值当出厂值叠加。
 */
public class Hook implements IXposedHookLoadPackage {
    private static final String TAG = "HBrLSP";
    private static final String OS_PREFIX = "OS3";

    // ---------- 自动亮度配置 ----------
    private static final String P_AB_ENABLE = "persist.hyperbrightness.autobr.enable";
    private static final String P_AB_PCT = "persist.hyperbrightness.autobr.pct"; // ×0.1%，1300 = ×1.30
    private static final String P_KNOTS = "persist.hyperbrightness.knots";       // "b:n,b:n,..."（GUI 从 displayconfig XML 提取）
    private static final int DEFAULT_AB_PCT = 1300;                              // 参考仓默认 ×1.30
    private static final int INT_MAX = 16383;                                    // 背光整数数组上限

    // 面板 亮度(backlight float)→nits 结点；dash 出厂 displayconfig 的 screenBrightnessMap，
    // 运行时优先读 P_KNOTS（GUI 写入本机值），读不到用这份默认（Redmi Turbo 5 Max）。
    private static final String DEFAULT_KNOTS =
            "0.000854597:2.0,0.499939:600.0,0.593761:800.0,0.836223:2000.0,1.0:3500.0";

    // ---------- 阳光上限配置 ----------
    private static final String P_SL_ENABLE = "persist.hyperbrightness.sunlight.enable";
    private static final String P_SL_TARGET = "persist.hyperbrightness.sunlight.target"; // 绝对目标 float ×1e6
    private static final String P_SL_PCT = "persist.hyperbrightness.sunlight.pct";       // 相对出厂 ×0.1%
    private static final String P_OLD_TARGET = "persist.sunlightboost.target"; // 旧模块兼容（只读兜底）
    private static final String P_OLD_PCT = "persist.sunlightboost.pct";       // 旧模块兼容（只读兜底）
    private static final int DEFAULT_SL_PCT = 1068;                            // 出厂 0.593761 → ≈1000 nit
    private static final String RES_MANUAL = "config_max_manual_brt_boost";

    // ---------- 资源数组 ----------
    private static final String RES_BRT = "config_autoBrightnessLcdBacklightValues";
    private static final String RES_NITS = "config_autoBrightnessDisplayValuesNits";

    // ---------- 运行期状态 ----------
    private static int idBrt;            // 资源 id，0 = 未解析（回调里 getIdentifier 兜底）
    private static int idNits;
    private static float k = 1f;         // 自动亮度增幅（1.0 = 功能关闭）
    private static volatile int[] stockBrt;      // 原厂背光整数表（首次拦截时缓存，用于 nits 重算）
    private static float[][] knots;              // 亮度→nits 结点（升序）
    private static float[] knotSlopes;
    private static final ThreadLocal<int[]> DEPTH = new ThreadLocal<>(); // 防重复变换深度计数
    private static volatile boolean loggedBrt, loggedNits;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!"android".equals(lpp.packageName)) return;
        String os = prop(lpp.classLoader, "ro.mi.os.version.name");
        if (!os.startsWith(OS_PREFIX)) {
            XposedBridge.log(TAG + ": os=" + os + " 非 " + OS_PREFIX + "，不 Hook");
            return;
        }
        try {
            hookSunlight(lpp, os);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": sunlight hook failed " + t);
        }
        try {
            hookAutoBrightness(lpp);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": autobrightness hook failed " + t);
        }
    }

    // =====================================================================
    // 功能一：阳光模式手动亮度上限（Hyper-Sunlight-Unlocker 移植）
    // =====================================================================

    private void hookSunlight(XC_LoadPackage.LoadPackageParam lpp, String os) throws Throwable {
        if (propInt(P_SL_ENABLE, 1) != 1) {
            XposedBridge.log(TAG + ": sunlight disabled by prop, skip");
            return;
        }
        Class<?> dpc = XposedHelpers.findClass(
                "com.android.server.display.DisplayPowerControllerImpl", lpp.classLoader);
        XposedBridge.hookAllMethods(dpc, "init", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object self = param.thisObject;
                    float stock = readStock(self);
                    if (stock <= 0f || stock > 1.0f) {
                        XposedBridge.log(TAG + ": sunlight stock=" + stock
                                + " 无效（功能未启用/哨兵值），不修改");
                        return;
                    }
                    float target = resolveSunlightTarget(stock);
                    if (target <= 0f || target > 1.0f) {
                        XposedBridge.log(TAG + ": sunlight target=" + target + " 无效，不修改");
                        return;
                    }
                    if (Math.abs(target - stock) < 1e-7f) {
                        XposedBridge.log(TAG + ": sunlight 目标等于出厂值，无需修改");
                        return;
                    }
                    float applied = Math.min(target, 1.0f);
                    Object disp = XposedHelpers.getObjectField(self, "mDisplayId");
                    XposedHelpers.setFloatField(self, "mMaxManualBoostBrightness", applied);
                    XposedBridge.log(TAG + ": sunlight display " + disp + " stock=" + stock
                            + " -> " + applied
                            + (applied >= 0.999999f ? " (clamped 1.0)" : ""));
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": sunlight apply failed " + t);
                }
            }
        });
        XposedBridge.log(TAG + ": " + os + ", sunlight hook armed (DisplayPowerControllerImpl.init)");
    }

    /** 出厂值：优先读资源（init 重入时字段可能已被本模块改过），查不到退回字段值。 */
    private static float readStock(Object self) {
        try {
            Object ctx = XposedHelpers.getObjectField(self, "mContext");
            Object res = XposedHelpers.callMethod(ctx, "getResources");
            for (String pkg : new String[]{"android.miui", "android"}) {
                try {
                    Object idObj = XposedHelpers.callMethod(res, "getIdentifier", RES_MANUAL, "dimen", pkg);
                    int id = ((Number) idObj).intValue();
                    if (id != 0) {
                        float v = ((Number) XposedHelpers.callMethod(res, "getFloat", id)).floatValue();
                        XposedBridge.log(TAG + ": sunlight stock from " + pkg + " resource = " + v);
                        return v;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": sunlight resource lookup failed: " + t);
        }
        try {
            float f = XposedHelpers.getFloatField(self, "mMaxManualBoostBrightness");
            XposedBridge.log(TAG + ": sunlight fallback to field = " + f);
            return f;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": sunlight field fallback failed " + t);
            return -1f;   // 触发 stock<=0 分支 → 安全 no-op
        }
    }

    /** 目标解析：绝对目标 prop →（兼容旧 prop）→ 倍率 prop →（兼容旧 pct）→ 默认 106.8%。 */
    private static float resolveSunlightTarget(float stock) {
        Integer v = propIntOrNull(P_SL_TARGET);
        String src = null;
        if (v != null && v > 0 && v <= 1000000) {
            src = P_SL_TARGET;
        } else {
            v = propIntOrNull(P_OLD_TARGET);
            if (v != null && v > 0 && v <= 1000000) src = P_OLD_TARGET + "(compat)";
        }
        if (src != null) {
            XposedBridge.log(TAG + ": sunlight target from " + src + " = " + v);
            return v / 1000000f;
        }
        Integer pct = propIntOrNull(P_SL_PCT);
        String psrc = null;
        if (pct != null && pct >= 1000 && pct <= 2000) {
            psrc = P_SL_PCT;
        } else {
            pct = propIntOrNull(P_OLD_PCT);
            if (pct != null && pct >= 1000 && pct <= 2000) psrc = P_OLD_PCT + "(compat)";
        }
        if (psrc != null) {
            XposedBridge.log(TAG + ": sunlight pct from " + psrc + " = " + pct);
            return stock * (pct / 1000.0f);
        }
        XposedBridge.log(TAG + ": sunlight no config, default pct " + DEFAULT_SL_PCT);
        return stock * (DEFAULT_SL_PCT / 1000.0f);
    }

    // =====================================================================
    // 功能二：自动亮度曲线拔高（HyperOS_autobrightness_boost 逻辑移植）
    // =====================================================================

    private void hookAutoBrightness(XC_LoadPackage.LoadPackageParam lpp) {
        boolean enabled = propInt(P_AB_ENABLE, 1) == 1;
        int pct = propInt(P_AB_PCT, DEFAULT_AB_PCT);
        if (pct < 1000 || pct > 3000) pct = DEFAULT_AB_PCT;
        if (!enabled || pct <= 1000) {
            XposedBridge.log(TAG + ": autobrightness disabled (enable=" + enabled + " pct=" + pct + ")");
            return;
        }
        k = pct / 1000.0f;
        String ks = propStr(P_KNOTS);
        initKnots(ks == null || ks.isEmpty() ? DEFAULT_KNOTS : ks);
        resolveArrayIds(lpp.classLoader);
        XposedBridge.log(TAG + ": autobrightness K=" + k + " knots=" + describeKnots());

        // 拦截点 A：Resources 层（AOSP 标准读法 getIntArray/getFloatArray）
        // 拦截点 B：ResourcesImpl 层（直接读 impl 或子类绕过 Resources 时的兜底）
        // 双层防重复：DEPTH 计数保证同一次调用链只变换一次。
        XC_MethodHook transform = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isTargetId(param)) incDepth();
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (k <= 1.0001f) return;
                    int id = argId(param);
                    if (id == idBrt && idBrt != 0) {
                        int[] raw = (int[]) param.result;
                        if (raw == null || raw.length == 0) return;
                        if (stockBrt == null) stockBrt = raw.clone();
                        if (depth() <= 1) {
                            int[] scaled = scaleInts(raw, k);
                            param.result = scaled;
                            if (!loggedBrt) {
                                loggedBrt = true;
                                XposedBridge.log(TAG + ": AB brt scaled len=" + raw.length
                                        + " head=" + head(raw, scaled));
                            }
                        }
                    } else if (id == idNits && idNits != 0) {
                        float[] raw = (float[]) param.result;
                        if (raw == null || raw.length == 0) return;
                        if (depth() <= 1) {
                            int[] stock = ensureStock(param.thisObject);
                            if (stock != null) {
                                float[] scaled = recomputeNits(raw, stock, k);
                                param.result = scaled;
                                if (!loggedNits) {
                                    loggedNits = true;
                                    XposedBridge.log(TAG + ": AB nits recomputed len=" + raw.length
                                            + " head=" + headF(raw, scaled));
                                }
                            } else {
                                XposedBridge.log(TAG + ": AB nits 无原厂背光表可参考，跳过重算");
                            }
                        }
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": AB hook error " + t);
                } finally {
                    if (isTargetId(param)) decDepth();
                }
            }
        };
        // TypedArray 路径：本机 ROM 里 nits 数组经 obtainTypedArray 读取
        // （BrightnessMappingStrategy.getFloatArray 风格），对 TypedArray.mData 原地变换。
        XC_MethodHook taHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isTargetId(param)) incDepth();
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (k <= 1.0001f) return;
                    int id = argId(param);
                    Object ta = param.result;
                    if (ta == null) return;
                    if (depth() > 1) return;   // 处于我们自己的 getFloatArray 链内，避免双变换
                    if (transformTypedArray(ta, id, param.thisObject)) {
                        if (id == idBrt && !loggedBrt) {
                            loggedBrt = true;
                            XposedBridge.log(TAG + ": AB brt scaled via obtainTypedArray");
                        } else if (id == idNits && !loggedNits) {
                            loggedNits = true;
                            XposedBridge.log(TAG + ": AB nits recomputed via obtainTypedArray");
                        }
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": AB typedArray error " + t);
                } finally {
                    if (isTargetId(param)) decDepth();
                }
            }
        };
        for (String clsName : new String[]{"android.content.res.Resources",
                "android.content.res.ResourcesImpl"}) {
            try {
                Class<?> cls = Class.forName(clsName);
                boolean any = false;
                for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                    String mn = m.getName();
                    if (mn.equals("getIntArray") || mn.equals("getFloatArray")) {
                        XposedBridge.hookAllMethods(cls, mn, transform);
                        any = true;
                    }
                }
                XposedBridge.hookAllMethods(cls, "obtainTypedArray", taHook);
                if (!any) {
                    XposedBridge.log(TAG + ": AB " + clsName + " 无 getIntArray/getFloatArray");
                } else {
                    XposedBridge.log(TAG + ": AB hooks armed on " + clsName);
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": AB hook " + clsName + " failed " + t);
            }
        }
    }

    // TypedValue 常量
    private static final int TYPE_INT_DEC = 0x10;
    private static final int TYPE_INT_HEX = 0x11;
    private static final int TYPE_FLOAT = 0x04;

    /** TypedArray 原地变换：mData 布局 = stride(AssetManager.STYLE_NUM_ENTRIES) × len，
     *  每元素 [type, data, ...]；背光表整型、nits 浮点按参考仓数学改写。
     *  布局自校验：先比对首元素 TypedArray 读数与 mData 原始位，不符则放弃（不动内存）。 */
    private static boolean transformTypedArray(Object ta, int id, Object res) {
        try {
            int len = ((Number) XposedHelpers.callMethod(ta, "length")).intValue();
            if (len <= 0) return false;
            int[] data = (int[]) readFieldChain(ta.getClass(), ta, "mData");
            int stride = staticIntChain("android.content.res.AssetManager", "STYLE_NUM_ENTRIES", 6);
            int typeOff = staticIntChain("android.content.res.AssetManager", "STYLE_TYPE", 0);
            int dataOff = staticIntChain("android.content.res.AssetManager", "STYLE_DATA", 1);
            if (stride <= 1) return false;
            int[] stock = null;
            if (id == idNits) {
                stock = ensureStock(res);
                if (stock == null) return false;
            }
            // 自校验：TypedArray 读数必须与 mData 首元素一致（验证 stride 假设）
            int ptype = data[typeOff];          // 第 0 个元素的 type
            if (id == idBrt && (ptype == TYPE_INT_DEC || ptype == TYPE_INT_HEX)) {
                int pv = data[dataOff];
                int cv = ((Number) XposedHelpers.callMethod(ta, "getInt", 0, Integer.MIN_VALUE)).intValue();
                if (cv != Integer.MIN_VALUE && pv != cv) {
                    XposedBridge.log(TAG + ": AB TypedArray 布局校验失败(int " + pv + "!=" + cv + ")，放弃");
                    return false;
                }
            } else if (id == idNits && ptype == TYPE_FLOAT) {
                float pf = Float.intBitsToFloat(data[dataOff]);
                float cf = ((Number) XposedHelpers.callMethod(ta, "getFloat", 0, Float.NaN)).floatValue();
                if (!Float.isNaN(cf) && pf != cf) {
                    XposedBridge.log(TAG + ": AB TypedArray 布局校验失败(float " + pf + "!=" + cf + ")，放弃");
                    return false;
                }
            } else {
                return false;   // 类型不符合预期（空数组/其它类型）
            }
            int changed = 0;
            for (int i = 0; i < len; i++) {
                int off = i * stride;
                int type = data[off + typeOff];
                if (id == idBrt && (type == TYPE_INT_DEC || type == TYPE_INT_HEX)) {
                    int v = data[off + dataOff];
                    data[off + dataOff] = (int) Math.min(INT_MAX, Math.max(1, Math.round(v * k)));
                    changed++;
                } else if (id == idNits && type == TYPE_FLOAT) {
                    // nits 不读旧值：直接由 原厂背光 ×K 经面板样条重算（与参考仓一致）
                    float newf = Math.min(1f, (stock[i] - 1) / 16383.75f * k);
                    float n = Math.min(knots[knots.length - 1][1], Math.max(knots[0][1], nitsOf(newf)));
                    data[off + dataOff] = Float.floatToRawIntBits(n);
                    changed++;
                }
            }
            return changed > 0;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": AB transformTypedArray failed " + t);
            return false;
        }
    }

    /** 沿类层次找字段（TypedArray 可能是厂商子类）。 */
    private static Object readFieldChain(Class<?> cls, Object self, String name) throws Exception {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(self);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** 静态 int 字段查找（declared 链），失败用 fallback（AOSP 稳定布局）。 */
    private static int staticIntChain(String clsName, String name, int fallback) {
        try {
            Class<?> c = Class.forName(clsName);
            for (Class<?> k = c; c != null; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.getInt(null);
                } catch (NoSuchFieldException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /** 原厂背光整数表：优先用首次拦截缓存，没有则防递归地再读一次原值。 */
    private static int[] ensureStock(Object res) {
        int[] s = stockBrt;
        if (s != null) return s;
        if (idBrt == 0 || res == null) return null;
        incDepth();
        try {
            int[] raw = (int[]) XposedHelpers.callMethod(res, "getIntArray", idBrt);
            if (raw != null && raw.length > 0) {
                stockBrt = raw;
                return raw;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": AB stock fetch failed " + t);
        } finally {
            decDepth();
        }
        return null;
    }

    private static int[] scaleInts(int[] raw, float kk) {
        int[] out = new int[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = (int) Math.min(INT_MAX, Math.max(1, Math.round(raw[i] * kk)));
        }
        return out;
    }

    /** nits 重算：等价于参考仓 rebuild_boost.py —— old_f=(int-1)/16383.75, new_f=min(1,old_f*K),
     *  nits = 样条(new_f)，clamp [结点最低 nits, 结点最高 nits]。 */
    private static float[] recomputeNits(float[] raw, int[] stock, float kk) {
        float lo = knots[0][1], hi = knots[knots.length - 1][1];
        float[] out = new float[raw.length];
        for (int i = 0; i < raw.length; i++) {
            float oldF = (stock[i] - 1) / 16383.75f;
            float newF = Math.min(1f, oldF * kk);
            out[i] = Math.min(hi, Math.max(lo, nitsOf(newF)));
        }
        return out;
    }

    private static void resolveArrayIds(ClassLoader cl) {
        try {
            Class<?> r = XposedHelpers.findClass("com.android.internal.R$array", cl);
            idBrt = intField(r, RES_BRT);
            idNits = intField(r, RES_NITS);
            XposedBridge.log(TAG + ": AB ids from R$array: brt=" + hex(idBrt) + " nits=" + hex(idNits));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": AB R$array lookup failed, 回调中用 getIdentifier 兜底: " + t);
        }
    }

    private static int intField(Class<?> cls, String name) throws Exception {
        Field f = cls.getField(name);
        return f.getInt(null);
    }

    private static int argId(XC_MethodHook.MethodHookParam param) {
        try {
            int id = ((Number) param.args[0]).intValue();
            if (idBrt == 0 || idNits == 0) {
                // 回调兜底解析（getIdentifier 解析 android 包内资源，id 不受 overlay 影响）
                if (param.thisObject != null) {
                    try {
                        if (idBrt == 0) {
                            idBrt = ((Number) XposedHelpers.callMethod(param.thisObject,
                                    "getIdentifier", RES_BRT, "array", "android")).intValue();
                        }
                        if (idNits == 0) {
                            idNits = ((Number) XposedHelpers.callMethod(param.thisObject,
                                    "getIdentifier", RES_NITS, "array", "android")).intValue();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            return id;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean isTargetId(XC_MethodHook.MethodHookParam param) {
        int id = argId(param);
        return id != 0 && (id == idBrt || id == idNits);
    }

    private static void incDepth() {
        DEPTH.set(new int[]{depth() + 1});
    }

    private static void decDepth() {
        DEPTH.set(new int[]{Math.max(0, depth() - 1)});
    }

    private static int depth() {
        int[] v = DEPTH.get();
        return v == null ? 0 : v[0];
    }

    // ---------- 亮度→nits 单调三次样条（Fritsch–Carlson，与参考仓 hermite 同式） ----------

    private static void initKnots(String spec) {
        try {
            List<float[]> pts = new ArrayList<>();
            for (String part : spec.split(",")) {
                String[] kv = part.trim().split(":");
                if (kv.length != 2) continue;
                pts.add(new float[]{Float.parseFloat(kv[0]), Float.parseFloat(kv[1])});
            }
            Collections.sort(pts, (a, b) -> Float.compare(a[0], b[0]));
            boolean ok = pts.size() >= 2;
            for (int i = 1; ok && i < pts.size(); i++) {
                if (pts.get(i)[0] <= pts.get(i - 1)[0]) ok = false;
            }
            if (!ok) throw new IllegalArgumentException("bad knots");
            knots = pts.toArray(new float[0][]);
            knotSlopes = fcSlopes(knots);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": knots parse failed (" + spec + ")，用默认: " + t);
            initKnots(DEFAULT_KNOTS);
        }
    }

    private static String describeKnots() {
        StringBuilder sb = new StringBuilder();
        for (float[] p : knots) sb.append(p[0]).append(':').append(p[1]).append(' ');
        return sb.toString();
    }

    private static float[] fcSlopes(float[][] pts) {
        int n = pts.length;
        float[] d = new float[Math.max(1, n - 1)];
        for (int i = 0; i < n - 1; i++) {
            d[i] = (pts[i + 1][1] - pts[i][1]) / (pts[i + 1][0] - pts[i][0]);
        }
        float[] m = new float[n];
        m[0] = d[0];
        for (int i = 1; i < n - 1; i++) m[i] = (d[i - 1] + d[i]) * 0.5f;
        m[n - 1] = d[n - 2];
        for (int i = 0; i < n - 1; i++) {
            if (d[i] == 0f) {
                m[i] = 0f;
                m[i + 1] = 0f;
            } else {
                float a = m[i] / d[i], b = m[i + 1] / d[i];
                float h = (float) Math.sqrt(a * a + b * b);
                if (h > 3f) {
                    float t = 3f / h;
                    m[i] *= t;
                    m[i + 1] *= t;
                }
            }
        }
        return m;
    }

    private static float nitsOf(float x) {
        if (x <= knots[0][0]) return knots[0][1];
        if (x >= knots[knots.length - 1][0]) return knots[knots.length - 1][1];
        int i = 0;
        while (x >= knots[i + 1][0]) {
            i++;
            if (x == knots[i][0]) return knots[i][1];
        }
        float h = knots[i + 1][0] - knots[i][0];
        float t = (x - knots[i][0]) / h;
        float hi = knots[i][1], hi1 = knots[i + 1][1], si = knotSlopes[i], si1 = knotSlopes[i + 1];
        return ((hi * (1 + 2 * t) + h * si * t) * (1 - t) * (1 - t)
                + (hi1 * (3 - 2 * t) + h * si1 * (t - 1)) * t * t);
    }

    // =====================================================================
    // 通用工具
    // =====================================================================

    private static String head(int[] raw, int[] scaled) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(5, raw.length); i++) {
            sb.append(i == 0 ? "" : ",").append(raw[i]).append("->").append(scaled[i]);
        }
        return sb.append("...]").toString();
    }

    private static String headF(float[] raw, float[] scaled) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(5, raw.length); i++) {
            sb.append(i == 0 ? "" : ",").append(raw[i]).append("->").append(scaled[i]);
        }
        return sb.append("...]").toString();
    }

    private static String hex(int v) {
        return v == 0 ? "0" : "0x" + Integer.toHexString(v);
    }

    /** 读系统属性（反射，避免桩类依赖 android.jar）。 */
    private static String prop(ClassLoader cl, String name) {
        try {
            Class<?> sp = XposedHelpers.findClass("android.os.SystemProperties", cl);
            return (String) XposedHelpers.callStaticMethod(sp, "get", name, "");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 读系统属性为 int，缺失/非法返回 null。 */
    private static Integer propIntOrNull(String name) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String s = ((String) sp.getMethod("get", String.class, String.class)
                    .invoke(null, name, "")).trim();
            if (!s.isEmpty()) return Integer.parseInt(s);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 读系统属性为 int，缺失/非法返回 fallback。 */
    private static int propInt(String name, int fallback) {
        Integer v = propIntOrNull(name);
        return v == null ? fallback : v;
    }

    /** 读系统属性字符串（系统进程内，反射 SystemProperties）。 */
    private static String propStr(String name) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class, String.class)
                    .invoke(null, name, "");
        } catch (Throwable t) {
            return "";
        }
    }
}