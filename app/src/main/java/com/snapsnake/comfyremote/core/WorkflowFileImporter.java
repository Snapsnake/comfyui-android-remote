package com.snapsnake.comfyremote.core;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.InflaterInputStream;

public final class WorkflowFileImporter {
    public static final class Imported {
        public final JSONObject workflow;
        public final String sourceName;
        Imported(JSONObject workflow, String sourceName) {
            this.workflow = workflow;
            this.sourceName = sourceName;
        }
    }

    private WorkflowFileImporter() {}

    public static Imported read(Context context, Uri uri) throws Exception {
        byte[] bytes = readAll(context.getContentResolver(), uri);
        String name = fileName(uri);
        JSONObject json = parseJsonBytes(bytes);
        if (json != null) return new Imported(json, name);
        if (isPng(bytes)) {
            JSONObject png = fromPng(bytes);
            if (png != null) return new Imported(png, name);
        }
        if (isWebP(bytes)) {
            JSONObject webp = fromWebP(bytes);
            if (webp != null) return new Imported(webp, name);
        }
        JSONObject scanned = scanForWorkflowJson(bytes);
        if (scanned != null) return new Imported(scanned, name);
        throw new IllegalArgumentException("No ComfyUI workflow metadata found in " + name);
    }

    private static JSONObject parseJsonBytes(byte[] bytes) {
        try {
            String text = new String(bytes, StandardCharsets.UTF_8).trim();
            if (text.startsWith("{")) return new JSONObject(text);
        } catch (Exception ignored) {}
        return null;
    }

    private static JSONObject fromPng(byte[] bytes) {
        try {
            int offset = 8;
            while (offset + 12 <= bytes.length) {
                int length = be32(bytes, offset);
                if (length < 0 || offset + 12L + length > bytes.length) break;
                String type = ascii(bytes, offset + 4, 4);
                int dataStart = offset + 8;
                byte[] data = slice(bytes, dataStart, length);
                String key = "";
                String value = "";
                if ("tEXt".equals(type)) {
                    int zero = indexOf(data, (byte) 0, 0);
                    if (zero > 0) {
                        key = new String(data, 0, zero, StandardCharsets.ISO_8859_1);
                        value = new String(data, zero + 1, data.length - zero - 1, StandardCharsets.ISO_8859_1);
                    }
                } else if ("zTXt".equals(type)) {
                    int zero = indexOf(data, (byte) 0, 0);
                    if (zero > 0 && zero + 2 <= data.length) {
                        key = new String(data, 0, zero, StandardCharsets.ISO_8859_1);
                        value = inflate(slice(data, zero + 2, data.length - zero - 2));
                    }
                } else if ("iTXt".equals(type)) {
                    ParsedText parsed = parseInternationalText(data);
                    if (parsed != null) { key = parsed.key; value = parsed.value; }
                }
                JSONObject workflow = metadataValue(key, value);
                if (workflow != null) return workflow;
                offset += 12 + length;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static JSONObject fromWebP(byte[] bytes) {
        try {
            int offset = 12;
            while (offset + 8 <= bytes.length) {
                String type = ascii(bytes, offset, 4);
                int length = le32(bytes, offset + 4);
                if (length < 0 || offset + 8L + length > bytes.length) break;
                byte[] data = slice(bytes, offset + 8, length);
                if ("EXIF".equals(type) || "XMP ".equals(type)) {
                    JSONObject found = scanForWorkflowJson(data);
                    if (found != null) return found;
                }
                offset += 8 + length + (length & 1);
            }
        } catch (Exception ignored) {}
        return scanForWorkflowJson(bytes);
    }

    private static JSONObject metadataValue(String key, String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String k = key == null ? "" : key.trim().toLowerCase();
        if (!k.equals("workflow") && !k.equals("prompt") && !k.contains("comfy")) return null;
        try {
            JSONObject parsed = new JSONObject(value.trim());
            if (k.equals("workflow")) return parsed;
            JSONObject wrapper = new JSONObject();
            wrapper.put("prompt", parsed);
            return wrapper;
        } catch (Exception ignored) {
            return scanForWorkflowJson(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static JSONObject scanForWorkflowJson(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        List<Integer> starts = new ArrayList<>();
        addStart(text, starts, "{\"last_node_id\"");
        addStart(text, starts, "{\"nodes\"");
        addStart(text, starts, "{\"prompt\"");
        addStart(text, starts, "{\"workflow\"");
        addStart(text, starts, "{\"1\":");
        for (int start : starts) {
            String json = balancedObject(text, start);
            if (json == null) continue;
            try {
                JSONObject object = new JSONObject(json);
                if (looksWorkflow(object)) return object;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static void addStart(String text, List<Integer> starts, String token) {
        int from = 0;
        while (true) {
            int index = text.indexOf(token, from);
            if (index < 0) return;
            starts.add(index);
            from = index + 1;
            if (starts.size() > 30) return;
        }
    }

    private static String balancedObject(String text, int start) {
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
                continue;
            }
            if (c == '"') quoted = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
                if (depth < 0) return null;
            }
        }
        return null;
    }

    private static boolean looksWorkflow(JSONObject object) {
        if (object == null) return false;
        if (object.optJSONArray("nodes") != null) return true;
        if (object.optJSONObject("prompt") != null || object.optJSONObject("workflow") != null) return true;
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            JSONObject node = object.optJSONObject(keys.next());
            if (node != null && node.has("class_type")) return true;
        }
        return false;
    }

    private static ParsedText parseInternationalText(byte[] data) throws Exception {
        int p0 = indexOf(data, (byte) 0, 0);
        if (p0 < 0 || p0 + 3 >= data.length) return null;
        String key = new String(data, 0, p0, StandardCharsets.ISO_8859_1);
        int compressionFlag = data[p0 + 1] & 0xff;
        int position = p0 + 3;
        int languageEnd = indexOf(data, (byte) 0, position);
        if (languageEnd < 0) return null;
        position = languageEnd + 1;
        int translatedEnd = indexOf(data, (byte) 0, position);
        if (translatedEnd < 0) return null;
        position = translatedEnd + 1;
        byte[] text = slice(data, position, data.length - position);
        String value = compressionFlag == 1 ? inflate(text) : new String(text, StandardCharsets.UTF_8);
        return new ParsedText(key, value);
    }

    private static String inflate(byte[] compressed) throws Exception {
        try (InflaterInputStream in = new InflaterInputStream(new java.io.ByteArrayInputStream(compressed));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readAll(ContentResolver resolver, Uri uri) throws Exception {
        try (InputStream in = resolver.openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IllegalArgumentException("Could not open file");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    private static String fileName(Uri uri) {
        String raw = uri == null ? "workflow" : uri.getLastPathSegment();
        if (raw == null || raw.trim().isEmpty()) return "workflow";
        int split = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf(':'));
        return split >= 0 && split + 1 < raw.length() ? raw.substring(split + 1) : raw;
    }

    private static boolean isPng(byte[] b) {
        return b != null && b.length >= 8 && (b[0] & 0xff) == 137 && b[1] == 80 && b[2] == 78 && b[3] == 71;
    }

    private static boolean isWebP(byte[] b) {
        return b != null && b.length >= 12 && ascii(b, 0, 4).equals("RIFF") && ascii(b, 8, 4).equals("WEBP");
    }

    private static int be32(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }
    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }
    private static String ascii(byte[] b, int o, int n) {
        if (b == null || o < 0 || n < 0 || o + n > b.length) return "";
        return new String(b, o, n, StandardCharsets.ISO_8859_1);
    }
    private static byte[] slice(byte[] b, int o, int n) {
        byte[] out = new byte[Math.max(0, n)];
        if (n > 0) System.arraycopy(b, o, out, 0, n);
        return out;
    }
    private static int indexOf(byte[] b, byte target, int start) {
        for (int i = Math.max(0, start); i < b.length; i++) if (b[i] == target) return i;
        return -1;
    }

    private static final class ParsedText {
        final String key;
        final String value;
        ParsedText(String key, String value) { this.key = key; this.value = value; }
    }
}
