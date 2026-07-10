package com.snapsnake.comfyremote.core;

import org.json.JSONObject;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class ServerProfile {
    public final String id;
    public final String name;
    public final String baseUrl;
    public final String cloudflareClientId;
    public final String cloudflareClientSecret;

    public ServerProfile(String id, String name, String baseUrl, String cloudflareClientId, String cloudflareClientSecret) {
        this.id = empty(id) ? UUID.randomUUID().toString() : id;
        this.name = empty(name) ? hostLabel(baseUrl) : name.trim();
        this.baseUrl = strip(baseUrl);
        this.cloudflareClientId = safe(cloudflareClientId).trim();
        this.cloudflareClientSecret = safe(cloudflareClientSecret).trim();
    }

    public JSONObject toJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("id", id);
            out.put("name", name);
            out.put("baseUrl", baseUrl);
            out.put("cloudflareClientId", cloudflareClientId);
            out.put("cloudflareClientSecret", cloudflareClientSecret);
        } catch (Exception ignored) {}
        return out;
    }

    public static ServerProfile fromJson(JSONObject raw) {
        if (raw == null) return new ServerProfile("", "ComfyUI", "", "", "");
        return new ServerProfile(
                raw.optString("id", ""),
                raw.optString("name", ""),
                raw.optString("baseUrl", raw.optString("url", "")),
                raw.optString("cloudflareClientId", raw.optString("cf_id", "")),
                raw.optString("cloudflareClientSecret", raw.optString("cf_secret", ""))
        );
    }

    public boolean hasCloudflareAccess() {
        return !cloudflareClientId.isEmpty() && !cloudflareClientSecret.isEmpty();
    }

    public List<String> candidateBaseUrls() {
        ArrayList<String> out = new ArrayList<>();
        String raw = strip(baseUrl);
        if (raw.isEmpty()) return out;
        if (raw.startsWith("//")) raw = raw.substring(2);
        String lower = raw.toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            add(out, raw);
        } else if (looksLocal(raw)) {
            add(out, "http://" + raw);
            add(out, "https://" + raw);
        } else {
            add(out, "https://" + raw);
            add(out, "http://" + raw);
        }
        return out;
    }

    public ServerProfile withResolvedBaseUrl(String resolved) {
        return new ServerProfile(id, name, resolved, cloudflareClientId, cloudflareClientSecret);
    }

    private static void add(ArrayList<String> out, String candidate) {
        String base = strip(candidate);
        try {
            URL u = new URL(base);
            if (u.getHost() != null && !u.getHost().trim().isEmpty() && !out.contains(base)) out.add(base);
        } catch (Exception ignored) {}
    }

    private static boolean looksLocal(String raw) {
        String s = safe(raw).toLowerCase(Locale.US);
        return s.startsWith("192.168.") || s.startsWith("10.") || s.startsWith("127.") ||
                s.startsWith("localhost") || s.contains(".local") ||
                s.matches("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*") || s.endsWith(":8188");
    }

    private static String hostLabel(String value) {
        String raw = strip(value);
        if (raw.isEmpty()) return "ComfyUI";
        try {
            String normalized = raw.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*") ? raw : "https://" + raw;
            String host = new URL(normalized).getHost();
            return empty(host) ? "ComfyUI" : host;
        } catch (Exception e) {
            return raw;
        }
    }

    private static String strip(String value) {
        String s = safe(value).trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static boolean empty(String s) { return s == null || s.trim().isEmpty(); }
    private static String safe(String s) { return s == null ? "" : s; }
}
