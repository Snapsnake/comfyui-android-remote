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

public class ComfyNativeActivity extends Activity {
    private static final String PREFS = "comfy_native_v1";
    private static final String LEGACY_REMOTE_PREFS = "comfyui_remote_prefs";
    private static final String LEGACY_TEMPLATE_PREFS = "comfyui_template_cache";
    private static final String KEY_URL = "url";
    private static final String KEY_CF_ID = "cf_id";
    private static final String KEY_CF_SECRET = "cf_secret";
    private static final String KEY_WORKFLOW = "workflow";
    private static final String KEY_OBJECT_INFO = "object_info";
    private static final String KEY_TEMPLATES = "templates_json";
    private static final String KEY_TEMPLATES_UPDATED = "templates_updated";
    private static final String KEY_LAST_OUTPUT = "last_output";
    private static final int REQ_JSON = 9001;
    private static final int FIRST_PAGE = 60;
    private static final int MAX_BODY_BYTES = 32 * 1024 * 1024;

    private static final ExecutorService IO = Executors.newFixedThreadPool(5);
    private static final LruCache<String, Bitmap> PREVIEW_MEMORY = new LruCache<>(80);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .build();

    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private LinearLayout connectionPanel;
    private LinearLayout content;
    private LinearLayout bottomNav;
    private LinearLayout nodeList;
    private LinearLayout templateList;
    private ScrollView scroll;
    private EditText urlEdit;
    private EditText cfIdEdit;
    private EditText cfSecretEdit;
    private EditText jsonEdit;
    private EditText nodeSearch;
    private EditText templateSearch;
    private TextView templateLine;
    private TextView loadedLine;
    private TextView updatedLine;
    private JSONObject workflow;
    private JSONObject objectInfo = new JSONObject();
    private final ArrayList<TemplateCard> templates = new ArrayList<>();
    private final ArrayList<FieldBinding> fieldBindings = new ArrayList<>();
    private final Set<String> expandedGroups = new HashSet<>();
    private String screen = "create";
    private String activeBase = "";
    private String nodeFilter = "";
    private String templateFilter = "";
    private String lastOutputUrl = "";
    private String promptId = "";
    private int templateLimit = FIRST_PAGE;
    private int pollAttempts = 0;
    private long templatesUpdated = 0L;
    private boolean refreshingTemplates = false;

    private static class TemplateCard {
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
        static TemplateCard fromJson(JSONObject o) {
            TemplateCard t = new TemplateCard();
            if (o == null) return t;
            t.source = o.optString("source", "default");
            t.name = o.optString("name", "");
            t.title = o.optString("title", t.name);
            t.description = o.optString("description", "");
            t.category = o.optString("category", "Templates");
            t.mediaSubtype = o.optString("mediaSubtype", "webp");
            return t;
        }
        private static String safe(String s) { return s == null ? "" : s; }
    }

    private static class FieldBinding {
        final String nodeId;
        final String key;
        final EditText edit;
        FieldBinding(String nodeId, String key, EditText edit) {
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        System.setProperty("http.keepAlive", "false");
        try { CookieManager.getInstance().setAcceptCookie(true); } catch (Exception ignored) {}
        buildRootUi();
        loadPersistedState();
        render();
        styleSystemBars();
    }

    private void buildRootUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(bgRoot());

        connectionPanel = new LinearLayout(this);
        connectionPanel.setOrientation(LinearLayout.VERTICAL);
        connectionPanel.setPadding(dp(18), dp(12), dp(18), dp(12));
        connectionPanel.setBackgroundColor(surface());
        root.addView(connectionPanel, new LinearLayout.LayoutParams(-1, -2));

        connectionPanel.addView(title("ComfyUI Mobile", 18));
        urlEdit = input("https://comfyui.example.com", true);
        connectionPanel.addView(urlEdit, boxLp(-1, 46, 0, 8, 0, 8));
        LinearLayout quick = horizontal();
        quick.addView(button("Test", false, this::testConnection), weightLp(42));
        quick.addView(button("Hide", false, () -> connectionPanel.setVisibility(View.GONE)), weightLp(42));
        quick.addView(button("Templates", true, () -> { screen = "templates"; render(); }), weightLp(42));
        connectionPanel.addView(quick, new LinearLayout.LayoutParams(-1, dp(42)));
        connectionPanel.addView(muted("Cloudflare Access optional", 12), boxLp(-1, -2, 0, 8, 0, 4));
        cfIdEdit = input("CF-Access-Client-Id", true);
        connectionPanel.addView(cfIdEdit, new LinearLayout.LayoutParams(-1, dp(42)));
        cfSecretEdit = input("CF-Access-Client-Secret", true);
        cfSecretEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        connectionPanel.addView(cfSecretEdit, boxLp(-1, 42, 0, 6, 0, 0));

        scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(bgRoot());
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(20));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        bottomNav = new LinearLayout(this);
        bottomNav.setOrientation(LinearLayout.HORIZONTAL);
        bottomNav.setGravity(Gravity.CENTER);
        bottomNav.setPadding(dp(14), dp(8), dp(14), dp(8));
        bottomNav.setBackgroundColor(surface());
        root.addView(bottomNav, new LinearLayout.LayoutParams(-1, dp(78)));
        setContentView(root);
    }

    private void loadPersistedState() {
        SharedPreferences p = prefs();
        SharedPreferences legacy = getSharedPreferences(LEGACY_REMOTE_PREFS, Context.MODE_PRIVATE);
        String savedUrl = firstNonEmpty(p.getString(KEY_URL, ""), legacy.getString("comfyui_url", ""));
        urlEdit.setText(savedUrl);
        cfIdEdit.setText(firstNonEmpty(p.getString(KEY_CF_ID, ""), legacy.getString("cf_access_client_id", "")));
        cfSecretEdit.setText(firstNonEmpty(p.getString(KEY_CF_SECRET, ""), legacy.getString("cf_access_client_secret", "")));
        if (!savedUrl.trim().isEmpty()) connectionPanel.setVisibility(View.GONE);
        lastOutputUrl = p.getString(KEY_LAST_OUTPUT, "");
        try {
            workflow = new JSONObject(p.getString(KEY_WORKFLOW, "{}"));
            if (workflow.length() == 0) workflow = null;
        } catch (Exception e) {
            workflow = null;
        }
        try { objectInfo = new JSONObject(p.getString(KEY_OBJECT_INFO, "{}")); } catch (Exception ignored) { objectInfo = new JSONObject(); }
        loadTemplatesFromCache();
    }

    private void render() {
        fieldBindings.clear();
        content.removeAllViews();
        renderBottomNav();
        if ("templates".equals(screen)) renderTemplatesScreen();
        else if ("nodes".equals(screen)) renderNodesScreen();
        else if ("run".equals(screen)) renderRunScreen();
        else if ("output".equals(screen)) renderOutputScreen();
        else renderCreateScreen();
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void renderCreateScreen() {
        content.addView(statusChip(), sectionLp());
        content.addView(pageHeader("Create", workflow == null ? "Choose a template or load workflow JSON." : workflow.length() + " nodes loaded."), sectionLp());
        LinearLayout card = card(false);
        card.addView(cardTitle("‹/›", "Workflow"));
        LinearLayout row = horizontal();
        row.setPadding(0, dp(10), 0, dp(10));
        row.addView(button("▦ Templates", false, () -> { screen = "templates"; render(); }), weightLp(46));
        row.addView(button("⇩ Import JSON", true, this::chooseJsonFile), weightLp(46));
        card.addView(row);
        if (workflow == null) {
            card.addView(label("Fallback: paste workflow JSON"));
            jsonEdit = input("Paste ComfyUI workflow/API JSON here…", false);
            jsonEdit.setSingleLine(false);
            jsonEdit.setGravity(Gravity.TOP | Gravity.LEFT);
            jsonEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            card.addView(jsonEdit, new LinearLayout.LayoutParams(-1, dp(150)));
            LinearLayout actions = horizontal();
            actions.setPadding(0, dp(12), 0, 0);
            actions.addView(button("Load JSON", false, this::chooseJsonFile), weightLp(44));
            actions.addView(button("Apply JSON", true, () -> importJson(jsonEdit.getText().toString())), weightLp(44));
            card.addView(actions);
        } else {
            card.addView(muted("Workflow is loaded. Edit fields in Nodes or execute in Run.", 13));
            LinearLayout actions = horizontal();
            actions.setPadding(0, dp(12), 0, 0);
            actions.addView(button("Nodes", false, () -> { screen = "nodes"; render(); }), weightLp(44));
            actions.addView(button("Run", true, () -> { screen = "run"; render(); }), weightLp(44));
            card.addView(actions);
        }
        content.addView(card, sectionLp());
        LinearLayout tip = card(true);
        tip.addView(label("Native foundation"));
        tip.addView(muted("No WebView shell and no skin-pass. Network uses OkHttp, cache migration, retries, and native workflow/subgraph import.", 13));
        content.addView(tip, sectionLp());
    }

    private void renderTemplatesScreen() {
        content.addView(statusChip(), sectionLp());
        content.addView(pageHeader("Templates", "Browse and load workflow templates"), sectionLp());
        LinearLayout panel = card(false);
        LinearLayout searchBox = horizontal();
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(dp(12), 0, dp(10), 0);
        searchBox.setBackground(bg(surface2(), 10, stroke(), 1));
        searchBox.addView(muted("⌕", 22), new LinearLayout.LayoutParams(dp(30), -1));
        templateSearch = bareEdit("Search templates…", true);
        templateSearch.setText(templateFilter);
        searchBox.addView(templateSearch, new LinearLayout.LayoutParams(0, -1, 1));
        TextView tune = muted("☷", 18);
        tune.setGravity(Gravity.CENTER);
        searchBox.addView(tune, new LinearLayout.LayoutParams(dp(32), -1));
        panel.addView(searchBox, new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout meta = horizontal();
        meta.setPadding(0, dp(8), 0, 0);
        loadedLine = muted("", 12);
        updatedLine = muted("", 12);
        updatedLine.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        meta.addView(loadedLine, new LinearLayout.LayoutParams(0, dp(30), 1));
        meta.addView(updatedLine, new LinearLayout.LayoutParams(0, dp(30), 1));
        panel.addView(meta);

        LinearLayout actions = horizontal();
        actions.setPadding(0, dp(4), 0, dp(4));
        actions.addView(button(refreshingTemplates ? "⟳ Refreshing…" : "⟳ Refresh", true, this::refreshTemplates), weightLp(42));
        actions.addView(button("⌫ Clear", false, () -> { templateFilter = ""; templateLimit = FIRST_PAGE; if (templateSearch != null) templateSearch.setText(""); refreshTemplateList(); }), weightLp(42));
        panel.addView(actions);
        templateLine = muted("Uses OkHttp. Old template cache is migrated automatically.", 12);
        templateLine.setSingleLine(true);
        templateLine.setEllipsize(TextUtils.TruncateAt.END);
        panel.addView(templateLine);
        content.addView(panel, sectionLp());

        templateList = new LinearLayout(this);
        templateList.setOrientation(LinearLayout.VERTICAL);
        content.addView(templateList, new LinearLayout.LayoutParams(-1, -2));
        templateSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                templateFilter = String.valueOf(s == null ? "" : s);
                templateLimit = FIRST_PAGE;
                refreshTemplateList();
            }
            public void afterTextChanged(Editable s) {}
        });
        refreshTemplateMeta();
        refreshTemplateList();
        if (templates.isEmpty() && !rawUrl().isEmpty()) refreshTemplates();
    }

    private void renderNodesScreen() {
        content.addView(statusChip(), sectionLp());
        content.addView(pageHeader("Nodes", "Search, edit and expand grouped nodes."), sectionLp());
        LinearLayout panel = card(false);
        LinearLayout searchBox = horizontal();
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(dp(12), 0, dp(10), 0);
        searchBox.setBackground(bg(surface2(), 10, stroke(), 1));
        searchBox.addView(muted("⌕", 22), new LinearLayout.LayoutParams(dp(30), -1));
        nodeSearch = bareEdit("Search nodes, classes, fields…", true);
        nodeSearch.setText(nodeFilter);
        searchBox.addView(nodeSearch, new LinearLayout.LayoutParams(0, -1, 1));
        panel.addView(searchBox, new LinearLayout.LayoutParams(-1, dp(46)));
        LinearLayout actions = horizontal();
        actions.setPadding(0, dp(10), 0, 0);
        actions.addView(button("Expand groups", false, this::expandGroups), weightLp(42));
        actions.addView(button("Clear", false, () -> { nodeFilter = ""; if (nodeSearch != null) nodeSearch.setText(""); refreshNodeList(); }), weightLp(42));
        panel.addView(actions);
        content.addView(panel, sectionLp());
        nodeList = new LinearLayout(this);
        nodeList.setOrientation(LinearLayout.VERTICAL);
        content.addView(nodeList, new LinearLayout.LayoutParams(-1, -2));
        nodeSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                nodeFilter = String.valueOf(s == null ? "" : s);
                refreshNodeList();
            }
            public void afterTextChanged(Editable s) {}
        });
        refreshNodeList();
    }

    private void renderRunScreen() {
        content.addView(statusChip(), sectionLp());
        content.addView(pageHeader("Run", "Execute workflow."), sectionLp());
        LinearLayout card = card(false);
        card.addView(cardTitle("▷", workflow == null ? "No workflow loaded" : "Workflow loaded"));
        card.addView(muted(workflow == null ? "Choose template or load JSON first." : workflow.length() + " nodes ready.", 13));
        LinearLayout metrics = horizontal();
        metrics.setPadding(0, dp(12), 0, dp(8));
        metrics.addView(metric("Nodes", workflow == null ? "0" : String.valueOf(workflow.length())), weightLp(56));
        metrics.addView(metric("Output", lastOutputUrl.isEmpty() ? "None" : "Ready"), weightLp(56));
        card.addView(metrics);
        LinearLayout actions = horizontal();
        actions.setPadding(0, dp(12), 0, 0);
        actions.addView(button("Output", false, () -> { screen = "output"; render(); }), weightLp(44));
        actions.addView(button("Run ▷", true, this::runWorkflow), weightLp(44));
        card.addView(actions);
        content.addView(card, sectionLp());
    }

    private void renderOutputScreen() {
        content.addView(statusChip(), sectionLp());
        content.addView(pageHeader("Output", "Recent output."), sectionLp());
        LinearLayout card = card(false);
        card.addView(cardTitle("▧", "Recent Output"));
        if (lastOutputUrl.trim().isEmpty()) {
            card.addView(muted("No output yet.", 14));
        } else {
            card.addView(muted(shortText(lastOutputUrl, 320), 12));
            card.addView(button("Open output", true, () -> openUrl(lastOutputUrl)), new LinearLayout.LayoutParams(-1, dp(44)));
        }
        content.addView(card, sectionLp());
    }

    private void refreshTemplates() {
        if (refreshingTemplates) return;
        saveConnection();
        if (baseCandidates().isEmpty()) {
            setStatus("Set a valid ComfyUI URL first.");
            connectionPanel.setVisibility(View.VISIBLE);
            return;
        }
        refreshingTemplates = true;
        setStatus("Refreshing templates...");
        IO.execute(() -> {
            ArrayList<TemplateCard> loaded = new ArrayList<>();
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
                        TemplateCard t = new TemplateCard();
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
                Iterator<String> it = custom.keys();
                while (it.hasNext()) {
                    String source = it.next();
                    JSONArray arr = custom.optJSONArray(source);
                    if (arr == null) continue;
                    for (int i = 0; i < arr.length(); i++) {
                        String name = arr.optString(i, "").trim();
                        if (name.isEmpty()) continue;
                        TemplateCard t = new TemplateCard();
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
            main.post(() -> {
                refreshingTemplates = false;
                if (!loaded.isEmpty()) {
                    templates.clear();
                    templates.addAll(loaded);
                    templatesUpdated = System.currentTimeMillis();
                    saveTemplatesToCache();
                    refreshTemplateMeta();
                    refreshTemplateList();
                    setStatus("Loaded " + loaded.size() + " templates.");
                    preloadTemplateWorkflows(new ArrayList<>(loaded));
                } else {
                    refreshTemplateList();
                    String msg = "Refresh failed: " + (finalError.isEmpty() ? "no templates returned" : finalError);
                    if (!templates.isEmpty()) msg += ". Kept local cache.";
                    setStatus(msg);
                }
            });
        });
    }

    private void preloadTemplateWorkflows(ArrayList<TemplateCard> list) {
        IO.execute(() -> {
            int ok = 0;
            for (int i = 0; i < list.size(); i++) {
                TemplateCard t = list.get(i);
                try {
                    writeText(rawTemplateFile(t), getTextAuto(templatePath(t)));
                    ok++;
                } catch (Exception ignored) {}
                if (i % 25 == 0) {
                    int done = i + 1;
                    int count = ok;
                    main.post(() -> setStatus("Caching workflows: " + done + "/" + list.size() + " ready " + count));
                }
            }
            int count = ok;
            main.post(() -> setStatus("Templates ready. Cached workflows: " + count + "."));
        });
    }

    private void refreshTemplateMeta() {
        if (loadedLine != null) {
            loadedLine.setText((templates.isEmpty() ? "○" : "●") + " Loaded " + templates.size() + " templates");
            loadedLine.setTextColor(templates.isEmpty() ? mutedColor() : accent());
        }
        if (updatedLine != null) updatedLine.setText(templatesUpdated > 0 ? "Updated " + timeAgo(templatesUpdated) : "Not refreshed yet");
    }

    private void refreshTemplateList() {
        if (templateList == null) return;
        templateList.removeAllViews();
        String q = templateFilter.trim().toLowerCase(Locale.US);
        ArrayList<TemplateCard> matches = new ArrayList<>();
        for (TemplateCard t : templates) {
            if (validTemplate(t) && (q.isEmpty() || templateSearchText(t).toLowerCase(Locale.US).contains(q))) matches.add(t);
        }
        int max = Math.min(templateLimit, matches.size());
        for (int i = 0; i < max; i++) templateList.addView(templateRow(matches.get(i)), sectionLp());
        if (matches.isEmpty()) templateList.addView(muted(templates.isEmpty() ? "No templates cached yet. Tap Refresh." : "Nothing found.", 14));
        if (matches.size() > max) {
            LinearLayout more = card(false);
            more.setGravity(Gravity.CENTER_HORIZONTAL);
            more.addView(muted("Showing " + max + " of " + matches.size() + " templates.", 13));
            more.addView(button("Show more", false, () -> { templateLimit += FIRST_PAGE; refreshTemplateList(); }), new LinearLayout.LayoutParams(-1, dp(40)));
            templateList.addView(more, sectionLp());
        }
    }

    private View templateRow(TemplateCard t) {
        LinearLayout row = card(false);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(10), dp(12));
        row.setClickable(true);
        row.setOnClickListener(v -> openTemplate(t));
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setImageResource(R.drawable.ic_launcher);
        preview.setBackground(bg(surface2(), 8, stroke(), 1));
        preview.setTag(t.id());
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(106), dp(82));
        ip.setMargins(0, 0, dp(12), 0);
        row.addView(preview, ip);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(body, new LinearLayout.LayoutParams(0, dp(82), 1));
        TextView title = title(displayTitle(t), 16);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(title);
        TextView desc = muted(shortText(nonEmpty(t.description, t.category).replace('_', ' '), 130), 12);
        desc.setMaxLines(2);
        desc.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(desc);
        TextView arrow = muted("›", 28);
        arrow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(22), dp(82)));
        loadPreview(preview, t);
        return row;
    }

    private void openTemplate(TemplateCard t) {
        if (!validTemplate(t)) { setStatus("Invalid template."); return; }
        setStatus("Opening template: " + displayTitle(t));
        IO.execute(() -> {
            try {
                String raw = readText(rawTemplateFile(t));
                if (raw.trim().isEmpty()) raw = readLegacyRawTemplate(t);
                if (raw.trim().isEmpty()) {
                    raw = getTextAuto(templatePath(t));
                    writeText(rawTemplateFile(t), raw);
                }
                JSONObject result = ComfyWorkflowConverter.importResult(new JSONObject(raw), loadObjectInfo());
                main.post(() -> handleImportResult(result));
            } catch (Exception e) {
                main.post(() -> setStatus("Template open failed: " + shortError(e)));
            }
        });
    }

    private void refreshNodeList() {
        if (nodeList == null) return;
        nodeList.removeAllViews();
        if (workflow == null || workflow.length() == 0) {
            nodeList.addView(muted("No workflow imported.", 14));
            return;
        }
        String q = nodeFilter.trim().toLowerCase(Locale.US);
        LinkedHashMap<String, ArrayList<String>> groups = new LinkedHashMap<>();
        for (String id : nodeIds()) {
            JSONObject node = workflow.optJSONObject(id);
            if (node == null) continue;
            String hay = id + " " + nodeTitle(node) + " " + node.optString("class_type") + " " + inputKeysText(node.optJSONObject("inputs")) + " " + groupName(node);
            if (!q.isEmpty() && !hay.toLowerCase(Locale.US).contains(q)) continue;
            String group = groupName(node);
            ArrayList<String> list = groups.get(group);
            if (list == null) {
                list = new ArrayList<>();
                groups.put(group, list);
            }
            list.add(id);
        }
        if (groups.isEmpty()) {
            nodeList.addView(muted("Nothing found.", 14));
            return;
        }
        boolean grouped = groups.size() > 1 || !groups.containsKey("General");
        for (Map.Entry<String, ArrayList<String>> entry : groups.entrySet()) {
            if (grouped) nodeList.addView(groupCard(entry.getKey(), entry.getValue()), sectionLp());
            if (!grouped || expandedGroups.contains(entry.getKey()) || !q.isEmpty()) {
                for (String id : entry.getValue()) nodeList.addView(nodeCard(id), sectionLp());
            }
        }
    }

    private View groupCard(String group, ArrayList<String> ids) {
        LinearLayout card = card(true);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        boolean open = expandedGroups.contains(group);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        card.addView(body, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(title(group, 16));
        body.addView(muted(ids.size() + " nodes" + (open ? " · expanded" : " · tap to expand"), 12));
        TextView arrow = title(open ? "⌃" : "⌄", 22);
        arrow.setGravity(Gravity.CENTER);
        card.addView(arrow, new LinearLayout.LayoutParams(dp(34), dp(42)));
        card.setOnClickListener(v -> { if (expandedGroups.contains(group)) expandedGroups.remove(group); else expandedGroups.add(group); refreshNodeList(); });
        return card;
    }

    private View nodeCard(String id) {
        JSONObject node = workflow.optJSONObject(id);
        LinearLayout card = card(false);
        if (node == null) return card;
        LinearLayout head = horizontal();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(title(nodeTitle(node), 16), new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = muted("›", 28);
        arrow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        head.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(42)));
        card.addView(head);
        card.addView(muted("#" + id + " · " + prettify(node.optString("class_type", "Node")) + ("General".equals(groupName(node)) ? "" : " · " + groupName(node)), 12));
        JSONObject inputs = node.optJSONObject("inputs");
        LinearLayout chips = horizontal();
        chips.setPadding(0, dp(8), 0, 0);
        chips.addView(chip(primitiveCount(inputs) + " fields"), weightLp(32));
        chips.addView(chip(linkCount(inputs) + " linked"), weightLp(32));
        card.addView(chips);
        card.setOnClickListener(v -> editNode(id));
        return card;
    }

    private void editNode(String id) {
        fieldBindings.clear();
        content.removeAllViews();
        renderBottomNav();
        JSONObject node = workflow == null ? null : workflow.optJSONObject(id);
        content.addView(statusChip(), sectionLp());
        content.addView(pageHeader(node == null ? "Node" : nodeTitle(node), node == null ? "" : "#" + id + " · " + node.optString("class_type", "")), sectionLp());
        LinearLayout card = card(false);
        if (node == null) {
            card.addView(muted("Node not found.", 14));
            content.addView(card, sectionLp());
            return;
        }
        JSONObject inputs = node.optJSONObject("inputs");
        boolean any = false;
        for (String key : inputKeys(inputs)) {
            Object value = inputs.opt(key);
            if (!isPrimitive(value)) continue;
            any = true;
            card.addView(label(prettify(key)));
            EditText edit = input("", false);
            edit.setText(value == JSONObject.NULL ? "" : String.valueOf(value));
            boolean multiline = key.toLowerCase(Locale.US).contains("prompt") || key.toLowerCase(Locale.US).contains("text") || String.valueOf(value).length() > 80;
            edit.setSingleLine(!multiline);
            edit.setGravity(multiline ? Gravity.TOP | Gravity.LEFT : Gravity.CENTER_VERTICAL | Gravity.LEFT);
            edit.setInputType(multiline ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
            card.addView(edit, new LinearLayout.LayoutParams(-1, multiline ? dp(110) : dp(44)));
            fieldBindings.add(new FieldBinding(id, key, edit));
        }
        if (!any) card.addView(muted("No direct editable fields.", 13));
        LinearLayout actions = horizontal();
        actions.setPadding(0, dp(12), 0, 0);
        actions.addView(button("Back", false, () -> { screen = "nodes"; render(); }), weightLp(44));
        actions.addView(button("Apply", true, () -> { applyEditedFields(); saveWorkflow(); setStatus("Applied."); }), weightLp(44));
        card.addView(actions);
        content.addView(card, sectionLp());
    }

    private void chooseJsonFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(Intent.createChooser(intent, "Choose ComfyUI workflow JSON"), REQ_JSON); }
        catch (Exception e) { setStatus("No file picker available."); }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_JSON && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try { importJson(readUri(data.getData())); }
            catch (Exception e) { setStatus("Could not read JSON: " + shortError(e)); }
        }
    }

    private void importJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) { setStatus("Empty JSON."); return; }
        setStatus("Importing workflow...");
        IO.execute(() -> {
            try {
                JSONObject result = ComfyWorkflowConverter.importResult(new JSONObject(raw), loadObjectInfo());
                main.post(() -> handleImportResult(result));
            } catch (Exception e) {
                main.post(() -> setStatus("Workflow import failed: " + shortError(e)));
            }
        });
    }

    private void handleImportResult(JSONObject result) {
        try {
            if (!result.optBoolean("ok", false)) { setStatus("Import failed: " + result.optString("error")); return; }
            workflow = result.optJSONObject("prompt");
            if (workflow == null) workflow = new JSONObject(result.optString("prompt", "{}"));
            saveWorkflow();
            screen = "create";
            setStatus("Imported " + workflow.length() + " nodes.");
            render();
        } catch (Exception e) {
            setStatus("Import failed: " + shortError(e));
        }
    }

    private void runWorkflow() {
        if (workflow == null || workflow.length() == 0) { setStatus("Import a workflow first."); return; }
        saveConnection();
        applyEditedFields();
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
                pollAttempts = 0;
                main.post(() -> { setStatus("Queued. Waiting for output..."); pollHistory(); });
            } catch (Exception e) {
                main.post(() -> setStatus("Run failed: " + shortError(e)));
            }
        });
    }

    private void pollHistory() {
        if (promptId.isEmpty()) return;
        pollAttempts++;
        IO.execute(() -> {
            try {
                JSONObject history = new JSONObject(getTextAuto("/history/" + enc(promptId)));
                OutputRef output = findOutput(history);
                if (output != null) {
                    lastOutputUrl = activeBase() + "/view?filename=" + enc(output.filename) + "&type=" + enc(output.type) + "&subfolder=" + enc(output.subfolder);
                    prefs().edit().putString(KEY_LAST_OUTPUT, lastOutputUrl).apply();
                    main.post(() -> { setStatus("Output ready."); screen = "output"; render(); });
                    return;
                }
            } catch (Exception ignored) {}
            if (pollAttempts < 240) main.postDelayed(this::pollHistory, 2000);
            else main.post(() -> setStatus("Timed out waiting for output."));
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

    private String getTextAuto(String path) throws Exception {
        Exception last = null;
        for (String base : baseCandidates()) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    String text = request(base + path, null);
                    activeBase = base;
                    prefs().edit().putString(KEY_URL, base).apply();
                    return text;
                } catch (Exception e) { last = e; }
            }
        }
        throw last == null ? new Exception("No valid URL") : last;
    }

    private String postJsonAuto(String path, String body) throws Exception {
        Exception last = null;
        for (String base : baseCandidates()) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    String text = request(base + path, body);
                    activeBase = base;
                    prefs().edit().putString(KEY_URL, base).apply();
                    return text;
                } catch (Exception e) { last = e; }
            }
        }
        throw last == null ? new Exception("No valid URL") : last;
    }

    private byte[] bytesAuto(String path) throws Exception {
        Exception last = null;
        for (String base : baseCandidates()) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    byte[] data = requestBytes(base + path, null);
                    activeBase = base;
                    return data;
                } catch (Exception e) { last = e; }
            }
        }
        throw last == null ? new Exception("No valid URL") : last;
    }

    private String request(String url, String postBody) throws Exception {
        return new String(requestBytes(url, postBody), "UTF-8");
    }

    private byte[] requestBytes(String url, String postBody) throws Exception {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Connection", "close")
                .header("Accept-Encoding", "identity")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .header("Accept", "application/json,text/plain,*/*")
                .header("User-Agent", "Mozilla/5.0 (Android) ComfyUI-Mobile");
        for (Map.Entry<String, String> header : authHeaders(url).entrySet()) builder.header(header.getKey(), header.getValue());
        if (postBody != null) builder.post(RequestBody.create(postBody, JSON));
        try (Response response = HTTP.newCall(builder.build()).execute()) {
            byte[] body = response.body() == null ? new byte[0] : response.body().bytes();
            if (body.length > MAX_BODY_BYTES) throw new Exception("response too large");
            if (!response.isSuccessful()) throw new Exception("HTTP " + response.code() + ": " + new String(body, "UTF-8"));
            return body;
        }
    }

    private Map<String, String> authHeaders(String url) {
        HashMap<String, String> headers = new HashMap<>();
        String id = cfIdEdit == null ? "" : cfIdEdit.getText().toString().trim();
        String secret = cfSecretEdit == null ? "" : cfSecretEdit.getText().toString().trim();
        if (!id.isEmpty() && !secret.isEmpty()) {
            headers.put("CF-Access-Client-Id", id);
            headers.put("CF-Access-Client-Secret", secret);
        }
        try {
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null && !cookies.trim().isEmpty()) headers.put("Cookie", cookies);
        } catch (Exception ignored) {}
        return headers;
    }

    private List<String> baseCandidates() {
        String raw = rawUrl();
        ArrayList<String> out = new ArrayList<>();
        if (raw.isEmpty()) return out;
        if (raw.startsWith("//")) raw = raw.substring(2);
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            addBase(out, raw);
            try {
                URL u = new URL(stripTrailingSlash(raw));
                addBase(out, ("https".equalsIgnoreCase(u.getProtocol()) ? "http" : "https") + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : ""));
            } catch (Exception ignored) {}
        } else {
            addBase(out, "https://" + raw);
            addBase(out, "http://" + raw);
        }
        return out;
    }

    private void addBase(ArrayList<String> out, String candidate) {
        String base = stripTrailingSlash(candidate);
        try {
            URL u = new URL(base);
            if (u.getHost() != null && !u.getHost().trim().isEmpty() && !out.contains(base)) out.add(base);
        } catch (Exception ignored) {}
    }

    private String activeBase() {
        if (!activeBase.trim().isEmpty()) return activeBase;
        List<String> bases = baseCandidates();
        return bases.isEmpty() ? "" : bases.get(0);
    }

    private void testConnection() {
        saveConnection();
        setStatus("Testing connection...");
        IO.execute(() -> {
            try {
                getTextAuto("/system_stats");
                main.post(() -> setStatus("Connection OK: " + hostLabel(activeBase())));
            } catch (Exception e) {
                main.post(() -> setStatus("Connection failed: " + shortError(e)));
            }
        });
    }

    private void loadPreview(ImageView image, TemplateCard template) {
        String memKey = activeBase() + "|" + template.id();
        Bitmap cached = PREVIEW_MEMORY.get(memKey);
        if (cached != null) { image.setImageBitmap(cached); return; }
        IO.execute(() -> {
            try {
                for (String ext : previewExts(template)) {
                    File file = previewFile(template, ext);
                    if (file.exists() && file.length() > 0) { showPreview(image, template.id(), memKey, file); return; }
                    File legacy = legacyPreviewFile(template, ext);
                    if (legacy.exists() && legacy.length() > 0) { showPreview(image, template.id(), memKey, legacy); return; }
                }
                for (String ext : previewExts(template)) {
                    try {
                        File file = previewFile(template, ext);
                        writeBytes(file, bytesAuto(previewPath(template, ext, false)));
                        showPreview(image, template.id(), memKey, file);
                        return;
                    } catch (Exception ignored) {}
                    try {
                        File file = previewFile(template, ext);
                        writeBytes(file, bytesAuto(previewPath(template, ext, true)));
                        showPreview(image, template.id(), memKey, file);
                        return;
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        });
    }

    private void showPreview(ImageView image, String expectedTag, String memKey, File file) {
        Bitmap bitmap = decodePreview(file);
        if (bitmap == null) return;
        PREVIEW_MEMORY.put(memKey, bitmap);
        main.post(() -> {
            Object tag = image.getTag();
            if (tag != null && String.valueOf(tag).equals(expectedTag)) image.setImageBitmap(bitmap);
        });
    }

    private Bitmap decodePreview(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inSampleSize = 1;
            while ((bounds.outWidth / options.inSampleSize) > 260 || (bounds.outHeight / options.inSampleSize) > 220) options.inSampleSize *= 2;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Exception e) { return null; }
    }

    private void loadTemplatesFromCache() {
        templates.clear();
        templatesUpdated = prefs().getLong(KEY_TEMPLATES_UPDATED, 0L);
        readTemplateArray(prefs().getString(KEY_TEMPLATES, "[]"));
        if (!templates.isEmpty()) return;
        SharedPreferences legacy = getSharedPreferences(LEGACY_TEMPLATE_PREFS, Context.MODE_PRIVATE);
        String[] keys = {"template_cards_stable_v1", "template_cards_v4", "template_cards_v3", "template_cards"};
        String[] times = {"template_cards_stable_updated_v1", "template_cards_updated_at_v4", "template_cards_updated_at_v3", "template_cards_updated_at"};
        for (int i = 0; i < keys.length && templates.isEmpty(); i++) {
            readTemplateArray(legacy.getString(keys[i], "[]"));
            templatesUpdated = legacy.getLong(times[i], templatesUpdated);
        }
        if (!templates.isEmpty()) saveTemplatesToCache();
    }

    private void readTemplateArray(String raw) {
        try {
            JSONArray arr = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                TemplateCard t = TemplateCard.fromJson(arr.optJSONObject(i));
                if (validTemplate(t)) templates.add(t);
            }
        } catch (Exception ignored) {}
    }

    private void saveTemplatesToCache() {
        JSONArray arr = new JSONArray();
        for (TemplateCard t : templates) {
            try { arr.put(t.toJson()); } catch (Exception ignored) {}
        }
        prefs().edit().putString(KEY_TEMPLATES, arr.toString()).putLong(KEY_TEMPLATES_UPDATED, templatesUpdated).apply();
    }

    private File rawTemplateFile(TemplateCard t) { return new File(cacheDir("raw"), sha1(activeBase() + "|" + t.id()) + ".json"); }
    private File previewFile(TemplateCard t, String ext) { return new File(cacheDir("preview"), sha1(activeBase() + "|" + t.id() + "|" + ext) + "." + ext.replaceAll("[^A-Za-z0-9]", "")); }
    private File legacyPreviewFile(TemplateCard t, String ext) { return new File(new File(getFilesDir(), "template_cache/preview"), sha1(activeBase() + "|" + t.id() + "|" + ext) + "." + ext.replaceAll("[^A-Za-z0-9]", "")); }
    private File cacheDir(String child) { File dir = new File(getFilesDir(), "native_cache/" + child); if (!dir.exists()) dir.mkdirs(); return dir; }
    private String readLegacyRawTemplate(TemplateCard t) {
        for (String base : baseCandidates()) {
            String raw = readText(new File(new File(getFilesDir(), "template_cache/raw"), sha1(base + "|" + t.id()) + ".json"));
            if (!raw.trim().isEmpty()) return raw;
        }
        return "";
    }

    private String templatePath(TemplateCard t) { return "default".equals(t.source) ? "/templates/" + encPath(t.name) + ".json" : "/api/workflow_templates/" + encPath(t.source) + "/" + encPath(t.name) + ".json"; }
    private String previewPath(TemplateCard t, String ext, boolean fallback) { return "default".equals(t.source) ? "/templates/" + encPath(t.name) + (fallback ? "" : "-1") + "." + ext : "/api/workflow_templates/" + encPath(t.source) + "/" + encPath(t.name) + "." + ext; }
    private ArrayList<String> previewExts(TemplateCard t) { LinkedHashSet<String> exts = new LinkedHashSet<>(); String s = safe(t.mediaSubtype).trim().toLowerCase(Locale.US); if (!s.isEmpty()) exts.add(s); Collections.addAll(exts, "webp", "png", "jpg", "jpeg", "gif"); return new ArrayList<>(exts); }

    private void saveConnection() {
        prefs().edit().putString(KEY_URL, rawUrl()).putString(KEY_CF_ID, cfIdEdit.getText().toString().trim()).putString(KEY_CF_SECRET, cfSecretEdit.getText().toString().trim()).apply();
        try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
    }

    private void saveWorkflow() { prefs().edit().putString(KEY_WORKFLOW, workflow == null ? "{}" : workflow.toString()).apply(); }
    private void applyEditedFields() {
        if (workflow == null) return;
        for (FieldBinding binding : fieldBindings) {
            try {
                JSONObject node = workflow.optJSONObject(binding.nodeId);
                if (node != null) node.getJSONObject("inputs").put(binding.key, coerce(binding.edit.getText().toString()));
            } catch (Exception ignored) {}
        }
    }

    private Object coerce(String raw) {
        String s = raw == null ? "" : raw.trim();
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;
        try { if (s.matches("-?\\d+")) return Long.parseLong(s); } catch (Exception ignored) {}
        try { if (s.matches("-?\\d+\\.\\d+")) return Double.parseDouble(s); } catch (Exception ignored) {}
        return raw == null ? "" : raw;
    }

    private OutputRef findOutput(JSONObject history) {
        try {
            Iterator<String> promptKeys = history.keys();
            OutputRef found = null;
            while (promptKeys.hasNext()) {
                JSONObject item = history.optJSONObject(promptKeys.next());
                JSONObject outputs = item == null ? null : item.optJSONObject("outputs");
                if (outputs == null) continue;
                Iterator<String> nodeKeys = outputs.keys();
                while (nodeKeys.hasNext()) {
                    JSONObject output = outputs.optJSONObject(nodeKeys.next());
                    OutputRef ref = firstOutput(output == null ? null : output.optJSONArray("videos")); if (ref != null) found = ref;
                    ref = firstOutput(output == null ? null : output.optJSONArray("gifs")); if (ref != null) found = ref;
                    ref = firstOutput(output == null ? null : output.optJSONArray("images")); if (ref != null) found = ref;
                }
            }
            return found;
        } catch (Exception e) { return null; }
    }

    private OutputRef firstOutput(JSONArray arr) {
        if (arr == null || arr.length() == 0) return null;
        JSONObject file = arr.optJSONObject(0);
        if (file == null) return null;
        String filename = file.optString("filename", "");
        if (filename.isEmpty()) return null;
        return new OutputRef(filename, file.optString("subfolder", ""), file.optString("type", "output"));
    }

    private List<String> nodeIds() { ArrayList<String> ids = new ArrayList<>(); if (workflow == null) return ids; Iterator<String> it = workflow.keys(); while (it.hasNext()) ids.add(it.next()); Collections.sort(ids); return ids; }
    private String nodeTitle(JSONObject node) { JSONObject meta = node == null ? null : node.optJSONObject("_meta"); return prettify(nonEmpty(meta == null ? "" : meta.optString("title", ""), node == null ? "Node" : node.optString("class_type", "Node"))); }
    private String groupName(JSONObject node) { JSONObject meta = node == null ? null : node.optJSONObject("_meta"); return nonEmpty(prettify(meta == null ? "" : meta.optString("group", meta.optString("group_name", ""))), "General"); }
    private List<String> inputKeys(JSONObject inputs) { ArrayList<String> keys = new ArrayList<>(); if (inputs == null) return keys; Iterator<String> it = inputs.keys(); while (it.hasNext()) keys.add(it.next()); Collections.sort(keys); return keys; }
    private String inputKeysText(JSONObject inputs) { StringBuilder sb = new StringBuilder(); for (String key : inputKeys(inputs)) sb.append(' ').append(key); return sb.toString(); }
    private boolean isPrimitive(Object value) { return value == JSONObject.NULL || value instanceof String || value instanceof Number || value instanceof Boolean; }
    private int primitiveCount(JSONObject inputs) { int n = 0; for (String key : inputKeys(inputs)) if (isPrimitive(inputs.opt(key))) n++; return n; }
    private int linkCount(JSONObject inputs) { int n = 0; for (String key : inputKeys(inputs)) if (inputs.optJSONArray(key) != null) n++; return n; }
    private void expandGroups() { if (workflow != null) for (String id : nodeIds()) expandedGroups.add(groupName(workflow.optJSONObject(id))); refreshNodeList(); }

    private boolean validTemplate(TemplateCard t) { return t != null && !safe(t.name).trim().isEmpty() && !safe(t.source).trim().isEmpty(); }
    private String templateSearchText(TemplateCard t) { return displayTitle(t) + " " + safe(t.name) + " " + safe(t.description) + " " + safe(t.category) + " " + safe(t.source); }
    private String displayTitle(TemplateCard t) { return nonEmpty(t.title, t.name); }

    private String rawUrl() {
        String s = urlEdit == null ? "" : urlEdit.getText().toString().trim();
        if (s.isEmpty()) s = prefs().getString(KEY_URL, "");
        if (s.isEmpty()) s = getSharedPreferences(LEGACY_REMOTE_PREFS, Context.MODE_PRIVATE).getString("comfyui_url", "");
        return stripTrailingSlash(s);
    }
    private String stripTrailingSlash(String s) { if (s == null) return ""; s = s.trim(); while (s.endsWith("/")) s = s.substring(0, s.length() - 1); return s; }
    private String hostLabel(String base) { try { return new URL(base).getHost(); } catch (Exception e) { return base; } }

    private View statusChip() {
        String base = activeBase();
        LinearLayout chip = horizontal();
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(12), 0, dp(12), 0);
        chip.setBackground(bg(surface(), 12, stroke(), 1));
        chip.addView(text(base.isEmpty() ? "○" : "●", 16, base.isEmpty() ? mutedColor() : rgb(66, 184, 93)), new LinearLayout.LayoutParams(dp(24), dp(38)));
        TextView label = muted(base.isEmpty() ? "Tap to set ComfyUI URL" : hostLabel(base), 12);
        label.setSingleLine(true);
        chip.addView(label, new LinearLayout.LayoutParams(0, dp(38), 1));
        TextView arrow = muted("›", 20);
        arrow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        chip.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(38)));
        chip.setOnClickListener(v -> connectionPanel.setVisibility(connectionPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        return chip;
    }

    private View pageHeader(String title, String subtitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(title(title, 28));
        TextView sub = muted(subtitle, 14);
        sub.setMaxLines(2);
        box.addView(sub);
        return box;
    }
    private View cardTitle(String icon, String text) { LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL); row.addView(badge(icon), new LinearLayout.LayoutParams(dp(34), dp(34))); TextView label = title(text, 19); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.setMargins(dp(10), 0, 0, 0); row.addView(label, lp); return row; }
    private View metric(String label, String value) { LinearLayout box = card(false); box.setGravity(Gravity.CENTER); TextView l = muted(label, 11); l.setGravity(Gravity.CENTER); TextView v = title(value, 14); v.setGravity(Gravity.CENTER); box.addView(l); box.addView(v); return box; }
    private TextView badge(String s) { TextView v = text(s, 14, accent()); v.setGravity(Gravity.CENTER); v.setBackground(bg(Color.rgb(37, 31, 22), 12, stroke(), 1)); return v; }
    private TextView chip(String s) { TextView v = muted(s, 12); v.setGravity(Gravity.CENTER); v.setSingleLine(true); v.setBackground(bg(surface2(), 14, stroke(), 1)); return v; }
    private LinearLayout horizontal() { LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); return row; }
    private LinearLayout card(boolean accentStroke) { LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(12), dp(12), dp(12), dp(12)); card.setBackground(bg(surface(), 16, accentStroke ? accent() : stroke(), 1)); return card; }
    private EditText input(String hint, boolean singleLine) { EditText e = new EditText(this); e.setHint(hint); e.setSingleLine(singleLine); e.setTextColor(Color.WHITE); e.setHintTextColor(mutedColor()); e.setTextSize(14); e.setPadding(dp(12), 0, dp(12), 0); e.setBackground(bg(surface2(), 12, stroke(), 1)); return e; }
    private EditText bareEdit(String hint, boolean singleLine) { EditText e = new EditText(this); e.setHint(hint); e.setSingleLine(singleLine); e.setTextColor(Color.WHITE); e.setHintTextColor(mutedColor()); e.setTextSize(14); e.setPadding(dp(8), 0, 0, 0); e.setBackgroundColor(Color.TRANSPARENT); return e; }
    private Button button(String label, boolean primary, Runnable action) { Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setSingleLine(true); b.setTextSize(13); b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); b.setTextColor(primary ? accent() : Color.WHITE); b.setPadding(dp(6), 0, dp(6), 0); b.setBackground(bg(primary ? Color.rgb(44, 35, 25) : surface2(), 12, primary ? accent() : stroke(), 1)); b.setOnClickListener(v -> action.run()); return b; }
    private View nav(String icon, String label, String target) { boolean selected = target.equals(screen); LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER); box.setPadding(0, dp(4), 0, dp(4)); box.setBackground(selected ? bg(Color.rgb(37,31,22), 12, accent(), 1) : bg(Color.TRANSPARENT, 12, Color.TRANSPARENT, 0)); TextView i = text(icon, 19, selected ? accent() : mutedColor()); i.setGravity(Gravity.CENTER); box.addView(i, new LinearLayout.LayoutParams(-1, dp(24))); TextView l = text(label, 10, selected ? accent() : mutedColor()); l.setGravity(Gravity.CENTER); l.setSingleLine(true); box.addView(l, new LinearLayout.LayoutParams(-1, dp(20))); box.setOnClickListener(v -> { screen = target; render(); }); return box; }
    private void renderBottomNav() { bottomNav.removeAllViews(); bottomNav.addView(nav("⊞", "Create", "create"), weightLp(58)); bottomNav.addView(nav("⌘", "Nodes", "nodes"), weightLp(58)); bottomNav.addView(nav("▦", "Templates", "templates"), weightLp(58)); bottomNav.addView(nav("▷", "Run", "run"), weightLp(58)); bottomNav.addView(nav("▧", "Output", "output"), weightLp(58)); }

    private LinearLayout.LayoutParams sectionLp() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, 0, 0, dp(12)); return p; }
    private LinearLayout.LayoutParams weightLp(int h) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(h), 1); p.setMargins(dp(4), 0, dp(4), 0); return p; }
    private LinearLayout.LayoutParams boxLp(int w, int h, int l, int t, int r, int b) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h < 0 ? h : dp(h)); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }
    private TextView title(String s, int sp) { TextView t = text(s, sp, Color.WHITE); t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); t.setMaxLines(3); t.setEllipsize(TextUtils.TruncateAt.END); return t; }
    private TextView label(String s) { TextView t = text(s, 13, Color.rgb(210, 210, 216)); t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); return t; }
    private TextView muted(String s, int sp) { return text(s, sp, mutedColor()); }
    private TextView text(String s, int sp, int color) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setIncludeFontPadding(false); t.setPadding(dp(2), 0, dp(2), dp(5)); return t; }
    private GradientDrawable bg(int color, int radius, int strokeColor, int strokeWidth) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); d.setStroke(dp(strokeWidth), strokeColor); return d; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private int rgb(int r, int g, int b) { return Color.rgb(r, g, b); }
    private int bgRoot() { return Color.rgb(18, 18, 19); }
    private int surface() { return Color.rgb(28, 28, 30); }
    private int surface2() { return Color.rgb(33, 33, 36); }
    private int stroke() { return Color.rgb(48, 48, 52); }
    private int mutedColor() { return Color.rgb(170, 170, 178); }
    private int accent() { return Color.rgb(218, 143, 60); }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private void setStatus(String msg) { if (templateLine != null) templateLine.setText(msg); Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }
    private void openUrl(String url) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { setStatus("Could not open URL."); } }
    private void styleSystemBars() { Window w = getWindow(); w.setStatusBarColor(bgRoot()); w.setNavigationBarColor(bgRoot()); w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE); }
    private String readUri(Uri uri) throws Exception { InputStream in = getContentResolver().openInputStream(uri); if (in == null) throw new Exception("empty file"); try { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); return out.toString("UTF-8"); } finally { in.close(); } }
    private String readText(File f) { try { byte[] b = readBytes(f); return b.length == 0 ? "" : new String(b, "UTF-8"); } catch (Exception e) { return ""; } }
    private byte[] readBytes(File f) { if (f == null || !f.exists()) return new byte[0]; ByteArrayOutputStream out = new ByteArrayOutputStream(); try { FileInputStream in = new FileInputStream(f); byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); in.close(); } catch (Exception ignored) {} return out.toByteArray(); }
    private void writeText(File f, String s) { try { writeBytes(f, safe(s).getBytes("UTF-8")); } catch (Exception ignored) {} }
    private void writeBytes(File f, byte[] data) { try { File parent = f.getParentFile(); if (parent != null && !parent.exists()) parent.mkdirs(); FileOutputStream out = new FileOutputStream(f); out.write(data); out.close(); } catch (Exception ignored) {} }
    private String sha1(String s) { try { MessageDigest md = MessageDigest.getInstance("SHA-1"); byte[] d = md.digest(safe(s).getBytes("UTF-8")); StringBuilder sb = new StringBuilder(); for (byte b : d) sb.append(String.format(Locale.US, "%02x", b & 0xff)); return sb.toString(); } catch (Exception e) { return String.valueOf(safe(s).hashCode()).replace("-", "n"); } }
    private String enc(String s) { try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; } }
    private String encPath(String s) { return enc(s).replace("%2F", "/"); }
    private String safe(String s) { return s == null ? "" : s; }
    private String nonEmpty(String v, String fallback) { return v == null || v.trim().isEmpty() ? safe(fallback) : v.trim(); }
    private String firstNonEmpty(String a, String b) { return a != null && !a.trim().isEmpty() ? a : safe(b); }
    private String shortText(String s, int max) { if (s == null) return ""; return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…"; }
    private String shortError(Exception e) { String s = e == null ? "" : e.getMessage(); if (s == null || s.trim().isEmpty()) s = e == null ? "unknown error" : e.getClass().getSimpleName(); return s.length() > 220 ? s.substring(0, 220) + "…" : s; }
    private String timeAgo(long ts) { long sec = Math.max(0L, (System.currentTimeMillis() - ts) / 1000L); if (sec < 60) return "just now"; long min = sec / 60L; if (min < 60) return min + "m ago"; long h = min / 60L; if (h < 24) return h + "h ago"; return (h / 24L) + "d ago"; }
    private String prettify(String s) { return safe(s).replace('_', ' ').replaceAll("([a-z])([A-Z])", "$1 $2").trim(); }
}
