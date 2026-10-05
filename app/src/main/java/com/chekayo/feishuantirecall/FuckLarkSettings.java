package com.chekayo.feishuantirecall;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * FeishuKit 设置页卡片：在飞书「设置」页注入独立卡片，仅显示「模块设置」。
 * 字号/图标随同页原生 setting item 自适应；点击弹出 {@link SettingsPanel}。
 *
 * 入口两处：
 * 1) 飞书设置 → 卡片「模块设置」；
 * 2) 桌面图标 / LSPosed 启动 → LauncherActivity。
 *
 * 生命周期: 不再实现 legacy 入口接口(原包装类 FuckLarkSettingsHook 已删),
 * 由唯一 modern 入口 FeishuKitModule 在 onPackageReady 调 {@link #install} 分发。
 */
public class FuckLarkSettings {

    static final String PKG_FEISHU = "com.ss.android.lark";
    static final String PKG_LARK = "com.larksuite.suite";
    static volatile String PKG = PKG_FEISHU;
    static final String ROW_TAG = "fucklark_row";
    /** 卡片内唯一可见文案 */
    static final String ROW_TEXT = "模块设置";

    /** 飞书设置页候选类（版本差异时逐个探测）。 */
    static final String[] SETTING_HOOK_TARGETS = {
            "com.ss.android.lark.setting.page.function.SettingPageFragment",
            "com.ss.android.lark.setting.page.SettingPageFragment",
            "com.ss.android.lark.setting.SettingFragment",
    };

    /** 插入位置参考：设置列表末尾/这些条目所在分组之后。 */
    static final String[] ANCHOR_TEXTS = { "关于飞书", "退出登录", "实验室", "内部设置" };

    static boolean isLarkFamily(String pkg) { return PKG_FEISHU.equals(pkg) || PKG_LARK.equals(pkg); }

    /** 完整功能安装。唯一分发点: FeishuKitModule.onPackageReady(已过滤国内/国际/白标)。 */
    public static void install(String packageName, ClassLoader classLoader) {
        if (!isLarkFamily(packageName) && !AntiRecall.isLarkApp(classLoader)) return;
        PKG = packageName;
        DataViews.PKG = PKG;
        DataMigration.install();
        hookSettingPages(classLoader);
    }

    /** 多目标 hook：任一设置页类存在即挂 onResume，保证有「模块设置」入口。 */
    static void hookSettingPages(ClassLoader cl) {
        boolean any = false;
        for (String name : SETTING_HOOK_TARGETS) {
            try {
                XposedHelpers.findAndHookMethod(name, cl, "onResume",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try { inject(param.thisObject); }
                            catch (Throwable t) { XposedBridge.log("[fucklark] inject err " + t); }
                        }
                    });
                XposedBridge.log("[fucklark] 已 hook 设置页 " + name);
                any = true;
            } catch (Throwable ignored) { /* 该版本类名不存在，试下一个 */ }
        }
        if (!any) {
            try {
                XposedHelpers.findAndHookMethod(android.app.Activity.class, "onResume", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            android.app.Activity a = (android.app.Activity) param.thisObject;
                            String n = a.getClass().getName();
                            if (n.contains("setting") || n.contains("Setting")) injectActivity(a);
                        } catch (Throwable ignored) {}
                    }
                });
                XposedBridge.log("[fucklark] 设置页类未命中，已 hook Activity.onResume 兜底");
            } catch (Throwable t) {
                XposedBridge.log("[fucklark] hook 设置入口失败 " + t);
            }
        }
    }

    static void inject(Object fragment) {
        Object v = XposedHelpers.callMethod(fragment, "getView");
        if (!(v instanceof View)) return;
        injectIntoRoot((View) v);
    }

    static void injectActivity(android.app.Activity a) {
        View content = a.findViewById(android.R.id.content);
        if (content != null) injectIntoRoot(content);
    }

    static void injectIntoRoot(View root) {
        if (!(root instanceof ViewGroup)) return;
        if (root.findViewWithTag(ROW_TAG) != null) return;
        final Context ctx = root.getContext();

        // 等设置列表布局完成再插卡片
        ViewGroup list = findSettingsList((ViewGroup) root);
        if (list != null && list.getWidth() <= 0) {
            root.post(new Runnable() {
                @Override public void run() {
                    try { injectIntoRoot(root); } catch (Throwable ignored) {}
                }
            });
            return;
        }
        if (list == null) {
            View sv = findScrollable((ViewGroup) root);
            if (sv instanceof ViewGroup) list = (ViewGroup) sv;
        }
        ViewGroup parent = list;
        if (parent == null && root instanceof ViewGroup) parent = (ViewGroup) root;
        if (parent == null) return;
        // RecyclerView 时不能随便 addView，取其父或自身能 add 的容器
        String pn = parent.getClass().getName();
        if (pn.contains("RecyclerView") && list != null) {
            if (list.getParent() instanceof ViewGroup) parent = (ViewGroup) list.getParent();
        }

        View card = buildAdvancedCard(ctx, findAnySettingItem((ViewGroup) root));
        int at = findInsertIndex(parent);
        try {
            parent.addView(card, Math.min(at, parent.getChildCount()));
            XposedBridge.log("[fucklark] 高级设置卡片已注入 parent=" + parent.getClass().getSimpleName()
                    + " index=" + at + "/" + parent.getChildCount());
        } catch (Throwable t) {
            try {
                parent.addView(card);
                XposedBridge.log("[fucklark] 高级设置卡片追加到末尾 parent=" + parent.getClass().getSimpleName());
            } catch (Throwable t2) {
                XposedBridge.log("[fucklark] 高级设置卡片注入失败 " + t2);
            }
        }
    }

    /**
     * 独立设置卡片：只显示「模块设置」。
     * 字号 / 文字颜色 / 右侧图标优先克隆同页原生 setting item，不同机型自适应。
     */
    static View buildAdvancedCard(final Context ctx, View templateHint) {
        float d = ctx.getResources().getDisplayMetrics().density;
        boolean night = Ui.isNight(ctx);

        // 从页面上任意原生设置行取样式
        float textSizePx = 0;
        int textColor = 0;
        android.graphics.drawable.Drawable icon = null;
        int iconW = 0, iconH = 0;
        View hint = templateHint;
        if (hint instanceof ViewGroup) {
            View labV = findByResourceId((ViewGroup) hint, "setting_item_left_text");
            TextView lab = (labV instanceof TextView) ? (TextView) labV : null;
            if (lab == null) lab = findTextView((ViewGroup) hint, "关于飞书");
            if (lab == null) lab = findFirstTextView(hint);
            if (lab != null) {
                textSizePx = lab.getTextSize();
                try { textColor = lab.getCurrentTextColor(); } catch (Throwable ignored) { }
            }
            View iconV = findByResourceId((ViewGroup) hint, "setting_item_right_icon");
            if (iconV instanceof ImageView) {
                ImageView iv = (ImageView) iconV;
                try {
                    android.graphics.drawable.Drawable dd = iv.getDrawable();
                    if (dd != null) {
                        android.graphics.drawable.Drawable.ConstantState cs = dd.getConstantState();
                        icon = cs != null ? cs.newDrawable() : dd;
                    }
                } catch (Throwable ignored) { }
                iconW = iv.getWidth() > 0 ? iv.getWidth() : 44;
                iconH = iv.getHeight() > 0 ? iv.getHeight() : 44;
            }
        }

        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setTag(ROW_TAG);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int side = Math.round(12 * d);
        int gap = Math.round(16 * d);
        wrap.setPadding(side, gap, side, gap);
        wrap.setBackgroundColor(android.graphics.Color.TRANSPARENT);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        int padH = Math.round(16 * d);
        int padV = Math.round(4 * d);
        card.setPadding(padH, padV, padH, padV);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(night ? 0xFF1C1C1E : 0xFFFFFFFF);
        bg.setCornerRadius(Math.round(12 * d));
        card.setBackground(bg);

        // 仅一行：模块设置
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setPadding(0, Math.round(16 * d), 0, Math.round(16 * d));

        TextView title = new TextView(ctx);
        title.setText(ROW_TEXT);
        if (textSizePx > 0) {
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSizePx);
        } else {
            title.setTextSize(16);
        }
        if (textColor != 0) title.setTextColor(textColor);
        else title.setTextColor(Ui.titleColor(ctx));
        title.setSingleLine(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.rightMargin = Math.round(12 * d);
        row.addView(title, tlp);

        if (icon != null) {
            ImageView chev = new ImageView(ctx);
            chev.setImageDrawable(icon);
            chev.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            int w = iconW > 0 ? iconW : ViewGroup.LayoutParams.WRAP_CONTENT;
            int h = iconH > 0 ? iconH : ViewGroup.LayoutParams.WRAP_CONTENT;
            try {
                android.graphics.drawable.Drawable dd = chev.getDrawable();
                if (dd != null && iconW > 0 && iconH > 0) dd.setBounds(0, 0, iconW, iconH);
            } catch (Throwable ignored) { }
            row.addView(chev, new LinearLayout.LayoutParams(w, h));
        } else {
            TextView chev = new TextView(ctx);
            chev.setText("›");
            if (textSizePx > 0) {
                chev.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSizePx);
            } else {
                chev.setTextSize(16);
            }
            chev.setTextColor(Ui.muteColor(ctx));
            row.addView(chev);
        }

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPanel(ctx); }
        });
        card.addView(row);
        wrap.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    /** 找设置页内容列表：优先 setting container / SettingGroupLayout 的父级。 */
    static ViewGroup findSettingsList(ViewGroup root) {
        try {
            int id = root.getResources().getIdentifier("container", "id", PKG);
            if (id != 0) {
                View v = root.findViewById(id);
                if (v instanceof ViewGroup) return (ViewGroup) v;
            }
        } catch (Throwable ignored) {}
        View rv = findScrollable(root);
        return rv instanceof ViewGroup ? (ViewGroup) rv : null;
    }

    /** 取同页任一原生设置行，供克隆字号/图标。 */
    static View findAnySettingItem(ViewGroup root) {
        String[] labels = { "关于飞书", "实验室", "内部设置", "通用", "通知", "隐私" };
        for (String lab : labels) {
            TextView tv = findTextView(root, lab);
            if (tv == null) continue;
            View cur = tv;
            int hops = 0;
            while (cur != null && hops < 6) {
                if (!(cur.getParent() instanceof View)) break;
                View p = (View) cur.getParent();
                if (p instanceof ViewGroup && (p.isClickable() || p.isLongClickable())) return p;
                cur = p;
                hops++;
            }
            if (tv.getParent() instanceof View) return (View) tv.getParent();
        }
        return null;
    }

    /** 插入位置：最后一个 SettingGroup / 分组之后；找不到则末尾。 */
    static int findInsertIndex(ViewGroup parent) {
        int n = parent.getChildCount();
        int lastGroup = -1;
        int afterAbout = -1;
        for (int i = 0; i < n; i++) {
            View c = parent.getChildAt(i);
            String cn = c.getClass().getName();
            if (cn.contains("SettingGroup") || cn.contains("SettingItem")
                    || cn.contains("setting") || cn.contains("Setting")) {
                lastGroup = i;
            }
            if (c instanceof ViewGroup && findTextView((ViewGroup) c, "关于飞书") != null) {
                afterAbout = i;
            }
            if (c instanceof ViewGroup && findTextView((ViewGroup) c, "退出登录") != null) {
                if (afterAbout < 0) afterAbout = i > 0 ? i - 1 : i;
            }
        }
        if (afterAbout >= 0) return afterAbout + 1;
        if (lastGroup >= 0) return lastGroup + 1;
        return n;
    }

    /**
     * 克隆原生 setting item（如 TextSettingItemView）：
     * 用宿主同一 Class 反射 new，再递归复制 padding / 子控件样式，
     * 因此字号、边距、图标在不同机型/字号设置下自动与原生一致。
     */
    static View cloneNativeSettingItem(View src, Context ctx) {
        try {
            View dest = newInstance(ctx, src.getClass());
            if (dest == null) {
                XposedBridge.log("[fucklark] clone: new instance failed " + src.getClass().getName());
                return null;
            }
            copyViewBase(src, dest);
            if (src instanceof ViewGroup && dest instanceof ViewGroup) {
                copyChildren((ViewGroup) src, (ViewGroup) dest, ctx);
            }
            // 改标题为「模块设置」（替换克隆中仍是锚点的文案）
            if (!applyRowText(dest, ROW_TEXT)) {
                XposedBridge.log("[fucklark] clone: applyRowText failed, fallback");
                return null;
            }
            XposedBridge.log("[fucklark] clone ok " + dest.getClass().getSimpleName());
            return dest;
        } catch (Throwable t) {
            XposedBridge.log("[fucklark] cloneNativeSettingItem err " + t);
            return null;
        }
    }

    static View newInstance(Context ctx, Class<?> cls) {
        Class<?>[][] types = {
                { Context.class },
                { Context.class, android.util.AttributeSet.class },
                { Context.class, android.util.AttributeSet.class, int.class },
                { Context.class, android.util.AttributeSet.class, int.class, int.class },
        };
        Object[][] args = {
                { ctx },
                { ctx, null },
                { ctx, null, 0 },
                { ctx, null, 0, 0 },
        };
        for (int i = 0; i < types.length; i++) {
            try {
                java.lang.reflect.Constructor<?> ctor = cls.getDeclaredConstructor(types[i]);
                ctor.setAccessible(true);
                Object v = ctor.newInstance(args[i]);
                if (v instanceof View) return (View) v;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    static void copyViewBase(View src, View dest) {
        try { dest.setPadding(src.getPaddingLeft(), src.getPaddingTop(), src.getPaddingRight(), src.getPaddingBottom()); } catch (Throwable ignored) {}
        try {
            if (src.getBackground() != null) {
                android.graphics.drawable.Drawable.ConstantState cs = src.getBackground().getConstantState();
                dest.setBackground(cs != null ? cs.newDrawable() : src.getBackground());
            }
        } catch (Throwable ignored) { }
        try { dest.setMinimumWidth(src.getMinimumWidth()); } catch (Throwable ignored) { }
        try { dest.setMinimumHeight(src.getMinimumHeight()); } catch (Throwable ignored) { }
        try { dest.setVisibility(src.getVisibility()); } catch (Throwable ignored) { }
        try { dest.setClickable(true); dest.setFocusable(true); } catch (Throwable ignored) { }
        try {
            dest.setContentDescription(src.getContentDescription());
        } catch (Throwable ignored) { }
    }

    static void copyChildren(ViewGroup src, ViewGroup dest, Context ctx) {
        int n = src.getChildCount();
        for (int i = 0; i < n; i++) {
            View sc = src.getChildAt(i);
            View dc = cloneViewRecursive(sc, ctx);
            if (dc == null) continue;
            try {
                ViewGroup.LayoutParams lp = sc.getLayoutParams();
                dest.addView(dc, lp);
            } catch (Throwable t) {
                try { dest.addView(dc); } catch (Throwable ignored) { }
            }
        }
    }

    static View cloneViewRecursive(View src, Context ctx) {
        View dest = newInstance(ctx, src.getClass());
        if (dest == null) return null;
        copyViewBase(src, dest);
        try { dest.setId(src.getId()); } catch (Throwable ignored) { }
        if (src instanceof TextView && dest instanceof TextView) {
            TextView s = (TextView) src, d = (TextView) dest;
            try { d.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, s.getTextSize()); } catch (Throwable ignored) { }
            try { d.setTextColor(s.getCurrentTextColor()); } catch (Throwable ignored) { }
            try { d.setText(s.getText()); } catch (Throwable ignored) { }
            try { d.setGravity(s.getGravity()); } catch (Throwable ignored) { }
            try { d.setSingleLine(s.isSingleLine()); } catch (Throwable ignored) { }
            try { d.setEllipsize(s.getEllipsize()); } catch (Throwable ignored) { }
        } else if (src instanceof ImageView && dest instanceof ImageView) {
            ImageView s = (ImageView) src, d = (ImageView) dest;
            try {
                android.graphics.drawable.Drawable sd = s.getDrawable();
                if (sd != null) {
                    android.graphics.drawable.Drawable.ConstantState cs = sd.getConstantState();
                    d.setImageDrawable(cs != null ? cs.newDrawable() : sd);
                }
            } catch (Throwable ignored) { }
            try { d.setScaleType(s.getScaleType()); } catch (Throwable ignored) { }
        } else if (src instanceof ViewGroup && dest instanceof ViewGroup) {
            copyChildren((ViewGroup) src, (ViewGroup) dest, ctx);
        }
        return dest;
    }

    /** 在克隆行上写入标题：把所有仍是锚点文案（实验室/内部设置…）的 TextView 换成目标文案。 */
    static boolean applyRowText(View row, String text) {
        java.util.List<TextView> list = new java.util.ArrayList<TextView>();
        collectTextViews(row, list);
        boolean ok = false;
        for (TextView tv : list) {
            CharSequence t = tv.getText();
            if (t == null) continue;
            for (String lab : ANCHOR_TEXTS) {
                if (lab.contentEquals(t)) {
                    tv.setText(text);
                    ok = true;
                    break;
                }
            }
        }
        if (ok) return true;
        // 没有锚点文案时：资源 id / 第一个 TextView / setter
        View idv = null;
        if (row instanceof ViewGroup) idv = findByResourceId((ViewGroup) row, "setting_item_left_text");
        if (idv instanceof TextView) {
            ((TextView) idv).setText(text);
            return true;
        }
        TextView first = findFirstTextView(row);
        if (first != null) {
            first.setText(text);
            return true;
        }
        String[] methods = { "setLeftText", "setTitle", "setText", "setMainText", "setLabel" };
        for (String m : methods) {
            try {
                de.robv.android.xposed.XposedHelpers.callMethod(row, m, text);
                return true;
            } catch (Throwable ignored) { }
        }
        return false;
    }

    static void collectTextViews(View v, java.util.List<TextView> out) {
        if (v instanceof TextView) out.add((TextView) v);
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            int n = vg.getChildCount();
            for (int i = 0; i < n; i++) collectTextViews(vg.getChildAt(i), out);
        }
    }

    static TextView findFirstTextView(View v) {
        if (v instanceof TextView) return (TextView) v;
        if (!(v instanceof ViewGroup)) return null;
        ViewGroup vg = (ViewGroup) v;
        int n = vg.getChildCount();
        for (int i = 0; i < n; i++) {
            TextView r = findFirstTextView(vg.getChildAt(i));
            if (r != null) return r;
        }
        return null;
    }

    /**
     * 找「实验室」等锚点的整行容器（可点击的 setting item）。
     * 从目标 TextView 向上走，找到第一个「可点击且父级是列表」的节点即为该行。
     */
    static View findAnchorItem(ViewGroup root) {
        for (String label : ANCHOR_TEXTS) {
            TextView tv = findTextView(root, label);
            if (tv == null) continue;
            View cur = tv;
            View item = null;
            int hops = 0;
            while (cur != null && hops < 8) {
                if (!(cur.getParent() instanceof View)) break;
                View p = (View) cur.getParent();
                String pn = p.getClass().getName();
                if (pn.contains("RecyclerView") || pn.contains("ListView")) {
                    // cur 就是列表直接子项
                    item = cur;
                    break;
                }
                // 飞书 setting 行：可点击 ViewGroup，内部有 left_text
                if (p instanceof ViewGroup && (p.isClickable() || p.isLongClickable())) {
                    item = p; // 整行
                    // 若父级已是列表容器，直接用这一行
                    View gp = (View) p.getParent();
                    if (gp != null && isListLike(gp)) {
                        item = p;
                        break;
                    }
                }
                cur = p;
                hops++;
            }
            if (item != null) {
                XposedBridge.log("[fucklark] 锚点 " + label + " -> " + item.getClass().getSimpleName());
                return item;
            }
            if (tv.getParent() instanceof View) return (View) tv.getParent();
        }
        return null;
    }

    static boolean isListLike(View v) {
        String n = v.getClass().getName();
        return n.contains("RecyclerView") || n.contains("ListView")
                || n.contains("ScrollView") || n.endsWith(".LinearLayout")
                || n.contains("LinearLayout");
    }

    static TextView findTextView(ViewGroup vg, String exact) {
        int n = vg.getChildCount();
        for (int i = 0; i < n; i++) {
            View c = vg.getChildAt(i);
            if (c instanceof TextView) {
                CharSequence t = ((TextView) c).getText();
                if (t != null && exact.contentEquals(t)) return (TextView) c;
            }
            if (c instanceof ViewGroup) {
                TextView r = findTextView((ViewGroup) c, exact);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 从原生 setting 行克隆几何与图标，保证边距/字号/右侧符号一致。 */
    static final class RowTemplate {
        int padL, padT, padR, padB, minH;
        float textSize;
        int textColor;
        android.graphics.drawable.Drawable icon;
        int iconW = -1, iconH = -1;
    }

    static RowTemplate loadTemplate(View anchorRow) {
        if (!(anchorRow instanceof ViewGroup)) return null;
        RowTemplate t = new RowTemplate();
        // TextSettingItemView 外层 padding 为 0，边距在子控件；按相对坐标计算
        View labV = findByResourceId((ViewGroup) anchorRow, "setting_item_left_text");
        TextView lab = (labV instanceof TextView) ? (TextView) labV : null;
        if (lab == null) lab = findTextView((ViewGroup) anchorRow, "内部设置");
        View iconV = findByResourceId((ViewGroup) anchorRow, "setting_item_right_icon");
        ImageView icon = (iconV instanceof ImageView) ? (ImageView) iconV : null;

        int rowW = anchorRow.getWidth();
        int rowH = anchorRow.getHeight();
        t.minH = rowH > 0 ? rowH : 0;

        if (lab != null) {
            // 相对父行：left/top 即为文字区内边距
            t.padL = Math.max(0, lab.getLeft());
            t.padT = Math.max(0, lab.getTop());
            t.textSize = lab.getTextSize();
            try { t.textColor = lab.getCurrentTextColor(); } catch (Throwable ignored) {}
        }
        if (icon != null) {
            // 量不到时用原生实测像素 44，而不是 44dp（会偏大约 2.75 倍）
            t.iconW = icon.getWidth() > 0 ? icon.getWidth() : 44;
            t.iconH = icon.getHeight() > 0 ? icon.getHeight() : 44;
            try {
                android.graphics.drawable.Drawable d = icon.getDrawable();
                if (d != null) {
                    android.graphics.drawable.Drawable.ConstantState cs = d.getConstantState();
                    t.icon = cs != null ? cs.newDrawable() : d;
                }
            } catch (Throwable ignored) {}
            // 右侧/底部：行宽/行高 - 图标 right/bottom
            if (rowW > 0) t.padR = Math.max(0, rowW - icon.getRight());
            if (rowH > 0) t.padB = Math.max(0, rowH - Math.max(lab != null ? lab.getBottom() : 0, icon.getBottom()));
        } else {
            // 无图标时用父 padding 兜底
            t.padL = anchorRow.getPaddingLeft();
            t.padT = anchorRow.getPaddingTop();
            t.padR = anchorRow.getPaddingRight();
            t.padB = anchorRow.getPaddingBottom();
        }
        // 文字右侧到图标之间不要顶死：原生 left_text 右边界 ~904，图标左 ~948
        if (lab != null && icon != null && rowW > 0) {
            t.padR = Math.max(t.padR, rowW - icon.getRight());
        }
        XposedBridge.log("[fucklark] RowTemplate pad=" + t.padL + "," + t.padT + "," + t.padR + "," + t.padB
                + " row=" + rowW + "x" + rowH + " textSizePx=" + t.textSize + " icon=" + t.iconW + "x" + t.iconH
                + " iconD=" + (t.icon != null));
        return t;
    }

    /** 按 resource-id 后缀在树中查找（无需依赖宿主 R 类）。 */
    static View findByResourceId(ViewGroup root, String entryName) {
        if (root == null || entryName == null) return null;
        try {
            int id = root.getId();
            if (id != 0 && id != View.NO_ID) {
                String name = root.getResources().getResourceEntryName(id);
                if (entryName.equals(name)) return root;
            }
        } catch (Throwable ignored) {}
        int n = root.getChildCount();
        for (int i = 0; i < n; i++) {
            View c = root.getChildAt(i);
            if (c instanceof ViewGroup) {
                View r = findByResourceId((ViewGroup) c, entryName);
                if (r != null) return r;
            } else {
                try {
                    int id = c.getId();
                    if (id != 0 && id != View.NO_ID) {
                        String name = c.getResources().getResourceEntryName(id);
                        if (entryName.equals(name)) return c;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** 飞书风格设置行：字号/边距/右侧图标与原生 setting item 一致。 */
    static View buildNativeRow(Context ctx, RowTemplate tpl, View anchorRow) {
        LinearLayout row = new LinearLayout(ctx);
        row.setTag(ROW_TAG);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackgroundColor(Color.TRANSPARENT);

        float d = ctx.getResources().getDisplayMetrics().density;
        int pl, pt, pr, pb, minH;
        if (tpl != null) {
            pl = tpl.padL; pt = tpl.padT; pr = tpl.padR; pb = tpl.padB;
            minH = tpl.minH;
        } else {
            float d0 = ctx.getResources().getDisplayMetrics().density;
            pl = pt = pr = pb = (int) (16 * d0);
            minH = (int) (54 * d0);
        }
        // 极值兜底：pad 全 0 时按像素推算（本机原生实测：左右 44px、行高约 149px）
        if (pl <= 0 && pt <= 0 && pr <= 0 && pb <= 0) {
            float d1 = ctx.getResources().getDisplayMetrics().density;
            pl = Math.round(16 * d1);   // ≈44px @ 2.75d
            pt = Math.round(16 * d1);
            pr = Math.round(16 * d1);
            pb = Math.round(16 * d1);
            if (minH <= 0) minH = Math.round(54 * d1);
        }
        row.setPadding(pl, pt, pr, pb);
        if (minH > 0) row.setMinimumHeight(minH);

        TextView title = new TextView(ctx);
        title.setText(ROW_TEXT);
        if (tpl != null && tpl.textSize > 0) {
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tpl.textSize);
        } else {
            title.setTextSize(16);
        }
        if (tpl != null && tpl.textColor != 0) {
            title.setTextColor(tpl.textColor);
        } else {
            title.setTextColor(Ui.isNight(ctx) ? 0xFFF0F0F0 : 0xFF1F2329);
        }
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setSingleLine(true);
        // 与原生 left_text 一致：占满左侧，右侧留给图标
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        // 原生 left_text 右边界约 904，图标区在 948+；用 rightMargin 留出图标宽度
        if (tpl != null && tpl.iconW > 0) tlp.rightMargin = Math.max(0, tpl.padR / 2);
        row.addView(title, tlp);

        View chev;
        if (tpl != null && tpl.icon != null) {
            ImageView iv = new ImageView(ctx);
            iv.setImageDrawable(tpl.icon);
            iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            int w = tpl.iconW > 0 ? tpl.iconW : ViewGroup.LayoutParams.WRAP_CONTENT;
            int h = tpl.iconH > 0 ? tpl.iconH : ViewGroup.LayoutParams.WRAP_CONTENT;
            try {
                android.graphics.drawable.Drawable d2 = iv.getDrawable();
                if (d2 != null && tpl.iconW > 0 && tpl.iconH > 0) {
                    d2.setBounds(0, 0, tpl.iconW, tpl.iconH);
                    iv.setImageDrawable(d2);
                }
            } catch (Throwable ignored) {}
            chev = iv;
            row.addView(iv, new LinearLayout.LayoutParams(w, h));
        } else {
            TextView tv = new TextView(ctx);
            tv.setText("›");
            tv.setTextSize(20);
            tv.setTextColor(Ui.isNight(ctx) ? 0xFF8A919C : 0xFFBBBFC4);
            chev = tv;
            row.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showPanel(ctx); }
        });
        return row;
    }

    static View findScrollable(ViewGroup vg) {
        View sv = null;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View c = vg.getChildAt(i);
            String cn = c.getClass().getName();
            if (cn.contains("RecyclerView")) return c;
            if (sv == null && cn.contains("ScrollView")) sv = c;
            if (c instanceof ViewGroup) {
                View r = findScrollable((ViewGroup) c);
                if (r != null && r.getClass().getName().contains("RecyclerView")) return r;
                if (sv == null && r != null) sv = r;
            }
        }
        return sv;
    }

    static void showPanel(final Context ctx) {
        SettingsPanel.show(ctx);
    }

    static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
