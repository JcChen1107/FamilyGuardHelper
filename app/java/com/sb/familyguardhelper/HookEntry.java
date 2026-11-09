package com.sb.familyguardhelper;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * FamilyGuard Helper v2 —— 修复「只能单独作用域生效」
 *
 * 根因与修复：
 *  1. Build 字段伪装原来是单 try-catch 串行执行，modifiers 反射 hack 在
 *     API 28+ 必抛异常 → 后续字段全部不生效。改为逐字段独立容错，
 *     并去掉 modifiers hack（XposedHelpers.setStaticObjectField 足够）。
 *  2. prop/Build 伪装原来无差别作用于所有作用域包，Heytap 帐号进程被
 *     伪装后触发风控 → 多包勾选时帐号链路挂掉。改为按包分层：
 *       - 家人守护本体：全套 hook（prop + Build + feature + 签名）
 *       - Heytap 帐号/推送：默认只做 hasSystemFeature 放行，
 *         不动设备信息（可用配置 hook_spoof_heytap=true 强开）
 *
 * 配置文件：/storage/emulated/0/fg_helper.props
 *   model / device / manufacturer / brand   设备伪装
 *   sigBase64                               签名注入（可选）
 *   hook_spoof_heytap=true                  强制对 Heytap 链路也做设备伪装（默认 false）
 *   extra_packages=a.b,c.d                  追加按家人守护策略处理的包名
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "FGHelper";

    // ---- 分层作用域 ----
    private static final Set<String> FAMILY_GUARD_PKGS = new HashSet<>(Arrays.asList(
            "com.oplus.familyguard", "com.coloros.familyguard",
            // v4.6: 协助链路的系统侧组件（从固件抠出普通安装）也走全套注入
            "com.coloros.sharescreen", "com.coloros.remoteguardservice"));

    // Heytap 帐号/推送链路：默认轻 hook，避免风控误伤
    private static final Set<String> HEYTAP_PKGS = new HashSet<>(Arrays.asList(
            "com.oplus.account", "com.heytap.account",
            "com.oplus.push", "com.coloros.assistnet"));

    // v2.3: 登录壳「移动服务」（HeyTap Mobile Services）——
    // 家人守护登录会跳转到它，它内部的设备检查必须被完整伪装，默认 FULL
    private static final Set<String> LOGIN_PKGS = new HashSet<>(Arrays.asList(
            "com.heytap.htms", "com.oplus.mobileservice"));

    // ---- 配置 ----
    private static String sModel = "PEFM00";
    private static String sDevice = "OP4F2F";
    private static String sManufacturer = "OPPO";
    private static String sBrand = "OPPO";
    private static String sSigBase64 = null;
    private static boolean sSpoofHeytap = false;
    private static boolean sBlockAssistFinish = false;
    private static boolean sSpoofHtms = false; // 登录可正常工作时保持 false；登录被设备检查拦住时改 true
    // v2.2: sharescreen SDK 初始化补丁参数（可被配置覆盖）
    private static String sNetBuildClass = "com.coloros.sharescreen.request.config.NetBuild";
    private static String sGsonField = "mGson";
    private static String sGsonClass = "com.google.gson.Gson";
    private static String sPrivacyActivity = "com.coloros.sharescreen.connecting.initiator.view.PrivacyPageActivity";
    private static final Set<String> sExtraPkgs = new HashSet<>();
    private static boolean sIsFamilyGuard = false;
    private static boolean sConfigLoaded = false;

    // 当前进程实际命中的策略，null = 不处理
    private Integer sMode; // 0=轻hook(feature only) 1=全套
    private static XC_LoadPackage.LoadPackageParam sLpp;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpp) {
        String pkg = lpp.packageName;
        if (sMode != null) {
            return; // 同进程重复回调防抖
        }

        if (!sConfigLoaded) {
            loadConfig();
            sConfigLoaded = true;
        }

        int mode;
        if (FAMILY_GUARD_PKGS.contains(pkg) || sExtraPkgs.contains(pkg)) {
            mode = 1;
        } else if (LOGIN_PKGS.contains(pkg)) {
            mode = sSpoofHtms ? 1 : 0;
        } else if (HEYTAP_PKGS.contains(pkg)) {
            mode = sSpoofHeytap ? 1 : 0;
        } else if (pkg.equals("com.heytap.htms") || pkg.startsWith("com.heytap.htms")
                || pkg.startsWith("com.heytap.msp")) {
            // 前缀兜底：移动服务/MSP SDK 变体包一律按登录壳处理
            mode = sSpoofHtms ? 1 : 0;
        } else if (pkg.startsWith("com.heytap.") || pkg.startsWith("com.oplus.")
                || pkg.startsWith("com.coloros.")) {
            // 兜底：作用域里勾了其它 oplus 系包，按轻策略处理
            mode = sSpoofHeytap ? 1 : 0;
        } else {
            return;
        }
        sMode = mode;
        sIsFamilyGuard = (mode == 1)
                && (FAMILY_GUARD_PKGS.contains(pkg) || sExtraPkgs.contains(pkg));
        sLpp = lpp;
        log(pkg + " -> mode=" + (mode == 1 ? "FULL" : "LIGHT"));

        // 每个 hook 独立容错：一个失败不影响其余
        if (mode == 1) {
            safeHook("SystemProperties", () -> hookSystemProperties());
            safeHook("BuildFields", () -> hookBuildFields());
            // v2.2: 补齐 sharescreen SDK 缺失的初始化（修复协助页 mGson 崩溃）
            safeHook("NetBuildFix", () -> hookNetBuildFix(lpp));
        }
        safeHook("HasSystemFeature", () -> hookHasSystemFeature(lpp));
        if (mode == 1) {
            safeHook("AccountFix", () -> hookAccountManager());
        }
        if (mode == 1) {
            safeHook("SignatureCheck", () -> hookSignatureCheck(lpp));
            safeHook("ApplicationAttach", () -> hookApplicationAttach());
            // v4.6: 最早注入点 —— attachBaseContext(super 调用)之前，
            // 抢在 app 自己的 LogUtils/<clinit>（依赖 oplus wrapper 类）之前
            safeHook("EarlyInject", () -> hookEarlyInject());
            // v5.0: z.d(SystemPropertiesCompat) 全方法短路 + v.n(Trace) 短路
            // z.d 的方法体直接引用 oplus wrapper 类，ART 原生解析不认注入的 element，
            // 提前在方法入口 setResult，让 clinit 永远走不到 wrapper 引用
            if (pkg.contains("sharescreen") || pkg.contains("remoteguardservice")) {
                safeHook("OplusCompat", () -> hookOplusCompat(lpp.classLoader));
            }
            // v2.1 诊断增强：闪退/闪回现场捕获
            safeHook("Diagnostics", () -> hookDiagnostics(lpp));
        }
    }

    private interface HookFn {
        void run();
    }

    private void safeHook(String name, HookFn fn) {
        try {
            fn.run();
        } catch (Throwable t) {
            log("hook " + name + " failed (continuing): " + t);
        }
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------
    private void loadConfig() {
        File cfg = new File("/storage/emulated/0/fg_helper.props");
        if (!cfg.exists() || !cfg.canRead()) {
            log("no config file, using defaults");
            return;
        }
        try {
            java.util.Properties p = new java.util.Properties();
            FileInputStream in = new FileInputStream(cfg);
            p.load(in);
            in.close();
            sModel = p.getProperty("model", sModel);
            sDevice = p.getProperty("device", sDevice);
            sManufacturer = p.getProperty("manufacturer", sManufacturer);
            sBrand = p.getProperty("brand", sBrand);
            sSigBase64 = p.getProperty("sigBase64", null);
            sSpoofHeytap = Boolean.parseBoolean(
                    p.getProperty("hook_spoof_heytap", "false"));
            sSpoofHtms = Boolean.parseBoolean(
                    p.getProperty("hook_spoof_htms", "true"));
            sBlockAssistFinish = Boolean.parseBoolean(
                    p.getProperty("block_assist_finish", "false"));
            sNetBuildClass = p.getProperty("netbuild_class", sNetBuildClass);
            sGsonField = p.getProperty("gson_field", sGsonField);
            sGsonClass = p.getProperty("gson_class", sGsonClass);
            sPrivacyActivity = p.getProperty("privacy_activity", sPrivacyActivity);
            String extra = p.getProperty("extra_packages", "");
            if (!extra.trim().isEmpty()) {
                sExtraPkgs.addAll(Arrays.asList(extra.trim().split("\\s*,\\s*")));
            }
            log("config loaded: model=" + sModel + " spoofHeytap=" + sSpoofHeytap
                    + " extra=" + sExtraPkgs);
        } catch (IOException e) {
            log("config load failed: " + e);
        }
    }

    // ------------------------------------------------------------------
    // Hook 1: SystemProperties.get（仅 FULL 模式进程）
    // ------------------------------------------------------------------
    private void hookSystemProperties() {
        Class<?> spClass = XposedHelpers.findClass("android.os.SystemProperties", null);
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                String key = (String) param.args[0];
                if (key == null) return;
                String replaced = spoofProp(key);
                if (replaced != null) {
                    param.setResult(replaced);
                }
            }
        };
        // get(String) 与 get(String,String) 各自独立挂，一个失败不影响另一个
        try {
            XposedHelpers.findAndHookMethod(spClass, "get", String.class, hook);
        } catch (Throwable t) {
            log("hook SP.get(1) failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(spClass, "get", String.class, String.class, hook);
        } catch (Throwable t) {
            log("hook SP.get(2) failed: " + t);
        }
    }

    private String spoofProp(String key) {
        switch (key) {
            case "ro.product.manufacturer":
            case "ro.product.brand":
            case "ro.vendor.oplus.marketing.brand":
                return sManufacturer;
            case "ro.product.model":
            case "ro.vendor.oplus.marketing.name":
                return sModel;
            case "ro.product.device":
            case "ro.product.oplus.device":
                return sDevice;
            case "ro.build.version.oplusrom":
            case "ro.oplus.version.branch":
                return "e3a1f1b2c4";
            case "ro.build.version.oplusrom.exp":
                return "1";
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------
    // Hook 2: Build 静态字段 —— 逐字段独立容错，去掉 modifiers hack
    // ------------------------------------------------------------------
    private void hookBuildFields() {
        String[][] patches = {
                {"MANUFACTURER", sManufacturer},
                {"BRAND", sBrand},
                {"MODEL", sModel},
                {"DEVICE", sDevice},
                {"PRODUCT", sDevice},
        };
        int ok = 0;
        for (String[] kv : patches) {
            try {
                XposedHelpers.setStaticObjectField(Build.class, kv[0], kv[1]);
                ok++;
            } catch (Throwable t) {
                log("Build." + kv[0] + " patch failed: " + t);
            }
        }
        log("Build patched " + ok + "/" + patches.length);
    }

    // ------------------------------------------------------------------
    // Hook 3: hasSystemFeature —— oplus/coloros/oppo/heytap 特性放行（全模式）
    // ------------------------------------------------------------------
    private void hookHasSystemFeature(XC_LoadPackage.LoadPackageParam lpp) {
        Class<?> pmClass = XposedHelpers.findClass(
                "android.app.ApplicationPackageManager", lpp.classLoader);
        XposedBridge.hookAllMethods(pmClass, "hasSystemFeature", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args.length == 0) return;
                String feature = (String) param.args[0];
                if (feature != null && (feature.startsWith("oplus.")
                        || feature.startsWith("coloros.")
                        || feature.startsWith("oppo.")
                        || feature.startsWith("heytap."))) {
                    log("faking feature: " + feature);
                    param.setResult(true);
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Hook 4: 签名校验绕过（仅 FULL，且配置了 sigBase64）
    // ------------------------------------------------------------------
    private void hookSignatureCheck(XC_LoadPackage.LoadPackageParam lpp) {
        if (sSigBase64 == null) {
            log("sigBase64 not configured, signature hook skipped");
            return;
        }
        final Signature fake = new Signature(android.util.Base64.decode(sSigBase64,
                android.util.Base64.DEFAULT));
        Class<?> pmClass = XposedHelpers.findClass(
                "android.app.ApplicationPackageManager", lpp.classLoader);
        XposedBridge.hookAllMethods(pmClass, "getPackageInfo", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                PackageInfo info = (PackageInfo) param.getResult();
                if (info == null || info.signatures == null) return;
                if (info.packageName.startsWith("com.oplus.")
                        || info.packageName.startsWith("com.heytap.")
                        || info.packageName.startsWith("com.coloros.")) {
                    info.signatures = new Signature[]{fake};
                    param.setResult(info);
                }
            }
        });
        log("signature hook installed");
    }

    // ------------------------------------------------------------------
    /** v5.1: ConfigManager 托管 —— 必须在 dex 注入完成后调用 */
    private void hookOplusConfigManager(final ClassLoader cl2) {
        // v5.1: OplusFeatureConfigManager 托管 —— 构造器触发 OplusFeatureCache
        //   clinit（HyperOS 上 invalid cache name 必炸），把构造器整个替换成空操作
        try {
            Class<?> cm = cl2.loadClass("com.oplus.content.OplusFeatureConfigManager");
            for (java.lang.reflect.Constructor<?> c : cm.getDeclaredConstructors()) {
                XposedBridge.hookMethod(c, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        return null;
                    }
                });
            }
            final Object dummy;
            java.lang.reflect.Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            sun.misc.Unsafe u = (sun.misc.Unsafe) uf.get(null);
            dummy = u.allocateInstance(cm);
            for (java.lang.reflect.Method m : cm.getDeclaredMethods()) {
                final Class<?> ret = m.getReturnType();
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (m.getName().toLowerCase().contains("instance")) {
                                param.setResult(dummy);
                            } else {
                                param.setResult(defaultFor(ret, param.args));
                            }
                        }
                    });
                } else {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(defaultFor(ret, param.args));
                        }
                    });
                }
            }
            log("OplusFeatureConfigManager 已托管(dummy+默认值)");
        } catch (Throwable t) {
            log("hookOplusConfigManager failed: " + t);
        }

    }

    // Hook 10: oplus 兼容层短路（v5.0）—— z.d/v.n 的方法体在入口即被替换
    private void hookOplusCompat(final ClassLoader cl) {
        try {
            Class<?> zd = cl.loadClass("z.d");
            java.lang.reflect.Method[] ms = zd.getDeclaredMethods();
            int hooked = 0;
            for (final java.lang.reflect.Method m : ms) {
                if (m.getName().equals("<init>")) continue;
                final Class<?> ret = m.getReturnType();
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(defaultFor(ret, param.args));
                    }
                });
                hooked++;
            }
            log("z.d(SystemPropertiesCompat) 已短路 " + hooked + " 个方法");
        } catch (Throwable t) {
            log("hookOplusCompat(z.d) failed: " + t);
        }
        // v.n(): 启动 Trace（wrapper.os.Trace 直接引用）→ 短路
        try {
            Class<?> v = cl.loadClass("com.coloros.sharescreen.common.utils.v");
            for (java.lang.reflect.Method m : v.getDeclaredMethods()) {
                if (m.getName().equals("n") && m.getReturnType() == void.class
                        && m.getParameterCount() == 0) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(null);
                        }
                    });
                    log("v.n(Trace) 已短路");
                }
            }
        } catch (Throwable t) {
            log("hookOplusCompat(v.n) failed: " + t);
        }
    }

    /** 按返回类型从实参里找默认值：String→最后一个String(def)，boolean→Boolean，int→Integer */
    private static Object defaultFor(Class<?> ret, Object[] args) {
        if (ret == String.class) {
            String last = "";
            for (Object a : args) {
                if (a instanceof String) last = (String) a;
            }
            return last;
        }
        if (ret == boolean.class) {
            Boolean last = Boolean.FALSE;
            for (Object a : args) {
                if (a instanceof Boolean) last = (Boolean) a;
            }
            return last;
        }
        if (ret == int.class) {
            Integer last = 0;
            for (Object a : args) {
                if (a instanceof Integer) last = (Integer) a;
            }
            return last;
        }
        if (ret == long.class) return 0L;
        if (ret == float.class) return 0f;
        if (ret == double.class) return 0d;
        if (ret == short.class) return (short) 0;
        if (ret == byte.class) return (byte) 0;
        if (ret == char.class) return (char) 0;
        return null;
    }

    /** v5.2: 类托管 —— 全部方法返回默认值、构造器跳过，让依赖缺失系统服务的类变成空壳 */
    private void neuterClass(ClassLoader cl, String className, String label) {
        try {
            Class<?> c = cl.loadClass(className);
            int n = 0;
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("<init>")) continue;
                final Class<?> ret = m.getReturnType();
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(defaultFor(ret, param.args));
                    }
                });
                n++;
            }
            for (java.lang.reflect.Constructor<?> cc : c.getDeclaredConstructors()) {
                XposedBridge.hookMethod(cc, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        return null;
                    }
                });
                n++;
            }
            log(label + " 已托管(" + n + " 个方法/构造器)");
        } catch (Throwable t) {
            log(label + " 托管失败: " + t);
        }
    }

    // Hook 9: attachBaseContext 早期注入（v4.6）
    // ShareScreenApp.attachBaseContext 里 LogUtils.<clinit> 就要 oplus wrapper 类，
    // Application.attach 已太晚。hook ContextThemeWrapper.attachBaseContext（App 的
    // super 调用必然经过），在原逻辑执行前完成 dex 注入。
    private void hookEarlyInject() {
        try {
            XposedBridge.hookAllMethods(android.view.ContextThemeWrapper.class,
                    "attachBaseContext", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                injectRxJava((Context) param.args[0]);
                            } catch (Throwable t) {
                                log("early inject 异常: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            log("hook attachBaseContext failed: " + t);
        }
    }

    // Hook 5: Application.attach —— 伪装确认 + rxjava2 注入 + Gson 补齐
    // ------------------------------------------------------------------
    private void hookApplicationAttach() {
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // 静默重打，失败不影响主流程
                        try {
                            XposedHelpers.setStaticObjectField(Build.class, "MANUFACTURER",
                                    sManufacturer);
                            XposedHelpers.setStaticObjectField(Build.class, "BRAND", sBrand);
                            XposedHelpers.setStaticObjectField(Build.class, "MODEL", sModel);
                        } catch (Throwable ignored) {
                        }
                        if (!sIsFamilyGuard) {
                            return; // rxjava2/Gson 补齐只针对家人守护进程
                        }
                        // this = Application 实例（args[0] 是 ContextImpl，类型不匹配）
                        Context ctx = (Context) param.thisObject;
                        try {
                            injectRxJava(ctx);
                        } catch (Throwable t) {
                            log("injectRxJava 异常: " + t);
                        }
                        // rxjava2 就位后才能注册 getInstance hook（方法签名解析需要）
                        try {
                            registerGetInstanceHook(sLpp);
                        } catch (Throwable t) {
                            log("registerGetInstanceHook 异常: " + t);
                        }
                        try {
                            // 强制初始化 NetBuild（跑 <clinit> 置好单例字段 g）
                            Class.forName(sNetBuildClass, true, ctx.getClassLoader());
                            ensureNetBuildGson(ctx.getClassLoader(), ctx);
                        } catch (Throwable t) {
                            log("attach 阶段 NetBuild 初始化失败(不阻断): " + t);
                        }
                    }
                });
    }

    // ------------------------------------------------------------------
    // Hook 5.5: rxjava2 类库注入
    //   根因：sharescreen SDK 引用 RxJava2 全名（io.reactivex.*），
    //   APK 只自带 RxJava3（io.reactivex.rxjava3.*）；ColorOS 把 rxjava2
    //   作为系统框架库提供，HyperOS 没有 → 注入模块自带 dex。
    // ------------------------------------------------------------------
    private static volatile boolean sInjected = false;
    private static ClassLoader sOplusCl; // v4.8: oplus 类兜底加载器
    private static volatile boolean sHookingLoadClass = false;

    /** oplus 类 loadClass 委托兜底：app 加载 com.oplus.* 失败前先问 oplCl */
    private void hookLoadClassDelegate(final ClassLoader appCl) {
        try {
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (sHookingLoadClass) return;
                            String name = param.args.length > 0
                                    && param.args[0] instanceof String
                                    ? (String) param.args[0] : null;
                            if (name == null || sOplusCl == null) return;
                            if (!name.startsWith("com.oplus.")
                                    && !name.startsWith("oplux.")) return;
                            sHookingLoadClass = true;
                            try {
                                Class<?> c = sOplusCl.loadClass(name);
                                log("loadClass 委托命中: " + name);
                                param.setResult(c);
                            } catch (Throwable ignored) {
                                // oplCl 也没有 → 走原逻辑报真实错误
                            } finally {
                                sHookingLoadClass = false;
                            }
                        }
                    });
            log("loadClass 委托兜底已启用 (oplus)");
        } catch (Throwable t) {
            log("hookLoadClassDelegate failed: " + t);
        }
    }

    /** InMemoryDexClassLoader 兜底：oplus dex 内存加载，不经文件/W^X */
    private boolean setupOplusInMemory(Context ctx, File oplusDex) {
        try {
            byte[] data = new byte[(int) oplusDex.length()];
            try (java.io.FileInputStream fin = new java.io.FileInputStream(oplusDex)) {
                int off = 0, n;
                while (off < data.length && (n = fin.read(data, off, data.length - off)) > 0) {
                    off += n;
                }
            }
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocateDirect(data.length);
            bb.put(data);
            bb.flip();
            sOplusCl = new dalvik.system.InMemoryDexClassLoader(bb, ctx.getClassLoader());
            // 立即验证
            try {
                sOplusCl.loadClass("com.oplus.wrapper.os.SystemProperties");
                log("oplus InMemory 加载验证通过");
                return true;
            } catch (Throwable t) {
                log("oplus InMemory 验证失败: " + t);
                sOplusCl = null;
                return false;
            }
        } catch (Throwable t) {
            log("setupOplusInMemory failed: " + t);
            return false;
        }
    }

    private void injectRxJava(Context ctx) {
        if (sInjected) {
            log("本进程已注入过，跳过");
            return;
        }
        sInjected = true;
        try {
            // 0) hidden API 放行（VMRuntime 是实例方法，需先 getRuntime）
            try {
                Class<?> vm = Class.forName("dalvik.system.VMRuntime");
                java.lang.reflect.Method getRuntime = vm.getDeclaredMethod("getRuntime");
                getRuntime.setAccessible(true);
                Object runtime = getRuntime.invoke(null);
                java.lang.reflect.Method m = vm.getDeclaredMethod(
                        "setHiddenApiExemptions", String[].class);
                m.setAccessible(true);
                m.invoke(runtime, new Object[]{new String[]{"L"}});
                log("hiddenapi 放行 OK");
            } catch (Throwable t) {
                log("hiddenapi 放行失败(继续): " + t);
            }

            ClassLoader appCl = ctx.getClassLoader();
            try {
                appCl.loadClass("io.reactivex.disposables.Disposable");
                log("rxjava2 已可加载，跳过注入");
                return;
            } catch (Throwable notLoaded) {
                log("rxjava2 缺失，开始注入");
            }

            String moduleApk = locateModuleApk(ctx);
            if (moduleApk == null) {
                log("模块 APK 定位失败，注入中止");
                return;
            }

            // 释放 assets dex 到目标 app cache
            // 注意 W^X：必须释放后设为只读；且换版本名强制刷新缓存
            // 两个 dex：consumer 补丁在前（先到先得），rxjava 库在后
            // v4.7: oplus dex 只给 sharescreen/remoteguard 进程 ——
            // familyguard 自带一套 oplus 类，混入固件版会搅乱它的 OplusFeatureCache
            java.util.List<String[]> dexAssetList = new java.util.ArrayList<>();
            dexAssetList.add(new String[]{"assets/fg_consumer.dex", "fg_consumer_v1.dex"});
            dexAssetList.add(new String[]{"assets/fg_rxjava2.dex", "fg_rxjava2_v3.dex"});
            String curPkg = sLpp != null ? sLpp.packageName : "";
            if (curPkg.contains("sharescreen") || curPkg.contains("remoteguardservice")) {
                dexAssetList.add(new String[]{"assets/fg_oplus.dex", "fg_oplus_v2.dex"});
                log("当前进程 " + curPkg + " 需要注入 oplus framework");
            }
            String[][] dexAssets = dexAssetList.toArray(new String[0][]);
            java.util.List<java.io.File> dexFiles = new java.util.ArrayList<>();
            for (String[] da : dexAssets) {
                File f = new File(ctx.getCacheDir(), da[1]);
                if (!f.exists()) {
                    try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(moduleApk)) {
                        java.util.zip.ZipEntry e = zf.getEntry(da[0]);
                        if (e == null) {
                            log("模块 assets 缺少 " + da[0]);
                            continue;
                        }
                        try (java.io.InputStream in = zf.getInputStream(e);
                             java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                fo.write(buf, 0, n);
                            }
                        }
                    }
                    f.setWritable(false, false);
                    f.setReadOnly();
                    log("已释放并设为只读: " + da[1] + " (" + f.length() + " bytes)");
                } else {
                    log("缓存复用: " + da[1]);
                }
                dexFiles.add(f);
            }
            if (dexFiles.isEmpty()) {
                log("无可注入 dex，中止");
                return;
            }
            StringBuilder sb = new StringBuilder("dex 已就位:");
            for (java.io.File f : dexFiles) {
                sb.append(" ").append(f.getName()).append("(").append(f.length()).append(")");
            }
            log(sb.toString());

            Object pathList = XposedHelpers.getObjectField(appCl, "pathList");
            boolean ok = false;
            try {
                XposedHelpers.callMethod(pathList, "addDexPath",
                        dexFiles.get(0).getAbsolutePath());
                ok = true;
                log("addDexPath 成功");
            } catch (Throwable t) {
                log("addDexPath 不可用，改走 makeDexElements: " + t);
                ok = appendViaMakeElements(appCl, pathList, dexFiles)
                        || appendViaLoadDex(appCl, pathList, dexFiles);
            }
            if (ok) {
                try {
                    appCl.loadClass("io.reactivex.disposables.Disposable");
                    log("rxjava2 注入验证通过");
                } catch (Throwable t) {
                    log("注入后 Disposable 仍不可加载: " + t);
                }
            }

            // v5.1: ConfigManager 托管（依赖注入后的类加载器）
            String curPkg3 = sLpp != null ? sLpp.packageName : "";
            if (curPkg3.contains("sharescreen") || curPkg3.contains("remoteguardservice")) {
                try {
                    hookOplusConfigManager(ctx.getClassLoader());
                    ClassLoader cl5 = ctx.getClassLoader();
                    // v5.2: 整类托管 —— 依赖 OPPO 系统服务的组件降级为空实现
                    neuterClass(cl5, "com.coloros.sharescreen.floatwindow.floatwindow.FloatWindowManager", "FloatWindow(悬浮窗)");
                    neuterClass(cl5, "com.coloros.sharescreen.statemanager.phonestate.PhoneStateManager", "PhoneState(电话监听)");
                    neuterClass(cl5, "com.oplus.tingle.ipc.Slave", "TingleIPC(OPPO跨进程)");
                } catch (Throwable t) {
                    log("hookOplusConfigManager 调用异常: " + t);
                }
            }

            // v4.9: oplus 类无条件下沉到 InMemory 委托 ——
            // 文件 element 虽然 appCl.loadClass 能过，但 App 的 Class.forName 解析链不认；
            // 委托 hook 直接拦 ClassLoader.loadClass，两种路径全覆盖
            String curPkg2 = sLpp != null ? sLpp.packageName : "";
            if (curPkg2.contains("sharescreen") || curPkg2.contains("remoteguardservice")) {
                File od = new File(ctx.getCacheDir(), "fg_oplus_v2.dex");
                if (od.exists() && setupOplusInMemory(ctx, od)) {
                    hookLoadClassDelegate(appCl);
                } else {
                    log("oplus InMemory 兜底未启用");
                }
            }
        } catch (Throwable t) {
            log("injectRxJava failed: " + t);
        }
    }

    /** 模块 APK 路径：优先 PackageManager 查自身（模块本身就是正常安装的应用） */
    private String locateModuleApk(Context ctx) {
        // 路线 1: PackageManager（LSPosed 内存加载模块时类加载器反查不到路径）
        try {
            android.content.pm.ApplicationInfo ai = ctx.getPackageManager()
                    .getApplicationInfo("com.sb.familyguardhelper", 0);
            if (ai.sourceDir != null && new File(ai.sourceDir).exists()) {
                return ai.sourceDir;
            }
        } catch (Throwable t) {
            log("PM 查模块路径失败: " + t);
        }
        // 路线 2: 类加载器 dexElements 反查（直装场景兜底）
        try {
            ClassLoader mcl = HookEntry.class.getClassLoader();
            Object pl = XposedHelpers.getObjectField(mcl, "pathList");
            Object[] els = (Object[]) XposedHelpers.getObjectField(pl, "dexElements");
            for (Object el : els) {
                Object f = XposedHelpers.getObjectField(el, "path");
                if (f instanceof File) {
                    String p = ((File) f).getAbsolutePath();
                    if (p.endsWith(".apk") && new File(p).exists()) {
                        return p;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 防止注入的 DexFile 被回收 */
    private static Object sKeepRef;

    /**
     * 路线 1：目标类加载器自己的 makeDexElements 静态工厂造 Element。
     * 不同版本签名不同：带/不带末尾 ClassLoader 参数，逐个尝试。
     * 关键：DexFile 由 App 自己的加载器注册，不会触发 multiple class loaders。
     */
    private boolean appendViaMakeElements(ClassLoader appCl, Object pathList,
            java.util.List<java.io.File> dexFiles) {
        Class<?> plCls = pathList.getClass();
        Class<?>[][] sigs = new Class<?>[][]{
                {java.util.List.class, java.io.File.class, java.util.List.class, ClassLoader.class},
                {java.util.List.class, java.io.File.class, java.util.List.class},
                {java.util.ArrayList.class, java.io.File.class, java.util.ArrayList.class, ClassLoader.class},
                {java.util.ArrayList.class, java.io.File.class, java.util.ArrayList.class}};
        for (Class<?>[] sig : sigs) {
            try {
                java.lang.reflect.Method m = plCls.getDeclaredMethod("makeDexElements", sig);
                m.setAccessible(true);
                java.util.ArrayList<java.io.File> files = new java.util.ArrayList<>(dexFiles);
                java.util.ArrayList<java.io.IOException> suppressed =
                        new java.util.ArrayList<>();
                Object[] els;
                if (sig.length == 4) {
                    els = (Object[]) m.invoke(null, files, null, suppressed, appCl);
                } else {
                    els = (Object[]) m.invoke(null, files, null, suppressed);
                }
                // v4.8: 不再吞异常 —— 打出每个加载失败的 dex
                for (java.io.IOException se : suppressed) {
                    log("makeDexElements suppressed: " + se);
                }
                if (els == null || els.length == 0) {
                    log("makeDexElements 返回空");
                    continue;
                }
                if (!suppressed.isEmpty()) {
                    log("makeDexElements 抑制异常: " + suppressed);
                }
                Object[] old = (Object[]) XposedHelpers.getObjectField(pathList, "dexElements");
                Object[] arr = (Object[]) java.lang.reflect.Array.newInstance(
                        old.getClass().getComponentType(), old.length + els.length);
                System.arraycopy(old, 0, arr, 0, old.length);
                System.arraycopy(els, 0, arr, old.length, els.length);
                XposedHelpers.setObjectField(pathList, "dexElements", arr);
                log("makeDexElements 注入完成 (+" + els.length + " element)");
                return true;
            } catch (NoSuchMethodException ignored) {
                // 换下一种签名
            } catch (Throwable t) {
                log("makeDexElements 调用失败: " + t);
            }
        }
        return false;
    }

    /** 路线 2：DexFile.loadDex + 手工构造 Element（hiddenapi 已放行时可用） */
    private boolean appendViaLoadDex(ClassLoader appCl, Object pathList,
            java.util.List<java.io.File> dexFiles) {
        try {
            Class<?> elCls = Class.forName("dalvik.system.DexPathList$Element");
            Object[] old = (Object[]) XposedHelpers.getObjectField(pathList, "dexElements");
            Object[] arr = (Object[]) java.lang.reflect.Array.newInstance(
                    old.getClass().getComponentType(), old.length + dexFiles.size());
            System.arraycopy(old, 0, arr, 0, old.length);
            int idx = old.length;
            for (java.io.File dex : dexFiles) {
                Class<?> dfCls = Class.forName("dalvik.system.DexFile");
                java.lang.reflect.Method loadDex = dfCls.getDeclaredMethod("loadDex",
                        String.class, String.class, int.class);
                loadDex.setAccessible(true);
                File odex = new File(dex.getParentFile(),
                        dex.getName().replace(".dex", ".odex"));
                Object df = loadDex.invoke(null, dex.getAbsolutePath(),
                        odex.getAbsolutePath(), 0);
                sKeepRef = df;
                Object newEl;
                try {
                    newEl = elCls.getDeclaredConstructor(
                            java.io.File.class, boolean.class, java.io.File.class, dfCls)
                            .newInstance(null, false, null, df);
                } catch (Throwable t) {
                    newEl = elCls.getDeclaredConstructor(dfCls).newInstance(df);
                }
                arr[idx++] = newEl;
            }
            XposedHelpers.setObjectField(pathList, "dexElements", arr);
            log("loadDex + Element 注入完成 (" + dexFiles.size() + ")");
            return true;
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            log("appendViaLoadDex failed: " + cause);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Hook 8: HyperOS 帐号兼容 —— OPPO 日历组件要创建 com.heytap 类型
    //   本地帐号，HyperOS 上非帐号属主直接 SecurityException 炸主线程。
    //   addAccountExplicitly 拦截返回 true；addAccount(异步) 换成立即完成的假 Future。
    // ------------------------------------------------------------------
    private void hookAccountManager() {
        try {
            XposedBridge.hookAllMethods(android.accounts.AccountManager.class,
                    "addAccountExplicitly", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object acc = param.args.length > 0 ? param.args[0] : null;
                            String type = acc != null
                                    ? ((android.accounts.Account) acc).type : null;
                            if (type != null && (type.contains("heytap")
                                    || type.contains("oppo") || type.contains("oplus"))) {
                                log("addAccountExplicitly(" + type + ") 拦截 -> 伪装成功");
                                param.setResult(true);
                            }
                        }
                    });
        } catch (Throwable t) {
            log("hook addAccountExplicitly failed: " + t);
        }
        try {
            XposedBridge.hookAllMethods(android.accounts.AccountManager.class,
                    "addAccount", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length == 0) return;
                            String type = param.args[0] instanceof String
                                    ? (String) param.args[0] : null;
                            if (type == null || !(type.contains("heytap")
                                    || type.contains("oppo") || type.contains("oplus"))) {
                                return;
                            }
                            log("addAccount(" + type + ") 拦截 -> 假 Future");
                            final android.os.Bundle fake = new android.os.Bundle();
                            fake.putString("authAccount", "local");
                            fake.putString("accountType", type);
                            param.setResult(new android.accounts.AccountManagerFuture<android.os.Bundle>() {
                                @Override public boolean cancel(boolean b) { return false; }
                                @Override public boolean isCancelled() { return false; }
                                @Override public boolean isDone() { return true; }
                                @Override public android.os.Bundle getResult()
                                        throws android.accounts.OperationCanceledException,
                                        java.io.IOException,
                                        android.accounts.AuthenticatorException {
                                    return fake;
                                }
                                @Override public android.os.Bundle getResult(long l,
                                        java.util.concurrent.TimeUnit timeUnit)
                                        throws android.accounts.OperationCanceledException,
                                        java.io.IOException,
                                        android.accounts.AuthenticatorException {
                                    return fake;
                                }
                            });
                        }
                    });
        } catch (Throwable t) {
            log("hook addAccount failed: " + t);
        }
    }

    // ------------------------------------------------------------------
    // Hook 6: 诊断增强 —— 捕获「协助家人闪回主界面」现场
    //   6a. Activity.finish 打日志（可选阻止协助类页面退出）
    //   6b. 全局未捕获异常记录
    //   6c. System.exit / Runtime.exit 拦截日志
    // ------------------------------------------------------------------
    private void hookDiagnostics(final XC_LoadPackage.LoadPackageParam lpp) {
        // 6a. finish 监控
        try {
            XposedBridge.hookAllMethods(android.app.Activity.class, "finish",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String cls = param.thisObject.getClass().getName();
                            log("Activity.finish -> " + cls);
                            if (sBlockAssistFinish && isAssistPage(cls)) {
                                log("BLOCKED finish of assist page: " + cls
                                        + " (按 fg_helper.props block_assist_finish=false 可关闭)");
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable t) {
            log("hook Activity.finish failed: " + t);
        }

        // 6b. 未捕获异常兜底记录（任何崩溃都能看到完整堆栈）
        try {
            final Thread.UncaughtExceptionHandler prev =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(
                    (t, e) -> {
                        log("UNCAUGHT in " + lpp.packageName + ": "
                                + android.util.Log.getStackTraceString(e));
                        if (prev != null) {
                            prev.uncaughtException(t, e);
                        }
                    });
        } catch (Throwable t) {
            log("uncaught handler wrap failed: " + t);
        }

        // 6c. 显式退出调用监控（部分 App 用 exit 硬退）
        try {
            XposedBridge.hookAllMethods(Runtime.class, "exit",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            log("Runtime.exit(" + param.args[0] + ") called! 堆栈:\n"
                                    + android.util.Log.getStackTraceString(
                                            new Exception("exit trace")));
                            if (sBlockAssistFinish) {
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable t) {
            log("hook Runtime.exit failed: " + t);
        }
    }

    private boolean isAssistPage(String activityClass) {
        String low = activityClass.toLowerCase();
        return low.contains("assist") || low.contains("remote")
                || low.contains("family") || low.contains("guard")
                || low.contains("bind") || low.contains("help");
    }

    // ------------------------------------------------------------------
    // Hook 7: sharescreen SDK 初始化补丁
    //   OPPO 上 NetBuild.mGson 由系统侧提前初始化；HyperOS 没人调 init，
    //   协助页 onCreate → NetBuild.j() 直接 lateinit 崩溃。
    //   双保险：Application.attach 后强塞一次 + 协助页 onCreate 前再补一次。
    // ------------------------------------------------------------------
    private void hookNetBuildFix(final XC_LoadPackage.LoadPackageParam lpp) {
        // 保险 1：进程起来立即补一次（无 Context，appContext 环节跳过）
        ensureNetBuildGson(lpp.classLoader, null);
        // 保险 2：协助页 onCreate 前再补一次（防 attach 后又有路径清空）
        try {
            Class<?> privacyAct;
            try {
                privacyAct = lpp.classLoader.loadClass(sPrivacyActivity);
            } catch (Throwable t) {
                privacyAct = null; // 类不存在则静默跳过，不影响其它功能
            }
            if (privacyAct != null) {
                XposedBridge.hookAllMethods(privacyAct, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        log("PrivacyPageActivity onCreate, 兜底补 init");
                        ensureNetBuildGson(lpp.classLoader, null);
                    }
                });
            }
        } catch (Throwable t) {
            log("hook privacy activity onCreate failed: " + t);
        }
        // 保险 3：getInstance hook —— 必须等 rxjava2 注入完成后才能注册
        // （hook 方法解析签名时要加载字段类型 CompositeDisposable）
        // 这里只记录待注册状态，真正注册在 attach 注入完成后执行
        // 见 attach 阶段的 registerGetInstanceHook()
    }

    /** attach 注入完成后调用：注册 getInstance / 崩溃点 v 的 after/before-hook */
    private void registerGetInstanceHook(final XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> nb = lpp.classLoader.loadClass(sNetBuildClass);
            // hook e()（疑似 getInstance）
            try {
                XposedBridge.hookAllMethods(nb, "e", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getResult() != null) {
                            ensureNetBuildGson(lpp.classLoader, null);
                        }
                    }
                });
                log("getInstance(e) hook 注册成功");
            } catch (Throwable t) {
                log("hook e failed: " + t);
            }
            // hook v() —— 崩溃点本身；before-hook 时单例必然存在
            try {
                XposedBridge.hookAllMethods(nb, "v", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        log("NetBuild.v 进入，执行补齐");
                        ensureNetBuildGson(lpp.classLoader, null);
                    }
                });
                log("crash-point(v) hook 注册成功");
            } catch (Throwable t) {
                log("hook v failed: " + t);
            }
        } catch (Throwable t) {
            log("register hooks failed: " + t);
        }
    }

    /**
     * v2.4: 按类型扫描注入（不依赖字段名）
     * 根因更新：R8 混淆把 lateinit 字段名改掉，但 Kotlin 的
     * UninitializedPropertyAccessException 消息字符串 "lateinit property mGson..."
     * 是字面量被原样保留 —— 按名字反射必挂 NoSuchFieldException。
     * 改为：遍历 NetBuild + 其 Companion/内部类 + 超类链，
     * 凡是 Gson 类型、值为 null 的字段一律注入新 Gson 实例。
     */
    private void ensureNetBuildGson(ClassLoader cl, Context ctxOrNull) {
        try {
            Class<?> nb = cl.loadClass(sNetBuildClass);

            // ---- 前置：sharescreen 全部 Application/Context holder 一次性填齐 ----
            if (ctxOrNull != null) {
                String[][] holders = {
                        {"com.coloros.sharescreen.common.base.BaseApplication", "g"},
                        {"com.coloros.sharescreen.request.RequestApplication", "a"},
                        {"com.coloros.sharescreen.connecting.ConnectingApplication", "a"},
                        {"com.coloros.sharescreen.floatwindow.FloatWindowApplication", "a"},
                        {"com.coloros.sharescreen.sharing.SharingApplication", "b"},
                        {"com.coloros.sharescreen.statemanager.StateManagerApplicationConfig", "a"},
                };
                for (String[] h : holders) {
                    try {
                        Class<?> c = cl.loadClass(h[0]);
                        Field f = c.getDeclaredField(h[1]);
                        if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                            continue;
                        }
                        f.setAccessible(true);
                        Object cur = null;
                        try {
                            cur = f.get(null);
                        } catch (Throwable ignored) {
                        }
                        if (cur == null) {
                            f.set(null, ctxOrNull);
                            log("holder 已注入: "
                                    + h[0].substring(h[0].lastIndexOf('.') + 1)
                                    + "." + h[1]);
                        }
                    } catch (Throwable t) {
                        log("holder 跳过 " + h[0] + "." + h[1] + ": " + t);
                    }
                }
            }

            // ---- 探针：关键库可加载性实测 ----
            String[] probes = {
                    "io.reactivex.disposables.CompositeDisposable",
                    "io.reactivex.disposables.Disposable",
                    "io.reactivex.schedulers.Schedulers",
                    "io.reactivex.android.schedulers.AndroidSchedulers",
                    "retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory",
                    "org.reactivestreams.Publisher",
                    "okhttp3.OkHttpClient",
                    "retrofit2.Retrofit",
                    "com.google.gson.Gson",
            };
            for (String p : probes) {
                try {
                    cl.loadClass(p);
                    log("probe OK : " + p);
                } catch (Throwable t) {
                    log("probe FAIL : " + p + " -> " + t);
                }
            }

            // ---- 单字母字段探测（getDeclaredField 只解析单个字段类型，
            //      绕开 getDeclaredFields 全量枚举对 rxjava 的 NCDFE 连锁崩）----
            Field gsonField = null, hostField = null, serviceField = null;
            String[] candidates = new String[29];
            int i = 0;
            for (char c = 'a'; c <= 'z'; c++) candidates[i++] = String.valueOf(c);
            candidates[26] = "INSTANCE";
            candidates[27] = "Companion";
            candidates[28] = "mGson";
            for (String name : candidates) {
                Field f;
                try {
                    f = nb.getDeclaredField(name);
                } catch (Throwable t) {
                    continue; // 不存在（或该字段类型不可解析）→ 跳过
                }
                try {
                    f.setAccessible(true);
                    boolean st = java.lang.reflect.Modifier.isStatic(f.getModifiers());
                    String tn = f.getType().getName();
                    if (!"h6.a".equals(tn)) {
                        log("字段 " + name + " : " + tn + (st ? " (static)" : ""));
                    }
                    if (tn.equals(sGsonClass) && !st) {
                        gsonField = f; // 目标：Gson 类型 instance 字段（原 mGson）
                    }
                    if (st && f.getType() == nb) {
                        hostField = f; // 宿主：静态字段类型 == NetBuild（原 INSTANCE）
                    }
                    if (tn.equals("h6.a") && !st) {
                        serviceField = f; // 网络请求服务（原 iNetRequestService）
                    }
                } catch (Throwable t) {
                    log("字段 " + name + " 解析失败: " + t);
                }
            }
            if (gsonField == null) {
                log("未找到 Gson instance 字段，注入中止");
                return;
            }
            Object host = null;
            if (hostField != null) {
                host = hostField.get(null);
            }
            if (host == null) {
                // 釜底抽薪：不等 App 建单例，自己 new 一个塞进静态字段。
                // 后续 App 通过 getInstance/静态访问拿到的都是这个初始化好的实例，
                // 规避 Epona 类加载器副本导致的 hook 时机错配。
                try {
                    host = nb.getDeclaredConstructor().newInstance();
                    hostField.set(null, host);
                    log("已自建 NetBuild 单例并写入静态字段 " + hostField.getName());
                } catch (Throwable t) {
                    log("自建单例失败: " + t);
                    return;
                }
            }
            Object cur = null;
            try {
                cur = gsonField.get(host);
            } catch (Throwable ignored) {
            }
            if (cur == null) {
                Object gson = gsonField.getType().getDeclaredConstructor().newInstance();
                gsonField.set(host, gson);
                log("Gson 注入成功: host=" + hostField.getName()
                        + " field=" + gsonField.getName());
            } else {
                log("Gson 已初始化(" + gsonField.getName() + ")，跳过");
            }

            // ---- 网络服务初始化：反射调用 NetBuild.s() ----
            // s() 内部：okhttp 客户端 → Retrofit(baseUrl/gson/RxJava2 工厂)
            //          → retrofit.create(h6.a) → 赋值字段 f（原 iNetRequestService）
            // 注意：s() 的协程依赖 appContext，必须在 attach 阶段（ctx != null）
            // 先注入 appContext 再调用；handleLoadPackage 等无 ctx 阶段只补 Gson
            if (serviceField != null) {
                Object svc = null;
                try {
                    svc = serviceField.get(host);
                } catch (Throwable ignored) {
                }
                if (svc != null) {
                    log("网络服务已初始化，跳过 s()");
                } else if (ctxOrNull == null) {
                    log("无 Context，s() 推迟到 attach 阶段执行");
                } else {
                    try {
                        java.lang.reflect.Method s = nb.getDeclaredMethod("s");
                        s.setAccessible(true);
                        s.invoke(host);
                        Object after = null;
                        try {
                            after = serviceField.get(host);
                        } catch (Throwable ignored) {
                        }
                        log("NetBuild.s() 执行完成, iNetRequestService="
                                + (after != null ? "已就绪" : "仍为空"));
                    } catch (Throwable t) {
                        Throwable cause = t.getCause() != null ? t.getCause() : t;
                        log("NetBuild.s() 调用失败: " + cause);
                    }
                }
            } else {
                log("未找到 h6.a 服务字段，s() 跳过");
            }
        } catch (Throwable t) {
            log("ensureNetBuildGson failed: " + t);
        }
    }

    private Object findSingletonHost(Class<?> declaring, Class<?> outer, ClassLoader cl) {
        Object h = findStaticFieldOfType(declaring, declaring);
        if (h != null) return h;
        if (outer != declaring) {
            h = findStaticFieldOfType(outer, declaring);
            if (h != null) return h;
        }
        try {
            return declaring.getDeclaredField("INSTANCE").get(null);
        } catch (Throwable ignored) {
        }
        try {
            return declaring.getDeclaredField("Companion").get(null);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 在 cls 的静态字段里找类型为 target 的第一个实例并返回其值 */
    private Object findStaticFieldOfType(Class<?> cls, Class<?> target) {
        try {
            for (Field f : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        && f.getType() == target) {
                    f.setAccessible(true);
                    return f.get(null);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void log(String msg) {
        XposedBridge.log("[" + TAG + "] " + msg);
    }
}
