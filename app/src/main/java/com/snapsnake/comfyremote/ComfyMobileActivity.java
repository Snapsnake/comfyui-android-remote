package com.snapsnake.comfyremote;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
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
            v.setBackgroundColor(rgb(0, 0, 0));
            v.setOverScrollMode(View.OVER_SCROLL_NEVER);
        }
        if (v instanceof WebView) {
            v.setBackgroundColor(rgb(0, 0, 0));
        }
        if (v instanceof EditText) {
            EditText e = (EditText) v;
            e.setTextColor(Color.WHITE);
            e.setHintTextColor(rgb(148, 148, 158));
            e.setTextSize(Math.max(14f, e.getTextSize() / getResources().getDisplayMetrics().scaledDensity));
            e.setBackground(bg(rgb(3, 7, 18), 16, rgb(51, 65, 85), 1));
        }
        if (v instanceof Button) {
            Button b = (Button) v;
            String label = String.valueOf(b.getText()).toLowerCase();
            boolean primary = label.contains("run") || label.contains("import") || label.contains("refresh") || label.contains("apply") || label.contains("open graph") || label.contains("choose");
            b.setAllCaps(false);
            b.setTextColor(Color.WHITE);
            b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            b.setBackground(bg(primary ? rgb(23, 37, 84) : rgb(17, 24, 39), 18, primary ? rgb(56, 189, 248) : rgb(51, 65, 85), 1));
        }
        if (v instanceof TextView && !(v instanceof Button) && !(v instanceof EditText)) {
            TextView t = (TextView) v;
            t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            if (t.getTextSize() / getResources().getDisplayMetrics().scaledDensity >= 20f) {
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
    private int rgb(int r, int g, int b) { return Color.rgb(r, g, b); }
}
