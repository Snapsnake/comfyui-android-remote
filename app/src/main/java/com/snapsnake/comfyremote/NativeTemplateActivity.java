package com.snapsnake.comfyremote;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class NativeTemplateActivity extends EnhancedPolishedActivity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<TemplateItem> templates = new ArrayList<>();
    private LinearLayout templateList;
    private EditText templateSearch;
    private String query = "";

    private static class TemplateItem { String source, name, title, desc, subtype, category; }
    private static class LinkRef { String node; int slot; LinkRef(String node, int slot) { this.node = node; this.slot = slot; } }

    @Override protected void onCreate(Bundle state) { super.onCreate(state); overrideTemplateButtons((View) getWindow().getDecorView()); }
    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) overrideTemplateButtons((View) getWindow().getDecorView()); }

    private void overrideTemplateButtons(View v) {
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
            for (int i = 0; i < g.getChildCount(); i++) overrideTemplateButtons(g.getChildAt(i));
        }
    }

    private void showTemplates() {
        try {
            callBase("saveUrl");
            String base = baseUrl();
            if (base.isEmpty()) { toast("Enter ComfyUI URL first"); return; }
            View pane = (View) baseField("pane");
            View graph = (View) baseField("graph");
            View output = (View) baseField("output");
            View top = (View) baseField("topPanel");
            if (top != null) top.setVisibility(View.GONE);
            if (pane != null) pane.setVisibility(View.VISIBLE);
            if (graph != null) graph.setVisibility(View.GONE);
            if (output != null) output.setVisibility(View.GONE);
            LinearLayout content = content();
            content.removeAllViews();
            content.addView(title("Templates"));
            content.addView(muted("Native ComfyUI templates with the same previews. Tap a card to load it into Nodes/Create."));
            LinearLayout tools = card(false);
            content.addView(tools, cardParams());
            templateSearch = new EditText(this);
            templateSearch.setSingleLine(true);
            templateSearch.setText(query);
            templateSearch.setTextColor(Color.WHITE);
            templateSearch.setHintTextColor(rgb(148, 163, 184));
            templateSearch.setHint("Search templates…");
            templateSearch.setTextSize(16);
            templateSearch.setPadding(dp(14), 0, dp(14), 0);
            templateSearch.setBackground(bg(rgb(15, 23, 42), 16, rgb(71, 85, 105), 1));
            tools.addView(templateSearch, new LinearLayout.LayoutParams(-1, dp(54)));
            LinearLayout row = row();
            tools.addView(row);
            action(row, "Refresh", true, this::loadTemplates);
            action(row, "Clear", false, () -> { query = ""; templateSearch.setText(""); renderTemplateList(); });
            templateSearch.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                public void onTextChanged(CharSequence s, int st, int b, int c) { query = String.valueOf(s); renderTemplateList(); }
                public void afterTextChanged(Editable e) {}
            });
            templateList = new LinearLayout(this);
            templateList.setOrientation(LinearLayout.VERTICAL);
            content.addView(templateList, new LinearLayout.LayoutParams(-1, -2));
            setStatus("Templates screen. Loading from ComfyUI API...");
            if (templates.isEmpty()) loadTemplates(); else renderTemplateList();
            callBaseQuiet("applyBars");
        } catch (Exception e) { setStatus("Templates failed: " + shortErr(e)); }
    }

    private void loadTemplates() {
        final String base = baseUrl();
        if (base.isEmpty()) { setStatus("Enter ComfyUI URL first."); return; }
        setStatus("Loading /templates/index.json and /api/workflow_templates...");
        new Thread(() -> {
            ArrayList<TemplateItem> loaded = new ArrayList<>();
            String warning = null;
            try {
                JSONArray core = new JSONArray(getText(base + "/templates/index.json"));
                for (int i = 0; i < core.length(); i++) {
                    JSONObject cat = core.optJSONObject(i);
                    if (cat == null) continue;
                    String source = cat.optString("moduleName", "default");
                    String category = cat.optString("localizedTitle", cat.optString("title", source));
                    JSONArray arr = cat.optJSONArray("templates");
                    if (arr == null) continue;
                    for (int j = 0; j < arr.length(); j++) {
                        JSONObject t = arr.optJSONObject(j);
                        if (t == null) continue;
                        TemplateItem item = new TemplateItem();
                        item.source = source == null || source.isEmpty() ? "default" : source;
                        item.name = t.optString("name", "");
                        item.title = t.optString("localizedTitle", t.optString("title", item.name));
                        item.desc = t.optString("localizedDescription", t.optString("description", ""));
                        item.subtype = t.optString("mediaSubtype", "webp");
                        item.category = category;
                        if (!item.name.isEmpty()) loaded.add(item);
                    }
                }
            } catch (Exception e) { warning = "Core templates failed: " + shortErr(e); }
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
                        item.desc = module;
                        item.subtype = "jpg";
                        item.category = "Custom Nodes";
                        loaded.add(item);
                    }
                }
            } catch (Exception e) { if (warning == null) warning = "Custom templates failed: " + shortErr(e); }
            final String warn = warning;
            ui.post(() -> {
                templates.clear();
                templates.addAll(loaded);
                renderTemplateList();
                setStatus("Loaded " + templates.size() + " templates" + (warn == null ? "." : ". " + warn));
            });
        }).start();
    }

    private void renderTemplateList() {
        if (templateList == null) return;
        templateList.removeAllViews();
        String q = query == null ? "" : query.trim().toLowerCase();
        int shown = 0;
        for (TemplateItem item : templates) {
            String hay = (item.title + " " + item.name + " " + item.desc + " " + item.category + " " + item.source).toLowerCase();
            if (!q.isEmpty() && !hay.contains(q)) continue;
            templateList.addView(templateCard(item));
            shown++;
        }
        if (shown == 0) templateList.addView(muted(templates.isEmpty() ? "No templates loaded yet." : "Nothing found."));
    }

    private View templateCard(TemplateItem item) {
        LinearLayout c = card(false);
        c.addView(title(shortText(item.title, 70)));
        c.addView(muted(item.category + " · " + item.source));
        ImageView img = new ImageView(this);
        img.setBackground(bg(rgb(15, 23, 42), 18, rgb(51, 65, 85), 1));
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, dp(160));
        ip.setMargins(0, dp(8), 0, dp(10));
        c.addView(img, ip);
        if (item.desc != null && !item.desc.trim().isEmpty()) c.addView(muted(shortText(item.desc.replace('_', ' '), 130)));
        LinearLayout r = row();
        c.addView(r);
        action(r, "Open", true, () -> openTemplate(item));
        action(r, "Name", false, () -> Toast.makeText(this, item.name, Toast.LENGTH_LONG).show());
        loadThumb(img, item);
        return c;
    }

    private void openTemplate(TemplateItem item) {
        final String base = baseUrl();
        setStatus("Loading template: " + item.title);
        new Thread(() -> {
            try {
                JSONObject defs = new JSONObject(getText(base + "/api/object_info"));
                JSONObject graph = new JSONObject(getText(templateJsonUrl(base, item)));
                JSONObject prompt = looksApiPrompt(graph) ? graph : graphToPrompt(graph, defs);
                JSONObject res = new JSONObject();
                res.put("ok", true);
                res.put("prompt", prompt);
                res.put("options", buildOptions(prompt, defs));
                res.put("mode", "Templates");
                ui.post(() -> importPrompt(res.toString()));
            } catch (Exception e) { ui.post(() -> setStatus("Template load failed: " + shortErr(e))); }
        }).start();
    }

    private JSONObject graphToPrompt(JSONObject graph, JSONObject defs) throws JSONException {
        JSONArray nodes = graph.optJSONArray("nodes");
        if (nodes == null && graph.optJSONObject("workflow") != null) nodes = graph.optJSONObject("workflow").optJSONArray("nodes");
        if (nodes == null) throw new JSONException("Template graph has no nodes array");
        Map<String, LinkRef> links = parseLinks(graph);
        JSONObject out = new JSONObject();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n == null) continue;
            String id = String.valueOf(n.opt("id"));
            String cls = n.optString("type", n.optString("comfyClass", ""));
            if (id == null || id.equals("null") || cls.isEmpty() || nonRunnable(cls)) continue;
            JSONObject item = new JSONObject();
            JSONObject inputs = new JSONObject();
            JSONObject meta = new JSONObject();
            meta.put("title", n.optString("title", cls));
            Set<String> linkedNames = new HashSet<>();
            JSONArray ins = n.optJSONArray("inputs");
            if (ins != null) {
                for (int k = 0; k < ins.length(); k++) {
                    JSONObject inp = ins.optJSONObject(k);
                    if (inp == null) continue;
                    String name = inp.optString("name", "");
                    Object linkObj = inp.opt("link");
                    if (name.isEmpty() || linkObj == null || linkObj == JSONObject.NULL) continue;
                    LinkRef ref = links.get(String.valueOf(linkObj));
                    if (ref != null && ref.node != null && !ref.node.isEmpty()) {
                        JSONArray a = new JSONArray();
                        a.put(ref.node);
                        a.put(ref.slot);
                        inputs.put(name, a);
                        linkedNames.add(name);
                    }
                }
            }
            ArrayList<String> names = widgetNames(n, cls, defs, linkedNames);
            JSONArray vals = n.optJSONArray("widgets_values");
            if (vals != null) {
                for (int w = 0; w < vals.length() && w < names.size(); w++) {
                    String key = names.get(w);
                    if (key == null || key.isEmpty() || inputs.has(key)) continue;
                    inputs.put(key, vals.opt(w));
                }
            }
            item.put("class_type", cls);
            item.put("inputs", inputs);
            item.put("_meta", meta);
            out.put(id, item);
        }
        if (out.length() == 0) throw new JSONException("Converted template is empty");
        return out;
    }

    private Map<String, LinkRef> parseLinks(JSONObject graph) {
        Map<String, LinkRef> map = new HashMap<>();
        JSONArray arr = graph.optJSONArray("links");
        if (arr == null && graph.optJSONObject("workflow") != null) arr = graph.optJSONObject("workflow").optJSONArray("links");
        if (arr == null) return map;
        for (int i = 0; i < arr.length(); i++) {
            Object raw = arr.opt(i);
            try {
                if (raw instanceof JSONArray) {
                    JSONArray a = (JSONArray) raw;
                    map.put(String.valueOf(a.opt(0)), new LinkRef(String.valueOf(a.opt(1)), a.optInt(2, 0)));
                } else if (raw instanceof JSONObject) {
                    JSONObject o = (JSONObject) raw;
                    String id = String.valueOf(o.opt("id"));
                    String origin = o.optString("origin_id", o.optString("source_id", o.optString("from_id", "")));
                    int slot = o.optInt("origin_slot", o.optInt("source_slot", o.optInt("from_slot", 0)));
                    map.put(id, new LinkRef(origin, slot));
                }
            } catch (Exception ignored) {}
        }
        return map;
    }

    private ArrayList<String> widgetNames(JSONObject node, String cls, JSONObject defs, Set<String> linked) {
        ArrayList<String> names = new ArrayList<>();
        JSONArray widgets = node.optJSONArray("widgets");
        if (widgets != null) {
            for (int i = 0; i < widgets.length(); i++) {
                JSONObject w = widgets.optJSONObject(i);
                if (w == null) continue;
                String name = w.optString("name", "");
                String type = w.optString("type", "").toLowerCase();
                if (!name.isEmpty() && !"upload".equals(name) && !type.contains("button") && !linked.contains(name)) names.add(name);
            }
            if (!names.isEmpty()) return names;
        }
        JSONObject def = defs.optJSONObject(cls);
        JSONObject input = def == null ? null : def.optJSONObject("input");
        addDefNames(names, input == null ? null : input.optJSONObject("required"), linked);
        addDefNames(names, input == null ? null : input.optJSONObject("optional"), linked);
        return names;
    }

    private void addDefNames(ArrayList<String> names, JSONObject section, Set<String> linked) {
        if (section == null) return;
        Iterator<String> it = section.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (!linked.contains(k)) names.add(k);
        }
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

    private boolean looksApiPrompt(JSONObject o) {
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            JSONObject n = o.optJSONObject(it.next());
            if (n != null && n.has("class_type")) return true;
        }
        return false;
    }

    private boolean nonRunnable(String cls) { String s = cls == null ? "" : cls.toLowerCase(); return s.contains("note") || s.contains("markdown") || s.contains("reroute"); }
    private String templateJsonUrl(String base, TemplateItem item) { return "default".equals(item.source) ? base + "/templates/" + path(item.name) + ".json" : base + "/api/workflow_templates/" + path(item.source) + "/" + path(item.name) + ".json"; }
    private String thumbUrl(String base, TemplateItem item, boolean fallback) { String ext = item.subtype == null || item.subtype.isEmpty() ? "webp" : item.subtype; return "default".equals(item.source) ? base + "/templates/" + path(item.name) + (fallback ? "" : "-1") + "." + ext : base + "/api/workflow_templates/" + path(item.source) + "/" + path(item.name) + "." + ext; }

    private void loadThumb(ImageView img, TemplateItem item) {
        final String base = baseUrl();
        new Thread(() -> {
            try {
                byte[] data;
                try { data = bytes(thumbUrl(base, item, false)); } catch (Exception e) { data = bytes(thumbUrl(base, item, true)); }
                Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bmp != null) ui.post(() -> img.setImageBitmap(bmp));
            } catch (Exception ignored) {}
        }).start();
    }

    private String getText(String url) throws Exception { return new String(bytes(url), "UTF-8"); }
    private byte[] bytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000); c.setReadTimeout(30000);
            Map<String, String> h = accessHeaders();
            for (String k : h.keySet()) c.setRequestProperty(k, h.get(k));
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while (in != null && (n = in.read(b)) > 0) out.write(b, 0, n);
            if (in != null) in.close();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + out.toString("UTF-8"));
            return out.toByteArray();
        } finally { c.disconnect(); }
    }

    private void importPrompt(String resultJson) {
        try {
            Method m = PolishedNodeActivity.class.getDeclaredMethod("handleImportJson", String.class);
            m.setAccessible(true);
            m.invoke(this, resultJson);
            overrideTemplateButtons((View) getWindow().getDecorView());
        } catch (Exception e) { setStatus("Import failed: " + shortErr(e)); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> accessHeaders() throws Exception { Method m = EnhancedPolishedActivity.class.getDeclaredMethod("accessHeaders"); m.setAccessible(true); return (Map<String, String>) m.invoke(this); }
    private LinearLayout content() throws Exception { return (LinearLayout) baseField("content"); }
    private String baseUrl() { try { Object x = callBase("baseUrl"); return x == null ? "" : String.valueOf(x); } catch (Exception e) { return ""; } }
    private Object baseField(String name) throws Exception { Field f = PolishedNodeActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(this); }
    private Object callBase(String name) throws Exception { Method m = PolishedNodeActivity.class.getDeclaredMethod(name); m.setAccessible(true); return m.invoke(this); }
    private void callBaseQuiet(String name) { try { callBase(name); } catch (Exception ignored) {} }
    private String path(String s) { try { return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20").replace("%2F", "/"); } catch (Exception e) { return s == null ? "" : s; } }
    private String shortText(String s, int max) { if (s == null) return ""; return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…"; }
    private String shortErr(Exception e) { String s = e.getMessage(); if (s == null || s.trim().isEmpty()) s = e.getClass().getSimpleName(); s = s.replace('\n', ' ').replace('\r', ' '); return s.length() > 220 ? s.substring(0, 220) + "…" : s; }
    private void setStatus(String text) { try { Object s = baseField("status"); if (s instanceof TextView) ((TextView) s).setText(text); } catch (Exception ignored) {} }
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
