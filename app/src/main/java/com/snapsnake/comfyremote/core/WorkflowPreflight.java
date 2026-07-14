package com.snapsnake.comfyremote.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Local schema validation before a prompt is sent to ComfyUI. */
public final class WorkflowPreflight {
    public static final String CODE_UNKNOWN_COMBO = "unknown_combo_value";
    public static final String CODE_SCHEMA_REFRESH = "schema_refresh_warning";

    public static final class Issue {
        public final String nodeId;
        public final String nodeTitle;
        public final String key;
        public final String message;
        public final String code;
        public final boolean blocking;

        Issue(String nodeId, String nodeTitle, String key, String message,
              String code, boolean blocking) {
            this.nodeId = safe(nodeId);
            this.nodeTitle = safe(nodeTitle);
            this.key = safe(key);
            this.message = safe(message);
            this.code = safe(code);
            this.blocking = blocking;
        }

        public static Issue warning(String nodeId, String nodeTitle, String key,
                                    String message, String code) {
            return new Issue(nodeId, nodeTitle, key, message, code, false);
        }

        public String summary() {
            String node = nodeTitle.isEmpty() ? (nodeId.isEmpty() ? "Workflow" : "Node #" + nodeId)
                    : (nodeId.isEmpty() ? nodeTitle : nodeTitle + " (#" + nodeId + ")");
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
                        issues.add(error(id, title, field.key, "required value is missing", "missing_required"));
                    }
                    continue;
                }
                Issue issue = typeIssue(id, title, field, value);
                if (issue != null) issues.add(issue);
            }
        }
        return issues;
    }

    public static List<Issue> blockingIssues(List<Issue> issues) {
        ArrayList<Issue> out = new ArrayList<>();
        if (issues != null) for (Issue issue : issues) if (issue != null && issue.blocking) out.add(issue);
        return out;
    }

    public static List<Issue> warnings(List<Issue> issues) {
        ArrayList<Issue> out = new ArrayList<>();
        if (issues != null) for (Issue issue : issues) if (issue != null && !issue.blocking) out.add(issue);
        return out;
    }

    public static boolean hasUnknownComboWarning(List<Issue> issues) {
        if (issues == null) return false;
        for (Issue issue : issues) {
            if (issue != null && CODE_UNKNOWN_COMBO.equals(issue.code)) return true;
        }
        return false;
    }

    private static Issue typeIssue(String nodeId, String title,
                                   NodeSchemaRegistry.FieldSpec field, Object value) {
        switch (field.kind) {
            case INTEGER:
                if (!(value instanceof Number)) {
                    return error(nodeId, title, field.key,
                            "expected an integer, got " + typeName(value), "wrong_type");
                }
                double number = ((Number) value).doubleValue();
                if (Math.rint(number) != number) {
                    return error(nodeId, title, field.key,
                            "expected an integer, got " + number, "wrong_type");
                }
                return null;
            case FLOAT:
                return value instanceof Number ? null : error(nodeId, title, field.key,
                        "expected a number, got " + typeName(value), "wrong_type");
            case BOOLEAN:
                return value instanceof Boolean ? null : error(nodeId, title, field.key,
                        "expected true/false, got " + typeName(value), "wrong_type");
            case COMBO:
                if (value instanceof JSONObject || value instanceof JSONArray) {
                    return error(nodeId, title, field.key,
                            "expected a selectable value, got " + typeName(value), "wrong_type");
                }
                if (field.required && String.valueOf(value).trim().isEmpty()) {
                    return error(nodeId, title, field.key, "value is empty", "missing_required");
                }
                if (field.options.length() > 0 && !contains(field.options, value)) {
                    if (!matchesOptionValueType(field.options, value)) {
                        return error(nodeId, title, field.key,
                                "value type does not match the server list: " + typeName(value), "wrong_type");
                    }
                    return Issue.warning(nodeId, title, field.key,
                            "value '" + String.valueOf(value)
                                    + "' is not present in the refreshed local list; sending it to ComfyUI for server validation",
                            CODE_UNKNOWN_COMBO);
                }
                return null;
            case FILE:
                if (value instanceof JSONObject || value instanceof JSONArray) {
                    return error(nodeId, title, field.key,
                            "expected a file name, got " + typeName(value), "wrong_type");
                }
                // File option lists are snapshots of input/ and can be stale immediately
                // after an upload. Existence is verified against /view separately.
                if (field.required && String.valueOf(value).trim().isEmpty()) {
                    return error(nodeId, title, field.key, "file is not selected", "missing_required");
                }
                return null;
            case STRING:
                return value instanceof String ? null : error(nodeId, title, field.key,
                        "expected text, got " + typeName(value), "wrong_type");
            case UNKNOWN:
            default:
                return null;
        }
    }

    private static Issue error(String nodeId, String title, String key,
                               String message, String code) {
        return new Issue(nodeId, title, key, message, code, true);
    }

    private static boolean contains(JSONArray options, Object value) {
        String target = String.valueOf(value);
        for (int i = 0; i < options.length(); i++) {
            if (target.equals(String.valueOf(options.opt(i)))) return true;
        }
        return false;
    }

    private static boolean matchesOptionValueType(JSONArray options, Object value) {
        if (value == null || value == JSONObject.NULL) return false;
        for (int i = 0; i < options.length(); i++) {
            Object option = options.opt(i);
            if (option == null || option == JSONObject.NULL) continue;
            if (value instanceof String) return option instanceof String;
            if (value instanceof Number) return option instanceof Number;
            if (value instanceof Boolean) return option instanceof Boolean;
            return option.getClass().equals(value.getClass());
        }
        return true;
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
