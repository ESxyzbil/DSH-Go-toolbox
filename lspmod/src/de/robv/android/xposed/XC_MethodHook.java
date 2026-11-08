package de.robv.android.xposed;

public class XC_MethodHook {
    public static class MethodHookParam {
        public Object[] args;
        public Object thisObject;
        private Object result;
        public boolean returnEarly;

        public Object getResult() { return result; }
        public void setResult(Object r) { result = r; returnEarly = true; }
    }

    public static class Unhook {
        public void unhook() {}
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
}
