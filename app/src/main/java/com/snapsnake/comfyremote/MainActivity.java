package com.snapsnake.comfyremote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
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
    private static final int FILE_CHOOSER_REQUEST = 42;

    private WebView webView;
    private EditText urlInput;
    private ProgressBar progressBar;
    private TextView statusText;
    private LinearLayout topBar;
    private LinearLayout mobileToolbar;
    private Button chromeButton;
    private Button testButton;
    private Button openButton;
    private Button reloadButton;
    private ValueCallback<Uri[]> filePathCallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        enterImmersiveMode();
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
        topBar.setPadding(dp(10), dp(8), dp(10), dp(8));
        topBar.setBackgroundColor(Color.rgb(17, 24, 39));
        column.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("ComfyUI connection");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        topBar.addView(title, new LinearLayout.LayoutParams(-1, -2));

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setHint("http://desktop-name.tailnet.ts.net:8188");
        urlInput.setTextSize(14);
        urlInput.setTextColor(Color.WHITE);
        urlInput.setHintTextColor(Color.rgb(156, 163, 175));
        topBar.addView(urlInput, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        topBar.addView(row, new LinearLayout.LayoutParams(-1, -2));

        testButton = makeButton("Test");
        row.addView(testButton, new LinearLayout.LayoutParams(0, dp(44), 1));

        openButton = makeButton("Open");
        row.addView(openButton, new LinearLayout.LayoutParams(0, dp(44), 1));

        reloadButton = makeButton("Reload");
        row.addView(reloadButton, new LinearLayout.LayoutParams(0, dp(44), 1));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        column.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(3)));

        statusText = new TextView(this);
        statusText.setText("Enter your ComfyUI URL, then press Test.");
        statusText.setTextColor(Color.rgb(209, 213, 219));
        statusText.setBackgroundColor(Color.rgb(17, 24, 39));
        statusText.setTextSize(12);
        statusText.setPadding(dp(10), dp(4), dp(10), dp(6));
        column.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        webView = new WebView(this);
        column.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));

        mobileToolbar = new LinearLayout(this);
        mobileToolbar.setOrientation(LinearLayout.HORIZONTAL);
        mobileToolbar.setGravity(Gravity.CENTER);
        mobileToolbar.setPadding(dp(6), dp(6), dp(6), dp(6));
        mobileToolbar.setVisibility(View.GONE);
        mobileToolbar.setBackground(toolbarBackground());

        Button run = makeToolbarButton("Run");
        Button reload = makeToolbarButton("Reload");
        Button fit = makeToolbarButton("Fit");
        Button zoomOut = makeToolbarButton("−");
        Button zoomIn = makeToolbarButton("+");
        Button menu = makeToolbarButton("Menu");

        mobileToolbar.addView(run, toolbarButtonParams());
        mobileToolbar.addView(reload, toolbarButtonParams());
        mobileToolbar.addView(fit, toolbarButtonParams());
        mobileToolbar.addView(zoomOut, toolbarButtonParams());
        mobileToolbar.addView(zoomIn, toolbarButtonParams());
        mobileToolbar.addView(menu, toolbarButtonParams());

        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-1, dp(64));
        mp.gravity = Gravity.BOTTOM;
        mp.setMargins(dp(8), 0, dp(8), dp(8));
        root.addView(mobileToolbar, mp);

        chromeButton = makeMiniButton("⋮");
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(46), dp(46));
        cp.gravity = Gravity.RIGHT | Gravity.BOTTOM;
        cp.setMargins(0, 0, dp(12), dp(78));
        root.addView(chromeButton, cp);

        testButton.setOnClickListener(v -> testConnection());
        openButton.setOnClickListener(v -> openCurrentUrl());
        reloadButton.setOnClickListener(v -> webView.reload());

        run.setOnClickListener(v -> runComfyQueue());
        reload.setOnClickListener(v -> webView.reload());
        fit.setOnClickListener(v -> fitComfyCanvas());
        zoomOut.setOnClickListener(v -> webView.zoomOut());
        zoomIn.setOnClickListener(v -> webView.zoomIn());
        menu.setOnClickListener(v -> toggleConnectionPanel());
        chromeButton.setOnClickListener(v -> toggleMobileToolbar());

        setContentView(root);
    }

    private LinearLayout.LayoutParams toolbarButtonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        b.setBackground(buttonBackground(Color.rgb(37, 99, 235), dp(10)));
        return b;
    }

    private Button makeToolbarButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setMinHeight(dp(44));
        b.setMinimumHeight(dp(44));
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setBackground(buttonBackground(Color.rgb(31, 41, 55), dp(14)));
        return b;
    }

    private Button makeMiniButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(22);
        b.setTextColor(Color.WHITE);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(buttonBackground(Color.argb(220, 31, 41, 55), dp(23)));
        return b;
    }

    private GradientDrawable buttonBackground(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private GradientDrawable toolbarBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.argb(235, 17, 24, 39));
        d.setCornerRadius(dp(22));
        d.setStroke(dp(1), Color.argb(180, 75, 85, 99));
        return d;
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
        s.setTextZoom(100);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setScrollbarFadingEnabled(true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams
            ) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;

                Intent intent;
                try {
                    intent = fileChooserParams.createIntent();
                } catch (Exception e) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                }

                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    Toast.makeText(MainActivity.this, "No file picker available", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });

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
                injectMobileLayer();
                mainHandler.postDelayed(() -> injectMobileLayer(), 800);
                mainHandler.postDelayed(() -> injectMobileLayer(), 2200);
                enterImmersiveMode();
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
            return "HTTP " + code + ". Check URL, port, or tunnel.";
        } catch (Exception e) {
            return "Connection failed: " + e.getClass().getSimpleName() + ". Check ComfyUI and URL.";
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
        showWorkspaceMode();
        webView.loadUrl(url);
    }

    private void showWorkspaceMode() {
        topBar.setVisibility(View.GONE);
        statusText.setVisibility(View.GONE);
        mobileToolbar.setVisibility(View.VISIBLE);
        chromeButton.setVisibility(View.VISIBLE);
        enterImmersiveMode();
    }

    private void toggleConnectionPanel() {
        boolean visible = topBar.getVisibility() == View.VISIBLE;
        topBar.setVisibility(visible ? View.GONE : View.VISIBLE);
        statusText.setVisibility(visible ? View.GONE : View.VISIBLE);
        enterImmersiveMode();
    }

    private void toggleMobileToolbar() {
        boolean visible = mobileToolbar.getVisibility() == View.VISIBLE;
        mobileToolbar.setVisibility(visible ? View.GONE : View.VISIBLE);
        chromeButton.setText(visible ? "☰" : "⋮");
        enterImmersiveMode();
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

    private void injectMobileLayer() {
        String script =
                "(function(){"
                        + "var head=document.head||document.documentElement;"
                        + "var meta=document.querySelector('meta[name=viewport]');"
                        + "if(!meta){meta=document.createElement('meta');meta.name='viewport';head.appendChild(meta);}"
                        + "meta.content='width=device-width,initial-scale=1,minimum-scale=0.35,maximum-scale=3,user-scalable=yes,viewport-fit=cover';"
                        + "document.documentElement.classList.add('comfy-android-remote');"
                        + "if(document.body){document.body.classList.add('comfy-android-remote');}"
                        + "if(!document.getElementById('comfy-android-remote-style')){"
                        + "var style=document.createElement('style');"
                        + "style.id='comfy-android-remote-style';"
                        + "style.textContent="
                        + "'html.comfy-android-remote,html.comfy-android-remote body{overscroll-behavior:none!important;touch-action:manipulation!important;-webkit-tap-highlight-color:transparent!important;}'"
                        + "+'html.comfy-android-remote button,html.comfy-android-remote input,html.comfy-android-remote select,html.comfy-android-remote textarea,html.comfy-android-remote [role=button]{min-height:36px!important;font-size:14px!important;}'"
                        + "+'html.comfy-android-remote textarea,html.comfy-android-remote input{line-height:1.35!important;}'"
                        + "+'html.comfy-android-remote .litecontextmenu,html.comfy-android-remote .p-menu,html.comfy-android-remote .p-dialog{font-size:15px!important;}'"
                        + "+'@media(max-width:820px){html.comfy-android-remote button{padding-left:10px!important;padding-right:10px!important;}html.comfy-android-remote canvas{touch-action:none!important;}}';"
                        + "head.appendChild(style);"
                        + "}"
                        + "window.ComfyAndroidRemote={"
                        + "clickByText:function(words){var nodes=[].slice.call(document.querySelectorAll('button,[role=button],.p-button'));for(var i=0;i<nodes.length;i++){var n=nodes[i];var t=((n.innerText||n.textContent||'')+' '+(n.title||'')+' '+(n.getAttribute('aria-label')||'')).toLowerCase();for(var j=0;j<words.length;j++){if(t.indexOf(words[j])>=0){n.click();return true;}}}return false;},"
                        + "run:function(){return this.clickByText(['run','queue','generate']);},"
                        + "fit:function(){var ok=this.clickByText(['fit','zoom to fit','reset view']);try{window.dispatchEvent(new KeyboardEvent('keydown',{key:'f',code:'KeyF',bubbles:true}));}catch(e){}return ok;}"
                        + "};"
                        + "})();";
        webView.evaluateJavascript(script, null);
    }

    private void runComfyQueue() {
        injectMobileLayer();
        webView.evaluateJavascript(
                "(function(){return window.ComfyAndroidRemote&&window.ComfyAndroidRemote.run?window.ComfyAndroidRemote.run():false;})();",
                value -> {
                    if (!"true".equals(value)) {
                        Toast.makeText(this, "Run button not found in ComfyUI", Toast.LENGTH_SHORT).show();
                    }
                }
        );
        enterImmersiveMode();
    }

    private void fitComfyCanvas() {
        injectMobileLayer();
        webView.evaluateJavascript(
                "(function(){return window.ComfyAndroidRemote&&window.ComfyAndroidRemote.fit?window.ComfyAndroidRemote.fit():false;})();",
                null
        );
        enterImmersiveMode();
    }

    private void enterImmersiveMode() {
        Window window = getWindow();
        View decor = window.getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersiveMode();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (filePathCallback == null) return;
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
            enterImmersiveMode();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (topBar.getVisibility() != View.VISIBLE && webView != null && !webView.canGoBack()) {
            toggleConnectionPanel();
        } else if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
