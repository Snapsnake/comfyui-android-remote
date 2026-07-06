package com.snapsnake.comfyremote;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Toast;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class AccessAwareActivity extends EnhancedPolishedActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        overrideGraphButtons((View) getWindow().getDecorView());
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) overrideGraphButtons((View) getWindow().getDecorView());
    }

    private void overrideGraphButtons(View v) {
        if (v instanceof Button) {
            Button b = (Button) v;
            String t = String.valueOf(b.getText()).trim();
            if ("Graph".equalsIgnoreCase(t)) b.setOnClickListener(x -> showGraphNormal());
            if ("Import".equalsIgnoreCase(t)) b.setOnClickListener(x -> importFromShownGraph());
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) overrideGraphButtons(g.getChildAt(i));
        }
    }

    private void showGraphNormal() {
        try {
            callBase("saveUrl");
            String base = String.valueOf(callBase("baseUrl"));
            if (base == null || base.trim().isEmpty()) {
                Toast.makeText(this, "Enter ComfyUI URL first", Toast.LENGTH_SHORT).show();
                return;
            }
            Object pane = baseField("pane");
            Object output = baseField("output");
            Object graph = baseField("graph");
            Object top = baseField("topPanel");
            if (pane instanceof ScrollView) ((ScrollView) pane).setVisibility(View.GONE);
            if (output instanceof WebView) ((WebView) output).setVisibility(View.GONE);
            if (top instanceof View) ((View) top).setVisibility(View.GONE);
            if (graph instanceof WebView) {
                WebView g = (WebView) graph;
                g.setVisibility(View.VISIBLE);
                String cur = g.getUrl();
                if (cur == null || !cur.startsWith(base) || cur.contains("/view")) g.loadUrl(base);
            }
            setStatus("Graph mode. Sign in if asked, then load workflow and Import.");
            callBaseQuiet("applyBars");
        } catch (Exception e) {
            setStatus("Graph failed: " + shortErr(e));
        }
    }

    private void importFromShownGraph() {
        try {
            callBase("saveUrl");
            String base = String.valueOf(callBase("baseUrl"));
            WebView g = (WebView) baseField("graph");
            String cur = g == null ? null : g.getUrl();
            if (cur == null || base == null || base.trim().isEmpty() || !cur.startsWith(base) || cur.contains("/view")) {
                showGraphNormal();
                Toast.makeText(this, "Graph opened. Load workflow, then press Import again.", Toast.LENGTH_SHORT).show();
                return;
            }
            callBase("importFromGraph");
            overrideGraphButtons((View) getWindow().getDecorView());
        } catch (Exception e) {
            setStatus("Import failed: " + shortErr(e));
        }
    }

    private Object baseField(String name) throws Exception {
        Field f = PolishedNodeActivity.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(this);
    }

    private Object callBase(String name) throws Exception {
        Method m = PolishedNodeActivity.class.getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(this);
    }

    private void callBaseQuiet(String name) { try { callBase(name); } catch (Exception ignored) {} }

    private void setStatus(String text) {
        try {
            Object status = baseField("status");
            if (status instanceof android.widget.TextView) ((android.widget.TextView) status).setText(text);
        } catch (Exception ignored) {}
    }

    private String shortErr(Exception e) {
        String s = e.getMessage();
        if (s == null || s.trim().isEmpty()) s = e.getClass().getSimpleName();
        return s.length() > 180 ? s.substring(0, 180) + "…" : s;
    }
}
