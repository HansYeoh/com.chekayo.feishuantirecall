package com.chekayo.feishuantirecall;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;

/** Decorates the existing bubble, without replacing its background or adding a row. */
final class RecallBubbleUi {
    static final String BLOCK = "com.ss.android.lark.chat.chatwindow.biz.component.messagelist.render.block.MessageGlobalBlock";
    private static final Map<View, Binding> bindings = new WeakHashMap<View, Binding>();
    private static Handler handler;
    private static boolean scheduled;
    private static Method outline;

    static Method findOutline(Class<?> layout) throws NoSuchMethodException {
        Method found = null;
        for (Method method : layout.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != Path.class
                    || !java.util.Arrays.equals(method.getParameterTypes(),
                    new Class<?>[]{float.class, float.class, float.class, float.class})) continue;
            if (found != null) throw new NoSuchMethodException("ambiguous bubble outline");
            found = method;
        }
        if (found == null) throw new NoSuchMethodException("bubble outline");
        found.setAccessible(true);
        return found;
    }

    static Method findBind(Class<?> block, Class<?> holder) throws Throwable {
        Method found = null;
        for (Method m : block.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (Modifier.isStatic(m.getModifiers()) || Modifier.isPrivate(m.getModifiers())
                    || m.isBridge() || m.isSynthetic() || m.getReturnType() != void.class
                    || p.length != 2 || p[0] != holder) continue;
            try {
                if (p[1].getMethod("getId").getReturnType() != String.class
                        || p[1].getMethod("getChatId").getReturnType() != String.class) continue;
            } catch (NoSuchMethodException ignored) { continue; }
            if (found != null) throw new IllegalStateException("ambiguous bubble bind");
            found = m;
        }
        if (found == null) throw new NoSuchMethodException("bubble bind");
        return found;
    }
    static void install(ClassLoader cl) throws Throwable {
        outline = findOutline(cl.loadClass("com.larksuite.component.ui.layout.LKUIRoundLinearLayout"));
        Class<?> block = cl.loadClass(BLOCK), holder = cl.loadClass(BLOCK + "$MessageGlobalViewHolder");
        final java.lang.reflect.Field bubble = holder.getField("mBubbleContentContainer");
        Method bind = findBind(block, holder);
        HookRuntime.hook(bind, "recall.bubble.bind", new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                View view = null;
                try {
                    view = (View) bubble.get(chain.getArgs().get(0));
                    unbind(view);
                } catch (Throwable ignored) {}
                Object result = chain.proceed();
                try {
                    Object model = chain.getArgs().get(1);
                    if (view != null && model != null) bind(view, RecallMessages.account(),
                            RecallMessages.value(model, "getChatId"), RecallMessages.value(model, "getId"));
                } catch (Throwable ignored) {}
                return result;
            }
        });
        HotReloadSafety.markExternalCallback("recall.bubble.attach-layout-refresh");
        ModuleLog.log("[fucklark] 撤回气泡: " + bind.getDeclaringClass().getName() + "." + bind.getName());
    }
    static void unbind(View view) {
        if (view == null) return;
        Binding previous = bindings.remove(view);
        if (previous == null) return;
        previous.remove(view);
        previous.dispose();
        view.removeOnLayoutChangeListener(previous);
    }
    static void bind(View view, String uid, String chat, String id) {
        unbind(view);
        if (RecallMessages.key(uid, chat, id) == null) return;
        Binding binding = new Binding(view, uid, chat, id);
        bindings.put(view, binding);
        binding.watch();
        view.addOnLayoutChangeListener(binding);
        binding.refresh(view, uid);
        schedule();
    }
    static void schedule() {
        if (scheduled) return;
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        scheduled = true;
        handler.postDelayed(REFRESH, 500);
    }
    static final Runnable REFRESH = new Runnable() {
        @Override public void run() {
            scheduled = false;
            String uid = RecallMessages.account();
            boolean active = RecallTranslationUi.refresh(uid);
            for (Map.Entry<View, Binding> item : new ArrayList<Map.Entry<View, Binding>>(bindings.entrySet())) {
                View view = item.getKey();
                if (!RecallVisibility.visible(view)) continue;
                active |= Config.antirecall && Config.showRecallHint && AntiRecall.TAMPER != 2;
                item.getValue().refresh(view, uid);
            }
            if (active) schedule();
        }
    };
    static final class Binding extends RecallVisibility implements View.OnLayoutChangeListener {
        final String uid, chat, id;
        Border border;
        CharSequence description, markedDescription;
        Binding(View view, String uid, String chat, String id) { super(view); this.uid = uid; this.chat = chat; this.id = id; }
        void refresh(View view, String activeUid) {
            boolean show = Config.antirecall && Config.showRecallHint && AntiRecall.TAMPER != 2
                    && uid.equals(activeUid) && RecallMessages.marked(uid, chat, id);
            if (!show) { remove(view); return; }
            if (border == null) {
                boolean dark = (view.getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                border = new Border(view, view.getResources().getDisplayMetrics().density, dark);
                view.getOverlay().add(border);
                description = view.getContentDescription();
                markedDescription = (description == null ? "" : description + "，") + "已撤回，原文已保留";
                view.setContentDescription(markedDescription);
            }
            border.setBounds(0, 0, view.getWidth(), view.getHeight());
            border.invalidateSelf(); // grouping can change corner radii without changing size
        }
        void remove(View view) {
            if (border == null) return;
            view.getOverlay().remove(border);
            border = null;
            if (markedDescription.equals(view.getContentDescription())) view.setContentDescription(description);
        }
        @Override public void onViewAttachedToWindow(View view) {
            super.onViewAttachedToWindow(view); refresh(view, RecallMessages.account());
        }
        @Override public void onViewDetachedFromWindow(View view) { super.onViewDetachedFromWindow(view); remove(view); }
        @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
            if (border != null) border.setBounds(0, 0, v.getWidth(), v.getHeight());
        }
    }
    static final class Border extends Drawable {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float density;
        final Path path = new Path();
        final Matrix transform = new Matrix();
        final RectF source = new RectF(), target = new RectF();
        final java.lang.ref.WeakReference<View> host;
        Border(View view, float density, boolean dark) {
            host = new java.lang.ref.WeakReference<View>(view);
            this.density = density;
            paint.setColor(dark ? 0xff707070 : 0xffb0b0b0);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * density);
            paint.setPathEffect(new DashPathEffect(new float[]{4 * density, 3 * density}, 0));
        }
        @Override public void draw(Canvas canvas) {
            View view = host.get();
            float inset = paint.getStrokeWidth() / 2;
            if (view == null || outline == null || getBounds().width() <= 2 * inset
                    || getBounds().height() <= 2 * inset) return;
            try {
                // Ask for precisely the bounds the host itself uses. Fractional
                // inset bounds collide in its integer-keyed global Path cache.
                int width = view.getWidth(), height = view.getHeight();
                Path cached = (Path) outline.invoke(view, 0f, 0f, (float) width, (float) height);
                path.set(cached);
                source.set(0, 0, width, height);
                target.set(inset, inset, width - inset, height - inset);
                transform.setRectToRect(source, target, Matrix.ScaleToFit.FILL);
                path.transform(transform);
                canvas.drawPath(path, paint);
            } catch (Throwable ignored) { /* Unknown shape: do not invent a rectangle. */ }
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
