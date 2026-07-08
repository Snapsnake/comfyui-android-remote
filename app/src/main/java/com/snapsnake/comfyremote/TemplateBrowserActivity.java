package com.snapsnake.comfyremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;

public class TemplateBrowserActivity extends EnhancedPolishedActivity {
    private static final String PREFS = "comfyui_template_cache";
    private static final String KEY_CARDS = "template_cards_v2";
    private static final String KEY_UPDATED_AT = "template_cards_updated_at";
    private static final int PAGE_SIZE = 24;
    private static final int MAX_TEXT_BYTES = 12 * 1024 * 1024;
    private static final int MAX_PREVIEW_BYTES = 6 * 1024 * 1024;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<TemplateItem> templates = new ArrayList<>();
    private LinearLayout templateList;
    private EditText search;
    private String filter = "";
    private int renderLimit = PAGE_SIZE;
    private long lastUpdatedAt = 0L;
    private boolean refreshing = false;
    private boolean preloading = false;

    private static class TemplateItem {
        String source;
        String name;
        String title;
        String description;
        String category;
        String mediaSubtype;
        boolean valid = true;
        String error;

        String id() {
            return safe(source).trim() + "/" + safe(name).trim();
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("source", safe(source));
            o.put("name", safe(name));
            o.put("title", safe(title));
            o.put("description", safe(description));
            o.put("category", safe(category));
            o.put("mediaSubtype", safe(mediaSubtype));
            o.put("valid", valid);
            o.put("error", safe(error));
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
            item.error = o.optString("error", "");
            return item;
        }

        private static String safe(String s) { return s == null ? "" : s; }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        loadCachedTemplates();
        hookTemplateButtons((View) getWindow().getDecorView());
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hookTemplateButtons((View) getWindow().getDecorView());
    }

    private void hookTemplateButtons(View v) {
        if (v instanceof Button) {
            Button b = (Button) v;
            String t = String.valueOf(b.getText()).trim();
            if ("Graph".equalsIgnoreCase(t) || "Templates".equalsIgnoreCase(t) || "Open Graph".equalsIgnoreCase(t)) {
                b.setText("Templates");
                b.setOnClickListener(x -> showTemplates());
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) hookTemplateButtons(g.getChildAt(i));
        }
    }

    private void showTemplates() {
        try {
            callBaseQuiet("saveUrl");
            View top = (View) baseField("topPanel");
            View pane = (View) baseField("pane");
            View graph = (View) baseField("graph");
            View output = (View) baseField("output");
            if (top != null) top.setVisibility(View.GONE);
            if (pane != null) {
                pane.setVisibility(View.VISIBLE);
                pane.setBackgroundColor(rgb(2, 6, 23));
            }
            if (graph != null) graph.setVisibility(View.GONE);
            if (output != null) output.setVisibility(View.GONE);

            LinearLayout content = (LinearLayout) baseField("content");
            content.removeAllViews();
            content.setBackgroundColor(rgb(2, 6, 23));
            content.addView(brandHeader(), cardParams());
            content.addView(searchPanel(), cardParams());

            templateList = new LinearLayout(this);
            templateList.setOrientation(LinearLayout.VERTICAL);
            content.addView(templateList, new LinearLayout.LayoutParams(-1, -2));

            if (templates.isEmpty()) {
                loadCachedTemplates();
                if (templates.isEmpty() && !baseUrl().isEmpty()) loadTemplates();
            }
            renderTemplates();
            setStatus(templates.isEmpty() ? "Templates cache is empty. Tap Refresh templates." : "Templates loaded from local cache.");
            callBaseQuiet("applyBars");
        } catch (Exception e) {
            setStatus("Templates failed: " + shortErr(e));
        }
    }

    private View brandHeader() {
        LinearLayout hero = card(true);
        hero.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        hero.addView(row);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        logo.setPadding(dp(8), dp(8), dp(8), dp(8));
        logo.setBackground(bg(rgb(15, 23, 42), 22, rgb(96, 165, 250), 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(74), dp(74));
        lp.setMargins(0, 0, dp(14), 0);
        row.addView(logo, lp);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        TextView title = title("ComfyUI Mobile");
        title.setTextSize(26);
        copy.addView(title);
        copy.addView(muted("Local template cache, safe search, refreshable workflows."));
        copy.addView(muted(lastUpdatedAt > 0 ? "Last refresh: " + timeAgo(lastUpdatedAt) : "No cached refresh yet."));
        return hero;
    }

    private View searchPanel() {
        LinearLayout tools = card(false);
        TextView label = muted("Preloaded Templates");
        label.setTextColor(rgb(203, 213, 225));
        tools.addView(label);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setText(filter == null ? "" : filter);
        search.setHint("Search templates…");
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(rgb(148, 163, 184));
        search.setTextSize(16);
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(bg(rgb(15, 23, 42), 16, rgb(71, 85, 105), 1));
        tools.addView(search, new LinearLayout.LayoutParams(-1, dp(54)));

        LinearLayout actions = row();
        tools.addView(actions);
        action(actions, refreshing ? "Refreshing…" : "Refresh templates", true, this::loadTemplates);
        action(actions, "Clear", false, () -> { filter = ""; renderLimit = PAGE_SIZE; if (search != null) search.setText(""); renderTemplates(); });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filter = String.valueOf(s == null ? "" : s);
                renderLimit = PAGE_SIZE;
                renderTemplates();
            }
            public void afterTextChanged(Editable s) {}
        });
        return tools;
    }

    private void loadCachedTemplates() {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        lastUpdatedAt = prefs.getLong(KEY_UPDATED_AT, 0L);
        String raw = prefs.getString(KEY_CARDS, "[]");
        ArrayList<TemplateItem> cached = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                TemplateItem item = TemplateItem.fromJson(o);
                if (!safe(item.name).trim().isEmpty()) cached.add(item);
            }
        } catch (Exception ignored) {}
        templates.clear();
        templates.addAll(cached);
    }

    private void saveTemplateCards(ArrayList<TemplateItem> items, long updatedAt) {
        JSONArray arr = new JSONArray();
        for (TemplateItem item : items) {
            try { arr.put(item.toJson()); } catch (Exception ignored) {}
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CARDS, arr.toString())
                .putLong(KEY_UPDATED_AT, updatedAt)
                .apply();
    }

    private void loadTemplates() {
        if (refreshing) return;
        String base = baseUrl();
        if (base.isEmpty()) { setStatus("Enter ComfyUI URL first, then refresh templates."); return; }
        refreshing = true;
        setStatus("Refreshing templates index...");
        new Thread(() -> {
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
            } catch (Exception e) {
                warnings.add("default templates: " + shortErr(e));
            }
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
                        item.valid = true;
                        loaded.add(item);
                    }
                }
            } catch (Exception e) {
                warnings.add("custom templates: " + shortErr(e));
            }

            long updatedAt = System.currentTimeMillis();
            String warning = joinWarnings(warnings);
            ui.post(() -> {
                refreshing = false;
                if (!loaded.isEmpty()) {
                    templates.clear();
                    templates.addAll(loaded);
                    lastUpdatedAt = updatedAt;
                    saveTemplateCards(loaded, updatedAt);
                    renderLimit = PAGE_SIZE;
                    renderTemplates();
                    setStatus("Loaded " + loaded.size() + " templates. Preloading workflow JSON..." + warning);
                    preloadTemplateJsons(base, new ArrayList<>(loaded));
                } else {
                    renderTemplates();
                    setStatus("Refresh failed; kept local cache." + warning);
                }
            });
        }).start();
    }

    private void preloadTemplateJsons(String base, ArrayList<TemplateItem> items) {
        if (preloading || items == null || items.isEmpty()) return;
        preloading = true;
        new Thread(() -> {
            int ok = 0;
            int failed = 0;
            for (int i = 0; i < items.size(); i++) {
                TemplateItem item = items.get(i);
                if (item == null || !item.valid || safe(item.name).trim().isEmpty()) continue;
                try {
                    String raw = getText(templateJsonUrl(base, item));
                    writeText(rawTemplateFile(base, item), raw);
                    ok++;
                } catch (Exception e) {
                    failed++;
                }
                if (i % 10 == 0) {
                    int done = i + 1;
                    int count = ok;
                    ui.post(() -> setStatus("Preloading templates: " + done + "/" + items.size() + " cached " + count + "."));
                }
            }
            int finalOk = ok;
            int finalFailed = failed;
            ui.post(() -> {
                preloading = false;
                setStatus("Templates ready. Cached workflows: " + finalOk + (finalFailed > 0 ? ", failed: " + finalFailed + "." : "."));
            });
        }).start();
    }

    private void renderTemplates() {
        if (templateList == null) return;
        templateList.removeAllViews();
        String q = safe(filter).trim().toLowerCase(Locale.US);
        ArrayList<TemplateItem> matches = new ArrayList<>();
        for (TemplateItem item : templates) {
            if (item == null) continue;
            String hay = metadataText(item).toLowerCase(Locale.US);
            if (!q.isEmpty() && !hay.contains(q)) continue;
            matches.add(item);
        }

        int shown = 0;
        int max = Math.min(renderLimit, matches.size());
        for (int i = 0; i < max; i++) {
            templateList.addView(templateCard(matches.get(i)), cardParams());
            shown++;
        }

        if (matches.isEmpty()) {
            templateList.addView(muted(templates.isEmpty() ? "No templates cached yet. Tap Refresh templates." : "Nothing found."));
            return;
        }

        if (matches.size() > shown) {
            LinearLayout more = card(false);
            more.addView(muted("Showing " + shown + " of " + matches.size() + " templates."));
            LinearLayout r = row();
            more.addView(r);
            action(r, "Show more", true, () -> { renderLimit += PAGE_SIZE; renderTemplates(); });
            templateList.addView(more, cardParams());
        } else {
            TextView count = muted("Showing " + shown + " template" + (shown == 1 ? "" : "s") + ".");
            count.setGravity(Gravity.CENTER_HORIZONTAL);
            templateList.addView(count);
        }
    }

    private View templateCard(TemplateItem item) {
        LinearLayout c = card(false);
        c.setClickable(true);
        c.setOnClickListener(v -> openTemplate(item));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        c.addView(row);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(bg(rgb(15, 23, 42), 18, rgb(51, 65, 85), 1));
        img.setTag(item.id());
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(96), dp(96));
        ip.setMargins(0, 0, dp(14), 0);
        row.addView(img, ip);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        row.addView(body, new LinearLayout.LayoutParams(0, -2, 1));
        TextView name = title(shortText(displayTitle(item), 68));
        name.setTextSize(20);
        body.addView(name);
        body.addView(muted(shortText(safe(item.category) + " · " + safe(item.source), 80)));
        if (!safe(item.description).trim().isEmpty()) body.addView(muted(shortText(item.description.replace('_', ' '), 120)));
        if (!item.valid) {
            TextView bad = muted("Template has parsing issues" + (safe(item.error).isEmpty() ? "" : ": " + shortText(item.error, 80)));
            bad.setTextColor(rgb(251, 191, 36));
            body.addView(bad);
        }

        TextView arrow = text("›", 34, rgb(226, 232, 240));
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(28), -1));
        loadPreview(img, item);
        return c;
    }

    private void openTemplate(TemplateItem item) {
        String base = baseUrl();
        if (base.isEmpty()) { setStatus("Enter ComfyUI URL first, then open a template."); return; }
        if (item == null || safe(item.name).trim().isEmpty()) { setStatus("Invalid template item."); return; }
        setStatus("Opening template: " + displayTitle(item));
        new Thread(() -> {
            try {
                String raw = readText(rawTemplateFile(base, item));
                if (raw.trim().isEmpty()) {
                    raw = getText(templateJsonUrl(base, item));
                    writeText(rawTemplateFile(base, item), raw);
                }
                JSONObject graph = new JSONObject(raw);
                JSONObject prompt = extractApiPrompt(graph);
                JSONObject defs = new JSONObject(getText(base + "/api/object_info"));
                JSONObject res = new JSONObject();
                res.put("ok", true);
                res.put("prompt", prompt);
                res.put("options", buildOptions(prompt, defs));
                res.put("mode", "Templates");
                ui.post(() -> importPrompt(res.toString()));
            } catch (Exception e) {
                ui.post(() -> setStatus("Template open failed: " + shortErr(e)));
            }
        }).start();
    }

    private JSONObject extractApiPrompt(JSONObject graph) throws JSONException {
        JSONObject extra = graph.optJSONObject("extra");
        JSONObject prompt = extra == null ? null : extra.optJSONObject("prompt");
        if (prompt != null && prompt.length() > 0) return prompt;
        prompt = graph.optJSONObject("prompt");
        if (prompt != null && prompt.length() > 0) return prompt;
        JSONObject workflow = graph.optJSONObject("workflow");
        if (workflow != null && looksApiPrompt(workflow)) return workflow;
        if (looksApiPrompt(graph)) return graph;
        throw new JSONException("template has no embedded API prompt; graph conversion is not supported for this template yet");
    }

    private boolean looksApiPrompt(JSONObject o) {
        if (o == null) return false;
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            JSONObject n = o.optJSONObject(it.next());
            if (n != null && n.has("class_type")) return true;
        }
        return false;
    }

    private JSONObject buildOptions(JSONObject prompt, JSONObject defs) throws JSONException {
        JSONObject options = new JSONObject();
        Iterator<String> ids = prompt.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            JSONObject node = prompt.optJSONObject(id);
            if (node == null) continue;
            String cls = node.optString("class_type", "");
            JSONObject def = defs.optJSONObject(cls);
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
            if (!(raw instanceof JSONArray)) continue;
            Object first = ((JSONArray) raw).opt(0);
            if (first instanceof JSONArray) out.put(id + ":" + key, first);
        }
    }

    private void loadPreview(ImageView img, TemplateItem item) {
        String base = baseUrl();
        String tag = item.id();
        img.setTag(tag);
        new Thread(() -> {
            try {
                for (String ext : previewExtensions(item)) {
                    byte[] cached = readBytes(previewFile(base, item, ext));
                    if (cached.length > 0) { showPreview(img, tag, cached); return; }
                }
                if (base.isEmpty()) return;
                for (String ext : previewExtensions(item)) {
                    try {
                        byte[] data = bytes(previewUrl(base, item, ext, false), MAX_PREVIEW_BYTES);
                        writeBytes(previewFile(base, item, ext), data);
                        showPreview(img, tag, data);
                        return;
                    } catch (Exception ignored) {}
                    try {
                        byte[] data = bytes(previewUrl(base, item, ext, true), MAX_PREVIEW_BYTES);
                        writeBytes(previewFile(base, item, ext), data);
                        showPreview(img, tag, data);
                        return;
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private ArrayList<String> previewExtensions(TemplateItem item) {
        LinkedHashSet<String> exts = new LinkedHashSet<>();
        String s = safe(item.mediaSubtype).trim().toLowerCase(Locale.US);
        if (!s.isEmpty()) exts.add(s);
        exts.add("webp");
        exts.add("gif");
        exts.add("png");
        exts.add("jpg");
        exts.add("jpeg");
        return new ArrayList<>(exts);
    }

    private void showPreview(ImageView img, String expectedTag, byte[] data) {
        if (data == null || data.length == 0) return;
        ui.post(() -> {
            Object currentTag = img.getTag();
            if (currentTag == null || !String.valueOf(currentTag).equals(expectedTag)) return;
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    Drawable d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(data)));
                    img.setImageDrawable(d);
                    if (d instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) d).start();
                    return;
                } catch (Exception ignored) {}
            }
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                if (bmp != null) img.setImageBitmap(bmp);
            } catch (Exception ignored) {}
        });
    }

    private String templateJsonUrl(String base, TemplateItem item) {
        if ("default".equals(item.source)) return base + "/templates/" + path(item.name) + ".json";
        return base + "/api/workflow_templates/" + path(item.source) + "/" + path(item.name) + ".json";
    }

    private String previewUrl(String base, TemplateItem item, String ext, boolean fallback) {
        if ("default".equals(item.source)) return base + "/templates/" + path(item.name) + (fallback ? "" : "-1") + "." + ext;
        return base + "/api/workflow_templates/" + path(item.source) + "/" + path(item.name) + "." + ext;
    }

    private String getText(String url) throws Exception { return new String(bytes(url, MAX_TEXT_BYTES), "UTF-8"); }

    private byte[] bytes(String url, int maxBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            for (Map.Entry<String, String> e : accessHeaders().entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            int total = 0;
            while (in != null && (n = in.read(b)) > 0) {
                total += n;
                if (total > maxBytes) throw new Exception("response is too large");
                out.write(b, 0, n);
            }
            if (in != null) in.close();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + out.toString("UTF-8"));
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private File cacheDir(String child) {
        File dir = new File(getFilesDir(), "template_cache/" + child);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private File rawTemplateFile(String base, TemplateItem item) {
        return new File(cacheDir("raw"), sha1(base + "|" + item.id()) + ".json");
    }

    private File previewFile(String base, TemplateItem item, String ext) {
        return new File(cacheDir("preview"), sha1(base + "|" + item.id() + "|" + ext) + "." + ext.replaceAll("[^A-Za-z0-9]", ""));
    }

    private String readText(File file) {
        try {
            byte[] data = readBytes(file);
            return data.length == 0 ? "" : new String(data, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private byte[] readBytes(File file) {
        if (file == null || !file.exists() || !file.isFile()) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            FileInputStream in = new FileInputStream(file);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private void writeText(File file, String text) {
        try { writeBytes(file, safe(text).getBytes("UTF-8")); } catch (Exception ignored) {}
    }

    private void writeBytes(File file, byte[] data) {
        if (file == null || data == null || data.length == 0) return;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream out = new FileOutputStream(file);
            out.write(data);
            out.flush();
            out.close();
        } catch (Exception ignored) {}
    }

    private void importPrompt(String resultJson) {
        try {
            Method m = PolishedNodeActivity.class.getDeclaredMethod("handleImportJson", String.class);
            m.setAccessible(true);
            m.invoke(this, resultJson);
            hookTemplateButtons((View) getWindow().getDecorView());
        } catch (Exception e) {
            setStatus("Import failed: " + shortErr(e));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> accessHeaders() throws Exception {
        Method m = EnhancedPolishedActivity.class.getDeclaredMethod("accessHeaders");
        m.setAccessible(true);
        return (Map<String, String>) m.invoke(this);
    }

    private String baseUrl() { try { Object x = callBase("baseUrl"); return x == null ? "" : String.valueOf(x); } catch (Exception e) { return ""; } }
    private Object baseField(String name) throws Exception { Field f = PolishedNodeActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(this); }
    private Object callBase(String name) throws Exception { Method m = PolishedNodeActivity.class.getDeclaredMethod(name); m.setAccessible(true); return m.invoke(this); }
    private void callBaseQuiet(String name) { try { callBase(name); } catch (Exception ignored) {} }
    private String path(String s) { try { return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20").replace("%2F", "/"); } catch (Exception e) { return s == null ? "" : s; } }
    private String safe(String s) { return s == null ? "" : s; }
    private String safeOpt(JSONObject o, String key, String fallback) { return o == null ? safe(fallback) : o.optString(key, safe(fallback)); }
    private String shortText(String s, int max) { if (s == null) return ""; return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…"; }
    private String shortErr(Exception e) { String s = e == null ? "" : e.getMessage(); if (s == null || s.trim().isEmpty()) s = e == null ? "unknown error" : e.getClass().getSimpleName(); s = s.replace('\n', ' ').replace('\r', ' '); return s.length() > 220 ? s.substring(0, 220) + "…" : s; }
    private String displayTitle(TemplateItem item) { String t = safe(item.title).trim(); return t.isEmpty() ? safe(item.name).trim() : t; }
    private String metadataText(TemplateItem item) { return displayTitle(item) + " " + safe(item.name) + " " + safe(item.description) + " " + safe(item.category) + " " + safe(item.source); }
    private String joinWarnings(ArrayList<String> warnings) { if (warnings == null || warnings.isEmpty()) return ""; StringBuilder sb = new StringBuilder(" Warning: "); for (int i = 0; i < warnings.size(); i++) { if (i > 0) sb.append("; "); sb.append(warnings.get(i)); } return sb.toString(); }
    private String timeAgo(long ts) { long sec = Math.max(0L, (System.currentTimeMillis() - ts) / 1000L); if (sec < 60) return "just now"; long min = sec / 60L; if (min < 60) return min + "m ago"; long h = min / 60L; if (h < 24) return h + "h ago"; return (h / 24L) + "d ago"; }
    private String sha1(String value) { try { MessageDigest md = MessageDigest.getInstance("SHA-1"); byte[] d = md.digest(safe(value).getBytes("UTF-8")); StringBuilder sb = new StringBuilder(); for (byte b : d) sb.append(String.format(Locale.US, "%02x", b & 0xff)); return sb.toString(); } catch (Exception e) { return String.valueOf(safe(value).hashCode()).replace("-", "n"); } }
    private void setStatus(String text) { try { Object status = baseField("status"); if (status instanceof TextView) ((TextView) status).setText(text); } catch (Exception ignored) {} }
    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private int rgb(int r, int g, int b) { return Color.rgb(r, g, b); }
    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(0, dp(8), 0, 0); return r; }
    private LinearLayout card(boolean accent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        if (accent) c.setBackground(bg(rgb(15, 23, 42), 26, rgb(56, 189, 248), 1));
        else c.setBackground(bg(rgb(20, 31, 49), 24, rgb(51, 65, 85), 1));
        return c;
    }
    private LinearLayout.LayoutParams cardParams() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, 0, 0, dp(14)); return p; }
    private TextView title(String t) { TextView v = text(t, 22, Color.WHITE); v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return v; }
    private TextView muted(String t) { return text(t, 14, rgb(148, 163, 184)); }
    private TextView text(String t, int size, int color) { TextView v = new TextView(this); v.setText(t); v.setTextColor(color); v.setTextSize(size); v.setPadding(dp(2), 0, dp(2), dp(8)); return v; }
    private void action(LinearLayout r, String text, boolean primary, Runnable run) { Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(15); b.setBackground(bg(primary ? rgb(37, 99, 235) : rgb(51, 65, 85), 16, primary ? rgb(125, 211, 252) : rgb(71, 85, 105), 1)); b.setOnClickListener(v -> run.run()); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(54), 1); p.setMargins(dp(3), 0, dp(3), 0); r.addView(b, p); }
    private GradientDrawable bg(int color, int radiusDp, int stroke, int strokeDp) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radiusDp)); d.setStroke(dp(strokeDp), stroke); return d; }
}
