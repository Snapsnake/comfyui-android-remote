package com.snapsnake.comfyremote.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;

/**
 * Reconstructs positional frontend widgets_values using the same schema order
 * exposed by ComfyUI object_info.
 *
 * A converted/linked widget still occupies a position in widgets_values. It
 * must consume that position even though the API value itself comes from a
 * graph link. Using only the visible input slots shifts every later value.
 */
public final class FrontendWidgetValueMapper {
    private static final String DYNAMIC_COMBO = "COMFY_DYNAMICCOMBO_V3";
    private static final String DYNAMIC_SLOT = "COMFY_DYNAMICSLOT_V3";

    private FrontendWidgetValueMapper() {}

    public static JSONObject correctFreshPrompt(JSONObject frontendRoot,
                                                 JSONObject apiPrompt,
                                                 JSONObject objectInfo) {
        JSONObject corrected = cloneObject(apiPrompt);
        if (corrected.length() == 0 || objectInfo == null || objectInfo.length() == 0) return corrected;
        JSONObject workflow = frontendWorkflow(frontendRoot);
        if (workflow == null) return corrected;
        Map<String, JSONObject> subgraphs = subgraphMap(frontendRoot, workflow);
        applyNodes(workflow.optJSONArray("nodes"), "", subgraphs, corrected, objectInfo, false);
        return corrected;
    }

    /**
     * Removes values that old builds could have assigned to the wrong widget
     * position. Only nodes containing an actually linked/converted widget are
     * touched. Graph links and non-widget fields are preserved.
     */
    public static JSONObject sanitizeLegacyCurrent(JSONObject frontendRoot,
                                                    JSONObject currentPrompt,
                                                    JSONObject objectInfo) {
        JSONObject sanitized = cloneObject(currentPrompt);
        if (sanitized.length() == 0 || objectInfo == null || objectInfo.length() == 0) return sanitized;
        JSONObject workflow = frontendWorkflow(frontendRoot);
        if (workflow == null) return sanitized;
        Map<String, JSONObject> subgraphs = subgraphMap(frontendRoot, workflow);
        applyNodes(workflow.optJSONArray("nodes"), "", subgraphs, sanitized, objectInfo, true);
        return sanitized;
    }

    private static void applyNodes(JSONArray nodes,
                                   String prefix,
                                   Map<String, JSONObject> subgraphs,
                                   JSONObject prompt,
                                   JSONObject objectInfo,
                                   boolean sanitizeLegacy) {
        if (nodes == null) return;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null) continue;
            String id = String.valueOf(node.opt("id"));
            String type = node.optString("type", node.optString("class_type", ""));
            JSONObject subgraph = subgraphs.get(type);
            if (subgraph != null) {
                applyNodes(subgraph.optJSONArray("nodes"), prefix + id + "_", subgraphs,
                        prompt, objectInfo, sanitizeLegacy);
                continue;
            }
            if ("-10".equals(id) || "-20".equals(id) || skipNodeType(type)) continue;
            applyNode(node, prefix + id, prompt, objectInfo, sanitizeLegacy);
        }
    }

    private static void applyNode(JSONObject frontendNode,
                                  String apiNodeId,
                                  JSONObject prompt,
                                  JSONObject objectInfo,
                                  boolean sanitizeLegacy) {
        JSONObject apiNode = prompt.optJSONObject(apiNodeId);
        if (apiNode == null) return;
        String classType = apiNode.optString("class_type",
                frontendNode.optString("type", frontendNode.optString("class_type", "")));
        JSONObject definition = objectInfo.optJSONObject(classType);
        JSONObject inputSchema = definition == null ? null : definition.optJSONObject("input");
        JSONObject apiInputs = apiNode.optJSONObject("inputs");
        JSONArray values = frontendNode.optJSONArray("widgets_values");
        boolean hasLinkedWidget = hasLinkedWidgetInput(frontendNode);
        if (inputSchema == null || apiInputs == null || values == null || values.length() == 0) {
            if (!sanitizeLegacy || hasLinkedWidget) {
                applyNamedWidgets(frontendNode, apiInputs, sanitizeLegacy);
            }
            return;
        }

        ArrayList<String> frontendWidgetOrder = frontendWidgetInputOrder(frontendNode);
        ArrayList<Binding> bindings = new ArrayList<>();
        Cursor cursor = new Cursor();
        JSONObject inputOrder = definition.optJSONObject("input_order");
        collectSection(bindings, inputSchema.optJSONObject("required"),
                inputOrder == null ? null : inputOrder.optJSONArray("required"),
                "", values, cursor, frontendWidgetOrder);
        collectSection(bindings, inputSchema.optJSONObject("optional"),
                inputOrder == null ? null : inputOrder.optJSONArray("optional"),
                "", values, cursor, frontendWidgetOrder);

        if (sanitizeLegacy) {
            if (!hasLinkedWidget) return;
            for (Binding binding : bindings) {
                Object current = apiInputs.opt(binding.name);
                if (!isConnection(current)) apiInputs.remove(binding.name);
            }
            applyNamedWidgets(frontendNode, apiInputs, true);
            return;
        }

        for (Binding binding : bindings) {
            Object current = apiInputs.opt(binding.name);
            if (isConnection(current)) continue;
            try {
                apiInputs.put(binding.name, cloneValue(binding.value));
            } catch (JSONException ignored) {}
        }
        applyNamedWidgets(frontendNode, apiInputs, false);
    }

    private static void collectSection(ArrayList<Binding> out,
                                       JSONObject section,
                                       JSONArray explicitOrder,
                                       String prefix,
                                       JSONArray values,
                                       Cursor cursor,
                                       ArrayList<String> frontendWidgetOrder) {
        if (section == null) return;
        ArrayList<String> keys = orderedKeys(section, explicitOrder, prefix, frontendWidgetOrder);
        for (int index = 0; index < keys.size(); index++) {
            String local = keys.get(index);
            JSONArray spec = section.optJSONArray(local);
            if (spec == null) continue;
            String name = prefix + local;
            Object first = spec.opt(0);
            JSONObject config = spec.optJSONObject(1);
            String type = String.valueOf(first == null ? "" : first).toUpperCase(Locale.US);

            if (DYNAMIC_SLOT.equals(type)) {
                JSONObject nested = config == null ? null : config.optJSONObject("inputs");
                JSONObject nestedOrder = config == null ? null : config.optJSONObject("input_order");
                collectSection(out, nested == null ? null : nested.optJSONObject("required"),
                        nestedOrder == null ? null : nestedOrder.optJSONArray("required"),
                        name + ".", values, cursor, frontendWidgetOrder);
                collectSection(out, nested == null ? null : nested.optJSONObject("optional"),
                        nestedOrder == null ? null : nestedOrder.optJSONArray("optional"),
                        name + ".", values, cursor, frontendWidgetOrder);
                continue;
            }

            boolean consumesValue = DYNAMIC_COMBO.equals(type) || isWidgetSpec(first, type, config);
            if (!consumesValue) continue;
            if (cursor.index >= values.length()) return;

            Object nextValue = values.opt(cursor.index);
            if (!valueFitsSpec(spec, nextValue)) {
                int matchingIndex = findMatchingKey(keys, index + 1, section, nextValue);
                if (matchingIndex >= 0) {
                    String matching = keys.remove(matchingIndex);
                    keys.add(index, matching);
                    local = matching;
                    spec = section.optJSONArray(local);
                    name = prefix + local;
                    first = spec == null ? null : spec.opt(0);
                    config = spec == null ? null : spec.optJSONObject(1);
                    type = String.valueOf(first == null ? "" : first).toUpperCase(Locale.US);
                }
            }

            Object value = values.opt(cursor.index++);
            out.add(new Binding(name, value));

            if (DYNAMIC_COMBO.equals(type)) {
                JSONObject option = dynamicOption(config, value);
                JSONObject nested = option == null ? null : option.optJSONObject("inputs");
                JSONObject nestedOrder = option == null ? null : option.optJSONObject("input_order");
                collectSection(out, nested == null ? null : nested.optJSONObject("required"),
                        nestedOrder == null ? null : nestedOrder.optJSONArray("required"),
                        name + ".", values, cursor, frontendWidgetOrder);
                collectSection(out, nested == null ? null : nested.optJSONObject("optional"),
                        nestedOrder == null ? null : nestedOrder.optJSONArray("optional"),
                        name + ".", values, cursor, frontendWidgetOrder);
            }
        }
    }

    private static int findMatchingKey(ArrayList<String> keys,
                                       int start,
                                       JSONObject section,
                                       Object value) {
        for (int i = start; i < keys.size(); i++) {
            JSONArray candidate = section.optJSONArray(keys.get(i));
            if (candidate == null) continue;
            String type = String.valueOf(candidate.opt(0)).toUpperCase(Locale.US);
            if (DYNAMIC_SLOT.equals(type)) continue;
            if (valueFitsSpec(candidate, value)) return i;
        }
        return -1;
    }

    private static boolean valueFitsSpec(JSONArray spec, Object value) {
        if (spec == null || value == null || value == JSONObject.NULL) return false;
        Object first = spec.opt(0);
        JSONObject config = spec.optJSONObject(1);
        String type = String.valueOf(first == null ? "" : first).toUpperCase(Locale.US);
        if (DYNAMIC_COMBO.equals(type)) return dynamicOption(config, value) != null;
        if (first instanceof JSONArray) return arrayContains((JSONArray) first, value);
        if ("BOOLEAN".equals(type) || "BOOL".equals(type)) return value instanceof Boolean;
        if ("INT".equals(type)) {
            if (!(value instanceof Number)) return false;
            double number = ((Number) value).doubleValue();
            return Math.rint(number) == number;
        }
        if ("FLOAT".equals(type) || "NUMBER".equals(type)) return value instanceof Number;
        if ("STRING".equals(type)) return value instanceof String;
        if ("COMBO".equals(type)) {
            JSONArray options = config == null ? null : config.optJSONArray("options");
            return options == null || arrayContains(options, value);
        }
        if (config != null && config.has("default")) {
            Object fallback = config.opt("default");
            if (fallback instanceof Boolean) return value instanceof Boolean;
            if (fallback instanceof Number) return value instanceof Number;
            if (fallback instanceof String) return value instanceof String;
        }
        return true;
    }

    private static boolean arrayContains(JSONArray array, Object value) {
        if (array == null) return false;
        String expected = String.valueOf(value);
        for (int i = 0; i < array.length(); i++) {
            if (expected.equals(String.valueOf(array.opt(i)))) return true;
        }
        return false;
    }

    private static ArrayList<String> orderedKeys(JSONObject section,
                                                 JSONArray explicitOrder,
                                                 String prefix,
                                                 ArrayList<String> frontendWidgetOrder) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (explicitOrder != null) {
            for (int i = 0; i < explicitOrder.length(); i++) {
                String key = explicitOrder.optString(i, "");
                if (!key.isEmpty() && section.has(key)) ordered.add(key);
            }
        }
        if (explicitOrder == null && frontendWidgetOrder != null) {
            for (String fullName : frontendWidgetOrder) {
                if (!fullName.startsWith(prefix)) continue;
                String remainder = fullName.substring(prefix.length());
                if (remainder.indexOf('.') >= 0) continue;
                if (section.has(remainder)) ordered.add(remainder);
            }
        }
        Iterator<String> keys = section.keys();
        while (keys.hasNext()) ordered.add(keys.next());
        return new ArrayList<>(ordered);
    }

    private static ArrayList<String> frontendWidgetInputOrder(JSONObject node) {
        ArrayList<String> names = new ArrayList<>();
        JSONArray inputs = node.optJSONArray("inputs");
        if (inputs == null) return names;
        for (int i = 0; i < inputs.length(); i++) {
            JSONObject input = inputs.optJSONObject(i);
            if (input == null) continue;
            JSONObject widget = input.optJSONObject("widget");
            if (widget == null) continue;
            String name = widget.optString("name", input.optString("name", ""));
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    private static boolean isWidgetSpec(Object first, String type, JSONObject config) {
        if (config != null && (config.optBoolean("forceInput", false)
                || config.optBoolean("force_input", false))) return false;
        if (first instanceof JSONArray) return true;
        if ("INT".equals(type) || "FLOAT".equals(type) || "NUMBER".equals(type)
                || "STRING".equals(type) || "BOOLEAN".equals(type) || "BOOL".equals(type)
                || "COMBO".equals(type)) return true;
        return config != null && config.has("default");
    }

    private static JSONObject dynamicOption(JSONObject config, Object selected) {
        JSONArray options = config == null ? null : config.optJSONArray("options");
        if (options == null) return null;
        String key = selected == null || selected == JSONObject.NULL ? "" : String.valueOf(selected);
        for (int i = 0; i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option != null && key.equals(String.valueOf(option.opt("key")))) return option;
        }
        return null;
    }

    private static void applyNamedWidgets(JSONObject frontendNode,
                                          JSONObject apiInputs,
                                          boolean sanitizeLegacy) {
        if (apiInputs == null) return;
        JSONArray widgets = frontendNode.optJSONArray("widgets");
        if (widgets == null) return;
        for (int i = 0; i < widgets.length(); i++) {
            JSONObject widget = widgets.optJSONObject(i);
            if (widget == null) continue;
            String name = widget.optString("name", "");
            if (name.isEmpty() || "upload".equalsIgnoreCase(name)
                    || "button".equalsIgnoreCase(widget.optString("type", ""))) continue;
            Object current = apiInputs.opt(name);
            if (isConnection(current)) continue;
            if (sanitizeLegacy) {
                apiInputs.remove(name);
            } else if (widget.has("value")) {
                try { apiInputs.put(name, cloneValue(widget.opt("value"))); }
                catch (JSONException ignored) {}
            }
        }
    }

    private static boolean hasLinkedWidgetInput(JSONObject node) {
        JSONArray inputs = node.optJSONArray("inputs");
        if (inputs == null) return false;
        for (int i = 0; i < inputs.length(); i++) {
            JSONObject input = inputs.optJSONObject(i);
            if (input == null || input.optJSONObject("widget") == null) continue;
            if (input.has("link") && !input.isNull("link")) return true;
        }
        return false;
    }

    private static JSONObject frontendWorkflow(JSONObject raw) {
        if (raw == null) return null;
        if (raw.optJSONArray("nodes") != null) return raw;
        JSONObject original = raw.optJSONObject("original");
        if (original != null && original.optJSONArray("nodes") != null) return original;
        JSONObject workflow = raw.optJSONObject("workflow");
        if (workflow != null && workflow.optJSONArray("nodes") != null) return workflow;
        JSONObject extra = raw.optJSONObject("extra");
        workflow = extra == null ? null : extra.optJSONObject("workflow");
        if (workflow != null && workflow.optJSONArray("nodes") != null) return workflow;
        return null;
    }

    private static Map<String, JSONObject> subgraphMap(JSONObject root, JSONObject workflow) {
        HashMap<String, JSONObject> out = new HashMap<>();
        addSubgraphs(out, root == null ? null : root.optJSONObject("definitions"));
        addSubgraphs(out, workflow == null ? null : workflow.optJSONObject("definitions"));
        JSONObject original = root == null ? null : root.optJSONObject("original");
        addSubgraphs(out, original == null ? null : original.optJSONObject("definitions"));
        return out;
    }

    private static void addSubgraphs(Map<String, JSONObject> out, JSONObject definitions) {
        JSONArray subgraphs = definitions == null ? null : definitions.optJSONArray("subgraphs");
        if (subgraphs == null) return;
        for (int i = 0; i < subgraphs.length(); i++) {
            JSONObject subgraph = subgraphs.optJSONObject(i);
            if (subgraph == null) continue;
            String id = subgraph.optString("id", "");
            if (!id.isEmpty()) out.put(id, subgraph);
        }
    }

    private static boolean isConnection(Object value) {
        if (!(value instanceof JSONArray)) return false;
        JSONArray link = (JSONArray) value;
        return link.length() >= 2
                && (link.opt(0) instanceof String || link.opt(0) instanceof Number)
                && link.opt(1) instanceof Number;
    }

    private static boolean skipNodeType(String type) {
        String value = type == null ? "" : type.toLowerCase(Locale.US);
        return value.contains("markdown") || value.contains("note") || "reroute".equals(value);
    }

    private static Object cloneValue(Object value) {
        if (value instanceof JSONObject) return cloneObject((JSONObject) value);
        if (value instanceof JSONArray) {
            try { return new JSONArray(value.toString()); }
            catch (Exception ignored) { return new JSONArray(); }
        }
        return value;
    }

    private static JSONObject cloneObject(JSONObject value) {
        if (value == null) return new JSONObject();
        try { return new JSONObject(value.toString()); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    private static final class Cursor { int index; }

    private static final class Binding {
        final String name;
        final Object value;

        Binding(String name, Object value) {
            this.name = name;
            this.value = value;
        }
    }
}
