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
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.webkit.WebView;

import org.json.JSONArray;
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
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StableComfyMobileActivity extends EnhancedPolishedActivity {
    private static final String REMOTE_PREFS = "comfyui_remote_prefs";
    private static final String KEY_URL = "comfyui_url";
    private static final String TEMPLATE_PREFS = "comfyui_template_cache";
    private static final String KEY_CARDS = "template_cards_stable_v1";
    private static final String KEY_UPDATED = "template_cards_stable_updated_v1";
    private static final int REQ_JSON = 44;
    private static final int PAGE_SIZE = 40;
    private static final int MAX_TEXT_BYTES = 16 * 1024 * 1024;
    private static final ExecutorService IO = Executors.newFixedThreadPool(4);
    private static final LruCache<String, Bitmap> PREVIEW_CACHE = new LruCache<>(32);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<TemplateItem> templates = new ArrayList<>();
    private LinearLayout templateList;
    private EditText searchInput;
    private TextView templateStatus, loadedText, updatedText;
    private boolean templateScreen = false;
    private boolean refreshing = false;
    private String filter = "";
    private int renderLimit = PAGE_SIZE;
    private long updatedAt = 0L;

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
            o.put("source", safe(source)); o.put("name", safe(name)); o.put("title", safe(title));
            o.put("description", safe(description)); o.put("category", safe(category)); o.put("mediaSubtype", safe(mediaSubtype));
            return o;
        }
        static TemplateItem fromJson(JSONObject o) {
            TemplateItem t = new TemplateItem();
            t.source = o.optString("source", "default"); t.name = o.optString("name", "");
            t.title = o.optString("title", t.name); t.description = o.optString("description", "");
            t.category = o.optString("category", "Templates"); t.mediaSubtype = o.optString("mediaSubtype", "webp");
            return t;
        }
        static String safe(String s) { return s == null ? "" : s; }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        loadCachedTemplates();
    }

    @Override protected void showTemplatesTab() {
        showTemplatesScreen();
    }

    @Override public void onBackPressed() {
        if (templateScreen) { goCreate(); return; }
        super.onBackPressed();
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        if (req == REQ_JSON) {
            if (result == Activity.RESULT_OK && data != null && data.getData() != null) {
                try { importWorkflowJson(readUriText(data.getData())); }
                catch (Exception e) { setStatus("Could not read JSON: " + shortErr(e)); }
            }
            callBase("applyBars");
            return;
        }
        super.onActivityResult(req, result, data);
    }

    private void showTemplatesScreen() {
        String base = stableBaseUrl();
        try {
            templateScreen = true;
            setBaseScreen("templates");
            hideWebViewsShowPane();
            LinearLayout content = (LinearLayout) baseField("content");
            View top = (View) baseField("topPanel");
            if (top != null) top.setVisibility(View.GONE);
            if (content == null) return;
            content.removeAllViews();
            content.setPadding(dp(20), dp(16), dp(20), dp(20));
            content.setBackgroundColor(bgRoot());
            content.addView(connectionChip(base), sectionParams());
            content.addView(header(), sectionParams());
            content.addView(searchPanel(), sectionParams());
            templateList = new LinearLayout(this);
            templateList.setOrientation(LinearLayout.VERTICAL);
            content.addView(templateList, new LinearLayout.LayoutParams(-1, -2));
            renderBottomNav();
            if (templates.isEmpty()) loadCachedTemplates();
            if (templates.isEmpty() && !base.isEmpty()) refreshTemplates();
            refreshMeta();
            renderTemplates();
            setStatus(base.isEmpty() ? "Set ComfyUI URL first." : "Templates loaded from local cache.");
        } catch (Exception e) {
            setStatus("Templates failed: " + shortErr(e));
        }
    }

    private void goCreate() {
        templateScreen = false;
        templateList = null;
        searchInput = null;
        setBaseScreen("create");
        callBase("showCreate");
        hideWebViewsShowPane();
    }

    private void hideWebViewsShowPane() {
        try {
            ScrollView pane = (ScrollView) baseField("pane");
            WebView graph = (WebView) baseField("graph");
            WebView output = (WebView) baseField("output");
            View workspace = (View) baseField("workspace");
            if (graph != null) graph.setVisibility(View.GONE);
            if (output != null) output.setVisibility(View.GONE);
            if (pane != null) { pane.setVisibility(View.VISIBLE); pane.bringToFront(); }
            if (workspace != null) workspace.setVisibility(View.VISIBLE);
        } catch (Exception ignored) {}
    }

    private View connectionChip(String base) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(12), 0, dp(12), 0);
        chip.setBackground(bg(surface(), 12, stroke(), 1));
        chip.addView(text(base.isEmpty() ? "○" : "●", 16, base.isEmpty() ? mutedColor() : rgb(66, 184, 93)), new LinearLayout.LayoutParams(dp(24), dp(38)));
        TextView label = muted(base.isEmpty() ? "ComfyUI URL is not set" : hostLabel(base), 12);
        label.setSingleLine(true);
        chip.addView(label, new LinearLayout.LayoutParams(0, dp(38), 1));
        TextView arrow = muted("›", 20);
        arrow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        chip.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(38)));
        chip.setOnClickListener(v -> callBase("toggleTopPanel"));
        return chip;
    }

    private View header() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        head.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        copy.addView(title("Templates", 28));
        copy.addView(muted("Browse and load workflow templates", 13));
        ImageView logo = new ImageView(this);
        logo.setImageResource(com.snapsnake.comfyremote.R.drawable.ic_launcher);
        logo.setPadding(dp(8), dp(8), dp(8), dp(8));
        logo.setBackground(bg(surface2(), 12, stroke(), 1));
        head.addView(logo, new LinearLayout.LayoutParams(dp(42), dp(42)));
        return head;
    }

    private View searchPanel() {
        LinearLayout tools = card(false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(12), 0, dp(10), 0);
        box.setBackground(bg(surface2(), 10, stroke(), 1));
        tools.addView(box, new LinearLayout.LayoutParams(-1, dp(46)));
        TextView icon = muted("⌕", 22); icon.setGravity(Gravity.CENTER);
        box.addView(icon, new LinearLayout.LayoutParams(dp(30), -1));
        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setText(filter == null ? "" : filter);
        searchInput.setHint("Search templates…");
        searchInput.setTextColor(Color.WHITE);
        searchInput.setHintTextColor(mutedColor());
        searchInput.setTextSize(14);
        searchInput.setPadding(dp(8), 0, 0, 0);
        searchInput.setBackgroundColor(Color.TRANSPARENT);
        box.addView(searchInput, new LinearLayout.LayoutParams(0, -1, 1));
        TextView tune = muted("☷", 18); tune.setGravity(Gravity.CENTER);
        box.addView(tune, new LinearLayout.LayoutParams(dp(32), -1));

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setPadding(0, dp(8), 0, 0);
        tools.addView(meta, new LinearLayout.LayoutParams(-1, dp(32)));
        loadedText = muted("", 12); loadedText.setSingleLine(true);
        meta.addView(loadedText, new LinearLayout.LayoutParams(0, -1, 1));
        updatedText = muted("", 12); updatedText.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); updatedText.setSingleLine(true);
        meta.addView(updatedText, new LinearLayout.LayoutParams(0, -1, 1));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, dp(42)); ap.setMargins(0, dp(4), 0, dp(4));
        tools.addView(actions, ap);
        actions.addView(button(refreshing ? "⟳ Refreshing…" : "⟳ Refresh", true, this::refreshTemplates), weight(dp(42)));
        actions.addView(button("⌫ Clear", false, () -> { filter = ""; renderLimit = PAGE_SIZE; if (searchInput != null) searchInput.setText(""); renderTemplates(); }), weight(dp(42)));
        templateStatus = muted("Native importer uses validated URL and frontend workflow conversion.", 12);
        templateStatus.setSingleLine(true);
        templateStatus.setEllipsize(TextUtils.TruncateAt.END);
        tools.addView(templateStatus);
        searchInput.addTextChangedListener(new TextWatcher() {
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
            nav.removeAllViews();
            nav.setBackgroundColor(surface());
            nav.addView(navItem("⊞", "Create", false, this::goCreate), weight(dp(58)));
            nav.addView(navItem("⌘", "Nodes", false, () -> { templateScreen = false; callBase("showNodes"); hideWebViewsShowPane(); }), weight(dp(58)));
            nav.addView(navItem("▦", "Templates", true, this::showTemplatesScreen), weight(dp(58)));
            nav.addView(navItem("▷", "Run", false, () -> { templateScreen = false; callBase("runWorkflow"); hideWebViewsShowPane(); }), weight(dp(58)));
            nav.addView(navItem("▧", "Output", false, () -> { templateScreen = false; callBase("openOutput"); }), weight(dp(58)));
        } catch (Exception ignored) {}
    }

    private void refreshTemplates() {
        if (refreshing) return;
        String base = stableBaseUrl();
        if (base.isEmpty()) { setStatus("Set a valid ComfyUI URL first."); callBase("toggleTopPanel"); return; }
        refreshing = true;
        setStatus("Refreshing templates index...");
        IO.execute(() -> {
            ArrayList<TemplateItem> loaded = new ArrayList<>();
            String error = "";
            try {
                JSONArray index = new JSONArray(getText(base + "/templates/index.json"));
                for (int i = 0; i < index.length(); i++) {
                    JSONObject category = index.optJSONObject(i); if (category == null) continue;
                    String source = nonEmpty(category.optString("moduleName", "default"), "default");
                    String categoryTitle = nonEmpty(category.optString("localizedTitle", category.optString("title", source)), "Templates");
                    JSONArray arr = category.optJSONArray("templates"); if (arr == null) continue;
                    for (int j = 0; j < arr.length(); j++) {
                        JSONObject raw = arr.optJSONObject(j); if (raw == null) continue;
                        TemplateItem item = new TemplateItem();
                        item.source = source; item.name = raw.optString("name", "").trim();
                        item.title = raw.optString("localizedTitle", raw.optString("title", item.name));
                        item.description = raw.optString("localizedDescription", raw.optString("description", ""));
                        item.category = categoryTitle; item.mediaSubtype = raw.optString("mediaSubtype", "webp");
                        if (!item.name.isEmpty()) loaded.add(item);
                    }
                }
            } catch (Exception e) { error = shortErr(e); }
            try {
                JSONObject custom = new JSONObject(getText(base + "/api/workflow_templates"));
                java.util.Iterator<String> it = custom.keys();
                while (it.hasNext()) {
                    String module = it.next(); if (module == null || module.trim().isEmpty()) continue;
                    JSONArray arr = custom.optJSONArray(module); if (arr == null) continue;
                    for (int i = 0; i < arr.length(); i++) {
                        String name = arr.optString(i, "").trim(); if (name.isEmpty()) continue;
                        TemplateItem item = new TemplateItem();
                        item.source = module.trim(); item.name = name; item.title = name; item.description = module; item.category = "Custom templates"; item.mediaSubtype = "";
                        loaded.add(item);
                    }
                }
            } catch (Exception ignored) {}
            long now = System.currentTimeMillis();
            String finalError = error;
            ui.post(() -> {
                refreshing = false;
                if (!loaded.isEmpty()) {
                    templates.clear(); templates.addAll(loaded); updatedAt = now; saveTemplateCards(loaded, now); renderLimit = PAGE_SIZE; refreshMeta(); renderTemplates();
                    setStatus("Loaded " + loaded.size() + " templates."); preloadTemplateJsons(base, new ArrayList<>(loaded));
                } else {
                    renderTemplates(); setStatus("Refresh failed" + (finalError.isEmpty() ? "." : ": " + finalError));
                }
            });
        });
    }

    private void preloadTemplateJsons(String base, ArrayList<TemplateItem> items) {
        IO.execute(() -> {
            int ok = 0;
            for (int i = 0; i < items.size(); i++) {
                TemplateItem item = items.get(i); if (!validTemplate(item)) continue;
                try { writeText(rawTemplateFile(base, item), getText(templateJsonUrl(base, item))); ok++; } catch (Exception ignored) {}
                if (i % 25 == 0) { int done = i + 1, count = ok; ui.post(() -> setStatus("Caching workflows: " + done + "/" + items.size() + " ready " + count + ".")); }
            }
            int count = ok; ui.post(() -> setStatus("Templates ready. Cached workflows: " + count + "."));
        });
    }

    private void renderTemplates() {
        if (templateList == null) return;
        templateList.removeAllViews();
        String q = nonNull(filter).trim().toLowerCase(Locale.US);
        ArrayList<TemplateItem> matches = new ArrayList<>();
        for (TemplateItem item : templates) if (validTemplate(item) && (q.isEmpty() || metadata(item).toLowerCase(Locale.US).contains(q))) matches.add(item);
        int max = Math.min(renderLimit, matches.size());
        for (int i = 0; i < max; i++) templateList.addView(templateCard(matches.get(i)), sectionParams());
        if (matches.isEmpty()) templateList.addView(muted(templates.isEmpty() ? "No templates cached yet. Tap Refresh." : "Nothing found.", 14));
        if (matches.size() > max) {
            LinearLayout more = card(false); more.setGravity(Gravity.CENTER_HORIZONTAL);
            more.addView(muted("Showing " + max + " of " + matches.size() + " templates.", 13));
            more.addView(button("Show more", false, () -> { renderLimit += PAGE_SIZE; renderTemplates(); }), new LinearLayout.LayoutParams(-1, dp(40)));
            templateList.addView(more, sectionParams());
        }
    }

    private View templateCard(TemplateItem item) {
        LinearLayout row = card(false); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(12), dp(12), dp(10), dp(12)); row.setClickable(true); row.setOnClickListener(v -> openTemplate(item));
        ImageView img = new ImageView(this); img.setScaleType(ImageView.ScaleType.CENTER_CROP); img.setImageResource(com.snapsnake.comfyremote.R.drawable.ic_launcher); img.setBackground(bg(surface2(), 8, stroke(), 1)); img.setTag(item.id());
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(106), dp(82)); ip.setMargins(0, 0, dp(12), 0); row.addView(img, ip);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setGravity(Gravity.CENTER_VERTICAL); row.addView(body, new LinearLayout.LayoutParams(0, dp(82), 1));
        TextView name = title(displayTitle(item), 16); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END); body.addView(name);
        TextView sub = muted(shortText(nonEmpty(item.description, item.category).replace('_', ' '), 130), 12); sub.setMaxLines(2); sub.setEllipsize(TextUtils.TruncateAt.END); body.addView(sub);
        TextView arrow = muted("›", 28); arrow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); row.addView(arrow, new LinearLayout.LayoutParams(dp(22), dp(82)));
        loadPreview(img, item);
        return row;
    }

    private void openTemplate(TemplateItem item) {
        String base = stableBaseUrl();
        if (base.isEmpty()) { setStatus("Set a valid ComfyUI URL first."); callBase("toggleTopPanel"); return; }
        if (!validTemplate(item)) { setStatus("Template entry is invalid."); return; }
        setStatus("Opening template: " + displayTitle(item));
        IO.execute(() -> {
            try {
                String raw = readText(rawTemplateFile(base, item));
                if (raw.trim().isEmpty()) { raw = getText(templateJsonUrl(base, item)); writeText(rawTemplateFile(base, item), raw); }
                JSONObject result = ComfyWorkflowConverter.importResult(new JSONObject(raw), objectInfoOrEmpty(base));
                ui.post(() -> importPrompt(result.toString()));
            } catch (Exception e) { ui.post(() -> setStatus("Template open failed: " + shortErr(e))); }
        });
    }

    private void importWorkflowJson(String raw) {
        IO.execute(() -> {
            try {
                JSONObject result = ComfyWorkflowConverter.importResult(new JSONObject(raw), objectInfoOrEmpty(stableBaseUrl()));
                ui.post(() -> importPrompt(result.toString()));
            } catch (Exception e) { ui.post(() -> setStatus("Workflow import failed: " + shortErr(e))); }
        });
    }

    private JSONObject objectInfoOrEmpty(String base) {
        if (base == null || base.isEmpty()) return new JSONObject();
        try { return new JSONObject(getText(base + "/api/object_info")); } catch (Exception e) { return new JSONObject(); }
    }

    private void loadPreview(ImageView img, TemplateItem item) {
        String base = stableBaseUrl(); if (base.isEmpty() || !validTemplate(item)) return;
        String tag = item.id(); String memKey = base + "|" + tag; img.setTag(tag);
        Bitmap mem = PREVIEW_CACHE.get(memKey); if (mem != null) { img.setImageBitmap(mem); return; }
        IO.execute(() -> {
            try {
                for (String ext : previewExtensions(item)) { File cached = previewFile(base, item, ext); if (cached.exists() && cached.length() > 0) { showPreviewFile(img, tag, memKey, cached); return; } }
                for (String ext : previewExtensions(item)) {
                    try { File f = previewFile(base, item, ext); downloadToFile(previewUrl(base, item, ext, false), f); showPreviewFile(img, tag, memKey, f); return; } catch (Exception ignored) {}
                    try { File f = previewFile(base, item, ext); downloadToFile(previewUrl(base, item, ext, true), f); showPreviewFile(img, tag, memKey, f); return; } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        });
    }

    private void showPreviewFile(ImageView img, String expectedTag, String memKey, File file) {
        Bitmap b = decodePreviewBitmap(file, dp(106), dp(82)); if (b == null) return; PREVIEW_CACHE.put(memKey, b);
        ui.post(() -> { Object current = img.getTag(); if (current != null && String.valueOf(current).equals(expectedTag)) img.setImageBitmap(b); });
    }

    private Bitmap decodePreviewBitmap(File file, int targetW, int targetH) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inPreferredConfig = Bitmap.Config.RGB_565; opts.inSampleSize = 1;
            while ((bounds.outWidth / opts.inSampleSize) > targetW * 2 || (bounds.outHeight / opts.inSampleSize) > targetH * 2) opts.inSampleSize *= 2;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Exception e) { return null; }
    }

    private String stableBaseUrl() {
        String raw = "";
        try { Object f = baseField("urlInput"); if (f instanceof EditText) raw = ((EditText) f).getText().toString(); } catch (Exception ignored) {}
        if (raw == null || raw.trim().isEmpty()) raw = getSharedPreferences(REMOTE_PREFS, Context.MODE_PRIVATE).getString(KEY_URL, "");
        raw = raw == null ? "" : raw.trim();
        if (raw.isEmpty()) return "";
        if (raw.startsWith("//")) raw = "https:" + raw;
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) raw = "https://" + raw;
        while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        try {
            URL u = new URL(raw);
            if (u.getHost() == null || u.getHost().trim().isEmpty()) return "";
            getSharedPreferences(REMOTE_PREFS, Context.MODE_PRIVATE).edit().putString(KEY_URL, raw).apply();
            return raw;
        } catch (Exception e) { return ""; }
    }

    private String hostLabel(String base) { try { return new URL(base).getHost(); } catch (Exception e) { return base; } }
    private boolean validTemplate(TemplateItem item) { return item != null && !nonNull(item.name).trim().isEmpty() && !nonNull(item.source).trim().isEmpty(); }
    private String templateJsonUrl(String base, TemplateItem item) { return "default".equals(item.source) ? base + "/templates/" + path(item.name) + ".json" : base + "/api/workflow_templates/" + path(item.source) + "/" + path(item.name) + ".json"; }
    private String previewUrl(String base, TemplateItem item, String ext, boolean fallback) { return "default".equals(item.source) ? base + "/templates/" + path(item.name) + (fallback ? "" : "-1") + "." + ext : base + "/api/workflow_templates/" + path(item.source) + "/" + path(item.name) + "." + ext; }
    private ArrayList<String> previewExtensions(TemplateItem item) { LinkedHashSet<String> exts = new LinkedHashSet<>(); String s = nonNull(item.mediaSubtype).trim().toLowerCase(Locale.US); if (!s.isEmpty()) exts.add(s); exts.add("webp"); exts.add("png"); exts.add("jpg"); exts.add("jpeg"); exts.add("gif"); return new ArrayList<>(exts); }

    private void loadCachedTemplates() { SharedPreferences p = getSharedPreferences(TEMPLATE_PREFS, Context.MODE_PRIVATE); updatedAt = p.getLong(KEY_UPDATED, 0L); templates.clear(); try { JSONArray arr = new JSONArray(p.getString(KEY_CARDS, "[]")); for (int i = 0; i < arr.length(); i++) { TemplateItem item = TemplateItem.fromJson(arr.optJSONObject(i)); if (validTemplate(item)) templates.add(item); } } catch (Exception ignored) {} }
    private void saveTemplateCards(ArrayList<TemplateItem> items, long time) { JSONArray arr = new JSONArray(); for (TemplateItem item : items) { try { if (validTemplate(item)) arr.put(item.toJson()); } catch (Exception ignored) {} } getSharedPreferences(TEMPLATE_PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CARDS, arr.toString()).putLong(KEY_UPDATED, time).apply(); }
    private void refreshMeta() { if (loadedText != null) { loadedText.setText((templates.isEmpty() ? "○" : "●") + " Loaded " + templates.size() + " template" + (templates.size() == 1 ? "" : "s")); loadedText.setTextColor(templates.isEmpty() ? mutedColor() : accent()); } if (updatedText != null) updatedText.setText(updatedAt > 0 ? "Updated " + timeAgo(updatedAt) : "Not refreshed yet"); }
    private String metadata(TemplateItem item) { return displayTitle(item) + " " + nonNull(item.name) + " " + nonNull(item.description) + " " + nonNull(item.category) + " " + nonNull(item.source); }
    private String displayTitle(TemplateItem item) { return nonEmpty(item.title, item.name); }
    private String nonEmpty(String v, String fallback) { return v == null || v.trim().isEmpty() ? nonNull(fallback) : v.trim(); }
    private String nonNull(String v) { return v == null ? "" : v; }
    private String shortText(String s, int max) { if (s == null) return ""; return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…"; }
    private String shortErr(Exception e) { String s = e == null ? "" : e.getMessage(); if (s == null || s.trim().isEmpty()) s = e == null ? "unknown error" : e.getClass().getSimpleName(); return s.length() > 180 ? s.substring(0, 180) + "…" : s; }
    private String timeAgo(long ts) { long sec = Math.max(0L, (System.currentTimeMillis() - ts) / 1000L); if (sec < 60) return "just now"; long min = sec / 60L; if (min < 60) return min + "m ago"; long h = min / 60L; if (h < 24) return h + "h ago"; return (h / 24L) + "d ago"; }
    private String path(String s) { try { return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20").replace("%2F", "/"); } catch (Exception e) { return s == null ? "" : s; } }
    private String sha1(String value) { try { MessageDigest md = MessageDigest.getInstance("SHA-1"); byte[] d = md.digest(nonNull(value).getBytes("UTF-8")); StringBuilder sb = new StringBuilder(); for (byte b : d) sb.append(String.format(Locale.US, "%02x", b & 0xff)); return sb.toString(); } catch (Exception e) { return String.valueOf(nonNull(value).hashCode()).replace("-", "n"); } }

    private String getText(String url) throws Exception { return new String(bytes(url, MAX_TEXT_BYTES), "UTF-8"); }
    private byte[] bytes(String url, int maxBytes) throws Exception { HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(); try { c.setConnectTimeout(10000); c.setReadTimeout(30000); for (Map.Entry<String, String> e : requestHeaders(url).entrySet()) c.setRequestProperty(e.getKey(), e.getValue()); int code = c.getResponseCode(); InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream(); ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n, total = 0; while (in != null && (n = in.read(b)) > 0) { total += n; if (total > maxBytes) throw new Exception("response is too large"); out.write(b, 0, n); } if (in != null) in.close(); if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + out.toString("UTF-8")); return out.toByteArray(); } finally { c.disconnect(); } }
    private void downloadToFile(String url, File file) throws Exception { HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(); File tmp = new File(file.getAbsolutePath() + ".tmp"); try { c.setConnectTimeout(10000); c.setReadTimeout(45000); for (Map.Entry<String, String> e : requestHeaders(url).entrySet()) c.setRequestProperty(e.getKey(), e.getValue()); int code = c.getResponseCode(); InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream(); if (code < 200 || code >= 300) throw new Exception("HTTP " + code); File parent = file.getParentFile(); if (parent != null && !parent.exists()) parent.mkdirs(); FileOutputStream out = new FileOutputStream(tmp); byte[] b = new byte[16384]; int n; while (in != null && (n = in.read(b)) > 0) out.write(b, 0, n); if (in != null) in.close(); out.flush(); out.close(); if (file.exists()) file.delete(); if (!tmp.renameTo(file)) { writeBytes(file, readBytes(tmp)); tmp.delete(); } } finally { c.disconnect(); if (tmp.exists() && (!file.exists() || file.length() == 0)) tmp.delete(); } }
    @SuppressWarnings("unchecked") private Map<String, String> requestHeaders(String url) throws Exception { Method m = EnhancedPolishedActivity.class.getDeclaredMethod("accessHeaders"); m.setAccessible(true); Map<String, String> h = (Map<String, String>) m.invoke(this); try { String cookies = CookieManager.getInstance().getCookie(url); if (cookies != null && !cookies.trim().isEmpty()) h.put("Coo" + "kie", cookies); } catch (Exception ignored) {} return h; }
    private String readUriText(Uri uri) throws Exception { InputStream in = getContentResolver().openInputStream(uri); if (in == null) throw new Exception("empty file"); try { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); return out.toString("UTF-8"); } finally { in.close(); } }
    private File cacheDir(String child) { File dir = new File(getFilesDir(), "template_cache/" + child); if (!dir.exists()) dir.mkdirs(); return dir; }
    private File rawTemplateFile(String base, TemplateItem item) { return new File(cacheDir("raw"), sha1(base + "|" + item.id()) + ".json"); }
    private File previewFile(String base, TemplateItem item, String ext) { return new File(cacheDir("preview"), sha1(base + "|" + item.id() + "|" + ext) + "." + ext.replaceAll("[^A-Za-z0-9]", "")); }
    private String readText(File file) { try { byte[] data = readBytes(file); return data.length == 0 ? "" : new String(data, "UTF-8"); } catch (Exception e) { return ""; } }
    private byte[] readBytes(File file) { if (file == null || !file.exists()) return new byte[0]; ByteArrayOutputStream out = new ByteArrayOutputStream(); try { FileInputStream in = new FileInputStream(file); byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); in.close(); } catch (Exception ignored) {} return out.toByteArray(); }
    private void writeText(File file, String text) { try { writeBytes(file, nonNull(text).getBytes("UTF-8")); } catch (Exception ignored) {} }
    private void writeBytes(File file, byte[] data) { try { File parent = file.getParentFile(); if (parent != null && !parent.exists()) parent.mkdirs(); FileOutputStream out = new FileOutputStream(file); out.write(data); out.flush(); out.close(); } catch (Exception ignored) {} }
    private void importPrompt(String resultJson) { try { templateScreen = false; setBaseScreen("create"); Method m = PolishedNodeActivity.class.getDeclaredMethod("handleImportJson", String.class); m.setAccessible(true); m.invoke(this, resultJson); hideWebViewsShowPane(); } catch (Exception e) { setStatus("Import failed: " + shortErr(e)); } }
    private Object baseField(String name) throws Exception { Field f = PolishedNodeActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(this); }
    private Object callBase(String name) { try { Method m = PolishedNodeActivity.class.getDeclaredMethod(name); m.setAccessible(true); return m.invoke(this); } catch (Exception e) { return null; } }
    private void setBaseScreen(String value) { try { Field f = PolishedNodeActivity.class.getDeclaredField("screen"); f.setAccessible(true); f.set(this, value); } catch (Exception ignored) {} }
    private void setStatus(String s) { try { Object x = baseField("status"); if (x instanceof TextView) ((TextView) x).setText(s); } catch (Exception ignored) {} if (templateStatus != null) templateStatus.setText(s); }

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
    private Button button(String t, boolean primary, Runnable action) { Button b = new Button(this); b.setText(t); b.setAllCaps(false); b.setSingleLine(true); b.setTextColor(primary ? accent() : Color.WHITE); b.setTextSize(13); b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); b.setPadding(dp(6), 0, dp(6), 0); b.setBackground(bg(primary ? Color.rgb(44,35,25) : surface2(), 12, primary ? accent() : stroke(), 1)); b.setOnClickListener(v -> action.run()); return b; }
    private View navItem(String icon, String label, boolean selected, Runnable action) { LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER); box.setPadding(0, dp(4), 0, dp(4)); box.setBackground(selected ? bg(Color.rgb(37,31,22), 12, accent(), 1) : bg(Color.TRANSPARENT, 12, Color.TRANSPARENT, 0)); TextView i = text(icon, 19, selected ? accent() : mutedColor()); i.setGravity(Gravity.CENTER); box.addView(i, new LinearLayout.LayoutParams(-1, dp(24))); TextView l = text(label, 10, selected ? accent() : mutedColor()); l.setGravity(Gravity.CENTER); l.setSingleLine(true); box.addView(l, new LinearLayout.LayoutParams(-1, dp(20))); box.setOnClickListener(v -> action.run()); return box; }
    private GradientDrawable bg(int color, int radiusDp, int stroke, int strokeDp) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radiusDp)); d.setStroke(dp(strokeDp), stroke); return d; }
}
