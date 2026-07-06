package com.snapsnake.comfyremote;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Toast;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

public class AuthenticatedComfyActivity extends EnhancedPolishedActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        installAuthenticatedWebViews();
        overrideGraphButtons((View) getWindow().getDecorView());
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            installAuthenticatedWebViews();
            overrideGraphButtons((View) getWindow().getDecorView());
        }
    }

    private void overrideGraphButtons(View v) {
        if (v instanceof Button) {
            Button b = (Button) v;
            String t = String.valueOf(b.getText()).trim();
            if ("Graph".equalsIgnoreCase(t)) {
                b.setText("Templates");
                b.setOnClickListener(x -> showComfyTemplates());
            }
            if ("Import".equalsIgnoreCase(t)) b.setOnClickListener(x -> importFromComfyUi());
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) overrideGraphButtons(g.getChildAt(i));
        }
    }

    private void installAuthenticatedWebViews() {
        try {
            Object graph = baseField("graph");
            Object output = baseField("output");
            if (graph instanceof WebView) ((WebView) graph).setWebViewClient(new AccessWebClient());
            if (output instanceof WebView) ((WebView) output).setWebViewClient(new AccessWebClient());
        } catch (Exception ignored) {}
    }

    private void showComfyTemplates() {
        try {
            callBase("saveUrl");
            String base = String.valueOf(callBase("baseUrl"));
            if (base == null || base.trim().isEmpty()) {
                Toast.makeText(this, "Enter ComfyUI URL first", Toast.LENGTH_SHORT).show();
                return;
            }
            installAuthenticatedWebViews();
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
                g.loadUrl(base, accessHeaders());
            }
            setStatus("ComfyUI templates. Use Workflow / Browse Templates, pick template, then Import.");
            callBaseQuiet("applyBars");
        } catch (Exception e) {
            setStatus("Templates failed: " + shortErr(e));
        }
    }

    private void importFromComfyUi() {
        try {
            callBase("saveUrl");
            String base = String.valueOf(callBase("baseUrl"));
            WebView g = (WebView) baseField("graph");
            String cur = g == null ? null : g.getUrl();
            if (cur == null || base == null || base.trim().isEmpty() || !cur.startsWith(base) || cur.contains("/view")) {
                showComfyTemplates();
                Toast.makeText(this, "Templates opened. Pick workflow/template, then press Import again.", Toast.LENGTH_SHORT).show();
                return;
            }
            callBase("importFromGraph");
            overrideGraphButtons((View) getWindow().getDecorView());
        } catch (Exception e) {
            setStatus("Import failed: " + shortErr(e));
        }
    }

    private class AccessWebClient extends WebViewClient {
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            try {
                if (!"GET".equalsIgnoreCase(request.getMethod())) return null;
                Uri uri = request.getUrl();
                if (!sameComfyHost(uri)) return null;
                HttpURLConnection c = (HttpURLConnection) new URL(uri.toString()).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(30000);
                Map<String, String> headers = accessHeaders();
                for (String k : headers.keySet()) c.setRequestProperty(k, headers.get(k));
                c.setRequestProperty("User-Agent", "ComfyUI-Android-Remote");
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) {
                    c.disconnect();
                    return null;
                }
                String ct = c.getContentType();
                String mime = "application/octet-stream";
                String enc = "UTF-8";
                if (ct != null && !ct.trim().isEmpty()) {
                    String[] parts = ct.split(";");
                    mime = parts[0].trim();
                    for (String p : parts) {
                        String s = p.trim().toLowerCase();
                        if (s.startsWith("charset=")) enc = p.trim().substring(8);
                    }
                }
                InputStream stream = c.getInputStream();
                return new WebResourceResponse(mime, enc, stream);
            } catch (Exception e) {
                return null;
            }
        }
    }

    private boolean sameComfyHost(Uri uri) {
        try {
            String base = String.valueOf(callBase("baseUrl"));
            Uri baseUri = Uri.parse(base);
            return uri != null
                    && baseUri != null
                    && uri.getHost() != null
                    && uri.getHost().equalsIgnoreCase(baseUri.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> accessHeaders() throws Exception {
        Method m = EnhancedPolishedActivity.class.getDeclaredMethod("accessHeaders");
        m.setAccessible(true);
        return (Map<String, String>) m.invoke(this);
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
