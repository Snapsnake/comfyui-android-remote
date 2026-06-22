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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
    private LinearLayout nodeDrawer;
    private LinearLayout nodeList;
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

        buildNodeDrawer(root);
        buildMobileToolbar(root);

        chromeButton = makeMiniButton("⋮");
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(46), dp(46));
        cp.gravity = Gravity.RIGHT | Gravity.BOTTOM;
        cp.setMargins(0, 0, dp(12), dp(78));
        root.addView(chromeButton, cp);

        testButton.setOnClickListener(v -> testConnection());
        openButton.setOnClickListener(v -> openCurrentUrl());
        reloadButton.setOnClickListener(v -> webView.reload());
        chromeButton.setOnClickListener(v -> toggleMobileToolbar());

        setContentView(root);
    }

    private void buildNodeDrawer(FrameLayout root) {
        nodeDrawer = new LinearLayout(this);
        nodeDrawer.setOrientation(LinearLayout.VERTICAL);
        nodeDrawer.setPadding(dp(10), dp(10), dp(10), dp(10));
        nodeDrawer.setVisibility(View.GONE);
        nodeDrawer.setClickable(true);
        nodeDrawer.setBackground(drawerBackground());

        LinearLayout drawerHeader = new LinearLayout(this);
        drawerHeader.setOrientation(LinearLayout.HORIZONTAL);
        drawerHeader.setGravity(Gravity.CENTER_VERTICAL);
        nodeDrawer.addView(drawerHeader, new LinearLayout.LayoutParams(-1, dp(52)));

        TextView drawerTitle = new TextView(this);
        drawerTitle.setText("Nodes");
        drawerTitle.setTextColor(Color.WHITE);
        drawerTitle.setTextSize(20);
        drawerTitle.setGravity(Gravity.CENTER_VERTICAL);
        drawerHeader.addView(drawerTitle, new LinearLayout.LayoutParams(0, -1, 1));

        Button refreshNodes = makeSmallDrawerButton("↻");
        drawerHeader.addView(refreshNodes, new LinearLayout.LayoutParams(dp(48), dp(44)));

        Button closeNodes = makeSmallDrawerButton("×");
        drawerHeader.addView(closeNodes, new LinearLayout.LayoutParams(dp(48), dp(44)));

        TextView drawerHint = new TextView(this);
        drawerHint.setText("Tap a node to expand. Edit widget values and press Apply.");
        drawerHint.setTextColor(Color.rgb(156, 163, 175));
        drawerHint.setTextSize(13);
        drawerHint.setPadding(0, 0, 0, dp(8));
        nodeDrawer.addView(drawerHint, new LinearLayout.LayoutParams(-1, -2));

        ScrollView nodeScroll = new ScrollView(this);
        nodeScroll.setFillViewport(false);
        nodeScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        nodeList = new LinearLayout(this);
        nodeList.setOrientation(LinearLayout.VERTICAL);
        nodeScroll.addView(nodeList, new ScrollView.LayoutParams(-1, -2));
        nodeDrawer.addView(nodeScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        int drawerWidth = Math.min(dp(390), Math.max(dp(300), getResources().getDisplayMetrics().widthPixels - dp(36)));
        FrameLayout.LayoutParams ndp = new FrameLayout.LayoutParams(drawerWidth, -1);
        ndp.gravity = Gravity.LEFT;
        ndp.setMargins(0, 0, 0, 0);
        root.addView(nodeDrawer, ndp);

        refreshNodes.setOnClickListener(v -> refreshNodeDrawer());
        closeNodes.setOnClickListener(v -> hideNodeDrawer());
    }

    private void buildMobileToolbar(FrameLayout root) {
        mobileToolbar = new LinearLayout(this);
        mobileToolbar.setOrientation(LinearLayout.HORIZONTAL);
        mobileToolbar.setGravity(Gravity.CENTER);
        mobileToolbar.setPadding(dp(6), dp(6), dp(6), dp(6));
        mobileToolbar.setVisibility(View.GONE);
        mobileToolbar.setBackground(toolbarBackground());

        Button nodes = makeToolbarButton("Nodes");
        Button run = makeToolbarButton("Run");
        Button fit = makeToolbarButton("Fit");
        Button zoomOut = makeToolbarButton("−");
        Button zoomIn = makeToolbarButton("+");
        Button menu = makeToolbarButton("Menu");

        mobileToolbar.addView(nodes, toolbarButtonParams());
        mobileToolbar.addView(run, toolbarButtonParams());
        mobileToolbar.addView(fit, toolbarButtonParams());
        mobileToolbar.addView(zoomOut, toolbarButtonParams());
        mobileToolbar.addView(zoomIn, toolbarButtonParams());
        mobileToolbar.addView(menu, toolbarButtonParams());

        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-1, dp(64));
        mp.gravity = Gravity.BOTTOM;
        mp.setMargins(dp(8), 0, dp(8), dp(8));
        root.addView(mobileToolbar, mp);

        nodes.setOnClickListener(v -> toggleNodeDrawer());
        run.setOnClickListener(v -> runComfyQueue());
        fit.setOnClickListener(v -> fitComfyCanvas());
        zoomOut.setOnClickListener(v -> webView.zoomOut());
        zoomIn.setOnClickListener(v -> webView.zoomIn());
        menu.setOnClickListener(v -> toggleConnectionPanel());
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

    private Button makeSmallDrawerButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(20);
        b.setTextColor(Color.WHITE);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(buttonBackground(Color.rgb(31, 41, 55), dp(12)));
        return b;
    }

    private Button makeTinyActionButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setBackground(buttonBackground(Color.rgb(37, 99, 235), dp(10)));
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

    private GradientDrawable drawerBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.argb(248, 15, 23, 42));
        d.setStroke(dp(1), Color.argb(220, 71, 85, 105));
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
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
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

    private void toggleNodeDrawer() {
        if (nodeDrawer.getVisibility() == View.VISIBLE) {
            hideNodeDrawer();
        } else {
            nodeDrawer.setVisibility(View.VISIBLE);
            mobileToolbar.setVisibility(View.GONE);
            chromeButton.setVisibility(View.GONE);
            refreshNodeDrawer();
        }
        enterImmersiveMode();
    }

    private void hideNodeDrawer() {
        nodeDrawer.setVisibility(View.GONE);
        mobileToolbar.setVisibility(View.VISIBLE);
        chromeButton.setVisibility(View.VISIBLE);
        enterImmersiveMode();
    }

    private void refreshNodeDrawer() {
        nodeList.removeAllViews();
        addDrawerMessage("Reading workflow nodes...", false);
        injectMobileLayer();
        webView.evaluateJavascript(getNodeListScript(), this::renderNodeDrawer);
    }

    private void renderNodeDrawer(String value) {
        nodeList.removeAllViews();
        try {
            if (value == null || "null".equals(value)) {
                addDrawerMessage("Could not read nodes. Open a workflow first.", true);
                return;
            }
            JSONArray nodes = new JSONArray(value);
            if (nodes.length() == 0) {
                addDrawerMessage("No nodes found in the current graph.", true);
                return;
            }
            for (int i = 0; i < nodes.length(); i++) {
                addNodeCard(nodes.getJSONObject(i), i + 1);
            }
        } catch (JSONException e) {
            addDrawerMessage("Could not parse ComfyUI node list.", true);
        } finally {
            enterImmersiveMode();
        }
    }

    private void addNodeCard(JSONObject node, int index) throws JSONException {
        int id = node.optInt("id", -1);
        String title = clean(node.optString("title", "Untitled"));
        String type = clean(node.optString("type", ""));
        JSONArray widgets = node.optJSONArray("widgets");
        JSONArray inputs = node.optJSONArray("inputs");
        JSONArray outputs = node.optJSONArray("outputs");

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.setBackground(buttonBackground(Color.rgb(30, 41, 59), dp(14)));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.setMargins(0, 0, 0, dp(8));
        nodeList.addView(card, cardParams);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(headerRow, new LinearLayout.LayoutParams(-1, dp(48)));

        Button header = makeNodeHeaderButton(index + ". #" + id + "  " + title);
        headerRow.addView(header, new LinearLayout.LayoutParams(0, -1, 1));

        Button focus = makeTinyActionButton("Focus");
        LinearLayout.LayoutParams focusParams = new LinearLayout.LayoutParams(dp(70), -1);
        focusParams.setMargins(dp(6), 0, 0, 0);
        headerRow.addView(focus, focusParams);

        TextView meta = makeDrawerText(type.isEmpty() ? "type: unknown" : "type: " + type, 13, Color.rgb(148, 163, 184));
        meta.setPadding(dp(4), dp(4), dp(4), dp(8));
        card.addView(meta, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setVisibility(View.GONE);
        details.setPadding(dp(4), dp(6), dp(4), 0);
        card.addView(details, new LinearLayout.LayoutParams(-1, -2));

        if (widgets != null && widgets.length() > 0) {
            details.addView(makeDrawerText("Widgets", 14, Color.WHITE));
            for (int i = 0; i < widgets.length(); i++) {
                JSONObject w = widgets.getJSONObject(i);
                String name = clean(w.optString("name", "widget_" + i));
                String wType = clean(w.optString("type", ""));
                String wValue = w.optString("value", "");
                addWidgetEditor(details, id, i, name, wType, wValue);
            }
        } else {
            details.addView(makeDrawerText("No widgets", 13, Color.rgb(148, 163, 184)));
        }

        if (inputs != null && inputs.length() > 0) {
            details.addView(makeDrawerText("\nInputs", 14, Color.WHITE));
            for (int i = 0; i < inputs.length(); i++) {
                JSONObject input = inputs.getJSONObject(i);
                details.addView(makeDrawerText("• " + clean(input.optString("name", "input")) + " : " + clean(input.optString("type", "")), 13, Color.rgb(203, 213, 225)));
            }
        }

        if (outputs != null && outputs.length() > 0) {
            details.addView(makeDrawerText("\nOutputs", 14, Color.WHITE));
            for (int i = 0; i < outputs.length(); i++) {
                JSONObject output = outputs.getJSONObject(i);
                details.addView(makeDrawerText("• " + clean(output.optString("name", "output")) + " : " + clean(output.optString("type", "")), 13, Color.rgb(203, 213, 225)));
            }
        }

        header.setOnClickListener(v -> {
            details.setVisibility(details.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            enterImmersiveMode();
        });
        focus.setOnClickListener(v -> focusNode(id));
    }

    private void addWidgetEditor(LinearLayout details, int nodeId, int widgetIndex, String name, String type, String value) {
        TextView label = makeDrawerText("• " + name + (type.isEmpty() ? "" : " [" + type + "]"), 13, Color.rgb(203, 213, 225));
        label.setPadding(dp(4), dp(8), dp(4), dp(2));
        details.addView(label, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        details.addView(row, new LinearLayout.LayoutParams(-1, -2));

        EditText valueInput = new EditText(this);
        valueInput.setText(value);
        valueInput.setTextSize(13);
        valueInput.setTextColor(Color.WHITE);
        valueInput.setHintTextColor(Color.rgb(148, 163, 184));
        valueInput.setPadding(dp(8), 0, dp(8), 0);
        valueInput.setSingleLine(value.length() < 60 && !value.contains("\n"));
        if (value.length() >= 60 || value.contains("\n")) {
            valueInput.setMinLines(2);
            valueInput.setMaxLines(5);
        }
        valueInput.setBackground(buttonBackground(Color.rgb(15, 23, 42), dp(10)));
        row.addView(valueInput, new LinearLayout.LayoutParams(0, -2, 1));

        Button apply = makeTinyActionButton("Apply");
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(dp(74), dp(46));
        applyParams.setMargins(dp(6), 0, 0, 0);
        row.addView(apply, applyParams);

        apply.setOnClickListener(v -> applyWidgetValue(nodeId, widgetIndex, valueInput.getText().toString()));
    }

    private Button makeNodeHeaderButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setBackground(buttonBackground(Color.rgb(51, 65, 85), dp(12)));
        return b;
    }

    private TextView makeDrawerText(String text, int size, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(dp(4), dp(3), dp(4), dp(3));
        return t;
    }

    private void addDrawerMessage(String text, boolean error) {
        TextView message = makeDrawerText(text, 15, error ? Color.rgb(248, 113, 113) : Color.rgb(203, 213, 225));
        message.setPadding(dp(6), dp(10), dp(6), dp(10));
        nodeList.addView(message, new LinearLayout.LayoutParams(-1, -2));
    }

    private String clean(String value) {
        if (value == null) return "";
        String s = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (s.length() > 140) s = s.substring(0, 137) + "...";
        return s;
    }

    private String jsString(String value) {
        return JSONObject.quote(value == null ? "" : value);
    }

    private String getNodeListScript() {
        return "(function(){"
                + "var graph=(window.app&&window.app.graph)||(window.graph)||((window.LGraphCanvas&&window.LGraphCanvas.active_canvas)&&window.LGraphCanvas.active_canvas.graph);"
                + "var nodes=(graph&&(graph._nodes||graph.nodes))||[];"
                + "return nodes.map(function(n){"
                + "function text(v){if(v===undefined||v===null)return '';try{return String(v);}catch(e){return '';}}"
                + "function list(items){return (items||[]).map(function(x){return {name:text(x&&x.name),type:text(x&&x.type)};});}"
                + "return {id:n.id||0,title:text(n.title||n.type||'Untitled'),type:text(n.type||''),widgets:(n.widgets||[]).map(function(w){return {name:text(w&&w.name),type:text(w&&w.type),value:text(w&&w.value)};}),inputs:list(n.inputs),outputs:list(n.outputs)};"
                + "});"
                + "})();";
    }

    private void focusNode(int nodeId) {
        String script = "(function(){"
                + "var graph=(window.app&&window.app.graph)||(window.graph)||((window.LGraphCanvas&&window.LGraphCanvas.active_canvas)&&window.LGraphCanvas.active_canvas.graph);"
                + "var canvas=(window.app&&window.app.canvas)||((window.LGraphCanvas&&window.LGraphCanvas.active_canvas)&&window.LGraphCanvas.active_canvas);"
                + "if(!graph||!canvas)return false;"
                + "var n=(graph.getNodeById&&graph.getNodeById(" + nodeId + "))||((graph._nodes||graph.nodes||[]).find(function(x){return x.id==" + nodeId + ";}));"
                + "if(!n)return false;"
                + "try{canvas.selectNode&&canvas.selectNode(n);}catch(e){}"
                + "try{if(canvas.ds&&n.pos){var scale=canvas.ds.scale||1;var size=n.size||[260,120];canvas.ds.offset[0]=(canvas.canvas.width/2)/scale-n.pos[0]-size[0]/2;canvas.ds.offset[1]=(canvas.canvas.height/2)/scale-n.pos[1]-size[1]/2;}}catch(e){}"
                + "try{canvas.setDirty&&canvas.setDirty(true,true);}catch(e){}"
                + "return true;"
                + "})();";
        webView.evaluateJavascript(script, value -> {
            if (!"true".equals(value)) {
                Toast.makeText(this, "Could not focus node", Toast.LENGTH_SHORT).show();
            }
        });
        enterImmersiveMode();
    }

    private void applyWidgetValue(int nodeId, int widgetIndex, String rawValue) {
        String script = "(function(){"
                + "var raw=" + jsString(rawValue) + ";"
                + "var value=raw;"
                + "if(raw==='true')value=true;else if(raw==='false')value=false;else if(raw!==''&&!isNaN(Number(raw)))value=Number(raw);"
                + "var graph=(window.app&&window.app.graph)||(window.graph)||((window.LGraphCanvas&&window.LGraphCanvas.active_canvas)&&window.LGraphCanvas.active_canvas.graph);"
                + "var canvas=(window.app&&window.app.canvas)||((window.LGraphCanvas&&window.LGraphCanvas.active_canvas)&&window.LGraphCanvas.active_canvas);"
                + "if(!graph)return false;"
                + "var n=(graph.getNodeById&&graph.getNodeById(" + nodeId + "))||((graph._nodes||graph.nodes||[]).find(function(x){return x.id==" + nodeId + ";}));"
                + "if(!n||!n.widgets||!n.widgets[" + widgetIndex + "])return false;"
                + "var w=n.widgets[" + widgetIndex + "];"
                + "w.value=value;"
                + "try{if(w.callback)w.callback.call(w,value,canvas,n,n.pos||[0,0],null);}catch(e){}"
                + "try{if(n.onWidgetChanged)n.onWidgetChanged(w.name,value,w);}catch(e){}"
                + "try{if(canvas&&canvas.setDirty)canvas.setDirty(true,true);}catch(e){}"
                + "try{if(graph.setDirtyCanvas)graph.setDirtyCanvas(true,true);}catch(e){}"
                + "return true;"
                + "})();";
        webView.evaluateJavascript(script, value -> {
            if ("true".equals(value)) {
                Toast.makeText(this, "Applied", Toast.LENGTH_SHORT).show();
                refreshNodeDrawer();
            } else {
                Toast.makeText(this, "Could not apply widget value", Toast.LENGTH_SHORT).show();
            }
        });
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
        if (nodeDrawer != null && nodeDrawer.getVisibility() == View.VISIBLE) {
            hideNodeDrawer();
        } else if (topBar.getVisibility() != View.VISIBLE && webView != null && !webView.canGoBack()) {
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
