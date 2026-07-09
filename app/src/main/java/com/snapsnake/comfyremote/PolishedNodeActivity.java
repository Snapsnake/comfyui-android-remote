package com.snapsnake.comfyremote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class PolishedNodeActivity extends Activity {
    private static final String PREFS = "comfyui_remote_prefs";
    private static final String KEY_URL = "comfyui_url";
    private static final String KEY_WORKFLOW = "workflow_api_json";
    private static final String KEY_OPTIONS = "workflow_field_options";
    private static final String KEY_OUTPUT = "last_output_url";
    private static final String KEY_LAST_DURATION = "last_generation_duration_ms";
    private static final int REQ_IMAGE = 43;
    private static final int REQ_JSON = 44;
    private static final int REQ_WEB_FILE = 45;

    private LinearLayout topPanel, content, bottomNav;
    private EditText urlInput, jsonEditor;
    private TextView status, generationText;
    private ProgressBar busyBar, generationBar;
    private FrameLayout workspace;
    private ScrollView pane;
    private WebView graph, output;
    private ValueCallback<Uri[]> webFileCallback;
    private JSONObject workflow;
    private JSONObject fieldOptions = new JSONObject();
    private final List<ApiField> fields = new ArrayList<>();
    private String screen = "create";
    private String selectedNodeId, pendingNode, pendingKey, currentPromptId, lastOutputUrl;
    private long generationStartMs = 0L, lastGenerationDurationMs = 0L;
    private int pollCount = 0;
    private boolean importing = false, generationRunning = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private static class ApiField {
        final String node, key;
        final EditText edit;
        ApiField(String node, String key, EditText edit) { this.node = node; this.key = key; this.edit = edit; }
    }
    private static class OutputFile {
        final String filename, subfolder, type;
        OutputFile(String filename, String subfolder, String type) { this.filename = filename; this.subfolder = subfolder; this.type = type; }
    }
    private class Bridge {
        @JavascriptInterface public void onImportResult(String json) {
            handler.post(() -> {
                if (!importing) return;
                importing = false;
                handleImportJson(json == null ? "" : json);
            });
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        configureWebViews();
        loadPrefs();
        render();
        applyBars();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(Color.BLACK);

        topPanel = new LinearLayout(this);
        topPanel.setOrientation(LinearLayout.VERTICAL);
        topPanel.setPadding(dp(18), dp(10), dp(18), dp(12));
        topPanel.setBackgroundColor(Color.rgb(9, 15, 28));
        root.addView(topPanel, new LinearLayout.LayoutParams(-1, -2));

        TextView appTitle = text("ComfyUI Mobile", 20, Color.WHITE);
        appTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        topPanel.addView(appTitle);

        urlInput = new EditText(this);
        soft(urlInput, 15);
        urlInput.setSingleLine(true);
        urlInput.setTextColor(Color.WHITE);
        urlInput.setHintTextColor(Color.rgb(150, 150, 160));
        urlInput.setHint("http://desktop-name.tailnet.ts.net:8188");
        urlInput.setPadding(dp(16), 0, dp(16), 0);
        urlInput.setBackground(bg(Color.rgb(1, 5, 14), dp(18), Color.rgb(45, 54, 74), 1));
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(-1, dp(50));
        up.setMargins(0, dp(8), 0, dp(8));
        topPanel.addView(urlInput, up);

        LinearLayout topRow = row(false);
        topPanel.addView(topRow);
        addTopButton(topRow, "Test", this::testConnection);
        addTopButton(topRow, "Create", this::showCreate);
        addTopButton(topRow, "Nodes", this::showNodes);
        addTopButton(topRow, "Import", this::importFromGraph);

        TextView cf = muted("Cloudflare Access is optional. Fill it only if Access protection is enabled.");
        cf.setPadding(dp(2), dp(6), dp(2), dp(4));
        topPanel.addView(cf);

        busyBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        busyBar.setMax(100);
        busyBar.setVisibility(View.GONE);
        root.addView(busyBar, new LinearLayout.LayoutParams(-1, dp(3)));

        status = text("URL panel hidden. Tap here to show it.", 13, Color.rgb(185, 190, 205));
        status.setSingleLine(false);
        status.setPadding(dp(18), dp(9), dp(18), dp(9));
        status.setBackgroundColor(Color.rgb(9, 15, 28));
        status.setOnClickListener(v -> toggleTopPanel());
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        workspace = new FrameLayout(this);
        workspace.setBackgroundColor(Color.BLACK);
        root.addView(workspace, new LinearLayout.LayoutParams(-1, 0, 1));

        pane = new ScrollView(this);
        pane.setVerticalScrollBarEnabled(false);
        pane.setOverScrollMode(View.OVER_SCROLL_NEVER);
        pane.setBackgroundColor(Color.BLACK);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(28), dp(24), dp(28), dp(90));
        pane.addView(content, new ScrollView.LayoutParams(-1, -2));
        workspace.addView(pane, new FrameLayout.LayoutParams(-1, -1));

        graph = new WebView(this);
        graph.setVisibility(View.GONE);
        workspace.addView(graph, new FrameLayout.LayoutParams(-1, -1));
        output = new WebView(this);
        output.setVisibility(View.GONE);
        workspace.addView(output, new FrameLayout.LayoutParams(-1, -1));

        bottomNav = new LinearLayout(this);
        bottomNav.setOrientation(LinearLayout.HORIZONTAL);
        bottomNav.setGravity(Gravity.CENTER);
        bottomNav.setPadding(dp(10), dp(8), dp(10), dp(8));
        bottomNav.setBackgroundColor(Color.BLACK);
        root.addView(bottomNav, new LinearLayout.LayoutParams(-1, dp(72)));
        addToolButton(bottomNav, "Create", this::showCreate);
        addToolButton(bottomNav, "Nodes", this::showNodes);
        addToolButton(bottomNav, "Templates", this::showGraph);
        addToolButton(bottomNav, "Run", this::runWorkflow);
        addToolButton(bottomNav, "Out", this::openOutput);

        setContentView(root);
    }

    private void loadPrefs() {
        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        urlInput.setText(p.getString(KEY_URL, "http://desktop-name.tailnet.ts.net:8188"));
        lastOutputUrl = p.getString(KEY_OUTPUT, "");
        lastGenerationDurationMs = p.getLong(KEY_LAST_DURATION, 0L);
        if (p.contains(KEY_URL)) topPanel.setVisibility(View.GONE);
        try { workflow = cleanWorkflow(new JSONObject(p.getString(KEY_WORKFLOW, "{}"))); if (workflow.length() == 0) workflow = null; } catch (Exception ignored) { workflow = null; }
        try { fieldOptions = new JSONObject(p.getString(KEY_OPTIONS, "{}")); } catch (Exception ignored) { fieldOptions = new JSONObject(); }
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"}) private void configureWebViews() {
        configureWebView(graph);
        configureWebView(output);
        graph.addJavascriptInterface(new Bridge(), "ComfyRemoteBridge");
        graph.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (webFileCallback != null) webFileCallback.onReceiveValue(null);
                webFileCallback = cb;
                Intent intent;
                try { intent = params.createIntent(); }
                catch (Exception e) { intent = new Intent(Intent.ACTION_GET_CONTENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*"); }
                try { startActivityForResult(intent, REQ_WEB_FILE); return true; }
                catch (Exception e) { webFileCallback = null; toast("No file picker available"); return false; }
            }
        });
        graph.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) { busy(true, "Graph loading..."); }
            @Override public void onPageFinished(WebView view, String url) { busy(false, "Graph loaded. Load workflow, then press Import."); injectGraphCss(); applyBars(); }
        });
        output.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) { busy(true, "Output loading..."); }
            @Override public void onPageFinished(WebView view, String url) { busy(false, "Output preview."); applyBars(); }
        });
    }

    private void configureWebView(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true); s.setAllowContentAccess(true); s.setLoadWithOverviewMode(true); s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true); s.setDisplayZoomControls(false); s.setSupportZoom(true); s.setTextZoom(100);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        w.setBackgroundColor(Color.BLACK);
    }

    private void render() {
        fields.clear();
        content.removeAllViews();
        if (workflow == null) { renderNoWorkflow(); return; }
        if ("nodes".equals(screen)) renderNodes(); else renderCreate();
    }

    private void pageHeader(String title, String subtitle) {
        TextView h = text(title, 30, Color.WHITE);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        content.addView(h);
        if (subtitle != null && !subtitle.trim().isEmpty()) {
            TextView s = muted(subtitle);
            s.setTextSize(17);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
            sp.setMargins(0, 0, 0, dp(18));
            content.addView(s, sp);
        }
    }

    private void renderNoWorkflow() {
        pageHeader("Create", "Open Graph, load workflow, then Import.");
        sectionTitle("Workflow");
        LinearLayout r1 = row(true);
        content.addView(r1, new LinearLayout.LayoutParams(-1, dp(54)));
        addCardButton(r1, "Templates", false, this::showGraph);
        addCardButton(r1, "Import", true, this::importFromGraph);

        jsonEditor = new EditText(this);
        soft(jsonEditor, 15);
        jsonEditor.setTextColor(Color.WHITE);
        jsonEditor.setHintTextColor(Color.rgb(150, 150, 160));
        jsonEditor.setHint("Fallback: paste API workflow JSON");
        jsonEditor.setGravity(Gravity.TOP | Gravity.LEFT);
        jsonEditor.setMinLines(5);
        jsonEditor.setMaxLines(8);
        jsonEditor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        jsonEditor.setPadding(dp(16), dp(12), dp(16), dp(12));
        jsonEditor.setBackground(bg(Color.rgb(1, 5, 14), dp(18), Color.rgb(45, 54, 74), 1));
        LinearLayout.LayoutParams jp = new LinearLayout.LayoutParams(-1, dp(150));
        jp.setMargins(0, dp(18), 0, dp(18));
        content.addView(jsonEditor, jp);

        LinearLayout r2 = row(true);
        content.addView(r2, new LinearLayout.LayoutParams(-1, dp(54)));
        addCardButton(r2, "Load JSON", false, this::chooseJson);
        addCardButton(r2, "Apply JSON", true, this::applyJson);
    }

    private void renderCreate() {
        if (selectedNodeId == null || !visibleNodeIds().contains(selectedNodeId)) selectedNodeId = firstEditableOrFirst();
        pageHeader("Create", workflow.length() + " nodes. Edit the selected node, then run.");
        nodePickerCard();
        selectedEditorCard();
        generateCard();
    }

    private void nodePickerCard() {
        sectionTitle("Selected node");
        content.addView(nodeSummaryView(selectedNodeId, false));
        LinearLayout r = row(true);
        content.addView(r, new LinearLayout.LayoutParams(-1, dp(52)));
        addCardButton(r, "Choose node", true, this::showNodes);
        addCardButton(r, "Import again", false, this::importFromGraph);
        spacer(18);
    }

    private void selectedEditorCard() {
        if (selectedNodeId == null) return;
        JSONObject node = workflow.optJSONObject(selectedNodeId);
        if (node == null) return;
        JSONObject inputs = node.optJSONObject("inputs");
        String cls = node.optString("class_type", "Node");
        sectionTitle(nodeTitle(node));
        content.addView(muted("#" + selectedNodeId + " · " + prettify(cls)));
        if (isLoadImage(cls)) addImageControl(content, selectedNodeId, inputs);
        boolean added = false;
        for (String key : inputKeys(inputs)) {
            Object value = inputs.opt(key);
            if (!primitive(value)) continue;
            if (isLoadImage(cls) && key.equals(imageKey(inputs))) continue;
            addField(content, selectedNodeId, key, value, fieldTitle(key));
            added = true;
        }
        boolean linked = addLinkedInputs(content, inputs);
        if (!added && !linked) content.addView(muted("No local editable fields. Tap a linked input or choose another node."));
        spacer(16);
    }

    private void renderNodes() {
        pageHeader("Nodes", "Tap a row to edit that node in Create.");
        List<String> ids = visibleNodeIds();
        for (String id : ids) content.addView(nodeSummaryView(id, true));
    }

    private View nodeSummaryView(String id, boolean clickable) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(12), 0, dp(12));
        box.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.setMargins(0, 0, 0, dp(8));
        box.setLayoutParams(bp);
        if (id == null || workflow == null || workflow.optJSONObject(id) == null) {
            box.addView(header("No node selected"));
            return box;
        }
        JSONObject node = workflow.optJSONObject(id);
        JSONObject inputs = node.optJSONObject("inputs");
        TextView title = text(nodeTitle(node), 18, Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(title);
        box.addView(muted("#" + id + " · " + prettify(node.optString("class_type", "Node"))));
        LinearLayout chips = row(true);
        chips.setPadding(0, dp(4), 0, 0);
        box.addView(chips);
        addChip(chips, editableCount(id, inputs) + " fields");
        addChip(chips, dropdownCount(id, inputs) + " lists");
        addChip(chips, fieldPreview(inputs));
        if (clickable) box.setOnClickListener(v -> { applyFields(); saveWorkflow(); selectedNodeId = id; showCreate(); });
        return box;
    }

    private void addChip(LinearLayout r, String value) {
        TextView chip = text(shorten(value, 28), 12, Color.rgb(226, 232, 240));
        chip.setSingleLine(true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(9), dp(5), dp(9), dp(5));
        chip.setBackground(bg(Color.rgb(13, 18, 31), dp(14), Color.rgb(45, 54, 74), 1));
        r.addView(chip, weight(dp(30)));
    }

    private boolean addLinkedInputs(LinearLayout c, JSONObject inputs) {
        if (inputs == null) return false;
        boolean linked = false;
        for (String key : inputKeys(inputs)) {
            JSONArray link = inputs.optJSONArray(key);
            if (link == null || link.length() == 0) continue;
            String ref = link.optString(0, "");
            if (ref.isEmpty() || workflow == null || !workflow.has(ref)) continue;
            JSONObject src = workflow.optJSONObject(ref);
            if (!linked) { c.addView(sectionLabel("Linked inputs")); linked = true; }
            Button jump = button(key + "  ←  #" + ref + " " + shorten(nodeTitle(src), 28), Color.rgb(6, 10, 22), 14, 16);
            jump.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            jump.setPadding(dp(14), 0, dp(14), 0);
            jump.setOnClickListener(v -> { applyFields(); saveWorkflow(); selectedNodeId = ref; showCreate(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(50));
            p.setMargins(0, 0, 0, dp(8));
            c.addView(jump, p);
        }
        return linked;
    }

    private void addImageControl(LinearLayout c, String id, JSONObject inputs) {
        String key = imageKey(inputs);
        LinearLayout box = fieldBox();
        box.addView(fieldName("Image · " + key));
        Button choose = button("Choose image from phone", Color.rgb(10, 20, 48), 14, 16);
        choose.setOnClickListener(v -> chooseImage(id, key));
        box.addView(choose, new LinearLayout.LayoutParams(-1, dp(50)));
        Object current = inputs == null ? null : inputs.opt(key);
        if (current instanceof String && !String.valueOf(current).trim().isEmpty()) box.addView(muted("Current: " + shorten(String.valueOf(current), 80)));
        c.addView(box, fieldBoxParams());
    }

    private void addField(LinearLayout c, String id, String key, Object value, String title) {
        LinearLayout box = fieldBox();
        box.addView(fieldName(title));
        JSONArray opts = optionValues(id, key, value);
        if (opts != null && opts.length() > 0) {
            Button b = button(String.valueOf(value) + "  ▼", Color.rgb(1, 5, 14), 15, 16);
            b.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            b.setPadding(dp(14), 0, dp(14), 0);
            b.setOnClickListener(v -> showOptionsPicker(id, key, opts));
            box.addView(b, new LinearLayout.LayoutParams(-1, dp(50)));
            c.addView(box, fieldBoxParams());
            return;
        }
        EditText e = new EditText(this);
        soft(e, 15);
        e.setText(value == JSONObject.NULL ? "" : String.valueOf(value));
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.rgb(150, 150, 160));
        e.setPadding(dp(14), 0, dp(14), 0);
        e.setBackground(bg(Color.rgb(1, 5, 14), dp(16), Color.rgb(45, 54, 74), 1));
        boolean multi = key.toLowerCase().contains("prompt") || key.toLowerCase().contains("text") || String.valueOf(value).length() > 80;
        e.setSingleLine(!multi);
        if (multi) {
            e.setGravity(Gravity.TOP | Gravity.LEFT);
            e.setMinLines(3);
            e.setMaxLines(8);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        } else if (value instanceof Number || numericKey(key)) {
            e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        }
        box.addView(e, new LinearLayout.LayoutParams(-1, multi ? dp(118) : dp(50)));
        c.addView(box, fieldBoxParams());
        fields.add(new ApiField(id, key, e));
    }

    private LinearLayout fieldBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(2));
        box.setBackgroundColor(Color.TRANSPARENT);
        return box;
    }
    private LinearLayout.LayoutParams fieldBoxParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(8), 0, 0);
        return p;
    }

    private void showOptionsPicker(String node, String key, JSONArray opts) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackground(bg(Color.rgb(7, 11, 20), dp(22), Color.rgb(45, 54, 74), 1));
        root.addView(header(human(key)));
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        for (int i = 0; i < opts.length(); i++) {
            String item = opts.optString(i, "");
            Button b = button(item, Color.rgb(13, 18, 31), 15, 14);
            b.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            b.setPadding(dp(14), 0, dp(14), 0);
            b.setOnClickListener(v -> { setInput(node, key, coerce(item)); saveWorkflow(); dialog.dismiss(); render(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(50));
            p.setMargins(0, dp(8), 0, 0);
            root.addView(b, p);
        }
        dialog.setView(root);
        dialog.setOnShowListener(d -> { Window w = dialog.getWindow(); if (w != null) w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); });
        dialog.show();
    }

    private void generateCard() {
        sectionTitle("Generate");
        LinearLayout r = row(true);
        content.addView(r, new LinearLayout.LayoutParams(-1, dp(52)));
        addCardButton(r, "Apply", false, () -> { applyFields(); saveWorkflow(); toast("Applied"); render(); });
        addCardButton(r, "Run", true, this::runWorkflow);
        Button out = button("Open latest output", Color.rgb(6, 10, 22), 14, 16);
        out.setOnClickListener(v -> openOutput());
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, dp(50));
        op.setMargins(0, dp(12), 0, dp(8));
        content.addView(out, op);
        generationText = muted(generationRunning ? "Generation running..." : generationIdleText());
        content.addView(generationText);
        generationBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        generationBar.setMax(100);
        content.addView(generationBar, new LinearLayout.LayoutParams(-1, dp(8)));
        refreshGenerationUi();
    }

    private void showCreate() { screen = "create"; pane.setVisibility(View.VISIBLE); graph.setVisibility(View.GONE); output.setVisibility(View.GONE); render(); status.setText("Create. Tap to show URL panel."); applyBars(); }
    private void showNodes() { screen = "nodes"; pane.setVisibility(View.VISIBLE); graph.setVisibility(View.GONE); output.setVisibility(View.GONE); render(); status.setText("Nodes. Tap to show URL panel."); applyBars(); }
    private void showGraph() { saveUrl(); pane.setVisibility(View.GONE); output.setVisibility(View.GONE); graph.setVisibility(View.VISIBLE); topPanel.setVisibility(View.GONE); String base = baseUrl(); if (base.isEmpty()) { toast("Enter ComfyUI URL first"); return; } String cur = graph.getUrl(); if (cur == null || !cur.startsWith(base) || cur.contains("/view")) graph.loadUrl(base); status.setText("Graph mode. Load workflow, wait until visible, then Import."); applyBars(); }
    private void showOutput(String url) { topPanel.setVisibility(View.GONE); pane.setVisibility(View.GONE); graph.setVisibility(View.GONE); output.setVisibility(View.VISIBLE); output.loadUrl(url); status.setText("Opening output inside the app..."); applyBars(); }

    private void importFromGraph() {
        saveUrl();
        String base = baseUrl();
        if (base.isEmpty()) { toast("Enter ComfyUI URL first"); return; }
        String cur = graph.getUrl();
        if (cur == null || !cur.startsWith(base) || cur.contains("/view")) { showGraph(); toast("Graph opened. Load workflow, then press Import again."); return; }
        importing = true;
        busy(true, "Importing workflow from Graph...");
        handler.postDelayed(() -> { if (importing) { importing = false; busy(false, "Import timed out. Wait until workflow is visible, then Import again."); } }, 15000);
        graph.evaluateJavascript(importScript(), null);
    }

    private String importScript() {
        return "(async function(){"
                + "function send(o){try{window.ComfyRemoteBridge.onImportResult(JSON.stringify(o));}catch(e){}}"
                + "function graphObj(){return (window.app&&app.graph)||window.graph||((window.LGraphCanvas&&window.LGraphCanvas.active_canvas)&&window.LGraphCanvas.active_canvas.graph);}"
                + "function prim(v){return v===null||['string','number','boolean'].indexOf(typeof v)>=0;}"
                + "function li(g,id){var links=(g&&g.links)||{};var l=links[id];if(!l&&Array.isArray(links)){for(var i=0;i<links.length;i++){if(links[i]&&(links[i].id==id||links[i][0]==id)){l=links[i];break;}}}if(!l)return null;if(Array.isArray(l))return {o:String(l[1]),s:Number(l[2]||0)};return {o:String(l.origin_id||l.source_id||l.from_id||l.origin||''),s:Number(l.origin_slot||l.source_slot||l.from_slot||0)};}"
                + "function opts(w){var o=w&&w.options;var a=null;if(o){if(Array.isArray(o.values))a=o.values;else if(Array.isArray(o))a=o;}if(!a&&Array.isArray(w&&w.values))a=w.values;return a&&a.length?a.map(function(x){return String(x);}):null;}"
                + "function fallback(){var g=graphObj();if(!g)return {ok:false,error:'Graph object not found'};var nodes=g._nodes||g.nodes||[];var out={},options={};for(var n of nodes){if(!n||n.id==null)continue;var cls=String(n.type||n.comfyClass||n.title||'');var title=String(n.title||cls);var low=cls.toLowerCase();if(!cls||low.indexOf('note')>=0||low.indexOf('markdown')>=0)continue;var item={class_type:cls,inputs:{},_meta:{title:title}};for(var inp of (n.inputs||[])){if(inp&&inp.link!=null&&inp.name){var x=li(g,inp.link);if(x&&x.o)item.inputs[String(inp.name)]=[x.o,x.s];}}for(var w of (n.widgets||[])){if(!w||!w.name)continue;var name=String(w.name);var type=String(w.type||'').toLowerCase();if(name==='upload'||type==='button')continue;var os=opts(w);if(os)options[String(n.id)+':'+name]=os;if(prim(w.value))item.inputs[name]=w.value;}out[String(n.id)]=item;}return {ok:Object.keys(out).length>0,prompt:out,options:options,mode:'graph fallback'};}"
                + "function mergeMeta(p,f){try{for(var k in p){if(f.prompt&&f.prompt[k]&&f.prompt[k]._meta){p[k]._meta=f.prompt[k]._meta;}}}catch(e){}return p;}"
                + "try{var f=fallback();if(window.app&&app.graphToPrompt){try{var gp=app.graphToPrompt();if(gp&&typeof gp.then==='function')gp=await gp;var p=gp&&(gp.output||gp.prompt||gp);if(p&&Object.keys(p).length){send({ok:true,prompt:mergeMeta(p,f),options:f.options||{},mode:'graphToPrompt'});return;}}catch(e){}}send(f);}catch(e){send({ok:false,error:String(e&&e.message?e.message:e)});}"
                + "})()";
    }

    private void handleImportJson(String decoded) {
        try {
            JSONObject res = new JSONObject(decoded);
            if (!res.optBoolean("ok", false)) { busy(false, "Import failed: " + res.optString("error")); return; }
            Object p = res.opt("prompt");
            if (p instanceof JSONObject) workflow = cleanWorkflow((JSONObject) p);
            else if (p instanceof String) workflow = cleanWorkflow(new JSONObject((String) p));
            else { busy(false, "Import failed: unsupported prompt format"); return; }
            fieldOptions = res.optJSONObject("options"); if (fieldOptions == null) fieldOptions = new JSONObject();
            saveWorkflow(); saveOptions(); selectedNodeId = firstEditableOrFirst();
            String problem = workflowProblem(workflow);
            busy(false, "Imported " + workflow.length() + " nodes via " + res.optString("mode", "Graph") + (problem == null ? "." : ". Warning: " + problem));
            showCreate();
        } catch (Exception e) { busy(false, "Import failed: " + shortError(e)); }
    }

    private void chooseJson() { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); try { startActivityForResult(Intent.createChooser(i, "Choose workflow JSON"), REQ_JSON); } catch (Exception e) { toast("No file picker available"); } }
    private void applyJson() { try { workflow = cleanWorkflow(new JSONObject(jsonEditor == null ? "" : jsonEditor.getText().toString())); fieldOptions = new JSONObject(); saveWorkflow(); saveOptions(); selectedNodeId = firstEditableOrFirst(); toast("Workflow loaded"); render(); } catch (JSONException e) { toast("Invalid JSON"); } }
    private void applyJsonText(String raw) { try { workflow = cleanWorkflow(new JSONObject(raw)); fieldOptions = new JSONObject(); saveWorkflow(); saveOptions(); selectedNodeId = firstEditableOrFirst(); toast("Workflow loaded"); render(); } catch (JSONException e) { toast("Invalid JSON file"); } }

    private void chooseImage(String node, String key) {
        if (key == null || key.trim().isEmpty()) { toast("Image input not found"); return; }
        pendingNode = node; pendingKey = key;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("image/*"); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(Intent.createChooser(i, "Choose image"), REQ_IMAGE); } catch (Exception e) { pendingNode = null; pendingKey = null; toast("No image picker available"); }
    }

    private void uploadImage(Uri uri, String node, String key) {
        if (uri == null || node == null || key == null) return;
        String base = baseUrl(); if (base.isEmpty()) { toast("Enter ComfyUI URL first"); return; }
        busy(true, "Uploading image...");
        new Thread(() -> { try { String uploaded = uploadMultipart(base, readBytes(uri), displayName(uri), getContentResolver().getType(uri)); setInput(node, key, uploaded); saveWorkflow(); handler.post(() -> { busy(false, "Uploaded image: " + uploaded); render(); }); } catch (Exception e) { handler.post(() -> busy(false, "Image upload failed: " + shortError(e))); } }).start();
    }

    private void runWorkflow() {
        if (workflow == null) { toast("Import a workflow first"); return; }
        saveUrl(); applyFields(); workflow = cleanWorkflow(workflow); saveWorkflow();
        String problem = workflowProblem(workflow); if (problem != null) { busy(false, "Run blocked: " + problem + " Re-import using Graph."); return; }
        String base = baseUrl(); if (base.isEmpty()) { toast("Enter ComfyUI URL first"); return; }
        try {
            JSONObject payload = new JSONObject(); payload.put("prompt", cleanWorkflow(workflow)); payload.put("client_id", "android-remote-" + System.currentTimeMillis());
            generationRunning = true; generationStartMs = System.currentTimeMillis(); pollCount = 0;
            updateGenerationUi(8, "Sending prompt..."); busy(true, "Sending prompt...");
            new Thread(() -> { try { JSONObject res = new JSONObject(postJson(base + "/prompt", payload.toString())); currentPromptId = res.optString("prompt_id", ""); if (currentPromptId.isEmpty()) throw new IllegalStateException("ComfyUI did not return prompt_id"); handler.post(() -> { showCreate(); busy(false, "Queued. Waiting for output..."); updateGenerationUi(15, "Queued."); pollHistory(); }); } catch (Exception e) { handler.post(() -> { generationRunning = false; busy(false, "Run failed: " + shortError(e)); updateGenerationUi(0, "Run failed: " + shortError(e)); }); } }).start();
        } catch (JSONException e) { toast("Could not build prompt JSON"); }
    }

    private void pollHistory() {
        if (currentPromptId == null || currentPromptId.isEmpty()) return;
        pollCount++;
        String base = baseUrl(); String pid = currentPromptId;
        updateGenerationUi(estimatedProgressPercent(), generationProgressText()); status.setText("Generating... " + generationProgressText());
        new Thread(() -> { try { OutputFile f = findOutput(new JSONObject(getText(base + "/history/" + enc(pid)))); if (f != null) { lastOutputUrl = base + "/view?filename=" + enc(f.filename) + "&type=" + enc(f.type) + "&subfolder=" + enc(f.subfolder); lastGenerationDurationMs = Math.max(1L, System.currentTimeMillis() - generationStartMs); getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_OUTPUT, lastOutputUrl).putLong(KEY_LAST_DURATION, lastGenerationDurationMs).apply(); handler.post(() -> { generationRunning = false; busy(false, "Output ready."); updateGenerationUi(100, "Output ready. Generation took " + fmtMs(lastGenerationDurationMs) + "."); }); return; } } catch (Exception ignored) {} if (pollCount < 240) handler.postDelayed(this::pollHistory, 2000); else handler.post(() -> { generationRunning = false; busy(false, "Timed out waiting for output."); updateGenerationUi(0, "Timed out waiting for output."); }); }).start();
    }

    private void openOutput() {
        if (lastOutputUrl != null && !lastOutputUrl.trim().isEmpty()) { showOutput(lastOutputUrl); return; }
        String base = baseUrl(); if (base.isEmpty()) { toast("Enter ComfyUI URL first"); return; }
        busy(true, "Finding latest output...");
        new Thread(() -> { try { OutputFile f = findOutput(new JSONObject(getText(base + "/history"))); if (f == null) throw new IllegalStateException("No output found in /history"); lastOutputUrl = base + "/view?filename=" + enc(f.filename) + "&type=" + enc(f.type) + "&subfolder=" + enc(f.subfolder); getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_OUTPUT, lastOutputUrl).apply(); handler.post(() -> { busy(false, "Opening output."); showOutput(lastOutputUrl); }); } catch (Exception e) { handler.post(() -> busy(false, "No output found: " + shortError(e))); } }).start();
    }

    private void testConnection() { saveUrl(); String base = baseUrl(); if (base.isEmpty()) { toast("Enter ComfyUI URL first"); return; } busy(true, "Testing..."); new Thread(() -> { try { getText(base + "/system_stats"); handler.post(() -> busy(false, "Connection OK.")); } catch (Exception e) { handler.post(() -> busy(false, "Connection failed: " + shortError(e))); } }).start(); }

    private JSONObject cleanWorkflow(JSONObject src) {
        JSONObject out = new JSONObject(); if (src == null) return out;
        Iterator<String> it = src.keys(); while (it.hasNext()) { String id = it.next(); JSONObject node = src.optJSONObject(id); if (node == null) continue; if (isNonRunnable(node.optString("class_type", node.optString("type", "")))) continue; try { out.put(id, node); } catch (JSONException ignored) {} }
        return out;
    }
    private String workflowProblem(JSONObject wf) {
        if (wf == null || wf.length() == 0) return "workflow is empty.";
        Iterator<String> it = wf.keys(); while (it.hasNext()) { String id = it.next(); JSONObject node = wf.optJSONObject(id); JSONObject inputs = node == null ? null : node.optJSONObject("inputs"); if (inputs == null) continue; for (String k : inputKeys(inputs)) { JSONArray arr = inputs.optJSONArray(k); if (arr != null && arr.length() > 0) { String ref = arr.optString(0, ""); if (!ref.isEmpty() && !wf.has(ref)) return "node #" + id + " input " + k + " points to missing node #" + ref + "."; } } }
        return null;
    }
    private List<String> nodeIds() { List<String> k = new ArrayList<>(); if (workflow == null) return k; Iterator<String> it = workflow.keys(); while (it.hasNext()) k.add(it.next()); Collections.sort(k, (a, b) -> { try { return Integer.compare(Integer.parseInt(a), Integer.parseInt(b)); } catch (Exception e) { return a.compareTo(b); } }); return k; }
    private List<String> visibleNodeIds() { List<String> ids = new ArrayList<>(); for (String id : nodeIds()) { JSONObject node = workflow.optJSONObject(id); if (node == null) continue; String cls = node.optString("class_type", "Node"); if (!isNonRunnable(cls) && !isReroute(cls)) ids.add(id); } return ids; }
    private String firstEditableOrFirst() { for (String id : visibleNodeIds()) { JSONObject node = workflow.optJSONObject(id); JSONObject inputs = node == null ? null : node.optJSONObject("inputs"); if (editableCount(id, inputs) > 0) return id; } List<String> ids = visibleNodeIds(); return ids.isEmpty() ? null : ids.get(0); }
    private List<String> inputKeys(JSONObject o) { List<String> k = new ArrayList<>(); if (o == null) return k; Iterator<String> it = o.keys(); while (it.hasNext()) k.add(it.next()); Collections.sort(k); return k; }
    private JSONArray optionValues(String node, String key, Object value) { JSONArray saved = fieldOptions == null ? null : fieldOptions.optJSONArray(node + ":" + key); if (saved != null && saved.length() > 0) return saved; if (value instanceof Boolean) return jsonArray("[\"true\",\"false\"]"); return commonOptions(key); }
    private JSONArray commonOptions(String key) { String k = key == null ? "" : key.toLowerCase(); if (k.equals("sampler_name") || k.equals("sampler")) return jsonArray("[\"euler\",\"euler_ancestral\",\"heun\",\"dpm_2\",\"dpm_2_ancestral\",\"lms\",\"dpm_fast\",\"dpm_adaptive\",\"dpmpp_2s_ancestral\",\"dpmpp_sde\",\"dpmpp_2m\",\"ddim\",\"uni_pc\"]"); if (k.equals("scheduler")) return jsonArray("[\"normal\",\"karras\",\"exponential\",\"sgm_uniform\",\"simple\",\"ddim_uniform\",\"beta\"]"); if (k.equals("format") || k.equals("codec")) return jsonArray("[\"auto\",\"mp4\",\"webm\",\"gif\"]"); return null; }
    private JSONArray jsonArray(String s) { try { return new JSONArray(s); } catch (JSONException e) { return null; } }
    private boolean primitive(Object v) { return v == JSONObject.NULL || v instanceof String || v instanceof Number || v instanceof Boolean; }
    private int editableCount(String id, JSONObject inputs) { int n = 0; for (String k : inputKeys(inputs)) if (primitive(inputs.opt(k))) n++; return n; }
    private int dropdownCount(String id, JSONObject inputs) { int n = 0; for (String k : inputKeys(inputs)) { Object v = inputs.opt(k); if (primitive(v) && optionValues(id, k, v) != null) n++; } return n; }
    private boolean isNonRunnable(String cls) { String s = cls == null ? "" : cls.toLowerCase(); return s.contains("note") || s.contains("markdown") || s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"); }
    private boolean isReroute(String cls) { String s = cls == null ? "" : cls.toLowerCase(); return s.contains("reroute"); }
    private boolean isLoadImage(String c) { String s = c == null ? "" : c.toLowerCase(); return s.contains("loadimage") || s.contains("load image"); }
    private boolean numericKey(String k) { String s = k.toLowerCase(); return s.contains("width") || s.contains("height") || s.contains("step") || s.contains("seed") || s.contains("cfg") || s.contains("duration") || s.contains("batch") || s.contains("fps") || s.contains("frame") || s.equals("denoise") || s.equals("length") || s.equals("strength"); }
    private String imageKey(JSONObject inputs) { for (String k : inputKeys(inputs)) if (k.equalsIgnoreCase("image") || k.toLowerCase().contains("image")) return k; return "image"; }
    private String nodeTitle(JSONObject node) { if (node == null) return "Node"; JSONObject meta = node.optJSONObject("_meta"); String title = meta == null ? "" : meta.optString("title", ""); if (title != null && !title.trim().isEmpty()) return prettify(title); return prettify(node.optString("class_type", "Node")); }
    private String human(String k) { String s = k.toLowerCase(); if (s.equals("ckpt_name")) return "Checkpoint"; if (s.equals("lora_name")) return "LoRA"; if (s.equals("seed") || s.equals("noise_seed")) return "Seed"; if (s.equals("steps")) return "Steps"; if (s.equals("cfg")) return "CFG"; if (s.equals("width")) return "Width"; if (s.equals("height")) return "Height"; if (s.equals("filename_prefix")) return "Filename prefix"; if (s.equals("image")) return "Image"; if (s.equals("text") || s.equals("prompt") || s.equals("positive")) return "Prompt"; if (s.equals("negative")) return "Negative prompt"; return prettify(k.replace('_', ' ')); }
    private String fieldTitle(String k) { String h = human(k); String raw = prettify(k.replace('_', ' ')); return h.equalsIgnoreCase(raw) ? k : h + " · " + k; }
    private String fieldPreview(JSONObject inputs) { List<String> keys = new ArrayList<>(); for (String k : inputKeys(inputs)) if (inputs != null && primitive(inputs.opt(k))) keys.add(k); if (keys.isEmpty()) return "no fields"; String out = ""; for (int i = 0; i < keys.size() && i < 3; i++) out += (i == 0 ? "" : ", ") + keys.get(i); if (keys.size() > 3) out += "…"; return out; }
    private String prettify(String v) { return v == null ? "Node" : v.replace('_',' ').replaceAll("([a-z])([A-Z])", "$1 $2").trim(); }
    private void applyFields() { if (workflow != null) for (ApiField f : fields) setInput(f.node, f.key, coerce(f.edit.getText().toString())); }
    private void setInput(String node, String key, Object val) { try { JSONObject n = workflow.optJSONObject(node); if (n != null) n.getJSONObject("inputs").put(key, val); } catch (Exception ignored) {} }
    private Object coerce(String raw) { if (raw == null) return ""; String s = raw.trim(); if ("true".equalsIgnoreCase(s)) return true; if ("false".equalsIgnoreCase(s)) return false; try { if (s.matches("-?\\d+")) return Long.parseLong(s); if (s.matches("-?\\d+\\.\\d+")) return Double.parseDouble(s); } catch (Exception ignored) {} return raw; }
    private void saveWorkflow() { if (workflow != null) getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_WORKFLOW, cleanWorkflow(workflow).toString()).apply(); }
    private void saveOptions() { getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_OPTIONS, fieldOptions == null ? "{}" : fieldOptions.toString()).apply(); }
    private void saveUrl() { getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_URL, baseUrl()).apply(); }
    private String baseUrl() { String u = urlInput.getText().toString().trim(); if (u.isEmpty()) return ""; if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u; while (u.endsWith("/")) u = u.substring(0, u.length() - 1); return u; }
    private String uploadMultipart(String base, byte[] bytes, String filename, String mime) throws Exception { String b = "----ComfyRemote" + System.currentTimeMillis(); HttpURLConnection c = (HttpURLConnection) new URL(base + "/upload/image").openConnection(); try { c.setConnectTimeout(10000); c.setReadTimeout(30000); c.setDoOutput(true); c.setRequestMethod("POST"); c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + b); OutputStream out = c.getOutputStream(); part(out, b, "type", "input"); part(out, b, "overwrite", "true"); filePart(out, b, "image", filename, mime == null ? "application/octet-stream" : mime, bytes); out.write(("--" + b + "--\r\n").getBytes("UTF-8")); out.flush(); out.close(); int code = c.getResponseCode(); String body = readStream(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream()); if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + ": " + body); return new JSONObject(body).optString("name", filename); } finally { c.disconnect(); } }
    private void part(OutputStream out, String b, String n, String v) throws Exception { out.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + n + "\"\r\n\r\n" + v + "\r\n").getBytes("UTF-8")); }
    private void filePart(OutputStream out, String b, String n, String fn, String mt, byte[] bytes) throws Exception { out.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + n + "\"; filename=\"" + fn + "\"\r\nContent-Type: " + mt + "\r\n\r\n").getBytes("UTF-8")); out.write(bytes); out.write("\r\n".getBytes("UTF-8")); }
    private String postJson(String url, String body) throws Exception { HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(); try { c.setConnectTimeout(10000); c.setReadTimeout(30000); c.setDoOutput(true); c.setRequestMethod("POST"); c.setRequestProperty("Content-Type", "application/json; charset=utf-8"); OutputStream out = c.getOutputStream(); out.write(body.getBytes("UTF-8")); out.close(); int code = c.getResponseCode(); String r = readStream(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream()); if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + ": " + r); return r; } finally { c.disconnect(); } }
    private String getText(String url) throws Exception { HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(); try { c.setConnectTimeout(8000); c.setReadTimeout(20000); int code = c.getResponseCode(); String r = readStream(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream()); if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + ": " + r); return r; } finally { c.disconnect(); } }
    private String readStream(InputStream in) throws Exception { if (in == null) return ""; try { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); return out.toString("UTF-8"); } finally { in.close(); } }
    private byte[] readBytes(Uri uri) throws Exception { InputStream in = getContentResolver().openInputStream(uri); if (in == null) throw new IllegalStateException("Could not read selected file"); try { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); return out.toByteArray(); } finally { in.close(); } }
    private String readText(Uri uri) throws Exception { return new String(readBytes(uri), "UTF-8"); }
    private String displayName(Uri uri) { String r = null; Cursor c = null; try { c = getContentResolver().query(uri, null, null, null, null); if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) r = c.getString(i); } } catch (Exception ignored) {} finally { if (c != null) c.close(); } if (r == null || r.trim().isEmpty()) r = "comfy_remote_image.png"; return r.replaceAll("[^A-Za-z0-9._-]", "_"); }
    private OutputFile findOutput(JSONObject history) throws JSONException { OutputFile found = null; Iterator<String> p = history.keys(); while (p.hasNext()) { JSONObject item = history.optJSONObject(p.next()); JSONObject outs = item == null ? null : item.optJSONObject("outputs"); if (outs == null) continue; Iterator<String> nodes = outs.keys(); while (nodes.hasNext()) { JSONObject o = outs.optJSONObject(nodes.next()); if (o == null) continue; OutputFile f = first(o.optJSONArray("videos")); if (f != null) found = f; f = first(o.optJSONArray("gifs")); if (f != null) found = f; f = first(o.optJSONArray("images")); if (f != null) found = f; } } return found; }
    private OutputFile first(JSONArray arr) { if (arr == null || arr.length() == 0) return null; JSONObject f = arr.optJSONObject(0); if (f == null) return null; String name = f.optString("filename", ""); if (name.isEmpty()) return null; return new OutputFile(name, f.optString("subfolder", ""), f.optString("type", "output")); }
    private String enc(String s) throws Exception { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
    private void updateGenerationUi(int percent, String message) { if (generationBar != null) generationBar.setProgress(Math.max(0, Math.min(100, percent))); if (generationText != null) generationText.setText(message); }
    private void refreshGenerationUi() { if (generationRunning) updateGenerationUi(estimatedProgressPercent(), generationProgressText()); else updateGenerationUi(0, generationIdleText()); }
    private String generationIdleText() { return lastGenerationDurationMs > 0 ? "Ready. Last generation: " + fmtMs(lastGenerationDurationMs) + "." : "Ready."; }
    private int estimatedProgressPercent() { if (!generationRunning || generationStartMs <= 0) return 0; long elapsed = System.currentTimeMillis() - generationStartMs; if (lastGenerationDurationMs > 0) return Math.max(10, Math.min(95, (int) ((elapsed * 100L) / Math.max(1L, lastGenerationDurationMs)))); return Math.max(10, Math.min(90, 15 + pollCount * 3)); }
    private String generationProgressText() { long elapsed = Math.max(0L, System.currentTimeMillis() - generationStartMs); String eta = "ETA unknown"; if (lastGenerationDurationMs > 0) eta = "ETA about " + fmtMs(Math.max(0L, lastGenerationDurationMs - elapsed)); return (pollCount <= 1 ? "Queued" : "Generating") + " · elapsed " + fmtMs(elapsed) + " · " + eta; }
    private String fmtMs(long ms) { long sec = Math.max(0L, ms / 1000L), min = sec / 60L, rem = sec % 60L; return min > 0 ? min + "m " + rem + "s" : rem + "s"; }
    private String shortError(Exception e) { if (e == null) return "unknown error"; String s = e.getMessage(); if (s == null || s.trim().isEmpty()) s = e.getClass().getSimpleName(); s = s.replace('\n', ' ').replace('\r', ' ').trim(); return s.length() > 260 ? s.substring(0, 260) + "…" : s; }
    private String shorten(String s, int max) { if (s == null) return ""; return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…"; }
    private LinearLayout row(boolean compact) { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(0, compact ? 0 : dp(8), 0, 0); return r; }
    private void spacer(int h) { TextView s = text("", 1, Color.TRANSPARENT); content.addView(s, new LinearLayout.LayoutParams(-1, dp(h))); }
    private void sectionTitle(String t) { TextView v = header(t); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(8), 0, dp(8)); content.addView(v, p); }
    private void addTopButton(LinearLayout parent, String label, Runnable action) { Button b = button(label, Color.rgb(6, 10, 22), 13, 18); b.setSingleLine(true); b.setOnClickListener(v -> action.run()); parent.addView(b, weight(dp(46))); }
    private void addToolButton(LinearLayout parent, String label, Runnable action) { Button b = button(label, Color.rgb(6, 10, 22), label.length() > 6 ? 10 : 12, 22); b.setSingleLine(true); b.setTextScaleX(label.length() > 6 ? 0.86f : 1f); b.setOnClickListener(v -> action.run()); parent.addView(b, weight(dp(50))); }
    private void addCardButton(LinearLayout r, String text, boolean primary, Runnable action) { Button b = button(text, primary ? Color.rgb(10, 20, 48) : Color.rgb(6, 10, 22), 14, 18); b.setOnClickListener(v -> action.run()); r.addView(b, weight(dp(52))); }
    private LinearLayout.LayoutParams weight(int h) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, h, 1); p.setMargins(dp(4), 0, dp(4), 0); return p; }
    private TextView text(String t, int size, int color) { TextView v = new TextView(this); v.setText(t); v.setTextSize(size); v.setTextColor(color); v.setPadding(dp(2), 0, dp(2), dp(8)); soft(v, size); return v; }
    private TextView header(String t) { TextView v = text(t, 22, Color.WHITE); v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return v; }
    private TextView sectionLabel(String t) { TextView v = text(t, 14, Color.rgb(219, 234, 254)); v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); v.setPadding(0, dp(10), 0, dp(8)); return v; }
    private TextView fieldName(String t) { TextView v = text(t, 13, Color.rgb(203, 213, 225)); v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); v.setSingleLine(false); return v; }
    private TextView muted(String t) { return text(t, 14, Color.rgb(150, 150, 160)); }
    private Button button(String t, int color, int size, int radius) { Button b = new Button(this); b.setText(t); b.setAllCaps(false); b.setSingleLine(false); b.setTextColor(Color.WHITE); b.setTextSize(size); b.setIncludeFontPadding(false); b.setGravity(Gravity.CENTER); b.setPadding(dp(6), 0, dp(6), 0); b.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); b.setBackground(bg(color, dp(radius), Color.rgb(45, 54, 74), 1)); return b; }
    private void soft(TextView v, int size) { v.setTextSize(size); v.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); v.setIncludeFontPadding(true); if (android.os.Build.VERSION.SDK_INT >= 21) v.setLetterSpacing(0.01f); }
    private GradientDrawable bg(int color, int radius, int strokeColor, int strokeDp) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); d.setStroke(dp(strokeDp), strokeColor); return d; }
    private void busy(boolean on, String msg) { busyBar.setVisibility(on ? View.VISIBLE : View.GONE); status.setText(msg); }
    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }
    private void toggleTopPanel() { boolean show = topPanel.getVisibility() != View.VISIBLE; topPanel.setVisibility(show ? View.VISIBLE : View.GONE); status.setText(show ? "URL panel shown. Tap here to hide it." : "URL panel hidden. Tap here to show it."); applyBars(); }
    private void injectGraphCss() { graph.evaluateJavascript("(function(){var m=document.querySelector('meta[name=viewport]')||document.createElement('meta');m.name='viewport';m.content='width=device-width,initial-scale=1,minimum-scale=.35,maximum-scale=3,user-scalable=yes';document.head.appendChild(m);})()", null); }
    private void applyBars() { Window w = getWindow(); w.setStatusBarColor(Color.BLACK); w.setNavigationBarColor(Color.BLACK); w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE); }
    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) applyBars(); }
    @Override protected void onActivityResult(int req, int result, Intent data) { if (req == REQ_WEB_FILE) { if (webFileCallback != null) { webFileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data)); webFileCallback = null; } applyBars(); return; } if (req == REQ_IMAGE) { String n = pendingNode, k = pendingKey; pendingNode = null; pendingKey = null; if (result == RESULT_OK && data != null && data.getData() != null) uploadImage(data.getData(), n, k); else status.setText("Image selection cancelled."); applyBars(); return; } if (req == REQ_JSON) { if (result == RESULT_OK && data != null && data.getData() != null) { try { applyJsonText(readText(data.getData())); } catch (Exception e) { toast("Could not read JSON"); } } applyBars(); return; } super.onActivityResult(req, result, data); }
    @Override public void onBackPressed() { if (output.getVisibility() == View.VISIBLE) { showCreate(); return; } if (graph.getVisibility() == View.VISIBLE) { if (graph.canGoBack()) graph.goBack(); else showCreate(); return; } if ("nodes".equals(screen)) { showCreate(); return; } super.onBackPressed(); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
