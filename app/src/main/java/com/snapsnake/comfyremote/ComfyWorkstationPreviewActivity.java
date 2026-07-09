package com.snapsnake.comfyremote;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.Editable;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ComfyWorkstationPreviewActivity extends Activity {
    private static final String VERSION = "0.13.1-preview-fields";
    private static final String PREFS = "comfy_workstation_preview_v1";
    private static final String OLD_PREFS = "comfy_workstation_v1";
    private static final String KEY_URL = "url";
    private static final String KEY_CF_ID = "cf_id";
    private static final String KEY_CF_SECRET = "cf_secret";
    private static final String KEY_WORKFLOW = "workflow";
    private static final String KEY_OBJECT_INFO = "object_info";
    private static final String KEY_TEMPLATES = "templates";
    private static final String KEY_TEMPLATES_AT = "templates_at";
    private static final String KEY_OUTPUTS = "outputs";
    private static final int REQ_JSON = 9401;
    private static final int PAGE_SIZE = 60;
    private static final int MAX_BODY = 64 * 1024 * 1024;
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final ExecutorService IO = Executors.newFixedThreadPool(6);
    private static final LruCache<String, Bitmap> IMAGES = new LruCache<>(64);
    private static final OkHttpClient HTTP_SAFE = new OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .build();
    private static final OkHttpClient HTTP_NORMAL = new OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout root, setupPanel, content, nav, templateList, nodeList;
    private ScrollView scroll;
    private EditText urlInput, cfIdInput, cfSecretInput, templateSearch, nodeSearch, jsonInput;
    private TextView statusLine, templateMeta;
    private JSONObject workflow;
    private JSONObject objectInfo = new JSONObject();
    private final ArrayList<TemplateItem> templates = new ArrayList<>();
    private final ArrayList<FieldRef> editingFields = new ArrayList<>();
    private final ArrayList<String> outputs = new ArrayList<>();
    private String screen = "home";
    private String activeBase = "";
    private String status = "Ready.";
    private String templateFilter = "";
    private String nodeFilter = "";
    private String promptId = "";
    private long templatesAt = 0L;
    private int templateLimit = PAGE_SIZE;
    private int pollCount = 0;
    private boolean busy = false;

    private static class TemplateItem {
        String source = "default";
        String name = "";
        String title = "";
        String description = "";
        String category = "Templates";
        String mediaSubtype = "webp";
        String id() { return n(source) + "/" + n(name); }
        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("source", source); o.put("name", name); o.put("title", title); o.put("description", description); o.put("category", category); o.put("mediaSubtype", mediaSubtype);
            return o;
        }
        static TemplateItem fromJson(JSONObject o) {
            TemplateItem t = new TemplateItem();
            if (o == null) return t;
            t.source = o.optString("source", "default");
            t.name = o.optString("name", "");
            t.title = o.optString("title", t.name);
            t.description = o.optString("description", "");
            t.category = o.optString("category", "Templates");
            t.mediaSubtype = o.optString("mediaSubtype", "webp");
            return t;
        }
        private static String n(String s) { return s == null ? "" : s; }
    }

    private static class FieldRef {
        final String nodeId;
        final String key;
        final EditText edit;
        FieldRef(String nodeId, String key, EditText edit) { this.nodeId = nodeId; this.key = key; this.edit = edit; }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try { CookieManager.getInstance().setAcceptCookie(true); } catch (Exception ignored) {}
        buildUi();
        loadState();
        render();
        styleBars();
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(bgRoot());

        setupPanel = new LinearLayout(this);
        setupPanel.setOrientation(LinearLayout.VERTICAL);
        setupPanel.setPadding(dp(18), dp(12), dp(18), dp(12));
        setupPanel.setBackgroundColor(surface());
        root.addView(setupPanel, new LinearLayout.LayoutParams(-1, -2));
        setupPanel.addView(title("ComfyUI Mobile", 21));
        setupPanel.addView(muted("Remote workstation for ComfyUI on PC · " + VERSION, 11), lp(-1, -2, 0, 2, 0, 8));
        urlInput = input("http://192.168.1.10:8188 or https://domain", true);
        setupPanel.addView(urlInput, lp(-1, 48, 0, 0, 0, 8));
        LinearLayout quick = row();
        quick.addView(button("Test", true, this::testConnection), weight(44));
        quick.addView(button("Hide", false, () -> setupPanel.setVisibility(View.GONE)), weight(44));
        quick.addView(button("Graph ↗", false, () -> openUrl(activeBase())), weight(44));
        setupPanel.addView(quick);
        setupPanel.addView(muted("Cloudflare Access is optional", 12), lp(-1, -2, 0, 8, 0, 4));
        cfIdInput = input("CF-Access-Client-Id", true);
        setupPanel.addView(cfIdInput, lp(-1, 42, 0, 0, 0, 6));
        cfSecretInput = input("CF-Access-Client-Secret", true);
        cfSecretInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        setupPanel.addView(cfSecretInput, lp(-1, 42, 0, 0, 0, 0));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(bgRoot());
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(14), dp(18), dp(18));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(8), dp(8), dp(8), dp(8));
        nav.setBackgroundColor(surface());
        root.addView(nav, new LinearLayout.LayoutParams(-1, dp(78)));
        setContentView(root);
    }

    private void loadState() {
        SharedPreferences p = prefs();
        SharedPreferences old = getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE);
        String url = firstNonEmpty(p.getString(KEY_URL, ""), old.getString(KEY_URL, ""));
        urlInput.setText(url);
        cfIdInput.setText(firstNonEmpty(p.getString(KEY_CF_ID, ""), old.getString(KEY_CF_ID, "")));
        cfSecretInput.setText(firstNonEmpty(p.getString(KEY_CF_SECRET, ""), old.getString(KEY_CF_SECRET, "")));
        if (!url.trim().isEmpty()) setupPanel.setVisibility(View.GONE);
        try { workflow = new JSONObject(p.getString(KEY_WORKFLOW, old.getString(KEY_WORKFLOW, "{}"))); if (workflow.length() == 0) workflow = null; normalizeWorkflowFields(); } catch (Exception e) { workflow = null; }
        try { objectInfo = new JSONObject(p.getString(KEY_OBJECT_INFO, old.getString(KEY_OBJECT_INFO, "{}"))); } catch (Exception e) { objectInfo = new JSONObject(); }
        readTemplates(p.getString(KEY_TEMPLATES, old.getString(KEY_TEMPLATES, "[]")));
        templatesAt = p.getLong(KEY_TEMPLATES_AT, old.getLong(KEY_TEMPLATES_AT, 0L));
        readOutputs();
    }

    private void render() {
        editingFields.clear();
        content.removeAllViews();
        renderNav();
        if ("templates".equals(screen)) renderTemplates();
        else if ("nodes".equals(screen)) renderNodes();
        else if ("run".equals(screen)) renderRun();
        else if ("outputs".equals(screen)) renderOutputs();
        else if ("help".equals(screen)) renderHelp();
        else renderHome();
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void renderHome() {
        content.addView(statusCard(), section());
        content.addView(header("Workstation", "Native controls for ComfyUI on your PC: templates, fields, run and output."), section());
        LinearLayout c = card(false);
        c.addView(cardTitle("◇", workflow == null ? "No workflow loaded" : "Workflow loaded"));
        c.addView(muted(workflow == null ? "Open a template from ComfyUI or import workflow/API JSON." : workflow.length() + " nodes ready.", 13));
        LinearLayout actions = row();
        actions.setPadding(0, dp(12), 0, 0);
        actions.addView(button("Templates", false, () -> { screen = "templates"; render(); }), weight(44));
        actions.addView(button("Import JSON", true, this::chooseJson), weight(44));
        c.addView(actions);
        if (workflow != null) {
            LinearLayout more = row();
            more.setPadding(0, dp(10), 0, 0);
            more.addView(button("Fields", false, () -> { screen = "nodes"; render(); }), weight(44));
            more.addView(button("Run", true, () -> { screen = "run"; render(); }), weight(44));
            c.addView(more);
        }
        content.addView(c, section());
        LinearLayout paste = card(true);
        paste.addView(label("Paste JSON manually"));
        jsonInput = input("ComfyUI workflow/API JSON…", false);
        jsonInput.setSingleLine(false);
        jsonInput.setGravity(Gravity.TOP | Gravity.LEFT);
        jsonInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        paste.addView(jsonInput, new LinearLayout.LayoutParams(-1, dp(120)));
        paste.addView(button("Apply", true, () -> importJson(jsonInput.getText().toString())), lp(-1, 42, 0, 10, 0, 0));
        content.addView(paste, section());
    }

    private void renderTemplates() {
        content.addView(statusCard(), section());
        content.addView(header("Templates", "Loaded from /templates/index.json and /api/workflow_templates on your ComfyUI."), section());
        LinearLayout tools = card(false);
        LinearLayout searchBox = row();
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(dp(12), 0, dp(8), 0);
        searchBox.setBackground(bg(surface2(), 12, stroke(), 1));
        searchBox.addView(muted("⌕", 22), new LinearLayout.LayoutParams(dp(30), -1));
        templateSearch = bareInput("Search templates…", true);
        templateSearch.setText(templateFilter);
        searchBox.addView(templateSearch, new LinearLayout.LayoutParams(0, -1, 1));
        tools.addView(searchBox, new LinearLayout.LayoutParams(-1, dp(48)));
        templateMeta = muted("", 12);
        tools.addView(templateMeta, lp(-1, -2, 0, 8, 0, 0));
        LinearLayout buttons = row();
        buttons.setPadding(0, dp(8), 0, 0);
        buttons.addView(button(busy ? "Loading…" : "Refresh from PC", true, this::loadTemplates), weight(44));
        buttons.addView(button("Clear", false, () -> { templateFilter = ""; templateLimit = PAGE_SIZE; if (templateSearch != null) templateSearch.setText(""); refreshTemplates(); }), weight(44));
        tools.addView(buttons);
        content.addView(tools, section());
        templateList = new LinearLayout(this);
        templateList.setOrientation(LinearLayout.VERTICAL);
        content.addView(templateList, new LinearLayout.LayoutParams(-1, -2));
        templateSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { templateFilter = String.valueOf(s == null ? "" : s); templateLimit = PAGE_SIZE; refreshTemplates(); }
            public void afterTextChanged(Editable s) {}
        });
        refreshTemplates();
    }

    private void refreshTemplates() {
        if (templateList == null) return;
        if (templateMeta != null) templateMeta.setText("Cached: " + templates.size() + (templatesAt > 0 ? " · " + timeAgo(templatesAt) : " · not refreshed yet"));
        templateList.removeAllViews();
        String q = safe(templateFilter).trim().toLowerCase(Locale.US);
        ArrayList<TemplateItem> matches = new ArrayList<>();
        for (TemplateItem t : templates) if (q.isEmpty() || templateText(t).toLowerCase(Locale.US).contains(q)) matches.add(t);
        int max = Math.min(templateLimit, matches.size());
        for (int i = 0; i < max; i++) templateList.addView(templateRow(matches.get(i)), section());
        if (matches.isEmpty()) templateList.addView(emptyCard(templates.isEmpty() ? "Cache is empty. Tap Refresh from PC." : "Nothing found."), section());
        if (matches.size() > max) templateList.addView(button("Show more", false, () -> { templateLimit += PAGE_SIZE; refreshTemplates(); }), lp(-1, 42, 0, 0, 0, 12));
    }

    private View templateRow(TemplateItem t) {
        LinearLayout row = card(false);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(10), dp(12));
        row.setClickable(true);
        row.setOnClickListener(v -> openTemplate(t));
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setImageResource(R.drawable.ic_launcher);
        preview.setBackground(bg(surface2(), 10, stroke(), 1));
        preview.setTag(t.id());
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(106), dp(82));
        ip.setMargins(0, 0, dp(12), 0);
        row.addView(preview, ip);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(body, new LinearLayout.LayoutParams(0, dp(82), 1));
        TextView name = title(displayTitle(t), 16);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(name);
        TextView desc = muted(shortText(nonEmpty(t.description, t.category).replace('_', ' '), 120), 12);
        desc.setMaxLines(2);
        desc.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(desc);
        body.addView(muted(t.source + " / " + t.name, 11));
        TextView arrow = muted("›", 28);
        arrow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(22), dp(82)));
        loadPreview(preview, t);
        return row;
    }

    private void renderNodes() {
        content.addView(statusCard(), section());
        content.addView(header("Fields", "Search and edit primitive inputs without the desktop canvas."), section());
        LinearLayout tools = card(false);
        LinearLayout box = row();
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(12), 0, dp(8), 0);
        box.setBackground(bg(surface2(), 12, stroke(), 1));
        box.addView(muted("⌕", 22), new LinearLayout.LayoutParams(dp(30), -1));
        nodeSearch = bareInput("prompt, seed, steps, width…", true);
        nodeSearch.setText(nodeFilter);
        box.addView(nodeSearch, new LinearLayout.LayoutParams(0, -1, 1));
        tools.addView(box, new LinearLayout.LayoutParams(-1, dp(48)));
        content.addView(tools, section());
        nodeList = new LinearLayout(this);
        nodeList.setOrientation(LinearLayout.VERTICAL);
        content.addView(nodeList, new LinearLayout.LayoutParams(-1, -2));
        nodeSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { nodeFilter = String.valueOf(s == null ? "" : s); refreshNodes(); }
            public void afterTextChanged(Editable s) {}
        });
        refreshNodes();
    }

    private void refreshNodes() {
        if (nodeList == null) return;
        nodeList.removeAllViews();
        if (workflow == null) { nodeList.addView(emptyCard("No workflow loaded."), section()); return; }
        normalizeWorkflowFields();
        String q = safe(nodeFilter).trim().toLowerCase(Locale.US);
        int count = 0;
        for (String id : nodeIds()) {
            JSONObject n = workflow.optJSONObject(id);
            if (n == null) continue;
            String hay = id + " " + nodeTitle(n) + " " + n.optString("class_type") + " " + inputKeysText(n.optJSONObject("inputs"));
            if (!q.isEmpty() && !hay.toLowerCase(Locale.US).contains(q)) continue;
            nodeList.addView(nodeRow(id, n), section());
            count++;
        }
        if (count == 0) nodeList.addView(emptyCard("Nothing found."), section());
    }

    private View nodeRow(String id, JSONObject node) {
        LinearLayout c = card(false);
        c.setClickable(true);
        c.setOnClickListener(v -> editNode(id));
        c.addView(title(nodeTitle(node), 16));
        c.addView(muted("#" + id + " · " + prettify(node.optString("class_type", "Node")) + " · " + primitiveCount(node.optJSONObject("inputs")) + " fields", 12));
        return c;
    }

    private void editNode(String id) {
        editingFields.clear();
        content.removeAllViews();
        renderNav();
        JSONObject node = workflow == null ? null : workflow.optJSONObject(id);
        ensureEditableFallback(node);
        content.addView(statusCard(), section());
        content.addView(header(node == null ? "Node" : nodeTitle(node), node == null ? "" : "#" + id + " · " + node.optString("class_type", "")), section());
        LinearLayout c = card(false);
        if (node == null) c.addView(muted("Node not found.", 14));
        else addEditors(c, id, node, false, 999);
        if (editingFields.isEmpty()) c.addView(muted("No direct editable fields in this node.", 14));
        LinearLayout actions = row();
        actions.setPadding(0, dp(12), 0, 0);
        actions.addView(button("Back", false, () -> { screen = "nodes"; render(); }), weight(44));
        actions.addView(button("Save", true, () -> { applyFields(); normalizeWorkflowFields(); saveWorkflow(); setStatus("Fields saved."); }), weight(44));
        c.addView(actions);
        content.addView(c, section());
    }

    private void renderRun() {
        content.addView(statusCard(), section());
        content.addView(header("Run", "Important fields are shown here: prompt, seed, steps, size and model."), section());
        LinearLayout run = card(false);
        run.addView(cardTitle("▷", workflow == null ? "No workflow" : "Ready"));
        run.addView(muted(workflow == null ? "Load workflow first." : workflow.length() + " nodes · outputs: " + outputs.size(), 13));
        LinearLayout actions = row();
        actions.setPadding(0, dp(12), 0, 0);
        actions.addView(button("Outputs", false, () -> { screen = "outputs"; render(); }), weight(44));
        actions.addView(button(busy ? "Waiting…" : "Run ▷", true, this::runWorkflow), weight(44));
        run.addView(actions);
        content.addView(run, section());
        LinearLayout quick = card(true);
        quick.addView(label("Quick fields"));
        if (workflow != null) {
            normalizeWorkflowFields();
            int addedNodes = 0;
            for (String id : nodeIds()) {
                JSONObject n = workflow.optJSONObject(id);
                int before = editingFields.size();
                if (n != null) addEditors(quick, id, n, true, 3);
                if (editingFields.size() > before) addedNodes++;
                if (addedNodes >= 18) break;
            }
        }
        if (editingFields.isEmpty()) quick.addView(muted("No quick fields. Open the Fields tab.", 13));
        quick.addView(button("Save quick fields", true, () -> { applyFields(); normalizeWorkflowFields(); saveWorkflow(); setStatus("Changes saved."); }), lp(-1, 42, 0, 10, 0, 0));
        content.addView(quick, section());
    }

    private void addEditors(LinearLayout parent, String id, JSONObject node, boolean importantOnly, int limit) {
        ensureEditableFallback(node);
        JSONObject inputs = node == null ? null : node.optJSONObject("inputs");
        if (inputs == null) return;
        int count = 0;
        for (String key : inputKeys(inputs)) {
            Object value = inputs.opt(key);
            if (!primitive(value)) continue;
            if (importantOnly && !importantField(key, node.optString("class_type", ""), value)) continue;
            if (count >= limit) break;
            parent.addView(label((importantOnly ? nodeTitle(node) + " · " : "") + prettify(key)));
            EditText e = input("", false);
            e.setText(value == JSONObject.NULL ? "" : String.valueOf(value));
            String lower = key.toLowerCase(Locale.US);
            boolean multi = lower.contains("prompt") || lower.contains("text") || String.valueOf(value).length() > 80;
            boolean numeric = value instanceof Number || lower.equals("seed") || lower.equals("steps") || lower.equals("width") || lower.equals("height") || lower.equals("cfg") || lower.equals("denoise");
            e.setSingleLine(!multi);
            e.setGravity(multi ? Gravity.TOP | Gravity.LEFT : Gravity.CENTER_VERTICAL | Gravity.LEFT);
            e.setInputType(multi ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) : (numeric ? (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL) : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)));
            parent.addView(e, new LinearLayout.LayoutParams(-1, multi ? dp(112) : dp(44)));
            editingFields.add(new FieldRef(id, key, e));
            count++;
        }
    }

    private void renderOutputs() {
        content.addView(statusCard(), section());
        content.addView(header("Outputs", "Opened through /view from ComfyUI."), section());
        if (outputs.isEmpty()) { content.addView(emptyCard("No outputs yet."), section()); return; }
        for (String url : outputs) {
            LinearLayout c = card(false);
            c.addView(muted(shortText(url, 260), 12));
            c.addView(button("Open", true, () -> openUrl(url)), lp(-1, 42, 0, 10, 0, 0));
            content.addView(c, section());
        }
    }

    private void renderHelp() {
        content.addView(statusCard(), section());
        content.addView(header("Connection", "What to configure on the PC."), section());
        LinearLayout c = card(false);
        c.addView(label("Local network"));
        c.addView(muted("Start ComfyUI: python main.py --listen 0.0.0.0 --port 8188. In the app use http://PC_IP:8188. Check Windows firewall.", 13));
        c.addView(label("Tunnel"));
        c.addView(muted("For a domain use https://... With Cloudflare Access fill Client Id and Secret. Requests first use HTTP/1.1 safe mode with Connection: close to reduce unexpected end of stream errors.", 13));
        c.addView(button("Show settings", false, () -> setupPanel.setVisibility(View.VISIBLE)), lp(-1, 42, 0, 10, 0, 0));
        content.addView(c, section());
    }

    private void chooseJson() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(Intent.createChooser(i, "Choose ComfyUI workflow JSON"), REQ_JSON); }
        catch (Exception e) { setStatus("File picker is unavailable."); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_JSON && res == RESULT_OK && data != null && data.getData() != null) {
            try { importJson(readUri(data.getData())); }
            catch (Exception e) { setStatus("Could not read JSON: " + shortError(e)); }
        }
    }

    private void importJson(String raw) {
        if (safe(raw).trim().isEmpty()) { setStatus("JSON is empty."); return; }
        saveConnection();
        setStatus("Importing workflow…");
        IO.execute(() -> {
            try {
                JSONObject result = ComfyWorkflowConverter.importResult(new JSONObject(raw), loadObjectInfo());
                JSONObject prompt = result.optJSONObject("prompt");
                if (prompt == null) prompt = new JSONObject(result.optString("prompt", "{}"));
                JSONObject finalPrompt = prompt;
                ui.post(() -> { workflow = finalPrompt; normalizeWorkflowFields(); saveWorkflow(); screen = "run"; setStatus("Imported nodes: " + workflow.length()); render(); });
            } catch (Exception e) { ui.post(() -> setStatus("Import failed: " + shortError(e))); }
        });
    }

    private void testConnection() {
        saveConnection();
        if (baseCandidates().isEmpty()) { setStatus("Enter ComfyUI URL."); return; }
        setStatus("Testing connection…");
        IO.execute(() -> {
            try {
                JSONObject stats = new JSONObject(getTextAuto("/system_stats"));
                objectInfo = loadObjectInfo();
                JSONObject sys = stats.optJSONObject("system");
                String os = sys == null ? "" : sys.optString("os", "");
                ui.post(() -> { setStatus("Connected: " + hostLabel(activeBase()) + (os.isEmpty() ? "" : " · " + os) + " · nodes: " + objectInfo.length()); render(); });
            } catch (Exception e) { ui.post(() -> { setStatus("Connection error: " + friendlyError(e)); setupPanel.setVisibility(View.VISIBLE); render(); }); }
        });
    }

    private void loadTemplates() {
        if (busy) return;
        saveConnection();
        if (baseCandidates().isEmpty()) { setStatus("Enter ComfyUI URL."); return; }
        busy = true;
        setStatus("Loading templates…");
        IO.execute(() -> {
            ArrayList<TemplateItem> loaded = new ArrayList<>();
            String err = "";
            try {
                JSONArray index = new JSONArray(getTextAuto("/templates/index.json"));
                for (int i = 0; i < index.length(); i++) {
                    JSONObject cat = index.optJSONObject(i);
                    if (cat == null) continue;
                    String source = nonEmpty(cat.optString("moduleName", "default"), "default");
                    String category = nonEmpty(cat.optString("localizedTitle", cat.optString("title", source)), "Templates");
                    JSONArray arr = cat.optJSONArray("templates");
                    if (arr == null) continue;
                    for (int j = 0; j < arr.length(); j++) {
                        JSONObject raw = arr.optJSONObject(j);
                        if (raw == null) continue;
                        TemplateItem t = new TemplateItem();
                        t.source = source;
                        t.name = raw.optString("name", "").trim();
                        t.title = raw.optString("localizedTitle", raw.optString("title", t.name));
                        t.description = raw.optString("localizedDescription", raw.optString("description", ""));
                        t.category = category;
                        t.mediaSubtype = raw.optString("mediaSubtype", "webp");
                        if (!t.name.isEmpty()) loaded.add(t);
                    }
                }
            } catch (Exception e) { err = shortError(e); }
            try {
                JSONObject custom = new JSONObject(getTextAuto("/api/workflow_templates"));
                Iterator<String> keys = custom.keys();
                while (keys.hasNext()) {
                    String source = keys.next();
                    JSONArray arr = custom.optJSONArray(source);
                    if (arr == null) continue;
                    for (int i = 0; i < arr.length(); i++) {
                        String name = arr.optString(i, "").trim();
                        if (name.isEmpty()) continue;
                        TemplateItem t = new TemplateItem();
                        t.source = source; t.name = name; t.title = name; t.description = source; t.category = "Custom templates"; t.mediaSubtype = "";
                        loaded.add(t);
                    }
                }
            } catch (Exception ignored) {}
            String finalErr = err;
            ui.post(() -> {
                busy = false;
                if (!loaded.isEmpty()) {
                    templates.clear(); templates.addAll(loaded); templatesAt = System.currentTimeMillis(); saveTemplates(); templateLimit = PAGE_SIZE; setStatus("Loaded templates: " + templates.size()); refreshTemplates();
                } else { setStatus("Templates did not load: " + (finalErr.isEmpty() ? "empty response" : finalErr)); refreshTemplates(); }
            });
        });
    }

    private void openTemplate(TemplateItem t) {
        if (t == null || t.name.trim().isEmpty()) return;
        saveConnection();
        setStatus("Opening template: " + displayTitle(t));
        IO.execute(() -> {
            try {
                String raw = getTextAuto(templatePath(t));
                JSONObject result = ComfyWorkflowConverter.importResult(new JSONObject(raw), loadObjectInfo());
                JSONObject prompt = result.optJSONObject("prompt");
                if (prompt == null) prompt = new JSONObject(result.optString("prompt", "{}"));
                JSONObject finalPrompt = prompt;
                ui.post(() -> { workflow = finalPrompt; normalizeWorkflowFields(); saveWorkflow(); screen = "run"; setStatus("Template loaded: " + finalPrompt.length() + " nodes"); render(); });
            } catch (Exception e) { ui.post(() -> setStatus("Template open failed: " + friendlyError(e))); }
        });
    }

    private void runWorkflow() {
        if (busy) return;
        if (workflow == null || workflow.length() == 0) { setStatus("Load workflow first."); return; }
        saveConnection();
        applyFields();
        normalizeWorkflowFields();
        saveWorkflow();
        busy = true;
        setStatus("Sending prompt…");
        IO.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("prompt", workflow);
                payload.put("client_id", "comfyui-mobile-" + System.currentTimeMillis());
                JSONObject response = new JSONObject(postJsonAuto("/prompt", payload.toString()));
                promptId = response.optString("prompt_id", "");
                if (promptId.isEmpty()) throw new Exception("ComfyUI did not return prompt_id");
                pollCount = 0;
                ui.post(() -> { setStatus("Queued. Waiting for output…"); pollHistory(); });
            } catch (Exception e) { ui.post(() -> { busy = false; setStatus("Run failed: " + friendlyError(e)); render(); }); }
        });
    }

    private void pollHistory() {
        if (promptId.isEmpty()) { busy = false; return; }
        pollCount++;
        IO.execute(() -> {
            try {
                ArrayList<String> found = findOutputs(new JSONObject(getTextAuto("/history/" + enc(promptId))));
                if (!found.isEmpty()) {
                    outputs.addAll(0, found);
                    while (outputs.size() > 25) outputs.remove(outputs.size() - 1);
                    saveOutputs();
                    ui.post(() -> { busy = false; screen = "outputs"; setStatus("Output ready."); render(); });
                    return;
                }
            } catch (Exception ignored) {}
            if (pollCount < 360) ui.postDelayed(this::pollHistory, 2000);
            else ui.post(() -> { busy = false; setStatus("Timed out waiting for output."); render(); });
        });
    }

    private void loadPreview(ImageView image, TemplateItem item) {
        String key = activeBase() + "|preview|" + item.id();
        Bitmap cached = IMAGES.get(key);
        if (cached != null) { image.setImageBitmap(cached); return; }
        IO.execute(() -> {
            for (String ext : previewExts(item)) {
                try {
                    Bitmap b = decodePreview(autoBytes(previewPath(item, ext, false), null));
                    if (b != null) { IMAGES.put(key, b); ui.post(() -> { if (String.valueOf(image.getTag()).equals(item.id())) image.setImageBitmap(b); }); return; }
                } catch (Exception ignored) {}
                try {
                    Bitmap b = decodePreview(autoBytes(previewPath(item, ext, true), null));
                    if (b != null) { IMAGES.put(key, b); ui.post(() -> { if (String.valueOf(image.getTag()).equals(item.id())) image.setImageBitmap(b); }); return; }
                } catch (Exception ignored) {}
            }
        });
    }

    private Bitmap decodePreview(byte[] data) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inSampleSize = 1;
            while ((bounds.outWidth / opts.inSampleSize) > 360 || (bounds.outHeight / opts.inSampleSize) > 280) opts.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception e) { return null; }
    }

    private JSONObject loadObjectInfo() {
        try { objectInfo = new JSONObject(getTextAuto("/api/object_info")); prefs().edit().putString(KEY_OBJECT_INFO, objectInfo.toString()).apply(); return objectInfo; }
        catch (Exception e) { try { return new JSONObject(prefs().getString(KEY_OBJECT_INFO, "{}")); } catch (Exception ignored) { return new JSONObject(); } }
    }

    private String getTextAuto(String path) throws Exception { return new String(autoBytes(path, null), "UTF-8"); }
    private String postJsonAuto(String path, String body) throws Exception { return new String(autoBytes(path, RequestBody.create(body, JSON)), "UTF-8"); }

    private byte[] autoBytes(String path, RequestBody body) throws Exception {
        StringBuilder errors = new StringBuilder();
        for (String base : baseCandidates()) {
            try { byte[] d = request(HTTP_SAFE, base + path, body, true); activeBase = base; prefs().edit().putString(KEY_URL, base).apply(); return d; }
            catch (Exception e) { errors.append(hostLabel(base)).append(" safe: ").append(shortError(e)).append('\n'); }
            try { byte[] d = request(HTTP_NORMAL, base + path, body, false); activeBase = base; prefs().edit().putString(KEY_URL, base).apply(); return d; }
            catch (Exception e) { errors.append(hostLabel(base)).append(" normal: ").append(shortError(e)).append('\n'); }
        }
        throw new Exception(errors.length() == 0 ? "no ComfyUI URL" : errors.toString().trim());
    }

    private byte[] request(OkHttpClient client, String url, RequestBody body, boolean safeMode) throws Exception {
        Request.Builder b = new Request.Builder().url(url)
                .header("Accept", "application/json,text/plain,image/*,*/*")
                .header("User-Agent", "Mozilla/5.0 Android ComfyUI-Mobile " + VERSION);
        if (safeMode) {
            b.header("Connection", "close");
            b.header("Accept-Encoding", "identity");
            b.header("Cache-Control", "no-cache");
            b.header("Pragma", "no-cache");
        }
        for (Map.Entry<String, String> h : authHeaders(url).entrySet()) b.header(h.getKey(), h.getValue());
        if (body != null) b.post(body);
        try (Response r = client.newCall(b.build()).execute()) {
            byte[] out = r.body() == null ? new byte[0] : r.body().bytes();
            if (out.length > MAX_BODY) throw new Exception("response is too large");
            if (!r.isSuccessful()) throw new Exception("HTTP " + r.code() + ": " + shortText(new String(out, "UTF-8"), 220));
            return out;
        }
    }

    private Map<String, String> authHeaders(String url) {
        HashMap<String, String> h = new HashMap<>();
        String id = cfIdInput == null ? "" : cfIdInput.getText().toString().trim();
        String secret = cfSecretInput == null ? "" : cfSecretInput.getText().toString().trim();
        if (!id.isEmpty() && !secret.isEmpty()) { h.put("CF-Access-Client-Id", id); h.put("CF-Access-Client-Secret", secret); }
        try { String cookies = CookieManager.getInstance().getCookie(url); if (cookies != null && !cookies.trim().isEmpty()) h.put("Cookie", cookies); } catch (Exception ignored) {}
        return h;
    }

    private List<String> baseCandidates() {
        String raw = rawUrl();
        ArrayList<String> out = new ArrayList<>();
        if (raw.isEmpty()) return out;
        if (raw.startsWith("//")) raw = raw.substring(2);
        String l = raw.toLowerCase(Locale.US);
        if (l.startsWith("http://") || l.startsWith("https://")) addBase(out, raw);
        else if (looksLocal(raw)) { addBase(out, "http://" + raw); addBase(out, "https://" + raw); }
        else { addBase(out, "https://" + raw); addBase(out, "http://" + raw); }
        return out;
    }

    private boolean looksLocal(String raw) {
        String s = raw.toLowerCase(Locale.US);
        return s.startsWith("192.168.") || s.startsWith("10.") || s.startsWith("127.") || s.startsWith("localhost") || s.contains(".local") || s.matches("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*") || s.endsWith(":8188");
    }

    private void addBase(ArrayList<String> out, String candidate) {
        String base = strip(candidate);
        try { URL u = new URL(base); if (u.getHost() != null && !u.getHost().trim().isEmpty() && !out.contains(base)) out.add(base); } catch (Exception ignored) {}
    }

    private ArrayList<String> findOutputs(JSONObject history) {
        ArrayList<String> urls = new ArrayList<>();
        try {
            Iterator<String> prompts = history.keys();
            while (prompts.hasNext()) {
                JSONObject item = history.optJSONObject(prompts.next());
                JSONObject outs = item == null ? null : item.optJSONObject("outputs");
                if (outs == null) continue;
                Iterator<String> nodes = outs.keys();
                while (nodes.hasNext()) {
                    JSONObject out = outs.optJSONObject(nodes.next());
                    addOutputUrls(urls, out == null ? null : out.optJSONArray("images"));
                    addOutputUrls(urls, out == null ? null : out.optJSONArray("videos"));
                    addOutputUrls(urls, out == null ? null : out.optJSONArray("gifs"));
                }
            }
        } catch (Exception ignored) {}
        return urls;
    }

    private void addOutputUrls(ArrayList<String> urls, JSONArray arr) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject f = arr.optJSONObject(i);
            if (f == null) continue;
            String name = f.optString("filename", "");
            if (!name.isEmpty()) urls.add(activeBase() + "/view?filename=" + enc(name) + "&type=" + enc(f.optString("type", "output")) + "&subfolder=" + enc(f.optString("subfolder", "")));
        }
    }

    private void applyFields() {
        if (workflow == null) return;
        for (FieldRef f : editingFields) {
            try { JSONObject n = workflow.optJSONObject(f.nodeId); if (n != null) n.getJSONObject("inputs").put(f.key, coerce(f.edit.getText().toString())); } catch (Exception ignored) {}
        }
    }

    private void normalizeWorkflowFields() {
        if (workflow == null) return;
        for (String id : nodeIds()) ensureEditableFallback(workflow.optJSONObject(id));
    }

    private void ensureEditableFallback(JSONObject node) {
        if (node == null) return;
        try {
            String cls = node.optString("class_type", "").toLowerCase(Locale.US).replace("_", "");
            JSONObject inputs = node.optJSONObject("inputs");
            if (inputs == null) { inputs = new JSONObject(); node.put("inputs", inputs); }
            if (cls.contains("cliptextencode") && !inputs.has("text")) inputs.put("text", "");
        } catch (Exception ignored) {}
    }

    private Object coerce(String raw) {
        String s = raw == null ? "" : raw.trim();
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;
        try { if (s.matches("-?\\d+")) return Long.parseLong(s); } catch (Exception ignored) {}
        try { if (s.matches("-?\\d+\\.\\d+")) return Double.parseDouble(s); } catch (Exception ignored) {}
        return raw == null ? "" : raw;
    }

    private ArrayList<String> nodeIds() { ArrayList<String> ids = new ArrayList<>(); if (workflow == null) return ids; Iterator<String> it = workflow.keys(); while (it.hasNext()) ids.add(it.next()); Collections.sort(ids); return ids; }
    private List<String> inputKeys(JSONObject o) { ArrayList<String> keys = new ArrayList<>(); if (o == null) return keys; Iterator<String> it = o.keys(); while (it.hasNext()) keys.add(it.next()); Collections.sort(keys); return keys; }
    private String inputKeysText(JSONObject o) { StringBuilder s = new StringBuilder(); for (String k : inputKeys(o)) s.append(' ').append(k); return s.toString(); }
    private boolean primitive(Object v) { return v == JSONObject.NULL || v instanceof String || v instanceof Number || v instanceof Boolean; }
    private int primitiveCount(JSONObject o) { int n = 0; for (String k : inputKeys(o)) if (primitive(o.opt(k))) n++; return n; }
    private String nodeTitle(JSONObject n) { JSONObject meta = n == null ? null : n.optJSONObject("_meta"); return prettify(nonEmpty(meta == null ? "" : meta.optString("title", ""), n == null ? "Node" : n.optString("class_type", "Node"))); }
    private boolean importantField(String key, String cls, Object value) { String k = safe(key).toLowerCase(Locale.US); return k.contains("prompt") || k.contains("text") || k.equals("seed") || k.equals("steps") || k.equals("cfg") || k.equals("denoise") || k.equals("width") || k.equals("height") || k.equals("sampler_name") || k.equals("scheduler") || k.equals("ckpt_name") || k.equals("lora_name") || k.equals("filename_prefix") || String.valueOf(value).length() > 120; }

    private void readTemplates(String raw) { templates.clear(); try { JSONArray arr = new JSONArray(raw == null ? "[]" : raw); for (int i = 0; i < arr.length(); i++) { TemplateItem t = TemplateItem.fromJson(arr.optJSONObject(i)); if (!t.name.trim().isEmpty()) templates.add(t); } } catch (Exception ignored) {} }
    private void saveTemplates() { JSONArray arr = new JSONArray(); for (TemplateItem t : templates) try { arr.put(t.toJson()); } catch (Exception ignored) {} prefs().edit().putString(KEY_TEMPLATES, arr.toString()).putLong(KEY_TEMPLATES_AT, templatesAt).apply(); }
    private void readOutputs() { outputs.clear(); try { JSONArray arr = new JSONArray(prefs().getString(KEY_OUTPUTS, "[]")); for (int i = 0; i < arr.length(); i++) { String s = arr.optString(i, ""); if (!s.trim().isEmpty()) outputs.add(s); } } catch (Exception ignored) {} }
    private void saveOutputs() { JSONArray arr = new JSONArray(); for (String s : outputs) arr.put(s); prefs().edit().putString(KEY_OUTPUTS, arr.toString()).apply(); }
    private String templatePath(TemplateItem t) { return "default".equals(t.source) ? "/templates/" + encPath(t.name) + ".json" : "/api/workflow_templates/" + encPath(t.source) + "/" + encPath(t.name) + ".json"; }
    private String previewPath(TemplateItem t, String ext, boolean fallback) { return "default".equals(t.source) ? "/templates/" + encPath(t.name) + (fallback ? "" : "-1") + "." + ext : "/api/workflow_templates/" + encPath(t.source) + "/" + encPath(t.name) + "." + ext; }
    private ArrayList<String> previewExts(TemplateItem t) { LinkedHashSet<String> out = new LinkedHashSet<>(); String m = safe(t.mediaSubtype).toLowerCase(Locale.US).trim(); if (!m.isEmpty()) out.add(m); out.add("webp"); out.add("png"); out.add("jpg"); out.add("jpeg"); out.add("gif"); return new ArrayList<>(out); }
    private String templateText(TemplateItem t) { return displayTitle(t) + " " + t.name + " " + t.description + " " + t.category + " " + t.source; }
    private String displayTitle(TemplateItem t) { return nonEmpty(t.title, t.name); }

    private void saveConnection() { prefs().edit().putString(KEY_URL, rawUrl()).putString(KEY_CF_ID, cfIdInput.getText().toString().trim()).putString(KEY_CF_SECRET, cfSecretInput.getText().toString().trim()).apply(); try { CookieManager.getInstance().flush(); } catch (Exception ignored) {} }
    private void saveWorkflow() { prefs().edit().putString(KEY_WORKFLOW, workflow == null ? "{}" : workflow.toString()).apply(); }
    private SharedPreferences prefs() { return getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private String rawUrl() { String s = urlInput == null ? "" : urlInput.getText().toString().trim(); if (s.isEmpty()) s = prefs().getString(KEY_URL, ""); return strip(s); }
    private String strip(String s) { if (s == null) return ""; s = s.trim(); while (s.endsWith("/")) s = s.substring(0, s.length() - 1); return s; }
    private String activeBase() { if (!activeBase.trim().isEmpty()) return activeBase; List<String> b = baseCandidates(); return b.isEmpty() ? "" : b.get(0); }
    private String hostLabel(String base) { try { return new URL(base).getHost(); } catch (Exception e) { return base; } }

    private View statusCard() {
        String base = activeBase();
        LinearLayout c = row(); c.setGravity(Gravity.CENTER_VERTICAL); c.setPadding(dp(12), dp(9), dp(12), dp(9)); c.setBackground(bg(surface(), 14, stroke(), 1));
        c.addView(text(base.isEmpty() ? "○" : "●", 18, base.isEmpty() ? mutedColor() : rgb(65, 190, 103)), new LinearLayout.LayoutParams(dp(26), dp(42)));
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        TextView h = muted(base.isEmpty() ? "Tap to set ComfyUI URL" : hostLabel(base), 12); h.setSingleLine(true); body.addView(h);
        statusLine = muted(shortText(status, 170), 12); statusLine.setMaxLines(2); body.addView(statusLine);
        c.addView(body, new LinearLayout.LayoutParams(0, -2, 1));
        c.addView(muted("›", 22), new LinearLayout.LayoutParams(dp(24), dp(42)));
        c.setOnClickListener(v -> setupPanel.setVisibility(setupPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        return c;
    }

    private View header(String t, String sub) { LinearLayout b = new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL); b.addView(title(t, 29)); TextView s = muted(sub, 14); s.setMaxLines(3); b.addView(s); return b; }
    private View cardTitle(String icon, String text) { LinearLayout r = row(); r.setGravity(Gravity.CENTER_VERTICAL); r.addView(badge(icon), new LinearLayout.LayoutParams(dp(34), dp(34))); TextView t = title(text, 19); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); p.setMargins(dp(10), 0, 0, 0); r.addView(t, p); return r; }
    private TextView badge(String s) { TextView v = text(s, 14, accent()); v.setGravity(Gravity.CENTER); v.setBackground(bg(Color.rgb(38, 31, 22), 12, stroke(), 1)); return v; }
    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); return r; }
    private LinearLayout card(boolean accentBorder) { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(12), dp(12), dp(12), dp(12)); c.setBackground(bg(surface(), 16, accentBorder ? accent() : stroke(), 1)); return c; }
    private View emptyCard(String s) { LinearLayout c = card(false); c.addView(muted(s, 14)); return c; }
    private EditText input(String hint, boolean single) { EditText e = new EditText(this); e.setHint(hint); e.setSingleLine(single); e.setTextColor(Color.WHITE); e.setHintTextColor(mutedColor()); e.setTextSize(14); e.setPadding(dp(12), 0, dp(12), 0); e.setBackground(bg(surface2(), 12, stroke(), 1)); return e; }
    private EditText bareInput(String hint, boolean single) { EditText e = new EditText(this); e.setHint(hint); e.setSingleLine(single); e.setTextColor(Color.WHITE); e.setHintTextColor(mutedColor()); e.setTextSize(14); e.setPadding(dp(8), 0, 0, 0); e.setBackgroundColor(Color.TRANSPARENT); return e; }
    private Button button(String label, boolean primary, Runnable action) { Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setSingleLine(true); b.setTextSize(13); b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); b.setTextColor(primary ? accent() : Color.WHITE); b.setPadding(dp(4), 0, dp(4), 0); b.setBackground(bg(primary ? Color.rgb(45, 35, 24) : surface2(), 12, primary ? accent() : stroke(), 1)); b.setOnClickListener(v -> action.run()); return b; }
    private View navItem(String icon, String label, String target) { boolean sel = target.equals(screen); LinearLayout b = new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL); b.setGravity(Gravity.CENTER); b.setPadding(0, dp(4), 0, dp(4)); b.setBackground(sel ? bg(Color.rgb(38,31,22), 12, accent(), 1) : bg(Color.TRANSPARENT, 12, Color.TRANSPARENT, 0)); TextView i = text(icon, 18, sel ? accent() : mutedColor()); i.setGravity(Gravity.CENTER); b.addView(i, new LinearLayout.LayoutParams(-1, dp(24))); TextView l = text(label, 10, sel ? accent() : mutedColor()); l.setGravity(Gravity.CENTER); l.setSingleLine(true); b.addView(l, new LinearLayout.LayoutParams(-1, dp(20))); b.setOnClickListener(v -> { screen = target; render(); }); return b; }
    private void renderNav() { nav.removeAllViews(); nav.addView(navItem("⌂", "Home", "home"), weight(56)); nav.addView(navItem("▦", "Templates", "templates"), weight(56)); nav.addView(navItem("⌘", "Fields", "nodes"), weight(56)); nav.addView(navItem("▷", "Run", "run"), weight(56)); nav.addView(navItem("▧", "Output", "outputs"), weight(56)); nav.addView(navItem("?", "Help", "help"), weight(46)); }
    private LinearLayout.LayoutParams section() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, 0, 0, dp(12)); return p; }
    private LinearLayout.LayoutParams weight(int h) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(h), 1); p.setMargins(dp(3), 0, dp(3), 0); return p; }
    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h < 0 ? h : dp(h)); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }
    private TextView title(String s, int sp) { TextView t = text(s, sp, Color.WHITE); t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); t.setMaxLines(3); t.setEllipsize(TextUtils.TruncateAt.END); return t; }
    private TextView label(String s) { TextView t = text(s, 13, Color.rgb(216,216,220)); t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); return t; }
    private TextView muted(String s, int sp) { return text(s, sp, mutedColor()); }
    private TextView text(String s, int sp, int color) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setIncludeFontPadding(false); t.setPadding(dp(2), 0, dp(2), dp(5)); return t; }
    private GradientDrawable bg(int color, int radius, int strokeColor, int strokeWidth) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); d.setStroke(dp(strokeWidth), strokeColor); return d; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private int rgb(int r, int g, int b) { return Color.rgb(r, g, b); }
    private int bgRoot() { return Color.rgb(18,18,19); }
    private int surface() { return Color.rgb(28,28,30); }
    private int surface2() { return Color.rgb(34,34,37); }
    private int stroke() { return Color.rgb(52,52,56); }
    private int mutedColor() { return Color.rgb(174,174,182); }
    private int accent() { return Color.rgb(218,143,60); }
    private void setStatus(String s) { status = safe(s); if (statusLine != null) statusLine.setText(shortText(status, 180)); }
    private void openUrl(String url) { try { if (safe(url).trim().isEmpty()) { setStatus("ComfyUI URL is empty."); return; } startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { setStatus("Could not open URL."); } }
    private void styleBars() { Window w = getWindow(); w.setStatusBarColor(bgRoot()); w.setNavigationBarColor(bgRoot()); w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE); }
    private String readUri(Uri uri) throws Exception { InputStream in = getContentResolver().openInputStream(uri); if (in == null) throw new Exception("empty file"); try { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); return out.toString("UTF-8"); } finally { in.close(); } }
    private String enc(String s) { try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; } }
    private String encPath(String s) { return enc(s).replace("%2F", "/"); }
    private String safe(String s) { return s == null ? "" : s; }
    private String nonEmpty(String v, String fallback) { return v == null || v.trim().isEmpty() ? safe(fallback) : v.trim(); }
    private String firstNonEmpty(String a, String b) { return a != null && !a.trim().isEmpty() ? a : safe(b); }
    private String shortText(String s, int max) { if (s == null) return ""; return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…"; }
    private String shortError(Exception e) { String s = e == null ? "" : e.getMessage(); if (s == null || s.trim().isEmpty()) s = e == null ? "unknown" : e.getClass().getSimpleName(); return shortText(s.replace('\n', ' '), 260); }
    private String friendlyError(Exception e) { String s = shortError(e); String l = s.toLowerCase(Locale.US); if (l.contains("unexpected end of stream") || l.contains("stream was reset")) return s + " · tunnel closed the stream. Check https://, Cloudflare headers and /system_stats."; if (l.contains("failed to connect") || l.contains("timeout")) return s + " · check PC IP, port 8188, firewall and --listen 0.0.0.0."; if (l.contains("401") || l.contains("403")) return s + " · access is blocked by Cloudflare/proxy."; return s; }
    private String timeAgo(long ts) { long sec = Math.max(0, (System.currentTimeMillis() - ts) / 1000); if (sec < 60) return "just now"; long min = sec / 60; if (min < 60) return min + "m ago"; long h = min / 60; if (h < 24) return h + "h ago"; return (h / 24) + "d ago"; }
    private String prettify(String s) { return safe(s).replace('_', ' ').replaceAll("([a-z])([A-Z])", "$1 $2").trim(); }
}
