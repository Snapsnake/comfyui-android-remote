package com.snapsnake.comfyremote;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebView;
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
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class EnhancedPolishedActivity extends PolishedNodeActivity {
    private static final String PREFS = "comfyui_remote_prefs";
    private static final String FAV_KEY = "favorite_node_ids";
    private static final String KEY_CF_ID = "cf_access_client_id";
    private static final String KEY_CF_SECRET = "cf_access_client_secret";
    private static final String KEY_OUTPUT = "last_output_url";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditText nodeSearch, cfIdInput, cfSecretInput;
    private LinearLayout nodeList, outputList;
    private String currentQuery = "", currentPromptId = "";
    private int pollCount = 0;

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
        addCloudflarePanel();
        overrideButtons((View) getWindow().getDecorView());
    }

    private void addCloudflarePanel() {
        LinearLayout top = (LinearLayout) getPrivate("topPanel");
        if (top == null) return;
        SharedPreferences p = getSharedPreferences(PREFS, 0);
        TextView label = muted("Cloudflare Access optional. Fill only after Access protection is enabled.");
        label.setTextSize(12);
        top.addView(label);
        cfIdInput = smallInput("CF-Access-Client-Id", p.getString(KEY_CF_ID, ""), false);
        top.addView(cfIdInput, new LinearLayout.LayoutParams(-1, dp(42)));
        cfSecretInput = smallInput("CF-Access-Client-Secret", p.getString(KEY_CF_SECRET, ""), true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(42));
        sp.setMargins(0, dp(6), 0, 0);
        top.addView(cfSecretInput, sp);
    }

    private EditText smallInput(String hint, String value, boolean secret) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(value == null ? "" : value);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(rgb(148, 163, 184));
        e.setHint(hint);
        e.setTextSize(13);
        e.setPadding(dp(12), 0, dp(12), 0);
        e.setBackground(bg(rgb(15, 23, 42), 12, rgb(71, 85, 105), 1));
        e.setInputType(secret ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD) : InputType.TYPE_CLASS_TEXT);
        return e;
    }

    private void overrideButtons(View v) {
        if (v instanceof Button) {
            Button b = (Button) v;
            String t = String.valueOf(b.getText()).trim();
            if ("Test".equalsIgnoreCase(t)) b.setOnClickListener(x -> testConnectionWithAccess());
            if ("Run".equalsIgnoreCase(t)) b.setOnClickListener(x -> runWorkflowWithAccess());
            if ("Graph".equalsIgnoreCase(t)) b.setOnClickListener(x -> showGraphWithAccess());
            if ("Import".equalsIgnoreCase(t)) b.setOnClickListener(x -> importFromGraphWithAccess());
            if ("Nodes".equalsIgnoreCase(t)) b.setOnClickListener(x -> showNodeSearch());
            if ("Output".equalsIgnoreCase(t)) b.setOnClickListener(x -> showRecentOutputs());
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) overrideButtons(g.getChildAt(i));
        }
    }

    private void saveConnectionPrefs() {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, 0).edit();
        if (cfIdInput != null) e.putString(KEY_CF_ID, cfIdInput.getText().toString().trim());
        if (cfSecretInput != null) e.putString(KEY_CF_SECRET, cfSecretInput.getText().toString().trim());
        e.apply();
        callQuiet("saveUrl");
    }

    private Map<String, String> accessHeaders() {
        saveConnectionPrefs();
        Map<String, String> h = new HashMap<>();
        String id = cfIdInput == null ? "" : cfIdInput.getText().toString().trim();
        String sec = cfSecretInput == null ? "" : cfSecretInput.getText().toString().trim();
        if (!id.isEmpty() && !sec.isEmpty()) {
            h.put("CF-Access-Client-Id", id);
            h.put("CF-Access-Client-Secret", sec);
        }
        return h;
    }

    private void addAccessHeaders(HttpURLConnection c) {
        Map<String, String> h = accessHeaders();
        for (String k : h.keySet()) c.setRequestProperty(k, h.get(k));
    }

    private void addAccessHeaders(DownloadManager.Request r) {
        Map<String, String> h = accessHeaders();
        for (String k : h.keySet()) r.addRequestHeader(k, h.get(k));
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
        if (wf == null || wf.length() == 0) { nodeList.addView(muted("No workflow imported.")); return; }
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
        } catch (Exception e) { Toast.makeText(this, "Could not open node", Toast.LENGTH_SHORT).show(); }
    }

    private void showGraphWithAccess() {
        saveConnectionPrefs();
        String base = baseUrl();
        if (base.isEmpty()) { Toast.makeText(this, "Enter ComfyUI URL first", Toast.LENGTH_SHORT).show(); return; }
        try {
            ((View) getPrivate("pane")).setVisibility(View.GONE);
            ((View) getPrivate("output")).setVisibility(View.GONE);
            WebView g = (WebView) getPrivate("graph");
            g.setVisibility(View.VISIBLE);
            Object top = getPrivate("topPanel");
            if (top instanceof View) ((View) top).setVisibility(View.GONE);
            g.loadUrl(base, accessHeaders());
            setStatus("Graph mode through Cloudflare. Load workflow, then Import.");
            callQuiet("applyBars");
        } catch (Exception e) { setStatus("Graph failed: " + shortErr(e)); }
    }

    private void importFromGraphWithAccess() {
        saveConnectionPrefs();
        WebView g = (WebView) getPrivate("graph");
        String base = baseUrl();
        String cur = g == null ? null : g.getUrl();
        if (cur == null || base.isEmpty() || !cur.startsWith(base) || cur.contains("/view")) {
            showGraphWithAccess();
            Toast.makeText(this, "Graph opened. Load workflow, then press Import again.", Toast.LENGTH_SHORT).show();
            return;
        }
        callQuiet("importFromGraph");
        overrideButtons((View) getWindow().getDecorView());
    }

    private void testConnectionWithAccess() {
        saveConnectionPrefs();
        String base = baseUrl();
        if (base.isEmpty()) { Toast.makeText(this, "Enter ComfyUI URL first", Toast.LENGTH_SHORT).show(); return; }
        setStatus("Testing Cloudflare + ComfyUI API...");
        new Thread(() -> {
            try {
                get(base + "/system_stats");
                handler.post(() -> setStatus("Connection OK: Cloudflare/API headers accepted."));
            } catch (Exception e) {
                handler.post(() -> setStatus("Connection failed: " + shortErr(e)));
            }
        }).start();
    }

    private void runWorkflowWithAccess() {
        JSONObject wf = workflow();
        if (wf == null) { Toast.makeText(this, "Import workflow first", Toast.LENGTH_SHORT).show(); return; }
        saveConnectionPrefs();
        callQuiet("applyFields");
        callQuiet("saveWorkflow");
        String base = baseUrl();
        if (base.isEmpty()) { Toast.makeText(this, "Enter ComfyUI URL first", Toast.LENGTH_SHORT).show(); return; }
        try {
            JSONObject payload = new JSONObject();
            payload.put("prompt", wf);
            payload.put("client_id", "android-remote-" + System.currentTimeMillis());
            setStatus("Sending prompt through Cloudflare...");
            new Thread(() -> {
                try {
                    JSONObject res = new JSONObject(post(base + "/prompt", payload.toString()));
                    currentPromptId = res.optString("prompt_id", "");
                    if (currentPromptId.isEmpty()) throw new IllegalStateException("ComfyUI did not return prompt_id");
                    pollCount = 0;
                    handler.post(() -> setStatus("Queued. Waiting for output..."));
                    pollHistoryWithAccess(base, currentPromptId);
                } catch (Exception e) {
                    handler.post(() -> setStatus("Run failed: " + shortErr(e)));
                }
            }).start();
        } catch (Exception e) { setStatus("Could not build prompt JSON: " + shortErr(e)); }
    }

    private void pollHistoryWithAccess(String base, String pid) {
        pollCount++;
        new Thread(() -> {
            try {
                ArrayList<OutItem> items = parseOutputs(new JSONObject(get(base + "/history/" + enc(pid))));
                if (!items.isEmpty()) {
                    OutItem item = items.get(0);
                    String url = outputUrl(base, item);
                    getSharedPreferences(PREFS, 0).edit().putString(KEY_OUTPUT, url).apply();
                    handler.post(() -> setStatus("Output ready: " + item.filename));
                    return;
                }
            } catch (Exception ignored) {}
            if (pollCount < 240) handler.postDelayed(() -> pollHistoryWithAccess(base, pid), 2000);
            else handler.post(() -> setStatus("Timed out waiting for output."));
        }).start();
    }

    private void showRecentOutputs() {
        showPaneOnly();
        setStatus("Output. Recent videos and images.");
        LinearLayout content = content();
        content.removeAllViews();
        content.addView(title("Recent outputs"));
        content.addView(muted("Latest videos/images from ComfyUI history. Open or download any item to your phone."));
        LinearLayout tools = card(false);
        content.addView(tools, cardParams());
        LinearLayout r = row();
        tools.addView(r);
        addAction(r, "Refresh", true, this::loadRecentOutputs);
        addAction(r, "Open latest", false, this::openLatestOutputWithAccess);
        outputList = new LinearLayout(this);
        outputList.setOrientation(LinearLayout.VERTICAL);
        content.addView(outputList, new LinearLayout.LayoutParams(-1, -2));
        loadRecentOutputs();
    }

    private void loadRecentOutputs() {
        String base = baseUrl();
        if (base.isEmpty()) { setStatus("Enter ComfyUI URL first."); return; }
        setStatus("Loading recent outputs...");
        new Thread(() -> {
            try {
                ArrayList<OutItem> items = parseOutputs(new JSONObject(get(base + "/history")));
                handler.post(() -> renderOutputs(base, items));
            } catch (Exception e) { handler.post(() -> setStatus("Could not load outputs: " + shortErr(e))); }
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
        LinearLayout list = outputList == null ? content() : outputList;
        list.removeAllViews();
        if (items.isEmpty()) { list.addView(muted("No recent outputs found.")); return; }
        OutItem latestVideo = null;
        for (OutItem item : items) { if (isVideoLike(item)) { latestVideo = item; break; } }
        if (latestVideo != null) {
            LinearLayout quick = card(false);
            list.addView(quick, cardParams());
            quick.addView(title("Latest video"));
            quick.addView(muted(latestVideo.filename));
            LinearLayout qr = row(); quick.addView(qr);
            OutItem f = latestVideo;
            addAction(qr, "Open", true, () -> openOutputUrl(outputUrl(base, f)));
            addAction(qr, "Download", false, () -> downloadOutput(base, f));
        }
        int limit = Math.min(30, items.size());
        for (int i = 0; i < limit; i++) {
            OutItem item = items.get(i);
            LinearLayout card = card(false);
            list.addView(card, cardParams());
            card.addView(title((item.kind.equals("video") ? "Video" : item.kind.equals("gif") ? "GIF" : "Image") + " · " + item.filename));
            card.addView(muted("folder: " + (item.subfolder.isEmpty() ? item.type : item.type + "/" + item.subfolder)));
            LinearLayout actions = row(); card.addView(actions);
            addAction(actions, "Open", true, () -> openOutputUrl(outputUrl(base, item)));
            addAction(actions, "Download", false, () -> downloadOutput(base, item));
        }
        setStatus("Loaded " + limit + " recent outputs.");
    }

    private void openLatestOutputWithAccess() {
        String saved = getSharedPreferences(PREFS, 0).getString(KEY_OUTPUT, "");
        if (saved != null && !saved.trim().isEmpty()) openOutputUrl(saved);
        else loadRecentOutputs();
    }

    private void downloadOutput(String base, OutItem item) {
        try {
            String url = outputUrl(base, item);
            String name = safeFilename(item.filename);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(name);
            request.setDescription("ComfyUI output");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            request.setMimeType(mimeType(item));
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            addAccessHeaders(request);
            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("DownloadManager unavailable");
            manager.enqueue(request);
            Toast.makeText(this, "Download started: " + name, Toast.LENGTH_LONG).show();
            setStatus("Downloading to Downloads: " + name);
        } catch (Exception e) {
            setStatus("Download failed: " + shortErr(e));
            Toast.makeText(this, "Download failed: " + shortErr(e), Toast.LENGTH_LONG).show();
        }
    }

    private void openOutputUrl(String url) {
        try {
            Object pane = getPrivate("pane"), graph = getPrivate("graph"), output = getPrivate("output"), top = getPrivate("topPanel");
            if (top instanceof View) ((View) top).setVisibility(View.GONE);
            if (pane instanceof View) ((View) pane).setVisibility(View.GONE);
            if (graph instanceof View) ((View) graph).setVisibility(View.GONE);
            if (output instanceof WebView) {
                WebView w = (WebView) output;
                w.setVisibility(View.VISIBLE);
                Map<String, String> h = accessHeaders();
                if (h.isEmpty()) w.loadUrl(url); else w.loadUrl(url, h);
                setStatus("Opening output...");
            }
        } catch (Exception e) { setStatus("Could not open output: " + shortErr(e)); }
    }

    private boolean isVideoLike(OutItem item) {
        String f = item.filename == null ? "" : item.filename.toLowerCase();
        return "video".equals(item.kind) || "gif".equals(item.kind) || f.endsWith(".mp4") || f.endsWith(".webm") || f.endsWith(".mov") || f.endsWith(".gif");
    }
    private String mimeType(OutItem item) {
        String f = item.filename == null ? "" : item.filename.toLowerCase();
        if (f.endsWith(".mp4")) return "video/mp4";
        if (f.endsWith(".webm")) return "video/webm";
        if (f.endsWith(".mov")) return "video/quicktime";
        if (f.endsWith(".gif")) return "image/gif";
        if (f.endsWith(".jpg") || f.endsWith(".jpeg")) return "image/jpeg";
        if (f.endsWith(".webp")) return "image/webp";
        if (f.endsWith(".png")) return "image/png";
        return isVideoLike(item) ? "video/mp4" : "application/octet-stream";
    }
    private String safeFilename(String name) {
        if (name == null || name.trim().isEmpty()) name = "comfyui_output";
        String clean = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.isEmpty() ? "comfyui_output" : clean;
    }
    private String outputUrl(String base, OutItem item) {
        return base + "/view?filename=" + enc(item.filename) + "&type=" + enc(item.type) + "&subfolder=" + enc(item.subfolder);
    }

    private String post(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000); c.setReadTimeout(30000); c.setDoOutput(true); c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            addAccessHeaders(c);
            OutputStream out = c.getOutputStream(); out.write(body.getBytes("UTF-8")); out.close();
            int code = c.getResponseCode();
            String r = read(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + ": " + r);
            return r;
        } finally { c.disconnect(); }
    }
    private String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000); c.setReadTimeout(20000); addAccessHeaders(c);
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

    private ArrayList<String> visibleIds(JSONObject wf) {
        ArrayList<String> ids = new ArrayList<>();
        Iterator<String> it = wf.keys();
        while (it.hasNext()) {
            String id = it.next(); JSONObject n = wf.optJSONObject(id); if (n == null) continue;
            String cls = n.optString("class_type", "").toLowerCase();
            if (cls.contains("note") || cls.contains("markdown") || cls.contains("reroute")) continue;
            ids.add(id);
        }
        return ids;
    }
    private Set<String> favs() { return new HashSet<>(getSharedPreferences(PREFS, 0).getStringSet(FAV_KEY, new HashSet<>())); }
    private void toggleFav(String id) { Set<String> s = favs(); if (s.contains(id)) s.remove(id); else s.add(id); getSharedPreferences(PREFS, 0).edit().putStringSet(FAV_KEY, s).apply(); }
    private int directCount(JSONObject inputs) { int n = 0; for (String k : inputKeys(inputs)) if (primitive(inputs.opt(k))) n++; return n; }
    private int linkedCount(JSONObject inputs) { int n = 0; for (String k : inputKeys(inputs)) if (inputs.optJSONArray(k) != null) n++; return n; }
    private String preview(JSONObject inputs) { ArrayList<String> p = new ArrayList<>(); for (String k : inputKeys(inputs)) if (primitive(inputs.opt(k))) p.add(k); if (p.isEmpty()) for (String k : inputKeys(inputs)) if (inputs.optJSONArray(k) != null) p.add(k + "→"); if (p.isEmpty()) return "no fields"; String s = ""; for (int i = 0; i < p.size() && i < 3; i++) s += (i == 0 ? "" : ", ") + p.get(i); return p.size() > 3 ? s + "…" : s; }
    private String keysText(JSONObject inputs) { StringBuilder sb = new StringBuilder(); for (String k : inputKeys(inputs)) sb.append(' ').append(k); return sb.toString(); }
    private ArrayList<String> inputKeys(JSONObject o) { ArrayList<String> keys = new ArrayList<>(); if (o == null) return keys; Iterator<String> it = o.keys(); while (it.hasNext()) keys.add(it.next()); Collections.sort(keys); return keys; }
    private boolean primitive(Object v) { return v == JSONObject.NULL || v instanceof String || v instanceof Number || v instanceof Boolean; }
    private String selectedId() { Object x = getPrivate("selectedNodeId"); return x == null ? "" : String.valueOf(x); }
    private JSONObject workflow() { Object x = getPrivate("workflow"); return x instanceof JSONObject ? (JSONObject) x : null; }
    private LinearLayout content() { return (LinearLayout) getPrivate("content"); }
    private String baseUrl() { Object x = callQuiet("baseUrl"); return x == null ? "" : String.valueOf(x); }
    private void showPaneOnly() { try { ((ScrollView) getPrivate("pane")).setVisibility(View.VISIBLE); ((FrameLayout) getPrivate("workspace")).setVisibility(View.VISIBLE); ((View) getPrivate("graph")).setVisibility(View.GONE); ((View) getPrivate("output")).setVisibility(View.GONE); } catch (Exception ignored) {} }
    private void setStatus(String s) { Object x = getPrivate("status"); if (x instanceof TextView) ((TextView) x).setText(s); }
    private Object getPrivate(String name) { try { Field f = PolishedNodeActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(this); } catch (Exception e) { return null; } }
    private void setPrivate(String name, Object val) throws Exception { Field f = PolishedNodeActivity.class.getDeclaredField(name); f.setAccessible(true); f.set(this, val); }
    private Object callQuiet(String name) { try { return call(name); } catch (Exception e) { return null; } }
    private Object call(String name) throws Exception { Method m = PolishedNodeActivity.class.getDeclaredMethod(name); m.setAccessible(true); return m.invoke(this); }
    private String nodeTitle(JSONObject node) { JSONObject meta = node.optJSONObject("_meta"); String t = meta == null ? "" : meta.optString("title", ""); if (t != null && !t.trim().isEmpty()) return pretty(t); return pretty(node.optString("class_type", "Node")); }
    private String pretty(String v) { return v == null ? "Node" : v.replace('_', ' ').replaceAll("([a-z])([A-Z])", "$1 $2").trim(); }
    private String enc(String s) { try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; } }
    private String shortErr(Exception e) { String s = e.getMessage(); return s == null ? e.getClass().getSimpleName() : (s.length() > 220 ? s.substring(0, 220) + "…" : s); }
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