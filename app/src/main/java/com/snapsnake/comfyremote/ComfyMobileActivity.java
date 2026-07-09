package com.snapsnake.comfyremote;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
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
        installNonDestructiveSkin();
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

    private void installNonDestructiveSkin() {
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
        }, 48);
    }

    private void applySkin(View v, int depth) {
        if (v == null) return;
        if (v instanceof ScrollView || v instanceof FrameLayout) {
            v.setBackgroundColor(bgRoot());
            v.setOverScrollMode(View.OVER_SCROLL_NEVER);
        }
        if (v instanceof LinearLayout && depth <= 3) {
            v.setBackgroundColor(bgRoot());
        }
        if (v instanceof EditText) {
            EditText e = (EditText) v;
            e.setTextColor(Color.WHITE);
            e.setHintTextColor(muted());
            e.setBackground(bg(surface2(), 14, stroke(), 1));
        }
        if (v instanceof Button) {
            Button b = (Button) v;
            String low = String.valueOf(b.getText()).toLowerCase();
            boolean primary = low.contains("run") || low.contains("apply") || low.contains("import") || low.contains("refresh") || low.contains("validate");
            b.setAllCaps(false);
            b.setTextColor(primary ? accent() : Color.WHITE);
            b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            b.setBackground(bg(primary ? Color.rgb(44, 35, 25) : surface2(), 14, primary ? accent() : stroke(), 1));
        }
        if (v instanceof TextView && !(v instanceof Button) && !(v instanceof EditText)) {
            TextView t = (TextView) v;
            float sp = t.getTextSize() / getResources().getDisplayMetrics().scaledDensity;
            if (sp >= 20f) {
                t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                t.setTextColor(Color.WHITE);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applySkin(g.getChildAt(i), depth + 1);
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
    private int bgRoot() { return Color.rgb(18, 18, 19); }
    private int surface2() { return Color.rgb(33, 33, 36); }
    private int stroke() { return Color.rgb(48, 48, 52); }
    private int muted() { return Color.rgb(170, 170, 178); }
    private int accent() { return Color.rgb(218, 143, 60); }
}
