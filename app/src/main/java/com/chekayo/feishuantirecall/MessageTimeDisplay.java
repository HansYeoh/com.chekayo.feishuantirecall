package com.chekayo.feishuantirecall;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** 消息下方时间：使用绑定模型的 create_time（秒），不解析相对日期文案。 */
final class MessageTimeDisplay {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<TextView, State> VIEWS = new WeakHashMap<TextView, State>();
    private static boolean changing;
    private static boolean installed;
    private static final class State {
        long seconds;
        CharSequence original;
        int visibility;
        State(long seconds, TextView tv) {
            this.seconds = seconds; original = tv.getText(); visibility = tv.getVisibility();
        }
    }

    static synchronized void install(ClassLoader loader) {
        if (installed) return;
        try {
            // 当前飞书 8.x：时间块 N(holder, messageVO)；类型和字段同时校验。
            Class<?> block = loader.loadClass("x64.d");
            Method binding = null;
            for (Method method : block.getDeclaredMethods()) {
                Class<?>[] args = method.getParameterTypes();
                if (!method.getName().equals("N") || args.length != 2 || method.getReturnType() != void.class) continue;
                Method timestamp = args[1].getMethod("c");
                if (timestamp.getReturnType() != long.class) continue;
                for (Field field : args[0].getDeclaredFields())
                    if (TextView.class.isAssignableFrom(field.getType())) binding = method;
            }
            if (binding == null) throw new NoSuchMethodException("消息时间块签名未匹配");
            XposedBridge.hookMethod(binding, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    TextView tv = findTime(p.args[0]);
                    if (tv != null) { State state = VIEWS.remove(tv); if (state != null) restore(tv, state); }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        TextView tv = findTime(p.args[0]);
                        if (tv == null) return;
                        long seconds = ((Number) XposedHelpers.callMethod(p.args[1], "c")).longValue();
                        State state = new State(seconds, tv);
                        VIEWS.put(tv, state);
                        apply(tv, state);
                    } catch (Throwable t) { XposedBridge.log("[fucklark] 消息时间绑定失败: " + t); }
                }
            });
            XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (changing || Looper.myLooper() != Looper.getMainLooper() || !(p.thisObject instanceof TextView)) return;
                    State state = VIEWS.get((TextView) p.thisObject);
                    if (state == null) return;
                    state.visibility = (Integer) p.args[0];
                    if (Config.messageTimeAlways && state.seconds > 0) p.args[0] = View.VISIBLE;
                }
            });
            installed = true;
            XposedBridge.log("[fucklark] 消息时间: 原生绑定时间 hook 已安装");
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] 消息时间功能当前版本未适配: " + t);
        }
    }

    private static TextView findTime(Object holder) {
        if (holder == null) return null;
        for (Field field : holder.getClass().getDeclaredFields()) {
            if (!TextView.class.isAssignableFrom(field.getType())) continue;
            try {
                field.setAccessible(true);
                TextView tv = (TextView) field.get(holder);
                if (tv != null && "time_tv".equals(tv.getResources().getResourceEntryName(tv.getId()))) return tv;
            } catch (Throwable ignored) {}
        }
        return null;
    }
    private static CharSequence nativeText(State state) {
        if (state.original != null && state.original.length() > 0) return state.original;
        return MessageTimeFormat.format(state.seconds, "{relative} HH:mm:ss");
    }
    private static void apply(TextView tv, State state) {
        changing = true;
        try {
            if (Config.messageTimeAlways && state.seconds > 0) {
                tv.setText(Config.messageTimeFormatting ? MessageTimeFormat.format(state.seconds, Config.messageTimePattern)
                        : nativeText(state));
                tv.setVisibility(View.VISIBLE);
            } else {
                tv.setText(Config.messageTimeFormatting && state.seconds > 0
                        ? MessageTimeFormat.format(state.seconds, Config.messageTimePattern) : state.original);
                tv.setVisibility(state.visibility);
            }
        } finally { changing = false; }
    }
    private static void restore(TextView tv, State state) {
        changing = true;
        try { tv.setText(state.original); tv.setVisibility(state.visibility); }
        finally { changing = false; }
    }
    static void onConfigChanged() {
        MAIN.post(new Runnable() {
            @Override public void run() {
                for (TextView tv : new ArrayList<TextView>(VIEWS.keySet())) {
                    State state = VIEWS.get(tv);
                    if (tv != null && state != null) apply(tv, state);
                }
            }
        });
    }
    private MessageTimeDisplay() {}
}
