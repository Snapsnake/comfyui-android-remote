package com.snapsnake.comfyremote.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public final class NodeSchemaRegistry {
    public enum Kind { STRING, INTEGER, FLOAT, BOOLEAN, COMBO, FILE, UNKNOWN }

    public static final class FieldSpec {
        public final String nodeId;
        public final String nodeClass;
        public final String key;
        public final Kind kind;
        public final boolean required;
        public final boolean connected;
        public final boolean multiline;
        public final Object value;
        public final JSONArray options;
        public final JSONObject config;

        FieldSpec(String nodeId, String nodeClass, String key, Kind kind, boolean required,
                  boolean connected, boolean multiline, Object value, JSONArray options, JSONObject config) {
            this.nodeId = nodeId;
            this.nodeClass = nodeClass;
            this.key = key;
            this.kind = kind;
            this.required = required;
            this.connected = connected;
            this.multiline = multiline;
            this.value = value;
            this.options = options == null ? new JSONArray() : options;
            this.config = config == null ? new JSONObject() : config;
        }
    }

    public interface NodeAdapter {
        boolean supports(String classType, JSONObject definition);
        List<FieldSpec> fields(String nodeId, JSONObject node, JSONObject definition);
    }

    private final JSONObject objectInfo;
    private final ArrayList<NodeAdapter> adapters = new ArrayList<>();

    public NodeSchemaRegistry(JSONObject objectInfo) {
        this.objectInfo = cloneObject(objectInfo);
        adapters.add(new CoreSchemaAdapter());
    }

    public void registerFirst(NodeAdapter adapter) {
        if (adapter != null) adapters.add(0, adapter);
    }

    public JSONObject definition(String classType) {
        JSONObject def = objectInfo.optJSONObject(classType == null ? "" : classType);
        return def == null ? new JSONObject() : def;
    }

    public List<FieldSpec> fieldsForNode(String nodeId, JSONObject node) {
        if (node == null) return Collections.emptyList();
        String classType = node.optString("class_type", "");
        JSONObject definition = definition(classType);
        for (NodeAdapter adapter : adapters) {
            try {
                if (adapter.supports(classType, definition)) return adapter.fields(nodeId, node, definition);
            } catch (Exception ignored) {}
        }
        return Collections.emptyList();
    }

    public List<String> classTypes() {
        ArrayList<String> out = new ArrayList<>();
        Iterator<String> keys = objectInfo.keys();
        while (keys.hasNext()) out.add(keys.next());
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public String displayName(String classType) {
        JSONObject def = definition(classType);
        String name = def.optString("display_name", "");
        return name.trim().isEmpty() ? classType : name;
    }

    public int materializeMissingDefaults(JSONObject prompt) {
        if (prompt == null) return 0;
        int added = 0;
        Iterator<String> ids = prompt.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            JSONObject node = prompt.optJSONObject(id);
            if (node == null) continue;
            JSONObject inputs = node.optJSONObject("inputs");
            try {
                if (inputs == null) {
                    inputs = new JSONObject();
                    node.put("inputs", inputs);
                }
                for (FieldSpec field : fieldsForNode(id, node)) {
                    if (field.connected || inputs.has(field.key)) continue;
                    inputs.put(field.key, field.value == JSONObject.NULL ? "" : field.value);
                    added++;
                }
            } catch (Exception ignored) {}
        }
        return added;
    }

    private final class CoreSchemaAdapter implements NodeAdapter {
        @Override public boolean supports(String classType, JSONObject definition) { return true; }

        @Override public List<FieldSpec> fields(String nodeId, JSONObject node, JSONObject definition) {
            ArrayList<FieldSpec> out = new ArrayList<>();
            JSONObject input = definition.optJSONObject("input");
            JSONObject required = input == null ? null : input.optJSONObject("required");
            JSONObject optional = input == null ? null : input.optJSONObject("optional");
            addSection(out, nodeId, node, required, true);
            addSection(out, nodeId, node, optional, false);

            // Compatibility path for existing primitive API-prompt values omitted by old/custom schemas.
            JSONObject inputs = node.optJSONObject("inputs");
            if (inputs != null) {
                Iterator<String> keys = inputs.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (contains(out, key)) continue;
                    Object value = inputs.opt(key);
                    if (!isPrimitive(value)) continue;
                    Kind kind = inferKind(value);
                    out.add(new FieldSpec(nodeId, node.optString("class_type", ""), key, kind,
                            false, false, isMultilineKey(key, new JSONObject()), value,
                            new JSONArray(), new JSONObject()));
                }
            }
            return out;
        }
    }

    private void addSection(ArrayList<FieldSpec> out, String nodeId, JSONObject node, JSONObject section, boolean required) {
        if (section == null) return;
        JSONObject inputs = node.optJSONObject("inputs");
        if (inputs == null) inputs = new JSONObject();
        Iterator<String> keys = section.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object raw = section.opt(key);
            if (!(raw instanceof JSONArray)) continue;
            JSONArray spec = (JSONArray) raw;
            Object typeRaw = spec.opt(0);
            JSONObject config = spec.optJSONObject(1);
            JSONArray options = typeRaw instanceof JSONArray ? (JSONArray) typeRaw : new JSONArray();
            Kind kind = kindOf(typeRaw, key, config);
            Object current = inputs.has(key) ? inputs.opt(key) : JSONObject.NULL;
            boolean connected = isConnection(current);
            Object value = connected ? JSONObject.NULL : current;
            if (value == JSONObject.NULL) value = schemaDefault(kind, options, config);
            boolean multiline = kind == Kind.STRING && isMultilineKey(key, config);
            out.add(new FieldSpec(nodeId, node.optString("class_type", ""), key, kind,
                    required, connected, multiline, value, cloneArray(options), cloneObject(config)));
        }
    }

    private static Kind kindOf(Object typeRaw, String key, JSONObject config) {
        if (typeRaw instanceof JSONArray) return Kind.COMBO;
        String type = String.valueOf(typeRaw == null ? "" : typeRaw).toUpperCase(Locale.US);
        if ("STRING".equals(type)) {
            String lower = key == null ? "" : key.toLowerCase(Locale.US);
            boolean upload = config != null && (config.optBoolean("image_upload", false) || config.optBoolean("upload", false));
            if (upload || lower.equals("image") || lower.equals("mask") || lower.equals("file") || lower.equals("upload") || lower.endsWith("_image") || lower.endsWith("_mask")) return Kind.FILE;
            return Kind.STRING;
        }
        if ("INT".equals(type)) return Kind.INTEGER;
        if ("FLOAT".equals(type) || "NUMBER".equals(type)) return Kind.FLOAT;
        if ("BOOLEAN".equals(type) || "BOOL".equals(type)) return Kind.BOOLEAN;
        if ("COMBO".equals(type)) return Kind.COMBO;
        return Kind.UNKNOWN;
    }

    private static Object schemaDefault(Kind kind, JSONArray options, JSONObject config) {
        if (config != null && config.has("default")) return config.opt("default");
        if (kind == Kind.COMBO && options != null && options.length() > 0) return options.opt(0);
        if (kind == Kind.BOOLEAN) return false;
        if (kind == Kind.INTEGER) return 0;
        if (kind == Kind.FLOAT) return 0.0;
        return "";
    }

    private static boolean isConnection(Object value) {
        if (!(value instanceof JSONArray)) return false;
        JSONArray array = (JSONArray) value;
        if (array.length() < 2) return false;
        Object node = array.opt(0);
        Object slot = array.opt(1);
        return (node instanceof String || node instanceof Number) && slot instanceof Number;
    }

    private static boolean isMultilineKey(String key, JSONObject config) {
        if (config != null && config.optBoolean("multiline", false)) return true;
        String k = key == null ? "" : key.toLowerCase(Locale.US);
        return k.contains("prompt") || k.equals("text") || k.endsWith("_text") || k.contains("negative");
    }

    private static Kind inferKind(Object value) {
        if (value instanceof Boolean) return Kind.BOOLEAN;
        if (value instanceof Integer || value instanceof Long) return Kind.INTEGER;
        if (value instanceof Number) return Kind.FLOAT;
        return Kind.STRING;
    }

    private static boolean contains(List<FieldSpec> fields, String key) {
        for (FieldSpec field : fields) if (field.key.equals(key)) return true;
        return false;
    }

    private static boolean isPrimitive(Object value) {
        return value == JSONObject.NULL || value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    private static JSONObject cloneObject(JSONObject object) {
        if (object == null) return new JSONObject();
        try { return new JSONObject(object.toString()); }
        catch (Exception e) { return new JSONObject(); }
    }

    private static JSONArray cloneArray(JSONArray array) {
        if (array == null) return new JSONArray();
        try { return new JSONArray(array.toString()); }
        catch (Exception e) { return new JSONArray(); }
    }
}
