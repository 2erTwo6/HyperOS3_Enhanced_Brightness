package de.robv.android.xposed;
public final class XposedHelpers {
    public static Class<?> findClass(String name, ClassLoader cl) throws Throwable { return null; }
    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> c, String m, Object... a) throws Throwable { return null; }
    public static XC_MethodHook.Unhook findAndHookConstructor(Class<?> c, Object... a) throws Throwable { return null; }
    public static float getFloatField(Object o, String f) throws Throwable { return 0f; }
    public static int getIntField(Object o, String f) throws Throwable { return 0; }
    public static Object getObjectField(Object o, String f) throws Throwable { return null; }
    public static void setFloatField(Object o, String f, float v) throws Throwable {}
    public static Object callMethod(Object o, String m, Object... a) throws Throwable { return null; }
    public static Object callStaticMethod(Class<?> c, String m, Object... a) throws Throwable { return null; }
}