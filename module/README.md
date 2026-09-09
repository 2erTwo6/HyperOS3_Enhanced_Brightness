# HyperOS Enhanced Brightness

两个开源项目的合并 LSPosed 模块（借鉴其实现逻辑重写）：

- [2erTwo6/Hyper-Sunlight-Unlocker](https://github.com/2erTwo6/Hyper-Sunlight-Unlocker)（LSPosed 阳光模式上限解锁）
- [2erTwo6/HyperOS_autobrightness_boost](https://github.com/2erTwo6/HyperOS_autobrightness_boost)（Magisk 自动亮度曲线拔高）

适配范围：`ro.mi.os.version.name` 以 `OS3` 开头的 MIUI/HyperOS（参照机 Redmi Turbo 5 Max / dash，OS3.0.305）。非 OS3 自动不 Hook。

## 功能

### ① 阳光模式手动亮度上限解锁（来自 Hyper-Sunlight-Unlocker）

hook system_server `com.android.server.display.DisplayPowerControllerImpl.init()`，
只改字段 `mMaxManualBoostBrightness`（DBV 满量程比例 float）。出厂值运行时读
`android.miui:dimen/config_max_manual_brt_boost`，不硬编码。

### ② 自动亮度曲线整体拔高（来自 HyperOS_autobrightness_boost）

原 Magisk 模块对 `AospFrameworkResOverlay.apk` 的 resources.arsc 做字节补丁：

- `config_autoBrightnessLcdBacklightValues` 整体 ×K（16383 封顶）
- `config_autoBrightnessDisplayValuesNits` 按面板 亮度→nits 单调三次样条重算
  （`old_f=(int-1)/16383.75, new_f=min(1,old_f×K), nits=样条(new_f)`）
- lux 数组（`config_autoBrightnessLevels`）不动

本模块把同一套数学移植进 LSPosed：**在 system_server 内拦截这三个资源数组的读取**
（`Resources` / `ResourcesImpl` 的 `getIntArray` / `getFloatArray`，带防重复变换计数），
读取时改值——效果等同于 arsc 补丁，但免刷 Magisk、K 随时可调、无需 sha256 校验。
面板 亮度→nits 结点默认 dash 出厂 `screenBrightnessMap`，GUI 会解析
`/product/etc/displayconfig/display_id_*.xml` 写入 `knots` 属性适配任意机型。

## 配置通道（persist 属性，GUI 经 su 写入）

| 属性 | 含义 | 默认 |
|---|---|---|
| `persist.hyperbrightness.autobr.enable` | 自动亮度增强开关 | 1 |
| `persist.hyperbrightness.autobr.pct` | 增幅 ×0.1%（1300=×1.30） | 1300 |
| `persist.hyperbrightness.sunlight.enable` | 阳光上限解锁开关 | 1 |
| `persist.hyperbrightness.sunlight.target` | 阳光绝对目标 float×1e6 | — |
| `persist.hyperbrightness.sunlight.pct` | 相对出厂 ×0.1%（1068=106.8%） | 1068 |
| `persist.hyperbrightness.knots` | 面板结点 `b1:n1,b2:n2,...` | dash 内置 |

阳光旧模块兼容：`persist.sunlightboost.target` / `persist.sunlightboost.pct`
只读兜底（新属性未设置时才读取，保存 GUI 配置后即被覆盖）。

**所有改动需软重启 system_server（GUI 内按钮）或重启后生效。**

## 构建

需要 JDK 17、python3、curl。`./build.sh`（自动下载 r8/D8 工具链 + 官方 android.jar，编译、
打包 AXML 清单并 v1 签名）。产物：`HyperOS-Enhanced-Brightness.apk`。

## 安装

```sh
adb push HyperOS-Enhanced-Brightness.apk /data/local/tmp/
adb shell pm install /data/local/tmp/HyperOS-Enhanced-Brightness.apk
```

LSPosed 管理器 → 模块 → **HyperOS Enhanced Brightness** → 启用，作用域勾选 **Android 系统**
（system_server），软重启/重启生效。

> ⚠ 若设备仍装有旧模块 Hyper-Sunlight-Unlocker（`com.sunlightboost.lsp`），请先在
> LSPosed 停用它：两个模块会同时修改 `mMaxManualBoostBrightness`，结果不确定。

## 验证

```sh
adb shell dumpsys display | grep mMaxManualBoostBrightness
# 应为阳光目标值（≤1.0）
adb shell su -c 'getprop persist.hyperbrightness.autobr.pct'
# 系统自动亮度开关（设置里的"亮度自动调节"）需用户打开，模块不改它
adb shell settings get system screen_brightness_mode   # 1 = 开
```

LSPosed 日志过滤 `HBrLSP:` 可见：

- `sunlight ... stock=... -> ...`
- `AB ids from R$array: brt=0x... nits=0x...`
- `AB brt scaled len=129 head=[...]`
- `AB nits recomputed via obtainTypedArray`

### Redmi Turbo 5 Max (dash, OS3.0.305) 实测结果

- 阳光上限：出厂 0.593761 → 目标 1.0，`dumpsys display` 中 `mMaxManualBoostBrightness=1.0` ✓
- 自动亮度：本 ROM 的 nits 数组经 `Resources.obtainTypedArray` 读取
  （`BrightnessMappingStrategy.getFloatArray` 风格），模块对 `TypedArray.mData`
  （stride=6）原地变换，带首元素读数自校验，变换失败自动放弃不动内存 ✓
- 变换后 ROM 的 `BrightnessMappingStrategy.create` 从 `PhysicalMappingStrategy`
  回退到 `SimpleMappingStrategy`（MIUI 对 nits 数组的单调性校验更严，重算后的
  顶部 3500-nit 平台触发回退）——但 Simple 路径用的背光表就是 ×K 后的数组，
  `dumpsys` 中 `SimpleMappingStrategy.mSpline` 已确认为增强值
  （如 lux=0 背光 0.00238→0.00311），自动亮度增强同样生效 ✓
- `isValidMapping` 对 nits/背光均允许平台值（非严格递增），K 在 1.0–2.0 范围内
  回退行为稳定，不会出现"曲线失效"的空策略

## 回滚 / 卸载

- GUI 恢复：增幅滑块拉回 100% 保存；阳光滑块拉回下限（原厂上限）保存 → 软重启
- 彻底：LSPosed 停用模块 + `pm uninstall com.hyperos.brightness.lsp`
- 属性清理（可选）：`su -c "setprop persist.hyperbrightness.autobr.enable 0; setprop persist.hyperbrightness.sunlight.enable 0"`

## 已知边界

- 自动亮度变换依赖读取方使用 `Resources/`/`ResourcesImpl` 的数组 API；若系统改走
  `obtainTypedArray`，日志会提示（诊断钩子），需扩展变换点
- OS4 哨兵值机型（出厂值 -1.0）自动不修改
- 中低照度下更费电、屏幕更亮，自行权衡；SDR 末端 600 nit / HBM 3500 nit 兜底不受影响

## GUI（模块桌面图标）

- **① 自动亮度曲线增幅**：100%–200% 滑块（×0.1% 步进）+ 开关按钮；保存时同时把
  本机 `displayconfig` 的面板结点写入 `knots` 属性；改动需软重启生效
- **② 阳光模式手动上限**：nit 滑块（下限=出厂上限，读自 `android.miui` 资源真值，
  刻度来自 dumpsys 标定表）+ 开关按钮
- 读数面板：当前 prop、出厂/生效阳光上限、DBV、皮肤温度、旧模块冲突提示
- **软重启按钮**：重启 system_server 应用全部改动

## 构建产物结构

```
module/
├── src/hbr/Hook.java         # 两个功能 hook（阳光上限 + 自动亮度变换）
├── src/hbr/MainActivity.java # 无资源代码式 GUI
├── src/de/robv/...           # Xposed 桩接口（编译期）
├── build.sh                  # 下载 r8/D8 + android.jar，编译 dex，打包签名
├── build_apk.py              # 手工 AXML 清单 + assets/xposed_init + v1 签名
└── HyperOS-Enhanced-Brightness.apk
```

## License / 致谢

MIT。实现逻辑来自 2erTwo6 的上述两个项目（MIT），本项目为其 LSPosed 合并移植版。