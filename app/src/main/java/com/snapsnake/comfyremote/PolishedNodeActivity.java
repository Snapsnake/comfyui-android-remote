package com.snapsnake.comfyremote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
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
import android.widget.ImageView;
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
    private String selectedNodeId, currentPromptId, lastOutputUrl;
    private long generationStartMs = 0L, lastGenerationDurationMs = 0L;
    private int pollCount = 0;
    private boolean importing = false, generationRunning = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private static class ApiField {
        final String node;
        final String key;
        final EditText edit;
        ApiField(String node, String key, EditText edit) { this.node = node; this.key = key; this.edit = edit; }
    }

    private static class OutputFile {
        final String filename;
        final String subfolder;
        final String type;
        OutputFile(String filename, String subfolder, String type) {
            this.filename = filename;
            this.subfolder = subfolder;
            this.type = type;
        }
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
        root.setBackgroundColor(bgRoot());

        topPanel = new LinearLayout(this);
        topPanel.setOrientation(LinearLayout.VERTICAL);
        topPanel.setPadding(dp(18), dp(12), dp(18), dp(12));
        topPanel.setBackgroundColor(surface());
        root.addView(topPanel, new LinearLayout.LayoutParams(-1, -2));

        TextView appTitle = titleText("ComfyUI Mobile", 18);
        topPanel.addView(appTitle);

        urlInput = input("http://desktop-name.tailnet.ts.net:8188", true);
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(-1, dp(48));
        urlLp.setMargins(0, dp(8), 0, dp(8));
        topPanel.addView(urlInput, urlLp);

        LinearLayout topRow = row();
        topPanel.addView(topRow, new LinearLayout.LayoutParams(-1, dp(44)));
        topRow.addView(actionButton("Test", false, this::testConnection), weight(dp(44)));
        topRow.addView(actionButton("Create", false, this::showCreate), weight(dp(44)));
        topRow.addView(actionButton("Nodes", false, this::showNodes), weight(dp(44)));
        topRow.addView(actionButton("Import", true, this::importFromGraph), weight(dp(44)));

        busyBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        busyBar.setMax(100);
        busyBar.setVisibility(View.GONE);
        root.addView(busyBar, new LinearLayout.LayoutParams(-1, dp(3)));

        status = new TextView(this);
        status.setVisibility(View.GONE);
        root.addView(status, new LinearLayout.LayoutParams(0, 0));

        workspace = new FrameLayout(this);
        workspace.setBackgroundColor(bgRoot());
        root.addView(workspace, new LinearLayout.LayoutParams(-1, 0, 1));

        pane = new ScrollView(this);
        pane.setFillViewport(false);
        pane.setVerticalScrollBarEnabled(false);
        pane.setOverScrollMode(View.OVER_SCROLL_NEVER);
        pane.setBackgroundColor(bgRoot());
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(18), dp(22), dp(22));
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
        bottomNav.setPadding(dp(14), dp(8), dp(14), dp(8));
        bottomNav.setBackgroundColor(surface());
        root.addView(bottomNav, new LinearLayout.LayoutParams(-1, dp(78)));

        setContentView(root);
    }

    private void loadPrefs() {
        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        urlInput.setText(p.getString(KEY_URL, "http://desktop-name.tailnet.ts.net:8188"));
        lastOutputUrl = p.getString(KEY_OUTPUT, "");
        lastGenerationDurationMs = p.getLong(KEY_LAST_DURATION, 0L);
        if (p.contains(KEY_URL)) topPanel.setVisibility(View.GONE);
        try {
            workflow = cleanWorkflow(new JSONObject(p.getString(KEY_WORKFLOW, "{}")));
            if (workflow.length() == 0) workflow = null;
        } catch (Exception ignored) {
            workflow = null;
        }
        try { fieldOptions = new JSONObject(p.getString(KEY_OPTIONS, "{}")); }
        catch (Exception ignored) { fieldOptions = new JSONObject(); }
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
                catch (Exception e) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                }
                try { startActivityForResult(intent, REQ_WEB_FILE); return true; }
                catch (Exception e) { webFileCallback = null; toast("No file picker available"); return false; }
            }
        });
        graph.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) { busy(true, "Graph loading..."); }
            @Override public void onPageFinished(WebView view, String url) { busy(false, "Graph loaded. Load workflow, then Import."); injectGraphCss(); applyBars(); }
        });
        output.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) { busy(true, "Output loading..."); }
            @Override public void onPageFinished(WebView view, String url) { busy(false, "Output preview."); applyBars(); }
        });
    }

    private void configureWebView(WebView w) {
        WebSettings s = w.getSettings();
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
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        w.setBackgroundColor(bgRoot());
    }

    private void render() {
        fields.clear();
        content.removeAllViews();
        updateBottomNav();
        if ("run".equals(screen)) { renderRun(); return; }
        if ("nodes".equals(screen)) { renderNodes(); return; }
        renderCreate();
    }

    private void renderCreate() {
        content.addView(statusChip("●  Connected to ComfyUI Remote", "›", this::toggleTopPanel), sectionParams());
        content.addView(pageHeader("Create", workflow == null ? "Open Graph, load workflow, then Import." : workflow.length() + " nodes loaded. Edit, validate, or run."));
        if (workflow == null) renderNoWorkflowCard(); else renderWorkflowLoadedCard();
        renderTipCard();
    }

    private void renderNoWorkflowCard() {
        LinearLayout card = card(false);
        card.addView(cardTitle("‹/›", "Workflow"));

        LinearLayout buttons = row();
        buttons.setPadding(0, dp(10), 0, dp(10));
        buttons.addView(actionButton("▦  Templates", false, this::showTemplatesTab), weight(dp(54)));
        buttons.addView(actionButton("⇩  Import", true, this::importFromGraph), weight(dp(54)));
        card.addView(buttons);

        card.addView(label("Fallback: paste API workflow JSON"));
        jsonEditor = input("Paste or drop workflow JSON here…", false);
        jsonEditor.setGravity(Gravity.TOP | Gravity.LEFT);
        jsonEditor.setMinLines(6);
        jsonEditor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        card.addView(jsonEditor, new LinearLayout.LayoutParams(-1, dp(170)));

        LinearLayout jsonButtons = row();
        jsonButtons.setPadding(0, dp(12), 0, 0);
        jsonButtons.addView(actionButton("▱  Load JSON", false, this::chooseJson), weight(dp(52)));
        jsonButtons.addView(actionButton("✓  Apply JSON", true, this::applyJson), weight(dp(52)));
        card.addView(jsonButtons);
        content.addView(card, sectionParams());
    }

    private void renderWorkflowLoadedCard() {
        if (selectedNodeId == null || !visibleNodeIds().contains(selectedNodeId)) selectedNodeId = firstEditableOrFirst();
        LinearLayout loaded = card(false);
        loaded.setOrientation(LinearLayout.HORIZONTAL);
        loaded.setGravity(Gravity.CENTER_VERTICAL);

        ImageView img = new ImageView(this);
        img.setImageResource(R.drawable.ic_launcher);
        img.setPadding(dp(12), dp(12), dp(12), dp(12));
        img.setBackground(bg(surface2(), 10, stroke(), 1));
        loaded.addView(img, new LinearLayout.LayoutParams(dp(72), dp(72)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, -2, 1);
        bp.setMargins(dp(12), 0, dp(10), 0);
        loaded.addView(body, bp);
        body.addView(titleText(selectedNodeId == null ? "Workflow loaded" : nodeTitle(workflow.optJSONObject(selectedNodeId)), 15));
        body.addView(mutedText(workflow.length() + " nodes · ready to edit", 12));
        loaded.addView(actionButton("Change", false, this::showTemplatesTab), new LinearLayout.LayoutParams(dp(82), dp(42)));
        content.addView(loaded, sectionParams());

        renderSelectedEditor();
        renderGenerateCard();
    }

    private void renderSelectedEditor() {
        if (selectedNodeId == null) return;
        JSONObject node = workflow.optJSONObject(selectedNodeId);
        if (node == null) return;
        JSONObject inputs = node.optJSONObject("inputs");
        LinearLayout card = card(false);
        card.addView(cardTitle("⌘", nodeTitle(node)));
        card.addView(mutedText("#" + selectedNodeId + " · " + prettify(node.optString("class_type", "Node")), 12));
        boolean added = false;
        for (String key : inputKeys(inputs)) {
            Object value = inputs.opt(key);
            if (!primitive(value)) continue;
            addField(card, selectedNodeId, key, value, fieldTitle(key));
            added = true;
        }
        if (!added) card.addView(mutedText("No direct editable fields. Use Nodes to select another node.", 13));
        LinearLayout row = row();
        row.setPadding(0, dp(12), 0, 0);
        row.addView(actionButton("Choose node", false, this::showNodes), weight(dp(48)));
        row.addView(actionButton("Apply", true, () -> { applyFields(); saveWorkflow(); toast("Applied"); }), weight(dp(48)));
        card.addView(row);
        content.addView(card, sectionParams());
    }

    private void renderGenerateCard() {
        LinearLayout card = card(false);
        card.addView(cardTitle("▷", "Generate"));
        generationText = mutedText(generationRunning ? generationProgressText() : generationIdleText(), 13);
        card.addView(generationText);
        generationBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        generationBar.setMax(100);
        card.addView(generationBar, new LinearLayout.LayoutParams(-1, dp(6)));
        LinearLayout row = row();
        row.setPadding(0, dp(12), 0, 0);
        row.addView(actionButton("Open output", false, this::openOutput), weight(dp(52)));
        row.addView(actionButton("Run  ▷", true, this::runWorkflow), weight(dp(52)));
        card.addView(row);
        content.addView(card, sectionParams());
        refreshGenerationUi();
    }

    private void renderRun() {
        content.addView(statusChip("●  Idle • Ready to run", "›", this::toggleTopPanel), sectionParams());
        content.addView(pageHeader("Run", "Execute and review results."));

        LinearLayout loaded = card(false);
        loaded.setOrientation(LinearLayout.HORIZONTAL);
        loaded.setGravity(Gravity.CENTER_VERTICAL);
        ImageView thumb = new ImageView(this);
        thumb.setImageResource(R.drawable.ic_launcher);
        thumb.setPadding(dp(12), dp(12), dp(12), dp(12));
        thumb.setBackground(bg(surface2(), 10, stroke(), 1));
        loaded.addView(thumb, new LinearLayout.LayoutParams(dp(72), dp(72)));
        LinearLayout lt = new LinearLayout(this);
        lt.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ltp = new LinearLayout.LayoutParams(0, -2, 1);
        ltp.setMargins(dp(12), 0, dp(8), 0);
        loaded.addView(lt, ltp);
        lt.addView(titleText(workflow == null ? "No workflow loaded" : "Workflow loaded", 14));
        lt.addView(mutedText(workflow == null ? "Choose a template or import JSON" : "Ready to execute", 12));
        loaded.addView(actionButton("Change", false, this::showTemplatesTab), new LinearLayout.LayoutParams(dp(82), dp(42)));
        content.addView(loaded, sectionParams());

        LinearLayout preview = card(false);
        preview.setGravity(Gravity.CENTER);
        ImageView img = new ImageView(this);
        img.setImageResource(R.drawable.ic_launcher);
        img.setPadding(dp(46), dp(46), dp(46), dp(46));
        preview.addView(img, new LinearLayout.LayoutParams(-1, dp(240)));
        content.addView(preview, sectionParams());

        LinearLayout ready = card(false);
        ready.setOrientation(LinearLayout.HORIZONTAL);
        ready.setGravity(Gravity.CENTER_VERTICAL);
        ready.addView(badge("✓"), new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout rt = new LinearLayout(this);
        rt.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rtp = new LinearLayout.LayoutParams(0, -2, 1);
        rtp.setMargins(dp(10), 0, dp(8), 0);
        ready.addView(rt, rtp);
        rt.addView(titleText(workflow == null ? "No workflow loaded" : "Ready to run", 14));
        rt.addView(mutedText(workflow == null ? "Import or choose a template first" : "All local checks passed", 12));
        ready.addView(actionButton("Validate", true, () -> toast(workflow == null ? "No workflow" : "Looks valid")), new LinearLayout.LayoutParams(dp(92), dp(42)));
        content.addView(ready, sectionParams());

        LinearLayout metrics = row();
        metrics.addView(metric("Nodes", workflow == null ? "0" : String.valueOf(workflow.length())), weight(dp(58)));
        metrics.addView(metric("Models", "—"), weight(dp(58)));
        metrics.addView(metric("Steps", "—"), weight(dp(58)));
        metrics.addView(metric("Last", lastGenerationDurationMs > 0 ? fmtMs(lastGenerationDurationMs) : "—"), weight(dp(58)));
        content.addView(metrics, sectionParams());

        LinearLayout actions = row();
        actions.addView(actionButton("Queue Prompt", false, () -> toast("Queue uses Run")), weight(dp(54)));
        actions.addView(actionButton("Run  ▷", true, this::runWorkflow), weight(dp(54)));
        content.addView(actions, sectionParams());

        content.addView(titleText("Recent Output", 16));
    }

    private void renderNodes() {
        content.addView(statusChip("●  Nodes", "›", this::toggleTopPanel), sectionParams());
        content.addView(pageHeader("Nodes", "Tap a node to edit it."));
        if (workflow == null) { content.addView(mutedText("No workflow imported.", 14)); return; }
        for (String id : visibleNodeIds()) content.addView(nodeSummaryView(id, true), sectionParams());
    }

    private View nodeSummaryView(String id, boolean clickable) {
        LinearLayout card = card(false);
        JSONObject node = workflow == null ? null : workflow.optJSONObject(id);
        if (node == null) { card.addView(titleText("No node selected", 16)); return card; }
        card.addView(titleText(nodeTitle(node), 16));
        card.addView(mutedText("#" + id + " · " + prettify(node.optString("class_type", "Node")), 12));
        LinearLayout chips = row();
        chips.setPadding(0, dp(8), 0, 0);
        chips.addView(chip(editableCount(id, node.optJSONObject("inputs")) + " fields"), weight(dp(32)));
        chips.addView(chip(dropdownCount(id, node.optJSONObject("inputs")) + " lists"), weight(dp(32)));
        card.addView(chips);
        if (clickable) card.setOnClickListener(v -> { selectedNodeId = id; showCreate(); });
        return card;
    }

    private void addField(LinearLayout parent, String id, String key, Object value, String title) {
        TextView label = label(title);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(10), 0, dp(4));
        parent.addView(label, lp);
        EditText e = input("", false);
        e.setText(value == JSONObject.NULL ? "" : String.valueOf(value));
        boolean multi = key.toLowerCase().contains("prompt") || key.toLowerCase().contains("text") || String.valueOf(value).length() > 80;
        e.setSingleLine(!multi);
        e.setGravity(multi ? Gravity.TOP | Gravity.LEFT : Gravity.CENTER_VERTICAL | Gravity.LEFT);
        e.setInputType(multi ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        parent.addView(e, new LinearLayout.LayoutParams(-1, multi ? dp(120) : dp(48)));
        fields.add(new ApiField(id, key, e));
    }

    private void showCreate() { screen = "create"; showPaneOnly(); render(); setStatus("Create"); }
    private void showNodes() { screen = "nodes"; showPaneOnly(); render(); setStatus("Nodes"); }
    private void showPaneOnly() { pane.setVisibility(View.VISIBLE); graph.setVisibility(View.GONE); output.setVisibility(View.GONE); workspace.setVisibility(View.VISIBLE); }

    protected void showTemplatesTab() { showGraph(); }

    private void showGraph() {
        saveUrl();
        pane.setVisibility(View.GONE);
        output.setVisibility(View.GONE);
        graph.setVisibility(View.VISIBLE);
        topPanel.setVisibility(View.GONE);
        String base = baseUrl();
        if (base.isEmpty()) { toast("Enter ComfyUI URL first"); return; }
        String cur = graph.getUrl();
        if (cur == null || !cur.startsWith(base) || cur.contains("/view")) graph.loadUrl(base);
        setStatus("Graph mode. Load workflow, then Import.");
        applyBars();
    }

    private void showOutput(String url) {
        topPanel.setVisibility(View.GONE);
        pane.setVisibility(View.GONE);
        graph.setVisibility(View.GONE);
        output.setVisibility(View.VISIBLE);
        output.loadUrl(url);
        setStatus("Opening output...");
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
        handler.postDelayed(() -> { if (importing) { importing = false; busy(false, "Import timed out. Try again after graph is loaded."); } }, 15000);
        graph.evaluateJavascript(importScript(), null);
    }

    private String importScript() {
        return "(async function(){function send(o){try{window.ComfyRemoteBridge.onImportResult(JSON.stringify(o));}catch(e){}}"
                + "function graphObj(){return (window.app&&app.graph)||window.graph||((window.LGraphCanvas&&window.LGraphCanvas.active_canvas)&&window.LGraphCanvas.active_canvas.graph);}"
                + "function prim(v){return v===null||['string','number','boolean'].indexOf(typeof v)>=0;}"
                + "function li(g,id){var links=(g&&g.links)||{};var l=links[id];if(!l&&Array.isArray(links)){for(var i=0;i<links.length;i++){if(links[i]&&(links[i].id==id||links[i][0]==id)){l=links[i];break;}}}if(!l)return null;if(Array.isArray(l))return {o:String(l[1]),s:Number(l[2]||0)};return {o:String(l.origin_id||l.source_id||l.from_id||l.origin||''),s:Number(l.origin_slot||l.source_slot||l.from_slot||0)};}"
                + "function fallback(){var g=graphObj();if(!g)return {ok:false,error:'Graph object not found'};var nodes=g._nodes||g.nodes||[];var out={};for(var n of nodes){if(!n||n.id==null)continue;var cls=String(n.type||n.comfyClass||n.title||'');var title=String(n.title||cls);var low=cls.toLowerCase();if(!cls||low.indexOf('note')>=0||low.indexOf('markdown')>=0)continue;var item={class_type:cls,inputs:{},_meta:{title:title}};for(var inp of (n.inputs||[])){if(inp&&inp.link!=null&&inp.name){var x=li(g,inp.link);if(x&&x.o)item.inputs[String(inp.name)]=[x.o,x.s];}}for(var w of (n.widgets||[])){if(!w||!w.name)continue;var name=String(w.name);var type=String(w.type||'').toLowerCase();if(name==='upload'||type==='button')continue;if(prim(w.value))item.inputs[name]=w.value;}out[String(n.id)]=item;}return {ok:Object.keys(out).length>0,prompt:out,options:{},mode:'graph fallback'};}"
                + "try{var f=fallback();if(window.app&&app.graphToPrompt){try{var gp=app.graphToPrompt();if(gp&&typeof gp.then==='function')gp=await gp;var p=gp&&(gp.output||gp.prompt||gp);if(p&&Object.keys(p).length){send({ok:true,prompt:p,options:{},mode:'graphToPrompt'});return;}}catch(e){}}send(f);}catch(e){send({ok:false,error:String(e&&e.message?e.message:e)});}})()";
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
            saveWorkflow();
            saveOptions();
            selectedNodeId = firstEditableOrFirst();
            busy(false, "Imported " + workflow.length() + " nodes.");
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
            fieldOptions = new JSONObject();
            saveWorkflow();
            saveOptions();
            selectedNodeId = firstEditableOrFirst();
            toast("Workflow loaded");
            showCreate();
        } catch (JSONException e) { toast("Invalid JSON"); }
    }

    private void applyJsonText(String raw) {
        try {
            workflow = cleanWorkflow(new JSONObject(raw));
            fieldOptions = new JSONObject();
            saveWorkflow();
            saveOptions();
            selectedNodeId = firstEditableOrFirst();
            toast("Workflow loaded");
            showCreate();
        } catch (JSONException e) { toast("Invalid JSON file"); }
    }

    private void runWorkflow() {
        if (workflow == null) { toast("Import a workflow first"); return; }
        saveUrl();
        applyFields();
        workflow = cleanWorkflow(workflow);
        saveWorkflow();
        String base = baseUrl();
        if (base.isEmpty()) { toast("Enter ComfyUI URL first"); return; }
        try {
            JSONObject payload = new JSONObject();
            payload.put("prompt", cleanWorkflow(workflow));
            payload.put("client_id", "android-remote-" + System.currentTimeMillis());
            generationRunning = true;
            generationStartMs = System.currentTimeMillis();
            pollCount = 0;
            screen = "run";
            showPaneOnly();
            render();
            updateGenerationUi(8, "Sending prompt...");
            busy(true, "Sending prompt...");
            new Thread(() -> {
                try {
                    JSONObject res = new JSONObject(postJson(base + "/prompt", payload.toString()));
                    currentPromptId = res.optString("prompt_id", "");
                    if (currentPromptId.isEmpty()) throw new IllegalStateException("ComfyUI did not return prompt_id");
                    handler.post(() -> { busy(false, "Queued. Waiting for output..."); pollHistory(); });
                } catch (Exception e) {
                    handler.post(() -> { generationRunning = false; busy(false, "Run failed: " + shortError(e)); updateGenerationUi(0, "Run failed: " + shortError(e)); });
                }
            }).start();
        } catch (JSONException e) { toast("Could not build prompt JSON"); }
    }

    private void pollHistory() {
        if (currentPromptId == null || currentPromptId.isEmpty()) return;
        pollCount++;
        String base = baseUrl();
        String pid = currentPromptId;
        updateGenerationUi(estimatedProgressPercent(), generationProgressText());
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
        toast("No output yet");
    }

    private void testConnection() {
        saveUrl();
        String base = baseUrl();
        if (base.isEmpty()) { toast("Enter ComfyUI URL first"); return; }
        busy(true, "Testing...");
        new Thread(() -> {
            try { getText(base + "/system_stats"); handler.post(() -> busy(false, "Connection OK.")); }
            catch (Exception e) { handler.post(() -> busy(false, "Connection failed: " + shortError(e))); }
        }).start();
    }

    private JSONObject cleanWorkflow(JSONObject src) {
        JSONObject out = new JSONObject();
        if (src == null) return out;
        Iterator<String> it = src.keys();
        while (it.hasNext()) {
            String id = it.next();
            JSONObject node = src.optJSONObject(id);
            if (node == null) continue;
            if (isNonRunnable(node.optString("class_type", node.optString("type", "")))) continue;
            try { out.put(id, node); } catch (JSONException ignored) {}
        }
        return out;
    }

    private List<String> visibleNodeIds() {
        ArrayList<String> ids = new ArrayList<>();
        if (workflow == null) return ids;
        Iterator<String> it = workflow.keys();
        while (it.hasNext()) {
            String id = it.next();
            JSONObject n = workflow.optJSONObject(id);
            if (n == null) continue;
            String cls = n.optString("class_type", "").toLowerCase();
            if (!cls.contains("note") && !cls.contains("markdown") && !cls.contains("reroute")) ids.add(id);
        }
        Collections.sort(ids, (a, b) -> {
            try { return Integer.compare(Integer.parseInt(a), Integer.parseInt(b)); }
            catch (Exception e) { return a.compareTo(b); }
        });
        return ids;
    }

    private String firstEditableOrFirst() { List<String> ids = visibleNodeIds(); return ids.isEmpty() ? null : ids.get(0); }
    private List<String> inputKeys(JSONObject o) { ArrayList<String> keys = new ArrayList<>(); if (o == null) return keys; Iterator<String> it = o.keys(); while (it.hasNext()) keys.add(it.next()); Collections.sort(keys); return keys; }
    private boolean primitive(Object v) { return v == JSONObject.NULL || v instanceof String || v instanceof Number || v instanceof Boolean; }
    private int editableCount(String id, JSONObject inputs) { int n = 0; for (String k : inputKeys(inputs)) if (primitive(inputs.opt(k))) n++; return n; }
    private int dropdownCount(String id, JSONObject inputs) { return 0; }
    private boolean isNonRunnable(String cls) { String s = cls == null ? "" : cls.toLowerCase(); return s.contains("note") || s.contains("markdown"); }
    private String nodeTitle(JSONObject node) { if (node == null) return "Workflow loaded"; JSONObject meta = node.optJSONObject("_meta"); String title = meta == null ? "" : meta.optString("title", ""); if (title != null && !title.trim().isEmpty()) return prettify(title); return prettify(node.optString("class_type", "Node")); }
    private String human(String k) { return prettify(k == null ? "" : k.replace('_', ' ')); }
    private String fieldTitle(String k) { return human(k) + " · " + k; }
    private String prettify(String v) { return v == null ? "Node" : v.replace('_',' ').replaceAll("([a-z])([A-Z])", "$1 $2").trim(); }
    private void applyFields() { if (workflow != null) for (ApiField f : fields) setInput(f.node, f.key, coerce(f.edit.getText().toString())); }
    private void setInput(String node, String key, Object val) { try { JSONObject n = workflow.optJSONObject(node); if (n != null) n.getJSONObject("inputs").put(key, val); } catch (Exception ignored) {} }
    private Object coerce(String raw) { if (raw == null) return ""; String s = raw.trim(); if ("true".equalsIgnoreCase(s)) return true; if ("false".equalsIgnoreCase(s)) return false; try { if (s.matches("-?\\d+")) return Long.parseLong(s); if (s.matches("-?\\d+\\.\\d+")) return Double.parseDouble(s); } catch (Exception ignored) {} return raw; }
    private void saveWorkflow() { if (workflow != null) getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_WORKFLOW, cleanWorkflow(workflow).toString()).apply(); }
    private void saveOptions() { getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_OPTIONS, fieldOptions == null ? "{}" : fieldOptions.toString()).apply(); }
    private void saveUrl() { getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_URL, baseUrl()).apply(); }
    private String baseUrl() { String u = urlInput.getText().toString().trim(); if (u.isEmpty()) return ""; if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u; while (u.endsWith("/")) u = u.substring(0, u.length() - 1); return u; }

    private String postJson(String url, String body) throws Exception { HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(); try { c.setConnectTimeout(10000); c.setReadTimeout(30000); c.setDoOutput(true); c.setRequestMethod("POST"); c.setRequestProperty("Content-Type", "application/json; charset=utf-8"); OutputStream out = c.getOutputStream(); out.write(body.getBytes("UTF-8")); out.close(); int code = c.getResponseCode(); String r = readStream(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream()); if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + ": " + r); return r; } finally { c.disconnect(); } }
    private String getText(String url) throws Exception { HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(); try { c.setConnectTimeout(8000); c.setReadTimeout(20000); int code = c.getResponseCode(); String r = readStream(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream()); if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + ": " + r); return r; } finally { c.disconnect(); } }
    private String readStream(InputStream in) throws Exception { if (in == null) return ""; try { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); return out.toString("UTF-8"); } finally { in.close(); } }
    private String readText(Uri uri) throws Exception { InputStream in = getContentResolver().openInputStream(uri); if (in == null) throw new IllegalStateException("Could not read selected file"); try { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); return out.toString("UTF-8"); } finally { in.close(); } }

    private OutputFile findOutput(JSONObject history) throws JSONException { OutputFile found = null; Iterator<String> p = history.keys(); while (p.hasNext()) { JSONObject item = history.optJSONObject(p.next()); JSONObject outs = item == null ? null : item.optJSONObject("outputs"); if (outs == null) continue; Iterator<String> nodes = outs.keys(); while (nodes.hasNext()) { JSONObject o = outs.optJSONObject(nodes.next()); if (o == null) continue; OutputFile f = first(o.optJSONArray("videos")); if (f != null) found = f; f = first(o.optJSONArray("gifs")); if (f != null) found = f; f = first(o.optJSONArray("images")); if (f != null) found = f; } } return found; }
    private OutputFile first(JSONArray arr) { if (arr == null || arr.length() == 0) return null; JSONObject f = arr.optJSONObject(0); if (f == null) return null; String name = f.optString("filename", ""); if (name.isEmpty()) return null; return new OutputFile(name, f.optString("subfolder", ""), f.optString("type", "output")); }
    private String enc(String s) { try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; } }

    private void updateGenerationUi(int percent, String message) { if (generationBar != null) generationBar.setProgress(Math.max(0, Math.min(100, percent))); if (generationText != null) generationText.setText(message); }
    private void refreshGenerationUi() { if (generationRunning) updateGenerationUi(estimatedProgressPercent(), generationProgressText()); else updateGenerationUi(0, generationIdleText()); }
    private String generationIdleText() { return lastGenerationDurationMs > 0 ? "Ready. Last generation: " + fmtMs(lastGenerationDurationMs) + "." : "Ready."; }
    private int estimatedProgressPercent() { if (!generationRunning || generationStartMs <= 0) return 0; long elapsed = System.currentTimeMillis() - generationStartMs; if (lastGenerationDurationMs > 0) return Math.max(10, Math.min(95, (int) ((elapsed * 100L) / Math.max(1L, lastGenerationDurationMs)))); return Math.max(10, Math.min(90, 15 + pollCount * 3)); }
    private String generationProgressText() { long elapsed = Math.max(0L, System.currentTimeMillis() - generationStartMs); return "Generating · elapsed " + fmtMs(elapsed); }
    private String fmtMs(long ms) { long sec = Math.max(0L, ms / 1000L), min = sec / 60L, rem = sec % 60L; return min > 0 ? min + "m " + rem + "s" : rem + "s"; }
    private String shortError(Exception e) { if (e == null) return "unknown error"; String s = e.getMessage(); if (s == null || s.trim().isEmpty()) s = e.getClass().getSimpleName(); return s.length() > 220 ? s.substring(0, 220) + "…" : s; }

    private void updateBottomNav() { bottomNav.removeAllViews(); bottomNav.addView(navItem("⊞", "Create", "create".equals(screen), this::showCreate), weight(dp(62))); bottomNav.addView(navItem("⌘", "Nodes", "nodes".equals(screen), this::showNodes), weight(dp(62))); bottomNav.addView(navItem("▦", "Templates", "templates".equals(screen), this::showTemplatesTab), weight(dp(62))); bottomNav.addView(navItem("▷", "Run", "run".equals(screen), () -> { screen = "run"; showPaneOnly(); render(); }), weight(dp(62))); bottomNav.addView(navItem("▧", "Output", false, this::openOutput), weight(dp(62))); }
    private LinearLayout pageHeader(String title, String subtitle) { LinearLayout wrap = new LinearLayout(this); wrap.setOrientation(LinearLayout.VERTICAL); wrap.setPadding(0, dp(12), 0, dp(16)); wrap.addView(titleText(title, 28)); wrap.addView(mutedText(subtitle, 15)); return wrap; }
    private LinearLayout statusChip(String left, String right, Runnable action) { LinearLayout chip = new LinearLayout(this); chip.setOrientation(LinearLayout.HORIZONTAL); chip.setGravity(Gravity.CENTER_VERTICAL); chip.setPadding(dp(12), 0, dp(12), 0); chip.setBackground(bg(surface(), 12, stroke(), 1)); chip.addView(text(left, 12, muted()), new LinearLayout.LayoutParams(0, -1, 1)); TextView arrow = text(right, 20, muted()); arrow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); chip.addView(arrow, new LinearLayout.LayoutParams(dp(24), -1)); chip.setOnClickListener(v -> action.run()); return chip; }
    private LinearLayout card(boolean accentBorder) { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(14), dp(14), dp(14), dp(14)); c.setBackground(bg(surface(), 16, accentBorder ? accent() : stroke(), 1)); return c; }
    private LinearLayout cardTitle(String icon, String title) { LinearLayout r = row(); r.setGravity(Gravity.CENTER_VERTICAL); r.addView(badge(icon), new LinearLayout.LayoutParams(dp(34), dp(34))); TextView t = titleText(title, 20); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.setMargins(dp(10), 0, 0, 0); r.addView(t, lp); return r; }
    private TextView badge(String s) { TextView b = text(s, 14, accent()); b.setGravity(Gravity.CENTER); b.setBackground(bg(Color.rgb(37,31,22), 12, stroke(), 1)); return b; }
    private View metric(String label, String value) { LinearLayout box = card(false); box.setGravity(Gravity.CENTER); TextView l = mutedText(label, 11); l.setGravity(Gravity.CENTER); TextView v = titleText(value, 14); v.setGravity(Gravity.CENTER); box.addView(l); box.addView(v); return box; }
    private TextView chip(String s) { TextView v = mutedText(s, 12); v.setGravity(Gravity.CENTER); v.setSingleLine(true); v.setBackground(bg(surface2(), 14, stroke(), 1)); return v; }
    private EditText input(String hint, boolean single) { EditText e = new EditText(this); e.setHint(hint); e.setSingleLine(single); e.setTextColor(Color.WHITE); e.setHintTextColor(muted()); e.setTextSize(14); e.setPadding(dp(14), 0, dp(14), 0); e.setBackground(bg(surface2(), 12, stroke(), 1)); return e; }
    private Button actionButton(String label, boolean primary, Runnable action) { Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setSingleLine(true); b.setTextSize(13); b.setTextColor(primary ? accent() : Color.WHITE); b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); b.setBackground(bg(primary ? Color.rgb(44,35,25) : surface2(), 12, primary ? accent() : stroke(), 1)); b.setOnClickListener(v -> action.run()); return b; }
    private View navItem(String icon, String label, boolean selected, Runnable action) { LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER); box.setPadding(0, dp(4), 0, dp(4)); box.setBackground(selected ? bg(Color.rgb(37,31,22), 12, accent(), 1) : bg(Color.TRANSPARENT, 12, Color.TRANSPARENT, 0)); TextView i = text(icon, 20, selected ? accent() : muted()); i.setGravity(Gravity.CENTER); box.addView(i, new LinearLayout.LayoutParams(-1, dp(24))); TextView l = text(label, 10, selected ? accent() : muted()); l.setGravity(Gravity.CENTER); l.setSingleLine(true); box.addView(l, new LinearLayout.LayoutParams(-1, dp(20))); box.setOnClickListener(v -> action.run()); return box; }
    private LinearLayout renderTipCard() { LinearLayout tip = card(true); tip.addView(label("💡  Tip")); tip.addView(mutedText("Use Templates to start quickly, or load your own JSON workflow.", 13)); content.addView(tip, sectionParams()); return tip; }
    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); return r; }
    private LinearLayout.LayoutParams weight(int h) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, h, 1); p.setMargins(dp(4), 0, dp(4), 0); return p; }
    private LinearLayout.LayoutParams sectionParams() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, 0, 0, dp(14)); return p; }
    private TextView titleText(String s, int sp) { TextView t = text(s, sp, Color.WHITE); t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); t.setMaxLines(2); t.setEllipsize(TextUtils.TruncateAt.END); return t; }
    private TextView label(String s) { TextView t = text(s, 13, Color.rgb(210,210,216)); t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return t; }
    private TextView mutedText(String s, int sp) { return text(s, sp, muted()); }
    private TextView text(String s, int sp, int color) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setPadding(dp(2), 0, dp(2), dp(4)); return t; }
    private void busy(boolean on, String msg) { if (busyBar != null) busyBar.setVisibility(on ? View.VISIBLE : View.GONE); setStatus(msg); }
    private void setStatus(String msg) { if (status != null) status.setText(msg); }
    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }
    private void toggleTopPanel() { boolean show = topPanel.getVisibility() != View.VISIBLE; topPanel.setVisibility(show ? View.VISIBLE : View.GONE); setStatus(show ? "URL panel shown" : "URL panel hidden"); }
    private void injectGraphCss() { graph.evaluateJavascript("(function(){var m=document.querySelector('meta[name=viewport]')||document.createElement('meta');m.name='viewport';m.content='width=device-width,initial-scale=1,minimum-scale=.35,maximum-scale=3,user-scalable=yes';document.head.appendChild(m);})()", null); }
    private void applyBars() { Window w = getWindow(); w.setStatusBarColor(bgRoot()); w.setNavigationBarColor(bgRoot()); w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE); }
    private GradientDrawable bg(int color, int radiusDp, int stroke, int strokeDp) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radiusDp)); d.setStroke(dp(strokeDp), stroke); return d; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private int bgRoot() { return Color.rgb(18,18,19); }
    private int surface() { return Color.rgb(28,28,30); }
    private int surface2() { return Color.rgb(33,33,36); }
    private int stroke() { return Color.rgb(48,48,52); }
    private int muted() { return Color.rgb(170,170,178); }
    private int accent() { return Color.rgb(218,143,60); }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        if (req == REQ_WEB_FILE) {
            if (webFileCallback != null) {
                webFileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
                webFileCallback = null;
            }
            applyBars();
            return;
        }
        if (req == REQ_JSON) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                try { applyJsonText(readText(data.getData())); } catch (Exception e) { toast("Could not read JSON"); }
            }
            applyBars();
            return;
        }
        super.onActivityResult(req, result, data);
    }

    @Override public void onBackPressed() {
        if (output.getVisibility() == View.VISIBLE) { showCreate(); return; }
        if (graph.getVisibility() == View.VISIBLE) { if (graph.canGoBack()) graph.goBack(); else showCreate(); return; }
        if ("nodes".equals(screen) || "run".equals(screen)) { showCreate(); return; }
        super.onBackPressed();
    }
}
