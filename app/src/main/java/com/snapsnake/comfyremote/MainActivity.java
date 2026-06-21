package com.snapsnake.comfyremote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private static final String PREFS_NAME = "comfyui_remote_prefs";
    private static final String KEY_URL = "comfyui_url";

    private WebView webView;
    private EditText urlInput;
    private ProgressBar progressBar;
    private TextView statusText;
    private LinearLayout topBar;
    private Button testButton;
    private Button openButton;
    private Button reloadButton;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        configureWebView();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        urlInput.setText(prefs.getString(KEY_URL, "http://desktop-name.tailnet.ts.net:8188"));
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setPadding(dp(8), dp(8), dp(8), dp(6));
        column.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("ComfyUI connection");
        title.setTextSize(18);
        topBar.addView(title, new LinearLayout.LayoutParams(-1, -2));

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setHint("http://desktop-name.tailnet.ts.net:8188");
        urlInput.setTextSize(14);
        topBar.addView(urlInput, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        topBar.addView(row, new LinearLayout.LayoutParams(-1, -2));

        testButton = new Button(this);
        testButton.setText("Test");
        row.addView(testButton, new LinearLayout.LayoutParams(0, dp(44), 1));

        openButton = new Button(this);
        openButton.setText("Open");
        row.addView(openButton, new LinearLayout.LayoutParams(0, dp(44), 1));

        reloadButton = new Button(this);
        reloadButton.setText("Reload");
        row.addView(reloadButton, new LinearLayout.LayoutParams(0, dp(44), 1));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        column.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(3)));

        statusText = new TextView(this);
        statusText.setText("Enter your Tailscale Serve URL, then press Test.");
        statusText.setTextSize(12);
        statusText.setPadding(dp(8), dp(4), dp(8), dp(4));
        column.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        webView = new WebView(this);
        column.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));

        Button hideButton = new Button(this);
        hideButton.setText("Hide");
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(dp(74), dp(48));
        hp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        hp.setMargins(0, 0, dp(10), dp(10));
        root.addView(hideButton, hp);

        testButton.setOnClickListener(v -> testConnection());
        openButton.setOnClickListener(v -> openCurrentUrl());
        reloadButton.setOnClickListener(v -> webView.reload());
        hideButton.setOnClickListener(v -> {
            boolean visible = topBar.getVisibility() == View.VISIBLE;
            topBar.setVisibility(visible ? View.GONE : View.VISIBLE);
            statusText.setVisibility(visible ? View.GONE : View.VISIBLE);
            hideButton.setText(visible ? "Show" : "Hide");
        });

        setContentView(root);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
                statusText.setText("Loading: " + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                statusText.setText("Loaded: " + url);
                injectViewport();
            }
        });
    }

    private void testConnection() {
        String base = getNormalizedUrl();
        if (base.isEmpty()) {
            Toast.makeText(this, "Enter ComfyUI URL", Toast.LENGTH_SHORT).show();
            return;
        }
        saveUrl(base);
        setBusy(true, "Testing /system_stats ...");
        new Thread(() -> {
            String message = checkSystemStats(base);
            mainHandler.post(() -> setBusy(false, message));
        }).start();
    }

    private String checkSystemStats(String base) {
        HttpURLConnection c = null;
        try {
            URL url = new URL(base + "/system_stats");
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) return "Connection OK. Press Open.";
            return "HTTP " + code + ". Check URL, port, or Tailscale Serve.";
        } catch (Exception e) {
            return "Connection failed: " + e.getClass().getSimpleName() + ". Check ComfyUI, Tailscale, and URL.";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void openCurrentUrl() {
        String url = getNormalizedUrl();
        if (url.isEmpty()) {
            Toast.makeText(this, "Enter ComfyUI URL", Toast.LENGTH_SHORT).show();
            return;
        }
        saveUrl(url);
        webView.loadUrl(url);
    }

    private void saveUrl(String url) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_URL, url).apply();
    }

    private String getNormalizedUrl() {
        String raw = urlInput.getText().toString().trim();
        if (raw.isEmpty()) return "";
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) raw = "http://" + raw;
        while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        return raw;
    }

    private void setBusy(boolean busy, String message) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        testButton.setEnabled(!busy);
        openButton.setEnabled(!busy);
        reloadButton.setEnabled(!busy);
        statusText.setText(message);
    }

    private void injectViewport() {
        String script = "(function(){var m=document.querySelector('meta[name=viewport]');if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}m.content='width=device-width,initial-scale=0.65,minimum-scale=0.25,maximum-scale=3,user-scalable=yes';})();";
        webView.evaluateJavascript(script, null);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
