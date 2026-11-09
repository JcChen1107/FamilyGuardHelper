package de.robv.android.xposed.callbacks;

/** compile-only 桩：真实 API 中 XC_LoadPackage 位于 callbacks 包 */
public class XC_LoadPackage {
    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public android.content.pm.ApplicationInfo appInfo;
        public boolean isFirstApplication;
        public LoadPackageParam() {}
    }
}
