package com.snapsnake.comfyremote.core;

import org.json.JSONObject;

import java.util.Locale;

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
        Kind inferred = kindFromFilename(this.filename);
        this.kind = inferred == Kind.FILE ? (kind == null ? Kind.FILE : kind) : inferred;
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

    public String mimeType() { return mimeForFilename(filename); }

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

    public static Kind kindFromFilename(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.US);
        if (lower.endsWith(".gif")) return Kind.GIF;
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".webp") || lower.endsWith(".bmp") || lower.endsWith(".heic") ||
                lower.endsWith(".heif")) return Kind.IMAGE;
        if (lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov") ||
                lower.endsWith(".mkv") || lower.endsWith(".m4v") || lower.endsWith(".avi")) return Kind.VIDEO;
        if (lower.endsWith(".wav") || lower.endsWith(".mp3") || lower.endsWith(".flac") ||
                lower.endsWith(".ogg") || lower.endsWith(".m4a") || lower.endsWith(".aac")) return Kind.AUDIO;
        return Kind.FILE;
    }

    public static String mimeForFilename(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.US);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".heic")) return "image/heic";
        if (lower.endsWith(".heif")) return "image/heif";
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".aac")) return "audio/aac";
        return "application/octet-stream";
    }

    private static boolean empty(String s) { return s == null || s.trim().isEmpty(); }
    private static String safe(String s) { return s == null ? "" : s; }
}
