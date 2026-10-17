package com.chekayo.feishuantirecall;

import android.view.View;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;

/** Wake a stopped refresh on window/show/attach events without retaining a View tree. */
class RecallVisibility implements View.OnAttachStateChangeListener,
        ViewTreeObserver.OnWindowFocusChangeListener, ViewTreeObserver.OnGlobalLayoutListener {
    private final WeakReference<View> view;
    private WeakReference<ViewTreeObserver> observer = new WeakReference<ViewTreeObserver>(null);
    RecallVisibility(View view) { this.view = new WeakReference<View>(view); }
    static boolean visible(View view) {
        return view != null && view.isAttachedToWindow() && view.getWindowVisibility() == View.VISIBLE && view.isShown();
    }
    void watch() {
        View target = view.get();
        if (target == null) return;
        target.addOnAttachStateChangeListener(this);
        if (target.isAttachedToWindow()) observe(target);
    }
    private void observe(View target) {
        unobserve();
        ViewTreeObserver current = target.getViewTreeObserver();
        if (!current.isAlive()) return;
        // Focus plus global visibility/layout events work on the module's API 29
        // minimum too; OnWindowVisibilityChangeListener was only added in API 34.
        current.addOnWindowFocusChangeListener(this);
        current.addOnGlobalLayoutListener(this);
        observer = new WeakReference<ViewTreeObserver>(current);
    }
    private void unobserve() {
        ViewTreeObserver current = observer.get();
        if (current != null && current.isAlive()) {
            current.removeOnWindowFocusChangeListener(this);
            current.removeOnGlobalLayoutListener(this);
        }
        observer.clear();
    }
    void dispose() {
        unobserve();
        View target = view.get();
        if (target != null) target.removeOnAttachStateChangeListener(this);
    }
    public void onViewAttachedToWindow(View target) { observe(target); wake(); }
    public void onViewDetachedFromWindow(View target) { unobserve(); }
    public void onWindowFocusChanged(boolean focused) { if (focused) wake(); }
    public void onGlobalLayout() { wake(); }
    boolean visible() { return visible(view.get()); }
    private void wake() { if (visible()) RecallBubbleUi.schedule(); }
}
