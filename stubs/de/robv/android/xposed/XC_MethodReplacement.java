package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** compile-only 桩 */
public abstract class XC_MethodReplacement extends XC_MethodHook {
    public static final XC_MethodReplacement RETURN_NULL = new XC_MethodReplacement() {
        @Override
        protected Object replaceHookedMethod(MethodHookParam param) { return null; }
    };

    @Override
    protected final void beforeHookedMethod(MethodHookParam param) throws Throwable {
        param.setResult(replaceHookedMethod(param));
    }

    @Override
    protected final void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;
}
