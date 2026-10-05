package com.chekayo.feishuantirecall;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 反射工具：替代业务代码实际使用的 XposedHelpers 子集
 * （findClass / callMethod ×9 / callStaticMethod ×6 / getStaticObjectField ×2，
 * 外加 hook 注册所需的精确查找与按签名枚举）。
 *
 * 语义对齐 legacy：
 * - 一律使用调用方传入的目标应用 ClassLoader，绝不退回模块自身 ClassLoader 查目标类；
 * - 方法/字段查找沿父类上溯（callMethod 的 best-match 与 legacy 一致）；
 * - 参数匹配处理 primitive 装箱与加宽（int→long 等）以及 null 实参（只匹配引用型形参）；
 * - InvocationTargetException 解包为原始原因后抛出；找不到成员抛带目标类/方法信息的 Error；
 * - 不通过字符串反射调用任何 de.robv / legacy API。
 *
 * 已知与 legacy 的差异：按签名枚举（findDeclaredMethods/findDeclaredConstructors）
 * 只看本类 declared 成员、不上溯——这是刻意的，供 HookRuntime.hookAll* 保持
 * legacy hookAllMethods/hookAllConstructors 的语义。
 */
public final class Reflect {

    private static final Map<String, Class<?>> PRIMITIVES = new HashMap<String, Class<?>>();
    private static final Map<Class<?>, Class<?>> BOX = new HashMap<Class<?>, Class<?>>();
    /** 加宽矩阵：key=实参的包装类，value=可加宽匹配到的 primitive 形参。 */
    private static final Map<Class<?>, Class<?>[]> WIDENING = new HashMap<Class<?>, Class<?>[]>();

    static {
        putPrim("boolean", Boolean.class); putPrim("byte", Byte.class);
        putPrim("char", Character.class);  putPrim("short", Short.class);
        putPrim("int", Integer.class);     putPrim("long", Long.class);
        putPrim("float", Float.class);     putPrim("double", Double.class);
        WIDENING.put(Byte.class,      new Class<?>[]{short.class, int.class, long.class, float.class, double.class});
        WIDENING.put(Short.class,     new Class<?>[]{int.class, long.class, float.class, double.class});
        WIDENING.put(Character.class, new Class<?>[]{int.class, long.class, float.class, double.class});
        WIDENING.put(Integer.class,   new Class<?>[]{long.class, float.class, double.class});
        WIDENING.put(Long.class,      new Class<?>[]{float.class, double.class});
        WIDENING.put(Float.class,     new Class<?>[]{double.class});
        WIDENING.put(Double.class,    new Class<?>[]{});
    }

    private static void putPrim(String name, Class<?> box) {
        Class<?> p;
        try { p = (Class<?>) box.getField("TYPE").get(null); }
        catch (Exception e) { throw new IllegalStateException(e); }
        PRIMITIVES.put(name, p);
        BOX.put(p, box);
    }

    private Reflect() {}

    // ── 类查找 ──────────────────────────────────────────────────────────

    /**
     * 等价 legacy XposedHelpers.findClass：支持 primitive 名（"int" 等）；
     * cl 为 null 时按 boot classloader 解析。失败抛 NoClassDefFoundError（带类名与 loader 信息）。
     */
    public static Class<?> findClass(String className, ClassLoader cl) {
        Class<?> prim = PRIMITIVES.get(className);
        if (prim != null) return prim;
        try {
            return (cl != null) ? Class.forName(className, false, cl) : Class.forName(className);
        } catch (ClassNotFoundException e) {
            NoClassDefFoundError err = new NoClassDefFoundError("Reflect.findClass failed: " + className
                    + " (loader=" + cl + ")");
            err.initCause(e);
            throw err;
        }
    }

    // ── 精确查找（沿父类上溯） ─────────────────────────────────────────

    /**
     * 等价 legacy XposedHelpers.findMethodExact：名字与形参表精确匹配
     * （primitive 与其包装类视为等价），沿父类上溯。找不到抛 NoSuchMethodException。
     */
    public static Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            Method exact = findExactIn(c.getDeclaredMethods(), methodName, parameterTypes);
            if (exact != null) return exact;
        }
        throw new NoSuchMethodException(sigString(clazz, methodName, parameterTypes));
    }

    private static Method findExactIn(Method[] methods, String methodName, Class<?>[] parameterTypes) {
        for (Method m : methods) {
            if (!m.getName().equals(methodName)) continue;
            if (!paramsExact(m.getParameterTypes(), parameterTypes)) continue;
            return m;
        }
        return null;
    }

    private static boolean paramsExact(Class<?>[] declared, Class<?>[] wanted) {
        if (declared.length != wanted.length) return false;
        for (int i = 0; i < declared.length; i++) {
            Class<?> d = declared[i], w = wanted[i];
            if (d == w) continue;
            boolean boxEq = d.isPrimitive()
                    ? BOX.get(d) == w
                    : (w.isPrimitive() && BOX.get(w) == d);
            if (!boxEq) return false;
        }
        return true;
    }

    // ── 按签名枚举 declared 成员（不沿父类，供 hookAll* 保持 legacy 语义） ──

    /**
     * 枚举本类 declared methods。methodName 传 null 通配名字；parameterTypes 传 null 通配签名，
     * 否则按 {@link #paramsExact} 精确匹配。与 legacy hookAllMethods 一致：不含继承成员。
     */
    public static List<Method> findDeclaredMethods(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        List<Method> out = new ArrayList<Method>();
        for (Method m : clazz.getDeclaredMethods()) {
            if (methodName != null && !m.getName().equals(methodName)) continue;
            if (parameterTypes != null && !paramsExact(m.getParameterTypes(), parameterTypes)) continue;
            out.add(m);
        }
        return out;
    }

    /** 枚举本类 declared constructors。parameterTypes 传 null 通配，否则精确匹配。 */
    public static List<Constructor<?>> findDeclaredConstructors(Class<?> clazz, Class<?>... parameterTypes) {
        List<Constructor<?>> out = new ArrayList<Constructor<?>>();
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            if (parameterTypes != null && !paramsExact(c.getParameterTypes(), parameterTypes)) continue;
            out.add(c);
        }
        return out;
    }

    // ── 调用 ────────────────────────────────────────────────────────────

    /** 等价 legacy XposedHelpers.callMethod：best-match + 解包原始异常。 */
    public static Object callMethod(Object receiver, String methodName, Object... args) {
        if (receiver == null) {
            throw new NullPointerException("Reflect.callMethod: receiver null, method=" + methodName);
        }
        return invokeBestMatch(receiver.getClass(), methodName, receiver, args);
    }

    /** 等价 legacy XposedHelpers.callStaticMethod。 */
    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        return invokeBestMatch(clazz, methodName, null, args);
    }

    private static Object invokeBestMatch(Class<?> start, String methodName, Object receiver, Object[] args) {
        Method best = null;
        int bestScore = Integer.MAX_VALUE;
        Class<?>[] argTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) argTypes[i] = (args[i] == null) ? null : args[i].getClass();
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                int score = matchScore(m.getParameterTypes(), args, argTypes);
                if (score >= 0 && score < bestScore) { best = m; bestScore = score; }
            }
        }
        if (best == null) {
            throw new NoSuchMethodError("Reflect: no best-match method for "
                    + sigString(start, methodName, argTypes));
        }
        try {
            if (!best.isAccessible()) best.setAccessible(true);
            return best.invoke(receiver, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException("Reflect: method threw ("
                    + sigString(start, methodName, argTypes) + ")", cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Reflect: inaccessible "
                    + sigString(start, methodName, argTypes), e);
        }
    }

    /**
     * 实参-形参兼容度：全兼容返回各项得分和（越小越精确），任一不兼容返回 -1。
     * 得分：同名同型 0；primitive 装箱/加宽 1；引用 assignable 2；null 实参配引用形参 3。
     */
    private static int matchScore(Class<?>[] params, Object[] args, Class<?>[] argTypes) {
        if (params.length != args.length) return -1;
        int total = 0;
        for (int i = 0; i < params.length; i++) {
            Class<?> p = params[i];
            Object arg = args[i];
            if (arg == null) {
                if (p.isPrimitive()) return -1;
                total += 3;
                continue;
            }
            Class<?> a = argTypes[i];
            if (p == a) { continue; }                       // exact：+0
            if (p.isPrimitive()) {
                if (BOX.get(p) == a) { total += 1; continue; }
                Class<?>[] widen = WIDENING.get(a);
                if (widen != null) {
                    boolean canWiden = false;
                    for (Class<?> wp : widen) { if (wp == p) { canWiden = true; break; } }
                    if (canWiden) { total += 1; continue; }
                }
                return -1;
            }
            if (p.isInstance(arg)) { total += 2; continue; }
            return -1;
        }
        return total;
    }

    // ── 静态字段 ────────────────────────────────────────────────────────

    /** 等价 legacy XposedHelpers.getStaticObjectField：沿父类找字段，找不到抛 NoSuchFieldError。 */
    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        try {
            return findField(clazz, fieldName).get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Reflect: inaccessible field " + clazz.getName() + "." + fieldName, e);
        }
    }

    /** 写静态字段（ModulePath 写 AntiRecall/ResignTracker 的 MODULE_PATH 用；字段可跨包 package-private）。 */
    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {
        try {
            findField(clazz, fieldName).set(null, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Reflect: inaccessible field " + clazz.getName() + "." + fieldName, e);
        }
    }

    private static Field findField(Class<?> clazz, String fieldName) {
        NoSuchFieldException first = null;
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                if (!f.isAccessible()) f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                if (first == null) first = e;
            }
        }
        NoSuchFieldError err = new NoSuchFieldError("Reflect: no field " + clazz.getName() + "." + fieldName);
        if (first != null) err.initCause(first);
        throw err;
    }

    // ── 诊断 ────────────────────────────────────────────────────────────

    private static String sigString(Class<?> clazz, String name, Class<?>[] types) {
        StringBuilder sb = new StringBuilder(clazz.getName()).append('#').append(name).append('(');
        if (types != null) {
            for (int i = 0; i < types.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(types[i] == null ? "null" : types[i].getName());
            }
        }
        return sb.append(')').toString();
    }
}
