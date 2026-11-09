package de.robv.android.xposed;

import java.lang.reflect.Member;

/** compile-only 桩 */
public abstract class XC_MethodHook {

    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result = null;
        private Throwable throwable = null;
        public boolean returnEarly = false;

        public Object getResult() { return result; }
        public void setResult(Object result) {
            this.result = result;
            this.returnEarly = true;
        }
        public Throwable getThrowable() { return throwable; }
        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.returnEarly = true;
        }
        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }
    }

    public static class Unhook {
        private final Member handle;
        public Unhook(Member handle) { this.handle = handle; }
        public Member getHookedMethod() { return handle; }
        public void unhook() {}
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
}
