package com.snapsnake.comfyremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RobustTemplateBrowserActivity extends EnhancedPolishedActivity {
    private static final String PREFS = "comfyui_template_cache";
    private static final String KEY_CARDS = "template_cards_v3";
    private static final String KEY_UPDATED_AT = "template_cards_updated_at_v3";
    private static final int PAGE_SIZE = 40;
    private static final int MAX_TEXT_BYTES = 16 * 1024 * 1024;
    private static final ExecutorService IO = Executors.newFixedThreadPool(4);
    private static final LruCache<String, Bitmap> PREVIEW_MEMORY = new LruCache<>(24);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<TemplateItem> templates = new ArrayList<>();
    private LinearLayout templateList;
    private EditText search;
    private TextView templateStatus, loadedText, updatedText;
    private String filter = "";
    private int renderLimit = PAGE_SIZE;
    private long lastUpdatedAt = 0L;
    private boolean templateScreen = false;
    private boolean refreshing = false;

    private static class TemplateItem {
        String source = "default";
        String name = "";
        String title = "";
        String description = "";
        String category = "Templates";
        String mediaSubtype = "webp";
        boolean valid = true;

        String id() { return safe(source).trim() + "/" + safe(name).trim(); }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("source", safe(source));
            o.put("name", safe(name));
            o.put("title", safe(title));
            o.put("description", safe(description));
            o.put("category", safe(category));
            o.put("mediaSubtype", safe(mediaSubtype));
            o.put("valid", valid);
            return o;
        }

        static TemplateItem fromJson(JSONObject o) {
            TemplateItem item = new TemplateItem();
            item.source = o.optString("source", "default");
            item.name = o.optString("name", "");
            item.title = o.optString("title", item.name);
            item.description = o.optString("description", "");
            item.category = o.optString("category", "Templates");
            item.mediaSubtype = o.optString("mediaSubtype", "webp");
            item.valid = o.optBoolean("valid", true);
            return item;
        }

        private static String safe(String s) { return s == null ? "" : s; }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        loadCachedTemplates();
    }

    @Override protected void showTemplatesTab() {
        showTemplatesScreen();
    }

    @Override public void onBackPressed() {
        if (templateScreen) {
            leaveTemplates();
            return;
        }
        super.onBackPressed();
    }

    private void showTemplatesScreen() {
        try {
            templateScreen = true;
            callBaseQuiet("saveUrl");
            setBaseScreen("templates");
            View pane = (View) baseField("pane");
            View graph = (View) baseField("graph");
            View output = (View) baseField("output");
            View top = (View) baseField("topPanel");
            LinearLayout content = (LinearLayout) baseField("content");
            if (top != null) top.setVisibility(View.GONE);
            if (pane != null) pane.setVisibility(View.VISIBLE);
            if (graph != null) graph.setVisibility(View.GONE);
            if (output != null) output.setVisibility(View.GONE);
            if (content == null) return;
            content.removeAllViews();
            content.setBackgroundColor(bgRoot());
            content.setPadding(dp(20), dp(16), dp(20), dp(20));
            content.addView(connectionChip(), sectionParams());
            content.addView(pageHeader(), sectionParams());
            content.addView(searchCard(), sectionParams());
            templateList = new LinearLayout(this);
            templateList.setOrientation(LinearLayout.VERTICAL);
            content.addView(templateList, new LinearLayout.LayoutParams(-1, -2));
            renderBottomNav();
            if (templates.isEmpty()) loadCachedTemplates();
            if (templates.isEmpty() && !baseUrl().isEmpty()) refreshTemplates();
            refreshMeta();
            renderTemplates();
            setStatus(templates.isEmpty() ? "Templates cache is empty. Tap Refresh." : "Templates loaded from local cache.");
            callBaseQuiet("applyBars");
        } catch (Exception e) {
            setStatus("Templates failed: " + shortErr(e));
        }
    }

    private void leaveTemplates() {
        templateScreen = false;
        templateList = null;
        search = null;
        setBaseScreen("create");
        try { callBase("showCreate"); } catch (Exception ignored) {}
    }

    private View connectionChip() {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(12), 0, dp(12), 0);
        chip.setBackground(bg(surface(), 12, stroke(), 1));
        chip.addView(text("●", 16, rgb(66, 184, 93)), new LinearLayout.LayoutParams(dp(24), dp(38)));
        TextView label = muted("Connected to ComfyUI Remote", 12);
        label.setSingleLine(true);
        chip.addView(label, new LinearLayout.LayoutParams(0, dp(38), 1));
        TextView arrow = muted("›", 20);
        arrow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        chip.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(38)));
        chip.setOnClickListener(v -> callBaseQuiet("toggleTopPanel"));
        return chip;
    }

    private View pageHeader() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        head.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        texts.addView(title("Templates", 28));
        texts.addView(muted("Browse and load workflow templates", 13));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        logo.setPadding(dp(8), dp(8), dp(8), dp(8));
        logo.setBackground(bg(surface2(), 12, stroke(), 1));
        head.addView(logo, new LinearLayout.LayoutParams(dp(42), dp(42)));
        return head;
    }

    private View searchCard() {
        LinearLayout tools = card(false);
        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(dp(12), 0, dp(10), 0);
        searchBox.setBackground(bg(surface2(), 10, stroke(), 1));
        tools.addView(searchBox, new LinearLayout.LayoutParams(-1, dp(46)));
        TextView icon = muted("⌕", 22);
        icon.setGravity(Gravity.CENTER);
        searchBox.addView(icon, new LinearLayout.LayoutParams(dp(30), -1));
        search = new EditText(this);
        search.setSingleLine(true);
        search.setText(filter == null ? "" : filter);
        search.setHint("Search templates…");
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(mutedColor());
        search.setTextSize(14);
        search.setPadding(dp(8), 0, 0, 0);
        search.setBackgroundColor(Color.TRANSPARENT);
        searchBox.addView(search, new LinearLayout.LayoutParams(0, -1, 1));
        TextView filterIcon = muted("☷", 18);
        filterIcon.setGravity(Gravity.CENTER);
        searchBox.addView(filterIcon, new LinearLayout.LayoutParams(dp(32), -1));

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setPadding(0, dp(8), 0, 0);
        tools.addView(meta, new LinearLayout.LayoutParams(-1, dp(32)));
        loadedText = muted("", 12);
        loadedText.setSingleLine(true);
        meta.addView(loadedText, new LinearLayout.LayoutParams(0, -1, 1));
        updatedText = muted("", 12);
        updatedText.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        updatedText.setSingleLine(true);
        meta.addView(updatedText, new LinearLayout.LayoutParams(0, -1, 1));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, dp(42));
        ap.setMargins(0, dp(4), 0, dp(4));
        tools.addView(actions, ap);
        actions.addView(actionButton(refreshing ? "⟳ Refreshing…" : "⟳ Refresh", true, this::refreshTemplates), weight(dp(42)));
        actions.addView(actionButton("⌫ Clear", false, () -> { filter = ""; renderLimit = PAGE_SIZE; if (search != null) search.setText(""); renderTemplates(); }), weight(dp(42)));

        templateStatus = muted("Preview loading is queued to avoid UI stalls.", 12);
        templateStatus.setSingleLine(true);
        templateStatus.setEllipsize(TextUtils.TruncateAt.END);
        tools.addView(templateStatus);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { filter = String.valueOf(s == null ? "" : s); renderLimit = PAGE_SIZE; renderTemplates(); }
            public void afterTextChanged(Editable s) {}
        });
        return tools;
    }

    private void renderBottomNav() {
        try {
            LinearLayout nav = (LinearLayout) baseField("bottomNav");
            if (nav == null) return;
            nav.setVisibility(View.VISIBLE);
            nav.removeAllViews();
            nav.setBackgroundColor(surface());
            nav.addView(navItem("⊞", "Create", false, this::leaveTemplates), weight(dp(58)));
            nav.addView(navItem("⌘", "Nodes", false, () -> { templateScreen = false; callBaseQuiet("showNodes"); }), weight(dp(58)));
            nav.addView(navItem("▦", "Templates", true, this::showTemplatesScreen), weight(dp(58)));
            nav.addView(navItem("▷", "Run", false, () -> { templateScreen = false; callBaseQuiet("runWorkflow"); }), weight(dp(58)));
            nav.addView(navItem("▧", "Output", false, () -> { templateScreen = false; callBaseQuiet("openOutput"); }), weight(dp(58)));
        } catch (Exception ignored) {}
    }

    private void loadCachedTemplates() {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        lastUpdatedAt = prefs.getLong(KEY_UPDATED_AT, 0L);
        templates.clear();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_CARDS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                TemplateItem item = TemplateItem.fromJson(o);
                if (!safe(item.name).trim().isEmpty()) templates.add(item);
            }
        } catch (Exception ignored) {}
    }

    private void saveTemplateCards(ArrayList<TemplateItem> items, long updatedAt) {
        JSONArray arr = new JSONArray();
        for (TemplateItem item : items) { try { arr.put(item.toJson()); } catch (Exception ignored) {} }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CARDS, arr.toString()).putLong(KEY_UPDATED_AT, updatedAt).apply();
    }

    private void refreshTemplates() {
        if (refreshing) return;
        String base = baseUrl();
        if (base.isEmpty()) { setStatus("Enter ComfyUI URL first, then refresh templates."); return; }
        refreshing = true;
        setStatus("Refreshing templates index...");
        IO.execute(() -> {
            ArrayList<TemplateItem> loaded = new ArrayList<>();
            ArrayList<String> warnings = new ArrayList<>();
            try {
                JSONArray index = new JSONArray(getText(base + "/templates/index.json"));
                for (int i = 0; i < index.length(); i++) {
                    JSONObject category = index.optJSONObject(i);
                    if (category == null) continue;
                    String source = category.optString("moduleName", "default");
                    String categoryTitle = category.optString("localizedTitle", category.optString("title", source));
                    JSONArray arr = category.optJSONArray("templates");
                    if (arr == null) continue;
                    for (int j = 0; j < arr.length(); j++) {
                        JSONObject raw = arr.optJSONObject(j);
                        if (raw == null) continue;
                        TemplateItem item = new TemplateItem();
                        item.source = source == null || source.trim().isEmpty() ? "default" : source.trim();
                        item.name = safeOpt(raw, "name", "").trim();
                        item.title = safeOpt(raw, "localizedTitle", safeOpt(raw, "title", item.name));
                        item.description = safeOpt(raw, "localizedDescription", safeOpt(raw, "description", ""));
                        item.category = safe(categoryTitle).trim().isEmpty() ? "Templates" : categoryTitle;
                        item.mediaSubtype = safeOpt(raw, "mediaSubtype", "webp");
                        item.valid = !item.name.isEmpty();
                        if (item.valid) loaded.add(item);
                    }
                }
            } catch (Exception e) { warnings.add("default templates: " + shortErr(e)); }
            try {
                JSONObject custom = new JSONObject(getText(base + "/api/workflow_templates"));
                Iterator<String> it = custom.keys();
                while (it.hasNext()) {
                    String module = it.next();
                    JSONArray arr = custom.optJSONArray(module);
                    if (arr == null) continue;
                    for (int i = 0; i < arr.length(); i++) {
                        String name = arr.optString(i, "").trim();
                        if (name.isEmpty()) continue;
                        TemplateItem item = new TemplateItem();
                        item.source = module;
                        item.name = name;
                        item.title = name;
                        item.description = module;
                        item.category = "Custom templates";
                        item.mediaSubtype = "";
                        loaded.add(item);
                    }
                }
            } catch (Exception e) { warnings.add("custom templates: " + shortErr(e)); }
            long updatedAt = System.currentTimeMillis();
            ui.post(() -> {
                refreshing = false;
                if (!loaded.isEmpty()) {
                    templates.clear();
                    templates.addAll(loaded);
                    lastUpdatedAt = updatedAt;
                    saveTemplateCards(loaded, updatedAt);
                    renderLimit = PAGE_SIZE;
                    refreshMeta();
                    renderTemplates();
                    setStatus("Loaded " + loaded.size() + " templates." + joinWarnings(warnings));
                    preloadTemplateJsons(base, new ArrayList<>(loaded));
                } else {
                    renderTemplates();
                    setStatus("Refresh failed; kept local cache." + joinWarnings(warnings));
                }
            });
        });
    }

    private void preloadTemplateJsons(String base, ArrayList<TemplateItem> items) {
        IO.execute(() -> {
            int ok = 0, failed = 0;
            for (int i = 0; i < items.size(); i++) {
                TemplateItem item = items.get(i);
                try {
                    String raw = getText(templateJsonUrl(base, item));
                    writeText(rawTemplateFile(base, item), raw);
                    ok++;
                } catch (Exception e) { failed++; }
                if (i % 25 == 0) {
                    int done = i + 1, count = ok;
                    ui.post(() -> setStatus("Caching workflows: " + done + "/" + items.size() + " ready " + count + "."));
                }
            }
            int finalOk = ok, finalFailed = failed;
            ui.post(() -> setStatus("Templates ready. Cached workflows: " + finalOk + (finalFailed > 0 ? ", failed: " + finalFailed + "." : ".")));
        });
    }

    private void renderTemplates() {
        if (templateList == null) return;
        templateList.removeAllViews();
        String q = safe(filter).trim().toLowerCase(Locale.US);
        ArrayList<TemplateItem> matches = new ArrayList<>();
        for (TemplateItem item : templates) if (q.isEmpty() || metadataText(item).toLowerCase(Locale.US).contains(q)) matches.add(item);
        int max = Math.min(renderLimit, matches.size());
        for (int i = 0; i < max; i++) templateList.addView(templateCard(matches.get(i)), sectionParams());
        if (matches.isEmpty()) templateList.addView(muted(templates.isEmpty() ? "No templates cached yet. Tap Refresh." : "Nothing found.", 14));
        if (matches.size() > max) {
            LinearLayout more = card(false);
            more.setGravity(Gravity.CENTER_HORIZONTAL);
            more.addView(muted("Showing " + max + " of " + matches.size() + " templates.", 13));
            more.addView(actionButton("Show more", false, () -> { renderLimit += PAGE_SIZE; renderTemplates(); }), new LinearLayout.LayoutParams(-1, dp(40)));
            templateList.addView(more, sectionParams());
        }
    }

    private View templateCard(TemplateItem item) {
        LinearLayout row = card(false);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(10), dp(12));
        row.setClickable(true);
        row.setOnClickListener(v -> openTemplate(item));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setImageResource(R.drawable.ic_launcher);
        img.setBackground(bg(surface2(), 8, stroke(), 1));
        img.setTag(item.id());
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(106), dp(82));
        ip.setMargins(0, 0, dp(12), 0);
        row.addView(img, ip);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(body, new LinearLayout.LayoutParams(0, dp(82), 1));
        TextView name = title(displayTitle(item), 16);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(name);
        String desc = safe(item.description).trim();
        TextView sub = muted(shortText(desc.isEmpty() ? safe(item.category) : desc.replace('_', ' '), 130), 12);
        sub.setMaxLines(2);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(sub);
        TextView arrow = muted("›", 28);
        arrow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(22), dp(82)));
        loadPreview(img, item);
        return row;
    }

    private void openTemplate(TemplateItem item) {
        String base = baseUrl();
        if (base.isEmpty()) { setStatus("Enter ComfyUI URL first, then open a template."); return; }
        setStatus("Opening template: " + displayTitle(item));
        IO.execute(() -> {
            try {
                String raw = readText(rawTemplateFile(base, item));
                if (raw.trim().isEmpty()) {
                    raw = getText(templateJsonUrl(base, item));
                    writeText(rawTemplateFile(base, item), raw);
                }
                JSONObject graph = new JSONObject(raw);
                JSONObject defs = new JSONObject(getText(base + "/api/object_info"));
                JSONObject prompt = toApiPrompt(graph, defs);
                JSONObject res = new JSONObject();
                res.put("ok", true);
                res.put("prompt", prompt);
                res.put("options", buildOptions(prompt, defs));
                res.put("mode", "Templates");
                ui.post(() -> importPrompt(res.toString()));
            } catch (Exception e) {
                ui.post(() -> setStatus("Template open failed: " + shortErr(e)));
            }
        });
    }

    private JSONObject toApiPrompt(JSONObject graph, JSONObject defs) throws JSONException {
        JSONObject wf = frontendWorkflow(graph);
        JSONObject extra = graph.optJSONObject("extra");
        JSONObject p = extra == null ? null : extra.optJSONObject("prompt");
        if (looksApiPrompt(p)) return mergeGroupMeta(p, wf);
        if (p != null && p.optJSONArray("nodes") != null) return convertFrontendWorkflow(p, defs);
        p = graph.optJSONObject("prompt");
        if (looksApiPrompt(p)) return mergeGroupMeta(p, wf);
        if (p != null && p.optJSONArray("nodes") != null) return convertFrontendWorkflow(p, defs);
        JSONObject w = graph.optJSONObject("workflow");
        if (looksApiPrompt(w)) return mergeGroupMeta(w, wf);
        if (w != null && w.optJSONArray("nodes") != null) return convertFrontendWorkflow(w, defs);
        if (looksApiPrompt(graph)) return mergeGroupMeta(graph, wf);
        JSONObject converted = convertFrontendWorkflow(graph, defs);
        if (converted.length() > 0) return converted;
        throw new JSONException("template has no API prompt and no convertible frontend workflow");
    }

    private JSONObject mergeGroupMeta(JSONObject prompt, JSONObject wf) throws JSONException {
        if (prompt == null || wf == null) return prompt == null ? new JSONObject() : prompt;
        JSONArray nodes = wf.optJSONArray("nodes");
        if (nodes == null) return prompt;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n == null) continue;
            String id = n.optString("id", "");
            JSONObject pNode = prompt.optJSONObject(id);
            if (pNode == null) continue;
            JSONObject meta = pNode.optJSONObject("_meta");
            if (meta == null) meta = new JSONObject();
            if (!meta.has("title")) meta.put("title", n.optString("title", pNode.optString("class_type", "Node")));
            String group = groupForNode(wf, n);
            if (!group.isEmpty()) meta.put("group", group);
            pNode.put("_meta", meta);
        }
        return prompt;
    }

    private JSONObject convertFrontendWorkflow(JSONObject raw, JSONObject defs) throws JSONException {
        JSONObject wf = frontendWorkflow(raw);
        JSONArray nodes = wf == null ? null : wf.optJSONArray("nodes");
        if (nodes == null || nodes.length() == 0) return new JSONObject();
        Object links = wf.opt("links");
        JSONObject out = new JSONObject();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n == null) continue;
            String id = n.optString("id", "");
            String cls = n.optString("type", n.optString("class_type", ""));
            if (id.trim().isEmpty() || cls.trim().isEmpty()) continue;
            String low = cls.toLowerCase(Locale.US);
            if (low.contains("note") || low.contains("markdown") || low.contains("reroute")) continue;
            JSONObject item = new JSONObject();
            item.put("class_type", cls);
            JSONObject inputs = new JSONObject();
            addLinkedInputs(inputs, n.optJSONArray("inputs"), links);
            addNamedWidgetInputs(inputs, n.optJSONArray("widgets"));
            addWidgetValueInputs(inputs, cls, n.optJSONArray("widgets_values"), defs);
            forceLoadImageInput(inputs, cls, n.optJSONArray("widgets_values"));
            item.put("inputs", inputs);
            JSONObject meta = new JSONObject();
            meta.put("title", n.optString("title", cls));
            String group = groupForNode(wf, n);
            if (!group.isEmpty()) meta.put("group", group);
            item.put("_meta", meta);
            out.put(id, item);
        }
        return out;
    }

    private JSONObject frontendWorkflow(JSONObject raw) {
        if (raw == null) return null;
        if (raw.optJSONArray("nodes") != null) return raw;
        JSONObject wf = raw.optJSONObject("workflow");
        if (wf != null && wf.optJSONArray("nodes") != null) return wf;
        JSONObject extra = raw.optJSONObject("extra");
        wf = extra == null ? null : extra.optJSONObject("workflow");
        if (wf != null && wf.optJSONArray("nodes") != null) return wf;
        JSONObject prompt = raw.optJSONObject("prompt");
        if (prompt != null && prompt.optJSONArray("nodes") != null) return prompt;
        return null;
    }

    private void addLinkedInputs(JSONObject inputs, JSONArray inArr, Object links) throws JSONException {
        if (inArr == null) return;
        for (int i = 0; i < inArr.length(); i++) {
            JSONObject inp = inArr.optJSONObject(i);
            if (inp == null) continue;
            String name = inp.optString("name", "");
            if (name.isEmpty() || !inp.has("link") || inp.isNull("link")) continue;
            JSONArray origin = linkOrigin(links, inp.opt("link"));
            if (origin != null) inputs.put(name, origin);
        }
    }

    private JSONArray linkOrigin(Object links, Object id) throws JSONException {
        if (!(links instanceof JSONArray)) return null;
        JSONArray arr = (JSONArray) links;
        for (int i = 0; i < arr.length(); i++) {
            Object raw = arr.opt(i);
            if (raw instanceof JSONArray) {
                JSONArray l = (JSONArray) raw;
                if (sameId(l.opt(0), id)) return new JSONArray().put(String.valueOf(l.opt(1))).put(l.optInt(2, 0));
            } else if (raw instanceof JSONObject) {
                JSONObject l = (JSONObject) raw;
                if (sameId(l.opt("id"), id)) return new JSONArray().put(String.valueOf(l.opt("origin_id"))).put(l.optInt("origin_slot", 0));
            }
        }
        return null;
    }

    private boolean sameId(Object a, Object b) { return String.valueOf(a).equals(String.valueOf(b)); }

    private void addNamedWidgetInputs(JSONObject inputs, JSONArray widgets) throws JSONException {
        if (widgets == null) return;
        for (int i = 0; i < widgets.length(); i++) {
            JSONObject w = widgets.optJSONObject(i);
            if (w == null) continue;
            String name = w.optString("name", "");
            if (name.isEmpty() || "upload".equals(name) || "button".equalsIgnoreCase(w.optString("type", ""))) continue;
            Object value = w.opt("value");
            if (primitive(value) && !inputs.has(name)) inputs.put(name, value);
        }
    }

    private void addWidgetValueInputs(JSONObject inputs, String cls, JSONArray values, JSONObject defs) throws JSONException {
        if (values == null || values.length() == 0) return;
        ArrayList<String> names = widgetInputNamesForClass(cls, defs);
        int vi = 0;
        for (String name : names) {
            if (vi >= values.length()) break;
            if (inputs.has(name)) continue;
            Object value = values.opt(vi++);
            if (primitive(value)) inputs.put(name, value);
        }
    }

    private ArrayList<String> widgetInputNamesForClass(String cls, JSONObject defs) {
        ArrayList<String> names = new ArrayList<>();
        JSONObject def = defs == null ? null : defs.optJSONObject(cls);
        JSONObject input = def == null ? null : def.optJSONObject("input");
        addWidgetInputNames(names, input == null ? null : input.optJSONObject("required"));
        addWidgetInputNames(names, input == null ? null : input.optJSONObject("optional"));
        return names;
    }

    private void addWidgetInputNames(ArrayList<String> names, JSONObject section) {
        if (section == null) return;
        Iterator<String> it = section.keys();
        while (it.hasNext()) {
            String key = it.next();
            Object raw = section.opt(key);
            if (!(raw instanceof JSONArray)) continue;
            JSONArray spec = (JSONArray) raw;
            Object first = spec.opt(0);
            if (first instanceof JSONArray || isPrimitiveInputType(String.valueOf(first))) names.add(key);
        }
    }

    private boolean isPrimitiveInputType(String t) {
        String s = t == null ? "" : t.toUpperCase(Locale.US);
        return "INT".equals(s) || "FLOAT".equals(s) || "STRING".equals(s) || "BOOLEAN".equals(s);
    }

    private void forceLoadImageInput(JSONObject inputs, String cls, JSONArray values) throws JSONException {
        if (inputs.has("image") || values == null || values.length() == 0) return;
        String s = cls == null ? "" : cls.toLowerCase(Locale.US).replace("_", "");
        if (!s.contains("loadimage")) return;
        for (int i = 0; i < values.length(); i++) {
            Object v = values.opt(i);
            if (primitive(v) && String.valueOf(v).trim().length() > 0) { inputs.put("image", v); return; }
        }
    }

    private boolean primitive(Object v) { return v == JSONObject.NULL || v instanceof String || v instanceof Number || v instanceof Boolean; }

    private boolean looksApiPrompt(JSONObject o) {
        if (o == null) return false;
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            JSONObject n = o.optJSONObject(it.next());
            if (n != null && n.has("class_type")) return true;
        }
        return false;
    }

    private String groupForNode(JSONObject wf, JSONObject node) {
        String direct = node.optString("group", "");
        if (!direct.trim().isEmpty()) return direct.trim();
        JSONArray pos = node.optJSONArray("pos");
        JSONArray groups = wf == null ? null : wf.optJSONArray("groups");
        if (pos == null || pos.length() < 2 || groups == null) return "";
        double x = pos.optDouble(0, 0), y = pos.optDouble(1, 0);
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g == null) continue;
            JSONArray b = g.optJSONArray("bounding");
            if (b == null) b = g.optJSONArray("_bounding");
            if (b == null || b.length() < 4) continue;
            double gx = b.optDouble(0), gy = b.optDouble(1), gw = b.optDouble(2), gh = b.optDouble(3);
            if (x >= gx && y >= gy && x <= gx + gw && y <= gy + gh) return g.optString("title", g.optString("name", "Group"));
        }
        return "";
    }

    private JSONObject buildOptions(JSONObject prompt, JSONObject defs) throws JSONException {
        JSONObject options = new JSONObject();
        Iterator<String> ids = prompt.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            JSONObject node = prompt.optJSONObject(id);
            if (node == null) continue;
            JSONObject def = defs.optJSONObject(node.optString("class_type", ""));
            JSONObject input = def == null ? null : def.optJSONObject("input");
            addOptions(options, id, input == null ? null : input.optJSONObject("required"));
            addOptions(options, id, input == null ? null : input.optJSONObject("optional"));
        }
        return options;
    }

    private void addOptions(JSONObject out, String id, JSONObject section) throws JSONException {
        if (section == null) return;
        Iterator<String> it = section.keys();
        while (it.hasNext()) {
            String key = it.next();
            Object raw = section.opt(key);
            if (raw instanceof JSONArray) {
                Object first = ((JSONArray) raw).opt(0);
                if (first instanceof JSONArray) out.put(id + ":" + key, first);
            }
        }
    }

    private void loadPreview(ImageView img, TemplateItem item) {
        String base = baseUrl();
        String tag = item.id();
        img.setTag(tag);
        String memKey = base + "|" + tag;
        Bitmap cachedBitmap = PREVIEW_MEMORY.get(memKey);
        if (cachedBitmap != null) { img.setImageBitmap(cachedBitmap); return; }
        IO.execute(() -> {
            try {
                for (String ext : previewExtensions(item)) {
                    File cached = previewFile(base, item, ext);
                    if (cached.exists() && cached.length() > 0) { showPreviewFile(img, tag, memKey, cached); return; }
                }
                if (base.isEmpty()) return;
                for (String ext : previewExtensions(item)) {
                    try { File target = previewFile(base, item, ext); downloadToFile(previewUrl(base, item, ext, false), target); showPreviewFile(img, tag, memKey, target); return; } catch (Exception ignored) {}
                    try { File target = previewFile(base, item, ext); downloadToFile(previewUrl(base, item, ext, true), target); showPreviewFile(img, tag, memKey, target); return; } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        });
    }

    private ArrayList<String> previewExtensions(TemplateItem item) {
        LinkedHashSet<String> exts = new LinkedHashSet<>();
        String s = safe(item.mediaSubtype).trim().toLowerCase(Locale.US);
        if (!s.isEmpty()) exts.add(s);
        exts.add("webp"); exts.add("png"); exts.add("jpg"); exts.add("jpeg"); exts.add("gif");
        return new ArrayList<>(exts);
    }

    private void showPreviewFile(ImageView img, String tag, String memKey, File file) {
        Bitmap bitmap = decodePreviewBitmap(file, dp(106), dp(82));
        if (bitmap == null) return;
        PREVIEW_MEMORY.put(memKey, bitmap);
        ui.post(() -> {
            Object current = img.getTag();
            if (current != null && String.valueOf(current).equals(tag)) img.setImageBitmap(bitmap);
        });
    }

    private Bitmap decodePreviewBitmap(File file, int targetW, int targetH) {
        if (file == null || !file.exists() || file.length() <= 0) return null;
        try {
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), b);
            if (b.outWidth > 0 && b.outHeight > 0) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                o.inSampleSize = sampleSize(b.outWidth, b.outHeight, targetW, targetH);
                return BitmapFactory.decodeFile(file.getAbsolutePath(), o);
            }
        } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), (decoder, info, src) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    Size s = info.getSize();
                    int sample = sampleSize(s.getWidth(), s.getHeight(), targetW, targetH);
                    decoder.setTargetSize(Math.max(1, s.getWidth() / sample), Math.max(1, s.getHeight() / sample));
                });
            } catch (Exception ignored) {}
        }
        return null;
    }

    private int sampleSize(int w, int h, int tw, int th) { int sample = 1; while ((w / sample) > tw * 2 || (h / sample) > th * 2) sample *= 2; return Math.max(1, sample); }
    private String templateJsonUrl(String base, TemplateItem item) { if ("default".equals(item.source)) return base + "/templates/" + path(item.name) + ".json"; return base + "/api/workflow_templates/" + path(item.source) + "/" + path(item.name) + ".json"; }
    private String previewUrl(String base, TemplateItem item, String ext, boolean fallback) { if ("default".equals(item.source)) return base + "/templates/" + path(item.name) + (fallback ? "" : "-1") + "." + ext; return base + "/api/workflow_templates/" + path(item.source) + "/" + path(item.name) + "." + ext; }
    private String getText(String url) throws Exception { return new String(bytes(url, MAX_TEXT_BYTES), "UTF-8"); }

    private byte[] bytes(String url, int maxBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000); c.setReadTimeout(30000);
            for (Map.Entry<String, String> e : requestHeaders(url).entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n, total = 0;
            while (in != null && (n = in.read(b)) > 0) { total += n; if (total > maxBytes) throw new Exception("response is too large"); out.write(b, 0, n); }
            if (in != null) in.close();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + out.toString("UTF-8"));
            return out.toByteArray();
        } finally { c.disconnect(); }
    }

    private void downloadToFile(String url, File file) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        File tmp = new File(file.getAbsolutePath() + ".tmp");
        try {
            c.setConnectTimeout(10000); c.setReadTimeout(45000);
            for (Map.Entry<String, String> e : requestHeaders(url).entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            File parent = file.getParentFile(); if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream out = new FileOutputStream(tmp);
            byte[] b = new byte[16384]; int n;
            while (in != null && (n = in.read(b)) > 0) out.write(b, 0, n);
            if (in != null) in.close(); out.flush(); out.close();
            if (file.exists()) file.delete();
            if (!tmp.renameTo(file)) { writeBytes(file, readBytes(tmp)); tmp.delete(); }
        } finally {
            c.disconnect();
            if (tmp.exists() && (!file.exists() || file.length() == 0)) tmp.delete();
        }
    }

    @SuppressWarnings("unchecked") private Map<String, String> requestHeaders(String url) throws Exception {
        Method m = EnhancedPolishedActivity.class.getDeclaredMethod("accessHeaders");
        m.setAccessible(true);
        Map<String, String> h = (Map<String, String>) m.invoke(this);
        try {
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null && !cookies.trim().isEmpty()) h.put("Coo" + "kie", cookies);
        } catch (Exception ignored) {}
        return h;
    }

    private File cacheDir(String child) { File dir = new File(getFilesDir(), "template_cache/" + child); if (!dir.exists()) dir.mkdirs(); return dir; }
    private File rawTemplateFile(String base, TemplateItem item) { return new File(cacheDir("raw"), sha1(base + "|" + item.id()) + ".json"); }
    private File previewFile(String base, TemplateItem item, String ext) { return new File(cacheDir("preview"), sha1(base + "|" + item.id() + "|" + ext) + "." + ext.replaceAll("[^A-Za-z0-9]", "")); }
    private String readText(File file) { try { byte[] data = readBytes(file); return data.length == 0 ? "" : new String(data, "UTF-8"); } catch (Exception e) { return ""; } }
    private byte[] readBytes(File file) { if (file == null || !file.exists() || !file.isFile()) return new byte[0]; ByteArrayOutputStream out = new ByteArrayOutputStream(); try { FileInputStream in = new FileInputStream(file); byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); in.close(); return out.toByteArray(); } catch (Exception e) { return new byte[0]; } }
    private void writeText(File file, String text) { try { writeBytes(file, safe(text).getBytes("UTF-8")); } catch (Exception ignored) {} }
    private void writeBytes(File file, byte[] data) { if (file == null || data == null || data.length == 0) return; try { File parent = file.getParentFile(); if (parent != null && !parent.exists()) parent.mkdirs(); FileOutputStream out = new FileOutputStream(file); out.write(data); out.flush(); out.close(); } catch (Exception ignored) {} }
    private void importPrompt(String resultJson) { try { templateScreen = false; setBaseScreen("create"); Method m = PolishedNodeActivity.class.getDeclaredMethod("handleImportJson", String.class); m.setAccessible(true); m.invoke(this, resultJson); } catch (Exception e) { setStatus("Import failed: " + shortErr(e)); } }
    private String baseUrl() { Object x = callBaseQuiet("baseUrl"); return x == null ? "" : String.valueOf(x); }
    private Object baseField(String name) throws Exception { Field f = PolishedNodeActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(this); }
    private Object callBase(String name) throws Exception { Method m = PolishedNodeActivity.class.getDeclaredMethod(name); m.setAccessible(true); return m.invoke(this); }
    private Object callBaseQuiet(String name) { try { return callBase(name); } catch (Exception e) { return null; } }
    private void setBaseScreen(String value) { try { Field f = PolishedNodeActivity.class.getDeclaredField("screen"); f.setAccessible(true); f.set(this, value); } catch (Exception ignored) {} }
    private void refreshMeta() { if (loadedText != null) { loadedText.setText((templates.isEmpty() ? "○" : "●") + " Loaded " + templates.size() + " template" + (templates.size() == 1 ? "" : "s")); loadedText.setTextColor(templates.isEmpty() ? mutedColor() : accent()); } if (updatedText != null) updatedText.setText(lastUpdatedAt > 0 ? "Updated " + timeAgo(lastUpdatedAt) : "Not refreshed yet"); }
    private void setStatus(String s) { Object x = null; try { x = baseField("status"); } catch (Exception ignored) {} if (x instanceof TextView) ((TextView) x).setText(s); if (templateStatus != null) templateStatus.setText(s); }
    private String path(String s) { try { return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20").replace("%2F", "/"); } catch (Exception e) { return s == null ? "" : s; } }
    private String safe(String s) { return s == null ? "" : s; }
    private String safeOpt(JSONObject o, String key, String fallback) { return o == null ? safe(fallback) : o.optString(key, safe(fallback)); }
    private String shortText(String s, int max) { if (s == null) return ""; return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…"; }
    private String shortErr(Exception e) { String s = e == null ? "" : e.getMessage(); if (s == null || s.trim().isEmpty()) s = e == null ? "unknown error" : e.getClass().getSimpleName(); return s.length() > 220 ? s.substring(0, 220) + "…" : s; }
    private String displayTitle(TemplateItem item) { String t = safe(item.title).trim(); return t.isEmpty() ? safe(item.name).trim() : t; }
    private String metadataText(TemplateItem item) { return displayTitle(item) + " " + safe(item.name) + " " + safe(item.description) + " " + safe(item.category) + " " + safe(item.source); }
    private String joinWarnings(ArrayList<String> warnings) { if (warnings == null || warnings.isEmpty()) return ""; StringBuilder sb = new StringBuilder(" Warning: "); for (int i = 0; i < warnings.size(); i++) { if (i > 0) sb.append("; "); sb.append(warnings.get(i)); } return sb.toString(); }
    private String timeAgo(long ts) { long sec = Math.max(0L, (System.currentTimeMillis() - ts) / 1000L); if (sec < 60) return "just now"; long min = sec / 60L; if (min < 60) return min + "m ago"; long h = min / 60L; if (h < 24) return h + "h ago"; return (h / 24L) + "d ago"; }
    private String sha1(String value) { try { MessageDigest md = MessageDigest.getInstance("SHA-1"); byte[] d = md.digest(safe(value).getBytes("UTF-8")); StringBuilder sb = new StringBuilder(); for (byte b : d) sb.append(String.format(Locale.US, "%02x", b & 0xff)); return sb.toString(); } catch (Exception e) { return String.valueOf(safe(value).hashCode()).replace("-", "n"); } }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private int rgb(int r, int g, int b) { return Color.rgb(r, g, b); }
    private int bgRoot() { return Color.rgb(18,18,19); }
    private int surface() { return Color.rgb(28,28,30); }
    private int surface2() { return Color.rgb(33,33,36); }
    private int stroke() { return Color.rgb(48,48,52); }
    private int mutedColor() { return Color.rgb(170,170,178); }
    private int accent() { return Color.rgb(218,143,60); }
    private LinearLayout card(boolean accentBorder) { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(12), dp(12), dp(12), dp(12)); c.setBackground(bg(surface(), 16, accentBorder ? accent() : stroke(), 1)); return c; }
    private LinearLayout.LayoutParams sectionParams() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, 0, 0, dp(12)); return p; }
    private LinearLayout.LayoutParams weight(int h) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, h, 1); p.setMargins(dp(4), 0, dp(4), 0); return p; }
    private TextView title(String t, int size) { TextView v = text(t, size, Color.WHITE); v.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); v.setIncludeFontPadding(false); return v; }
    private TextView muted(String t, int size) { return text(t, size, mutedColor()); }
    private TextView text(String t, int size, int color) { TextView v = new TextView(this); v.setText(t); v.setTextColor(color); v.setTextSize(size); v.setIncludeFontPadding(false); v.setPadding(dp(2), 0, dp(2), dp(5)); return v; }
    private Button actionButton(String t, boolean primary, Runnable action) { Button b = new Button(this); b.setText(t); b.setAllCaps(false); b.setSingleLine(true); b.setTextColor(primary ? accent() : Color.WHITE); b.setTextSize(13); b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); b.setPadding(dp(6), 0, dp(6), 0); b.setBackground(bg(primary ? Color.rgb(44,35,25) : surface2(), 12, primary ? accent() : stroke(), 1)); b.setOnClickListener(v -> action.run()); return b; }
    private View navItem(String icon, String label, boolean selected, Runnable action) { LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER); box.setPadding(0, dp(4), 0, dp(4)); box.setBackground(selected ? bg(Color.rgb(37,31,22), 12, accent(), 1) : bg(Color.TRANSPARENT, 12, Color.TRANSPARENT, 0)); TextView i = text(icon, 19, selected ? accent() : mutedColor()); i.setGravity(Gravity.CENTER); box.addView(i, new LinearLayout.LayoutParams(-1, dp(24))); TextView l = text(label, 10, selected ? accent() : mutedColor()); l.setGravity(Gravity.CENTER); l.setSingleLine(true); box.addView(l, new LinearLayout.LayoutParams(-1, dp(20))); box.setOnClickListener(v -> action.run()); return box; }
    private GradientDrawable bg(int color, int radiusDp, int stroke, int strokeDp) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radiusDp)); d.setStroke(dp(strokeDp), stroke); return d; }
}
