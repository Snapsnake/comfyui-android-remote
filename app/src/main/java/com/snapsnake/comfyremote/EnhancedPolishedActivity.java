package com.snapsnake.comfyremote;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class EnhancedPolishedActivity extends PolishedNodeActivity {
    private static final String FAV_KEY = "favorite_node_ids";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditText nodeSearch;
    private LinearLayout nodeList;
    private String currentQuery = "";

    private static class OutItem {
        String kind, filename, subfolder, type;
        OutItem(String kind, JSONObject o) {
            this.kind = kind;
            filename = o.optString("filename", "");
            subfolder = o.optString("subfolder", "");
            type = o.optString("type", "output");
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        overrideButtons((View) getWindow().getDecorView());
    }

    private void overrideButtons(View v) {
        if (v instanceof Button) {
            Button b = (Button) v;
            String t = String.valueOf(b.getText()).trim();
            if ("Nodes".equalsIgnoreCase(t)) b.setOnClickListener(x -> showNodeSearch());
            if ("Output".equalsIgnoreCase(t)) b.setOnClickListener(x -> showRecentOutputs());
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) overrideButtons(g.getChildAt(i));
        }
    }

    private void showNodeSearch() {
        showPaneOnly();
        setStatus("Nodes. Search, star, tap to edit.");
        LinearLayout content = content();
        content.removeAllViews();
        content.addView(title("Nodes"));
        content.addView(muted("Search by node name, id, class, or field. Star nodes you use often."));
        LinearLayout searchCard = card(false);
        content.addView(searchCard, cardParams());
        nodeSearch = new EditText(this);
        nodeSearch.setSingleLine(true);
        nodeSearch.setText(currentQuery);
        nodeSearch.setTextColor(Color.WHITE);
        nodeSearch.setHintTextColor(rgb(148, 163, 184));
        nodeSearch.setHint("Search nodes…");
        nodeSearch.setTextSize(16);
        nodeSearch.setPadding(dp(14), 0, dp(14), 0);
        nodeSearch.setBackground(bg(rgb(15, 23, 42), 16, rgb(71, 85, 105), 1));
        searchCard.addView(nodeSearch, new LinearLayout.LayoutParams(-1, dp(56)));
        LinearLayout row = row();
        searchCard.addView(row);
        addAction(row, "★ Favorites", false, () -> { currentQuery = "★"; nodeSearch.setText(currentQuery); refreshNodeList(); });
        addAction(row, "Clear", false, () -> { currentQuery = ""; nodeSearch.setText(""); refreshNodeList(); });
        nodeList = new LinearLayout(this);
        nodeList.setOrientation(LinearLayout.VERTICAL);
        content.addView(nodeList, new LinearLayout.LayoutParams(-1, -2));
        nodeSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { currentQuery = String.valueOf(s); refreshNodeList(); }
            public void afterTextChanged(Editable e) {}
        });
        refreshNodeList();
    }

    private void refreshNodeList() {
        if (nodeList == null) return;
        nodeList.removeAllViews();
        JSONObject wf = workflow();
        if (wf == null || wf.length() == 0) {
            nodeList.addView(muted("No workflow imported."));
            return;
        }
        Set<String> favs = favs();
        String q = currentQuery == null ? "" : currentQuery.trim().toLowerCase();
        boolean favOnly = q.equals("★") || q.equals("*") || q.equals("star") || q.equals("fav");
        ArrayList<String> ids = visibleIds(wf);
        Collections.sort(ids, (a, b) -> {
            boolean fa = favs.contains(a), fb = favs.contains(b);
            if (fa != fb) return fa ? -1 : 1;
            try { return Integer.compare(Integer.parseInt(a), Integer.parseInt(b)); } catch (Exception e) { return a.compareTo(b); }
        });
        int shown = 0;
        for (String id : ids) {
            JSONObject n = wf.optJSONObject(id);
            if (n == null) continue;
            if (favOnly && !favs.contains(id)) continue;
            String hay = (id + " " + nodeTitle(n) + " " + n.optString("class_type", "") + " " + keysText(n.optJSONObject("inputs"))).toLowerCase();
            if (!favOnly && !q.isEmpty() && !hay.contains(q)) continue;
            nodeList.addView(nodeCard(id, n, favs.contains(id)));
            shown++;
        }
        if (shown == 0) nodeList.addView(muted("Nothing found."));
    }

    private View nodeCard(String id, JSONObject node, boolean fav) {
        LinearLayout card = card(id.equals(selectedId()));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = title(nodeTitle(node));
        name.setTextSize(17);
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        Button star = smallButton(fav ? "★" : "☆", fav ? rgb(37, 99, 235) : rgb(51, 65, 85));
        star.setOnClickListener(v -> { toggleFav(id); refreshNodeList(); });
        head.addView(star, new LinearLayout.LayoutParams(dp(54), dp(48)));
        card.addView(head);
        card.addView(muted("#" + id + " · " + pretty(node.optString("class_type", "Node"))));
        JSONObject inputs = node.optJSONObject("inputs");
        LinearLayout chips = row();
        card.addView(chips);
        chip(chips, directCount(inputs) + " direct");
        chip(chips, linkedCount(inputs) + " linked");
        chip(chips, preview(inputs));
        card.setOnClickListener(v -> openNode(id));
        return card;
    }

    private void openNode(String id) {
        try {
            setPrivate("selectedNodeId", id);
            call("showCreate");
            overrideButtons((View) getWindow().getDecorView());
        } catch (Exception e) {
            Toast.makeText(this, "Could not open node", Toast.LENGTH_SHORT).show();
        }
    }

    private void showRecentOutputs() {
        showPaneOnly();
        setStatus("Output. Recent videos and images.");
        LinearLayout content = content();
        content.removeAllViews();
        content.addView(title("Recent outputs"));
        content.addView(muted("Latest videos/images from ComfyUI history. Tap any item to open it."));
        LinearLayout tools = card(false);
        content.addView(tools, cardParams());
        LinearLayout r = row();
        tools.addView(r);
        addAction(r, "Refresh", true, this::loadRecentOutputs);
        addAction(r, "Open latest", false, () -> callQuiet("openOutput"));
        loadRecentOutputs();
    }

    private void loadRecentOutputs() {
        String base = baseUrl();
        if (base.isEmpty()) { setStatus("Enter ComfyUI URL first."); return; }
        setStatus("Loading recent outputs...");
        new Thread(() -> {
            try {
                String body = get(base + "/history");
                ArrayList<OutItem> items = parseOutputs(new JSONObject(body));
                handler.post(() -> renderOutputs(base, items));
            } catch (Exception e) {
                handler.post(() -> setStatus("Could not load outputs: " + shortErr(e)));
            }
        }).start();
    }

    private ArrayList<OutItem> parseOutputs(JSONObject history) {
        ArrayList<OutItem> out = new ArrayList<>();
        Iterator<String> it = history.keys();
        while (it.hasNext()) {
            JSONObject item = history.optJSONObject(it.next());
            JSONObject outputs = item == null ? null : item.optJSONObject("outputs");
            if (outputs == null) continue;
            Iterator<String> ns = outputs.keys();
            while (ns.hasNext()) {
                JSONObject o = outputs.optJSONObject(ns.next());
                if (o == null) continue;
                addOutputArray(out, "video", o.optJSONArray("videos"));
                addOutputArray(out, "gif", o.optJSONArray("gifs"));
                addOutputArray(out, "image", o.optJSONArray("images"));
            }
        }
        Collections.reverse(out);
        return out;
    }

    private void addOutputArray(ArrayList<OutItem> out, String kind, JSONArray arr) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && !o.optString("filename", "").isEmpty()) out.add(new OutItem(kind, o));
        }
    }

    private void renderOutputs(String base, ArrayList<OutItem> items) {
        LinearLayout content = content();
        if (items.isEmpty()) { content.addView(muted("No recent outputs found.")); return; }
        int limit = Math.min(30, items.size());
        for (int i = 0; i < limit; i++) {
            OutItem item = items.get(i);
            LinearLayout card = card(false);
            content.addView(card, cardParams());
            card.addView(title((item.kind.equals("video") ? "Video" : item.kind.equals("gif") ? "GIF" : "Image") + " · " + item.filename));
            card.addView(muted("folder: " + (item.subfolder.isEmpty() ? item.type : item.type + "/" + item.subfolder)));
            Button open = largeButton("Open", rgb(37, 99, 235));
            open.setOnClickListener(v -> openOutputUrl(outputUrl(base, item)));
            card.addView(open, new LinearLayout.LayoutParams(-1, dp(54)));
        }
        setStatus("Loaded " + limit + " recent outputs.");
    }

    private void openOutputUrl(String url) {
        try {
            Method m = PolishedNodeActivity.class.getDeclaredMethod("showOutput", String.class);
            m.setAccessible(true);
            m.invoke(this, url);
        } catch (Exception e) { setStatus("Could not open output."); }
    }

    private String outputUrl(String base, OutItem item) {
        return base + "/view?filename=" + enc(item.filename) + "&type=" + enc(item.type) + "&subfolder=" + enc(item.subfolder);
    }

    private ArrayList<String> visibleIds(JSONObject wf) {
        ArrayList<String> ids = new ArrayList<>();
        Iterator<String> it = wf.keys();
        while (it.hasNext()) {
            String id = it.next();
            JSONObject n = wf.optJSONObject(id);
            if (n == null) continue;
            String cls = n.optString("class_type", "").toLowerCase();
            if (cls.contains("note") || cls.contains("markdown") || cls.contains("reroute")) continue;
            ids.add(id);
        }
        return ids;
    }

    private Set<String> favs() {
        return new HashSet<>(getSharedPreferences("comfyui_remote_prefs", 0).getStringSet(FAV_KEY, new HashSet<>()));
    }
    private void toggleFav(String id) {
        Set<String> s = favs();
        if (s.contains(id)) s.remove(id); else s.add(id);
        getSharedPreferences("comfyui_remote_prefs", 0).edit().putStringSet(FAV_KEY, s).apply();
    }

    private int directCount(JSONObject inputs) {
        int n = 0; for (String k : inputKeys(inputs)) if (primitive(inputs.opt(k))) n++; return n;
    }
    private int linkedCount(JSONObject inputs) {
        int n = 0; for (String k : inputKeys(inputs)) if (inputs.optJSONArray(k) != null) n++; return n;
    }
    private String preview(JSONObject inputs) {
        ArrayList<String> p = new ArrayList<>();
        for (String k : inputKeys(inputs)) if (primitive(inputs.opt(k))) p.add(k);
        if (p.isEmpty()) for (String k : inputKeys(inputs)) if (inputs.optJSONArray(k) != null) p.add(k + "→");
        if (p.isEmpty()) return "no fields";
        String s = "";
        for (int i = 0; i < p.size() && i < 3; i++) s += (i == 0 ? "" : ", ") + p.get(i);
        return p.size() > 3 ? s + "…" : s;
    }
    private String keysText(JSONObject inputs) {
        StringBuilder sb = new StringBuilder();
        for (String k : inputKeys(inputs)) sb.append(' ').append(k);
        return sb.toString();
    }
    private ArrayList<String> inputKeys(JSONObject o) {
        ArrayList<String> keys = new ArrayList<>();
        if (o == null) return keys;
        Iterator<String> it = o.keys();
        while (it.hasNext()) keys.add(it.next());
        Collections.sort(keys);
        return keys;
    }
    private boolean primitive(Object v) { return v == JSONObject.NULL || v instanceof String || v instanceof Number || v instanceof Boolean; }
    private String selectedId() { Object x = getPrivate("selectedNodeId"); return x == null ? "" : String.valueOf(x); }
    private JSONObject workflow() { Object x = getPrivate("workflow"); return x instanceof JSONObject ? (JSONObject) x : null; }
    private LinearLayout content() { return (LinearLayout) getPrivate("content"); }
    private String baseUrl() { Object x = callQuiet("baseUrl"); return x == null ? "" : String.valueOf(x); }
    private void showPaneOnly() {
        try {
            ((ScrollView) getPrivate("pane")).setVisibility(View.VISIBLE);
            ((FrameLayout) getPrivate("workspace")).setVisibility(View.VISIBLE);
            ((View) getPrivate("graph")).setVisibility(View.GONE);
            ((View) getPrivate("output")).setVisibility(View.GONE);
        } catch (Exception ignored) {}
    }
    private void setStatus(String s) { Object x = getPrivate("status"); if (x instanceof TextView) ((TextView) x).setText(s); }
    private Object getPrivate(String name) {
        try { Field f = PolishedNodeActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(this); }
        catch (Exception e) { return null; }
    }
    private void setPrivate(String name, Object val) throws Exception { Field f = PolishedNodeActivity.class.getDeclaredField(name); f.setAccessible(true); f.set(this, val); }
    private Object callQuiet(String name) { try { return call(name); } catch (Exception e) { return null; } }
    private Object call(String name) throws Exception { Method m = PolishedNodeActivity.class.getDeclaredMethod(name); m.setAccessible(true); return m.invoke(this); }

    private String nodeTitle(JSONObject node) {
        JSONObject meta = node.optJSONObject("_meta");
        String t = meta == null ? "" : meta.optString("title", "");
        if (t != null && !t.trim().isEmpty()) return pretty(t);
        return pretty(node.optString("class_type", "Node"));
    }
    private String pretty(String v) { return v == null ? "Node" : v.replace('_', ' ').replaceAll("([a-z])([A-Z])", "$1 $2").trim(); }
    private String enc(String s) { try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; } }
    private String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000); c.setReadTimeout(20000);
            int code = c.getResponseCode();
            String body = read(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + body);
            return body;
        } finally { c.disconnect(); }
    }
    private String read(InputStream in) throws Exception {
        if (in == null) return "";
        try { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); return out.toString("UTF-8"); }
        finally { in.close(); }
    }
    private String shortErr(Exception e) { String s = e.getMessage(); return s == null ? e.getClass().getSimpleName() : (s.length() > 160 ? s.substring(0, 160) + "…" : s); }

    private int rgb(int r, int g, int b) { return Color.rgb(r, g, b); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(0, dp(8), 0, 0); return r; }
    private LinearLayout card(boolean accent) { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(14), dp(14), dp(14), dp(14)); c.setBackground(bg(accent ? rgb(30,64,125) : rgb(30,41,59), 24, accent ? rgb(96,165,250) : rgb(71,85,105), accent ? 2 : 1)); return c; }
    private LinearLayout.LayoutParams cardParams() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, 0, 0, dp(12)); return p; }
    private TextView title(String t) { TextView v = new TextView(this); v.setText(t); v.setTextColor(Color.WHITE); v.setTextSize(20); v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); v.setPadding(dp(2), 0, dp(2), dp(8)); return v; }
    private TextView muted(String t) { TextView v = new TextView(this); v.setText(t); v.setTextColor(rgb(148,163,184)); v.setTextSize(14); v.setPadding(dp(2), 0, dp(2), dp(8)); return v; }
    private void chip(LinearLayout r, String s) { TextView v = muted(s); v.setTextColor(rgb(226,232,240)); v.setGravity(Gravity.CENTER); v.setSingleLine(true); v.setBackground(bg(rgb(15,23,42), 14, rgb(71,85,105), 1)); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(34), 1); p.setMargins(0, 0, dp(6), 0); r.addView(v, p); }
    private void addAction(LinearLayout r, String text, boolean primary, Runnable action) { Button b = largeButton(text, primary ? rgb(37,99,235) : rgb(51,65,85)); b.setOnClickListener(v -> action.run()); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(54), 1); p.setMargins(dp(3), 0, dp(3), 0); r.addView(b, p); }
    private Button largeButton(String t, int color) { Button b = new Button(this); b.setText(t); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(15); b.setBackground(bg(color, 16, rgb(71,85,105), 1)); return b; }
    private Button smallButton(String t, int color) { Button b = largeButton(t, color); b.setTextSize(24); return b; }
    private GradientDrawable bg(int color, int radiusDp, int stroke, int strokeDp) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radiusDp)); d.setStroke(dp(strokeDp), stroke); return d; }
    private void bars() { Window w = getWindow(); w.setStatusBarColor(rgb(2,6,23)); w.setNavigationBarColor(rgb(15,23,42)); }
}