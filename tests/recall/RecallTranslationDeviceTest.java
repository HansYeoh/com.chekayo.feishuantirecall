package com.chekayo.feishuantirecall;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.animation.RotateAnimation;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import io.github.libxposed.api.XposedInterface;

/** Runs in an isolated app_process with synthetic identities, not in Feishu. */
public final class RecallTranslationDeviceTest {
    private static int checks;
    private static String account = "1001";
    private static void check(boolean result, String name) {
        if (!result) throw new AssertionError(name);
        checks++;
    }
    public static final class Model {
        final String id;
        Model(String id) { this.id = id; }
        public String getId() { return id; }
        public String getChatId() { return "50"; }
    }
    public static final class AnimationView extends FrameLayout {
        final View child;
        boolean running, attached = true;
        int window = View.VISIBLE;
        AnimationView(Context context) {
            super(context);
            child = new View(context);
            addView(child);
        }
        @Override public boolean isAttachedToWindow() { return attached; }
        @Override public int getWindowVisibility() { return window; }
        @Override public boolean isShown() { return getVisibility() == View.VISIBLE; }
        void startLoading() {
            RotateAnimation rotation = new RotateAnimation(0, 360);
            rotation.setDuration(1000);
            rotation.setRepeatCount(-1);
            child.startAnimation(rotation);
            running = true;
        }
        public void a() {
            child.clearAnimation();
            running = false;
        }
    }
    public static final class Holder {
        public AnimationView mTranslateShowOriginLV;
        Holder(AnimationView view) { mTranslateShowOriginLV = view; }
    }
    public static final class Control extends TextView {
        Control(Context context) { super(context); }
        public boolean isAttachedToWindow() { return true; }
        public int getWindowVisibility() { return View.VISIBLE; }
        public boolean isShown() { return getVisibility() == View.VISIBLE; }
    }
    public static final class ThreadHolder {
        public AnimationView translateShowOriginLv;
        public Control mTranslateShowOriginTV, vLayoutTranslate;
        ThreadHolder(Context context) {
            translateShowOriginLv = new AnimationView(context);
            mTranslateShowOriginTV = new Control(context);
            vLayoutTranslate = new Control(context);
        }
    }
    public static final class Bubble extends View {
        boolean attached = true;
        int window = View.VISIBLE;
        boolean canonical = true;
        final java.util.Map<String,Path> cache = new java.util.HashMap<String,Path>();
        Bubble(Context context) { super(context); }
        public boolean isAttachedToWindow() { return attached; }
        public int getWindowVisibility() { return window; }
        public boolean isShown() { return getVisibility() == View.VISIBLE; }
        // Same integer cache contract, with intentionally asymmetric corners.
        public Path outline(float l,float t,float r,float b) {
            canonical &= l == 0 && t == 0 && r == (int)r && b == (int)b;
            String key = (int)(r-l) + ":" + (int)(b-t);
            Path found = cache.get(key);
            if (found == null) {
                found = new Path();
                found.addRoundRect(new RectF(l,t,r,b),new float[]{2,2,12,12,12,12,12,12},Path.Direction.CW);
                cache.put(key,found);
            }
            return found;
        }
    }
    static void resetRefresh() throws Exception {
        Field handler = RecallBubbleUi.class.getDeclaredField("handler"); handler.setAccessible(true);
        android.os.Handler current = (android.os.Handler)handler.get(null);
        if (current != null) current.removeCallbacks(RecallBubbleUi.REFRESH);
        Field scheduled = RecallBubbleUi.class.getDeclaredField("scheduled"); scheduled.setAccessible(true);
        scheduled.setBoolean(null,false);
    }
    static boolean scheduled() throws Exception {
        Field field = RecallBubbleUi.class.getDeclaredField("scheduled"); field.setAccessible(true);
        return field.getBoolean(null);
    }
    static void auditUi(Context context) throws Throwable {
        final ThreadHolder holder = new ThreadHolder(context);
        final AnimationView view = holder.translateShowOriginLv;
        RecallTranslationUi.BindHook bind = new RecallTranslationUi.BindHook(
                ThreadHolder.class.getField("translateShowOriginLv"), ThreadHolder.class.getField("mTranslateShowOriginTV"),
                ThreadHolder.class.getField("vLayoutTranslate"));
        Runnable host = new Runnable() {
            public void run() {
                view.setVisibility(View.VISIBLE); view.startLoading();
                holder.mTranslateShowOriginTV.setVisibility(View.VISIBLE);
                holder.vLayoutTranslate.setVisibility(View.VISIBLE);
                holder.mTranslateShowOriginTV.setText("translating");
                holder.mTranslateShowOriginTV.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {} });
            }
        };
        Chain normal = new Chain(null,new Object[]{holder,new Model("80")},host); bind.intercept(normal);
        check(normal.calls == 1 && view.running,"thread ordinary translation binding preserved");
        Config.antirecall = false;
        check(!RecallTranslationUi.refresh(account) && view.running,"disabled anti-recall stops polling while preserving normal translation");
        Config.antirecall = true;
        RecallMessages.markerStore.add("1001","50","80");
        RecallTranslationUi.refresh(account);
        check(!view.running && holder.mTranslateShowOriginTV.getVisibility() == View.GONE
                && holder.vLayoutTranslate.getVisibility() == View.GONE,"thread spinner, label and translation container cleared without rebind");
        check(!holder.mTranslateShowOriginTV.callOnClick(),"thread label click cleared");
        Chain recycled = new Chain(null,new Object[]{holder,new Model("81")},host); bind.intercept(recycled);
        check(recycled.calls == 1 && view.running && holder.mTranslateShowOriginTV.getVisibility() == View.VISIBLE,
                "thread recycled normal row restores translation");
        RecallMessages.markerStore.add("1001","50","81");
        Chain late = new Chain(view,new Object[0],host); new RecallTranslationUi.StartHook().intercept(late);
        check(late.calls == 0 && !view.running,"thread late callback cannot restart loading");

        Field outline = RecallBubbleUi.class.getDeclaredField("outline"); outline.setAccessible(true);
        outline.set(null,Bubble.class.getMethod("outline",float.class,float.class,float.class,float.class));
        Bubble bubble = new Bubble(context); bubble.layout(0,0,101,51);
        RecallBubbleUi.Border border = new RecallBubbleUi.Border(bubble,1f,false); border.setBounds(0,0,101,51);
        Bitmap bitmap = Bitmap.createBitmap(101,51,Bitmap.Config.ARGB_8888);
        border.draw(new Canvas(bitmap));
        check(bubble.canonical && bubble.cache.containsKey("101:51") && !bubble.cache.containsKey("100:50"),
                "mdpi outline request uses canonical host key, never neighboring size");
        RectF bounds = new RectF(); bubble.cache.get("101:51").computeBounds(bounds,true);
        check(bounds.equals(new RectF(0,0,101,51)),"cached host Path remains unmodified");
        border.path.computeBounds(bounds,true);
        check(bounds.left > 0 && bounds.right < 101,"module-owned Path is inset after copy");
        bubble.outline(0,0,100,50).computeBounds(bounds,true);
        check(bounds.equals(new RectF(0,0,100,50)),"neighboring bubble obtains its own canonical outline");
        bitmap.recycle();
        RecallMessages.markerStore.add("1001","50","90");
        Config.showRecallHint = true;
        RecallBubbleUi.bind(bubble,account,"50","90");
        bubble.window = View.GONE;
        resetRefresh(); RecallBubbleUi.REFRESH.run();
        check(!scheduled(),"hidden but attached activity stops polling");
        Field map = RecallBubbleUi.class.getDeclaredField("bindings"); map.setAccessible(true);
        RecallBubbleUi.Binding binding = (RecallBubbleUi.Binding)((java.util.Map<?,?>)map.get(null)).get(bubble);
        bubble.window = View.VISIBLE; binding.onWindowFocusChanged(true);
        check(scheduled(),"visible window resumes refresh without rebind");
        Config.showRecallHint = false;
        resetRefresh(); RecallBubbleUi.REFRESH.run();
        check(!scheduled() && binding.border == null,"disabling hint clears border and stops bubble-only polling");
        Config.showRecallHint = true; bubble.attached = false;
        resetRefresh(); RecallBubbleUi.REFRESH.run();
        check(!scheduled(),"detached bubble stops polling");
        bubble.attached = true; binding.onViewAttachedToWindow(bubble);
        check(scheduled() && binding.border != null,"reattach restores decoration and refresh");
        RecallBubbleUi.unbind(bubble);
        resetRefresh();
    }
    private static final class Chain implements XposedInterface.Chain {
        final Object receiver;
        final Object[] args;
        final Runnable host;
        int calls;
        Chain(Object receiver, Object[] args, Runnable host) {
            this.receiver = receiver; this.args = args; this.host = host;
        }
        public Executable getExecutable() { return null; }
        public Object getThisObject() { return receiver; }
        public List<Object> getArgs() { return Arrays.asList(args); }
        public Object getArg(int i) { return args[i]; }
        public Object proceed() { calls++; host.run(); return null; }
        public Object proceed(Object[] args) { return proceed(); }
        public Object proceedWith(Object receiver) { throw new UnsupportedOperationException(); }
        public Object proceedWith(Object receiver, Object[] args) { throw new UnsupportedOperationException(); }
    }
    public static void main(String[] args) throws Throwable {
        if (args.length != 1 || !args[0].matches("/data/local/tmp/feishukit-translation-test-[a-f0-9]+"))
            throw new IllegalArgumentException("An isolated test directory is required");
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        RecallMessages.markerStore = new RecallMarkStore(new java.io.File(args[0], "marks"));
        RecallMessages.applicationReady = true;
        RecallMessages.accountReader = new RecallMessages.AccountReader() {
            public String read() { return account; }
        };
        Config.antirecall = true;
        AntiRecall.TAMPER = 1;
        Field stop = RecallTranslationUi.class.getDeclaredField("stopAnimation");
        stop.setAccessible(true);
        stop.set(null, AnimationView.class.getMethod("a"));
        final AnimationView view = new AnimationView(context);
        Holder holder = new Holder(view);
        final int[] clicks = {0};
        Runnable host = new Runnable() {
            public void run() {
                view.setVisibility(View.VISIBLE);
                view.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { clicks[0]++; }
                });
                view.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) { return true; }
                });
                view.startLoading();
            }
        };
        RecallTranslationUi.BindHook bind = new RecallTranslationUi.BindHook(Holder.class.getField("mTranslateShowOriginLV"));
        Chain normal = new Chain(null, new Object[]{holder, new Model("1")}, host);
        bind.intercept(normal);
        check(normal.calls == 1 && view.running && view.getVisibility() == View.VISIBLE, "ordinary bind proceeds once");
        check(view.isClickable() && view.isLongClickable(), "ordinary listeners preserved");

        RecallMessages.markerStore.add("1001", "50", "1");
        check(!RecallTranslationUi.refresh(account), "cleaned translation no longer keeps polling alive");
        check(!view.running && view.child.getAnimation() == null, "recall without rebind stops child animation and running flag");
        check(view.getVisibility() == View.GONE && !view.isClickable() && !view.isLongClickable(), "recalled translation entry removed");
        // Invoke only the listener: performClick also asks Autofill for an app
        // identity, which this isolated shell process intentionally lacks.
        check(!view.callOnClick() && clicks[0] == 0, "old translation click listener cleared");
        Chain start = new Chain(view, new Object[0], host);
        new RecallTranslationUi.StartHook().intercept(start);
        check(start.calls == 0 && !view.running, "late callback cannot restart recalled animation");

        Config.showRecallHint = false;
        Chain recalled = new Chain(null, new Object[]{holder, new Model("1")}, host);
        bind.intercept(recalled);
        check(recalled.calls == 0 && view.getVisibility() == View.GONE, "recalled bind skipped even with border hidden");

        Chain recycled = new Chain(null, new Object[]{holder, new Model("2")}, host);
        bind.intercept(recycled);
        RecallTranslationUi.refresh(account);
        check(recycled.calls == 1 && view.running && view.getVisibility() == View.VISIBLE, "recycled ordinary row restores translation");
        view.callOnClick();
        check(view.isLongClickable() && clicks[0] == 1, "recycled ordinary listeners restored");
        Chain ordinaryStart = new Chain(view, new Object[0], host);
        new RecallTranslationUi.StartHook().intercept(ordinaryStart);
        check(ordinaryStart.calls == 1, "ordinary animation start proceeds once");

        account = "3003";
        Chain switched = new Chain(null, new Object[]{holder, new Model("1")}, host);
        bind.intercept(switched);
        RecallTranslationUi.refresh(account);
        check(switched.calls == 1 && view.running, "same message id in another account is unaffected");
        account = "1001";
        RecallTranslationUi.refresh(account);
        check(view.running, "old-account view cannot inherit active account marker");

        view.attached = false;
        check(!RecallTranslationUi.refresh(account), "detached rows do not keep refresh loop active");
        Chain detach = new Chain(null, new Object[]{holder, new Model("3")}, host);
        bind.intercept(detach);
        RecallMessages.markerStore.add("1001", "50", "3");
        RecallTranslationUi.refresh(account);
        check(view.running, "detached row is not polled");
        Field bindings = RecallTranslationUi.class.getDeclaredField("bindings");
        bindings.setAccessible(true);
        View.OnAttachStateChangeListener attachment = (View.OnAttachStateChangeListener)
                ((java.util.Map<?, ?>) bindings.get(null)).get(view);
        view.attached = true;
        attachment.onViewAttachedToWindow(view);
        check(!view.running && view.getVisibility() == View.GONE, "reattach without bind clears a recall received offscreen");

        final Runnable nativeBind = host;
        Chain concurrent = new Chain(null, new Object[]{holder, new Model("4")}, new Runnable() {
            public void run() {
                nativeBind.run();
                RecallMessages.markerStore.add("1001", "50", "4");
            }
        });
        bind.intercept(concurrent);
        check(concurrent.calls == 1 && !view.running, "recall arriving during host bind is cleaned afterward");

        bind.intercept(new Chain(null, new Object[]{holder, new Model("5")}, host));
        RecallMessages.markerStore.add("1001", "50", "5");
        new android.os.Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                try {
                    check(!view.running && view.getVisibility() == View.GONE, "scheduled shared timer cleans recall without host rebind");
                    auditUi(context);
                    RecallMessages.markerStore.closeForTest();
                    System.out.println("PASS: " + checks + " Android translation lifecycle checks");
                    System.exit(0);
                } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
            }
        }, 750);
        Looper.loop();
    }
}
