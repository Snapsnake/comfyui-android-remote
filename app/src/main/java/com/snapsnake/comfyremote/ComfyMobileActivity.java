package com.snapsnake.comfyremote;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class ComfyMobileActivity extends TemplateBrowserActivity {
    private boolean skinScheduled = false;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        installGlobalSkin();
        scheduleSkin();
    }

    @Override protected void onResume() {
        super.onResume();
        scheduleSkin();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) scheduleSkin();
    }

    private void installGlobalSkin() {
        View root = getWindow().getDecorView();
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() { scheduleSkin(); }
        });
    }

    private void scheduleSkin() {
        if (skinScheduled) return;
        skinScheduled = true;
        View root = getWindow().getDecorView();
        root.postDelayed(() -> {
            skinScheduled = false;
            applySkin(root, 0);
        }, 32);
    }

    private void applySkin(View v, int depth) {
        if (v == null) return;

        if (v instanceof ScrollView || v instanceof FrameLayout) {
            v.setBackgroundColor(Color.BLACK);
            v.setOverScrollMode(View.OVER_SCROLL_NEVER);
        }
        if (v instanceof WebView) {
            v.setBackgroundColor(Color.BLACK);
        }

        if (v instanceof LinearLayout) {
            LinearLayout layout = (LinearLayout) v;
            String text = subtreeText(layout).toLowerCase();
            boolean looksLikeContentCard = depth >= 4 && (
                    text.contains("workflow") ||
                    text.contains("selected node") ||
                    text.contains("generate") ||
                    text.contains("linked inputs") ||
                    text.contains("fallback") ||
                    text.contains("fields") ||
                    text.contains("lists")
            );
            boolean bottomNav = text.contains("create") && text.contains("nodes") && text.contains("run") && text.contains("output");

            if (looksLikeContentCard) {
                layout.setBackgroundColor(Color.TRANSPARENT);
                layout.setPadding(0, dp(8), 0, dp(8));
                trimMargins(layout, 0, 0, 0, dp(18));
            } else if (bottomNav) {
                layout.setBackgroundColor(Color.BLACK);
                layout.setPadding(dp(16), dp(6), dp(16), dp(10));
                ViewGroup.LayoutParams lp = layout.getLayoutParams();
                if (lp != null && lp.height > dp(64)) {
                    lp.height = dp(64);
                    layout.setLayoutParams(lp);
                }
            } else if (depth <= 3) {
                layout.setBackgroundColor(Color.BLACK);
            }
        }

        if (v instanceof EditText) {
            EditText e = (EditText) v;
            e.setTextColor(Color.WHITE);
            e.setHintTextColor(rgb(148, 148, 158));
            e.setTextSize(Math.max(14f, e.getTextSize() / getResources().getDisplayMetrics().scaledDensity));
            e.setPadding(dp(16), e.getPaddingTop(), dp(16), e.getPaddingBottom());
            e.setBackground(bg(rgb(1, 5, 14), 18, rgb(40, 48, 66), 1));
        }

        if (v instanceof Button) {
            Button b = (Button) v;
            String label = String.valueOf(b.getText()).toLowerCase();
            boolean primary = label.contains("run") || label.contains("import") || label.contains("refresh") || label.contains("apply") || label.contains("choose");
            b.setAllCaps(false);
            b.setTextColor(Color.WHITE);
            b.setTextSize(15);
            b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(10), 0, dp(10), 0);
            b.setBackground(bg(primary ? rgb(10, 20, 48) : rgb(6, 10, 22), 18, primary ? rgb(56, 189, 248) : rgb(45, 54, 74), 1));
            ViewGroup.LayoutParams lp = b.getLayoutParams();
            if (lp != null && lp.height > dp(48)) {
                lp.height = dp(48);
                b.setLayoutParams(lp);
            }
        }

        if (v instanceof TextView && !(v instanceof Button) && !(v instanceof EditText)) {
            TextView t = (TextView) v;
            float sp = t.getTextSize() / getResources().getDisplayMetrics().scaledDensity;
            t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            if (sp >= 20f) {
                t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                t.setTextColor(Color.WHITE);
            } else if (sp <= 14f) {
                t.setTextColor(rgb(148, 148, 158));
            }
        }

        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applySkin(g.getChildAt(i), depth + 1);
        }
    }

    private String subtreeText(View v) {
        if (v instanceof TextView) return String.valueOf(((TextView) v).getText()) + " ";
        if (!(v instanceof ViewGroup)) return "";
        ViewGroup g = (ViewGroup) v;
        StringBuilder sb = new StringBuilder();
        int max = Math.min(g.getChildCount(), 12);
        for (int i = 0; i < max; i++) sb.append(subtreeText(g.getChildAt(i)));
        return sb.toString();
    }

    private void trimMargins(View v, int l, int t, int r, int b) {
        ViewGroup.LayoutParams raw = v.getLayoutParams();
        if (raw instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams) raw;
            p.setMargins(l, t, r, b);
            v.setLayoutParams(p);
        }
    }

    private GradientDrawable bg(int color, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private int rgb(int r, int g, int b) { return Color.rgb(r, g, b); }
}
