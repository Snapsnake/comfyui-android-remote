package com.snapsnake.comfyremote;

import android.annotation.SuppressLint;
import android.app.Activity;
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
    private static final String KEY_OUTPUT = "last_output_url";
    private static final int REQ_WEB_FILE = 42;
    private static final int REQ_IMAGE = 43;
    private static final int REQ_JSON = 44;

    private EditText urlInput;
    private TextView status;
    private ProgressBar progress;
    private ScrollView nativePane;
    private LinearLayout content;
    private WebView graph;
    private EditText jsonEditor;
    private ValueCallback<Uri[]> webFileCallback;
    private JSONObject workflow;
    private final List<ApiField> fields = new ArrayList<>();
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
        configureGraph();
        loadPrefs();
        renderNative();
        immersive();
    }

    private void loadPrefs() {
        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        urlInput.setText(p.getString(KEY_URL, "http://desktop-name.tailnet.ts.net:8188"));
        lastOutputUrl = p.getString(KEY_OUTPUT, "");
        String saved = p.getString(KEY_WORKFLOW, "");
        if (!saved.trim().isEmpty()) {
            try { workflow = new JSONObject(saved); } catch (JSONException ignored) { workflow = null; }
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(2, 6, 23));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(12), dp(8), dp(12), dp(8));
        top.setBackgroundColor(Color.rgb(15, 23, 42));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        TextView title = text("ComfyUI Mobile Remote", 18, Color.WHITE);
        top.addView(title);

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setTextColor(Color.WHITE);
        urlInput.setHintTextColor(Color.rgb(148, 163, 184));
        urlInput.setHint("http://desktop-name.tailnet.ts.net:8188");
        urlInput.setTextSize(14);
        urlInput.setPadding(dp(12), 0, dp(12), 0);
        urlInput.setBackground(bg(Color.rgb(30, 41, 59), dp(12)));
        top.addView(urlInput, new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout topRow = row();
        top.addView(topRow);
        addTopButton(topRow, "Test", () -> testConnection());
        addTopButton(topRow, "Native", () -> showNative());
        addTopButton(topRow, "Graph", () -> showGraph());
        addTopButton(topRow, "Import", () -> importFromGraph());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        status = text("Open Graph, load a workflow, then Import it into native phone controls.", 12, Color.rgb(203, 213, 225));
        status.setPadding(dp(12), dp(6), dp(12), dp(6));
        status.setBackgroundColor(Color.rgb(15, 23, 42));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout workspace = new FrameLayout(this);
        root.addView(workspace, new LinearLayout.LayoutParams(-1, 0, 1));

        nativePane = new ScrollView(this);
        nativePane.setFillViewport(false);
        nativePane.setOverScrollMode(View.OVER_SCROLL_NEVER);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(88));
        nativePane.addView(content, new ScrollView.LayoutParams(-1, -2));
        workspace.addView(nativePane, new FrameLayout.LayoutParams(-1, -1));

        graph = new WebView(this);
        graph.setVisibility(View.GONE);
        workspace.addView(graph, new FrameLayout.LayoutParams(-1, -1));

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

    @SuppressLint("SetJavaScriptEnabled") private void configureGraph() {
        WebSettings s = graph.getSettings();
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
        graph.setOverScrollMode(View.OVER_SCROLL_NEVER);
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
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                busy(true, "Graph loading: " + url);
            }
            @Override public void onPageFinished(WebView view, String url) {
                busy(false, "Graph loaded. Load your workflow, then press Import.");
                injectGraphCss();
                immersive();
            }
        });
    }

    private void renderNative() {
        fields.clear();
        content.removeAllViews();
        content.addView(text("Native workflow", 28, Color.WHITE));
        content.addView(muted("Phone-first mode: import the open ComfyUI graph once, then use native fields, image upload, Run and Output."));
        sourceCard();
        if (workflow == null) {
            LinearLayout c = card();
            c.addView(header("No workflow imported"));
            c.addView(muted("Press Graph, load your normal ComfyUI workflow, then press Import. The app will convert it through ComfyUI's graphToPrompt and build phone controls."));
            content.addView(c, cardParams());
            return;
        }
        runCard();
        nodeCards();
    }

    private void sourceCard() {
        LinearLayout c = card();
        content.addView(c, cardParams());
        c.addView(header("Workflow"));
        c.addView(muted("Recommended: Graph → load workflow → Import. Manual API JSON import is available below."));
        LinearLayout r1 = row();
        c.addView(r1);
        addCardButton(r1, "Open Graph", false, () -> showGraph());
        addCardButton(r1, "Import from Graph", true, () -> importFromGraph());
        jsonEditor = new EditText(this);
        jsonEditor.setText(workflow == null ? "" : pretty(workflow));
        jsonEditor.setTextColor(Color.WHITE);
        jsonEditor.setHintTextColor(Color.rgb(148, 163, 184));
        jsonEditor.setHint("Or paste API workflow JSON here");
        jsonEditor.setTextSize(13);
        jsonEditor.setGravity(Gravity.TOP | Gravity.LEFT);
        jsonEditor.setMinLines(4);
        jsonEditor.setMaxLines(8);
        jsonEditor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        jsonEditor.setPadding(dp(12), dp(10), dp(12), dp(10));
        jsonEditor.setBackground(bg(Color.rgb(15, 23, 42), dp(14)));
        LinearLayout.LayoutParams jp = new LinearLayout.LayoutParams(-1, dp(145));
        jp.setMargins(0, dp(10), 0, dp(10));
        c.addView(jsonEditor, jp);
        LinearLayout r2 = row();
        c.addView(r2);
        addCardButton(r2, "Load JSON file", false, () -> chooseJson());
        addCardButton(r2, "Apply JSON", true, () -> applyJson());
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
        for (String id : nodeIds()) {
            JSONObject node = workflow.optJSONObject(id);
            if (node == null) continue;
            JSONObject inputs = node.optJSONObject("inputs");
            if (inputs == null) continue;
            String cls = node.optString("class_type", "Node");
            if (!useful(cls, inputs)) continue;
            nodeCard(id, cls, inputs);
        }
    }

    private void nodeCard(String id, String cls, JSONObject inputs) {
        LinearLayout c = card();
        content.addView(c, cardParams());
        c.addView(header("#" + id + "  " + prettify(cls)));
        if (isLoadImage(cls)) {
            c.addView(label("Image input"));
            String imageKey = imageKey(inputs);
            Button choose = button("Choose image from phone", Color.rgb(37, 99, 235), 15, 14);
            choose.setOnClickListener(v -> chooseImage(id, imageKey));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(58));
            p.setMargins(0, 0, 0, dp(12));
            c.addView(choose, p);
        }
        if (isOutput(cls)) {
            c.addView(label("Output"));
            Button preview = button("Preview latest output", Color.rgb(51, 65, 85), 15, 14);
            preview.setOnClickListener(v -> openOutput());
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(54));
            p.setMargins(0, 0, 0, dp(12));
            c.addView(preview, p);
        }
        boolean added = false;
        for (String key : inputKeys(inputs)) {
            Object value = inputs.opt(key);
            if (!primitive(value)) continue;
            addField(c, id, key, value, cls);
            added = true;
        }
        if (!added) c.addView(muted("No directly editable fields in this node."));
    }

    private void addField(LinearLayout c, String id, String key, Object value, String cls) {
        c.addView(label(human(key)));
        EditText e = new EditText(this);
        e.setText(value == JSONObject.NULL ? "" : String.valueOf(value));
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.rgb(148, 163, 184));
        e.setTextSize(17);
        e.setPadding(dp(12), 0, dp(12), 0);
        e.setBackground(bg(Color.rgb(15, 23, 42), dp(14)));
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
        p.setMargins(0, 0, 0, dp(12));
        c.addView(e, p);
        fields.add(new ApiField(id, key, e));
    }

    private void showNative() {
        nativePane.setVisibility(View.VISIBLE);
        graph.setVisibility(View.GONE);
        renderNative();
        status.setText("Native mode ready.");
        immersive();
    }

    private void showGraph() {
        saveUrl();
        nativePane.setVisibility(View.GONE);
        graph.setVisibility(View.VISIBLE);
        String base = baseUrl();
        if (base.isEmpty()) { toast("Enter ComfyUI URL first"); return; }
        String cur = graph.getUrl();
        if (cur == null || !cur.startsWith(base) || cur.contains("/view")) graph.loadUrl(base);
        status.setText("Graph mode. Load your workflow here, then press Import.");
        immersive();
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
        busy(true, "Importing workflow from Graph...");
        String js = "(async()=>{try{if(!(window.app&&app.graphToPrompt))return JSON.stringify({ok:false,error:'graphToPrompt unavailable'});let r=await app.graphToPrompt();let p=r&&(r.output||r.prompt);if(!p)return JSON.stringify({ok:false,error:'No API prompt'});return JSON.stringify({ok:true,prompt:p});}catch(e){return JSON.stringify({ok:false,error:String(e&&e.message?e.message:e)});}})();";
        graph.evaluateJavascript(js, this::handleImport);
    }

    private void handleImport(String value) {
        try {
            String decoded = new JSONArray("[" + value + "]").getString(0);
            JSONObject res = new JSONObject(decoded);
            if (!res.optBoolean("ok", false)) { busy(false, "Import failed: " + res.optString("error")); return; }
            Object p = res.opt("prompt");
            if (p instanceof JSONObject) workflow = (JSONObject) p;
            else if (p instanceof String) workflow = new JSONObject((String) p);
            else { busy(false, "Import failed: unsupported prompt format"); return; }
            saveWorkflow();
            busy(false, "Workflow imported. Native controls are ready.");
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
        try { workflow = new JSONObject(jsonEditor.getText().toString()); saveWorkflow(); toast("Workflow loaded"); renderNative(); }
        catch (JSONException e) { toast("Invalid JSON"); }
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
        if (lastOutputUrl != null && !lastOutputUrl.trim().isEmpty()) { openUrl(lastOutputUrl); return; }
        String base = baseUrl();
        if (base.isEmpty()) { toast("Enter ComfyUI URL first"); return; }
        busy(true, "Finding latest output...");
        new Thread(() -> {
            try {
                OutputFile f = findOutput(new JSONObject(getText(base + "/history")));
                if (f == null) throw new IllegalStateException();
                lastOutputUrl = base + "/view?filename=" + enc(f.filename) + "&type=" + enc(f.type) + "&subfolder=" + enc(f.subfolder);
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_OUTPUT, lastOutputUrl).apply();
                handler.post(() -> { busy(false, "Opening output."); openUrl(lastOutputUrl); });
            } catch (Exception e) { handler.post(() -> busy(false, "No output found")); }
        }).start();
    }

    private void testConnection() {
        saveUrl();
        String base = baseUrl();
        if (base.isEmpty()) { toast("Enter ComfyUI URL first"); return; }
        busy(true, "Testing...");
        new Thread(() -> {
            try { getText(base + "/system_stats"); handler.post(() -> busy(false, "Connection OK.")); }
            catch (Exception e) { handler.post(() -> busy(false, "Connection failed: " + e.getClass().getSimpleName())); }
        }).start();
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
    private void openUrl(String u) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); } catch (Exception e) { toast("No app can open output URL"); } }

    private boolean useful(String cls, JSONObject inputs) { if (isLoadImage(cls) || isOutput(cls)) return true; Iterator<String> it = inputs.keys(); while (it.hasNext()) if (primitive(inputs.opt(it.next()))) return true; return false; }
    private boolean primitive(Object v) { return v == JSONObject.NULL || v instanceof String || v instanceof Number || v instanceof Boolean; }
    private List<String> nodeIds() { List<String> k = new ArrayList<>(); Iterator<String> it = workflow.keys(); while (it.hasNext()) k.add(it.next()); Collections.sort(k, (a,b)->{ try { return Integer.compare(Integer.parseInt(a), Integer.parseInt(b)); } catch(Exception e){ return a.compareTo(b); }}); return k; }
    private List<String> inputKeys(JSONObject o) { List<String> k = new ArrayList<>(); Iterator<String> it = o.keys(); while (it.hasNext()) k.add(it.next()); Collections.sort(k); return k; }
    private boolean numericKey(String k) { String s = k.toLowerCase(); return s.contains("width") || s.contains("height") || s.contains("step") || s.contains("seed") || s.contains("cfg") || s.contains("duration") || s.contains("batch") || s.contains("fps") || s.contains("frame"); }
    private boolean isLoadImage(String c) { String s = c.toLowerCase(); return s.contains("loadimage") || s.contains("load image"); }
    private boolean isOutput(String c) { String s = c.toLowerCase(); return s.contains("saveimage") || s.contains("save image") || s.contains("savevideo") || s.contains("save video") || s.contains("previewimage") || s.contains("preview image"); }
    private String imageKey(JSONObject inputs) { for (String k : inputKeys(inputs)) if (k.equalsIgnoreCase("image") || k.toLowerCase().contains("image")) return k; return "image"; }
    private String human(String k) { String s = k.toLowerCase(); if (s.equals("ckpt_name")) return "Checkpoint"; if (s.equals("lora_name")) return "LoRA"; if (s.equals("seed") || s.equals("noise_seed")) return "Seed"; if (s.equals("steps")) return "Steps"; if (s.equals("cfg")) return "CFG"; if (s.equals("width")) return "Width"; if (s.equals("height")) return "Height"; if (s.equals("filename_prefix")) return "Filename prefix"; if (s.equals("image")) return "Image"; if (s.equals("text") || s.equals("prompt") || s.equals("positive")) return "Prompt"; return prettify(k.replace('_', ' ')); }
    private String prettify(String v) { return v == null ? "Node" : v.replace('_',' ').replaceAll("([a-z])([A-Z])", "$1 $2").trim(); }
    private String pretty(JSONObject o) { try { return o.toString(2); } catch (Exception e) { return o.toString(); } }

    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(0, dp(8), 0, 0); return r; }
    private void addCardButton(LinearLayout r, String text, boolean primary, Runnable action) { Button b = button(text, primary ? Color.rgb(37, 99, 235) : Color.rgb(51, 65, 85), 15, 14); b.setOnClickListener(v -> action.run()); r.addView(b, weight(dp(50))); }
    private LinearLayout card() { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(14), dp(14), dp(14), dp(14)); c.setBackground(bg(Color.rgb(30, 41, 59), dp(22))); return c; }
    private LinearLayout.LayoutParams cardParams() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, 0, 0, dp(14)); return p; }
    private LinearLayout.LayoutParams weight(int h) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, h, 1); p.setMargins(dp(3), 0, dp(3), 0); return p; }
    private TextView text(String t, int size, int color) { TextView v = new TextView(this); v.setText(t); v.setTextSize(size); v.setTextColor(color); v.setPadding(dp(2), 0, dp(2), dp(8)); return v; }
    private TextView header(String t) { return text(t, 20, Color.WHITE); }
    private TextView label(String t) { TextView v = text(t, 17, Color.rgb(226, 232, 240)); v.setPadding(dp(2), dp(6), dp(2), dp(6)); return v; }
    private TextView muted(String t) { return text(t, 14, Color.rgb(148, 163, 184)); }
    private Button button(String t, int color, int size, int radius) { Button b = new Button(this); b.setText(t); b.setAllCaps(false); b.setSingleLine(false); b.setTextColor(Color.WHITE); b.setTextSize(size); b.setIncludeFontPadding(false); b.setGravity(Gravity.CENTER); b.setPadding(dp(6), 0, dp(6), 0); b.setBackground(bg(color, dp(radius))); return b; }
    private GradientDrawable bg(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); return d; }
    private void busy(boolean on, String msg) { progress.setVisibility(on ? View.VISIBLE : View.GONE); status.setText(msg); }
    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }
    private void injectGraphCss() { graph.evaluateJavascript("(function(){var m=document.querySelector('meta[name=viewport]')||document.createElement('meta');m.name='viewport';m.content='width=device-width,initial-scale=1,minimum-scale=.35,maximum-scale=3,user-scalable=yes';document.head.appendChild(m);})()", null); }
    private void immersive() { Window w = getWindow(); w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE); }
    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive(); }
    @Override protected void onActivityResult(int req, int result, Intent data) {
        if (req == REQ_WEB_FILE) { if (webFileCallback != null) { webFileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data)); webFileCallback = null; } immersive(); return; }
        if (req == REQ_IMAGE) { String n = pendingNode, k = pendingKey; pendingNode = null; pendingKey = null; if (result == RESULT_OK && data != null && data.getData() != null) uploadImage(data.getData(), n, k); else status.setText("Image selection cancelled. Choose image can be pressed again."); immersive(); return; }
        if (req == REQ_JSON) { if (result == RESULT_OK && data != null && data.getData() != null) { try { jsonEditor.setText(readText(data.getData())); applyJson(); } catch (Exception e) { toast("Could not read JSON"); } } immersive(); return; }
        super.onActivityResult(req, result, data);
    }
    @Override public void onBackPressed() { if (graph.getVisibility() == View.VISIBLE) { if (graph.canGoBack()) graph.goBack(); else showNative(); return; } super.onBackPressed(); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
