package com.snapsnake.comfyremote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
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
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final String PREFS_NAME = "comfyui_remote_prefs";
    private static final String KEY_URL = "comfyui_url";
    private static final String KEY_OPEN_PARAMS_DEFAULT = "open_params_default";
    private static final String KEY_SHOW_ONLY_EDITABLE = "show_only_editable";
    private static final String KEY_HIDE_TECHNICAL = "hide_technical_fields";
    private static final String KEY_COMPACT_CARDS = "compact_cards";
    private static final String KEY_CONFIRM_RUN = "confirm_before_run";
    private static final String KEY_AUTO_REFRESH_AFTER_APPLY = "auto_refresh_after_apply";
    private static final String KEY_AGGRESSIVE_GRAPH_RETURN = "aggressive_graph_return";
    private static final String KEY_LARGE_UI = "large_ui";
    private static final String KEY_HUMAN_LABELS = "human_readable_labels";
    private static final String KEY_ONE_APPLY_PER_CARD = "one_apply_per_card";
    private static final String KEY_FULL_SCREEN_PARAMS = "full_screen_params";
    private static final int FILE_CHOOSER_REQUEST = 42;

    private WebView webView;
    private EditText urlInput;
    private ProgressBar progressBar;
    private TextView statusText;
    private LinearLayout topBar;
    private LinearLayout mobileToolbar;
    private LinearLayout nodeDrawer;
    private LinearLayout nodeList;
    private LinearLayout menuDrawer;
    private Button chromeButton;
    private Button testButton;
    private Button openButton;
    private Button reloadButton;
    private ValueCallback<Uri[]> filePathCallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static class WidgetField {
        final int widgetIndex;
        final EditText input;
        WidgetField(int widgetIndex, EditText input) {
            this.widgetIndex = widgetIndex;
            this.input = input;
        }
    }

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
        openButton = makeButton("Open");
        reloadButton = makeButton("Reload");
        row.addView(testButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        row.addView(openButton, new LinearLayout.LayoutParams(0, dp(44), 1));
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

        buildParamsDrawer(root);
        buildMenuDrawer(root);
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

    private void buildParamsDrawer(FrameLayout root) {
        nodeDrawer = new LinearLayout(this);
        nodeDrawer.setOrientation(LinearLayout.VERTICAL);
        nodeDrawer.setPadding(dp(14), dp(12), dp(14), dp(12));
        nodeDrawer.setVisibility(View.GONE);
        nodeDrawer.setClickable(true);
        nodeDrawer.setBackground(drawerBackground());

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        nodeDrawer.addView(header, new LinearLayout.LayoutParams(-1, dp(56)));

        TextView title = new TextView(this);
        title.setText("Params");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, -1, 1));

        Button refresh = makeSmallDrawerButton("↻");
        Button close = makeSmallDrawerButton("×");
        header.addView(refresh, new LinearLayout.LayoutParams(dp(56), dp(50)));
        header.addView(close, new LinearLayout.LayoutParams(dp(56), dp(50)));

        TextView hint = new TextView(this);
        hint.setText("Edit node parameters. Use Choose image for Load Image nodes.");
        hint.setTextColor(Color.rgb(156, 163, 175));
        hint.setTextSize(15);
        hint.setPadding(0, 0, 0, dp(10));
        nodeDrawer.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        nodeList = new LinearLayout(this);
        nodeList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(nodeList, new ScrollView.LayoutParams(-1, -2));
        nodeDrawer.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        root.addView(nodeDrawer, new FrameLayout.LayoutParams(-1, -1, Gravity.LEFT));
        refresh.setOnClickListener(v -> refreshNodeDrawer());
        close.setOnClickListener(v -> hideNodeDrawer());
    }

    private void buildMenuDrawer(FrameLayout root) {
        menuDrawer = new LinearLayout(this);
        menuDrawer.setOrientation(LinearLayout.VERTICAL);
        menuDrawer.setPadding(dp(14), dp(12), dp(14), dp(12));
        menuDrawer.setVisibility(View.GONE);
        menuDrawer.setClickable(true);
        menuDrawer.setBackground(drawerBackground());

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        menuDrawer.addView(header, new LinearLayout.LayoutParams(-1, dp(56)));

        TextView title = new TextView(this);
        title.setText("Menu");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, -1, 1));

        Button close = makeSmallDrawerButton("×");
        header.addView(close, new LinearLayout.LayoutParams(dp(56), dp(50)));
        close.setOnClickListener(v -> hideMenuDrawer());

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        menuDrawer.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        content.addView(makeSectionTitle("Connection"));
        content.addView(makeMenuAction("Test connection", () -> testConnection()));
        content.addView(makeMenuAction("Reload ComfyUI", () -> webView.reload()));
        content.addView(makeMenuAction("Show URL panel", () -> {
            hideMenuDrawer();
            topBar.setVisibility(View.VISIBLE);
            statusText.setVisibility(View.VISIBLE);
        }));

        content.addView(makeSectionTitle("Workflow"));
        content.addView(makeMenuAction("Preview latest output", () -> openLatestOutput()));
        content.addView(makeMenuAction("Open full ComfyUI graph", () -> {
            hideMenuDrawer();
            returnToGraph();
        }));
        content.addView(makeMenuAction("Fit canvas", () -> fitComfyCanvas()));

        content.addView(makeSectionTitle("Settings"));
        content.addView(makeSettingCheckBox(KEY_LARGE_UI, "Large UI for Pixel 8a", true));
        content.addView(makeSettingCheckBox(KEY_HUMAN_LABELS, "Human-readable labels", true));
        content.addView(makeSettingCheckBox(KEY_ONE_APPLY_PER_CARD, "One Apply button per card", true));
        content.addView(makeSettingCheckBox(KEY_FULL_SCREEN_PARAMS, "Full-screen Params", true));
        content.addView(makeSettingCheckBox(KEY_OPEN_PARAMS_DEFAULT, "Open Params by default", false));
        content.addView(makeSettingCheckBox(KEY_SHOW_ONLY_EDITABLE, "Show only editable nodes", true));
        content.addView(makeSettingCheckBox(KEY_HIDE_TECHNICAL, "Hide technical fields", true));
        content.addView(makeSettingCheckBox(KEY_COMPACT_CARDS, "Compact cards", false));
        content.addView(makeSettingCheckBox(KEY_CONFIRM_RUN, "Confirm before Run", true));
        content.addView(makeSettingCheckBox(KEY_AUTO_REFRESH_AFTER_APPLY, "Auto refresh after Apply", true));
        content.addView(makeSettingCheckBox(KEY_AGGRESSIVE_GRAPH_RETURN, "Aggressive Graph return", true));

        content.addView(makeSectionTitle("Debug"));
        content.addView(makeMenuAction("Clear WebView cache", () -> {
            webView.clearCache(true);
            webView.clearHistory();
            Toast.makeText(this, "WebView cache cleared", Toast.LENGTH_SHORT).show();
        }));

        root.addView(menuDrawer, new FrameLayout.LayoutParams(-1, -1, Gravity.RIGHT));
    }

    private void buildMobileToolbar(FrameLayout root) {
        mobileToolbar = new LinearLayout(this);
        mobileToolbar.setOrientation(LinearLayout.HORIZONTAL);
        mobileToolbar.setGravity(Gravity.CENTER);
        mobileToolbar.setPadding(dp(6), dp(6), dp(6), dp(6));
        mobileToolbar.setVisibility(View.GONE);
        mobileToolbar.setBackground(toolbarBackground());

        Button params = makeToolbarButton("Params");
        Button graph = makeToolbarButton("Graph");
        Button run = makeToolbarButton("Run");
        Button output = makeToolbarButton("Output");
        Button menu = makeToolbarButton("Menu");
        mobileToolbar.addView(params, toolbarButtonParams());
        mobileToolbar.addView(graph, toolbarButtonParams());
        mobileToolbar.addView(run, toolbarButtonParams());
        mobileToolbar.addView(output, toolbarButtonParams());
        mobileToolbar.addView(menu, toolbarButtonParams());

        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-1, dp(68));
        mp.gravity = Gravity.BOTTOM;
        mp.setMargins(dp(8), 0, dp(8), dp(8));
        root.addView(mobileToolbar, mp);

        params.setOnClickListener(v -> toggleNodeDrawer());
        graph.setOnClickListener(v -> returnToGraph());
        run.setOnClickListener(v -> runComfyQueue());
        output.setOnClickListener(v -> openLatestOutput());
        menu.setOnClickListener(v -> toggleMenuDrawer());
    }

    private LinearLayout.LayoutParams toolbarButtonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(52), 1);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private TextView makeSectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(18);
        t.setPadding(dp(2), dp(18), dp(2), dp(8));
        return t;
    }

    private View makeMenuAction(String text, Runnable action) {
        Button b = makeButton(text);
        b.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        b.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(54));
        p.setMargins(0, 0, 0, dp(10));
        b.setLayoutParams(p);
        b.setOnClickListener(v -> { action.run(); enterImmersiveMode(); });
        return b;
    }

    private CheckBox makeSettingCheckBox(String key, String text, boolean defaultValue) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextColor(Color.rgb(226, 232, 240));
        box.setTextSize(16);
        box.setMinHeight(dp(48));
        box.setPadding(dp(2), dp(6), dp(2), dp(6));
        box.setChecked(getBoolSetting(key, defaultValue));
        box.setOnCheckedChangeListener((buttonView, isChecked) -> {
            setBoolSetting(key, isChecked);
            if (nodeDrawer != null && nodeDrawer.getVisibility() == View.VISIBLE) refreshNodeDrawer();
        });
        return box;
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(Color.WHITE);
        b.setSingleLine(true);
        b.setIncludeFontPadding(false);
        b.setBackground(buttonBackground(Color.rgb(37, 99, 235), dp(12)));
        return b;
    }

    private Button makeToolbarButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setSingleLine(true);
        b.setIncludeFontPadding(false);
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        b.setPadding(dp(3), 0, dp(3), 0);
        b.setBackground(buttonBackground(Color.rgb(31, 41, 55), dp(16)));
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
        b.setTextSize(22);
        b.setTextColor(Color.WHITE);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(buttonBackground(Color.rgb(31, 41, 55), dp(14)));
        return b;
    }

    private Button makeTinyActionButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setSingleLine(true);
        b.setIncludeFontPadding(false);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setBackground(buttonBackground(Color.rgb(37, 99, 235), dp(14)));
        return b;
    }

    private Button makeSecondaryActionButton(String text) {
        Button b = makeTinyActionButton(text);
        b.setBackground(buttonBackground(Color.rgb(51, 65, 85), dp(14)));
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
        d.setColor(Color.argb(240, 17, 24, 39));
        d.setCornerRadius(dp(24));
        d.setStroke(dp(1), Color.argb(180, 75, 85, 99));
        return d;
    }

    private GradientDrawable drawerBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.argb(252, 15, 23, 42));
        d.setStroke(dp(1), Color.argb(220, 71, 105));
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
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent intent;
                try { intent = params.createIntent(); }
                catch (Exception e) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                }
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
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
                if (getBoolSetting(KEY_OPEN_PARAMS_DEFAULT, false) && topBar.getVisibility() != View.VISIBLE) {
                    mainHandler.postDelayed(() -> showNodeDrawer(), 800);
                }
                enterImmersiveMode();
            }
        });
    }

    private void testConnection() {
        String base = getNormalizedUrl();
        if (base.isEmpty()) { Toast.makeText(this, "Enter ComfyUI URL", Toast.LENGTH_SHORT).show(); return; }
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
        } finally { if (c != null) c.disconnect(); }
    }

    private void openCurrentUrl() {
        String url = getNormalizedUrl();
        if (url.isEmpty()) { Toast.makeText(this, "Enter ComfyUI URL", Toast.LENGTH_SHORT).show(); return; }
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
        if (nodeDrawer.getVisibility() == View.VISIBLE) hideNodeDrawer(); else showNodeDrawer();
        enterImmersiveMode();
    }

    private void showNodeDrawer() {
        hideMenuDrawerIfOpen();
        updateDrawerWidth(nodeDrawer);
        nodeDrawer.setVisibility(View.VISIBLE);
        mobileToolbar.setVisibility(View.GONE);
        chromeButton.setVisibility(View.GONE);
        refreshNodeDrawer();
        enterImmersiveMode();
    }

    private void hideNodeDrawer() {
        nodeDrawer.setVisibility(View.GONE);
        mobileToolbar.setVisibility(View.VISIBLE);
        chromeButton.setVisibility(View.VISIBLE);
        enterImmersiveMode();
    }

    private void toggleMenuDrawer() {
        if (menuDrawer.getVisibility() == View.VISIBLE) hideMenuDrawer();
        else {
            hideNodeDrawerIfOpen();
            updateDrawerWidth(menuDrawer);
            menuDrawer.setVisibility(View.VISIBLE);
            mobileToolbar.setVisibility(View.GONE);
            chromeButton.setVisibility(View.GONE);
        }
        enterImmersiveMode();
    }

    private void hideMenuDrawer() {
        menuDrawer.setVisibility(View.GONE);
        mobileToolbar.setVisibility(View.VISIBLE);
        chromeButton.setVisibility(View.VISIBLE);
        enterImmersiveMode();
    }

    private void hideMenuDrawerIfOpen() {
        if (menuDrawer != null && menuDrawer.getVisibility() == View.VISIBLE) {
            menuDrawer.setVisibility(View.GONE);
            mobileToolbar.setVisibility(View.VISIBLE);
            chromeButton.setVisibility(View.VISIBLE);
        }
    }

    private void updateDrawerWidth(View drawer) {
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) drawer.getLayoutParams();
        p.width = getBoolSetting(KEY_FULL_SCREEN_PARAMS, true) ? -1 : Math.min(dp(420), Math.max(dp(320), getResources().getDisplayMetrics().widthPixels - dp(24)));
        drawer.setLayoutParams(p);
    }

    private void refreshNodeDrawer() {
        nodeList.removeAllViews();
        addDrawerMessage("Reading workflow parameters...", false);
        injectMobileLayer();
        webView.evaluateJavascript(getNodeListScript(), this::renderNodeDrawer);
    }

    private void renderNodeDrawer(String value) {
        nodeList.removeAllViews();
        try {
            if (value == null || "null".equals(value)) { addDrawerMessage("Could not read workflow. Open a workflow first.", true); return; }
            JSONArray nodes = new JSONArray(value);
            int shown = 0;
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject node = nodes.getJSONObject(i);
                JSONArray widgets = node.optJSONArray("widgets");
                boolean editable = widgets != null && widgets.length() > 0;
                if (getBoolSetting(KEY_SHOW_ONLY_EDITABLE, true) && !editable) continue;
                addNodeCard(node, shown + 1);
                shown++;
            }
            if (shown == 0) addDrawerMessage("No editable parameters found. Disable 'Show only editable nodes' in Menu → Settings to inspect all nodes.", true);
        } catch (JSONException e) { addDrawerMessage("Could not parse ComfyUI node list.", true); }
        finally { enterImmersiveMode(); }
    }

    private void addNodeCard(JSONObject node, int index) throws JSONException {
        int id = node.optInt("id", -1);
        String title = clean(node.optString("title", "Untitled"));
        String type = clean(node.optString("type", ""));
        JSONArray widgets = node.optJSONArray("widgets");
        JSONArray inputs = node.optJSONArray("inputs");
        JSONArray outputs = node.optJSONArray("outputs");
        boolean compact = getBoolSetting(KEY_COMPACT_CARDS, false);
        boolean hideTechnical = getBoolSetting(KEY_HIDE_TECHNICAL, true);
        boolean oneApply = getBoolSetting(KEY_ONE_APPLY_PER_CARD, true);
        List<WidgetField> fields = new ArrayList<>();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), compact ? dp(10) : dp(14), dp(12), compact ? dp(10) : dp(14));
        card.setBackground(buttonBackground(Color.rgb(30, 41, 59), dp(18)));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.setMargins(0, 0, 0, compact ? dp(10) : dp(14));
        nodeList.addView(card, cardParams);

        Button header = makeNodeHeaderButton(index + ". " + title);
        card.addView(header, new LinearLayout.LayoutParams(-1, compact ? dp(52) : dp(60)));
        if (!hideTechnical) card.addView(makeDrawerText("#" + id + (type.isEmpty() ? "" : " · type: " + type), 14, Color.rgb(148, 163, 184)));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setVisibility(View.GONE);
        details.setPadding(dp(2), compact ? dp(8) : dp(10), dp(2), 0);
        card.addView(details, new LinearLayout.LayoutParams(-1, -2));

        if (widgets != null && widgets.length() > 0) {
            if (isLoadImageNode(title, type)) addLoadImageActions(details, id, widgets);
            if (isOutputNode(title, type)) addOutputActions(details);
            details.addView(makeDrawerText("Editable fields", 16, Color.WHITE));
            for (int i = 0; i < widgets.length(); i++) {
                JSONObject w = widgets.getJSONObject(i);
                String rawName = clean(w.optString("name", "widget_" + i));
                String labelName = displayWidgetName(title, type, rawName, i);
                String wType = clean(w.optString("type", ""));
                String wValue = w.optString("value", "");
                if (isPseudoUploadWidget(title, type, rawName, labelName)) continue;
                fields.add(addWidgetEditor(details, id, i, labelName, rawName, wType, wValue, !oneApply));
            }
            if (oneApply && !fields.isEmpty()) {
                Button applyCard = makeTinyActionButton("Apply card");
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(56));
                p.setMargins(0, dp(14), 0, 0);
                details.addView(applyCard, p);
                applyCard.setOnClickListener(v -> applyWidgetValues(id, fields));
            }
        } else details.addView(makeDrawerText("No editable widgets", 15, Color.rgb(148, 163, 184)));

        if (!hideTechnical) {
            if (inputs != null && inputs.length() > 0) {
                details.addView(makeDrawerText("\nInputs", 15, Color.WHITE));
                for (int i = 0; i < inputs.length(); i++) {
                    JSONObject input = inputs.getJSONObject(i);
                    details.addView(makeDrawerText("• " + clean(input.optString("name", "input")) + " : " + clean(input.optString("type", "")), 14, Color.rgb(203, 213, 225)));
                }
            }
            if (outputs != null && outputs.length() > 0) {
                details.addView(makeDrawerText("\nOutputs", 15, Color.WHITE));
                for (int i = 0; i < outputs.length(); i++) {
                    JSONObject output = outputs.getJSONObject(i);
                    details.addView(makeDrawerText("• " + clean(output.optString("name", "output")) + " : " + clean(output.optString("type", "")), 14, Color.rgb(203, 213, 225)));
                }
            }
        }
        header.setOnClickListener(v -> { details.setVisibility(details.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); enterImmersiveMode(); });
    }

    private void addLoadImageActions(LinearLayout details, int nodeId, JSONArray widgets) {
        int imageWidgetIndex = findImageWidgetIndex(widgets);
        if (imageWidgetIndex < 0) return;
        TextView title = makeDrawerText("Image input", 16, Color.WHITE);
        title.setPadding(dp(4), dp(8), dp(4), dp(4));
        details.addView(title, new LinearLayout.LayoutParams(-1, -2));
        Button choose = makeTinyActionButton("Choose image from phone");
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(58));
        p.setMargins(0, 0, 0, dp(10));
        details.addView(choose, p);
        choose.setOnClickListener(v -> triggerLoadImagePicker(nodeId, imageWidgetIndex));
    }

    private void addOutputActions(LinearLayout details) {
        TextView title = makeDrawerText("Output", 16, Color.WHITE);
        title.setPadding(dp(4), dp(8), dp(4), dp(4));
        details.addView(title, new LinearLayout.LayoutParams(-1, -2));
        Button preview = makeSecondaryActionButton("Preview latest output");
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(58));
        p.setMargins(0, 0, 0, dp(10));
        details.addView(preview, p);
        preview.setOnClickListener(v -> openLatestOutput());
    }

    private int findImageWidgetIndex(JSONArray widgets) {
        for (int i = 0; i < widgets.length(); i++) {
            JSONObject w = widgets.optJSONObject(i);
            if (w == null) continue;
            String name = w.optString("name", "").toLowerCase();
            if (name.equals("image") || name.contains("image")) return i;
        }
        return widgets.length() > 0 ? 0 : -1;
    }

    private boolean isLoadImageNode(String title, String type) {
        String s = ((title == null ? "" : title) + " " + (type == null ? "" : type)).toLowerCase();
        return s.contains("load image") || s.contains("loadimage");
    }

    private boolean isOutputNode(String title, String type) {
        String s = ((title == null ? "" : title) + " " + (type == null ? "" : type)).toLowerCase();
        return s.contains("save video") || s.contains("savevideo") || s.contains("save image") || s.contains("saveimage") || s.contains("preview");
    }

    private boolean isPseudoUploadWidget(String title, String type, String rawName, String labelName) {
        if (!isLoadImageNode(title, type)) return false;
        String n = ((rawName == null ? "" : rawName) + " " + (labelName == null ? "" : labelName)).toLowerCase();
        return n.equals("upload upload") || n.equals("upload") || n.contains("upload button");
    }

    private WidgetField addWidgetEditor(LinearLayout details, int nodeId, int widgetIndex, String name, String rawName, String type, String value, boolean inlineApply) {
        boolean compact = getBoolSetting(KEY_COMPACT_CARDS, false);
        TextView label = makeDrawerText("• " + name, 16, Color.rgb(226, 232, 240));
        label.setPadding(dp(4), compact ? dp(8) : dp(12), dp(4), dp(4));
        details.addView(label, new LinearLayout.LayoutParams(-1, -2));
        if (!getBoolSetting(KEY_HIDE_TECHNICAL, true) && !name.equals(rawName)) {
            details.addView(makeDrawerText("raw: " + rawName + (type.isEmpty() ? "" : " [" + type + "]"), 12, Color.rgb(148, 163, 184)));
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        details.addView(row, new LinearLayout.LayoutParams(-1, -2));
        EditText valueInput = new EditText(this);
        valueInput.setText(value);
        valueInput.setTextSize(17);
        valueInput.setTextColor(Color.WHITE);
        valueInput.setHintTextColor(Color.rgb(148, 163, 184));
        valueInput.setPadding(dp(12), 0, dp(12), 0);
        valueInput.setMinHeight(compact ? dp(52) : dp(58));
        valueInput.setSelectAllOnFocus(false);
        valueInput.setSingleLine(shouldUseSingleLine(name, value));
        if (!shouldUseSingleLine(name, value)) {
            valueInput.setGravity(Gravity.TOP | Gravity.LEFT);
            valueInput.setMinLines(3);
            valueInput.setMaxLines(8);
        }
        valueInput.setInputType(isNumericField(name, type, value) ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED : (valueInput.isSingleLine() ? InputType.TYPE_CLASS_TEXT : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        valueInput.setBackground(buttonBackground(Color.rgb(15, 23, 42), dp(14)));
        row.addView(valueInput, new LinearLayout.LayoutParams(0, -2, 1));
        if (inlineApply) {
            Button apply = makeTinyActionButton("Apply");
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(92), compact ? dp(52) : dp(58));
            ap.setMargins(dp(8), 0, 0, 0);
            row.addView(apply, ap);
            apply.setOnClickListener(v -> applyWidgetValue(nodeId, widgetIndex, valueInput.getText().toString()));
        }
        return new WidgetField(widgetIndex, valueInput);
    }

    private String displayWidgetName(String nodeTitle, String nodeType, String rawName, int index) {
        if (!getBoolSetting(KEY_HUMAN_LABELS, true)) return rawName;
        String n = rawName == null ? "" : rawName;
        String title = (nodeTitle == null ? "" : nodeTitle).toLowerCase();
        String type = (nodeType == null ? "" : nodeType).toLowerCase();
        if (n.equals("noise_seed") || n.equals("seed")) return "Seed";
        if (n.equals("ckpt_name")) return "Checkpoint";
        if (n.equals("lora_name")) return "LoRA";
        if (n.equals("text_encoder")) return "Text encoder";
        if (n.equals("sampler_name")) return "Sampler";
        if (n.equals("scheduler")) return "Scheduler";
        if (n.equals("steps")) return "Steps";
        if (n.equals("cfg")) return "CFG";
        if (n.equals("width")) return "Width";
        if (n.equals("height")) return "Height";
        if (n.equals("batch_size")) return "Batch size";
        if (n.equals("filename_prefix")) return "Filename prefix";
        if (n.equals("format")) return "Format";
        if (n.equals("codec")) return "Codec";
        if (n.equals("image")) return "Image";
        if (n.equals("upload")) return "Upload";
        if ((title.contains("image to video") || type.contains("ltx") || title.contains("ltx")) && n.startsWith("value")) {
            if (index == 0) return "Prompt";
            if (index == 1) return "Prompt enhance";
            if (index == 2) return "Width";
            if (index == 3) return "Height";
            if (index == 4) return "Duration / seconds";
            if (index == 5) return "Steps";
        }
        if (n.startsWith("value_")) return "Parameter " + n.substring("value_".length());
        if (n.equals("value")) return "Value";
        return prettifyName(n);
    }

    private String prettifyName(String raw) {
        if (raw == null || raw.isEmpty()) return "Parameter";
        String[] parts = raw.replace('_', ' ').split(" ");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.length() == 0 ? raw : out.toString();
    }

    private boolean shouldUseSingleLine(String name, String value) {
        String lower = (name == null ? "" : name).toLowerCase();
        if (lower.contains("prompt") || lower.contains("text")) return false;
        return value == null || (value.length() < 48 && !value.contains("\n"));
    }

    private boolean isNumericField(String name, String type, String value) {
        String lower = ((name == null ? "" : name) + " " + (type == null ? "" : type)).toLowerCase();
        if (lower.contains("width") || lower.contains("height") || lower.contains("step") || lower.contains("seed") || lower.contains("cfg") || lower.contains("duration") || lower.contains("batch") || lower.contains("fps") || lower.contains("frame")) return true;
        if (value == null || value.isEmpty()) return false;
        try { Double.parseDouble(value); return true; } catch (Exception ignored) { return false; }
    }

    private Button makeNodeHeaderButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        b.setTextSize(18);
        b.setTextColor(Color.WHITE);
        b.setSingleLine(true);
        b.setIncludeFontPadding(false);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setBackground(buttonBackground(Color.rgb(51, 65, 85), dp(16)));
        return b;
    }

    private TextView makeDrawerText(String text, int size, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(dp(4), dp(4), dp(4), dp(4));
        return t;
    }

    private void addDrawerMessage(String text, boolean error) {
        TextView message = makeDrawerText(text, 16, error ? Color.rgb(248, 113, 113) : Color.rgb(203, 213, 225));
        message.setPadding(dp(6), dp(12), dp(6), dp(12));
        nodeList.addView(message, new LinearLayout.LayoutParams(-1, -2));
    }

    private String clean(String value) {
        if (value == null) return "";
        String s = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (s.length() > 160) s = s.substring(0, 157) + "...";
        return s;
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

    private void applyWidgetValue(int nodeId, int widgetIndex, String rawValue) {
        JSONArray arr = new JSONArray();
        try { JSONObject item = new JSONObject(); item.put("index", widgetIndex); item.put("value", rawValue); arr.put(item); } catch (JSONException ignored) {}
        applyWidgetValuesJson(nodeId, arr);
    }

    private void applyWidgetValues(int nodeId, List<WidgetField> fields) {
        JSONArray arr = new JSONArray();
        for (WidgetField field : fields) {
            try { JSONObject item = new JSONObject(); item.put("index", field.widgetIndex); item.put("value", field.input.getText().toString()); arr.put(item); } catch (JSONException ignored) {}
        }
        applyWidgetValuesJson(nodeId, arr);
    }

    private void applyWidgetValuesJson(int nodeId, JSONArray values) {
        String script = "(function(){"
                + "var values=" + values.toString() + ";"
                + "function convert(raw){if(raw==='true')return true;if(raw==='false')return false;if(raw!==''&&!isNaN(Number(raw)))return Number(raw);return raw;}"
                + "var graph=(window.app&&window.app.graph)||(window.graph)||((window.LGraphCanvas&&window.LGraphCanvas.active_canvas)&&window.LGraphCanvas.active_canvas.graph);"
                + "var canvas=(window.app&&window.app.canvas)||((window.LGraphCanvas&&window.LGraphCanvas.active_canvas)&&window.LGraphCanvas.active_canvas);"
                + "if(!graph)return false;"
                + "var n=(graph.getNodeById&&graph.getNodeById(" + nodeId + "))||((graph._nodes||graph.nodes||[]).find(function(x){return x.id==" + nodeId + ";}));"
                + "if(!n||!n.widgets)return false;"
                + "for(var i=0;i<values.length;i++){var item=values[i];var idx=item.index;var raw=item.value;var value=convert(raw);if(!n.widgets[idx])continue;var w=n.widgets[idx];w.value=value;try{if(w.callback)w.callback.call(w,value,canvas,n,n.pos||[0,0],null);}catch(e){}try{if(n.onWidgetChanged)n.onWidgetChanged(w.name,value,w);}catch(e){}}"
                + "try{if(canvas&&canvas.setDirty)canvas.setDirty(true,true);}catch(e){}try{if(graph.setDirtyCanvas)graph.setDirtyCanvas(true,true);}catch(e){}return true;})();";
        webView.evaluateJavascript(script, value -> {
            if ("true".equals(value)) {
                Toast.makeText(this, "Applied", Toast.LENGTH_SHORT).show();
                if (getBoolSetting(KEY_AUTO_REFRESH_AFTER_APPLY, true)) refreshNodeDrawer();
            } else Toast.makeText(this, "Could not apply widget value", Toast.LENGTH_SHORT).show();
        });
        enterImmersiveMode();
    }

    private void triggerLoadImagePicker(int nodeId, int imageWidgetIndex) {
        injectMobileLayer();
        Toast.makeText(this, "Opening image picker...", Toast.LENGTH_SHORT).show();
        String script = "(function(){try{"
                + "var graph=(window.app&&window.app.graph)||(window.graph)||((window.LGraphCanvas&&window.LGraphCanvas.active_canvas)&&window.LGraphCanvas.active_canvas.graph);"
                + "var canvas=(window.app&&window.app.canvas)||((window.LGraphCanvas&&window.LGraphCanvas.active_canvas)&&window.LGraphCanvas.active_canvas);"
                + "if(!graph)return false;"
                + "var n=(graph.getNodeById&&graph.getNodeById(" + nodeId + "))||((graph._nodes||graph.nodes||[]).find(function(x){return x.id==" + nodeId + ";}));"
                + "if(!n||!n.widgets||!n.widgets[" + imageWidgetIndex + "])return false;"
                + "var old=document.getElementById('comfy-android-remote-file-input');if(old)old.remove();"
                + "var input=document.createElement('input');input.id='comfy-android-remote-file-input';input.type='file';input.accept='image/*';input.style.position='fixed';input.style.left='-10000px';input.style.top='-10000px';"
                + "input.onchange=async function(){var file=input.files&&input.files[0];if(!file)return;try{var fd=new FormData();fd.append('image',file,file.name);fd.append('type','input');fd.append('overwrite','true');var r=await fetch('/upload/image',{method:'POST',body:fd});var j=await r.json();var name=j.name||file.name;var w=n.widgets[" + imageWidgetIndex + "];w.value=name;try{if(w.callback)w.callback.call(w,name,canvas,n,n.pos||[0,0],null);}catch(e){}try{if(n.onWidgetChanged)n.onWidgetChanged(w.name,name,w);}catch(e){}try{if(canvas&&canvas.setDirty)canvas.setDirty(true,true);}catch(e){}try{if(graph.setDirtyCanvas)graph.setDirtyCanvas(true,true);}catch(e){}alert('Image selected: '+name);}catch(e){alert('Image upload failed: '+e.message);}};"
                + "document.body.appendChild(input);input.click();return true;"
                + "}catch(e){return false;}})();";
        webView.evaluateJavascript(script, value -> {
            if (!"true".equals(value)) Toast.makeText(this, "Could not open image picker", Toast.LENGTH_SHORT).show();
        });
    }

    private void openLatestOutput() {
        Toast.makeText(this, "Trying latest output...", Toast.LENGTH_SHORT).show();
        injectMobileLayer();
        String script = "(async function(){function enc(v){return encodeURIComponent(v||'');}function first(arr){return arr&&arr.length?arr[0]:null;}try{var h=await fetch('/history').then(function(r){return r.json();});var found=null;Object.keys(h).forEach(function(pid){var outs=(h[pid]&&h[pid].outputs)||{};Object.keys(outs).forEach(function(nid){var o=outs[nid]||{};found=first(o.videos)||first(o.gifs)||first(o.images)||found;});});if(!found||!found.filename)return false;location.href='/view?filename='+enc(found.filename)+'&type='+enc(found.type||'output')+'&subfolder='+enc(found.subfolder||'');return true;}catch(e){return false;}})();";
        webView.evaluateJavascript(script, value -> { if ("false".equals(value)) Toast.makeText(this, "No output found yet", Toast.LENGTH_SHORT).show(); });
    }

    private void returnToGraph() {
        hideNodeDrawerIfOpen(); hideMenuDrawerIfOpen(); injectMobileLayer();
        boolean aggressive = getBoolSetting(KEY_AGGRESSIVE_GRAPH_RETURN, true);
        String overlayPart = aggressive ? "try{[].slice.call(document.querySelectorAll('.p-dialog-mask,.p-component-overlay,.p-dialog,.p-sidebar,.p-drawer,.p-overlaypanel')).forEach(function(el){el.style.display='none';});}catch(e){}" : "";
        String script = "(function(){function esc(){try{document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',bubbles:true,cancelable:true}));window.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',bubbles:true,cancelable:true}));}catch(e){}}esc();esc();function info(el){return ((el.innerText||el.textContent||'')+' '+(el.title||'')+' '+(el.getAttribute('aria-label')||'')).toLowerCase();}function clickByWords(words){var els=[].slice.call(document.querySelectorAll('button,[role=button],a,.p-tab,.p-button'));for(var i=0;i<els.length;i++){var t=info(els[i]);for(var j=0;j<words.length;j++){if(t.indexOf(words[j])>=0){els[i].click();return true;}}}return false;}var closed=0;[].slice.call(document.querySelectorAll('button,[role=button]')).forEach(function(el){var t=info(el);if(t==='×'||t.indexOf('close')>=0||t.indexOf('dismiss')>=0){try{el.click();closed++;}catch(e){}}});var clicked=clickByWords(['graph','workflow','editor']);" + overlayPart + "var canvas=(window.app&&window.app.canvas)||((window.LGraphCanvas&&window.LGraphCanvas.active_canvas)&&window.LGraphCanvas.active_canvas);try{if(canvas&&canvas.canvas){canvas.canvas.focus();canvas.setDirty&&canvas.setDirty(true,true);}}catch(e){}return clicked||closed>0||!!canvas;})();";
        webView.evaluateJavascript(script, value -> { if (!"true".equals(value)) Toast.makeText(this, "Could not return to graph", Toast.LENGTH_SHORT).show(); });
        enterImmersiveMode();
    }

    private void hideNodeDrawerIfOpen() {
        if (nodeDrawer != null && nodeDrawer.getVisibility() == View.VISIBLE) {
            nodeDrawer.setVisibility(View.GONE);
            mobileToolbar.setVisibility(View.VISIBLE);
            chromeButton.setVisibility(View.VISIBLE);
        }
    }

    private void saveUrl(String url) { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_URL, url).apply(); }

    private String getNormalizedUrl() {
        String raw = urlInput.getText().toString().trim();
        if (raw.isEmpty()) return "";
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) raw = "http://" + raw;
        while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        return raw;
    }

    private boolean getBoolSetting(String key, boolean defaultValue) { return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(key, defaultValue); }
    private void setBoolSetting(String key, boolean value) { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply(); }

    private void setBusy(boolean busy, String message) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        testButton.setEnabled(!busy); openButton.setEnabled(!busy); reloadButton.setEnabled(!busy); statusText.setText(message);
    }

    private void injectMobileLayer() {
        String script = "(function(){var head=document.head||document.documentElement;var meta=document.querySelector('meta[name=viewport]');if(!meta){meta=document.createElement('meta');meta.name='viewport';head.appendChild(meta);}meta.content='width=device-width,initial-scale=1,minimum-scale=0.35,maximum-scale=3,user-scalable=yes,viewport-fit=cover';document.documentElement.classList.add('comfy-android-remote');if(document.body){document.body.classList.add('comfy-android-remote');}if(!document.getElementById('comfy-android-remote-style')){var style=document.createElement('style');style.id='comfy-android-remote-style';style.textContent='html.comfy-android-remote,html.comfy-android-remote body{overscroll-behavior:none!important;touch-action:manipulation!important;-webkit-tap-highlight-color:transparent!important;}html.comfy-android-remote button,html.comfy-android-remote input,html.comfy-android-remote select,html.comfy-android-remote textarea,html.comfy-android-remote [role=button]{min-height:40px!important;font-size:15px!important;}html.comfy-android-remote textarea,html.comfy-android-remote input{line-height:1.35!important;}html.comfy-android-remote .litecontextmenu,html.comfy-android-remote .p-menu,html.comfy-android-remote .p-dialog{font-size:15px!important;}@media(max-width:820px){html.comfy-android-remote button{padding-left:10px!important;padding-right:10px!important;}html.comfy-android-remote canvas{touch-action:none!important;}}';head.appendChild(style);}window.ComfyAndroidRemote={clickByText:function(words){var nodes=[].slice.call(document.querySelectorAll('button,[role=button],.p-button'));for(var i=0;i<nodes.length;i++){var n=nodes[i];var t=((n.innerText||n.textContent||'')+' '+(n.title||'')+' '+(n.getAttribute('aria-label')||'')).toLowerCase();for(var j=0;j<words.length;j++){if(t.indexOf(words[j])>=0){n.click();return true;}}}return false;},run:function(){return this.clickByText(['run','queue','generate']);},fit:function(){var ok=this.clickByText(['fit','zoom to fit','reset view']);try{window.dispatchEvent(new KeyboardEvent('keydown',{key:'f',code:'KeyF',bubbles:true}));}catch(e){}return ok;}};})();";
        webView.evaluateJavascript(script, null);
    }

    private void runComfyQueue() {
        if (getBoolSetting(KEY_CONFIRM_RUN, true)) {
            new AlertDialog.Builder(this).setTitle("Run workflow?").setMessage("Start ComfyUI generation with the current workflow values.").setPositiveButton("Run", (dialog, which) -> runComfyQueueNow()).setNegativeButton("Cancel", null).show();
            return;
        }
        runComfyQueueNow();
    }

    private void runComfyQueueNow() {
        injectMobileLayer();
        webView.evaluateJavascript("(function(){return window.ComfyAndroidRemote&&window.ComfyAndroidRemote.run?window.ComfyAndroidRemote.run():false;})();", value -> { if (!"true".equals(value)) Toast.makeText(this, "Run button not found in ComfyUI", Toast.LENGTH_SHORT).show(); });
        enterImmersiveMode();
    }

    private void fitComfyCanvas() {
        injectMobileLayer();
        webView.evaluateJavascript("(function(){return window.ComfyAndroidRemote&&window.ComfyAndroidRemote.fit?window.ComfyAndroidRemote.fit():false;})();", null);
        enterImmersiveMode();
    }

    private void enterImmersiveMode() {
        Window window = getWindow();
        View decor = window.getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) enterImmersiveMode(); }

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
        if (menuDrawer != null && menuDrawer.getVisibility() == View.VISIBLE) hideMenuDrawer();
        else if (nodeDrawer != null && nodeDrawer.getVisibility() == View.VISIBLE) hideNodeDrawer();
        else if (topBar.getVisibility() != View.VISIBLE && webView != null && !webView.canGoBack()) toggleConnectionPanel();
        else if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
