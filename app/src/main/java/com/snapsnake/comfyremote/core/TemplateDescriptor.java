package com.snapsnake.comfyremote.core;

import org.json.JSONObject;

public final class TemplateDescriptor {
    public final String source;
    public final String name;
    public final String title;
    public final String description;
    public final String category;
    public final String mediaSubtype;

    public TemplateDescriptor(String source, String name, String title, String description, String category, String mediaSubtype) {
        this.source = empty(source) ? "default" : source.trim();
        this.name = safe(name).trim();
        this.title = empty(title) ? this.name : title.trim();
        this.description = safe(description).trim();
        this.category = empty(category) ? "Templates" : category.trim();
        this.mediaSubtype = empty(mediaSubtype) ? "webp" : mediaSubtype.trim();
    }

    public String id() { return source + "/" + name; }

    public JSONObject toJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("source", source);
            out.put("name", name);
            out.put("title", title);
            out.put("description", description);
            out.put("category", category);
            out.put("mediaSubtype", mediaSubtype);
        } catch (Exception ignored) {}
        return out;
    }

    public static TemplateDescriptor fromJson(JSONObject raw) {
        if (raw == null) return new TemplateDescriptor("default", "", "", "", "Templates", "webp");
        return new TemplateDescriptor(
                raw.optString("source", "default"),
                raw.optString("name", ""),
                raw.optString("title", raw.optString("localizedTitle", "")),
                raw.optString("description", raw.optString("localizedDescription", "")),
                raw.optString("category", "Templates"),
                raw.optString("mediaSubtype", "webp")
        );
    }

    public String searchableText() {
        return title + " " + name + " " + description + " " + category + " " + source;
    }

    private static boolean empty(String s) { return s == null || s.trim().isEmpty(); }
    private static String safe(String s) { return s == null ? "" : s; }
}
