package de.robv.android.xposed;

import java.lang.reflect.Member;

/** compile-only 桩 */
public class XposedBridge {

    public static void log(String text) {}
    public static void log(Throwable t) {}

    public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> clazz, String methodName, XC_MethodHook hook) {
        return null;
    }

    public static XC_MethodHook.Unhook hookMethod(
            Member method, XC_MethodHook hook) {
        return null;
    }
}
