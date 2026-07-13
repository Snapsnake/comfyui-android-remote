package com.snapsnake.comfyremote.core;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** A workflow field that points to a file under ComfyUI input/. */
public final class WorkflowFileRequirement {
    public final String nodeId;
    public final String nodeClass;
    public final String nodeTitle;
    public final String key;
    public final String value;
    public final String mimeHint;

    public WorkflowFileRequirement(String nodeId, String nodeClass, String nodeTitle,
                                   String key, String value, String mimeHint) {
        this.nodeId = safe(nodeId);
        this.nodeClass = safe(nodeClass);
        this.nodeTitle = safe(nodeTitle);
        this.key = safe(key);
        this.value = safe(value);
        this.mimeHint = safe(mimeHint).isEmpty() ? "*/*" : mimeHint;
    }

    public String id() { return nodeId + ":" + key; }
    public boolean isEmpty() { return value.trim().isEmpty(); }

    public static List<WorkflowFileRequirement> discover(WorkflowDocument document,
                                                         NodeSchemaRegistry registry) {
        ArrayList<WorkflowFileRequirement> out = new ArrayList<>();
        if (document == null || document.isEmpty() || registry == null) return out;
        JSONObject prompt = document.apiPrompt();
        Iterator<String> ids = prompt.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            JSONObject node = prompt.optJSONObject(id);
            if (node == null) continue;
            String classType = node.optString("class_type", "");
            JSONObject meta = node.optJSONObject("_meta");
            String title = meta == null ? "" : meta.optString("title", "");
            if (title.trim().isEmpty()) title = registry.displayName(classType);
            JSONObject inputs = node.optJSONObject("inputs");
            if (inputs == null) continue;

            for (NodeSchemaRegistry.FieldSpec field : registry.fieldsForNode(id, node)) {
                if (field.connected || !isInputFileField(classType, field)) continue;
                Object raw = inputs.opt(field.key);
                if (raw instanceof JSONObject || raw instanceof org.json.JSONArray) continue;
                String value = raw == null || raw == JSONObject.NULL ? "" : String.valueOf(raw);
                out.add(new WorkflowFileRequirement(
                        id, classType, title, field.key, value, mimeHint(classType, field.key)
                ));
            }
        }
        return out;
    }

    public static boolean isInputFileField(String classType, NodeSchemaRegistry.FieldSpec field) {
        if (field == null) return false;
        if (field.config.optBoolean("image_upload", false)
                || field.config.optBoolean("upload", false)
                || hasTrueUploadFlag(field.config)) return true;
        if (field.kind == NodeSchemaRegistry.Kind.FILE) return true;

        String cls = safe(classType).toLowerCase(Locale.US).replace("_", "");
        String leaf = leaf(field.key).toLowerCase(Locale.US);
        boolean loader = cls.contains("loadimage") || cls.contains("loadmask")
                || cls.contains("loadaudio") || cls.contains("loadvideo")
                || cls.contains("loadfile") || cls.contains("inputfile");
        if (!loader) return false;
        return leaf.equals("image") || leaf.equals("mask") || leaf.equals("audio")
                || leaf.equals("video") || leaf.equals("file") || leaf.equals("path")
                || leaf.equals("filename") || leaf.endsWith("_image")
                || leaf.endsWith("_mask") || leaf.endsWith("_audio")
                || leaf.endsWith("_video") || leaf.endsWith("_file");
    }

    private static boolean hasTrueUploadFlag(JSONObject config) {
        if (config == null) return false;
        Iterator<String> keys = config.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.toLowerCase(Locale.US).endsWith("_upload") && config.optBoolean(key, false)) return true;
        }
        return false;
    }

    private static String mimeHint(String classType, String key) {
        String text = (safe(classType) + " " + safe(key)).toLowerCase(Locale.US);
        if (text.contains("audio") || text.contains("sound")) return "audio/*";
        if (text.contains("video") || text.contains("movie")) return "video/*";
        if (text.contains("image") || text.contains("mask") || text.contains("photo")) return "image/*";
        return "*/*";
    }

    private static String leaf(String key) {
        String value = safe(key);
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(dot + 1);
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
