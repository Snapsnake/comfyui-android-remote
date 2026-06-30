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

public class FocusedStudioActivity extends Activity {
    private static final String PREFS = "comfyui_remote_prefs";
    private static final String KEY_URL = "comfyui_url";
    private static final String KEY_WORKFLOW = "workflow_api_json";
    private static final String KEY_OPTIONS = "workflow_field_options";
    private static final String KEY_OUTPUT = "last_output_url";
    private static final String KEY_LAST_DURATION = "last_generation_duration_ms";
    private static final int REQ_IMAGE = 43;
    private static final int REQ_JSON = 44;
    private static final int REQ_WEB_FILE = 45;

    private static final String CAT_TEXT = "Text";
    private static final String CAT_MODELS = "Models";
    private static final String CAT_SAMPLING = "Sampling";
    private static final String CAT_VIDEO = "Video";
    private static final String CAT_INPUTS = "Inputs";
    private static final String CAT_OTHER = "Other";

    private LinearLayout topPanel, content;
    private EditText urlInput, jsonEditor;
    private TextView status, generationText;
    private ProgressBar busyBar, generationBar;
    private FrameLayout workspace;
    private ScrollView studioPane;
    private WebView graph, output;
    private ValueCallback<Uri[]> webFileCallback;
    private JSONObject workflow;
    private JSONObject fieldOptions = new JSONObject();
    private final List<ApiField> fields = new ArrayList<>();
    private String screen = "create";
    private String selectedCategory = CAT_TEXT;
    private String selectedControlNodeId, selectedMapNodeId, pendingNode, pendingKey, currentPromptId, lastOutputUrl;
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
        renderStudio();
        applyBars();
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

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(Color.rgb(2, 6, 23));

        topPanel = new LinearLayout(this);
        topPanel.setOrientation(LinearLayout.VERTICAL);
        topPanel.setPadding(dp(12), dp(8), dp(12), dp(8));
        topPanel.setBackgroundColor(Color.rgb(15, 23, 42));
        root.addView(topPanel, new LinearLayout.LayoutParams(-1, -2));
        topPanel.addView(text("ComfyUI Remote", 18, Color.WHITE));

        urlInput = new EditText(this);
        soft(urlInput, 14);
        urlInput.setSingleLine(true);
        urlInput.setTextColor(Color.WHITE);
        urlInput.setHintTextColor(Color.rgb(148, 163, 184));
        urlInput.setHint("http://desktop-name.tailnet.ts.net:8188");
        urlInput.setPadding(dp(12), 0, dp(12), 0);
        urlInput.setBackground(bg(Color.rgb(30, 41, 59), dp(12), Color.rgb(71, 85, 105), 1));
        topPanel.addView(urlInput, new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout topRow = row();
        topPanel.addView(topRow);
        addTopButton(topRow, "Test", this::testConnection);
        addTopButton(topRow, "Create", this::showCreate);
        addTopButton(topRow, "Map", this::showMap);
        addTopButton(topRow, "Import", this::importFromGraph);

        busyBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        busyBar.setMax(100);
        busyBar.setVisibility(View.GONE);
        root.addView(busyBar, new LinearLayout.LayoutParams(-1, dp(3)));

        status = text("URL panel hidden. Tap here to show it.", 12, Color.rgb(203, 213, 225));
        status.setPadding(dp(12), dp(7), dp(12), dp(7));
        status.setBackgroundColor(Color.rgb(15, 23, 42));
        status.setOnClickListener(v -> toggleTopPanel());
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        workspace = new FrameLayout(this);
        root.addView(workspace, new LinearLayout.LayoutParams(-1, 0, 1));
        studioPane = new ScrollView(this);
        studioPane.setVerticalScrollBarEnabled(true);
        studioPane.setScrollbarFadingEnabled(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(88));
        studioPane.addView(content, new ScrollView.LayoutParams(-1, -2));
        workspace.addView(studioPane, new FrameLayout.LayoutParams(-1, -1));

        graph = new WebView(this);
        graph.setVisibility(View.GONE);
        workspace.addView(graph, new FrameLayout.LayoutParams(-1, -1));
        output = new WebView(this);
        output.setVisibility(View.GONE);
        workspace.addView(output, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(dp(8), dp(8), dp(8), dp(8));
        bottom.setBackgroundColor(Color.rgb(15, 23, 42));
        root.addView(bottom, new LinearLayout.LayoutParams(-1, dp(72)));
        addToolButton(bottom, "Create", this::showCreate);
        addToolButton(bottom, "Map", this::showMap);
        addToolButton(bottom, "Graph", this::showGraph);
        addToolButton(bottom, "Run", this::runWorkflow);
        addToolButton(bottom, "Output", this::openOutput);
        setContentView(root);
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
    }

    private void renderStudio() {
        fields.clear();
        content.removeAllViews();
        if (workflow == null) {
            content.addView(text("Create", 26, Color.WHITE));
            content.addView(muted("Open Graph, load workflow, then Import."));
            sourceCard();
            return;
        }
        if ("map".equals(screen)) renderMapScreen(); else renderCreateScreen();
    }

    private void renderCreateScreen() {
        ensureUsefulCategory();
        content.addView(text("Create", 26, Color.WHITE));
        content.addView(muted(workflow.length() + " nodes loaded. Pick a category, then edit one group."));
        categoryCard();
        controlCard();
        generateCard();
    }

    private void renderMapScreen() {
        content.addView(text("Workflow map", 26, Color.WHITE));
        content.addView(muted("Technical view. It is separate from editing."));
        workflowMapCard();
        selectedNodeCard();
    }

    private void sourceCard() {
        LinearLayout c = card(); content.addView(c, cardParams());
        c.addView(header("Workflow"));
        c.addView(muted("Use Graph → load workflow → Import. Manual API JSON import is a fallback."));
        LinearLayout r1 = row(); c.addView(r1);
        addCardButton(r1, "Open Graph", false, this::showGraph);
        addCardButton(r1, "Import", true, this::importFromGraph);
        jsonEditor = new EditText(this);
        soft(jsonEditor, 13);
        jsonEditor.setTextColor(Color.WHITE);
        jsonEditor.setHintTextColor(Color.rgb(148, 163, 184));
        jsonEditor.setHint("Fallback: paste API workflow JSON");
        jsonEditor.setGravity(Gravity.TOP | Gravity.LEFT);
        jsonEditor.setMinLines(4);
        jsonEditor.setMaxLines(8);
        jsonEditor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        jsonEditor.setPadding(dp(12), dp(10), dp(12), dp(10));
        jsonEditor.setBackground(bg(Color.rgb(15, 23, 42), dp(14), Color.rgb(51, 65, 85), 1));
        LinearLayout.LayoutParams jp = new LinearLayout.LayoutParams(-1, dp(130));
        jp.setMargins(0, dp(10), 0, dp(10));
        c.addView(jsonEditor, jp);
        LinearLayout r2 = row(); c.addView(r2);
        addCardButton(r2, "Load JSON", false, this::chooseJson);
        addCardButton(r2, "Apply JSON", true, this::applyJson);
    }

    private void categoryCard() {
        LinearLayout c = card(); content.addView(c, cardParams());
        c.addView(header("Categories"));
        addCategoryRow(c, CAT_TEXT, CAT_MODELS);
        addCategoryRow(c, CAT_SAMPLING, CAT_VIDEO);
        addCategoryRow(c, CAT_INPUTS, CAT_OTHER);
    }

    private void addCategoryRow(LinearLayout c, String a, String b) {
        LinearLayout r = row(); c.addView(r);
        addCategoryButton(r, a);
        addCategoryButton(r, b);
    }

    private void addCategoryButton(LinearLayout r, String cat) {
        int count = categoryNodeIds(cat).size();
        boolean selected = cat.equals(selectedCategory);
        Button b = button(categoryTitle(cat) + "\n" + count + " groups", selected ? Color.rgb(37, 99, 235) : Color.rgb(51, 65, 85), 13, 14);
        b.setOnClickListener(v -> { applyFields(); saveWorkflow(); selectedCategory = cat; selectedControlNodeId = null; renderStudio(); });
        r.addView(b, weight(dp(62)));
    }

    private void controlCard() {
        List<String> ids = categoryNodeIds(selectedCategory);
        LinearLayout c = cardAccent(); content.addView(c, cardParams());
        c.addView(header(categoryTitle(selectedCategory)));
        if (ids.isEmpty()) {
            c.addView(muted("No editable groups in this category."));
            return;
        }
        if (selectedControlNodeId == null || !ids.contains(selectedControlNodeId)) selectedControlNodeId = ids.get(0);
        LinearLayout r = row(); c.addView(r);
        addCardButton(r, "Pick group", false, () -> showControlPicker(ids));
        addCardButton(r, "Next in category", true, () -> { applyFields(); saveWorkflow(); selectedControlNodeId = nextId(ids, selectedControlNodeId); renderStudio(); });
        addControlGroup(c, selectedControlNodeId);
    }

    private void showControlPicker(List<String> ids) {
        String[] items = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) items[i] = controlLabel(ids.get(i));
        new AlertDialog.Builder(this).setTitle(categoryTitle(selectedCategory)).setItems(items, (d, which) -> {
            applyFields(); saveWorkflow(); selectedControlNodeId = ids.get(which); renderStudio();
        }).show();
    }

    private String controlLabel(String id) {
        JSONObject node = workflow.optJSONObject(id);
        JSONObject inputs = node == null ? null : node.optJSONObject("inputs");
        String cls = node == null ? "Node" : node.optString("class_type", "Node");
        return "#" + id + "  " + prettify(cls) + "  ·  " + fieldKeyPreview(inputs);
    }

    private String fieldKeyPreview(JSONObject inputs) {
        List<String> keys = new ArrayList<>();
        for (String k : inputKeys(inputs)) if (inputs != null && primitive(inputs.opt(k))) keys.add(k);
        if (keys.isEmpty()) return "image";
        String out = "";
        for (int i = 0; i < keys.size() && i < 3; i++) out += (i == 0 ? "" : ", ") + keys.get(i);
        if (keys.size() > 3) out += "…";
        return out;
    }

    private void addControlGroup(LinearLayout c, String id) {
        JSONObject node = workflow.optJSONObject(id); if (node == null) return;
        JSONObject inputs = node.optJSONObject("inputs"); if (inputs == null) return;
        String cls = node.optString("class_type", "Node");
        TextView h = fieldLabel("#" + id + "  " + prettify(cls));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.setMargins(0, dp(12), 0, dp(8));
        c.addView(h, hp);
        if (isLoadImage(cls)) addImageControl(c, id, inputs);
        for (String key : inputKeys(inputs)) {
            Object value = inputs.opt(key);
            if (!primitive(value)) continue;
            if (isLoadImage(cls) && key.equals(imageKey(inputs))) continue;
            addField(c, id, key, value, fieldTitle(key));
        }
    }

    private void generateCard() {
        LinearLayout c = card(); content.addView(c, cardParams());
        c.addView(header("Generate"));
        LinearLayout r = row(); c.addView(r);
        addCardButton(r, "Apply", false, () -> { applyFields(); saveWorkflow(); toast("Applied"); renderStudio(); });
        addCardButton(r, "Run", true, this::runWorkflow);
        Button out = button("Open latest output", Color.rgb(51, 65, 85), 15, 14);
        out.setOnClickListener(v -> openOutput());
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, dp(52));
        op.setMargins(dp(3), dp(10), dp(3), dp(8));
        c.addView(out, op);
        generationText = muted(generationRunning ? "Generation running..." : generationIdleText());
        c.addView(generationText);
        generationBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        generationBar.setMax(100);
        c.addView(generationBar, new LinearLayout.LayoutParams(-1, dp(10)));
        refreshGenerationUi();
    }

    private void workflowMapCard() {
        List<String> ids = visibleNodeIds();
        LinearLayout c = card(); content.addView(c, cardParams());
        c.addView(header("Nodes"));
        c.addView(muted("All nodes. Use this only for technical inspection."));
        if (ids.isEmpty()) return;
        LinearLayout actions = row(); c.addView(actions);
        addCardButton(actions, "First editable", false, () -> { applyFields(); selectedMapNodeId = firstEditableNodeId(ids); renderStudio(); });
        addCardButton(actions, "Next editable", true, () -> { applyFields(); selectedMapNodeId = nextEditableNodeId(ids, selectedMapNodeId); renderStudio(); });
        LinearLayout tileRow = null;
        for (int i = 0; i < ids.size(); i++) {
            if (i % 2 == 0) { tileRow = row(); tileRow.setPadding(0, dp(6), 0, 0); c.addView(tileRow); }
            addNodeTile(tileRow, ids.get(i));
        }
        if (ids.size() % 2 == 1 && tileRow != null) tileRow.addView(new View(this), weight(dp(86)));
    }

    private void addNodeTile(LinearLayout parent, String id) {
        JSONObject node = workflow.optJSONObject(id); if (node == null) return;
        JSONObject inputs = node.optJSONObject("inputs");
        String cls = node.optString("class_type", "Node");
        int edit = editableCount(id, inputs);
        int dd = dropdownCount(id, inputs);
        boolean selected = id.equals(selectedMapNodeId);
        int color = selected ? Color.rgb(37, 99, 235) : (edit > 0 ? Color.rgb(15, 23, 42) : Color.rgb(17, 24, 39));
        Button b = button("#" + id + "\n" + shorten(prettify(cls), 24) + "\nedit " + edit + "   list " + dd, color, 12, 14);
        b.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        b.setPadding(dp(10), 0, dp(8), 0);
        b.setOnClickListener(v -> { applyFields(); saveWorkflow(); selectedMapNodeId = id; renderStudio(); });
        parent.addView(b, weight(dp(86)));
    }

    private void selectedNodeCard() {
        if (selectedMapNodeId == null || workflow == null) return;
        JSONObject node = workflow.optJSONObject(selectedMapNodeId); if (node == null) return;
        JSONObject inputs = node.optJSONObject("inputs");
        String cls = node.optString("class_type", "Node");
        LinearLayout c = card(); content.addView(c, cardParams());
        c.addView(header("#" + selectedMapNodeId + "  " + prettify(cls)));
        c.addView(muted("Inspector. Main editing is in Create."));
        boolean added = false;
        for (String key : inputKeys(inputs)) {
            Object value = inputs.opt(key);
            if (!primitive(value)) continue;
            addField(c, selectedMapNodeId, key, value, fieldTitle(key));
            added = true;
        }
        boolean linked = addLinkedInputs(c, inputs);
        if (!added && !linked) c.addView(muted("No direct editable fields or linked inputs in this node."));
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
            String cls = src == null ? "Node" : src.optString("class_type", "Node");
            if (!linked) { c.addView(fieldLabel("Linked inputs")); linked = true; }
            Button jump = button(key + "  ←  #" + ref + " " + shorten(prettify(cls), 34), Color.rgb(51, 65, 85), 14, 14);
            jump.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            jump.setPadding(dp(14), 0, dp(14), 0);
            jump.setOnClickListener(v -> { applyFields(); saveWorkflow(); selectedMapNodeId = ref; renderStudio(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52));
            p.setMargins(0, 0, 0, dp(10));
            c.addView(jump, p);
        }
        return linked;
    }

    private void addImageControl(LinearLayout c, String id, JSONObject inputs) {
        String key = imageKey(inputs);
        c.addView(fieldLabel("Image  ·  " + key));
        Button choose = button("Choose image from phone", Color.rgb(37, 99, 235), 15, 14);
        choose.setOnClickListener(v -> chooseImage(id, key));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(58));
        p.setMargins(0, 0, 0, dp(8));
        c.addView(choose, p);
        Object current = inputs.opt(key);
        if (current instanceof String && !String.valueOf(current).trim().isEmpty()) c.addView(muted("Current: " + shorten(String.valueOf(current), 80)));
    }

    private void addField(LinearLayout c, String id, String key, Object value, String title) {
        c.addView(fieldLabel(title));
        JSONArray opts = optionValues(id, key, value);
        if (opts != null && opts.length() > 0) {
            Button b = button(String.valueOf(value) + "  ▼", Color.rgb(15, 23, 42), 16, 14);
            b.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            b.setPadding(dp(14), 0, dp(14), 0);
            b.setOnClickListener(v -> showOptionsPicker(id, key, opts));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(58));
            p.setMargins(0, 0, 0, dp(14));
            c.addView(b, p);
            return;
        }
        EditText e = new EditText(this);
        soft(e, 16);
        e.setText(value == JSONObject.NULL ? "" : String.valueOf(value));
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.rgb(148, 163, 184));
        e.setPadding(dp(12), 0, dp(12), 0);
        e.setBackground(bg(Color.rgb(15, 23, 42), dp(14), Color.rgb(30, 41, 59), 1));
        boolean multi = key.toLowerCase().contains("prompt") || key.toLowerCase().contains("text") || key.toLowerCase().contains("value") || String.valueOf(value).length() > 60;
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
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, multi ? dp(126) : dp(56));
        p.setMargins(0, 0, 0, dp(14));
        c.addView(e, p);
        fields.add(new ApiField(id, key, e));
    }

    private void showOptionsPicker(String node, String key, JSONArray opts) {
        String[] items = new String[opts.length()];
        for (int i = 0; i < opts.length(); i++) items[i] = opts.optString(i, "");
        new AlertDialog.Builder(this).setTitle(human(key)).setItems(items, (d, which) -> {
            setInput(node, key, coerce(items[which]));
            saveWorkflow();
            toast("Selected: " + items[which]);
            renderStudio();
        }).show();
    }

    private void showCreate() {
        screen = "create";
        studioPane.setVisibility(View.VISIBLE);
        graph.setVisibility(View.GONE);
        output.setVisibility(View.GONE);
        renderStudio();
        status.setText("Create screen. Tap this line to show/hide URL panel.");
        applyBars();
    }

    private void showMap() {
        screen = "map";
        studioPane.setVisibility(View.VISIBLE);
        graph.setVisibility(View.GONE);
        output.setVisibility(View.GONE);
        renderStudio();
        status.setText("Map screen. Tap this line to show/hide URL panel.");
        applyBars();
    }

    private void showGraph() {
        saveUrl();
        studioPane.setVisibility(View.GONE);
        output.setVisibility(View.GONE);
        graph.setVisibility(View.VISIBLE);
        topPanel.setVisibility(View.GONE);
        String base = baseUrl();
        if (base.isEmpty()) { toast("Enter ComfyUI URL first"); return; }
        String cur = graph.getUrl();
        if (cur == null || !cur.startsWith(base) || cur.contains("/view")) graph.loadUrl(base);
        status.setText("Graph mode. Load workflow, wait until visible, then Import.");
        applyBars();
    }

    private void showOutput(String url) {
        topPanel.setVisibility(View.GONE);
        studioPane.setVisibility(View.GONE);
        graph.setVisibility(View.GONE);
        output.setVisibility(View.VISIBLE);
        output.loadUrl(url);
        status.setText("Opening output inside the app...");
        applyBars();
    }

    private void importFromGraph() {
        saveUrl();
        String base = baseUrl();
        if (base.isEmpty()) { toast("Enter ComfyUI URL first"); return; }
        String cur = graph.getUrl();
        if (cur == null || !cur.startsWith(base) || cur.contains("/view")) {
            showGraph();
            toast("Graph opened. Load workflow, then press Import again.");
            return;
        }
        importing = true;
        busy(true, "Importing workflow from Graph...");
        handler.postDelayed(() -> {
            if (importing) {
                importing = false;
                busy(false, "Import timed out. Wait until workflow is visible, then Import again.");
            }
        }, 15000);
        graph.evaluateJavascript(importScript(), null);
    }

    private String importScript() {
        return "(async function(){"
                + "function send(o){try{window.ComfyRemoteBridge.onImportResult(JSON.stringify(o));}catch(e){}}"
                + "function graphObj(){return (window.app&&app.graph)||window.graph||((window.LGraphCanvas&&window.LGraphCanvas.active_canvas)&&window.LGraphCanvas.active_canvas.graph);}"
                + "function prim(v){return v===null||['string','number','boolean'].indexOf(typeof v)>=0;}"
                + "function li(g,id){var links=(g&&g.links)||{};var l=links[id];if(!l&&Array.isArray(links)){for(var i=0;i<links.length;i++){if(links[i]&&(links[i].id==id||links[i][0]==id)){l=links[i];break;}}}if(!l)return null;if(Array.isArray(l))return {o:String(l[1]),s:Number(l[2]||0)};return {o:String(l.origin_id||l.source_id||l.from_id||l.origin||''),s:Number(l.origin_slot||l.source_slot||l.from_slot||0)};}"
                + "function opts(w){var o=w&&w.options;var a=null;if(o){if(Array.isArray(o.values))a=o.values;else if(Array.isArray(o))a=o;}if(!a&&Array.isArray(w&&w.values))a=w.values;return a&&a.length?a.map(function(x){return String(x);}):null;}"
                + "function fallback(){var g=graphObj();if(!g)return {ok:false,error:'Graph object not found'};var nodes=g._nodes||g.nodes||[];var out={},options={};for(var n of nodes){if(!n||n.id==null)continue;var cls=String(n.type||n.comfyClass||n.title||'');var low=cls.toLowerCase();if(!cls||low.indexOf('note')>=0||low.indexOf('markdown')>=0)continue;var item={class_type:cls,inputs:{}};for(var inp of (n.inputs||[])){if(inp&&inp.link!=null&&inp.name){var x=li(g,inp.link);if(x&&x.o)item.inputs[String(inp.name)]=[x.o,x.s];}}for(var w of (n.widgets||[])){if(!w||!w.name)continue;var name=String(w.name);var type=String(w.type||'').toLowerCase();if(name==='upload'||type==='button')continue;var os=opts(w);if(os)options[String(n.id)+':'+name]=os;if(prim(w.value))item.inputs[name]=w.value;}out[String(n.id)]=item;}return {ok:Object.keys(out).length>0,prompt:out,options:options,mode:'graph fallback'};}"
                + "try{var f=fallback();if(window.app&&app.graphToPrompt){try{var gp=app.graphToPrompt();if(gp&&typeof gp.then==='function')gp=await gp;var p=gp&&(gp.output||gp.prompt||gp);if(p&&Object.keys(p).length){send({ok:true,prompt:p,options:f.options||{},mode:'graphToPrompt'});return;}}catch(e){}}send(f);}catch(e){send({ok:false,error:String(e&&e.message?e.message:e)});}"
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
            fieldOptions = res.optJSONObject("options");
            if (fieldOptions == null) fieldOptions = new JSONObject();
            saveWorkflow(); saveOptions(); selectedCategory = bestDefaultCategory(); selectedControlNodeId = null; selectedMapNodeId = null;
            String problem = workflowProblem(workflow);
            busy(false, "Imported " + workflow.length() + " nodes via " + res.optString("mode", "Graph") + (problem == null ? "." : ". Warning: " + problem));
            showCreate();
        } catch (Exception e) { busy(false, "Import failed: " + shortError(e)); }
    }

    private void chooseJson() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(Intent.createChooser(i, "Choose workflow JSON"), REQ_JSON); }
        catch (Exception e) { toast("No file picker available"); }
    }

    private void applyJson() {
        try {
            workflow = cleanWorkflow(new JSONObject(jsonEditor == null ? "" : jsonEditor.getText().toString()));
            fieldOptions = new JSONObject(); saveWorkflow(); saveOptions(); selectedCategory = bestDefaultCategory(); selectedControlNodeId = null; selectedMapNodeId = null;
            toast("Workflow loaded"); renderStudio();
        } catch (JSONException e) { toast("Invalid JSON"); }
    }
    private void applyJsonText(String raw) {
        try { workflow = cleanWorkflow(new JSONObject(raw)); fieldOptions = new JSONObject(); saveWorkflow(); saveOptions(); selectedCategory = bestDefaultCategory(); selectedControlNodeId = null; selectedMapNodeId = null; toast("Workflow loaded"); renderStudio(); }
        catch (JSONException e) { toast("Invalid JSON file"); }
    }

    private void chooseImage(String node, String key) {
        if (key == null || key.trim().isEmpty()) { toast("Image input not found"); return; }
        pendingNode = node; pendingKey = key;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(Intent.createChooser(i, "Choose image"), REQ_IMAGE); }
        catch (Exception e) { pendingNode = null; pendingKey = null; toast("No image picker available"); }
    }

    private void uploadImage(Uri uri, String node, String key) {
        if (uri == null || node == null || key == null) return;
        String base = baseUrl();
        if (base.isEmpty()) { toast("Enter ComfyUI URL first"); return; }
        busy(true, "Uploading image...");
        new Thread(() -> {
            try {
                String uploaded = uploadMultipart(base, readBytes(uri), displayName(uri), getContentResolver().getType(uri));
                setInput(node, key, uploaded); saveWorkflow();
                handler.post(() -> { busy(false, "Uploaded image: " + uploaded); renderStudio(); });
            } catch (Exception e) { handler.post(() -> busy(false, "Image upload failed: " + shortError(e))); }
        }).start();
    }

    private void runWorkflow() {
        if (workflow == null) { toast("Import a workflow first"); return; }
        saveUrl(); applyFields(); workflow = cleanWorkflow(workflow); saveWorkflow();
        String problem = workflowProblem(workflow);
        if (problem != null) { busy(false, "Run blocked: " + problem + " Re-import using Graph."); return; }
        String base = baseUrl();
        if (base.isEmpty()) { toast("Enter ComfyUI URL first"); return; }
        try {
            JSONObject payload = new JSONObject();
            payload.put("prompt", cleanWorkflow(workflow));
            payload.put("client_id", "android-remote-" + System.currentTimeMillis());
            generationRunning = true; generationStartMs = System.currentTimeMillis(); pollCount = 0;
            updateGenerationUi(8, "Sending prompt..."); busy(true, "Sending prompt...");
            new Thread(() -> {
                try {
                    JSONObject res = new JSONObject(postJson(base + "/prompt", payload.toString()));
                    currentPromptId = res.optString("prompt_id", "");
                    if (currentPromptId.isEmpty()) throw new IllegalStateException("ComfyUI did not return prompt_id");
                    handler.post(() -> { screen = "create"; showCreate(); busy(false, "Queued. Waiting for output..."); updateGenerationUi(15, "Queued."); pollHistory(); });
                } catch (Exception e) { handler.post(() -> { generationRunning = false; busy(false, "Run failed: " + shortError(e)); updateGenerationUi(0, "Run failed: " + shortError(e)); }); }
            }).start();
        } catch (JSONException e) { toast("Could not build prompt JSON"); }
    }

    private void pollHistory() {
        if (currentPromptId == null || currentPromptId.isEmpty()) return;
        pollCount++;
        String base = baseUrl(); String pid = currentPromptId;
        updateGenerationUi(estimatedProgressPercent(), generationProgressText());
        status.setText("Generating... " + generationProgressText());
        new Thread(() -> {
            try {
                OutputFile f = findOutput(new JSONObject(getText(base + "/history/" + enc(pid))));
                if (f != null) {
                    lastOutputUrl = base + "/view?filename=" + enc(f.filename) + "&type=" + enc(f.type) + "&subfolder=" + enc(f.subfolder);
                    lastGenerationDurationMs = Math.max(1L, System.currentTimeMillis() - generationStartMs);
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_OUTPUT, lastOutputUrl).putLong(KEY_LAST_DURATION, lastGenerationDurationMs).apply();
                    handler.post(() -> { generationRunning = false; busy(false, "Output ready."); updateGenerationUi(100, "Output ready. Generation took " + fmtMs(lastGenerationDurationMs) + "."); });
                    return;
                }
            } catch (Exception ignored) {}
            if (pollCount < 240) handler.postDelayed(this::pollHistory, 2000);
            else handler.post(() -> { generationRunning = false; busy(false, "Timed out waiting for output."); updateGenerationUi(0, "Timed out waiting for output."); });
        }).start();
    }

    private void openOutput() {
        if (lastOutputUrl != null && !lastOutputUrl.trim().isEmpty()) { showOutput(lastOutputUrl); return; }
        String base = baseUrl();
        if (base.isEmpty()) { toast("Enter ComfyUI URL first"); return; }
        busy(true, "Finding latest output...");
        new Thread(() -> {
            try {
                OutputFile f = findOutput(new JSONObject(getText(base + "/history")));
                if (f == null) throw new IllegalStateException("No output found in /history");
                lastOutputUrl = base + "/view?filename=" + enc(f.filename) + "&type=" + enc(f.type) + "&subfolder=" + enc(f.subfolder);
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_OUTPUT, lastOutputUrl).apply();
                handler.post(() -> { busy(false, "Opening output."); showOutput(lastOutputUrl); });
            } catch (Exception e) { handler.post(() -> busy(false, "No output found: " + shortError(e))); }
        }).start();
    }

    private void testConnection() {
        saveUrl(); String base = baseUrl();
        if (base.isEmpty()) { toast("Enter ComfyUI URL first"); return; }
        busy(true, "Testing...");
        new Thread(() -> {
            try { getText(base + "/system_stats"); handler.post(() -> busy(false, "Connection OK.")); }
            catch (Exception e) { handler.post(() -> busy(false, "Connection failed: " + shortError(e))); }
        }).start();
    }

    private JSONObject cleanWorkflow(JSONObject src) {
        JSONObject out = new JSONObject(); if (src == null) return out;
        Iterator<String> it = src.keys();
        while (it.hasNext()) {
            String id = it.next(); JSONObject node = src.optJSONObject(id);
            if (node == null) continue;
            if (isNonRunnable(node.optString("class_type", node.optString("type", "")))) continue;
            try { out.put(id, node); } catch (JSONException ignored) {}
        }
        return out;
    }

    private String workflowProblem(JSONObject wf) {
        if (wf == null || wf.length() == 0) return "workflow is empty.";
        Iterator<String> it = wf.keys();
        while (it.hasNext()) {
            String id = it.next(); JSONObject node = wf.optJSONObject(id); JSONObject inputs = node == null ? null : node.optJSONObject("inputs");
            if (inputs == null) continue;
            for (String k : inputKeys(inputs)) {
                JSONArray arr = inputs.optJSONArray(k);
                if (arr != null && arr.length() > 0) {
                    String ref = arr.optString(0, "");
                    if (!ref.isEmpty() && !wf.has(ref)) return "node #" + id + " input " + k + " points to missing node #" + ref + ".";
                }
            }
        }
        return null;
    }

    private String[] categoryOrder() { return new String[]{CAT_TEXT, CAT_MODELS, CAT_SAMPLING, CAT_VIDEO, CAT_INPUTS, CAT_OTHER}; }
    private void ensureUsefulCategory() { if (categoryNodeIds(selectedCategory).isEmpty()) selectedCategory = bestDefaultCategory(); }
    private String bestDefaultCategory() { for (String c : categoryOrder()) if (!categoryNodeIds(c).isEmpty()) return c; return CAT_OTHER; }
    private String categoryTitle(String cat) {
        if (CAT_TEXT.equals(cat)) return "Prompt / Text";
        if (CAT_MODELS.equals(cat)) return "Models / LoRA";
        if (CAT_SAMPLING.equals(cat)) return "Sampling";
        if (CAT_VIDEO.equals(cat)) return "Video / Size";
        if (CAT_INPUTS.equals(cat)) return "Inputs";
        return "Other";
    }
    private List<String> categoryNodeIds(String cat) {
        List<String> ids = new ArrayList<>();
        for (String id : editableNodeIds()) if (nodeMatchesCategory(id, cat)) ids.add(id);
        return ids;
    }
    private boolean nodeMatchesCategory(String id, String cat) {
        JSONObject node = workflow == null ? null : workflow.optJSONObject(id);
        if (node == null) return false;
        JSONObject inputs = node.optJSONObject("inputs");
        String cls = node.optString("class_type", "Node");
        String all = cls.toLowerCase();
        for (String k : inputKeys(inputs)) if (inputs != null && primitive(inputs.opt(k))) all += " " + k.toLowerCase();
        if (CAT_TEXT.equals(cat)) return has(all, "prompt", "text", "string", "positive", "negative", "caption");
        if (CAT_MODELS.equals(cat)) return has(all, "lora", "ckpt", "checkpoint", "model", "vae", "clip");
        if (CAT_SAMPLING.equals(cat)) return has(all, "seed", "noise", "sampler", "scheduler", "step", "cfg", "denoise", "sigma", "ksampler", "random");
        if (CAT_VIDEO.equals(cat)) return has(all, "width", "height", "latent", "video", "frame", "fps", "duration", "batch", "length", "strength", "compression", "resize", "scale");
        if (CAT_INPUTS.equals(cat)) return isLoadImage(cls) || has(all, "image", "upload", "input");
        return !nodeMatchesCategory(id, CAT_TEXT) && !nodeMatchesCategory(id, CAT_MODELS) && !nodeMatchesCategory(id, CAT_SAMPLING) && !nodeMatchesCategory(id, CAT_VIDEO) && !nodeMatchesCategory(id, CAT_INPUTS);
    }
    private boolean has(String s, String... parts) { for (String p : parts) if (s.contains(p)) return true; return false; }

    private List<String> nodeIds() {
        List<String> k = new ArrayList<>(); if (workflow == null) return k;
        Iterator<String> it = workflow.keys(); while (it.hasNext()) k.add(it.next());
        Collections.sort(k, (a, b) -> { try { return Integer.compare(Integer.parseInt(a), Integer.parseInt(b)); } catch (Exception e) { return a.compareTo(b); } });
        return k;
    }
    private List<String> visibleNodeIds() { List<String> ids = new ArrayList<>(); for (String id : nodeIds()) { JSONObject node = workflow.optJSONObject(id); if (node == null) continue; String cls = node.optString("class_type", "Node"); if (!isNonRunnable(cls) && !isReroute(cls)) ids.add(id); } return ids; }
    private List<String> editableNodeIds() { List<String> ids = new ArrayList<>(); for (String id : visibleNodeIds()) { JSONObject node = workflow.optJSONObject(id); JSONObject inputs = node == null ? null : node.optJSONObject("inputs"); if (editableCount(id, inputs) > 0 || (node != null && isLoadImage(node.optString("class_type", "")))) ids.add(id); } return ids; }
    private List<String> inputKeys(JSONObject o) { List<String> k = new ArrayList<>(); if (o == null) return k; Iterator<String> it = o.keys(); while (it.hasNext()) k.add(it.next()); Collections.sort(k); return k; }
    private JSONArray optionValues(String node, String key, Object value) { JSONArray saved = fieldOptions == null ? null : fieldOptions.optJSONArray(node + ":" + key); if (saved != null && saved.length() > 0) return saved; if (value instanceof Boolean) return jsonArray("[\"true\",\"false\"]"); return commonOptions(key); }
    private JSONArray commonOptions(String key) { String k = key == null ? "" : key.toLowerCase(); if (k.equals("sampler_name") || k.equals("sampler")) return jsonArray("[\"euler\",\"euler_ancestral\",\"heun\",\"dpm_2\",\"dpm_2_ancestral\",\"lms\",\"dpm_fast\",\"dpm_adaptive\",\"dpmpp_2s_ancestral\",\"dpmpp_sde\",\"dpmpp_2m\",\"ddim\",\"uni_pc\"]"); if (k.equals("scheduler")) return jsonArray("[\"normal\",\"karras\",\"exponential\",\"sgm_uniform\",\"simple\",\"ddim_uniform\",\"beta\"]"); return null; }
    private JSONArray jsonArray(String s) { try { return new JSONArray(s); } catch (JSONException e) { return null; } }
    private boolean primitive(Object v) { return v == JSONObject.NULL || v instanceof String || v instanceof Number || v instanceof Boolean; }
    private int editableCount(String id, JSONObject inputs) { int n = 0; for (String k : inputKeys(inputs)) if (primitive(inputs.opt(k))) n++; return n; }
    private int dropdownCount(String id, JSONObject inputs) { int n = 0; for (String k : inputKeys(inputs)) { Object v = inputs.opt(k); if (primitive(v) && optionValues(id, k, v) != null) n++; } return n; }
    private String firstEditableNodeId(List<String> ids) { for (String id : ids) { JSONObject node = workflow.optJSONObject(id); JSONObject inputs = node == null ? null : node.optJSONObject("inputs"); if (editableCount(id, inputs) > 0) return id; } return ids.isEmpty() ? null : ids.get(0); }
    private String nextEditableNodeId(List<String> ids, String cur) { return nextId(ids, cur); }
    private String nextId(List<String> ids, String cur) { if (ids.isEmpty()) return null; int idx = ids.indexOf(cur); return ids.get((idx < 0 ? 0 : idx + 1) % ids.size()); }
    private boolean isNonRunnable(String cls) { String s = cls == null ? "" : cls.toLowerCase(); return s.contains("note") || s.contains("markdown") || s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"); }
    private boolean isReroute(String cls) { String s = cls == null ? "" : cls.toLowerCase(); return s.contains("reroute"); }
    private boolean isLoadImage(String c) { String s = c == null ? "" : c.toLowerCase(); return s.contains("loadimage") || s.contains("load image"); }
    private boolean numericKey(String k) { String s = k.toLowerCase(); return s.contains("width") || s.contains("height") || s.contains("step") || s.contains("seed") || s.contains("cfg") || s.contains("duration") || s.contains("batch") || s.contains("fps") || s.contains("frame") || s.equals("denoise") || s.equals("length"); }
    private String imageKey(JSONObject inputs) { for (String k : inputKeys(inputs)) if (k.equalsIgnoreCase("image") || k.toLowerCase().contains("image")) return k; return "image"; }
    private String human(String k) { String s = k.toLowerCase(); if (s.equals("ckpt_name")) return "Checkpoint"; if (s.equals("lora_name")) return "LoRA"; if (s.equals("seed") || s.equals("noise_seed")) return "Seed"; if (s.equals("steps")) return "Steps"; if (s.equals("cfg")) return "CFG"; if (s.equals("width")) return "Width"; if (s.equals("height")) return "Height"; if (s.equals("filename_prefix")) return "Filename prefix"; if (s.equals("image")) return "Image"; if (s.equals("text") || s.equals("prompt") || s.equals("positive")) return "Prompt"; if (s.equals("negative")) return "Negative prompt"; return prettify(k.replace('_', ' ')); }
    private String fieldTitle(String k) { String h = human(k); String raw = prettify(k.replace('_', ' ')); return h.equalsIgnoreCase(raw) ? k : h + "  ·  " + k; }
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
    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(0, dp(8), 0, 0); return r; }
    private void addTopButton(LinearLayout parent, String label, Runnable action) { Button b = button(label, Color.rgb(51, 65, 85), 14, 14); b.setSingleLine(true); b.setOnClickListener(v -> action.run()); parent.addView(b, weight(dp(46))); }
    private void addToolButton(LinearLayout parent, String label, Runnable action) { Button b = button(label, Color.rgb(30, 41, 59), 12, 16); b.setSingleLine(true); b.setOnClickListener(v -> action.run()); parent.addView(b, weight(dp(52))); }
    private void addCardButton(LinearLayout r, String text, boolean primary, Runnable action) { Button b = button(text, primary ? Color.rgb(37, 99, 235) : Color.rgb(51, 65, 85), 15, 14); b.setOnClickListener(v -> action.run()); r.addView(b, weight(dp(50))); }
    private LinearLayout card() { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(14), dp(14), dp(14), dp(14)); c.setBackground(bg(Color.rgb(30, 41, 59), dp(22), Color.rgb(71, 85, 105), 1)); return c; }
    private LinearLayout cardAccent() { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(14), dp(14), dp(14), dp(14)); c.setBackground(bg(Color.rgb(24, 41, 65), dp(22), Color.rgb(96, 165, 250), 2)); return c; }
    private LinearLayout.LayoutParams cardParams() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, 0, 0, dp(16)); return p; }
    private LinearLayout.LayoutParams weight(int h) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, h, 1); p.setMargins(dp(3), 0, dp(3), 0); return p; }
    private TextView text(String t, int size, int color) { TextView v = new TextView(this); v.setText(t); v.setTextSize(size); v.setTextColor(color); v.setPadding(dp(2), 0, dp(2), dp(8)); soft(v, size); return v; }
    private TextView header(String t) { return text(t, 19, Color.WHITE); }
    private TextView fieldLabel(String t) { TextView v = text(t, 14, Color.rgb(219, 234, 254)); v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); v.setPadding(dp(10), dp(6), dp(10), dp(6)); v.setBackground(bg(Color.rgb(15, 23, 42), dp(10), Color.rgb(59, 130, 246), 1)); return v; }
    private TextView muted(String t) { return text(t, 14, Color.rgb(148, 163, 184)); }
    private Button button(String t, int color, int size, int radius) { Button b = new Button(this); b.setText(t); b.setAllCaps(false); b.setSingleLine(false); b.setTextColor(Color.WHITE); b.setTextSize(size); b.setIncludeFontPadding(false); b.setGravity(Gravity.CENTER); b.setPadding(dp(6), 0, dp(6), 0); b.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); b.setBackground(bg(color, dp(radius), Color.rgb(71, 85, 105), 1)); return b; }
    private void soft(TextView v, int size) { v.setTextSize(size); v.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); v.setIncludeFontPadding(true); if (android.os.Build.VERSION.SDK_INT >= 21) v.setLetterSpacing(0.01f); }
    private GradientDrawable bg(int color, int radius, int strokeColor, int strokeDp) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); d.setStroke(dp(strokeDp), strokeColor); return d; }
    private void busy(boolean on, String msg) { busyBar.setVisibility(on ? View.VISIBLE : View.GONE); status.setText(msg); }
    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }
    private void toggleTopPanel() { boolean show = topPanel.getVisibility() != View.VISIBLE; topPanel.setVisibility(show ? View.VISIBLE : View.GONE); status.setText(show ? "URL panel shown. Tap here to hide it." : "URL panel hidden. Tap here to show it."); applyBars(); }
    private void injectGraphCss() { graph.evaluateJavascript("(function(){var m=document.querySelector('meta[name=viewport]')||document.createElement('meta');m.name='viewport';m.content='width=device-width,initial-scale=1,minimum-scale=.35,maximum-scale=3,user-scalable=yes';document.head.appendChild(m);})()", null); }
    private void applyBars() { Window w = getWindow(); w.setStatusBarColor(Color.rgb(2, 6, 23)); w.setNavigationBarColor(Color.rgb(15, 23, 42)); w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE); }
    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) applyBars(); }
    @Override protected void onActivityResult(int req, int result, Intent data) { if (req == REQ_WEB_FILE) { if (webFileCallback != null) { webFileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data)); webFileCallback = null; } applyBars(); return; } if (req == REQ_IMAGE) { String n = pendingNode, k = pendingKey; pendingNode = null; pendingKey = null; if (result == RESULT_OK && data != null && data.getData() != null) uploadImage(data.getData(), n, k); else status.setText("Image selection cancelled."); applyBars(); return; } if (req == REQ_JSON) { if (result == RESULT_OK && data != null && data.getData() != null) { try { applyJsonText(readText(data.getData())); } catch (Exception e) { toast("Could not read JSON"); } } applyBars(); return; } super.onActivityResult(req, result, data); }
    @Override public void onBackPressed() { if (output.getVisibility() == View.VISIBLE) { showCreate(); return; } if (graph.getVisibility() == View.VISIBLE) { if (graph.canGoBack()) graph.goBack(); else showCreate(); return; } super.onBackPressed(); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
