package com.snapsnake.comfyremote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
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

public class MainActivity extends Activity {
    private static final String PREFS = "comfyui_remote_prefs";
    private static final String KEY_URL = "comfyui_url";
    private static final String KEY_WORKFLOW = "workflow_api_json";
    private static final String KEY_OPTIONS = "workflow_field_options";
    private static final String KEY_OUTPUT = "last_output_url";
    private static final int REQ_WEB_FILE = 42;
    private static final int REQ_IMAGE = 43;
    private static final int REQ_JSON = 44;

    private LinearLayout topPanel;
    private EditText urlInput;
    private TextView status;
    private ProgressBar progress;
    private FrameLayout workspace;
    private ScrollView nativePane;
    private LinearLayout content;
    private WebView graph;
    private WebView output;
    private View scrollTrack;
    private View scrollThumb;
    private EditText jsonEditor;
    private ValueCallback<Uri[]> webFileCallback;
    private JSONObject workflow;
    private JSONObject fieldOptions = new JSONObject();
    private final List<ApiField> fields = new ArrayList<>();
    private boolean expandAllNodes = false;
    private String pendingNode;
    private String pendingKey;
    private String lastOutputUrl;
    private String currentPromptId;
    private int pollCount;
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
        OutputFile(String filename, String subfolder, String type) { this.filename = filename; this.subfolder = subfolder; this.type = type; }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        configureWebViews();
        loadPrefs();
        renderNative();
        applySystemBars();
    }

    private void loadPrefs() {
        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        urlInput.setText(p.getString(KEY_URL, "http://desktop-name.tailnet.ts.net:8188"));
        lastOutputUrl = p.getString(KEY_OUTPUT, "");
        if (p.contains(KEY_URL) && topPanel != null) topPanel.setVisibility(View.GONE);
        String saved = p.getString(KEY_WORKFLOW, "");
        if (!saved.trim().isEmpty()) {
            try { workflow = new JSONObject(saved); } catch (JSONException ignored) { workflow = null; }
        }
        String savedOptions = p.getString(KEY_OPTIONS, "{}");
        try { fieldOptions = new JSONObject(savedOptions); } catch (JSONException ignored) { fieldOptions = new JSONObject(); }
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

        TextView title = text("ComfyUI Mobile Remote", 18, Color.WHITE);
        topPanel.addView(title);

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setTextColor(Color.WHITE);
        urlInput.setHintTextColor(Color.rgb(148, 163, 184));
        urlInput.setHint("http://desktop-name.tailnet.ts.net:8188");
        urlInput.setTextSize(14);
        urlInput.setPadding(dp(12), 0, dp(12), 0);
        urlInput.setBackground(bgStroke(Color.rgb(30, 41, 59), dp(12), Color.rgb(71, 85, 105), 1));
        topPanel.addView(urlInput, new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout topRow = row();
        topPanel.addView(topRow);
        addTopButton(topRow, "Test", () -> testConnection());
        addTopButton(topRow, "Native", () -> showNative());
        addTopButton(topRow, "Graph", () -> showGraph());
        addTopButton(topRow, "Import", () -> importFromGraph());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        status = text("URL panel hidden. Tap here to show it.", 12, Color.rgb(203, 213, 225));
        status.setPadding(dp(12), dp(7), dp(12), dp(7));
        status.setBackgroundColor(Color.rgb(15, 23, 42));
        status.setOnClickListener(v -> toggleTopPanel());
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        workspace = new FrameLayout(this);
        root.addView(workspace, new LinearLayout.LayoutParams(-1, 0, 1));

        nativePane = new ScrollView(this);
        nativePane.setFillViewport(false);
        nativePane.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        nativePane.setVerticalScrollBarEnabled(false);
        nativePane.setScrollbarFadingEnabled(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(14), dp(22), dp(88));
        nativePane.addView(content, new ScrollView.LayoutParams(-1, -2));
        workspace.addView(nativePane, new FrameLayout.LayoutParams(-1, -1));
        nativePane.setOnScrollChangeListener((v, sx, sy, oldx, oldy) -> updateScrollIndicator());

        graph = new WebView(this);
        graph.setVisibility(View.GONE);
        workspace.addView(graph, new FrameLayout.LayoutParams(-1, -1));

        output = new WebView(this);
        output.setVisibility(View.GONE);
        workspace.addView(output, new FrameLayout.LayoutParams(-1, -1));

        scrollTrack = new View(this);
        scrollTrack.setBackground(bgStroke(Color.argb(110, 51, 65, 85), dp(4), Color.argb(120, 71, 85, 105), 1));
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(dp(7), -1, Gravity.RIGHT);
        tp.setMargins(0, dp(10), dp(4), dp(10));
        workspace.addView(scrollTrack, tp);

        scrollThumb = new View(this);
        scrollThumb.setBackground(bgStroke(Color.rgb(96, 165, 250), dp(4), Color.rgb(147, 197, 253), 1));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(7), dp(72), Gravity.RIGHT | Gravity.TOP);
        sp.setMargins(0, dp(10), dp(4), 0);
        workspace.addView(scrollThumb, sp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(dp(8), dp(8), dp(8), dp(8));
        bottom.setBackgroundColor(Color.rgb(15, 23, 42));
        root.addView(bottom, new LinearLayout.LayoutParams(-1, dp(72)));
        addToolButton(bottom, "Native", () -> showNative());
        addToolButton(bottom, "Graph", () -> showGraph());
        addToolButton(bottom, "Import", () -> importFromGraph());
        addToolButton(bottom, "Run", () -> runWorkflow());
        addToolButton(bottom, "Output", () -> openOutput());

        setContentView(root);
    }

    private void toggleTopPanel() {
        boolean show = topPanel.getVisibility() != View.VISIBLE;
        topPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        status.setText(show ? "URL panel shown. Tap here to hide it." : "URL panel hidden. Tap here to show it.");
        applySystemBars();
    }

    private void addTopButton(LinearLayout parent, String label, Runnable action) {
        Button b = button(label, Color.rgb(51, 65, 85), 14, 14);
        b.setOnClickListener(v -> action.run());
        parent.addView(b, weight(dp(46)));
    }

    private void addToolButton(LinearLayout parent, String label, Runnable action) {
        Button b = button(label, Color.rgb(30, 41, 59), 13, 16);
        b.setOnClickListener(v -> action.run());
        parent.addView(b, weight(dp(52)));
    }

    @SuppressLint("SetJavaScriptEnabled") private void configureWebViews() {
        configureCommonWebView(graph);
        configureCommonWebView(output);

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
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) { busy(true, "Graph loading: " + url); }
            @Override public void onPageFinished(WebView view, String url) {
                busy(false, "Graph loaded. Load workflow, then press Import.");
                injectGraphCss();
                applySystemBars();
            }
        });
        output.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) { busy(true, "Output loading..."); }
            @Override public void onPageFinished(WebView view, String url) {
                busy(false, "Output preview. Press Back or Native to return.");
                applySystemBars();
            }
        });
    }

    private void configureCommonWebView(WebView w) {
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
    }

    private void renderNative() {
        fields.clear();
        content.removeAllViews();
        content.addView(text("Native workflow", 28, Color.WHITE));
        content.addView(muted("Import Graph once, then use the phone UI: image upload, selectable fields, Run and Output."));
        if (workflow == null) {
            sourceCard();
            LinearLayout c = card();
            c.addView(header("No workflow imported"));
            c.addView(muted("Open Graph, load your ComfyUI workflow, wait until nodes are visible, then press Import."));
            content.addView(c, cardParams());
            nativePane.post(this::updateScrollIndicator);
            return;
        }
        importedSummaryCard();
        runCard();
        nodeCards();
        nativePane.post(this::updateScrollIndicator);
    }

    private void sourceCard() {
        LinearLayout c = card();
        content.addView(c, cardParams());
        c.addView(header("Workflow"));
        c.addView(muted("Recommended: Graph → load workflow → Import. Manual API JSON import is only a fallback."));
        LinearLayout r1 = row();
        c.addView(r1);
        addCardButton(r1, "Open Graph", false, () -> showGraph());
        addCardButton(r1, "Import from Graph", true, () -> importFromGraph());
        jsonEditor = new EditText(this);
        jsonEditor.setText("");
        jsonEditor.setTextColor(Color.WHITE);
        jsonEditor.setHintTextColor(Color.rgb(148, 163, 184));
        jsonEditor.setHint("Fallback only: paste API workflow JSON here");
        jsonEditor.setTextSize(13);
        jsonEditor.setGravity(Gravity.TOP | Gravity.LEFT);
        jsonEditor.setMinLines(4);
        jsonEditor.setMaxLines(8);
        jsonEditor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        jsonEditor.setPadding(dp(12), dp(10), dp(12), dp(10));
        jsonEditor.setBackground(bgStroke(Color.rgb(15, 23, 42), dp(14), Color.rgb(51, 65, 85), 1));
        LinearLayout.LayoutParams jp = new LinearLayout.LayoutParams(-1, dp(130));
        jp.setMargins(0, dp(10), 0, dp(10));
        c.addView(jsonEditor, jp);
        LinearLayout r2 = row();
        c.addView(r2);
        addCardButton(r2, "Load JSON file", false, () -> chooseJson());
        addCardButton(r2, "Apply JSON", true, () -> applyJson());
    }

    private void importedSummaryCard() {
        LinearLayout c = cardAccent();
        content.addView(c, cardParams());
        c.addView(header("Workflow imported"));
        c.addView(muted(workflow.length() + " nodes loaded. Node cards are collapsed by default; tap a card to edit it."));
        LinearLayout r = row();
        c.addView(r);
        addCardButton(r, "Collapse all", false, () -> { expandAllNodes = false; renderNative(); });
        addCardButton(r, "Expand all", true, () -> { expandAllNodes = true; renderNative(); });
    }

    private void runCard() {
        LinearLayout c = card();
        content.addView(c, cardParams());
        c.addView(header("Run"));
        LinearLayout r = row();
        c.addView(r);
        addCardButton(r, "Apply fields", false, () -> { applyFields(); saveWorkflow(); toast("Applied"); renderNative(); });
        addCardButton(r, "Run workflow", true, () -> runWorkflow());
        Button out = button("Open latest output", Color.rgb(51, 65, 85), 15, 14);
        out.setOnClickListener(v -> openOutput());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52));
        p.setMargins(dp(3), dp(10), dp(3), 0);
        c.addView(out, p);
    }

    private void nodeCards() {
        int shown = 0;
        for (String id : nodeIds()) {
            JSONObject node = workflow.optJSONObject(id);
            if (node == null) continue;
            JSONObject inputs = node.optJSONObject("inputs");
            if (inputs == null) continue;
            String cls = node.optString("class_type", "Node");
            if (!useful(cls, inputs)) continue;
            nodeCard(id, cls, inputs);
            shown++;
        }
        if (shown == 0) {
            LinearLayout c = card();
            c.addView(header("No editable nodes"));
            c.addView(muted("Import worked, but this workflow has no simple editable widget fields."));
            content.addView(c, cardParams());
        }
    }

    private void nodeCard(String id, String cls, JSONObject inputs) {
        LinearLayout c = card();
        content.addView(c, cardParams());
        final boolean[] expanded = new boolean[]{expandAllNodes || isLoadImage(cls)};
        Button head = button((expanded[0] ? "▾ " : "▸ ") + "#" + id + "  " + prettify(cls), Color.rgb(30, 41, 59), 18, 16);
        head.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        head.setPadding(dp(14), 0, dp(14), 0);
        c.addView(head, new LinearLayout.LayoutParams(-1, dp(58)));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(10), 0, 0);
        c.addView(body, new LinearLayout.LayoutParams(-1, -2));
        body.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
        head.setOnClickListener(v -> {
            expanded[0] = !expanded[0];
            body.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
            head.setText((expanded[0] ? "▾ " : "▸ ") + "#" + id + "  " + prettify(cls));
            nativePane.post(this::updateScrollIndicator);
        });

        if (isLoadImage(cls)) {
            body.addView(label("Image input"));
            String imageKey = imageKey(inputs);
            Button choose = button("Choose image from phone", Color.rgb(37, 99, 235), 15, 14);
            choose.setOnClickListener(v -> chooseImage(id, imageKey));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(58));
            p.setMargins(0, 0, 0, dp(12));
            body.addView(choose, p);
        }
        if (isOutput(cls)) {
            body.addView(label("Output"));
            Button preview = button("Preview latest output", Color.rgb(51, 65, 85), 15, 14);
            preview.setOnClickListener(v -> openOutput());
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(54));
            p.setMargins(0, 0, 0, dp(12));
            body.addView(preview, p);
        }
        boolean added = false;
        for (String key : inputKeys(inputs)) {
            Object value = inputs.opt(key);
            if (!primitive(value)) continue;
            addField(body, id, key, value, cls);
            added = true;
        }
        if (!added) body.addView(muted("No directly editable fields in this node."));
    }

    private void addField(LinearLayout c, String id, String key, Object value, String cls) {
        c.addView(label(human(key)));
        JSONArray opts = optionValues(id, key);
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
        e.setText(value == JSONObject.NULL ? "" : String.valueOf(value));
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.rgb(148, 163, 184));
        e.setTextSize(17);
        e.setPadding(dp(12), 0, dp(12), 0);
        e.setBackground(bgStroke(Color.rgb(15, 23, 42), dp(14), Color.rgb(30, 41, 59), 1));
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
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, multi ? dp(116) : dp(56));
        p.setMargins(0, 0, 0, dp(14));
        c.addView(e, p);
        fields.add(new ApiField(id, key, e));
    }

    private void showOptionsPicker(String node, String key, JSONArray opts) {
        String[] items = new String[opts.length()];
        for (int i = 0; i < opts.length(); i++) items[i] = opts.optString(i, "");
        new AlertDialog.Builder(this)
                .setTitle(human(key))
                .setItems(items, (d, which) -> {
                    setInput(node, key, coerce(items[which]));
                    saveWorkflow();
                    toast("Selected: " + items[which]);
                    renderNative();
                })
                .show();
    }

    private JSONArray optionValues(String node, String key) {
        return fieldOptions == null ? null : fieldOptions.optJSONArray(node + ":" + key);
    }

    private void showNative() {
        nativePane.setVisibility(View.VISIBLE);
        graph.setVisibility(View.GONE);
        output.setVisibility(View.GONE);
        setScrollIndicatorVisible(true);
        renderNative();
        status.setText("Native mode ready. Tap this line to show/hide URL panel.");
        applySystemBars();
    }

    private void showGraph() {
        saveUrl();
        nativePane.setVisibility(View.GONE);
        output.setVisibility(View.GONE);
        graph.setVisibility(View.VISIBLE);
        setScrollIndicatorVisible(false);
        topPanel.setVisibility(View.GONE);
        String base = baseUrl();
        if (base.isEmpty()) { toast("Enter ComfyUI URL first"); return; }
        String cur = graph.getUrl();
        if (cur == null || !cur.startsWith(base) || cur.contains("/view")) graph.loadUrl(base);
        status.setText("Graph mode. Load workflow here, wait until nodes are visible, then press Import.");
        applySystemBars();
    }

    private void showOutput(String url) {
        topPanel.setVisibility(View.GONE);
        nativePane.setVisibility(View.GONE);
        graph.setVisibility(View.GONE);
        output.setVisibility(View.VISIBLE);
        setScrollIndicatorVisible(false);
        output.loadUrl(url);
        status.setText("Opening output inside the app...");
        applySystemBars();
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
        busy(true, "Importing visible Graph nodes...");
        graph.evaluateJavascript(importScript(), this::handleImport);
    }

    private String importScript() {
        return "(function(){" +
                "function graphObj(){return (window.app&&app.graph)||window.graph||((window.LGraphCanvas&&window.LGraphCanvas.active_canvas)&&window.LGraphCanvas.active_canvas.graph);}" +
                "function primitive(v){return v===null||['string','number','boolean'].indexOf(typeof v)>=0;}" +
                "function linkInfo(g,id){var links=(g&&g.links)||{};var l=links[id];if(!l&&Array.isArray(links)){for(var i=0;i<links.length;i++){if(links[i]&&(links[i].id==id||links[i][0]==id)){l=links[i];break;}}}if(!l)return null;if(Array.isArray(l))return {origin:String(l[1]),slot:Number(l[2]||0)};return {origin:String(l.origin_id||l.source_id||l.from_id||l.origin||''),slot:Number(l.origin_slot||l.source_slot||l.from_slot||0)};}" +
                "function optValues(w){var o=w&&w.options;var a=null;if(o){if(Array.isArray(o.values))a=o.values;else if(Array.isArray(o))a=o;}if(!a&&Array.isArray(w&&w.values))a=w.values;if(!a||!a.length)return null;return a.map(function(x){return String(x);});}" +
                "function fromGraph(){var g=graphObj();if(!g)return {ok:false,error:'Graph object not found'};var nodes=g._nodes||g.nodes||[];if(!nodes.length)return {ok:false,error:'No visible nodes in Graph'};var out={};var options={};for(var ni=0;ni<nodes.length;ni++){var n=nodes[ni];if(!n||n.id==null)continue;var cls=String(n.type||n.comfyClass||n.title||'');if(!cls)continue;var item={class_type:cls,inputs:{}};var ins=n.inputs||[];for(var ii=0;ii<ins.length;ii++){var inp=ins[ii];if(!inp||inp.link==null||!inp.name)continue;var li=linkInfo(g,inp.link);if(li&&li.origin)item.inputs[String(inp.name)]=[li.origin,li.slot];}var ws=n.widgets||[];for(var wi=0;wi<ws.length;wi++){var w=ws[wi];if(!w||!w.name)continue;var name=String(w.name);var type=String(w.type||'').toLowerCase();if(name==='upload'||type==='button')continue;var opts=optValues(w);if(opts)options[String(n.id)+':'+name]=opts;var val=w.value;if(!primitive(val))continue;item.inputs[name]=val;}if(n.widgets_values&&ws.length){for(var vi=0;vi<Math.min(ws.length,n.widgets_values.length);vi++){var ww=ws[vi];if(!ww||!ww.name)continue;var nm=String(ww.name);if(item.inputs.hasOwnProperty(nm)||nm==='upload'||String(ww.type||'').toLowerCase()==='button')continue;var vv=n.widgets_values[vi];if(primitive(vv))item.inputs[nm]=vv;}}out[String(n.id)]=item;}return Object.keys(out).length?{ok:true,prompt:out,options:options,mode:'visible graph'}:{ok:false,error:'No importable nodes'};}" +
                "try{var r=fromGraph();return JSON.stringify(r);}catch(e){return JSON.stringify({ok:false,error:String(e&&e.message?e.message:e)});}" +
                "})()";
    }

    private void handleImport(String value) {
        try {
            String decoded = new JSONArray("[" + value + "]").getString(0);
            JSONObject res = new JSONObject(decoded);
            if (!res.optBoolean("ok", false)) {
                busy(false, "Import failed: " + res.optString("error") + ". In Graph, wait until nodes are visible.");
                return;
            }
            Object p = res.opt("prompt");
            if (p instanceof JSONObject) workflow = (JSONObject) p;
            else if (p instanceof String) workflow = new JSONObject((String) p);
            else { busy(false, "Import failed: unsupported prompt format"); return; }
            fieldOptions = res.optJSONObject("options");
            if (fieldOptions == null) fieldOptions = new JSONObject();
            saveWorkflow();
            saveOptions();
            busy(false, "Imported " + workflow.length() + " nodes. Native controls are ready.");
            expandAllNodes = false;
            showNative();
        } catch (Exception e) { busy(false, "Import failed: " + e.getClass().getSimpleName()); }
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
            String raw = jsonEditor == null ? "" : jsonEditor.getText().toString();
            workflow = new JSONObject(raw);
            fieldOptions = new JSONObject();
            saveWorkflow();
            saveOptions();
            toast("Workflow loaded");
            renderNative();
        } catch (JSONException e) { toast("Invalid JSON"); }
    }

    private void applyJsonText(String raw) {
        try {
            workflow = new JSONObject(raw);
            fieldOptions = new JSONObject();
            saveWorkflow();
            saveOptions();
            toast("Workflow loaded");
            renderNative();
        }
        catch (JSONException e) { toast("Invalid JSON file"); }
    }

    private void chooseImage(String node, String key) {
        if (key == null || key.trim().isEmpty()) { toast("Image input not found"); return; }
        pendingNode = node;
        pendingKey = key;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { toast("Opening image picker..."); startActivityForResult(Intent.createChooser(i, "Choose image"), REQ_IMAGE); }
        catch (Exception e) { pendingNode = null; pendingKey = null; toast("No image picker available"); }
    }

    private void uploadImage(Uri uri, String node, String key) {
        if (uri == null || node == null || key == null) return;
        String base = baseUrl();
        if (base.isEmpty()) { toast("Enter ComfyUI URL first"); return; }
        busy(true, "Uploading image...");
        new Thread(() -> {
            try {
                String name = displayName(uri);
                String mime = getContentResolver().getType(uri);
                String uploaded = uploadMultipart(base, readBytes(uri), name, mime);
                setInput(node, key, uploaded);
                saveWorkflow();
                handler.post(() -> { busy(false, "Uploaded image: " + uploaded); toast("Image selected: " + uploaded); renderNative(); });
            } catch (Exception e) { handler.post(() -> busy(false, "Image upload failed: " + e.getClass().getSimpleName())); }
        }).start();
    }

    private void runWorkflow() {
        if (workflow == null) { toast("Import a workflow first"); return; }
        saveUrl();
        applyFields();
        saveWorkflow();
        String base = baseUrl();
        if (base.isEmpty()) { toast("Enter ComfyUI URL first"); return; }
        try {
            JSONObject payload = new JSONObject();
            payload.put("prompt", workflow);
            payload.put("client_id", "android-remote-" + System.currentTimeMillis());
            busy(true, "Sending prompt...");
            new Thread(() -> {
                try {
                    JSONObject res = new JSONObject(postJson(base + "/prompt", payload.toString()));
                    currentPromptId = res.optString("prompt_id", "");
                    if (currentPromptId.isEmpty()) throw new IllegalStateException("No prompt_id");
                    pollCount = 0;
                    handler.post(() -> { busy(false, "Queued. Waiting for output..."); pollHistory(); });
                } catch (Exception e) { handler.post(() -> busy(false, "Run failed: " + e.getClass().getSimpleName())); }
            }).start();
        } catch (JSONException e) { toast("Could not build prompt JSON"); }
    }

    private void pollHistory() {
        if (currentPromptId == null || currentPromptId.isEmpty()) return;
        pollCount++;
        String base = baseUrl();
        String pid = currentPromptId;
        status.setText("Waiting for output... " + pollCount);
        new Thread(() -> {
            try {
                OutputFile f = findOutput(new JSONObject(getText(base + "/history/" + enc(pid))));
                if (f != null) {
                    lastOutputUrl = base + "/view?filename=" + enc(f.filename) + "&type=" + enc(f.type) + "&subfolder=" + enc(f.subfolder);
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_OUTPUT, lastOutputUrl).apply();
                    handler.post(() -> status.setText("Output ready. Press Output."));
                    return;
                }
            } catch (Exception ignored) {}
            if (pollCount < 120) handler.postDelayed(this::pollHistory, 2000);
            else handler.post(() -> status.setText("Timed out waiting for output."));
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
                if (f == null) throw new IllegalStateException();
                lastOutputUrl = base + "/view?filename=" + enc(f.filename) + "&type=" + enc(f.type) + "&subfolder=" + enc(f.subfolder);
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_OUTPUT, lastOutputUrl).apply();
                handler.post(() -> { busy(false, "Opening output inside app."); showOutput(lastOutputUrl); });
            } catch (Exception e) { handler.post(() -> busy(false, "No output found")); }
        }).start();
    }

    private void testConnection() {
        saveUrl();
        String base = baseUrl();
        if (base.isEmpty()) { toast("Enter ComfyUI URL first"); return; }
        busy(true, "Testing...");
        new Thread(() -> {
            try { getText(base + "/system_stats"); handler.post(() -> busy(false, "Connection OK. Tap status line to hide URL panel.")); }
            catch (Exception e) { handler.post(() -> busy(false, "Connection failed: " + e.getClass().getSimpleName())); }
        }).start();
    }

    private void updateScrollIndicator() {
        if (nativePane == null || workspace == null || scrollTrack == null || scrollThumb == null) return;
        if (nativePane.getVisibility() != View.VISIBLE) { setScrollIndicatorVisible(false); return; }
        int extent = nativePane.getHeight();
        int range = extent;
        if (nativePane.getChildCount() > 0 && nativePane.getChildAt(0) != null) {
            range = nativePane.getChildAt(0).getHeight();
        }
        int offset = nativePane.getScrollY();
        if (range <= extent + dp(8)) { setScrollIndicatorVisible(false); return; }
        setScrollIndicatorVisible(true);
        int trackHeight = Math.max(1, workspace.getHeight() - dp(20));
        int thumbHeight = Math.max(dp(48), Math.min(trackHeight, trackHeight * extent / Math.max(range, 1)));
        int maxTop = Math.max(0, trackHeight - thumbHeight);
        int top = dp(10) + (int) (maxTop * (offset / (float) Math.max(1, range - extent)));
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) scrollThumb.getLayoutParams();
        p.height = thumbHeight;
        p.topMargin = top;
        p.rightMargin = dp(4);
        scrollThumb.setLayoutParams(p);
    }

    private void setScrollIndicatorVisible(boolean visible) {
        int v = visible ? View.VISIBLE : View.GONE;
        if (scrollTrack != null) scrollTrack.setVisibility(v);
        if (scrollThumb != null) scrollThumb.setVisibility(v);
    }

    private void applyFields() { if (workflow != null) for (ApiField f : fields) setInput(f.node, f.key, coerce(f.edit.getText().toString())); }
    private Object coerce(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;
        try { if (s.matches("-?\\d+")) return Long.parseLong(s); if (s.matches("-?\\d+\\.\\d+")) return Double.parseDouble(s); } catch (Exception ignored) {}
        return raw;
    }
    private void setInput(String node, String key, Object val) { try { JSONObject n = workflow.optJSONObject(node); if (n != null) n.getJSONObject("inputs").put(key, val); } catch (Exception ignored) {} }
    private void saveWorkflow() { if (workflow != null) getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_WORKFLOW, workflow.toString()).apply(); }
    private void saveOptions() { getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_OPTIONS, fieldOptions == null ? "{}" : fieldOptions.toString()).apply(); }
    private void saveUrl() { getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_URL, baseUrl()).apply(); }
    private String baseUrl() { String u = urlInput.getText().toString().trim(); if (u.isEmpty()) return ""; if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u; while (u.endsWith("/")) u = u.substring(0, u.length() - 1); return u; }

    private String uploadMultipart(String base, byte[] bytes, String filename, String mime) throws Exception {
        String b = "----ComfyRemote" + System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/upload/image").openConnection();
        try {
            c.setConnectTimeout(10000); c.setReadTimeout(30000); c.setDoOutput(true); c.setRequestMethod("POST"); c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + b);
            OutputStream out = c.getOutputStream();
            part(out, b, "type", "input"); part(out, b, "overwrite", "true"); filePart(out, b, "image", filename, mime == null ? "application/octet-stream" : mime, bytes);
            out.write(("--" + b + "--\r\n").getBytes("UTF-8")); out.flush(); out.close();
            int code = c.getResponseCode(); String body = readStream(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
            if (code < 200 || code >= 300) throw new IllegalStateException(body);
            return new JSONObject(body).optString("name", filename);
        } finally { c.disconnect(); }
    }
    private void part(OutputStream out, String b, String n, String v) throws Exception { out.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + n + "\"\r\n\r\n" + v + "\r\n").getBytes("UTF-8")); }
    private void filePart(OutputStream out, String b, String n, String fn, String mt, byte[] bytes) throws Exception { out.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + n + "\"; filename=\"" + fn + "\"\r\nContent-Type: " + mt + "\r\n\r\n").getBytes("UTF-8")); out.write(bytes); out.write("\r\n".getBytes("UTF-8")); }
    private String postJson(String url, String body) throws Exception { HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(); try { c.setConnectTimeout(10000); c.setReadTimeout(30000); c.setDoOutput(true); c.setRequestMethod("POST"); c.setRequestProperty("Content-Type", "application/json; charset=utf-8"); OutputStream out = c.getOutputStream(); out.write(body.getBytes("UTF-8")); out.close(); int code = c.getResponseCode(); String r = readStream(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream()); if (code < 200 || code >= 300) throw new IllegalStateException(r); return r; } finally { c.disconnect(); } }
    private String getText(String url) throws Exception { HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(); try { c.setConnectTimeout(8000); c.setReadTimeout(20000); int code = c.getResponseCode(); String r = readStream(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream()); if (code < 200 || code >= 300) throw new IllegalStateException(r); return r; } finally { c.disconnect(); } }
    private String readStream(InputStream in) throws Exception { if (in == null) return ""; try { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); return out.toString("UTF-8"); } finally { in.close(); } }
    private byte[] readBytes(Uri uri) throws Exception { InputStream in = getContentResolver().openInputStream(uri); if (in == null) throw new IllegalStateException(); try { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); return out.toByteArray(); } finally { in.close(); } }
    private String readText(Uri uri) throws Exception { return new String(readBytes(uri), "UTF-8"); }
    private String displayName(Uri uri) { String r = null; Cursor c = null; try { c = getContentResolver().query(uri, null, null, null, null); if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) r = c.getString(i); } } catch (Exception ignored) {} finally { if (c != null) c.close(); } if (r == null || r.trim().isEmpty()) r = "comfy_remote_image.png"; return r.replaceAll("[^A-Za-z0-9._-]", "_"); }

    private OutputFile findOutput(JSONObject history) throws JSONException { OutputFile found = null; Iterator<String> p = history.keys(); while (p.hasNext()) { JSONObject item = history.optJSONObject(p.next()); if (item == null) continue; JSONObject outs = item.optJSONObject("outputs"); if (outs == null) continue; Iterator<String> nodes = outs.keys(); while (nodes.hasNext()) { JSONObject o = outs.optJSONObject(nodes.next()); if (o == null) continue; OutputFile f = first(o.optJSONArray("videos")); if (f != null) found = f; f = first(o.optJSONArray("gifs")); if (f != null) found = f; f = first(o.optJSONArray("images")); if (f != null) found = f; } } return found; }
    private OutputFile first(JSONArray arr) { if (arr == null || arr.length() == 0) return null; JSONObject f = arr.optJSONObject(0); if (f == null) return null; String name = f.optString("filename", ""); if (name.isEmpty()) return null; return new OutputFile(name, f.optString("subfolder", ""), f.optString("type", "output")); }
    private String enc(String s) throws Exception { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }

    private boolean useful(String cls, JSONObject inputs) { String c = cls == null ? "" : cls.toLowerCase(); if (c.contains("note") || c.contains("markdown") || c.contains("reroute")) return false; if (isLoadImage(cls) || isOutput(cls)) return true; Iterator<String> it = inputs.keys(); while (it.hasNext()) if (primitive(inputs.opt(it.next()))) return true; return false; }
    private boolean primitive(Object v) { return v == JSONObject.NULL || v instanceof String || v instanceof Number || v instanceof Boolean; }
    private List<String> nodeIds() { List<String> k = new ArrayList<>(); Iterator<String> it = workflow.keys(); while (it.hasNext()) k.add(it.next()); Collections.sort(k, (a,b)->{ try { return Integer.compare(Integer.parseInt(a), Integer.parseInt(b)); } catch(Exception e){ return a.compareTo(b); }}); return k; }
    private List<String> inputKeys(JSONObject o) { List<String> k = new ArrayList<>(); Iterator<String> it = o.keys(); while (it.hasNext()) k.add(it.next()); Collections.sort(k); return k; }
    private boolean numericKey(String k) { String s = k.toLowerCase(); return s.contains("width") || s.contains("height") || s.contains("step") || s.contains("seed") || s.contains("cfg") || s.contains("duration") || s.contains("batch") || s.contains("fps") || s.contains("frame"); }
    private boolean isLoadImage(String c) { String s = c == null ? "" : c.toLowerCase(); return s.contains("loadimage") || s.contains("load image"); }
    private boolean isOutput(String c) { String s = c == null ? "" : c.toLowerCase(); return s.contains("saveimage") || s.contains("save image") || s.contains("savevideo") || s.contains("save video") || s.contains("previewimage") || s.contains("preview image"); }
    private String imageKey(JSONObject inputs) { for (String k : inputKeys(inputs)) if (k.equalsIgnoreCase("image") || k.toLowerCase().contains("image")) return k; return "image"; }
    private String human(String k) { String s = k.toLowerCase(); if (s.equals("ckpt_name")) return "Checkpoint"; if (s.equals("lora_name")) return "LoRA"; if (s.equals("seed") || s.equals("noise_seed")) return "Seed"; if (s.equals("steps")) return "Steps"; if (s.equals("cfg")) return "CFG"; if (s.equals("width")) return "Width"; if (s.equals("height")) return "Height"; if (s.equals("filename_prefix")) return "Filename prefix"; if (s.equals("image")) return "Image"; if (s.equals("text") || s.equals("prompt") || s.equals("positive")) return "Prompt"; return prettify(k.replace('_', ' ')); }
    private String prettify(String v) { return v == null ? "Node" : v.replace('_',' ').replaceAll("([a-z])([A-Z])", "$1 $2").trim(); }

    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(0, dp(8), 0, 0); return r; }
    private void addCardButton(LinearLayout r, String text, boolean primary, Runnable action) { Button b = button(text, primary ? Color.rgb(37, 99, 235) : Color.rgb(51, 65, 85), 15, 14); b.setOnClickListener(v -> action.run()); r.addView(b, weight(dp(50))); }
    private LinearLayout card() { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(14), dp(14), dp(14), dp(14)); c.setBackground(bgStroke(Color.rgb(30, 41, 59), dp(22), Color.rgb(71, 85, 105), 1)); return c; }
    private LinearLayout cardAccent() { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(14), dp(14), dp(14), dp(14)); c.setBackground(bgStroke(Color.rgb(24, 41, 65), dp(22), Color.rgb(96, 165, 250), 2)); return c; }
    private LinearLayout.LayoutParams cardParams() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, 0, 0, dp(16)); return p; }
    private LinearLayout.LayoutParams weight(int h) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, h, 1); p.setMargins(dp(3), 0, dp(3), 0); return p; }
    private TextView text(String t, int size, int color) { TextView v = new TextView(this); v.setText(t); v.setTextSize(size); v.setTextColor(color); v.setPadding(dp(2), 0, dp(2), dp(8)); return v; }
    private TextView header(String t) { return text(t, 20, Color.WHITE); }
    private TextView label(String t) { TextView v = text(t, 17, Color.rgb(226, 232, 240)); v.setPadding(dp(2), dp(8), dp(2), dp(6)); return v; }
    private TextView muted(String t) { return text(t, 14, Color.rgb(148, 163, 184)); }
    private Button button(String t, int color, int size, int radius) { Button b = new Button(this); b.setText(t); b.setAllCaps(false); b.setSingleLine(false); b.setTextColor(Color.WHITE); b.setTextSize(size); b.setIncludeFontPadding(false); b.setGravity(Gravity.CENTER); b.setPadding(dp(6), 0, dp(6), 0); b.setBackground(bgStroke(color, dp(radius), Color.rgb(71, 85, 105), 1)); return b; }
    private GradientDrawable bgStroke(int color, int radius, int strokeColor, int strokeDp) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); d.setStroke(dp(strokeDp), strokeColor); return d; }
    private void busy(boolean on, String msg) { progress.setVisibility(on ? View.VISIBLE : View.GONE); status.setText(msg); }
    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }
    private void injectGraphCss() { graph.evaluateJavascript("(function(){var m=document.querySelector('meta[name=viewport]')||document.createElement('meta');m.name='viewport';m.content='width=device-width,initial-scale=1,minimum-scale=.35,maximum-scale=3,user-scalable=yes';document.head.appendChild(m);})()", null); }
    private void applySystemBars() {
        Window w = getWindow();
        w.setStatusBarColor(Color.rgb(2, 6, 23));
        w.setNavigationBarColor(Color.rgb(15, 23, 42));
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) applySystemBars(); }
    @Override protected void onActivityResult(int req, int result, Intent data) {
        if (req == REQ_WEB_FILE) { if (webFileCallback != null) { webFileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data)); webFileCallback = null; } applySystemBars(); return; }
        if (req == REQ_IMAGE) { String n = pendingNode, k = pendingKey; pendingNode = null; pendingKey = null; if (result == RESULT_OK && data != null && data.getData() != null) uploadImage(data.getData(), n, k); else status.setText("Image selection cancelled. Choose image can be pressed again."); applySystemBars(); return; }
        if (req == REQ_JSON) { if (result == RESULT_OK && data != null && data.getData() != null) { try { applyJsonText(readText(data.getData())); } catch (Exception e) { toast("Could not read JSON"); } } applySystemBars(); return; }
        super.onActivityResult(req, result, data);
    }
    @Override public void onBackPressed() {
        if (output.getVisibility() == View.VISIBLE) { showNative(); return; }
        if (graph.getVisibility() == View.VISIBLE) { if (graph.canGoBack()) graph.goBack(); else showNative(); return; }
        super.onBackPressed();
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
