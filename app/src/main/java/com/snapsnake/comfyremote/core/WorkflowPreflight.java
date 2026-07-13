package com.snapsnake.comfyremote.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Local schema validation before a prompt is sent to ComfyUI. */
public final class WorkflowPreflight {
    public static final class Issue {
        public final String nodeId;
        public final String nodeTitle;
        public final String key;
        public final String message;

        Issue(String nodeId, String nodeTitle, String key, String message) {
            this.nodeId = safe(nodeId);
            this.nodeTitle = safe(nodeTitle);
            this.key = safe(key);
            this.message = safe(message);
        }

        public String summary() {
            String node = nodeTitle.isEmpty() ? "Node #" + nodeId : nodeTitle + " (#" + nodeId + ")";
            return node + " · " + key + ": " + message;
        }
    }

    private WorkflowPreflight() {}

    public static List<Issue> validate(WorkflowDocument document, NodeSchemaRegistry registry) {
        ArrayList<Issue> issues = new ArrayList<>();
        if (document == null || document.isEmpty() || registry == null) return issues;
        JSONObject prompt = document.apiPrompt();
        Iterator<String> ids = prompt.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            JSONObject node = prompt.optJSONObject(id);
            if (node == null) continue;
            JSONObject inputs = node.optJSONObject("inputs");
            if (inputs == null) inputs = new JSONObject();
            String classType = node.optString("class_type", "");
            JSONObject meta = node.optJSONObject("_meta");
            String title = meta == null ? "" : meta.optString("title", "");
            if (title.trim().isEmpty()) title = registry.displayName(classType);

            for (NodeSchemaRegistry.FieldSpec field : registry.fieldsForNode(id, node)) {
                if (field.connected) continue;
                boolean has = inputs.has(field.key) && !inputs.isNull(field.key);
                Object value = has ? inputs.opt(field.key) : JSONObject.NULL;
                if (!has) {
                    if (field.required && field.canUseLocalValue()) {
                        issues.add(new Issue(id, title, field.key, "required value is missing"));
                    }
                    continue;
                }
                String error = typeError(field, value);
                if (!error.isEmpty()) issues.add(new Issue(id, title, field.key, error));
            }
        }
        return issues;
    }

    private static String typeError(NodeSchemaRegistry.FieldSpec field, Object value) {
        switch (field.kind) {
            case INTEGER:
                if (!(value instanceof Number)) return "expected an integer, got " + typeName(value);
                double number = ((Number) value).doubleValue();
                if (Math.rint(number) != number) return "expected an integer, got " + number;
                return "";
            case FLOAT:
                return value instanceof Number ? "" : "expected a number, got " + typeName(value);
            case BOOLEAN:
                return value instanceof Boolean ? "" : "expected true/false, got " + typeName(value);
            case COMBO:
            case FILE:
                if (value instanceof JSONObject || value instanceof JSONArray) {
                    return "expected a selectable value, got " + typeName(value);
                }
                if (field.options.length() > 0 && !contains(field.options, value)) {
                    return "value '" + String.valueOf(value) + "' is not available in the server schema";
                }
                if (field.required && String.valueOf(value).trim().isEmpty()) return "value is empty";
                return "";
            case STRING:
                return value instanceof String ? "" : "expected text, got " + typeName(value);
            case UNKNOWN:
            default:
                return "";
        }
    }

    private static boolean contains(JSONArray options, Object value) {
        String target = String.valueOf(value);
        for (int i = 0; i < options.length(); i++) {
            if (target.equals(String.valueOf(options.opt(i)))) return true;
        }
        return false;
    }

    private static String typeName(Object value) {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Number) return "number";
        if (value instanceof String) return "text";
        if (value instanceof JSONArray) return "link/list";
        if (value instanceof JSONObject) return "object";
        return value.getClass().getSimpleName();
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
