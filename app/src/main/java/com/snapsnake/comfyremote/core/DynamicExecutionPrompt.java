package com.snapsnake.comfyremote.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Produces the flat prompt format used by the official ComfyUI frontend for
 * v3 dynamic inputs.
 *
 * ComfyUI expects the selector and its children as separate prompt keys:
 *
 *   resize_type = "scale dimensions"
 *   resize_type.width = ...
 *   resize_type.height = ...
 *   resize_type.crop = ...
 *
 * The backend uses the live node schema to rebuild the nested resize_type
 * dictionary before calling the node's execute method. Sending a JSON object
 * directly as resize_type makes validation discard the dynamic input.
 *
 * Some exported frontend workflows omit the dynamic selector input slot while
 * retaining its first widgets_values entry. Older app builds then shifted that
 * value into another field. This class repairs an invalid/missing selector by
 * matching the present dotted child paths to the best schema option.
 */
public final class DynamicExecutionPrompt {
    private static final String DYNAMIC_COMBO = "COMFY_DYNAMICCOMBO_V3";
    private static final String DYNAMIC_SLOT = "COMFY_DYNAMICSLOT_V3";

    private DynamicExecutionPrompt() {}

    /**
     * Kept as pack() for binary/source compatibility with 0.14.7. The result is
     * deliberately flat, not nested.
     */
    public static JSONObject pack(JSONObject editablePrompt, JSONObject objectInfo) throws JSONException {
        JSONObject out = cloneObject(editablePrompt);
        if (out.length() == 0 || objectInfo == null || objectInfo.length() == 0) return out;

        Iterator<String> ids = out.keys();
        while (ids.hasNext()) {
            JSONObject node = out.optJSONObject(ids.next());
            if (node == null) continue;
            JSONObject inputs = node.optJSONObject("inputs");
            JSONObject definition = objectInfo.optJSONObject(node.optString("class_type", ""));
            JSONObject schema = definition == null ? null : definition.optJSONObject("input");
            if (inputs == null || schema == null) continue;
            normalizeSection(inputs, schema.optJSONObject("required"), "");
            normalizeSection(inputs, schema.optJSONObject("optional"), "");
        }
        return out;
    }

    private static void normalizeSection(JSONObject inputs, JSONObject section,
                                         String prefix) throws JSONException {
        if (section == null) return;
        Iterator<String> keys = section.keys();
        while (keys.hasNext()) {
            String local = keys.next();
            String flatKey = prefix + local;
            JSONArray spec = section.optJSONArray(local);
            if (spec == null) continue;
            String type = typeOf(spec);
            JSONObject config = spec.optJSONObject(1);

            if (DYNAMIC_COMBO.equals(type)) {
                flattenComboObject(inputs, flatKey, local);

                Object selected = inputs.opt(flatKey);
                JSONObject option = dynamicOption(config, selected);
                if (option == null) {
                    option = bestMatchingOption(config, inputs, flatKey);
                    Object key = option == null ? firstDynamicOptionKey(config) : option.opt("key");
                    inputs.put(flatKey, key == null || key == JSONObject.NULL ? "" : key);
                }

                option = dynamicOption(config, inputs.opt(flatKey));
                JSONObject nested = option == null ? null : option.optJSONObject("inputs");
                Set<String> allowed = schemaPaths(nested);
                removeStaleChildren(inputs, flatKey + ".", allowed);
                normalizeSection(inputs, nested == null ? null : nested.optJSONObject("required"), flatKey + ".");
                normalizeSection(inputs, nested == null ? null : nested.optJSONObject("optional"), flatKey + ".");
                continue;
            }

            if (DYNAMIC_SLOT.equals(type)) {
                flattenSlotObject(inputs, flatKey);
                JSONObject nested = config == null ? null : config.optJSONObject("inputs");
                normalizeSection(inputs, nested == null ? null : nested.optJSONObject("required"), flatKey + ".");
                normalizeSection(inputs, nested == null ? null : nested.optJSONObject("optional"), flatKey + ".");
            }
        }
    }

    private static void flattenComboObject(JSONObject inputs, String flatKey,
                                           String selectorName) throws JSONException {
        Object raw = inputs.opt(flatKey);
        if (!(raw instanceof JSONObject)) return;
        JSONObject nested = (JSONObject) raw;
        Object selected = nested.has(selectorName) ? nested.opt(selectorName) : nested.opt("value");
        inputs.put(flatKey, selected == null || selected == JSONObject.NULL ? "" : cloneValue(selected));
        Iterator<String> keys = nested.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (selectorName.equals(key) || "value".equals(key)) continue;
            inputs.put(flatKey + "." + key, cloneValue(nested.opt(key)));
        }
    }

    private static void flattenSlotObject(JSONObject inputs, String flatKey) throws JSONException {
        Object raw = inputs.opt(flatKey);
        if (!(raw instanceof JSONObject)) return;
        JSONObject nested = (JSONObject) raw;
        inputs.remove(flatKey);
        Iterator<String> keys = nested.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            inputs.put(flatKey + "." + key, cloneValue(nested.opt(key)));
        }
    }

    private static JSONObject bestMatchingOption(JSONObject config, JSONObject inputs,
                                                  String flatKey) {
        JSONArray options = config == null ? null : config.optJSONArray("options");
        if (options == null || options.length() == 0) return null;
        JSONObject best = options.optJSONObject(0);
        int bestScore = -1;
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option == null) continue;
            Set<String> paths = schemaPaths(option.optJSONObject("inputs"));
            int score = 0;
            for (String path : paths) {
                if (inputs.has(flatKey + "." + path)) score++;
            }
            if (score > bestScore) {
                best = option;
                bestScore = score;
            }
        }
        return best;
    }

    private static Set<String> schemaPaths(JSONObject inputSchema) {
        HashSet<String> out = new HashSet<>();
        if (inputSchema == null) return out;
        collectSchemaPaths(out, inputSchema.optJSONObject("required"), "");
        collectSchemaPaths(out, inputSchema.optJSONObject("optional"), "");
        return out;
    }

    private static void collectSchemaPaths(Set<String> out, JSONObject section,
                                           String prefix) {
        if (section == null) return;
        Iterator<String> keys = section.keys();
        while (keys.hasNext()) {
            String local = keys.next();
            String path = prefix + local;
            out.add(path);
            JSONArray spec = section.optJSONArray(local);
            if (spec == null) continue;
            String type = typeOf(spec);
            JSONObject config = spec.optJSONObject(1);
            if (DYNAMIC_COMBO.equals(type)) {
                JSONArray options = config == null ? null : config.optJSONArray("options");
                if (options == null) continue;
                for (int i = 0; i < options.length(); i++) {
                    JSONObject option = options.optJSONObject(i);
                    JSONObject nested = option == null ? null : option.optJSONObject("inputs");
                    collectSchemaPaths(out, nested == null ? null : nested.optJSONObject("required"), path + ".");
                    collectSchemaPaths(out, nested == null ? null : nested.optJSONObject("optional"), path + ".");
                }
            } else if (DYNAMIC_SLOT.equals(type)) {
                JSONObject nested = config == null ? null : config.optJSONObject("inputs");
                collectSchemaPaths(out, nested == null ? null : nested.optJSONObject("required"), path + ".");
                collectSchemaPaths(out, nested == null ? null : nested.optJSONObject("optional"), path + ".");
            }
        }
    }

    private static void removeStaleChildren(JSONObject inputs, String prefix,
                                            Set<String> allowed) {
        ArrayList<String> remove = new ArrayList<>();
        Iterator<String> keys = inputs.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.startsWith(prefix)) continue;
            String relative = key.substring(prefix.length());
            if (!allowed.contains(relative)) remove.add(key);
        }
        for (String key : remove) inputs.remove(key);
    }

    private static JSONObject dynamicOption(JSONObject config, Object selected) {
        JSONArray options = config == null ? null : config.optJSONArray("options");
        if (options == null) return null;
        String selectedKey = selected == null || selected == JSONObject.NULL ? "" : String.valueOf(selected);
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option != null && selectedKey.equals(String.valueOf(option.opt("key")))) return option;
        }
        return null;
    }

    private static Object firstDynamicOptionKey(JSONObject config) {
        JSONArray options = config == null ? null : config.optJSONArray("options");
        JSONObject first = options == null ? null : options.optJSONObject(0);
        return first == null ? "" : first.opt("key");
    }

    private static String typeOf(JSONArray spec) {
        return String.valueOf(spec == null ? "" : spec.opt(0)).toUpperCase(Locale.US);
    }

    private static Object cloneValue(Object value) {
        if (value instanceof JSONObject) return cloneObject((JSONObject) value);
        if (value instanceof JSONArray) {
            try { return new JSONArray(value.toString()); }
            catch (Exception ignored) { return new JSONArray(); }
        }
        return value;
    }

    private static JSONObject cloneObject(JSONObject object) {
        if (object == null) return new JSONObject();
        try { return new JSONObject(object.toString()); }
        catch (Exception e) { return new JSONObject(); }
    }
}
