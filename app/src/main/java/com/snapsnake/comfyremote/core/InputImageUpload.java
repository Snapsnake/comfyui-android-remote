package com.snapsnake.comfyremote.core;

import org.json.JSONObject;

import java.util.Locale;

/** Utilities shared by upload, workflow persistence and queue preflight. */
public final class InputImageUpload {
    public static final class Ref {
        public final String filename;
        public final String subfolder;

        Ref(String filename, String subfolder) {
            this.filename = filename == null ? "" : filename;
            this.subfolder = subfolder == null ? "" : subfolder;
        }

        public String workflowValue() {
            return subfolder.isEmpty() ? filename : subfolder + "/" + filename;
        }
    }

    private InputImageUpload() {}

    public static String normalizeFilename(String candidate, byte[] bytes) {
        String raw = candidate == null ? "" : candidate.trim().replace('\\', '/');
        int slash = raw.lastIndexOf('/');
        if (slash >= 0) raw = raw.substring(slash + 1);
        int colon = raw.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < raw.length()) raw = raw.substring(colon + 1);
        raw = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        while (raw.startsWith(".")) raw = raw.substring(1);
        if (raw.isEmpty()) raw = "input";

        String inferred = extensionFromBytes(bytes);
        String ext = extension(raw);
        if (ext.isEmpty()) {
            raw += inferred.isEmpty() ? ".png" : inferred;
        } else if ((".bin".equals(ext) || ".tmp".equals(ext) || ".octet-stream".equals(ext)) && !inferred.isEmpty()) {
            raw = raw.substring(0, raw.length() - ext.length()) + inferred;
        }
        return raw;
    }

    public static String mediaType(byte[] bytes, String filename) {
        String ext = extension(filename);
        if (".jpg".equals(ext) || ".jpeg".equals(ext)) return "image/jpeg";
        if (".png".equals(ext)) return "image/png";
        if (".webp".equals(ext)) return "image/webp";
        if (".gif".equals(ext)) return "image/gif";
        if (".bmp".equals(ext)) return "image/bmp";
        String inferred = extensionFromBytes(bytes);
        if (".jpg".equals(inferred)) return "image/jpeg";
        if (".png".equals(inferred)) return "image/png";
        if (".webp".equals(inferred)) return "image/webp";
        if (".gif".equals(inferred)) return "image/gif";
        if (".bmp".equals(inferred)) return "image/bmp";
        return "application/octet-stream";
    }

    public static Ref fromUploadResponse(JSONObject response, String fallbackFilename) {
        String filename = response == null ? "" : response.optString("name", "").trim();
        if (filename.isEmpty()) filename = fallbackFilename == null ? "" : fallbackFilename.trim();
        String subfolder = response == null ? "" : response.optString("subfolder", "").trim();
        return new Ref(sanitizeServerPart(filename, false), sanitizeServerPart(subfolder, true));
    }

    public static Ref parseWorkflowValue(String value) {
        String normalized = value == null ? "" : value.trim().replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        String[] parts = normalized.split("/");
        StringBuilder folder = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            String part = sanitizeServerPart(parts[i], false);
            if (part.isEmpty()) continue;
            if (folder.length() > 0) folder.append('/');
            folder.append(part);
        }
        String filename = parts.length == 0 ? "" : sanitizeServerPart(parts[parts.length - 1], false);
        return new Ref(filename, folder.toString());
    }

    public static String extensionFromBytes(byte[] data) {
        if (data == null) return "";
        if (data.length >= 3 && u(data[0]) == 0xFF && u(data[1]) == 0xD8 && u(data[2]) == 0xFF) return ".jpg";
        if (data.length >= 8 && u(data[0]) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G' && u(data[4]) == 0x0D && u(data[5]) == 0x0A && u(data[6]) == 0x1A && u(data[7]) == 0x0A) return ".png";
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F' && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') return ".webp";
        if (data.length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8' && (data[4] == '7' || data[4] == '9') && data[5] == 'a') return ".gif";
        if (data.length >= 2 && data[0] == 'B' && data[1] == 'M') return ".bmp";
        return "";
    }

    private static String extension(String name) {
        if (name == null) return "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        if (dot <= slash || dot < 0 || dot + 1 >= name.length()) return "";
        return name.substring(dot).toLowerCase(Locale.US);
    }

    private static String sanitizeServerPart(String value, boolean allowPath) {
        String raw = value == null ? "" : value.trim().replace('\\', '/');
        if (!allowPath) {
            int slash = raw.lastIndexOf('/');
            if (slash >= 0) raw = raw.substring(slash + 1);
            return raw.replaceAll("[^A-Za-z0-9._() -]", "_");
        }
        StringBuilder out = new StringBuilder();
        for (String part : raw.split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) continue;
            String clean = part.replaceAll("[^A-Za-z0-9._() -]", "_");
            if (clean.isEmpty()) continue;
            if (out.length() > 0) out.append('/');
            out.append(clean);
        }
        return out.toString();
    }

    private static int u(byte value) { return value & 0xFF; }
}
