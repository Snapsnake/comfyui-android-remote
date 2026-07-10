package com.snapsnake.comfyremote.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;

/**
 * Converts the app's editable dotted representation of ComfyUI v3 dynamic
 * fields into the nested dictionaries expected by node execute methods.
 *
 * The editor intentionally keeps paths such as resize_type.width flat because
 * they are convenient to render and update independently. They must never be
 * sent as top-level keyword arguments. Immediately before /prompt, this class
 * converts them to:
 *
 * resize_type: {
 *   resize_type: "scale dimensions",
 *   width: ...,
 *   height: ...,
 *   crop: ...
 * }
 */
public final class DynamicExecutionPrompt {
    private static final String DYNAMIC_COMBO = "COMFY_DYNAMICCOMBO_V3";
    private static final String DYNAMIC_SLOT = "COMFY_DYNAMICSLOT_V3";

    private DynamicExecutionPrompt() {}

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
            packTopLevelSection(inputs, schema.optJSONObject("required"));
            packTopLevelSection(inputs, schema.optJSONObject("optional"));
        }
        return out;
    }

    private static void packTopLevelSection(JSONObject flatInputs, JSONObject section) throws JSONException {
        if (section == null) return;
        Iterator<String> keys = section.keys();
        while (keys.hasNext()) {
            String local = keys.next();
            JSONArray spec = section.optJSONArray(local);
            if (spec == null) continue;
            String type = typeOf(spec);
            JSONObject config = spec.optJSONObject(1);
            if (DYNAMIC_COMBO.equals(type)) {
                Object raw = flatInputs.opt(local);
                JSONObject packed = buildDynamicCombo(flatInputs, local, local, config, raw);
                removePrefixed(flatInputs, local + ".");
                flatInputs.put(local, packed);
            } else if (DYNAMIC_SLOT.equals(type)) {
                Object raw = flatInputs.opt(local);
                JSONObject packed = buildDynamicSlot(flatInputs, local, config, raw);
                removePrefixed(flatInputs, local + ".");
                flatInputs.put(local, packed);
            }
        }
    }

    private static JSONObject buildDynamicCombo(JSONObject flatInputs, String flatKey,
                                                String selectorName, JSONObject config,
                                                Object rawValue) throws JSONException {
        JSONObject existing = rawValue instanceof JSONObject ? cloneObject((JSONObject) rawValue) : new JSONObject();
        Object selected;
        if (existing.has(selectorName)) selected = existing.opt(selectorName);
        else if (existing.has("value")) selected = existing.opt("value");
        else if (rawValue != null && rawValue != JSONObject.NULL && !(rawValue instanceof JSONObject)) selected = rawValue;
        else selected = firstDynamicOptionKey(config);

        JSONObject packed = new JSONObject();
        packed.put(selectorName, cloneValue(selected == null ? "" : selected));

        JSONObject option = dynamicOption(config, selected);
        JSONObject nestedSchema = option == null ? null : option.optJSONObject("inputs");
        if (nestedSchema != null) {
            fillNestedSection(packed, existing, flatInputs, nestedSchema.optJSONObject("required"), flatKey + ".");
            fillNestedSection(packed, existing, flatInputs, nestedSchema.optJSONObject("optional"), flatKey + ".");
        } else {
            // Unknown future option: preserve its already nested payload instead
            // of discarding data merely because this client has an older schema.
            Iterator<String> existingKeys = existing.keys();
            while (existingKeys.hasNext()) {
                String key = existingKeys.next();
                if (selectorName.equals(key) || "value".equals(key)) continue;
                packed.put(key, cloneValue(existing.opt(key)));
            }
        }
        return packed;
    }

    private static JSONObject buildDynamicSlot(JSONObject flatInputs, String flatKey,
                                               JSONObject config, Object rawValue) throws JSONException {
        JSONObject existing = rawValue instanceof JSONObject ? cloneObject((JSONObject) rawValue) : new JSONObject();
        JSONObject packed = new JSONObject();
        JSONObject nestedSchema = config == null ? null : config.optJSONObject("inputs");
        if (nestedSchema != null) {
            fillNestedSection(packed, existing, flatInputs, nestedSchema.optJSONObject("required"), flatKey + ".");
            fillNestedSection(packed, existing, flatInputs, nestedSchema.optJSONObject("optional"), flatKey + ".");
        } else {
            Iterator<String> keys = existing.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                packed.put(key, cloneValue(existing.opt(key)));
            }
        }
        return packed;
    }

    private static void fillNestedSection(JSONObject target, JSONObject existingParent,
                                          JSONObject flatInputs, JSONObject section,
                                          String flatPrefix) throws JSONException {
        if (section == null) return;
        Iterator<String> keys = section.keys();
        while (keys.hasNext()) {
            String local = keys.next();
            String flatKey = flatPrefix + local;
            JSONArray spec = section.optJSONArray(local);
            if (spec == null) continue;
            String type = typeOf(spec);
            JSONObject config = spec.optJSONObject(1);
            Object existing = existingParent.opt(local);

            if (DYNAMIC_COMBO.equals(type)) {
                Object raw = flatInputs.has(flatKey) ? flatInputs.opt(flatKey) : existing;
                target.put(local, buildDynamicCombo(flatInputs, flatKey, local, config, raw));
                continue;
            }
            if (DYNAMIC_SLOT.equals(type)) {
                Object raw = flatInputs.has(flatKey) ? flatInputs.opt(flatKey) : existing;
                target.put(local, buildDynamicSlot(flatInputs, flatKey, config, raw));
                continue;
            }

            if (flatInputs.has(flatKey)) {
                target.put(local, cloneValue(flatInputs.opt(flatKey)));
            } else if (existingParent.has(local)) {
                target.put(local, cloneValue(existing));
            } else {
                Object fallback = schemaDefault(spec);
                if (fallback != JSONObject.NULL) target.put(local, cloneValue(fallback));
            }
        }
    }

    private static Object schemaDefault(JSONArray spec) {
        if (spec == null) return JSONObject.NULL;
        JSONObject config = spec.optJSONObject(1);
        if (config != null && config.has("default")) return config.opt("default");
        Object type = spec.opt(0);
        if (type instanceof JSONArray && ((JSONArray) type).length() > 0) return ((JSONArray) type).opt(0);
        String name = String.valueOf(type == null ? "" : type).toUpperCase(Locale.US);
        if ("BOOLEAN".equals(name) || "BOOL".equals(name)) return false;
        if ("INT".equals(name)) return 0;
        if ("FLOAT".equals(name) || "NUMBER".equals(name)) return 0.0;
        if ("STRING".equals(name)) return "";
        return JSONObject.NULL;
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

    private static void removePrefixed(JSONObject object, String prefix) {
        ArrayList<String> remove = new ArrayList<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith(prefix)) remove.add(key);
        }
        for (String key : remove) object.remove(key);
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
