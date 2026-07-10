package com.snapsnake.comfyremote.core;

import org.json.JSONObject;

public final class OutputAsset {
    public enum Kind { IMAGE, VIDEO, AUDIO, GIF, FILE }

    public final String promptId;
    public final String nodeId;
    public final String filename;
    public final String subfolder;
    public final String type;
    public final Kind kind;

    public OutputAsset(String promptId, String nodeId, String filename, String subfolder, String type, Kind kind) {
        this.promptId = safe(promptId);
        this.nodeId = safe(nodeId);
        this.filename = safe(filename);
        this.subfolder = safe(subfolder);
        this.type = empty(type) ? "output" : type;
        this.kind = kind == null ? Kind.FILE : kind;
    }

    public JSONObject toJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("promptId", promptId);
            out.put("nodeId", nodeId);
            out.put("filename", filename);
            out.put("subfolder", subfolder);
            out.put("type", type);
            out.put("kind", kind.name());
        } catch (Exception ignored) {}
        return out;
    }

    public static OutputAsset fromJson(JSONObject raw) {
        if (raw == null) return new OutputAsset("", "", "", "", "output", Kind.FILE);
        Kind kind;
        try { kind = Kind.valueOf(raw.optString("kind", "FILE")); }
        catch (Exception e) { kind = Kind.FILE; }
        return new OutputAsset(
                raw.optString("promptId", ""),
                raw.optString("nodeId", ""),
                raw.optString("filename", ""),
                raw.optString("subfolder", ""),
                raw.optString("type", "output"),
                kind
        );
    }

    private static boolean empty(String s) { return s == null || s.trim().isEmpty(); }
    private static String safe(String s) { return s == null ? "" : s; }
}
