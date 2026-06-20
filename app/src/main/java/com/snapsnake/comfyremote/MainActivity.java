package com.snapsnake.comfyremote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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

public class MainActivity extends Activity {
    private static final String PREFS_NAME = "comfyui_remote_prefs";
    private static final String KEY_URL = "comfyui_url";

    private WebView webView;
    private EditText urlInput;
    private ProgressBar progressBar;
    private TextView statusText;
    private LinearLayout topBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        configureWebView();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        urlInput.setText(prefs.getString(KEY_URL, "http://100.x.x.x:8188"));
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(8), dp(8), dp(8));
        column.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setHint("http://100.x.x.x:8188");
        urlInput.setTextSize(13);
        topBar.addView(urlInput, new LinearLayout.LayoutParams(0, dp(44), 1));

        Button openButton = new Button(this);
        openButton.setText("Open");
        topBar.addView(openButton, new LinearLayout.LayoutParams(dp(72), dp(44)));

        Button reloadButton = new Button(this);
        reloadButton.setText("Reload");
        topBar.addView(reloadButton, new LinearLayout.LayoutParams(dp(84), dp(44)));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        column.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(3)));

        statusText = new TextView(this);
        statusText.setText("Enter ComfyUI URL and press Open.");
        statusText.setTextSize(12);
        statusText.setPadding(dp(8), dp(4), dp(8), dp(4));
        column.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        webView = new WebView(this);
        column.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));

        Button hideButton = new Button(this);
        hideButton.setText("Hide");
        FrameLayout.LayoutParams hideParams = new FrameLayout.LayoutParams(dp(74), dp(48));
        hideParams.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        hideParams.setMargins(0, 0, dp(10), dp(10));
        root.addView(hideButton, hideParams);

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
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

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

    private void injectViewport() {
        String script = "(function(){var m=document.querySelector('meta[name=viewport]');if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}m.content='width=device-width,initial-scale=0.65,minimum-scale=0.25,maximum-scale=3,user-scalable=yes';})();";
        webView.evaluateJavascript(script, null);
    }

    private void openCurrentUrl() {
        String url = getNormalizedUrl();
        if (url.isEmpty()) {
            Toast.makeText(this, "Enter ComfyUI URL", Toast.LENGTH_SHORT).show();
            return;
        }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_URL, url).apply();
        webView.loadUrl(url);
    }

    private String getNormalizedUrl() {
        String raw = urlInput.getText().toString().trim();
        if (raw.isEmpty()) return "";
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) raw = "http://" + raw;
        return raw;
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
