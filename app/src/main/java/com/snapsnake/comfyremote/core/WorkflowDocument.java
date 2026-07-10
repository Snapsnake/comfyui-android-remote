package com.snapsnake.comfyremote.core;

import com.snapsnake.comfyremote.ComfyWorkflowConverter;

import org.json.JSONObject;

public final class WorkflowDocument {
    private static final int CURRENT_WIDGET_MAPPING_VERSION = 2;

    private final JSONObject original;
    private final JSONObject apiPrompt;
    private final String sourceName;
    private final long updatedAt;
    private final int widgetMappingVersion;

    public WorkflowDocument(JSONObject original, JSONObject apiPrompt, String sourceName, long updatedAt) {
        this(original, apiPrompt, sourceName, updatedAt, CURRENT_WIDGET_MAPPING_VERSION);
    }

    private WorkflowDocument(JSONObject original, JSONObject apiPrompt, String sourceName,
                             long updatedAt, int widgetMappingVersion) {
        this.original = cloneObject(original);
        this.apiPrompt = cloneObject(apiPrompt);
        this.sourceName = sourceName == null ? "" : sourceName;
        this.updatedAt = updatedAt <= 0 ? System.currentTimeMillis() : updatedAt;
        this.widgetMappingVersion = Math.max(0, widgetMappingVersion);
    }

    public static WorkflowDocument empty() {
        return new WorkflowDocument(new JSONObject(), new JSONObject(), "",
                System.currentTimeMillis(), CURRENT_WIDGET_MAPPING_VERSION);
    }

    public static WorkflowDocument importRaw(JSONObject raw, JSONObject objectInfo, String sourceName) throws Exception {
        JSONObject source = raw == null ? new JSONObject() : raw;
        JSONObject schema = objectInfo == null ? new JSONObject() : objectInfo;

        JSONObject bundledPrompt = source.optJSONObject("apiPrompt");
        JSONObject bundledOriginal = source.optJSONObject("original");
        if (bundledPrompt != null && looksApiPrompt(bundledPrompt)) {
            JSONObject original = bundledOriginal == null ? new JSONObject() : bundledOriginal;
            int incomingMapping = source.optInt("widgetMappingVersion", 0);
            JSONObject repaired = rebuildAndMerge(
                    original,
                    bundledPrompt,
                    schema,
                    incomingMapping < CURRENT_WIDGET_MAPPING_VERSION
            );
            return new WorkflowDocument(
                    original,
                    repaired,
                    source.optString("sourceName", sourceName == null ? "" : sourceName),
                    source.optLong("updatedAt", System.currentTimeMillis()),
                    CURRENT_WIDGET_MAPPING_VERSION
            );
        }

        JSONObject extra = source.optJSONObject("extra");
        JSONObject mobile = extra == null ? null : extra.optJSONObject("comfyui_mobile");
        JSONObject embeddedPrompt = extra == null ? null : extra.optJSONObject("prompt");
        if (mobile != null && embeddedPrompt != null && looksApiPrompt(embeddedPrompt)) {
            int incomingMapping = mobile.optInt("widgetMappingVersion", 0);
            JSONObject repaired = rebuildAndMerge(
                    source,
                    embeddedPrompt,
                    schema,
                    incomingMapping < CURRENT_WIDGET_MAPPING_VERSION
            );
            return new WorkflowDocument(
                    source,
                    repaired,
                    mobile.optString("sourceName", sourceName == null ? "" : sourceName),
                    mobile.optLong("updatedAt", System.currentTimeMillis()),
                    CURRENT_WIDGET_MAPPING_VERSION
            );
        }

        JSONObject result = ComfyWorkflowConverter.importResult(source, schema);
        JSONObject prompt = result.optJSONObject("prompt");
        if (prompt == null) prompt = new JSONObject(result.optString("prompt", "{}"));
        prompt = FrontendWidgetValueMapper.correctFreshPrompt(source, prompt, schema);
        return new WorkflowDocument(source, prompt, sourceName, System.currentTimeMillis(),
                CURRENT_WIDGET_MAPPING_VERSION);
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
                saved.optLong("updatedAt", saved.optLong("savedAt", System.currentTimeMillis())),
                saved.optInt("widgetMappingVersion", 0)
        );
    }

    public JSONObject toJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("formatVersion", 4);
            out.put("kind", "comfyui-mobile-workflow");
            out.put("widgetMappingVersion", widgetMappingVersion);
            out.put("original", cloneObject(original));
            out.put("apiPrompt", cloneObject(apiPrompt));
            out.put("sourceName", sourceName);
            out.put("updatedAt", updatedAt);
            out.put("exportedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        return out;
    }

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
            mobile.put("formatVersion", 4);
            mobile.put("widgetMappingVersion", widgetMappingVersion);
            mobile.put("sourceName", sourceName);
            mobile.put("updatedAt", updatedAt);
            mobile.put("exportedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        return out;
    }

    public WorkflowDocument repaired(JSONObject objectInfo) {
        try {
            JSONObject schema = objectInfo == null ? new JSONObject() : objectInfo;
            JSONObject repaired = rebuildAndMerge(
                    original,
                    apiPrompt,
                    schema,
                    widgetMappingVersion < CURRENT_WIDGET_MAPPING_VERSION
            );
            return new WorkflowDocument(original, repaired, sourceName,
                    System.currentTimeMillis(), CURRENT_WIDGET_MAPPING_VERSION);
        } catch (Exception ignored) {
            return snapshot();
        }
    }

    private static JSONObject rebuildAndMerge(JSONObject original,
                                              JSONObject currentPrompt,
                                              JSONObject objectInfo,
                                              boolean sanitizeLegacy) throws Exception {
        if (original == null || original.length() == 0
                || objectInfo == null || objectInfo.length() == 0) {
            return ComfyWorkflowConverter.repairPrompt(
                    currentPrompt,
                    original == null ? new JSONObject() : original,
                    objectInfo == null ? new JSONObject() : objectInfo
            );
        }

        try {
            JSONObject fresh = ComfyWorkflowConverter.toApiPrompt(original, objectInfo);
            fresh = FrontendWidgetValueMapper.correctFreshPrompt(original, fresh, objectInfo);
            JSONObject current = cloneObject(currentPrompt);
            if (sanitizeLegacy) {
                current = FrontendWidgetValueMapper.sanitizeLegacyCurrent(original, current, objectInfo);
            }
            return ComfyWorkflowConverter.mergeValidPromptValues(fresh, current, objectInfo);
        } catch (Exception ignored) {
            return ComfyWorkflowConverter.repairPrompt(currentPrompt, original, objectInfo);
        }
    }

    public JSONObject original() { return cloneObject(original); }
    public JSONObject apiPrompt() { return apiPrompt; }
    public JSONObject apiPromptCopy() { return cloneObject(apiPrompt); }
    public String sourceName() { return sourceName; }
    public long updatedAt() { return updatedAt; }
    public boolean isEmpty() { return apiPrompt.length() == 0; }
    public int nodeCount() { return apiPrompt.length(); }

    public WorkflowDocument renamed(String name) {
        return new WorkflowDocument(original, apiPrompt, name, System.currentTimeMillis(),
                widgetMappingVersion);
    }

    public WorkflowDocument snapshot() {
        return new WorkflowDocument(original, apiPrompt, sourceName, System.currentTimeMillis(),
                widgetMappingVersion);
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
