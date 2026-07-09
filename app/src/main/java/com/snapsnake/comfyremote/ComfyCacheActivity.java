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
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
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
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

public class ComfyCacheActivity extends Activity {
    private static final String VERSION = "0.12.2-real-cache-standalone";
    private static final String PREFS = "comfy_real_cache_v1";
    private static final String OLD_REMOTE_PREFS = "comfyui_remote_prefs";
    private static final String OLD_TEMPLATE_PREFS = "comfyui_template_cache";
    private static final String KEY_URL = "url";
    private static final String KEY_CF_ID = "cf_id";
    private static final String KEY_CF_SECRET = "cf_secret";
    private static final String KEY_TEMPLATES = "templates_json";
    private static final String KEY_TEMPLATES_AT = "templates_at";
    private static final String KEY_WORKFLOW = "workflow";
    private static final String KEY_OBJECT_INFO = "object_info";
    private static final String KEY_OUTPUT = "output_url";
    private static final int REQ_JSON = 12201;
    private static final int PAGE_SIZE = 60;
    private static final int MAX_BODY_BYTES = 32 * 1024 * 1024;
    private static final ExecutorService IO = Executors.newFixedThreadPool(5);
    private static final LruCache<String, Bitmap> BITMAP_CACHE = new LruCache<>(80);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    private static final OkHttpClient HTTP_SAFE = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .build();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private LinearLayout topPanel;
    private LinearLayout content;
    private LinearLayout nav;
    private LinearLayout templateList;
    private LinearLayout nodeList;
    private ScrollView scroll;
    private EditText urlInput;
    private EditText cfIdInput;
    private EditText cfSecretInput;
    private EditText templateSearch;
    private EditText nodeSearch;
    private EditText jsonInput;
    private TextView statusLine;
    private TextView countLine;
    private TextView updatedLine;
    private final ArrayList<TemplateItem> templates = new ArrayList<>();
    private final ArrayList<FieldRef> editingFields = new ArrayList<>();
    private final Set<String> expandedGroups = new HashSet<>();
    private JSONObject workflow;
    private JSONObject objectInfo = new JSONObject();
    private String screen = "create";
    private String activeBase = "";
    private String templateFilter = "";
    private String nodeFilter = "";
    private String promptId = "";
    private String outputUrl = "";
    private long templatesAt = 0L;
    private int templateLimit = PAGE_SIZE;
    private int pollCount = 0;
    private boolean cacheLoading = false;

    private static class TemplateItem {
        String source = "default";
        String name = "";
        String title = "";
        String description = "";
        String category = "Templates";
        String mediaSubtype = "webp";
        String id() { return safe(source) + "/" + safe(name); }
        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("source", safe(source));
            o.put("name", safe(name));
            o.put("title", safe(title));
            o.put("description", safe(description));
            o.put("category", safe(category));
            o.put("mediaSubtype", safe(mediaSubtype));
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
        static String safe(String s) { return s == null ? "" : s; }
    }

    private static class FieldRef {
        final String nodeId;
        final String key;
        final EditText edit;
        FieldRef(String nodeId, String key, EditText edit) {
            this.nodeId = nodeId;
            this.key = key;
            this.edit = edit;
        }
    }

    private static class OutputRef {
        final String filename;
        final String subfolder;
        final String type;
        OutputRef(String filename, String subfolder, String type) {
            this.filename = filename;
            this.subfolder = subfolder;
            this.type = type;
        }
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

        topPanel = new LinearLayout(this);
        topPanel.setOrientation(LinearLayout.VERTICAL);
        topPanel.setPadding(dp(18), dp(12), dp(18), dp(12));
        topPanel.setBackgroundColor(surface());
        root.addView(topPanel, new LinearLayout.LayoutParams(-1, -2));
        topPanel.addView(title("ComfyUI Mobile", 18));
        topPanel.addView(muted("Native launcher · " + VERSION, 11));
        urlInput = input("https://comfyui.example.com", true);
        topPanel.addView(urlInput, lp(-1, 46, 0, 8, 0, 8));
        LinearLayout quick = row();
        quick.addView(button("Test", false, this::testConnection), weight(42));
        quick.addView(button("Hide", false, () -> topPanel.setVisibility(View.GONE)), weight(42));
        quick.addView(button("Templates", true, () -> { screen = "templates"; render(); }), weight(42));
        topPanel.addView(quick, new LinearLayout.LayoutParams(-1, dp(42)));
        topPanel.addView(muted("Cloudflare Access optional", 12), lp(-1, -2, 0, 8, 0, 4));
        cfIdInput = input("CF-Access-Client-Id", true);
        topPanel.addView(cfIdInput, new LinearLayout.LayoutParams(-1, dp(42)));
        cfSecretInput = input("CF-Access-Client-Secret", true);
        cfSecretInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        topPanel.addView(cfSecretInput, lp(-1, 42, 0, 6, 0, 0));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(bgRoot());
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(20));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(14), dp(8), dp(14), dp(8));
        nav.setBackgroundColor(surface());
        root.addView(nav, new LinearLayout.LayoutParams(-1, dp(78)));
        setContentView(root);
    }

    private void loadState() {
        SharedPreferences p = prefs();
        SharedPreferences old = getSharedPreferences(OLD_REMOTE_PREFS, Context.MODE_PRIVATE);
        String url = firstNonEmpty(p.getString(KEY_URL, ""), old.getString("comfyui_url", ""));
        urlInput.setText(url);
        cfIdInput.setText(firstNonEmpty(p.getString(KEY_CF_ID, ""), old.getString("cf_access_client_id", "")));
        cfSecretInput.setText(firstNonEmpty(p.getString(KEY_CF_SECRET, ""), old.getString("cf_access_client_secret", "")));
        if (!url.trim().isEmpty()) topPanel.setVisibility(View.GONE);
        outputUrl = p.getString(KEY_OUTPUT, "");
        try {
            workflow = new JSONObject(p.getString(KEY_WORKFLOW, "{}"));
            if (workflow.length() == 0) workflow = null;
        } catch (Exception e) { workflow = null; }
        try { objectInfo = new JSONObject(p.getString(KEY_OBJECT_INFO, "{}")); } catch (Exception e) { objectInfo = new JSONObject(); }
        loadTemplateCache();
    }

    private void render() {
        editingFields.clear();
        content.removeAllViews();
        renderNav();
        if ("templates".equals(screen)) renderTemplates();
        else if ("nodes".equals(screen)) renderNodes();
        else if ("run".equals(screen)) renderRun();
        else if ("output".equals(screen)) renderOutput();
        else renderCreate();
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void renderCreate() {
        content.addView(statusChip(), section());
        content.addView(header("Create", workflow == null ? "Load a cached ComfyUI template or import workflow JSON." : workflow.length() + " nodes loaded."), section());
        LinearLayout card = card(false);
        card.addView(cardTitle("‹/›", "Workflow"));
        LinearLayout actions = row();
        actions.setPadding(0, dp(10), 0, dp(10));
        actions.addView(button("▦ Templates", false, () -> { screen = "templates"; render(); }), weight(46));
        actions.addView(button("⇩ Import JSON", true, this::chooseJson), weight(46));
        card.addView(actions);
        if (workflow == null) {
            card.addView(label("Fallback: paste workflow JSON"));
            jsonInput = input("Paste ComfyUI workflow/API JSON here…", false);
            jsonInput.setSingleLine(false);
            jsonInput.setGravity(Gravity.TOP | Gravity.LEFT);
            jsonInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            card.addView(jsonInput, new LinearLayout.LayoutParams(-1, dp(150)));
            LinearLayout row = row();
            row.setPadding(0, dp(12), 0, 0);
            row.addView(button("Load JSON", false, this::chooseJson), weight(44));
            row.addView(button("Apply JSON", true, () -> importJson(jsonInput.getText().toString())), weight(44));
            card.addView(row);
        } else {
            card.addView(muted("Edit fields in Nodes or execute in Run.", 13));
            LinearLayout row = row();
            row.setPadding(0, dp(12), 0, 0);
            row.addView(button("Nodes", false, () -> { screen = "nodes"; render(); }), weight(44));
            row.addView(button("Run", true, () -> { screen = "run"; render(); }), weight(44));
            card.addView(row);
        }
        content.addView(card, section());
        LinearLayout tip = card(true);
        tip.addView(label("Real template cache"));
        tip.addView(muted("Load/Update Cache downloads the actual ComfyUI template list once and stores it locally. Search Clear does not delete cache.", 13));
        content.addView(tip, section());
    }

    private void renderTemplates() {
        content.addView(statusChip(), section());
        content.addView(header("Templates", "Local cache of real templates from your ComfyUI"), section());
        LinearLayout tools = card(false);
        LinearLayout searchBox = row();
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(dp(12), 0, dp(10), 0);
        searchBox.setBackground(bg(surface2(), 10, stroke(), 1));
        searchBox.addView(muted("⌕", 22), new LinearLayout.LayoutParams(dp(30), -1));
        templateSearch = bareInput("Search cached templates…", true);
        templateSearch.setText(templateFilter);
        searchBox.addView(templateSearch, new LinearLayout.LayoutParams(0, -1, 1));
        tools.addView(searchBox, new LinearLayout.LayoutParams(-1, dp(46)));
        LinearLayout meta = row();
        meta.setPadding(0, dp(8), 0, 0);
        countLine = muted("", 12);
        updatedLine = muted("", 12);
        updatedLine.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        meta.addView(countLine, new LinearLayout.LayoutParams(0, dp(30), 1));
        meta.addView(updatedLine, new LinearLayout.LayoutParams(0, dp(30), 1));
        tools.addView(meta);
        LinearLayout buttons = row();
        buttons.setPadding(0, dp(4), 0, dp(4));
        buttons.addView(button(cacheLoading ? "⟳ Loading…" : "⟳ Load/Update Cache", true, this::loadOrUpdateTemplateCache), weight(42));
        buttons.addView(button("⌫ Clear Search", false, () -> { templateFilter = ""; templateLimit = PAGE_SIZE; if (templateSearch != null) templateSearch.setText(""); refreshTemplateList(); }), weight(42));
        tools.addView(buttons);
        statusLine = muted("Cache contains only data downloaded from ComfyUI. No bundled templates.", 12);
        statusLine.setSingleLine(true);
        statusLine.setEllipsize(TextUtils.TruncateAt.END);
        tools.addView(statusLine);
        content.addView(tools, section());
        templateList = new LinearLayout(this);
        templateList.setOrientation(LinearLayout.VERTICAL);
        content.addView(templateList, new LinearLayout.LayoutParams(-1, -2));
        templateSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                templateFilter = String.valueOf(s == null ? "" : s);
                templateLimit = PAGE_SIZE;
                refreshTemplateList();
            }
            public void afterTextChanged(Editable s) {}
        });
        refreshTemplateMeta();
        refreshTemplateList();
    }

    private void loadOrUpdateTemplateCache() {
        if (cacheLoading) return;
        saveConnection();
        if (baseCandidates().isEmpty()) {
            setStatus("Set a valid ComfyUI URL first.");
            topPanel.setVisibility(View.VISIBLE);
            return;
        }
        cacheLoading = true;
        setStatus("Loading template list from ComfyUI...");
        IO.execute(() -> {
            ArrayList<TemplateItem> loaded = new ArrayList<>();
            String error = "";
            try {
                JSONArray index = new JSONArray(getTextAuto("/templates/index.json"));
                for (int i = 0; i < index.length(); i++) {
                    JSONObject category = index.optJSONObject(i);
                    if (category == null) continue;
                    String source = nonEmpty(category.optString("moduleName", "default"), "default");
                    String categoryTitle = nonEmpty(category.optString("localizedTitle", category.optString("title", source)), "Templates");
                    JSONArray arr = category.optJSONArray("templates");
                    if (arr == null) continue;
                    for (int j = 0; j < arr.length(); j++) {
                        JSONObject raw = arr.optJSONObject(j);
                        if (raw == null) continue;
                        TemplateItem t = new TemplateItem();
                        t.source = source;
                        t.name = raw.optString("name", "").trim();
                        t.title = raw.optString("localizedTitle", raw.optString("title", t.name));
                        t.description = raw.optString("localizedDescription", raw.optString("description", ""));
                        t.category = categoryTitle;
                        t.mediaSubtype = raw.optString("mediaSubtype", "webp");
                        if (validTemplate(t)) loaded.add(t);
                    }
                }
            } catch (Exception e) {
                error = shortError(e);
            }
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
                        t.source = source;
                        t.name = name;
                        t.title = name;
                        t.description = source;
                        t.category = "Custom templates";
                        t.mediaSubtype = "";
                        loaded.add(t);
                    }
                }
            } catch (Exception ignored) {}
            String finalError = error;
            ui.post(() -> {
                cacheLoading = false;
                if (!loaded.isEmpty()) {
                    templates.clear();
                    templates.addAll(loaded);
                    templatesAt = System.currentTimeMillis();
                    saveTemplateCache();
                    templateLimit = PAGE_SIZE;
                    refreshTemplateMeta();
                    refreshTemplateList();
                    setStatus("Cached " + loaded.size() + " templates from ComfyUI.");
                    preloadWorkflowCache(new ArrayList<>(loaded));
                } else {
                    refreshTemplateList();
                    String prefix = templates.isEmpty() ? "Cache update failed: " : "Cache update failed; kept old cache. ";
                    setStatus(prefix + (finalError.isEmpty() ? "No templates returned." : finalError));
                }
            });
        });
    }

    private void preloadWorkflowCache(ArrayList<TemplateItem> list) {
        IO.execute(() -> {
            int ok = 0;
            for (int i = 0; i < list.size(); i++) {
                try {
                    writeText(rawTemplateFile(list.get(i)), getTextAuto(templatePath(list.get(i))));
                    ok++;
                } catch (Exception ignored) {}
                if (i % 25 == 0) {
                    int done = i + 1;
                    int ready = ok;
                    ui.post(() -> setStatus("Caching workflows: " + done + "/" + list.size() + " ready " + ready));
                }
            }
            int ready = ok;
            ui.post(() -> setStatus("Workflow cache ready: " + ready + "."));
        });
    }

    private void refreshTemplateMeta() {
        if (countLine != null) {
            countLine.setText((templates.isEmpty() ? "○" : "●") + " Cached " + templates.size());
            countLine.setTextColor(templates.isEmpty() ? mutedColor() : accent());
        }
        if (updatedLine != null) updatedLine.setText(templatesAt > 0 ? "Updated " + timeAgo(templatesAt) : "No cache yet");
    }

    private void refreshTemplateList() {
        if (templateList == null) return;
        templateList.removeAllViews();
        String q = safe(templateFilter).trim().toLowerCase(Locale.US);
        ArrayList<TemplateItem> matches = new ArrayList<>();
        for (TemplateItem t : templates) if (validTemplate(t) && (q.isEmpty() || templateText(t).toLowerCase(Locale.US).contains(q))) matches.add(t);
        int max = Math.min(templateLimit, matches.size());
        for (int i = 0; i < max; i++) templateList.addView(templateRow(matches.get(i)), section());
        if (matches.isEmpty()) templateList.addView(muted(templates.isEmpty() ? "No cached templates. Tap Load/Update Cache once when ComfyUI is reachable." : "Nothing found.", 14));
        if (matches.size() > max) {
            LinearLayout more = card(false);
            more.setGravity(Gravity.CENTER_HORIZONTAL);
            more.addView(muted("Showing " + max + " of " + matches.size() + " templates.", 13));
            more.addView(button("Show more", false, () -> { templateLimit += PAGE_SIZE; refreshTemplateList(); }), new LinearLayout.LayoutParams(-1, dp(40)));
            templateList.addView(more, section());
        }
    }

    private View templateRow(TemplateItem t) {
        LinearLayout row = card(false);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(10), dp(12));
        row.setClickable(true);
        row.setOnClickListener(v -> openTemplate(t));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setImageResource(R.drawable.ic_launcher);
        img.setBackground(bg(surface2(), 8, stroke(), 1));
        img.setTag(t.id());
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(106), dp(82));
        ip.setMargins(0, 0, dp(12), 0);
        row.addView(img, ip);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(body, new LinearLayout.LayoutParams(0, dp(82), 1));
        TextView name = title(displayTitle(t), 16);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(name);
        TextView desc = muted(shortText(nonEmpty(t.description, t.category).replace('_', ' '), 130), 12);
        desc.setMaxLines(2);
        desc.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(desc);
        TextView arrow = muted("›", 28);
        arrow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(22), dp(82)));
        loadPreview(img, t);
        return row;
    }

    private void openTemplate(TemplateItem t) {
        if (!validTemplate(t)) { setStatus("Invalid template."); return; }
        setStatus("Opening template: " + displayTitle(t));
        IO.execute(() -> {
            try {
                String raw = readText(rawTemplateFile(t));
                if (raw.trim().isEmpty()) raw = readLegacyRaw(t);
                if (raw.trim().isEmpty()) {
                    raw = getTextAuto(templatePath(t));
                    writeText(rawTemplateFile(t), raw);
                }
                JSONObject result = ComfyWorkflowConverter.importResult(new JSONObject(raw), loadObjectInfo());
                ui.post(() -> handleImport(result));
            } catch (Exception e) {
                ui.post(() -> setStatus("Template open failed: " + shortError(e)));
            }
        });
    }

    private void renderNodes() {
        content.addView(statusChip(), section());
        content.addView(header("Nodes", "Search, edit and expand grouped nodes."), section());
        LinearLayout tools = card(false);
        LinearLayout search = row();
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(dp(12), 0, dp(10), 0);
        search.setBackground(bg(surface2(), 10, stroke(), 1));
        search.addView(muted("⌕", 22), new LinearLayout.LayoutParams(dp(30), -1));
        nodeSearch = bareInput("Search nodes, classes, fields…", true);
        nodeSearch.setText(nodeFilter);
        search.addView(nodeSearch, new LinearLayout.LayoutParams(0, -1, 1));
        tools.addView(search, new LinearLayout.LayoutParams(-1, dp(46)));
        LinearLayout actions = row();
        actions.setPadding(0, dp(10), 0, 0);
        actions.addView(button("Expand groups", false, this::expandGroups), weight(42));
        actions.addView(button("Clear", false, () -> { nodeFilter = ""; if (nodeSearch != null) nodeSearch.setText(""); refreshNodes(); }), weight(42));
        tools.addView(actions);
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
        if (workflow == null || workflow.length() == 0) { nodeList.addView(muted("No workflow imported.", 14)); return; }
        String q = safe(nodeFilter).trim().toLowerCase(Locale.US);
        LinkedHashMap<String, ArrayList<String>> groups = new LinkedHashMap<>();
        for (String id : nodeIds()) {
            JSONObject node = workflow.optJSONObject(id);
            if (node == null) continue;
            String hay = id + " " + nodeTitle(node) + " " + node.optString("class_type") + " " + inputKeyText(node.optJSONObject("inputs")) + " " + groupName(node);
            if (!q.isEmpty() && !hay.toLowerCase(Locale.US).contains(q)) continue;
            String group = groupName(node);
            ArrayList<String> list = groups.get(group);
            if (list == null) { list = new ArrayList<>(); groups.put(group, list); }
            list.add(id);
        }
        if (groups.isEmpty()) { nodeList.addView(muted("Nothing found.", 14)); return; }
        boolean grouped = groups.size() > 1 || !groups.containsKey("General");
        for (Map.Entry<String, ArrayList<String>> e : groups.entrySet()) {
            if (grouped) nodeList.addView(groupCard(e.getKey(), e.getValue()), section());
            if (!grouped || expandedGroups.contains(e.getKey()) || !q.isEmpty()) for (String id : e.getValue()) nodeList.addView(nodeCard(id), section());
        }
    }

    private View groupCard(String group, ArrayList<String> ids) {
        LinearLayout c = card(true);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        boolean open = expandedGroups.contains(group);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        c.addView(body, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(title(group, 16));
        body.addView(muted(ids.size() + " nodes" + (open ? " · expanded" : " · tap to expand"), 12));
        TextView arrow = title(open ? "⌃" : "⌄", 22);
        arrow.setGravity(Gravity.CENTER);
        c.addView(arrow, new LinearLayout.LayoutParams(dp(34), dp(42)));
        c.setOnClickListener(v -> { if (expandedGroups.contains(group)) expandedGroups.remove(group); else expandedGroups.add(group); refreshNodes(); });
        return c;
    }

    private View nodeCard(String id) {
        JSONObject node = workflow.optJSONObject(id);
        LinearLayout c = card(false);
        if (node == null) return c;
        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(title(nodeTitle(node), 16), new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = muted("›", 28);
        arrow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        head.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(42)));
        c.addView(head);
        c.addView(muted("#" + id + " · " + prettify(node.optString("class_type", "Node")) + ("General".equals(groupName(node)) ? "" : " · " + groupName(node)), 12));
        JSONObject inputs = node.optJSONObject("inputs");
        LinearLayout chips = row();
        chips.setPadding(0, dp(8), 0, 0);
        chips.addView(chip(primitiveCount(inputs) + " fields"), weight(32));
        chips.addView(chip(linkCount(inputs) + " linked"), weight(32));
        c.addView(chips);
        c.setOnClickListener(v -> editNode(id));
        return c;
    }

    private void editNode(String id) {
        editingFields.clear();
        content.removeAllViews();
        renderNav();
        JSONObject node = workflow == null ? null : workflow.optJSONObject(id);
        content.addView(statusChip(), section());
        content.addView(header(node == null ? "Node" : nodeTitle(node), node == null ? "" : "#" + id + " · " + node.optString("class_type", "")), section());
        LinearLayout card = card(false);
        if (node == null) { card.addView(muted("Node not found.", 14)); content.addView(card, section()); return; }
        JSONObject inputs = node.optJSONObject("inputs");
        boolean any = false;
        for (String key : inputKeys(inputs)) {
            Object value = inputs.opt(key);
            if (!primitive(value)) continue;
            any = true;
            card.addView(label(prettify(key)));
            EditText edit = input("", false);
            edit.setText(value == JSONObject.NULL ? "" : String.valueOf(value));
            boolean multi = key.toLowerCase(Locale.US).contains("prompt") || key.toLowerCase(Locale.US).contains("text") || String.valueOf(value).length() > 80;
            edit.setSingleLine(!multi);
            edit.setGravity(multi ? Gravity.TOP | Gravity.LEFT : Gravity.CENTER_VERTICAL | Gravity.LEFT);
            edit.setInputType(multi ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
            card.addView(edit, new LinearLayout.LayoutParams(-1, multi ? dp(110) : dp(44)));
            editingFields.add(new FieldRef(id, key, edit));
        }
        if (!any) card.addView(muted("No direct editable fields.", 13));
        LinearLayout actions = row();
        actions.setPadding(0, dp(12), 0, 0);
        actions.addView(button("Back", false, () -> { screen = "nodes"; render(); }), weight(44));
        actions.addView(button("Apply", true, () -> { applyFields(); saveWorkflow(); setStatus("Applied."); }), weight(44));
        card.addView(actions);
        content.addView(card, section());
    }

    private void renderRun() {
        content.addView(statusChip(), section());
        content.addView(header("Run", "Execute workflow."), section());
        LinearLayout c = card(false);
        c.addView(cardTitle("▷", workflow == null ? "No workflow loaded" : "Workflow loaded"));
        c.addView(muted(workflow == null ? "Choose template or load JSON first." : workflow.length() + " nodes ready.", 13));
        LinearLayout metrics = row();
        metrics.setPadding(0, dp(12), 0, dp(8));
        metrics.addView(metric("Nodes", workflow == null ? "0" : String.valueOf(workflow.length())), weight(56));
        metrics.addView(metric("Output", outputUrl.isEmpty() ? "None" : "Ready"), weight(56));
        c.addView(metrics);
        LinearLayout actions = row();
        actions.setPadding(0, dp(12), 0, 0);
        actions.addView(button("Output", false, () -> { screen = "output"; render(); }), weight(44));
        actions.addView(button("Run ▷", true, this::runWorkflow), weight(44));
        c.addView(actions);
        content.addView(c, section());
    }

    private void renderOutput() {
        content.addView(statusChip(), section());
        content.addView(header("Output", "Recent output."), section());
        LinearLayout c = card(false);
        c.addView(cardTitle("▧", "Recent Output"));
        if (outputUrl.trim().isEmpty()) c.addView(muted("No output yet.", 14));
        else {
            c.addView(muted(shortText(outputUrl, 320), 12));
            c.addView(button("Open output", true, () -> openUrl(outputUrl)), new LinearLayout.LayoutParams(-1, dp(44)));
        }
        content.addView(c, section());
    }

    private void chooseJson() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(Intent.createChooser(intent, "Choose ComfyUI workflow JSON"), REQ_JSON); }
        catch (Exception e) { setStatus("No file picker available."); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_JSON && res == RESULT_OK && data != null && data.getData() != null) {
            try { importJson(readUri(data.getData())); }
            catch (Exception e) { setStatus("Could not read JSON: " + shortError(e)); }
        }
    }

    private void importJson(String raw) {
        if (safe(raw).trim().isEmpty()) { setStatus("Empty JSON."); return; }
        setStatus("Importing workflow...");
        IO.execute(() -> {
            try {
                JSONObject result = ComfyWorkflowConverter.importResult(new JSONObject(raw), loadObjectInfo());
                ui.post(() -> handleImport(result));
            } catch (Exception e) { ui.post(() -> setStatus("Workflow import failed: " + shortError(e))); }
        });
    }

    private void handleImport(JSONObject result) {
        try {
            if (!result.optBoolean("ok", false)) { setStatus("Import failed: " + result.optString("error")); return; }
            workflow = result.optJSONObject("prompt");
            if (workflow == null) workflow = new JSONObject(result.optString("prompt", "{}"));
            saveWorkflow();
            screen = "create";
            setStatus("Imported " + workflow.length() + " nodes.");
            render();
        } catch (Exception e) { setStatus("Import failed: " + shortError(e)); }
    }

    private void runWorkflow() {
        if (workflow == null || workflow.length() == 0) { setStatus("Import a workflow first."); return; }
        saveConnection();
        applyFields();
        saveWorkflow();
        setStatus("Sending prompt...");
        IO.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("prompt", workflow);
                payload.put("client_id", "comfyui-mobile-" + System.currentTimeMillis());
                JSONObject response = new JSONObject(postJsonAuto("/prompt", payload.toString()));
                promptId = response.optString("prompt_id", "");
                if (promptId.isEmpty()) throw new Exception("ComfyUI did not return prompt_id");
                pollCount = 0;
                ui.post(() -> { setStatus("Queued. Waiting for output..."); pollHistory(); });
            } catch (Exception e) { ui.post(() -> setStatus("Run failed: " + shortError(e))); }
        });
    }

    private void pollHistory() {
        if (promptId.isEmpty()) return;
        pollCount++;
        IO.execute(() -> {
            try {
                OutputRef output = findOutput(new JSONObject(getTextAuto("/history/" + enc(promptId))));
                if (output != null) {
                    outputUrl = activeBase() + "/view?filename=" + enc(output.filename) + "&type=" + enc(output.type) + "&subfolder=" + enc(output.subfolder);
                    prefs().edit().putString(KEY_OUTPUT, outputUrl).apply();
                    ui.post(() -> { setStatus("Output ready."); screen = "output"; render(); });
                    return;
                }
            } catch (Exception ignored) {}
            if (pollCount < 240) ui.postDelayed(this::pollHistory, 2000);
            else ui.post(() -> setStatus("Timed out waiting for output."));
        });
    }

    private JSONObject loadObjectInfo() {
        try {
            objectInfo = new JSONObject(getTextAuto("/api/object_info"));
            prefs().edit().putString(KEY_OBJECT_INFO, objectInfo.toString()).apply();
            return objectInfo;
        } catch (Exception e) {
            try { return new JSONObject(prefs().getString(KEY_OBJECT_INFO, "{}")); }
            catch (Exception ignored) { return new JSONObject(); }
        }
    }

    private String getTextAuto(String path) throws Exception { return new String(autoBytes(path, null), "UTF-8"); }
    private String postJsonAuto(String path, String body) throws Exception { return new String(autoBytes(path, body), "UTF-8"); }
    private byte[] bytesAuto(String path) throws Exception { return autoBytes(path, null); }

    private byte[] autoBytes(String path, String post) throws Exception {
        StringBuilder errors = new StringBuilder();
        for (String base : baseCandidates()) {
            try {
                byte[] data = request(HTTP, base + path, post, false);
                activeBase = base;
                prefs().edit().putString(KEY_URL, base).apply();
                return data;
            } catch (Exception e) { errors.append("normal ").append(base).append(": ").append(shortError(e)).append("\n"); }
            try {
                byte[] data = request(HTTP_SAFE, base + path, post, true);
                activeBase = base;
                prefs().edit().putString(KEY_URL, base).apply();
                return data;
            } catch (Exception e) { errors.append("safe ").append(base).append(": ").append(shortError(e)).append("\n"); }
        }
        throw new Exception(errors.length() == 0 ? "No valid URL" : errors.toString().trim());
    }

    private byte[] request(OkHttpClient client, String url, String post, boolean safeMode) throws Exception {
        Request.Builder b = new Request.Builder().url(url)
                .header("Accept", "application/json,text/plain,*/*")
                .header("User-Agent", "Mozilla/5.0 Android ComfyUI-Mobile " + VERSION);
        if (safeMode) {
            b.header("Connection", "close");
            b.header("Accept-Encoding", "identity");
            b.header("Cache-Control", "no-cache");
            b.header("Pragma", "no-cache");
        }
        for (Map.Entry<String, String> h : authHeaders(url).entrySet()) b.header(h.getKey(), h.getValue());
        if (post != null) b.post(RequestBody.create(post, JSON));
        try (Response r = client.newCall(b.build()).execute()) {
            byte[] body = r.body() == null ? new byte[0] : r.body().bytes();
            if (body.length > MAX_BODY_BYTES) throw new Exception("response too large");
            if (!r.isSuccessful()) throw new Exception("HTTP " + r.code() + ": " + new String(body, "UTF-8"));
            return body;
        }
    }

    private Map<String, String> authHeaders(String url) {
        HashMap<String, String> h = new HashMap<>();
        String id = cfIdInput == null ? "" : cfIdInput.getText().toString().trim();
        String secret = cfSecretInput == null ? "" : cfSecretInput.getText().toString().trim();
        if (!id.isEmpty() && !secret.isEmpty()) {
            h.put("CF-Access-Client-Id", id);
            h.put("CF-Access-Client-Secret", secret);
        }
        try {
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null && !cookies.trim().isEmpty()) h.put("Cookie", cookies);
        } catch (Exception ignored) {}
        return h;
    }

    private List<String> baseCandidates() {
        String raw = rawUrl();
        ArrayList<String> out = new ArrayList<>();
        if (raw.isEmpty()) return out;
        if (raw.startsWith("//")) raw = raw.substring(2);
        if (raw.startsWith("http://") || raw.startsWith("https://")) addBase(out, raw);
        else { addBase(out, "https://" + raw); addBase(out, "http://" + raw); }
        return out;
    }

    private void addBase(ArrayList<String> out, String candidate) {
        String base = strip(candidate);
        try {
            URL u = new URL(base);
            if (u.getHost() != null && !u.getHost().trim().isEmpty() && !out.contains(base)) out.add(base);
        } catch (Exception ignored) {}
    }

    private void testConnection() {
        saveConnection();
        setStatus("Testing connection...");
        IO.execute(() -> {
            try {
                getTextAuto("/system_stats");
                String templateResult;
                try { JSONArray a = new JSONArray(getTextAuto("/templates/index.json")); templateResult = "; templates index OK: " + a.length(); }
                catch (Exception e) { templateResult = "; templates index failed: " + shortError(e); }
                String msg = "Connection OK: " + hostLabel(activeBase()) + templateResult;
                ui.post(() -> setStatus(msg));
            } catch (Exception e) { ui.post(() -> setStatus("Connection failed: " + shortError(e))); }
        });
    }

    private void loadPreview(ImageView image, TemplateItem t) {
        String key = activeBase() + "|" + t.id();
        Bitmap cached = BITMAP_CACHE.get(key);
        if (cached != null) { image.setImageBitmap(cached); return; }
        IO.execute(() -> {
            try {
                for (String ext : previewExts(t)) {
                    File f = previewFile(t, ext);
                    if (f.exists() && f.length() > 0) { showPreview(image, t.id(), key, f); return; }
                    File legacy = legacyPreviewFile(t, ext);
                    if (legacy.exists() && legacy.length() > 0) { showPreview(image, t.id(), key, legacy); return; }
                }
                for (String ext : previewExts(t)) {
                    try { File f = previewFile(t, ext); writeBytes(f, bytesAuto(previewPath(t, ext, false))); showPreview(image, t.id(), key, f); return; } catch (Exception ignored) {}
                    try { File f = previewFile(t, ext); writeBytes(f, bytesAuto(previewPath(t, ext, true))); showPreview(image, t.id(), key, f); return; } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        });
    }

    private void showPreview(ImageView image, String expectedTag, String key, File file) {
        Bitmap b = decodePreview(file);
        if (b == null) return;
        BITMAP_CACHE.put(key, b);
        ui.post(() -> { Object tag = image.getTag(); if (tag != null && String.valueOf(tag).equals(expectedTag)) image.setImageBitmap(b); });
    }

    private Bitmap decodePreview(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inSampleSize = 1;
            while ((bounds.outWidth / opts.inSampleSize) > 260 || (bounds.outHeight / opts.inSampleSize) > 220) opts.inSampleSize *= 2;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Exception e) { return null; }
    }

    private void loadTemplateCache() {
        templates.clear();
        templatesAt = prefs().getLong(KEY_TEMPLATES_AT, 0L);
        readTemplateArray(prefs().getString(KEY_TEMPLATES, "[]"));
        if (!templates.isEmpty()) return;
        SharedPreferences legacy = getSharedPreferences(OLD_TEMPLATE_PREFS, Context.MODE_PRIVATE);
        String[] keys = {"template_cards_stable_v1", "template_cards_v4", "template_cards_v3", "template_cards"};
        String[] times = {"template_cards_stable_updated_v1", "template_cards_updated_at_v4", "template_cards_updated_at_v3", "template_cards_updated_at"};
        for (int i = 0; i < keys.length && templates.isEmpty(); i++) {
            readTemplateArray(legacy.getString(keys[i], "[]"));
            templatesAt = legacy.getLong(times[i], templatesAt);
        }
        if (!templates.isEmpty()) saveTemplateCache();
    }

    private void readTemplateArray(String raw) {
        try {
            JSONArray arr = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                TemplateItem t = TemplateItem.fromJson(arr.optJSONObject(i));
                if (validTemplate(t) && !containsTemplate(t.id())) templates.add(t);
            }
        } catch (Exception ignored) {}
    }

    private boolean containsTemplate(String id) { for (TemplateItem t : templates) if (t.id().equals(id)) return true; return false; }
    private void saveTemplateCache() { JSONArray arr = new JSONArray(); for (TemplateItem t : templates) try { arr.put(t.toJson()); } catch (Exception ignored) {} prefs().edit().putString(KEY_TEMPLATES, arr.toString()).putLong(KEY_TEMPLATES_AT, templatesAt).apply(); }
    private File rawTemplateFile(TemplateItem t) { return new File(cacheDir("raw"), sha1(activeBase() + "|" + t.id()) + ".json"); }
    private File previewFile(TemplateItem t, String ext) { return new File(cacheDir("preview"), sha1(activeBase() + "|" + t.id() + "|" + ext) + "." + ext.replaceAll("[^A-Za-z0-9]", "")); }
    private File legacyPreviewFile(TemplateItem t, String ext) { return new File(new File(getFilesDir(), "template_cache/preview"), sha1(activeBase() + "|" + t.id() + "|" + ext) + "." + ext.replaceAll("[^A-Za-z0-9]", "")); }
    private String readLegacyRaw(TemplateItem t) { for (String base : baseCandidates()) { String raw = readText(new File(new File(getFilesDir(), "template_cache/raw"), sha1(base + "|" + t.id()) + ".json")); if (!raw.trim().isEmpty()) return raw; } return ""; }
    private File cacheDir(String child) { File dir = new File(getFilesDir(), "real_template_cache/" + child); if (!dir.exists()) dir.mkdirs(); return dir; }
    private String templatePath(TemplateItem t) { return "default".equals(t.source) ? "/templates/" + encPath(t.name) + ".json" : "/api/workflow_templates/" + encPath(t.source) + "/" + encPath(t.name) + ".json"; }
    private String previewPath(TemplateItem t, String ext, boolean fallback) { return "default".equals(t.source) ? "/templates/" + encPath(t.name) + (fallback ? "" : "-1") + "." + ext : "/api/workflow_templates/" + encPath(t.source) + "/" + encPath(t.name) + "." + ext; }
    private ArrayList<String> previewExts(TemplateItem t) { LinkedHashSet<String> out = new LinkedHashSet<>(); String s = safe(t.mediaSubtype).trim().toLowerCase(Locale.US); if (!s.isEmpty()) out.add(s); Collections.addAll(out, "webp", "png", "jpg", "jpeg", "gif"); return new ArrayList<>(out); }

    private OutputRef findOutput(JSONObject history) {
        try {
            Iterator<String> prompts = history.keys();
            OutputRef found = null;
            while (prompts.hasNext()) {
                JSONObject item = history.optJSONObject(prompts.next());
                JSONObject outputs = item == null ? null : item.optJSONObject("outputs");
                if (outputs == null) continue;
                Iterator<String> nodes = outputs.keys();
                while (nodes.hasNext()) {
                    JSONObject output = outputs.optJSONObject(nodes.next());
                    OutputRef r = firstOutput(output == null ? null : output.optJSONArray("videos")); if (r != null) found = r;
                    r = firstOutput(output == null ? null : output.optJSONArray("gifs")); if (r != null) found = r;
                    r = firstOutput(output == null ? null : output.optJSONArray("images")); if (r != null) found = r;
                }
            }
            return found;
        } catch (Exception e) { return null; }
    }

    private OutputRef firstOutput(JSONArray arr) { if (arr == null || arr.length() == 0) return null; JSONObject f = arr.optJSONObject(0); if (f == null) return null; String name = f.optString("filename", ""); if (name.isEmpty()) return null; return new OutputRef(name, f.optString("subfolder", ""), f.optString("type", "output")); }
    private List<String> nodeIds() { ArrayList<String> ids = new ArrayList<>(); if (workflow == null) return ids; Iterator<String> it = workflow.keys(); while (it.hasNext()) ids.add(it.next()); Collections.sort(ids); return ids; }
    private String nodeTitle(JSONObject n) { JSONObject meta = n == null ? null : n.optJSONObject("_meta"); return prettify(nonEmpty(meta == null ? "" : meta.optString("title", ""), n == null ? "Node" : n.optString("class_type", "Node"))); }
    private String groupName(JSONObject n) { JSONObject meta = n == null ? null : n.optJSONObject("_meta"); return nonEmpty(prettify(meta == null ? "" : meta.optString("group", meta.optString("group_name", ""))), "General"); }
    private List<String> inputKeys(JSONObject o) { ArrayList<String> keys = new ArrayList<>(); if (o == null) return keys; Iterator<String> it = o.keys(); while (it.hasNext()) keys.add(it.next()); Collections.sort(keys); return keys; }
    private String inputKeyText(JSONObject o) { StringBuilder sb = new StringBuilder(); for (String k : inputKeys(o)) sb.append(' ').append(k); return sb.toString(); }
    private boolean primitive(Object v) { return v == JSONObject.NULL || v instanceof String || v instanceof Number || v instanceof Boolean; }
    private int primitiveCount(JSONObject o) { int n = 0; for (String k : inputKeys(o)) if (primitive(o.opt(k))) n++; return n; }
    private int linkCount(JSONObject o) { int n = 0; for (String k : inputKeys(o)) if (o.optJSONArray(k) != null) n++; return n; }
    private void expandGroups() { if (workflow != null) for (String id : nodeIds()) expandedGroups.add(groupName(workflow.optJSONObject(id))); refreshNodes(); }
    private void applyFields() { if (workflow == null) return; for (FieldRef f : editingFields) try { JSONObject node = workflow.optJSONObject(f.nodeId); if (node != null) node.getJSONObject("inputs").put(f.key, coerce(f.edit.getText().toString())); } catch (Exception ignored) {} }
    private Object coerce(String raw) { String s = raw == null ? "" : raw.trim(); if ("true".equalsIgnoreCase(s)) return true; if ("false".equalsIgnoreCase(s)) return false; try { if (s.matches("-?\\d+")) return Long.parseLong(s); } catch (Exception ignored) {} try { if (s.matches("-?\\d+\\.\\d+")) return Double.parseDouble(s); } catch (Exception ignored) {} return raw == null ? "" : raw; }
    private boolean validTemplate(TemplateItem t) { return t != null && !safe(t.name).trim().isEmpty() && !safe(t.source).trim().isEmpty(); }
    private String templateText(TemplateItem t) { return displayTitle(t) + " " + safe(t.name) + " " + safe(t.description) + " " + safe(t.category) + " " + safe(t.source); }
    private String displayTitle(TemplateItem t) { return nonEmpty(t.title, t.name); }

    private void saveConnection() { prefs().edit().putString(KEY_URL, rawUrl()).putString(KEY_CF_ID, cfIdInput.getText().toString().trim()).putString(KEY_CF_SECRET, cfSecretInput.getText().toString().trim()).apply(); try { CookieManager.getInstance().flush(); } catch (Exception ignored) {} }
    private void saveWorkflow() { prefs().edit().putString(KEY_WORKFLOW, workflow == null ? "{}" : workflow.toString()).apply(); }
    private String rawUrl() { String s = urlInput == null ? "" : urlInput.getText().toString().trim(); if (s.isEmpty()) s = prefs().getString(KEY_URL, ""); if (s.isEmpty()) s = getSharedPreferences(OLD_REMOTE_PREFS, Context.MODE_PRIVATE).getString("comfyui_url", ""); return strip(s); }
    private String strip(String s) { if (s == null) return ""; s = s.trim(); while (s.endsWith("/")) s = s.substring(0, s.length() - 1); return s; }
    private String activeBase() { if (!activeBase.trim().isEmpty()) return activeBase; List<String> b = baseCandidates(); return b.isEmpty() ? "" : b.get(0); }
    private String hostLabel(String base) { try { return new URL(base).getHost(); } catch (Exception e) { return base; } }

    private View statusChip() { String base = activeBase(); LinearLayout c = row(); c.setGravity(Gravity.CENTER_VERTICAL); c.setPadding(dp(12), 0, dp(12), 0); c.setBackground(bg(surface(), 12, stroke(), 1)); c.addView(text(base.isEmpty() ? "○" : "●", 16, base.isEmpty() ? mutedColor() : rgb(66, 184, 93)), new LinearLayout.LayoutParams(dp(24), dp(38))); TextView label = muted(base.isEmpty() ? "Tap to set ComfyUI URL" : hostLabel(base), 12); label.setSingleLine(true); c.addView(label, new LinearLayout.LayoutParams(0, dp(38), 1)); TextView arrow = muted("›", 20); arrow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); c.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(38))); c.setOnClickListener(v -> topPanel.setVisibility(topPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)); return c; }
    private View header(String title, String sub) { LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.addView(title(title, 28)); TextView s = muted(sub, 14); s.setMaxLines(2); box.addView(s); return box; }
    private View cardTitle(String icon, String text) { LinearLayout r = row(); r.setGravity(Gravity.CENTER_VERTICAL); r.addView(badge(icon), new LinearLayout.LayoutParams(dp(34), dp(34))); TextView t = title(text, 19); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); p.setMargins(dp(10), 0, 0, 0); r.addView(t, p); return r; }
    private View metric(String k, String v) { LinearLayout m = card(false); m.setGravity(Gravity.CENTER); TextView a = muted(k, 11); a.setGravity(Gravity.CENTER); TextView b = title(v, 14); b.setGravity(Gravity.CENTER); m.addView(a); m.addView(b); return m; }
    private TextView badge(String s) { TextView v = text(s, 14, accent()); v.setGravity(Gravity.CENTER); v.setBackground(bg(Color.rgb(37, 31, 22), 12, stroke(), 1)); return v; }
    private TextView chip(String s) { TextView v = muted(s, 12); v.setGravity(Gravity.CENTER); v.setSingleLine(true); v.setBackground(bg(surface2(), 14, stroke(), 1)); return v; }
    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); return r; }
    private LinearLayout card(boolean accentBorder) { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(12), dp(12), dp(12), dp(12)); c.setBackground(bg(surface(), 16, accentBorder ? accent() : stroke(), 1)); return c; }
    private EditText input(String hint, boolean single) { EditText e = new EditText(this); e.setHint(hint); e.setSingleLine(single); e.setTextColor(Color.WHITE); e.setHintTextColor(mutedColor()); e.setTextSize(14); e.setPadding(dp(12), 0, dp(12), 0); e.setBackground(bg(surface2(), 12, stroke(), 1)); return e; }
    private EditText bareInput(String hint, boolean single) { EditText e = new EditText(this); e.setHint(hint); e.setSingleLine(single); e.setTextColor(Color.WHITE); e.setHintTextColor(mutedColor()); e.setTextSize(14); e.setPadding(dp(8), 0, 0, 0); e.setBackgroundColor(Color.TRANSPARENT); return e; }
    private Button button(String label, boolean primary, Runnable action) { Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setSingleLine(true); b.setTextSize(13); b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); b.setTextColor(primary ? accent() : Color.WHITE); b.setPadding(dp(6), 0, dp(6), 0); b.setBackground(bg(primary ? Color.rgb(44, 35, 25) : surface2(), 12, primary ? accent() : stroke(), 1)); b.setOnClickListener(v -> action.run()); return b; }
    private View navItem(String icon, String label, String target) { boolean selected = target.equals(screen); LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER); box.setPadding(0, dp(4), 0, dp(4)); box.setBackground(selected ? bg(Color.rgb(37,31,22), 12, accent(), 1) : bg(Color.TRANSPARENT, 12, Color.TRANSPARENT, 0)); TextView i = text(icon, 19, selected ? accent() : mutedColor()); i.setGravity(Gravity.CENTER); box.addView(i, new LinearLayout.LayoutParams(-1, dp(24))); TextView l = text(label, 10, selected ? accent() : mutedColor()); l.setGravity(Gravity.CENTER); l.setSingleLine(true); box.addView(l, new LinearLayout.LayoutParams(-1, dp(20))); box.setOnClickListener(v -> { screen = target; render(); }); return box; }
    private void renderNav() { nav.removeAllViews(); nav.addView(navItem("⊞", "Create", "create"), weight(58)); nav.addView(navItem("⌘", "Nodes", "nodes"), weight(58)); nav.addView(navItem("▦", "Templates", "templates"), weight(58)); nav.addView(navItem("▷", "Run", "run"), weight(58)); nav.addView(navItem("▧", "Output", "output"), weight(58)); }

    private LinearLayout.LayoutParams section() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, 0, 0, dp(12)); return p; }
    private LinearLayout.LayoutParams weight(int h) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(h), 1); p.setMargins(dp(4), 0, dp(4), 0); return p; }
    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h < 0 ? h : dp(h)); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }
    private TextView title(String s, int sp) { TextView t = text(s, sp, Color.WHITE); t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); t.setMaxLines(3); t.setEllipsize(TextUtils.TruncateAt.END); return t; }
    private TextView label(String s) { TextView t = text(s, 13, Color.rgb(210,210,216)); t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); return t; }
    private TextView muted(String s, int sp) { return text(s, sp, mutedColor()); }
    private TextView text(String s, int sp, int color) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setIncludeFontPadding(false); t.setPadding(dp(2), 0, dp(2), dp(5)); return t; }
    private GradientDrawable bg(int color, int radius, int strokeColor, int strokeWidth) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); d.setStroke(dp(strokeWidth), strokeColor); return d; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private int rgb(int r, int g, int b) { return Color.rgb(r, g, b); }
    private int bgRoot() { return Color.rgb(18,18,19); }
    private int surface() { return Color.rgb(28,28,30); }
    private int surface2() { return Color.rgb(33,33,36); }
    private int stroke() { return Color.rgb(48,48,52); }
    private int mutedColor() { return Color.rgb(170,170,178); }
    private int accent() { return Color.rgb(218,143,60); }
    private void setStatus(String s) { if (statusLine != null) statusLine.setText(s); Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private void openUrl(String url) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { setStatus("Could not open URL."); } }
    private void styleBars() { Window w = getWindow(); w.setStatusBarColor(bgRoot()); w.setNavigationBarColor(bgRoot()); w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE); }
    private SharedPreferences prefs() { return getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private String readUri(Uri uri) throws Exception { InputStream in = getContentResolver().openInputStream(uri); if (in == null) throw new Exception("empty file"); try { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); return out.toString("UTF-8"); } finally { in.close(); } }
    private String readText(File f) { try { byte[] b = readBytes(f); return b.length == 0 ? "" : new String(b, "UTF-8"); } catch (Exception e) { return ""; } }
    private byte[] readBytes(File f) { if (f == null || !f.exists()) return new byte[0]; ByteArrayOutputStream out = new ByteArrayOutputStream(); try { FileInputStream in = new FileInputStream(f); byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); in.close(); } catch (Exception ignored) {} return out.toByteArray(); }
    private void writeText(File f, String s) { try { writeBytes(f, safe(s).getBytes("UTF-8")); } catch (Exception ignored) {} }
    private void writeBytes(File f, byte[] data) { try { File p = f.getParentFile(); if (p != null && !p.exists()) p.mkdirs(); FileOutputStream out = new FileOutputStream(f); out.write(data); out.close(); } catch (Exception ignored) {} }
    private String sha1(String s) { try { MessageDigest md = MessageDigest.getInstance("SHA-1"); byte[] d = md.digest(safe(s).getBytes("UTF-8")); StringBuilder sb = new StringBuilder(); for (byte b : d) sb.append(String.format(Locale.US, "%02x", b & 0xff)); return sb.toString(); } catch (Exception e) { return String.valueOf(safe(s).hashCode()).replace("-", "n"); } }
    private String enc(String s) { try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; } }
    private String encPath(String s) { return enc(s).replace("%2F", "/"); }
    private String safe(String s) { return s == null ? "" : s; }
    private String nonEmpty(String v, String fallback) { return v == null || v.trim().isEmpty() ? safe(fallback) : v.trim(); }
    private String firstNonEmpty(String a, String b) { return a != null && !a.trim().isEmpty() ? a : safe(b); }
    private String shortText(String s, int max) { if (s == null) return ""; return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…"; }
    private String shortError(Exception e) { String s = e == null ? "" : e.getMessage(); if (s == null || s.trim().isEmpty()) s = e == null ? "unknown error" : e.getClass().getSimpleName(); return s.length() > 220 ? s.substring(0, 220) + "…" : s; }
    private String timeAgo(long ts) { long sec = Math.max(0, (System.currentTimeMillis() - ts) / 1000); if (sec < 60) return "just now"; long min = sec / 60; if (min < 60) return min + "m ago"; long h = min / 60; if (h < 24) return h + "h ago"; return (h / 24) + "d ago"; }
    private String prettify(String s) { return safe(s).replace('_', ' ').replaceAll("([a-z])([A-Z])", "$1 $2").trim(); }
}
