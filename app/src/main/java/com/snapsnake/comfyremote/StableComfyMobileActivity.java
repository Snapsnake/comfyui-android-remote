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
import android.widget.ProgressBar;
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
import java.io.OutputStream;
import java.net.HttpURLConnection;
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

public class StableComfyMobileActivity extends Activity {
    private static final String PREFS = "comfyui_mobile_stable_prefs";
    private static final String KEY_URL = "url";
    private static final String KEY_CF_ID = "cf_id";
    private static final String KEY_CF_SECRET = "cf_secret";
    private static final String KEY_WORKFLOW = "workflow";
    private static final String KEY_OPTIONS = "options";
    private static final String KEY_OUTPUT = "last_output";
    private static final String KEY_TEMPLATES = "templates_v2";
    private static final String KEY_TEMPLATES_AT = "templates_updated_at_v2";
    private static final int REQ_JSON = 5001;
    private static final int PAGE_SIZE = 50;
    private static final int MAX_TEXT_BYTES = 24 * 1024 * 1024;
    private static final ExecutorService IO = Executors.newFixedThreadPool(5);
    private static final LruCache<String, Bitmap> PREVIEWS = new LruCache<>(48);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout root, topPanel, content, bottomNav, nodeList, templateList;
    private ScrollView scroll;
    private EditText urlInput, cfIdInput, cfSecretInput, jsonEditor, nodeSearch, templateSearch;
    private TextView statusText, templateStatus, loadedText, updatedText, generationText;
    private ProgressBar progress;
    private JSONObject workflow;
    private JSONObject fieldOptions = new JSONObject();
    private final ArrayList<ApiField> fields = new ArrayList<>();
    private final ArrayList<TemplateItem> templates = new ArrayList<>();
    private final Set<String> expandedGroups = new HashSet<>();
    private String screen = "create";
    private String activeBaseUrl = "";
    private String selectedNodeId = null;
    private String nodeQuery = "";
    private String templateQuery = "";
    private String currentPromptId = "";
    private String lastOutputUrl = "";
    private int templateLimit = PAGE_SIZE;
    private long templatesUpdatedAt = 0L;
    private long runStartedAt = 0L;
    private int pollCount = 0;
    private boolean templatesRefreshing = false;
    private boolean generationRunning = false;

    private static class ApiField {
        final String nodeId, key;
        final EditText edit;
        ApiField(String nodeId, String key, EditText edit) { this.nodeId = nodeId; this.key = key; this.edit = edit; }
    }

    private static class TemplateItem {
        String source = "default", name = "", title = "", description = "", category = "Templates", mediaSubtype = "webp";
        String id() { return safe(source) + "/" + safe(name); }
        JSONObject toJson() throws Exception { JSONObject o = new JSONObject(); o.put("source", safe(source)); o.put("name", safe(name)); o.put("title", safe(title)); o.put("description", safe(description)); o.put("category", safe(category)); o.put("mediaSubtype", safe(mediaSubtype)); return o; }
        static TemplateItem fromJson(JSONObject o) { TemplateItem t = new TemplateItem(); if (o == null) return t; t.source = o.optString("source", "default"); t.name = o.optString("name", ""); t.title = o.optString("title", t.name); t.description = o.optString("description", ""); t.category = o.optString("category", "Templates"); t.mediaSubtype = o.optString("mediaSubtype", "webp"); return t; }
        static String safe(String s) { return s == null ? "" : s; }
    }

    private static class OutputFile {
        final String filename, subfolder, type;
        OutputFile(String filename, String subfolder, String type) { this.filename = filename; this.subfolder = subfolder; this.type = type; }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try { CookieManager.getInstance().setAcceptCookie(true); } catch (Exception ignored) {}
        buildShell();
        loadState();
        render();
        applyBars();
    }

    private void buildShell() {
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
        urlInput = input("https://comfyui.example.com", true);
        topPanel.addView(urlInput, boxLp(-1, 46, 0, 8, 0, 8));
        LinearLayout row = row();
        row.addView(button("Test", false, this::testConnection), weight(42));
        row.addView(button("Create", false, () -> { screen = "create"; render(); }), weight(42));
        row.addView(button("Templates", true, () -> { screen = "templates"; render(); }), weight(42));
        topPanel.addView(row, new LinearLayout.LayoutParams(-1, dp(42)));
        TextView cf = muted("Cloudflare Access optional", 12);
        topPanel.addView(cf, boxLp(-1, -2, 0, 8, 0, 4));
        cfIdInput = input("CF-Access-Client-Id", true);
        topPanel.addView(cfIdInput, new LinearLayout.LayoutParams(-1, dp(42)));
        cfSecretInput = input("CF-Access-Client-Secret", true);
        cfSecretInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        topPanel.addView(cfSecretInput, boxLp(-1, 42, 0, 6, 0, 0));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

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

    private void loadState() {
        SharedPreferences p = prefs();
        urlInput.setText(p.getString(KEY_URL, ""));
        cfIdInput.setText(p.getString(KEY_CF_ID, ""));
        cfSecretInput.setText(p.getString(KEY_CF_SECRET, ""));
        lastOutputUrl = p.getString(KEY_OUTPUT, "");
        if (!urlInput.getText().toString().trim().isEmpty()) topPanel.setVisibility(View.GONE);
        try { workflow = new JSONObject(p.getString(KEY_WORKFLOW, "{}")); if (workflow.length() == 0) workflow = null; } catch (Exception e) { workflow = null; }
        try { fieldOptions = new JSONObject(p.getString(KEY_OPTIONS, "{}")); } catch (Exception e) { fieldOptions = new JSONObject(); }
        loadTemplateCache();
    }

    private void saveConnectionPrefs() {
        prefs().edit().putString(KEY_URL, rawUrl()).putString(KEY_CF_ID, cfIdInput.getText().toString().trim()).putString(KEY_CF_SECRET, cfSecretInput.getText().toString().trim()).apply();
        try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
    }

    private void render() {
        fields.clear();
        content.removeAllViews();
        renderBottomNav();
        if ("nodes".equals(screen)) renderNodes();
        else if ("templates".equals(screen)) renderTemplatesScreen();
        else if ("run".equals(screen)) renderRun();
        else if ("output".equals(screen)) renderOutput();
        else renderCreate();
        scroll.scrollTo(0, 0);
    }

    private void renderCreate() {
        content.addView(statusChip(), section());
        content.addView(pageHeader("Create", workflow == null ? "Choose a template or load workflow JSON." : workflow.length() + " nodes loaded."), section());
        LinearLayout card = card(false);
        card.addView(cardTitle("‹/›", "Workflow"));
        LinearLayout actions = row();
        actions.setPadding(0, dp(10), 0, dp(10));
        actions.addView(button("▦ Templates", false, () -> { screen = "templates"; render(); }), weight(46));
        actions.addView(button("⇩ Import JSON", true, this::chooseJson), weight(46));
        card.addView(actions);
        if (workflow != null) {
            card.addView(muted("Loaded workflow. Use Nodes to edit fields or Run to execute.", 13));
            LinearLayout loaded = row();
            loaded.setPadding(0, dp(12), 0, 0);
            loaded.addView(button("Nodes", false, () -> { screen = "nodes"; render(); }), weight(44));
            loaded.addView(button("Run", true, () -> { screen = "run"; render(); }), weight(44));
            card.addView(loaded);
        } else {
            card.addView(label("Fallback: paste workflow JSON"));
            jsonEditor = input("Paste ComfyUI workflow/API JSON here…", false);
            jsonEditor.setSingleLine(false);
            jsonEditor.setGravity(Gravity.TOP | Gravity.LEFT);
            jsonEditor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            card.addView(jsonEditor, new LinearLayout.LayoutParams(-1, dp(150)));
            LinearLayout jsonActions = row();
            jsonActions.setPadding(0, dp(12), 0, 0);
            jsonActions.addView(button("Load JSON", false, this::chooseJson), weight(44));
            jsonActions.addView(button("Apply JSON", true, () -> importWorkflowJson(jsonEditor.getText().toString())), weight(44));
            card.addView(jsonActions);
        }
        content.addView(card, section());
        LinearLayout tip = card(true);
        tip.addView(label("Tip"));
        tip.addView(muted("The native importer reads frontend workflow, API prompt, and subgraphs. It does not rely on stale extra.prompt only.", 13));
        content.addView(tip, section());
    }

    private void renderNodes() {
        content.addView(statusChip(), section());
        content.addView(pageHeader("Nodes", "Search, edit and expand grouped nodes."), section());
        LinearLayout tools = card(false);
        LinearLayout searchBox = row();
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(dp(12), 0, dp(10), 0);
        searchBox.setBackground(bg(surface2(), 10, stroke(), 1));
        searchBox.addView(muted("⌕", 22), new LinearLayout.LayoutParams(dp(30), -1));
        nodeSearch = new EditText(this);
        nodeSearch.setSingleLine(true);
        nodeSearch.setText(nodeQuery);
        nodeSearch.setHint("Search nodes, classes, fields…");
        nodeSearch.setTextColor(Color.WHITE);
        nodeSearch.setHintTextColor(mutedColor());
        nodeSearch.setTextSize(14);
        nodeSearch.setBackgroundColor(Color.TRANSPARENT);
        searchBox.addView(nodeSearch, new LinearLayout.LayoutParams(0, -1, 1));
        tools.addView(searchBox, new LinearLayout.LayoutParams(-1, dp(46)));
        LinearLayout buttons = row();
        buttons.setPadding(0, dp(10), 0, 0);
        buttons.addView(button("Expand groups", false, this::expandAllGroups), weight(42));
        buttons.addView(button("Clear", false, () -> { nodeQuery = ""; if (nodeSearch != null) nodeSearch.setText(""); refreshNodeList(); }), weight(42));
        tools.addView(buttons);
        content.addView(tools, section());
        nodeList = new LinearLayout(this);
        nodeList.setOrientation(LinearLayout.VERTICAL);
        content.addView(nodeList, new LinearLayout.LayoutParams(-1, -2));
        nodeSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { nodeQuery = String.valueOf(s == null ? "" : s); refreshNodeList(); }
            public void afterTextChanged(Editable s) {}
        });
        refreshNodeList();
    }

    private void refreshNodeList() {
        if (nodeList == null) return;
        nodeList.removeAllViews();
        if (workflow == null || workflow.length() == 0) { nodeList.addView(muted("No workflow imported.", 14)); return; }
        String q = nodeQuery == null ? "" : nodeQuery.trim().toLowerCase(Locale.US);
        LinkedHashMap<String, ArrayList<String>> groups = new LinkedHashMap<>();
        for (String id : nodeIds()) {
            JSONObject n = workflow.optJSONObject(id);
            if (n == null) continue;
            String hay = id + " " + nodeTitle(n) + " " + n.optString("class_type") + " " + inputKeysText(n.optJSONObject("inputs")) + " " + groupOf(n);
            if (!q.isEmpty() && !hay.toLowerCase(Locale.US).contains(q)) continue;
            String group = groupOf(n);
            ArrayList<String> list = groups.get(group);
            if (list == null) { list = new ArrayList<>(); groups.put(group, list); }
            list.add(id);
        }
        if (groups.isEmpty()) { nodeList.addView(muted("Nothing found.", 14)); return; }
        boolean hasGroups = groups.size() > 1 || !groups.containsKey("General");
        for (Map.Entry<String, ArrayList<String>> e : groups.entrySet()) {
            if (hasGroups) nodeList.addView(groupCard(e.getKey(), e.getValue()), section());
            if (!hasGroups || expandedGroups.contains(e.getKey()) || !q.isEmpty()) for (String id : e.getValue()) nodeList.addView(nodeCard(id), section());
        }
    }

    private View groupCard(String group, ArrayList<String> ids) {
        LinearLayout c = card(true);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        boolean open = expandedGroups.contains(group);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        c.addView(body, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(title(group, 16));
        body.addView(muted(ids.size() + " nodes" + (open ? " · expanded" : " · tap to expand"), 12));
        TextView arrow = title(open ? "⌃" : "⌄", 22); arrow.setGravity(Gravity.CENTER);
        c.addView(arrow, new LinearLayout.LayoutParams(dp(34), dp(42)));
        c.setOnClickListener(v -> { if (expandedGroups.contains(group)) expandedGroups.remove(group); else expandedGroups.add(group); refreshNodeList(); });
        return c;
    }

    private View nodeCard(String id) {
        JSONObject n = workflow.optJSONObject(id);
        LinearLayout c = card(false);
        if (n == null) return c;
        LinearLayout head = row(); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(title(nodeTitle(n), 16), new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = muted("›", 28); arrow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); head.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(42)));
        c.addView(head);
        c.addView(muted("#" + id + " · " + prettify(n.optString("class_type", "Node")) + ("General".equals(groupOf(n)) ? "" : " · " + groupOf(n)), 12));
        JSONObject inputs = n.optJSONObject("inputs");
        LinearLayout chips = row(); chips.setPadding(0, dp(8), 0, 0);
        chips.addView(chip(primitiveCount(inputs) + " fields"), weight(32));
        chips.addView(chip(linkCount(inputs) + " linked"), weight(32));
        c.addView(chips);
        c.setOnClickListener(v -> { selectedNodeId = id; renderNodeEditor(id); });
        return c;
    }

    private void renderNodeEditor(String id) {
        screen = "create";
        fields.clear();
        content.removeAllViews();
        renderBottomNav();
        content.addView(statusChip(), section());
        JSONObject n = workflow == null ? null : workflow.optJSONObject(id);
        content.addView(pageHeader(n == null ? "Node" : nodeTitle(n), n == null ? "" : "#" + id + " · " + n.optString("class_type", "")), section());
        LinearLayout card = card(false);
        if (n == null) { card.addView(muted("Node not found.", 14)); content.addView(card, section()); return; }
        JSONObject inputs = n.optJSONObject("inputs");
        boolean any = false;
        for (String key : inputKeys(inputs)) {
            Object val = inputs.opt(key);
            if (!primitive(val)) continue;
            any = true;
            card.addView(label(prettify(key)));
            EditText e = input("", false);
            e.setText(val == JSONObject.NULL ? "" : String.valueOf(val));
            boolean multi = key.toLowerCase(Locale.US).contains("prompt") || key.toLowerCase(Locale.US).contains("text") || String.valueOf(val).length() > 80;
            e.setSingleLine(!multi); e.setGravity(multi ? Gravity.TOP | Gravity.LEFT : Gravity.CENTER_VERTICAL | Gravity.LEFT);
            e.setInputType(multi ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
            card.addView(e, new LinearLayout.LayoutParams(-1, multi ? dp(110) : dp(44)));
            fields.add(new ApiField(id, key, e));
        }
        if (!any) card.addView(muted("No direct editable fields.", 13));
        LinearLayout actions = row(); actions.setPadding(0, dp(12), 0, 0);
        actions.addView(button("Back to Nodes", false, () -> { screen = "nodes"; render(); }), weight(44));
        actions.addView(button("Apply", true, () -> { applyFields(); saveWorkflow(); toast("Applied"); }), weight(44));
        card.addView(actions);
        content.addView(card, section());
    }

    private void renderTemplatesScreen() {
        content.addView(statusChip(), section());
        content.addView(pageHeader("Templates", "Browse and load workflow templates"), section());
        LinearLayout tools = card(false);
        LinearLayout searchBox = row(); searchBox.setGravity(Gravity.CENTER_VERTICAL); searchBox.setPadding(dp(12), 0, dp(10), 0); searchBox.setBackground(bg(surface2(), 10, stroke(), 1));
        searchBox.addView(muted("⌕", 22), new LinearLayout.LayoutParams(dp(30), -1));
        templateSearch = new EditText(this); templateSearch.setSingleLine(true); templateSearch.setText(templateQuery); templateSearch.setHint("Search templates…"); templateSearch.setTextColor(Color.WHITE); templateSearch.setHintTextColor(mutedColor()); templateSearch.setTextSize(14); templateSearch.setBackgroundColor(Color.TRANSPARENT);
        searchBox.addView(templateSearch, new LinearLayout.LayoutParams(0, -1, 1));
        TextView tune = muted("☷", 18); tune.setGravity(Gravity.CENTER); searchBox.addView(tune, new LinearLayout.LayoutParams(dp(32), -1));
        tools.addView(searchBox, new LinearLayout.LayoutParams(-1, dp(46)));
        LinearLayout meta = row(); meta.setPadding(0, dp(8), 0, 0);
        loadedText = muted("", 12); updatedText = muted("", 12); updatedText.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        meta.addView(loadedText, new LinearLayout.LayoutParams(0, dp(30), 1)); meta.addView(updatedText, new LinearLayout.LayoutParams(0, dp(30), 1)); tools.addView(meta);
        LinearLayout actions = row(); actions.setPadding(0, dp(4), 0, dp(4));
        actions.addView(button(templatesRefreshing ? "⟳ Refreshing…" : "⟳ Refresh", true, this::refreshTemplates), weight(42));
        actions.addView(button("⌫ Clear", false, () -> { templateQuery = ""; templateLimit = PAGE_SIZE; if (templateSearch != null) templateSearch.setText(""); refreshTemplateList(); }), weight(42));
        tools.addView(actions);
        templateStatus = muted("Auto-tries https/http, caches templates and previews.", 12); templateStatus.setSingleLine(true); templateStatus.setEllipsize(TextUtils.TruncateAt.END); tools.addView(templateStatus);
        content.addView(tools, section());
        templateList = new LinearLayout(this); templateList.setOrientation(LinearLayout.VERTICAL); content.addView(templateList, new LinearLayout.LayoutParams(-1, -2));
        templateSearch.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s, int start, int count, int after) {} public void onTextChanged(CharSequence s, int start, int before, int count) { templateQuery = String.valueOf(s == null ? "" : s); templateLimit = PAGE_SIZE; refreshTemplateList(); } public void afterTextChanged(Editable s) {} });
        refreshTemplateMeta();
        if (templates.isEmpty() && !rawUrl().trim().isEmpty()) refreshTemplates();
        refreshTemplateList();
    }

    private void refreshTemplates() {
        if (templatesRefreshing) return;
        saveConnectionPrefs();
        if (baseCandidates().isEmpty()) { setStatus("Set a valid ComfyUI URL first."); topPanel.setVisibility(View.VISIBLE); return; }
        templatesRefreshing = true; setStatus("Refreshing templates..."); if (templateStatus != null) templateStatus.setText("Refreshing templates...");
        IO.execute(() -> {
            ArrayList<TemplateItem> loaded = new ArrayList<>(); String error = "";
            try {
                String json = getTextAuto("/templates/index.json");
                JSONArray index = new JSONArray(json);
                for (int i = 0; i < index.length(); i++) {
                    JSONObject cat = index.optJSONObject(i); if (cat == null) continue;
                    String source = nonEmpty(cat.optString("moduleName", "default"), "default"); String catTitle = nonEmpty(cat.optString("localizedTitle", cat.optString("title", source)), "Templates");
                    JSONArray arr = cat.optJSONArray("templates"); if (arr == null) continue;
                    for (int j = 0; j < arr.length(); j++) { JSONObject raw = arr.optJSONObject(j); if (raw == null) continue; TemplateItem item = new TemplateItem(); item.source = source; item.name = raw.optString("name", "").trim(); item.title = raw.optString("localizedTitle", raw.optString("title", item.name)); item.description = raw.optString("localizedDescription", raw.optString("description", "")); item.category = catTitle; item.mediaSubtype = raw.optString("mediaSubtype", "webp"); if (!item.name.isEmpty()) loaded.add(item); }
                }
            } catch (Exception e) { error = shortErr(e); }
            try {
                String custom = getTextAuto("/api/workflow_templates");
                JSONObject obj = new JSONObject(custom); Iterator<String> it = obj.keys();
                while (it.hasNext()) { String module = it.next(); JSONArray arr = obj.optJSONArray(module); if (arr == null) continue; for (int i = 0; i < arr.length(); i++) { String name = arr.optString(i, "").trim(); if (name.isEmpty()) continue; TemplateItem item = new TemplateItem(); item.source = module; item.name = name; item.title = name; item.description = module; item.category = "Custom templates"; item.mediaSubtype = ""; loaded.add(item); } }
            } catch (Exception ignored) {}
            String err = error;
            ui.post(() -> { templatesRefreshing = false; if (!loaded.isEmpty()) { templates.clear(); templates.addAll(loaded); templatesUpdatedAt = System.currentTimeMillis(); saveTemplateCache(); refreshTemplateMeta(); refreshTemplateList(); setStatus("Loaded " + loaded.size() + " templates."); preloadTemplates(new ArrayList<>(loaded)); } else { refreshTemplateList(); setStatus("Refresh failed: " + (err.isEmpty() ? "no templates returned" : err)); if (templateStatus != null) templateStatus.setText("Refresh failed: " + (err.isEmpty() ? "no templates returned" : err)); } });
        });
    }

    private void preloadTemplates(ArrayList<TemplateItem> list) { IO.execute(() -> { int ok = 0; for (int i = 0; i < list.size(); i++) { try { TemplateItem t = list.get(i); writeText(rawTemplateFile(t), getTextAuto(templatePath(t))); ok++; } catch (Exception ignored) {} if (i % 25 == 0) { int done = i + 1, count = ok; ui.post(() -> setStatus("Caching workflows: " + done + "/" + list.size() + " ready " + count)); } } int count = ok; ui.post(() -> setStatus("Templates ready. Cached workflows: " + count + ".")); }); }

    private void refreshTemplateMeta() { if (loadedText != null) { loadedText.setText((templates.isEmpty() ? "○" : "●") + " Loaded " + templates.size() + " templates"); loadedText.setTextColor(templates.isEmpty() ? mutedColor() : accent()); } if (updatedText != null) updatedText.setText(templatesUpdatedAt > 0 ? "Updated " + timeAgo(templatesUpdatedAt) : "Not refreshed yet"); }

    private void refreshTemplateList() {
        if (templateList == null) return; templateList.removeAllViews();
        String q = templateQuery == null ? "" : templateQuery.trim().toLowerCase(Locale.US); ArrayList<TemplateItem> matches = new ArrayList<>();
        for (TemplateItem t : templates) if (validTemplate(t) && (q.isEmpty() || templateHay(t).toLowerCase(Locale.US).contains(q))) matches.add(t);
        int max = Math.min(templateLimit, matches.size()); for (int i = 0; i < max; i++) templateList.addView(templateCard(matches.get(i)), section());
        if (matches.isEmpty()) templateList.addView(muted(templates.isEmpty() ? "No templates cached yet. Tap Refresh." : "Nothing found.", 14));
        if (matches.size() > max) { LinearLayout more = card(false); more.setGravity(Gravity.CENTER_HORIZONTAL); more.addView(muted("Showing " + max + " of " + matches.size() + " templates.", 13)); more.addView(button("Show more", false, () -> { templateLimit += PAGE_SIZE; refreshTemplateList(); }), new LinearLayout.LayoutParams(-1, dp(40))); templateList.addView(more, section()); }
    }

    private View templateCard(TemplateItem t) {
        LinearLayout row = card(false); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(12), dp(12), dp(10), dp(12)); row.setClickable(true); row.setOnClickListener(v -> openTemplate(t));
        ImageView img = new ImageView(this); img.setScaleType(ImageView.ScaleType.CENTER_CROP); img.setImageResource(R.drawable.ic_launcher); img.setBackground(bg(surface2(), 8, stroke(), 1)); img.setTag(t.id()); LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(106), dp(82)); ip.setMargins(0, 0, dp(12), 0); row.addView(img, ip);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setGravity(Gravity.CENTER_VERTICAL); row.addView(body, new LinearLayout.LayoutParams(0, dp(82), 1));
        TextView name = title(displayTitle(t), 16); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END); body.addView(name);
        TextView desc = muted(shortText(nonEmpty(t.description, t.category).replace('_', ' '), 130), 12); desc.setMaxLines(2); desc.setEllipsize(TextUtils.TruncateAt.END); body.addView(desc);
        TextView arrow = muted("›", 28); arrow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); row.addView(arrow, new LinearLayout.LayoutParams(dp(22), dp(82))); loadPreview(img, t); return row;
    }

    private void openTemplate(TemplateItem t) {
        if (!validTemplate(t)) { setStatus("Invalid template entry."); return; }
        setStatus("Opening template: " + displayTitle(t)); if (templateStatus != null) templateStatus.setText("Opening template: " + displayTitle(t));
        IO.execute(() -> { try { String raw = readText(rawTemplateFile(t)); if (raw.trim().isEmpty()) { raw = getTextAuto(templatePath(t)); writeText(rawTemplateFile(t), raw); } JSONObject result = ComfyWorkflowConverter.importResult(new JSONObject(raw), objectInfoOrEmpty()); ui.post(() -> handleImportResult(result)); } catch (Exception e) { ui.post(() -> { setStatus("Template open failed: " + shortErr(e)); if (templateStatus != null) templateStatus.setText("Template open failed: " + shortErr(e)); }); } });
    }

    private void renderRun() {
        content.addView(statusChip(), section()); content.addView(pageHeader("Run", "Execute and review results."), section());
        LinearLayout card = card(false); card.addView(cardTitle("▷", workflow == null ? "No workflow loaded" : "Workflow loaded")); card.addView(muted(workflow == null ? "Choose template or load JSON first." : workflow.length() + " nodes ready.", 13));
        LinearLayout metrics = row(); metrics.setPadding(0, dp(12), 0, dp(8)); metrics.addView(metric("Nodes", workflow == null ? "0" : String.valueOf(workflow.length())), weight(58)); metrics.addView(metric("Status", generationRunning ? "Run" : "Idle"), weight(58)); card.addView(metrics);
        generationText = muted(generationRunning ? "Generating..." : "Ready.", 13); card.addView(generationText);
        LinearLayout actions = row(); actions.setPadding(0, dp(12), 0, 0); actions.addView(button("Output", false, () -> { screen = "output"; render(); }), weight(44)); actions.addView(button("Run ▷", true, this::runWorkflow), weight(44)); card.addView(actions); content.addView(card, section());
    }

    private void renderOutput() {
        content.addView(statusChip(), section()); content.addView(pageHeader("Output", "Last generated result."), section());
        LinearLayout card = card(false); card.addView(cardTitle("▧", "Recent Output"));
        if (lastOutputUrl == null || lastOutputUrl.trim().isEmpty()) card.addView(muted("No output yet.", 14));
        else { card.addView(muted(shortText(lastOutputUrl, 300), 12)); card.addView(button("Open output", true, () -> openUrl(lastOutputUrl)), new LinearLayout.LayoutParams(-1, dp(44))); }
        content.addView(card, section());
    }

    private void chooseJson() { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); try { startActivityForResult(Intent.createChooser(i, "Choose ComfyUI workflow JSON"), REQ_JSON); } catch (Exception e) { toast("No file picker available"); } }

    @Override protected void onActivityResult(int req, int result, Intent data) { super.onActivityResult(req, result, data); if (req == REQ_JSON && result == RESULT_OK && data != null && data.getData() != null) { try { importWorkflowJson(readUriText(data.getData())); } catch (Exception e) { setStatus("Could not read JSON: " + shortErr(e)); } } }

    private void importWorkflowJson(String raw) { if (raw == null || raw.trim().isEmpty()) { setStatus("Empty JSON."); return; } setStatus("Importing workflow..."); IO.execute(() -> { try { JSONObject result = ComfyWorkflowConverter.importResult(new JSONObject(raw), objectInfoOrEmpty()); ui.post(() -> handleImportResult(result)); } catch (Exception e) { ui.post(() -> setStatus("Workflow import failed: " + shortErr(e))); } }); }

    private void handleImportResult(JSONObject result) { try { if (!result.optBoolean("ok", false)) { setStatus("Import failed: " + result.optString("error")); return; } workflow = result.optJSONObject("prompt"); if (workflow == null) workflow = new JSONObject(result.optString("prompt", "{}")); fieldOptions = result.optJSONObject("options"); if (fieldOptions == null) fieldOptions = new JSONObject(); selectedNodeId = firstNodeId(); saveWorkflow(); screen = "create"; setStatus("Imported " + workflow.length() + " nodes."); render(); } catch (Exception e) { setStatus("Import failed: " + shortErr(e)); } }

    private void runWorkflow() {
        if (workflow == null || workflow.length() == 0) { setStatus("Import a workflow first."); return; }
        saveConnectionPrefs(); setStatus("Sending prompt..."); generationRunning = true; runStartedAt = System.currentTimeMillis(); pollCount = 0; if (generationText != null) generationText.setText("Sending prompt...");
        IO.execute(() -> { try { JSONObject payload = new JSONObject(); payload.put("prompt", workflow); payload.put("client_id", "comfyui-mobile-" + System.currentTimeMillis()); JSONObject res = new JSONObject(postJsonAuto("/prompt", payload.toString())); currentPromptId = res.optString("prompt_id", ""); if (currentPromptId.isEmpty()) throw new Exception("ComfyUI did not return prompt_id"); ui.post(() -> { setStatus("Queued. Waiting for output..."); pollHistory(); }); } catch (Exception e) { ui.post(() -> { generationRunning = false; setStatus("Run failed: " + shortErr(e)); if (generationText != null) generationText.setText("Run failed: " + shortErr(e)); }); } });
    }

    private void pollHistory() { if (currentPromptId == null || currentPromptId.isEmpty()) return; pollCount++; IO.execute(() -> { try { JSONObject h = new JSONObject(getTextAuto("/history/" + enc(currentPromptId))); OutputFile f = findOutput(h); if (f != null) { String base = activeBase(); lastOutputUrl = base + "/view?filename=" + enc(f.filename) + "&type=" + enc(f.type) + "&subfolder=" + enc(f.subfolder); prefs().edit().putString(KEY_OUTPUT, lastOutputUrl).apply(); ui.post(() -> { generationRunning = false; setStatus("Output ready."); screen = "output"; render(); }); return; } } catch (Exception ignored) {} if (pollCount < 240) ui.postDelayed(this::pollHistory, 2000); else ui.post(() -> { generationRunning = false; setStatus("Timed out waiting for output."); if (generationText != null) generationText.setText("Timed out waiting for output."); }); }); }

    private OutputFile findOutput(JSONObject history) { try { Iterator<String> p = history.keys(); OutputFile found = null; while (p.hasNext()) { JSONObject item = history.optJSONObject(p.next()); JSONObject outs = item == null ? null : item.optJSONObject("outputs"); if (outs == null) continue; Iterator<String> nodes = outs.keys(); while (nodes.hasNext()) { JSONObject o = outs.optJSONObject(nodes.next()); OutputFile f = firstOutput(o == null ? null : o.optJSONArray("videos")); if (f != null) found = f; f = firstOutput(o == null ? null : o.optJSONArray("gifs")); if (f != null) found = f; f = firstOutput(o == null ? null : o.optJSONArray("images")); if (f != null) found = f; } } return found; } catch (Exception e) { return null; } }
    private OutputFile firstOutput(JSONArray arr) { if (arr == null || arr.length() == 0) return null; JSONObject f = arr.optJSONObject(0); if (f == null) return null; String name = f.optString("filename", ""); if (name.isEmpty()) return null; return new OutputFile(name, f.optString("subfolder", ""), f.optString("type", "output")); }

    private void testConnection() { saveConnectionPrefs(); setStatus("Testing connection..."); IO.execute(() -> { try { String stats = getTextAuto("/system_stats"); ui.post(() -> setStatus("Connection OK: " + hostLabel(activeBase()))); } catch (Exception e) { ui.post(() -> setStatus("Connection failed: " + shortErr(e))); } }); }

    private JSONObject objectInfoOrEmpty() { try { return new JSONObject(getTextAuto("/api/object_info")); } catch (Exception e) { return new JSONObject(); } }

    private String getTextAuto(String path) throws Exception { Exception last = null; for (String base : baseCandidates()) { try { String r = getTextUrl(base + path); activeBaseUrl = base; prefs().edit().putString(KEY_URL, base).apply(); return r; } catch (Exception e) { last = e; } } throw last == null ? new Exception("No valid URL") : last; }
    private String postJsonAuto(String path, String body) throws Exception { Exception last = null; for (String base : baseCandidates()) { try { String r = postJsonUrl(base + path, body); activeBaseUrl = base; prefs().edit().putString(KEY_URL, base).apply(); return r; } catch (Exception e) { last = e; } } throw last == null ? new Exception("No valid URL") : last; }

    private List<String> baseCandidates() {
        String raw = rawUrl().trim(); if (raw.isEmpty()) return new ArrayList<>();
        ArrayList<String> bases = new ArrayList<>();
        if (raw.startsWith("//")) raw = raw.substring(2);
        if (raw.startsWith("http://") || raw.startsWith("https://")) { addBase(bases, raw); try { URL u = new URL(stripSlash(raw)); String alt = (u.getProtocol().equals("https") ? "http" : "https") + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : ""); addBase(bases, alt); } catch (Exception ignored) {} }
        else { addBase(bases, "https://" + raw); addBase(bases, "http://" + raw); }
        return bases;
    }
    private void addBase(ArrayList<String> out, String s) { String b = stripSlash(s); try { URL u = new URL(b); if (u.getHost() != null && !u.getHost().trim().isEmpty() && !out.contains(b)) out.add(b); } catch (Exception ignored) {} }
    private String activeBase() { if (activeBaseUrl != null && !activeBaseUrl.isEmpty()) return activeBaseUrl; List<String> b = baseCandidates(); return b.isEmpty() ? "" : b.get(0); }
    private String rawUrl() { String s = urlInput == null ? "" : urlInput.getText().toString().trim(); if (s.isEmpty()) s = prefs().getString(KEY_URL, ""); return s == null ? "" : s.trim(); }
    private String stripSlash(String s) { if (s == null) return ""; s = s.trim(); while (s.endsWith("/")) s = s.substring(0, s.length() - 1); return s; }

    private String getTextUrl(String url) throws Exception { return new String(bytes(url, MAX_TEXT_BYTES, null), "UTF-8"); }
    private String postJsonUrl(String url, String body) throws Exception { return new String(bytes(url, MAX_TEXT_BYTES, body), "UTF-8"); }
    private byte[] bytes(String url, int maxBytes, String postBody) throws Exception { HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(); try { c.setConnectTimeout(12000); c.setReadTimeout(45000); c.setRequestProperty("User-Agent", "Mozilla/5.0 Android ComfyUI-Mobile"); c.setRequestProperty("Accept", "application/json,text/plain,*/*"); for (Map.Entry<String, String> e : authHeaders(url).entrySet()) c.setRequestProperty(e.getKey(), e.getValue()); if (postBody != null) { c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json; charset=utf-8"); OutputStream out = c.getOutputStream(); out.write(postBody.getBytes("UTF-8")); out.close(); } int code = c.getResponseCode(); InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream(); ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n, total = 0; while (in != null && (n = in.read(buf)) > 0) { total += n; if (total > maxBytes) throw new Exception("response is too large"); out.write(buf, 0, n); } if (in != null) in.close(); if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + out.toString("UTF-8")); return out.toByteArray(); } finally { c.disconnect(); } }
    private Map<String, String> authHeaders(String url) { HashMap<String, String> h = new HashMap<>(); String id = cfIdInput == null ? "" : cfIdInput.getText().toString().trim(); String secret = cfSecretInput == null ? "" : cfSecretInput.getText().toString().trim(); if (!id.isEmpty() && !secret.isEmpty()) { h.put("CF-Access-Client-Id", id); h.put("CF-Access-Client-Secret", secret); } try { String cookies = CookieManager.getInstance().getCookie(url); if (cookies != null && !cookies.trim().isEmpty()) h.put("Cookie", cookies); } catch (Exception ignored) {} return h; }

    private void loadPreview(ImageView img, TemplateItem t) { String memKey = activeBase() + "|" + t.id(); Bitmap mem = PREVIEWS.get(memKey); if (mem != null) { img.setImageBitmap(mem); return; } IO.execute(() -> { try { for (String ext : previewExts(t)) { File cached = previewFile(t, ext); if (cached.exists() && cached.length() > 0) { showPreview(img, t.id(), memKey, cached); return; } } for (String ext : previewExts(t)) { try { File f = previewFile(t, ext); downloadToFileAuto(previewPath(t, ext, false), f); showPreview(img, t.id(), memKey, f); return; } catch (Exception ignored) {} try { File f = previewFile(t, ext); downloadToFileAuto(previewPath(t, ext, true), f); showPreview(img, t.id(), memKey, f); return; } catch (Exception ignored) {} } } catch (Exception ignored) {} }); }
    private void showPreview(ImageView img, String tag, String memKey, File f) { Bitmap b = decodeBitmap(f); if (b == null) return; PREVIEWS.put(memKey, b); ui.post(() -> { Object current = img.getTag(); if (current != null && String.valueOf(current).equals(tag)) img.setImageBitmap(b); }); }
    private Bitmap decodeBitmap(File f) { try { BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeFile(f.getAbsolutePath(), bounds); if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null; BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inPreferredConfig = Bitmap.Config.RGB_565; opts.inSampleSize = 1; while ((bounds.outWidth / opts.inSampleSize) > 220 || (bounds.outHeight / opts.inSampleSize) > 180) opts.inSampleSize *= 2; return BitmapFactory.decodeFile(f.getAbsolutePath(), opts); } catch (Exception e) { return null; } }
    private void downloadToFileAuto(String path, File f) throws Exception { byte[] data = bytesAuto(path); File parent = f.getParentFile(); if (parent != null && !parent.exists()) parent.mkdirs(); FileOutputStream out = new FileOutputStream(f); out.write(data); out.flush(); out.close(); }
    private byte[] bytesAuto(String path) throws Exception { Exception last = null; for (String base : baseCandidates()) { try { byte[] b = bytes(base + path, MAX_TEXT_BYTES, null); activeBaseUrl = base; return b; } catch (Exception e) { last = e; } } throw last == null ? new Exception("No valid URL") : last; }

    private String templatePath(TemplateItem t) { return "default".equals(t.source) ? "/templates/" + encPath(t.name) + ".json" : "/api/workflow_templates/" + encPath(t.source) + "/" + encPath(t.name) + ".json"; }
    private String previewPath(TemplateItem t, String ext, boolean fallback) { return "default".equals(t.source) ? "/templates/" + encPath(t.name) + (fallback ? "" : "-1") + "." + ext : "/api/workflow_templates/" + encPath(t.source) + "/" + encPath(t.name) + "." + ext; }
    private ArrayList<String> previewExts(TemplateItem t) { LinkedHashSet<String> exts = new LinkedHashSet<>(); String s = safe(t.mediaSubtype).trim().toLowerCase(Locale.US); if (!s.isEmpty()) exts.add(s); Collections.addAll(exts, "webp", "png", "jpg", "jpeg", "gif"); return new ArrayList<>(exts); }

    private List<String> nodeIds() { ArrayList<String> ids = new ArrayList<>(); if (workflow == null) return ids; Iterator<String> it = workflow.keys(); while (it.hasNext()) ids.add(it.next()); Collections.sort(ids, (a, b) -> { try { return Integer.compare(Integer.parseInt(a.replaceAll("\\D.*", "")), Integer.parseInt(b.replaceAll("\\D.*", ""))); } catch (Exception e) { return a.compareTo(b); } }); return ids; }
    private String firstNodeId() { List<String> ids = nodeIds(); return ids.isEmpty() ? null : ids.get(0); }
    private String nodeTitle(JSONObject n) { JSONObject meta = n == null ? null : n.optJSONObject("_meta"); String t = meta == null ? "" : meta.optString("title", ""); return prettify(nonEmpty(t, n == null ? "Node" : n.optString("class_type", "Node"))); }
    private String groupOf(JSONObject n) { JSONObject meta = n == null ? null : n.optJSONObject("_meta"); String g = meta == null ? "" : meta.optString("group", meta.optString("group_name", "")); return nonEmpty(prettify(g), "General"); }
    private List<String> inputKeys(JSONObject o) { ArrayList<String> keys = new ArrayList<>(); if (o == null) return keys; Iterator<String> it = o.keys(); while (it.hasNext()) keys.add(it.next()); Collections.sort(keys); return keys; }
    private String inputKeysText(JSONObject o) { StringBuilder sb = new StringBuilder(); for (String k : inputKeys(o)) sb.append(' ').append(k); return sb.toString(); }
    private boolean primitive(Object v) { return v == JSONObject.NULL || v instanceof String || v instanceof Number || v instanceof Boolean; }
    private int primitiveCount(JSONObject o) { int n = 0; for (String k : inputKeys(o)) if (primitive(o.opt(k))) n++; return n; }
    private int linkCount(JSONObject o) { int n = 0; for (String k : inputKeys(o)) if (o.optJSONArray(k) != null) n++; return n; }
    private void expandAllGroups() { if (workflow != null) for (String id : nodeIds()) expandedGroups.add(groupOf(workflow.optJSONObject(id))); refreshNodeList(); }
    private void applyFields() { if (workflow == null) return; for (ApiField f : fields) try { JSONObject n = workflow.optJSONObject(f.nodeId); if (n != null) n.getJSONObject("inputs").put(f.key, coerce(f.edit.getText().toString())); } catch (Exception ignored) {} }
    private Object coerce(String raw) { String s = raw == null ? "" : raw.trim(); if ("true".equalsIgnoreCase(s)) return true; if ("false".equalsIgnoreCase(s)) return false; try { if (s.matches("-?\\d+")) return Long.parseLong(s); if (s.matches("-?\\d+\\.\\d+")) return Double.parseDouble(s); } catch (Exception ignored) {} return raw == null ? "" : raw; }
    private void saveWorkflow() { prefs().edit().putString(KEY_WORKFLOW, workflow == null ? "{}" : workflow.toString()).putString(KEY_OPTIONS, fieldOptions == null ? "{}" : fieldOptions.toString()).apply(); }

    private void loadTemplateCache() { SharedPreferences p = prefs(); templatesUpdatedAt = p.getLong(KEY_TEMPLATES_AT, 0); templates.clear(); try { JSONArray arr = new JSONArray(p.getString(KEY_TEMPLATES, "[]")); for (int i = 0; i < arr.length(); i++) { TemplateItem t = TemplateItem.fromJson(arr.optJSONObject(i)); if (validTemplate(t)) templates.add(t); } } catch (Exception ignored) {} }
    private void saveTemplateCache() { JSONArray arr = new JSONArray(); for (TemplateItem t : templates) try { arr.put(t.toJson()); } catch (Exception ignored) {} prefs().edit().putString(KEY_TEMPLATES, arr.toString()).putLong(KEY_TEMPLATES_AT, templatesUpdatedAt).apply(); }
    private boolean validTemplate(TemplateItem t) { return t != null && !safe(t.name).trim().isEmpty() && !safe(t.source).trim().isEmpty(); }
    private String templateHay(TemplateItem t) { return displayTitle(t) + " " + safe(t.name) + " " + safe(t.description) + " " + safe(t.category) + " " + safe(t.source); }
    private String displayTitle(TemplateItem t) { return nonEmpty(t.title, t.name); }

    private View statusChip() { String base = activeBase(); LinearLayout chip = row(); chip.setGravity(Gravity.CENTER_VERTICAL); chip.setPadding(dp(12), 0, dp(12), 0); chip.setBackground(bg(surface(), 12, stroke(), 1)); chip.addView(text(base.isEmpty() ? "○" : "●", 16, base.isEmpty() ? mutedColor() : rgb(66, 184, 93)), new LinearLayout.LayoutParams(dp(24), dp(38))); TextView label = muted(base.isEmpty() ? "Tap to set ComfyUI URL" : hostLabel(base), 12); label.setSingleLine(true); chip.addView(label, new LinearLayout.LayoutParams(0, dp(38), 1)); TextView arrow = muted("›", 20); arrow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); chip.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(38))); chip.setOnClickListener(v -> topPanel.setVisibility(topPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)); return chip; }
    private View pageHeader(String h, String sub) { LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.addView(title(h, 28)); TextView st = muted(sub, 14); st.setMaxLines(2); box.addView(st); return box; }
    private View cardTitle(String icon, String text) { LinearLayout r = row(); r.setGravity(Gravity.CENTER_VERTICAL); TextView b = badge(icon); r.addView(b, new LinearLayout.LayoutParams(dp(34), dp(34))); TextView t = title(text, 19); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.setMargins(dp(10), 0, 0, 0); r.addView(t, lp); return r; }
    private View metric(String k, String v) { LinearLayout m = card(false); m.setGravity(Gravity.CENTER); TextView a = muted(k, 11); a.setGravity(Gravity.CENTER); TextView b = title(v, 14); b.setGravity(Gravity.CENTER); m.addView(a); m.addView(b); return m; }
    private TextView badge(String s) { TextView v = text(s, 14, accent()); v.setGravity(Gravity.CENTER); v.setBackground(bg(Color.rgb(37, 31, 22), 12, stroke(), 1)); return v; }
    private TextView chip(String s) { TextView v = muted(s, 12); v.setGravity(Gravity.CENTER); v.setSingleLine(true); v.setBackground(bg(surface2(), 14, stroke(), 1)); return v; }
    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); return r; }
    private LinearLayout card(boolean accentBorder) { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(12), dp(12), dp(12), dp(12)); c.setBackground(bg(surface(), 16, accentBorder ? accent() : stroke(), 1)); return c; }
    private EditText input(String hint, boolean single) { EditText e = new EditText(this); e.setHint(hint); e.setSingleLine(single); e.setTextColor(Color.WHITE); e.setHintTextColor(mutedColor()); e.setTextSize(14); e.setPadding(dp(12), 0, dp(12), 0); e.setBackground(bg(surface2(), 12, stroke(), 1)); return e; }
    private Button button(String label, boolean primary, Runnable run) { Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setSingleLine(true); b.setTextSize(13); b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); b.setTextColor(primary ? accent() : Color.WHITE); b.setPadding(dp(6), 0, dp(6), 0); b.setBackground(bg(primary ? Color.rgb(44, 35, 25) : surface2(), 12, primary ? accent() : stroke(), 1)); b.setOnClickListener(v -> run.run()); return b; }
    private View navItem(String icon, String label, String target) { boolean selected = target.equals(screen); LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER); box.setPadding(0, dp(4), 0, dp(4)); box.setBackground(selected ? bg(Color.rgb(37,31,22), 12, accent(), 1) : bg(Color.TRANSPARENT, 12, Color.TRANSPARENT, 0)); TextView i = text(icon, 19, selected ? accent() : mutedColor()); i.setGravity(Gravity.CENTER); box.addView(i, new LinearLayout.LayoutParams(-1, dp(24))); TextView l = text(label, 10, selected ? accent() : mutedColor()); l.setGravity(Gravity.CENTER); l.setSingleLine(true); box.addView(l, new LinearLayout.LayoutParams(-1, dp(20))); box.setOnClickListener(v -> { screen = target; render(); }); return box; }
    private void renderBottomNav() { bottomNav.removeAllViews(); bottomNav.addView(navItem("⊞", "Create", "create"), weight(58)); bottomNav.addView(navItem("⌘", "Nodes", "nodes"), weight(58)); bottomNav.addView(navItem("▦", "Templates", "templates"), weight(58)); bottomNav.addView(navItem("▷", "Run", "run"), weight(58)); bottomNav.addView(navItem("▧", "Output", "output"), weight(58)); }

    private LinearLayout.LayoutParams section() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, 0, 0, dp(12)); return p; }
    private LinearLayout.LayoutParams weight(int h) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(h), 1); p.setMargins(dp(4), 0, dp(4), 0); return p; }
    private LinearLayout.LayoutParams boxLp(int w, int h, int l, int t, int r, int b) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h < 0 ? h : dp(h)); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }
    private TextView title(String s, int sp) { TextView t = text(s, sp, Color.WHITE); t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); t.setMaxLines(3); t.setEllipsize(TextUtils.TruncateAt.END); return t; }
    private TextView label(String s) { TextView t = text(s, 13, Color.rgb(210,210,216)); t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); return t; }
    private TextView muted(String s, int sp) { return text(s, sp, mutedColor()); }
    private TextView text(String s, int sp, int c) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(c); t.setIncludeFontPadding(false); t.setPadding(dp(2), 0, dp(2), dp(5)); return t; }
    private GradientDrawable bg(int color, int radiusDp, int strokeColor, int strokeDp) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radiusDp)); d.setStroke(dp(strokeDp), strokeColor); return d; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private int rgb(int r, int g, int b) { return Color.rgb(r, g, b); }
    private int bgRoot() { return Color.rgb(18,18,19); }
    private int surface() { return Color.rgb(28,28,30); }
    private int surface2() { return Color.rgb(33,33,36); }
    private int stroke() { return Color.rgb(48,48,52); }
    private int mutedColor() { return Color.rgb(170,170,178); }
    private int accent() { return Color.rgb(218,143,60); }

    private void setStatus(String s) { if (statusText == null) { statusText = new TextView(this); statusText.setVisibility(View.GONE); } statusText.setText(s); if (templateStatus != null) templateStatus.setText(s); Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private void openUrl(String u) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); } catch (Exception e) { setStatus("Could not open URL."); } }
    private void applyBars() { Window w = getWindow(); w.setStatusBarColor(bgRoot()); w.setNavigationBarColor(bgRoot()); w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE); }
    private SharedPreferences prefs() { return getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private String enc(String s) { try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; } }
    private String encPath(String s) { return enc(s).replace("%2F", "/"); }
    private String readUriText(Uri uri) throws Exception { InputStream in = getContentResolver().openInputStream(uri); if (in == null) throw new Exception("empty file"); try { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); return out.toString("UTF-8"); } finally { in.close(); } }
    private File cacheDir(String child) { File dir = new File(getFilesDir(), "stable_cache/" + child); if (!dir.exists()) dir.mkdirs(); return dir; }
    private File rawTemplateFile(TemplateItem t) { return new File(cacheDir("raw"), sha1(activeBase() + "|" + t.id()) + ".json"); }
    private File previewFile(TemplateItem t, String ext) { return new File(cacheDir("preview"), sha1(activeBase() + "|" + t.id() + "|" + ext) + "." + ext.replaceAll("[^A-Za-z0-9]", "")); }
    private String readText(File f) { try { byte[] b = readBytes(f); return b.length == 0 ? "" : new String(b, "UTF-8"); } catch (Exception e) { return ""; } }
    private byte[] readBytes(File f) { if (f == null || !f.exists()) return new byte[0]; ByteArrayOutputStream out = new ByteArrayOutputStream(); try { FileInputStream in = new FileInputStream(f); byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); in.close(); } catch (Exception ignored) {} return out.toByteArray(); }
    private void writeText(File f, String s) { try { File parent = f.getParentFile(); if (parent != null && !parent.exists()) parent.mkdirs(); FileOutputStream out = new FileOutputStream(f); out.write(safe(s).getBytes("UTF-8")); out.flush(); out.close(); } catch (Exception ignored) {} }
    private String sha1(String s) { try { MessageDigest md = MessageDigest.getInstance("SHA-1"); byte[] d = md.digest(safe(s).getBytes("UTF-8")); StringBuilder sb = new StringBuilder(); for (byte b : d) sb.append(String.format(Locale.US, "%02x", b & 0xff)); return sb.toString(); } catch (Exception e) { return String.valueOf(safe(s).hashCode()).replace("-", "n"); } }
    private String safe(String s) { return s == null ? "" : s; }
    private String nonEmpty(String v, String fallback) { return v == null || v.trim().isEmpty() ? safe(fallback) : v.trim(); }
    private String shortText(String s, int max) { if (s == null) return ""; return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…"; }
    private String shortErr(Exception e) { String s = e == null ? "" : e.getMessage(); if (s == null || s.trim().isEmpty()) s = e == null ? "unknown error" : e.getClass().getSimpleName(); return s.length() > 200 ? s.substring(0, 200) + "…" : s; }
    private String timeAgo(long ts) { long sec = Math.max(0, (System.currentTimeMillis() - ts) / 1000); if (sec < 60) return "just now"; long min = sec / 60; if (min < 60) return min + "m ago"; long h = min / 60; if (h < 24) return h + "h ago"; return (h / 24) + "d ago"; }
    private String hostLabel(String base) { try { return new URL(base).getHost(); } catch (Exception e) { return base; } }
    private String prettify(String s) { return safe(s).replace('_', ' ').replaceAll("([a-z])([A-Z])", "$1 $2").trim(); }
}
