package com.snapsnake.comfyremote;

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
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;

public class TemplateBrowserActivity extends EnhancedPolishedActivity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<TemplateItem> templates = new ArrayList<>();
    private LinearLayout templateList;
    private EditText search;
    private String filter = "";

    private static class TemplateItem {
        String source;
        String name;
        String title;
        String description;
        String category;
        String mediaSubtype;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
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
            callBase("saveUrl");
            String base = baseUrl();
            if (base.isEmpty()) { toast("Enter ComfyUI URL first"); return; }
            View top = (View) baseField("topPanel");
            View pane = (View) baseField("pane");
            View graph = (View) baseField("graph");
            View output = (View) baseField("output");
            if (top != null) top.setVisibility(View.GONE);
            if (pane != null) pane.setVisibility(View.VISIBLE);
            if (graph != null) graph.setVisibility(View.GONE);
            if (output != null) output.setVisibility(View.GONE);

            LinearLayout content = (LinearLayout) baseField("content");
            content.removeAllViews();
            content.addView(title("Templates"));
            content.addView(muted("ComfyUI template browser. Uses embedded API prompt from template JSON, so subgraph templates import as full workflows."));

            LinearLayout tools = card(false);
            content.addView(tools, cardParams());
            search = new EditText(this);
            search.setSingleLine(true);
            search.setText(filter);
            search.setHint("Search templates…");
            search.setTextColor(Color.WHITE);
            search.setHintTextColor(rgb(148, 163, 184));
            search.setTextSize(16);
            search.setPadding(dp(14), 0, dp(14), 0);
            search.setBackground(bg(rgb(15, 23, 42), 16, rgb(71, 85, 105), 1));
            tools.addView(search, new LinearLayout.LayoutParams(-1, dp(54)));
            LinearLayout actions = row();
            tools.addView(actions);
            action(actions, "Refresh", true, this::loadTemplates);
            action(actions, "Clear", false, () -> { filter = ""; search.setText(""); renderTemplates(); });
            search.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                public void onTextChanged(CharSequence s, int start, int before, int count) { filter = String.valueOf(s); renderTemplates(); }
                public void afterTextChanged(Editable s) {}
            });

            templateList = new LinearLayout(this);
            templateList.setOrientation(LinearLayout.VERTICAL);
            content.addView(templateList, new LinearLayout.LayoutParams(-1, -2));
            if (templates.isEmpty()) loadTemplates(); else renderTemplates();
            setStatus("Templates screen.");
            callBaseQuiet("applyBars");
        } catch (Exception e) {
            setStatus("Templates failed: " + shortErr(e));
        }
    }

    private void loadTemplates() {
        String base = baseUrl();
        if (base.isEmpty()) { setStatus("Enter ComfyUI URL first."); return; }
        setStatus("Loading ComfyUI templates...");
        new Thread(() -> {
            ArrayList<TemplateItem> loaded = new ArrayList<>();
            String warning = null;
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
                        item.source = source == null || source.isEmpty() ? "default" : source;
                        item.name = raw.optString("name", "");
                        item.title = raw.optString("localizedTitle", raw.optString("title", item.name));
                        item.description = raw.optString("localizedDescription", raw.optString("description", ""));
                        item.category = categoryTitle;
                        item.mediaSubtype = raw.optString("mediaSubtype", "webp");
                        if (!item.name.isEmpty()) loaded.add(item);
                    }
                }
            } catch (Exception e) {
                warning = "default templates: " + shortErr(e);
            }
            try {
                JSONObject custom = new JSONObject(getText(base + "/api/workflow_templates"));
                Iterator<String> it = custom.keys();
                while (it.hasNext()) {
                    String module = it.next();
                    JSONArray arr = custom.optJSONArray(module);
                    if (arr == null) continue;
                    for (int i = 0; i < arr.length(); i++) {
                        String name = arr.optString(i, "");
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
            } catch (Exception e) {
                if (warning == null) warning = "custom templates: " + shortErr(e);
            }
            String warn = warning;
            ui.post(() -> {
                templates.clear();
                templates.addAll(loaded);
                renderTemplates();
                setStatus("Loaded " + loaded.size() + " templates" + (warn == null ? "." : ". Warning: " + warn));
            });
        }).start();
    }

    private void renderTemplates() {
        if (templateList == null) return;
        templateList.removeAllViews();
        String q = filter == null ? "" : filter.trim().toLowerCase();
        int shown = 0;
        for (TemplateItem item : templates) {
            String hay = (item.title + " " + item.name + " " + item.description + " " + item.category + " " + item.source).toLowerCase();
            if (!q.isEmpty() && !hay.contains(q)) continue;
            templateList.addView(templateCard(item));
            shown++;
        }
        if (shown == 0) templateList.addView(muted(templates.isEmpty() ? "No templates loaded." : "Nothing found."));
    }

    private View templateCard(TemplateItem item) {
        LinearLayout c = card(false);
        c.addView(title(shortText(item.title, 70)));
        c.addView(muted(item.category + " · " + item.source));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(bg(rgb(15, 23, 42), 18, rgb(51, 65, 85), 1));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, dp(170));
        ip.setMargins(0, dp(8), 0, dp(10));
        c.addView(img, ip);
        if (item.description != null && !item.description.trim().isEmpty()) c.addView(muted(shortText(item.description.replace('_', ' '), 130)));
        LinearLayout r = row();
        c.addView(r);
        action(r, "Open", true, () -> openTemplate(item));
        action(r, "Name", false, () -> toast(item.name));
        loadPreview(img, item);
        return c;
    }

    private void openTemplate(TemplateItem item) {
        String base = baseUrl();
        setStatus("Opening template: " + item.title);
        new Thread(() -> {
            try {
                JSONObject graph = new JSONObject(getText(templateJsonUrl(base, item)));
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
        if (looksApiPrompt(graph)) return graph;
        throw new JSONException("template has no embedded API prompt; graph conversion is not supported for this template yet");
    }

    private boolean looksApiPrompt(JSONObject o) {
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
        new Thread(() -> {
            try {
                byte[] data = null;
                for (String ext : previewExtensions(item)) {
                    try { data = bytes(previewUrl(base, item, ext, false)); break; } catch (Exception ignored) {}
                    try { data = bytes(previewUrl(base, item, ext, true)); break; } catch (Exception ignored) {}
                }
                if (data != null) showPreview(img, data);
            } catch (Exception ignored) {}
        }).start();
    }

    private ArrayList<String> previewExtensions(TemplateItem item) {
        LinkedHashSet<String> exts = new LinkedHashSet<>();
        String s = item.mediaSubtype == null ? "" : item.mediaSubtype.trim().toLowerCase();
        if (!s.isEmpty()) exts.add(s);
        exts.add("webp");
        exts.add("gif");
        exts.add("png");
        exts.add("jpg");
        exts.add("jpeg");
        return new ArrayList<>(exts);
    }

    private void showPreview(ImageView img, byte[] data) {
        ui.post(() -> {
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    Drawable d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(data)));
                    img.setImageDrawable(d);
                    if (d instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) d).start();
                    return;
                } catch (Exception ignored) {}
            }
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bmp != null) img.setImageBitmap(bmp);
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

    private String getText(String url) throws Exception { return new String(bytes(url), "UTF-8"); }

    private byte[] bytes(String url) throws Exception {
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
            while (in != null && (n = in.read(b)) > 0) out.write(b, 0, n);
            if (in != null) in.close();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + out.toString("UTF-8"));
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
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
    private String shortText(String s, int max) { if (s == null) return ""; return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…"; }
    private String shortErr(Exception e) { String s = e.getMessage(); if (s == null || s.trim().isEmpty()) s = e.getClass().getSimpleName(); s = s.replace('\n', ' ').replace('\r', ' '); return s.length() > 220 ? s.substring(0, 220) + "…" : s; }
    private void setStatus(String text) { try { Object status = baseField("status"); if (status instanceof TextView) ((TextView) status).setText(text); } catch (Exception ignored) {} }
    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private int rgb(int r, int g, int b) { return Color.rgb(r, g, b); }
    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(0, dp(8), 0, 0); return r; }
    private LinearLayout card(boolean accent) { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(14), dp(14), dp(14), dp(14)); c.setBackground(bg(accent ? rgb(30, 64, 125) : rgb(30, 41, 59), 24, accent ? rgb(96, 165, 250) : rgb(71, 85, 105), accent ? 2 : 1)); return c; }
    private LinearLayout.LayoutParams cardParams() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, 0, 0, dp(14)); return p; }
    private TextView title(String t) { TextView v = text(t, 22, Color.WHITE); v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return v; }
    private TextView muted(String t) { return text(t, 14, rgb(148, 163, 184)); }
    private TextView text(String t, int size, int color) { TextView v = new TextView(this); v.setText(t); v.setTextColor(color); v.setTextSize(size); v.setPadding(dp(2), 0, dp(2), dp(8)); return v; }
    private void action(LinearLayout r, String text, boolean primary, Runnable run) { Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(15); b.setBackground(bg(primary ? rgb(37, 99, 235) : rgb(51, 65, 85), 16, rgb(71, 85, 105), 1)); b.setOnClickListener(v -> run.run()); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(54), 1); p.setMargins(dp(3), 0, dp(3), 0); r.addView(b, p); }
    private GradientDrawable bg(int color, int radiusDp, int stroke, int strokeDp) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radiusDp)); d.setStroke(dp(strokeDp), stroke); return d; }
}
