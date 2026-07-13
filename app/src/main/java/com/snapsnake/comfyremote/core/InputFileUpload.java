package com.snapsnake.comfyremote.core;

import java.util.Locale;

/** Filename and MIME normalization for arbitrary files uploaded to ComfyUI input/. */
public final class InputFileUpload {
    private InputFileUpload() {}

    public static String normalizeFilename(String candidate, byte[] bytes, String preferredMime) {
        String raw = candidate == null ? "" : candidate.trim().replace('\\', '/');
        int slash = raw.lastIndexOf('/');
        if (slash >= 0) raw = raw.substring(slash + 1);
        int colon = raw.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < raw.length()) raw = raw.substring(colon + 1);
        raw = raw.replaceAll("[^A-Za-z0-9._() -]", "_");
        while (raw.startsWith(".")) raw = raw.substring(1);
        if (raw.isEmpty()) raw = "input";

        String ext = extension(raw);
        String inferred = extensionFromMime(preferredMime);
        if (inferred.isEmpty()) inferred = InputImageUpload.extensionFromBytes(bytes);
        if (ext.isEmpty() && !inferred.isEmpty()) raw += inferred;
        return raw;
    }

    public static String mediaType(byte[] bytes, String filename, String preferredMime) {
        String preferred = preferredMime == null ? "" : preferredMime.trim().toLowerCase(Locale.US);
        if (!preferred.isEmpty() && !"*/*".equals(preferred) && !"application/octet-stream".equals(preferred)) {
            return preferred;
        }
        String ext = extension(filename);
        if (".jpg".equals(ext) || ".jpeg".equals(ext)) return "image/jpeg";
        if (".png".equals(ext)) return "image/png";
        if (".webp".equals(ext)) return "image/webp";
        if (".gif".equals(ext)) return "image/gif";
        if (".bmp".equals(ext)) return "image/bmp";
        if (".wav".equals(ext)) return "audio/wav";
        if (".mp3".equals(ext)) return "audio/mpeg";
        if (".flac".equals(ext)) return "audio/flac";
        if (".ogg".equals(ext) || ".oga".equals(ext)) return "audio/ogg";
        if (".m4a".equals(ext)) return "audio/mp4";
        if (".aac".equals(ext)) return "audio/aac";
        if (".mp4".equals(ext) || ".m4v".equals(ext)) return "video/mp4";
        if (".webm".equals(ext)) return "video/webm";
        if (".mov".equals(ext)) return "video/quicktime";
        if (".mkv".equals(ext)) return "video/x-matroska";
        if (".json".equals(ext)) return "application/json";
        if (".txt".equals(ext)) return "text/plain";
        return InputImageUpload.mediaType(bytes, filename);
    }

    public static String extensionFromMime(String mime) {
        String value = mime == null ? "" : mime.trim().toLowerCase(Locale.US);
        if ("image/jpeg".equals(value)) return ".jpg";
        if ("image/png".equals(value)) return ".png";
        if ("image/webp".equals(value)) return ".webp";
        if ("image/gif".equals(value)) return ".gif";
        if ("image/bmp".equals(value)) return ".bmp";
        if ("audio/wav".equals(value) || "audio/x-wav".equals(value)) return ".wav";
        if ("audio/mpeg".equals(value)) return ".mp3";
        if ("audio/flac".equals(value)) return ".flac";
        if ("audio/ogg".equals(value)) return ".ogg";
        if ("audio/mp4".equals(value)) return ".m4a";
        if ("audio/aac".equals(value)) return ".aac";
        if ("video/mp4".equals(value)) return ".mp4";
        if ("video/webm".equals(value)) return ".webm";
        if ("video/quicktime".equals(value)) return ".mov";
        if ("video/x-matroska".equals(value)) return ".mkv";
        if ("application/json".equals(value)) return ".json";
        if ("text/plain".equals(value)) return ".txt";
        return "";
    }

    private static String extension(String name) {
        if (name == null) return "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        if (dot <= slash || dot < 0 || dot + 1 >= name.length()) return "";
        return name.substring(dot).toLowerCase(Locale.US);
    }
}
