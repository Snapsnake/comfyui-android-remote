package com.snapsnake.comfyremote.core;

import com.snapsnake.comfyremote.ComfyWorkflowConverter;

import org.json.JSONObject;

public final class WorkflowDocument {
    private final JSONObject original;
    private final JSONObject apiPrompt;
    private final String sourceName;
    private final long updatedAt;

    public WorkflowDocument(JSONObject original, JSONObject apiPrompt, String sourceName, long updatedAt) {
        this.original = cloneObject(original);
        this.apiPrompt = cloneObject(apiPrompt);
        this.sourceName = sourceName == null ? "" : sourceName;
        this.updatedAt = updatedAt <= 0 ? System.currentTimeMillis() : updatedAt;
    }

    public static WorkflowDocument empty() {
        return new WorkflowDocument(new JSONObject(), new JSONObject(), "", System.currentTimeMillis());
    }

    public static WorkflowDocument importRaw(JSONObject raw, JSONObject objectInfo, String sourceName) throws Exception {
        JSONObject source = raw == null ? new JSONObject() : raw;

        // Exact round-trip bundle written by the Android client.
        JSONObject bundledPrompt = source.optJSONObject("apiPrompt");
        JSONObject bundledOriginal = source.optJSONObject("original");
        if (bundledPrompt != null && looksApiPrompt(bundledPrompt)) {
            return new WorkflowDocument(
                    bundledOriginal == null ? new JSONObject() : bundledOriginal,
                    bundledPrompt,
                    source.optString("sourceName", sourceName == null ? "" : sourceName),
                    source.optLong("updatedAt", System.currentTimeMillis())
            );
        }

        // Frontend export written by this app. The complete frontend graph is
        // retained as the original, while the embedded API prompt carries the
        // exact current values. Ordinary third-party workflows do not use this
        // marker and continue through the normal converter.
        JSONObject extra = source.optJSONObject("extra");
        JSONObject mobile = extra == null ? null : extra.optJSONObject("comfyui_mobile");
        JSONObject embeddedPrompt = extra == null ? null : extra.optJSONObject("prompt");
        if (mobile != null && embeddedPrompt != null && looksApiPrompt(embeddedPrompt)) {
            return new WorkflowDocument(
                    source,
                    embeddedPrompt,
                    mobile.optString("sourceName", sourceName == null ? "" : sourceName),
                    mobile.optLong("updatedAt", System.currentTimeMillis())
            );
        }

        JSONObject result = ComfyWorkflowConverter.importResult(source, objectInfo == null ? new JSONObject() : objectInfo);
        JSONObject prompt = result.optJSONObject("prompt");
        if (prompt == null) prompt = new JSONObject(result.optString("prompt", "{}"));
        return new WorkflowDocument(source, prompt, sourceName, System.currentTimeMillis());
    }

    public static WorkflowDocument fromJson(JSONObject saved) {
        if (saved == null) return empty();
        JSONObject original = saved.optJSONObject("original");
        JSONObject prompt = saved.optJSONObject("apiPrompt");
        if (prompt == null && looksApiPrompt(saved)) prompt = saved;
        return new WorkflowDocument(
                original == null ? new JSONObject() : original,
                prompt == null ? new JSONObject() : prompt,
                saved.optString("sourceName", saved.optString("displayName", "")),
                saved.optLong("updatedAt", saved.optLong("savedAt", System.currentTimeMillis()))
        );
    }

    public JSONObject toJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("formatVersion", 2);
            out.put("kind", "comfyui-mobile-workflow");
            out.put("original", cloneObject(original));
            out.put("apiPrompt", cloneObject(apiPrompt));
            out.put("sourceName", sourceName);
            out.put("updatedAt", updatedAt);
            out.put("exportedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        return out;
    }

    /**
     * Preserves the complete frontend workflow and embeds the current,
     * executable API prompt. Unknown nodes, subgraphs, groups and metadata are
     * left untouched.
     */
    public JSONObject frontendWithCurrentPrompt() {
        JSONObject out = cloneObject(original);
        try {
            JSONObject extra = out.optJSONObject("extra");
            if (extra == null) {
                extra = new JSONObject();
                out.put("extra", extra);
            }
            extra.put("prompt", cloneObject(apiPrompt));
            JSONObject mobile = extra.optJSONObject("comfyui_mobile");
            if (mobile == null) {
                mobile = new JSONObject();
                extra.put("comfyui_mobile", mobile);
            }
            mobile.put("formatVersion", 2);
            mobile.put("sourceName", sourceName);
            mobile.put("updatedAt", updatedAt);
            mobile.put("exportedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        return out;
    }

    public JSONObject original() { return cloneObject(original); }
    public JSONObject apiPrompt() { return apiPrompt; }
    public JSONObject apiPromptCopy() { return cloneObject(apiPrompt); }
    public String sourceName() { return sourceName; }
    public long updatedAt() { return updatedAt; }
    public boolean isEmpty() { return apiPrompt.length() == 0; }
    public int nodeCount() { return apiPrompt.length(); }

    public WorkflowDocument renamed(String name) {
        return new WorkflowDocument(original, apiPrompt, name, System.currentTimeMillis());
    }

    public WorkflowDocument snapshot() {
        return new WorkflowDocument(original, apiPrompt, sourceName, System.currentTimeMillis());
    }

    private static boolean looksApiPrompt(JSONObject object) {
        if (object == null) return false;
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            JSONObject node = object.optJSONObject(keys.next());
            if (node != null && node.has("class_type")) return true;
        }
        return false;
    }

    private static JSONObject cloneObject(JSONObject object) {
        if (object == null) return new JSONObject();
        try { return new JSONObject(object.toString()); }
        catch (Exception e) { return new JSONObject(); }
    }
}
