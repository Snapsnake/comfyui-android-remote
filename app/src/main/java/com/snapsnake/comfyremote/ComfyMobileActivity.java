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
import android.widget.TextView;

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

public class ComfyMobileActivity extends EnhancedPolishedActivity {
    private static final String PREFS = "comfyui_template_cache";
    private static final String KEY_CARDS = "template_cards_v4";
    private static final String KEY_UPDATED_AT = "template_cards_updated_at_v4";
    private static final int REQ_JSON = 44;
    private static final int PAGE_SIZE = 40;
    private static final int MAX_TEXT_BYTES = 16 * 1024 * 1024;
    private static final ExecutorService IO = Executors.newFixedThreadPool(4);
    private static final LruCache<String, Bitmap> PREVIEW_MEMORY = new LruCache<>(32);

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
        private static String safe(String s) { return s == null ? "" : s; }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        loadCachedTemplates();
    }

    @Override protected void showTemplatesTab() { showTemplatesScreen(); }

    @Override public void onBackPressed() {
        if (templateScreen) { leaveTemplates(); return; }
        super.onBackPressed();
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        if (req == REQ_JSON) {
            if (result == Activity.RESULT_OK && data != null && data.getData() != null) {
                try { importWorkflowJson(readUriText(data.getData())); }
                catch (Exception e) { setStatus("Could not read JSON: " + shortErr(e)); }
            }
            callBaseQuiet("applyBars");
            return;
        }
        super.onActivityResult(req, result, data);
    }

    private void importWorkflowJson(String raw) {
        IO.execute(() -> {
            try {
                JSONObject workflow = new JSONObject(raw);
                JSONObject objectInfo = objectInfoOrEmpty();
                JSONObject result = ComfyWorkflowConverter.importResult(workflow, objectInfo);
                ui.post(() -> importPrompt(result.toString()));
            } catch (Exception e) {
                ui.post(() -> setStatus("Workflow import failed: " + shortErr(e)));
            }
        });
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
            content.setPadding(dp(20), dp(16), dp(20), dp(20));
            content.setBackgroundColor(bgRoot());
            content.addView(connectionChip(), sectionParams());
            content.addView(pageHeader(), sectionParams());
            content.addView(searchPanel(), sectionParams());
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
        } catch (Exception e) { setStatus("Templates failed: " + shortErr(e)); }
    }

    private void leaveTemplates() {
        templateScreen = false;
        templateList = null;
        search = null;
        setBaseScreen("create");
        callBaseQuiet("showCreate");
    }

    private View connectionChip() {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(12), 0, dp(12), 0);
        chip.setBackground(bg(surface(), 12, stroke(), 1));
        chip.addView(text("●", 16, rgb(66,184,93)), new LinearLayout.LayoutParams(dp(24), dp(38)));
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
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        head.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        copy.addView(title("Templates", 28));
        copy.addView(muted("Browse and load workflow templates", 13));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        logo.setPadding(dp(8), dp(8), dp(8), dp(8));
        logo.setBackground(bg(surface2(), 12, stroke(), 1));
        head.addView(logo, new LinearLayout.LayoutParams(dp(42), dp(42)));
        return head;
    }

    private View searchPanel() {
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
        actions.addView(button(refreshing ? "⟳ Refreshing…" : "⟳ Refresh", true, this::refreshTemplates), weight(dp(42)));
        actions.addView(button("⌫ Clear", false, () -> { filter = ""; renderLimit = PAGE_SIZE; if (search != null) search.setText(""); renderTemplates(); }), weight(dp(42)));
        templateStatus = muted("Uses frontend workflow/subgraph conversion, not stale extra.prompt.", 12);
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
            nav.removeAllViews();
            nav.setBackgroundColor(surface());
            nav.addView(navItem("⊞", "Create", false, this::leaveTemplates), weight(dp(58)));
            nav.addView(navItem("⌘", "Nodes", false, () -> { templateScreen = false; callBaseQuiet("showNodes"); }), weight(dp(58)));
            nav.addView(navItem("▦", "Templates", true, this::showTemplatesScreen), weight(dp(58)));
            nav.addView(navItem("▷", "Run", false, () -> { templateScreen = false; callBaseQuiet("runWorkflow"); }), weight(dp(58)));
            nav.addView(navItem("▧", "Output", false, () -> { templateScreen = false; callBaseQuiet("openOutput"); }), weight(dp(58)));
        } catch (Exception ignored) {}
    }

    private void refreshTemplates() {
        if (refreshing) return;
        String base = baseUrl();
        if (base.isEmpty()) { setStatus("Enter ComfyUI URL first."); return; }
        refreshing = true;
        setStatus("Refreshing templates index...");
        IO.execute(() -> {
            ArrayList<TemplateItem> loaded = new ArrayList<>();
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
                        item.name = raw.optString("name", "").trim();
                        item.title = raw.optString("localizedTitle", raw.optString("title", item.name));
                        item.description = raw.optString("localizedDescription", raw.optString("description", ""));
                        item.category = categoryTitle == null || categoryTitle.trim().isEmpty() ? "Templates" : categoryTitle;
                        item.mediaSubtype = raw.optString("mediaSubtype", "webp");
                        if (!item.name.isEmpty()) loaded.add(item);
                    }
                }
            } catch (Exception ignored) {}
            try {
                JSONObject custom = new JSONObject(getText(base + "/api/workflow_templates"));
                java.util.Iterator<String> it = custom.keys();
                while (it.hasNext()) {
                    String module = it.next();
                    JSONArray arr = custom.optJSONArray(module);
                    if (arr == null) continue;
                    for (int i = 0; i < arr.length(); i++) {
                        String name = arr.optString(i, "").trim();
                        if (name.isEmpty()) continue;
                        TemplateItem item = new TemplateItem();
                        item.source = module; item.name = name; item.title = name; item.description = module; item.category = "Custom templates"; item.mediaSubtype = "";
                        loaded.add(item);
                    }
                }
            } catch (Exception ignored) {}
            long now = System.currentTimeMillis();
            ui.post(() -> {
                refreshing = false;
                if (!loaded.isEmpty()) {
                    templates.clear(); templates.addAll(loaded); lastUpdatedAt = now; saveTemplateCards(loaded, now); renderLimit = PAGE_SIZE; refreshMeta(); renderTemplates();
                    setStatus("Loaded " + loaded.size() + " templates.");
                    preloadTemplateJsons(base, new ArrayList<>(loaded));
                } else {
                    renderTemplates(); setStatus("Refresh failed; kept local cache.");
                }
            });
        });
    }

    private void preloadTemplateJsons(String base, ArrayList<TemplateItem> items) {
        IO.execute(() -> {
            int ok = 0;
            for (int i = 0; i < items.size(); i++) {
                try { writeText(rawTemplateFile(base, items.get(i)), getText(templateJsonUrl(base, items.get(i)))); ok++; }
                catch (Exception ignored) {}
                if (i % 25 == 0) { int done = i + 1, count = ok; ui.post(() -> setStatus("Caching workflows: " + done + "/" + items.size() + " ready " + count + ".")); }
            }
            int count = ok;
            ui.post(() -> setStatus("Templates ready. Cached workflows: " + count + "."));
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
            more.addView(button("Show more", false, () -> { renderLimit += PAGE_SIZE; renderTemplates(); }), new LinearLayout.LayoutParams(-1, dp(40)));
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
        TextView sub = muted(shortText(safe(item.description).trim().isEmpty() ? safe(item.category) : item.description.replace('_', ' '), 130), 12);
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
        if (base.isEmpty()) { setStatus("Enter ComfyUI URL first."); return; }
        setStatus("Opening template: " + displayTitle(item));
        IO.execute(() -> {
            try {
                String raw = readText(rawTemplateFile(base, item));
                if (raw.trim().isEmpty()) { raw = getText(templateJsonUrl(base, item)); writeText(rawTemplateFile(base, item), raw); }
                JSONObject result = ComfyWorkflowConverter.importResult(new JSONObject(raw), objectInfoOrEmpty());
                ui.post(() -> importPrompt(result.toString()));
            } catch (Exception e) { ui.post(() -> setStatus("Template open failed: " + shortErr(e))); }
        });
    }

    private JSONObject objectInfoOrEmpty() {
        String base = baseUrl();
        if (base.isEmpty()) return new JSONObject();
        try { return new JSONObject(getText(base + "/api/object_info")); }
        catch (Exception e) { return new JSONObject(); }
    }

    private void loadPreview(ImageView img, TemplateItem item) {
        String base = baseUrl();
        String tag = item.id();
        String memKey = base + "|" + tag;
        img.setTag(tag);
        Bitmap mem = PREVIEW_MEMORY.get(memKey);
        if (mem != null) { img.setImageBitmap(mem); return; }
        IO.execute(() -> {
            try {
                for (String ext : previewExtensions(item)) {
                    File cached = previewFile(base, item, ext);
                    if (cached.exists() && cached.length() > 0) { showPreviewFile(img, tag, memKey, cached); return; }
                }
                if (base.isEmpty()) return;
                for (String ext : previewExtensions(item)) {
                    try { File f = previewFile(base, item, ext); downloadToFile(previewUrl(base, item, ext, false), f); showPreviewFile(img, tag, memKey, f); return; } catch (Exception ignored) {}
                    try { File f = previewFile(base, item, ext); downloadToFile(previewUrl(base, item, ext, true), f); showPreviewFile(img, tag, memKey, f); return; } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        });
    }

    private void showPreviewFile(ImageView img, String expectedTag, String memKey, File file) {
        Bitmap bitmap = decodePreviewBitmap(file, dp(106), dp(82));
        if (bitmap == null) return;
        PREVIEW_MEMORY.put(memKey, bitmap);
        ui.post(() -> { Object current = img.getTag(); if (current != null && String.valueOf(current).equals(expectedTag)) img.setImageBitmap(bitmap); });
    }

    private Bitmap decodePreviewBitmap(File file, int targetW, int targetH) {
        try {
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), b);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            o.inSampleSize = 1;
            while ((b.outWidth / o.inSampleSize) > targetW * 2 || (b.outHeight / o.inSampleSize) > targetH * 2) o.inSampleSize *= 2;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), o);
        } catch (Exception e) { return null; }
    }

    private ArrayList<String> previewExtensions(TemplateItem item) {
        LinkedHashSet<String> exts = new LinkedHashSet<>();
        String s = safe(item.mediaSubtype).trim().toLowerCase(Locale.US);
        if (!s.isEmpty()) exts.add(s);
        exts.add("webp"); exts.add("png"); exts.add("jpg"); exts.add("jpeg"); exts.add("gif");
        return new ArrayList<>(exts);
    }

    private String templateJsonUrl(String base, TemplateItem item) { return "default".equals(item.source) ? base + "/templates/" + path(item.name) + ".json" : base + "/api/workflow_templates/" + path(item.source) + "/" + path(item.name) + ".json"; }
    private String previewUrl(String base, TemplateItem item, String ext, boolean fallback) { return "default".equals(item.source) ? base + "/templates/" + path(item.name) + (fallback ? "" : "-1") + "." + ext : base + "/api/workflow_templates/" + path(item.source) + "/" + path(item.name) + "." + ext; }

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
        } finally { c.disconnect(); if (tmp.exists() && (!file.exists() || file.length() == 0)) tmp.delete(); }
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

    private String readUriText(Uri uri) throws Exception { InputStream in = getContentResolver().openInputStream(uri); if (in == null) throw new Exception("empty file"); try { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); return out.toString("UTF-8"); } finally { in.close(); } }
    private File cacheDir(String child) { File dir = new File(getFilesDir(), "template_cache/" + child); if (!dir.exists()) dir.mkdirs(); return dir; }
    private File rawTemplateFile(String base, TemplateItem item) { return new File(cacheDir("raw"), sha1(base + "|" + item.id()) + ".json"); }
    private File previewFile(String base, TemplateItem item, String ext) { return new File(cacheDir("preview"), sha1(base + "|" + item.id() + "|" + ext) + "." + ext.replaceAll("[^A-Za-z0-9]", "")); }
    private String readText(File file) { try { byte[] data = readBytes(file); return data.length == 0 ? "" : new String(data, "UTF-8"); } catch (Exception e) { return ""; } }
    private byte[] readBytes(File file) { if (file == null || !file.exists()) return new byte[0]; ByteArrayOutputStream out = new ByteArrayOutputStream(); try { FileInputStream in = new FileInputStream(file); byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); in.close(); } catch (Exception ignored) {} return out.toByteArray(); }
    private void writeText(File file, String text) { try { writeBytes(file, safe(text).getBytes("UTF-8")); } catch (Exception ignored) {} }
    private void writeBytes(File file, byte[] data) { try { File parent = file.getParentFile(); if (parent != null && !parent.exists()) parent.mkdirs(); FileOutputStream out = new FileOutputStream(file); out.write(data); out.flush(); out.close(); } catch (Exception ignored) {} }
    private void importPrompt(String resultJson) { try { templateScreen = false; setBaseScreen("create"); Method m = PolishedNodeActivity.class.getDeclaredMethod("handleImportJson", String.class); m.setAccessible(true); m.invoke(this, resultJson); } catch (Exception e) { setStatus("Import failed: " + shortErr(e)); } }
    private String baseUrl() { Object x = callBaseQuiet("baseUrl"); return x == null ? "" : String.valueOf(x); }
    private Object baseField(String name) throws Exception { Field f = PolishedNodeActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(this); }
    private Object callBaseQuiet(String name) { try { Method m = PolishedNodeActivity.class.getDeclaredMethod(name); m.setAccessible(true); return m.invoke(this); } catch (Exception e) { return null; } }
    private void setBaseScreen(String value) { try { Field f = PolishedNodeActivity.class.getDeclaredField("screen"); f.setAccessible(true); f.set(this, value); } catch (Exception ignored) {} }
    private void setStatus(String s) { try { Object x = baseField("status"); if (x instanceof TextView) ((TextView) x).setText(s); } catch (Exception ignored) {} if (templateStatus != null) templateStatus.setText(s); }
    private void refreshMeta() { if (loadedText != null) { loadedText.setText((templates.isEmpty() ? "○" : "●") + " Loaded " + templates.size() + " template" + (templates.size() == 1 ? "" : "s")); loadedText.setTextColor(templates.isEmpty() ? mutedColor() : accent()); } if (updatedText != null) updatedText.setText(lastUpdatedAt > 0 ? "Updated " + timeAgo(lastUpdatedAt) : "Not refreshed yet"); }
    private String path(String s) { try { return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20").replace("%2F", "/"); } catch (Exception e) { return s == null ? "" : s; } }
    private String safe(String s) { return s == null ? "" : s; }
    private String shortText(String s, int max) { if (s == null) return ""; return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…"; }
    private String shortErr(Exception e) { String s = e == null ? "" : e.getMessage(); if (s == null || s.trim().isEmpty()) s = e == null ? "unknown error" : e.getClass().getSimpleName(); return s.length() > 220 ? s.substring(0, 220) + "…" : s; }
    private String displayTitle(TemplateItem item) { String t = safe(item.title).trim(); return t.isEmpty() ? safe(item.name).trim() : t; }
    private String metadataText(TemplateItem item) { return displayTitle(item) + " " + safe(item.name) + " " + safe(item.description) + " " + safe(item.category) + " " + safe(item.source); }
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
    private Button button(String t, boolean primary, Runnable action) { Button b = new Button(this); b.setText(t); b.setAllCaps(false); b.setSingleLine(true); b.setTextColor(primary ? accent() : Color.WHITE); b.setTextSize(13); b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); b.setPadding(dp(6), 0, dp(6), 0); b.setBackground(bg(primary ? Color.rgb(44,35,25) : surface2(), 12, primary ? accent() : stroke(), 1)); b.setOnClickListener(v -> action.run()); return b; }
    private View navItem(String icon, String label, boolean selected, Runnable action) { LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER); box.setPadding(0, dp(4), 0, dp(4)); box.setBackground(selected ? bg(Color.rgb(37,31,22), 12, accent(), 1) : bg(Color.TRANSPARENT, 12, Color.TRANSPARENT, 0)); TextView i = text(icon, 19, selected ? accent() : mutedColor()); i.setGravity(Gravity.CENTER); box.addView(i, new LinearLayout.LayoutParams(-1, dp(24))); TextView l = text(label, 10, selected ? accent() : mutedColor()); l.setGravity(Gravity.CENTER); l.setSingleLine(true); box.addView(l, new LinearLayout.LayoutParams(-1, dp(20))); box.setOnClickListener(v -> action.run()); return box; }
    private GradientDrawable bg(int color, int radiusDp, int stroke, int strokeDp) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radiusDp)); d.setStroke(dp(strokeDp), stroke); return d; }
}
