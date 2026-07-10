package com.snapsnake.comfyremote;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

public class ComfyWorkflowConverter {
    private static class Ref {
        final String nodeId;
        final int slot;
        Ref(String nodeId, int slot) { this.nodeId = nodeId; this.slot = slot; }
        JSONArray json() { return new JSONArray().put(nodeId).put(slot); }
    }

    private static class Link {
        final String id;
        final String originId;
        final int originSlot;
        final String targetId;
        final int targetSlot;
        Link(String id, String originId, int originSlot, String targetId, int targetSlot) {
            this.id = id; this.originId = originId; this.originSlot = originSlot; this.targetId = targetId; this.targetSlot = targetSlot;
        }
    }

    private static class Cursor { int index = 0; }

    public static JSONObject importResult(JSONObject raw, JSONObject objectInfo) throws JSONException {
        JSONObject result = new JSONObject();
        JSONObject prompt = toApiPrompt(raw, objectInfo);
        result.put("ok", true);
        result.put("prompt", prompt);
        result.put("options", buildOptions(prompt, objectInfo));
        result.put("mode", "workflow-json");
        return result;
    }

    public static JSONObject toApiPrompt(JSONObject raw, JSONObject objectInfo) throws JSONException {
        JSONObject frontend = frontendWorkflow(raw);
        if (frontend != null) {
            JSONObject converted = convertFrontend(frontend, raw, objectInfo);
            if (converted.length() > 0) return normalizeDynamicInputShapes(converted, objectInfo);
        }
        JSONObject extra = raw.optJSONObject("extra");
        JSONObject p = extra == null ? null : extra.optJSONObject("prompt");
        if (looksApiPrompt(p)) return normalizeDynamicInputShapes(cloneObject(p), objectInfo);
        p = raw.optJSONObject("prompt");
        if (looksApiPrompt(p)) return normalizeDynamicInputShapes(cloneObject(p), objectInfo);
        JSONObject w = raw.optJSONObject("workflow");
        if (looksApiPrompt(w)) return normalizeDynamicInputShapes(cloneObject(w), objectInfo);
        if (looksApiPrompt(raw)) return normalizeDynamicInputShapes(cloneObject(raw), objectInfo);
        throw new JSONException("No ComfyUI API prompt or convertible frontend workflow found");
    }

    /**
     * Rebuilds a prompt from the preserved frontend workflow and then reapplies
     * only schema-valid edits from the currently saved API prompt. This repairs
     * workflows imported by older app versions without discarding user edits.
     */
    public static JSONObject repairPrompt(JSONObject currentPrompt, JSONObject originalWorkflow,
                                          JSONObject objectInfo) throws JSONException {
        JSONObject current = normalizeDynamicInputShapes(cloneObject(currentPrompt), objectInfo);
        if (originalWorkflow == null || originalWorkflow.length() == 0 || objectInfo == null || objectInfo.length() == 0) {
            return current;
        }
        JSONObject fresh;
        try {
            fresh = toApiPrompt(originalWorkflow, objectInfo);
        } catch (Exception ignored) {
            return current;
        }
        return mergeValidPromptValues(fresh, current, objectInfo);
    }

    public static JSONObject mergeValidPromptValues(JSONObject freshPrompt, JSONObject currentPrompt,
                                                     JSONObject objectInfo) throws JSONException {
        JSONObject repaired = cloneObject(freshPrompt);
        if (currentPrompt == null) return repaired;
        Iterator<String> ids = currentPrompt.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            JSONObject currentNode = currentPrompt.optJSONObject(id);
            if (currentNode == null) continue;
            JSONObject freshNode = repaired.optJSONObject(id);
            if (freshNode == null) {
                repaired.put(id, cloneObject(currentNode));
                continue;
            }
            String classType = currentNode.optString("class_type", freshNode.optString("class_type", ""));
            JSONObject definition = objectInfo == null ? null : objectInfo.optJSONObject(classType);
            JSONObject currentInputs = currentNode.optJSONObject("inputs");
            JSONObject freshInputs = freshNode.optJSONObject("inputs");
            if (freshInputs == null) {
                freshInputs = new JSONObject();
                freshNode.put("inputs", freshInputs);
            }
            if (currentInputs != null) {
                Iterator<String> keys = currentInputs.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object value = currentInputs.opt(key);
                    if (definition == null || validInputValue(definition, key, value, currentInputs, freshInputs)) {
                        freshInputs.put(key, cloneValue(value));
                    }
                }
            }
            JSONObject meta = currentNode.optJSONObject("_meta");
            if (meta != null) freshNode.put("_meta", cloneObject(meta));
            freshNode.put("class_type", classType);
        }
        return normalizeDynamicInputShapes(repaired, objectInfo);
    }

    private static JSONObject frontendWorkflow(JSONObject raw) {
        if (raw == null) return null;
        if (raw.optJSONArray("nodes") != null) return raw;
        JSONObject w = raw.optJSONObject("workflow");
        if (w != null && w.optJSONArray("nodes") != null) return w;
        JSONObject extra = raw.optJSONObject("extra");
        w = extra == null ? null : extra.optJSONObject("workflow");
        if (w != null && w.optJSONArray("nodes") != null) return w;
        JSONObject p = raw.optJSONObject("prompt");
        if (p != null && p.optJSONArray("nodes") != null) return p;
        return null;
    }

    private static JSONObject convertFrontend(JSONObject wf, JSONObject root, JSONObject objectInfo) throws JSONException {
        JSONArray nodes = wf.optJSONArray("nodes");
        if (nodes == null) return new JSONObject();
        Map<String, JSONObject> subgraphs = subgraphMap(root);
        Map<String, Link> topLinks = linksById(wf.optJSONArray("links"));
        Map<String, Ref> subgraphOutputRefs = new HashMap<>();
        JSONObject out = new JSONObject();

        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n == null) continue;
            String id = n.optString("id", "");
            String type = n.optString("type", n.optString("class_type", ""));
            JSONObject sg = subgraphs.get(type);
            if (sg == null) continue;
            fillSubgraphOutputs(id, sg, subgraphOutputRefs);
        }

        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n == null) continue;
            String id = n.optString("id", "");
            String type = n.optString("type", n.optString("class_type", ""));
            if (skipNodeType(type)) continue;
            JSONObject sg = subgraphs.get(type);
            if (sg != null) {
                convertSubgraphNode(out, n, sg, topLinks, subgraphOutputRefs, objectInfo);
            } else {
                JSONObject item = apiNodeFromFrontend(n, objectInfo, linkId -> resolveTopLink(linkId, topLinks, subgraphOutputRefs));
                attachGroupMeta(item, wf, n, null);
                out.put(id, item);
            }
        }
        return out;
    }

    private interface Resolver { Ref resolve(Object linkId) throws JSONException; }

    private static JSONObject apiNodeFromFrontend(JSONObject n, JSONObject objectInfo, Resolver resolver) throws JSONException {
        String cls = n.optString("type", n.optString("class_type", ""));
        JSONObject item = new JSONObject();
        item.put("class_type", cls);
        JSONObject inputs = new JSONObject();
        JSONArray frontendInputs = n.optJSONArray("inputs");
        addLinkedInputs(inputs, frontendInputs, resolver);
        addNamedWidgetInputs(inputs, n.optJSONArray("widgets"));
        addWidgetValueInputs(inputs, cls, frontendInputs, n.optJSONArray("widgets_values"), objectInfo);
        forceLoadImageInput(inputs, cls, n.optJSONArray("widgets_values"));
        item.put("inputs", inputs);
        JSONObject meta = new JSONObject();
        meta.put("title", titleOf(n, cls));
        item.put("_meta", meta);
        return item;
    }

    private static void convertSubgraphNode(JSONObject out, JSONObject wrapper, JSONObject sg, Map<String, Link> topLinks, Map<String, Ref> subgraphOutputRefs, JSONObject objectInfo) throws JSONException {
        String wrapperId = wrapper.optString("id", "");
        String prefix = wrapperId + "_";
        String sgName = sg.optString("name", "Subgraph");
        JSONArray internalNodes = sg.optJSONArray("nodes");
        if (internalNodes == null) return;
        Map<String, Link> internalLinks = linksById(sg.optJSONArray("links"));
        Map<String, JSONObject> internalNodeMap = nodesById(internalNodes);
        Map<Integer, Ref> externalInputs = new HashMap<>();
        JSONArray wrapperInputs = wrapper.optJSONArray("inputs");
        if (wrapperInputs != null) {
            for (int slot = 0; slot < wrapperInputs.length(); slot++) {
                Link incoming = linkToTarget(topLinks, wrapperId, slot);
                if (incoming != null) {
                    Ref r = resolveTopOrigin(incoming, subgraphOutputRefs);
                    if (r != null) externalInputs.put(slot, r);
                }
            }
        }
        for (int i = 0; i < internalNodes.length(); i++) {
            JSONObject n = internalNodes.optJSONObject(i);
            if (n == null) continue;
            String id = n.optString("id", "");
            String type = n.optString("type", n.optString("class_type", ""));
            if (skipNodeType(type) || "-10".equals(id) || "-20".equals(id)) continue;
            JSONObject item = apiNodeFromFrontend(n, objectInfo, linkId -> resolveInternalLink(linkId, prefix, internalLinks, internalNodeMap, externalInputs));
            attachGroupMeta(item, sg, n, sgName);
            out.put(prefix + id, item);
        }
    }

    private static void fillSubgraphOutputs(String wrapperId, JSONObject sg, Map<String, Ref> out) throws JSONException {
        String prefix = wrapperId + "_";
        Map<String, Link> links = linksById(sg.optJSONArray("links"));
        for (Link l : links.values()) {
            if ("-20".equals(l.targetId)) {
                Ref r = "-10".equals(l.originId) ? null : new Ref(prefix + l.originId, l.originSlot);
                if (r != null) out.put(wrapperId + ":" + l.targetSlot, r);
            }
        }
    }

    private static Ref resolveTopLink(Object linkId, Map<String, Link> links, Map<String, Ref> subgraphOutputRefs) {
        Link l = links.get(String.valueOf(linkId));
        if (l == null) return null;
        return resolveTopOrigin(l, subgraphOutputRefs);
    }

    private static Ref resolveTopOrigin(Link l, Map<String, Ref> subgraphOutputRefs) {
        Ref sgOut = subgraphOutputRefs.get(l.originId + ":" + l.originSlot);
        if (sgOut != null) return sgOut;
        return new Ref(l.originId, l.originSlot);
    }

    private static Ref resolveInternalLink(Object linkId, String prefix, Map<String, Link> links, Map<String, JSONObject> nodeMap, Map<Integer, Ref> externalInputs) throws JSONException {
        Link l = links.get(String.valueOf(linkId));
        if (l == null) return null;
        if ("-10".equals(l.originId)) return externalInputs.get(l.originSlot);
        JSONObject origin = nodeMap.get(l.originId);
        if (origin != null && isReroute(origin)) {
            JSONArray ins = origin.optJSONArray("inputs");
            if (ins != null && ins.length() > 0) {
                JSONObject input = ins.optJSONObject(0);
                if (input != null && input.has("link") && !input.isNull("link")) return resolveInternalLink(input.opt("link"), prefix, links, nodeMap, externalInputs);
            }
        }
        return new Ref(prefix + l.originId, l.originSlot);
    }

    private static void addLinkedInputs(JSONObject inputs, JSONArray inArr, Resolver resolver) throws JSONException {
        if (inArr == null) return;
        for (int i = 0; i < inArr.length(); i++) {
            JSONObject inp = inArr.optJSONObject(i);
            if (inp == null) continue;
            String name = inp.optString("name", "");
            if (name.isEmpty() || !inp.has("link") || inp.isNull("link")) continue;
            Ref r = resolver.resolve(inp.opt("link"));
            if (r != null) inputs.put(name, r.json());
        }
    }

    private static void addNamedWidgetInputs(JSONObject inputs, JSONArray widgets) throws JSONException {
        if (widgets == null) return;
        for (int i = 0; i < widgets.length(); i++) {
            JSONObject w = widgets.optJSONObject(i);
            if (w == null) continue;
            String name = w.optString("name", "");
            if (name.isEmpty() || "upload".equals(name) || "button".equalsIgnoreCase(w.optString("type", ""))) continue;
            Object value = w.opt("value");
            if (primitive(value) && !inputs.has(name)) inputs.put(name, value);
        }
    }

    private static void addWidgetValueInputs(JSONObject inputs, String cls, JSONArray frontendInputs,
                                             JSONArray values, JSONObject objectInfo) throws JSONException {
        if (values == null || values.length() == 0) return;
        ArrayList<String> names = widgetInputNamesFromFrontend(frontendInputs);
        ArrayList<String> schemaNames = widgetInputNamesForClass(cls, objectInfo, values);
        if (names.isEmpty()) {
            names.addAll(schemaNames);
        } else if (names.size() < values.length()) {
            for (String name : schemaNames) if (!names.contains(name)) names.add(name);
        }
        int vi = 0;
        for (String name : names) {
            if (vi >= values.length()) break;
            Object value = values.opt(vi++);
            if (inputs.has(name)) continue;
            if (primitive(value)) inputs.put(name, value);
        }
    }

    private static ArrayList<String> widgetInputNamesFromFrontend(JSONArray frontendInputs) {
        ArrayList<String> names = new ArrayList<>();
        if (frontendInputs == null) return names;
        for (int i = 0; i < frontendInputs.length(); i++) {
            JSONObject input = frontendInputs.optJSONObject(i);
            if (input == null) continue;
            JSONObject widget = input.optJSONObject("widget");
            if (widget == null) continue;
            String name = widget.optString("name", input.optString("name", ""));
            String type = widget.optString("type", "");
            if (name.isEmpty() || "upload".equals(name) || "button".equalsIgnoreCase(type)) continue;
            names.add(name);
        }
        return names;
    }

    private static ArrayList<String> widgetInputNamesForClass(String cls, JSONObject objectInfo, JSONArray values) {
        ArrayList<String> names = new ArrayList<>();
        JSONObject def = objectInfo == null ? null : objectInfo.optJSONObject(cls);
        JSONObject input = def == null ? null : def.optJSONObject("input");
        Cursor cursor = new Cursor();
        collectWidgetInputNames(names, input == null ? null : input.optJSONObject("required"), "", values, cursor);
        collectWidgetInputNames(names, input == null ? null : input.optJSONObject("optional"), "", values, cursor);
        return names;
    }

    private static void collectWidgetInputNames(ArrayList<String> names, JSONObject section, String prefix,
                                                JSONArray values, Cursor cursor) {
        if (section == null) return;
        Iterator<String> it = section.keys();
        while (it.hasNext()) {
            String local = it.next();
            String key = prefix + local;
            Object raw = section.opt(local);
            if (!(raw instanceof JSONArray)) continue;
            JSONArray spec = (JSONArray) raw;
            Object first = spec.opt(0);
            JSONObject config = spec.optJSONObject(1);
            String type = String.valueOf(first == null ? "" : first).toUpperCase(Locale.US);
            if (first instanceof JSONArray || isPrimitiveInputType(type)) {
                names.add(key);
                cursor.index++;
                continue;
            }
            if ("COMFY_DYNAMICCOMBO_V3".equals(type)) {
                names.add(key);
                Object selected = values == null ? null : values.opt(cursor.index);
                cursor.index++;
                JSONObject option = dynamicOption(config, selected);
                JSONObject nested = option == null ? null : option.optJSONObject("inputs");
                collectWidgetInputNames(names, nested == null ? null : nested.optJSONObject("required"), key + ".", values, cursor);
                collectWidgetInputNames(names, nested == null ? null : nested.optJSONObject("optional"), key + ".", values, cursor);
                continue;
            }
            if ("COMFY_DYNAMICSLOT_V3".equals(type)) {
                JSONObject nested = config == null ? null : config.optJSONObject("inputs");
                collectWidgetInputNames(names, nested == null ? null : nested.optJSONObject("required"), key + ".", values, cursor);
                collectWidgetInputNames(names, nested == null ? null : nested.optJSONObject("optional"), key + ".", values, cursor);
            }
        }
    }

    private static boolean isPrimitiveInputType(String t) {
        String s = t == null ? "" : t.toUpperCase(Locale.US);
        return "INT".equals(s) || "FLOAT".equals(s) || "NUMBER".equals(s) || "STRING".equals(s) ||
                "BOOLEAN".equals(s) || "BOOL".equals(s) || "COMBO".equals(s);
    }

    private static void forceLoadImageInput(JSONObject inputs, String cls, JSONArray values) throws JSONException {
        String s = cls == null ? "" : cls.toLowerCase(Locale.US).replace("_", "");
        if (!s.contains("loadimage") || inputs.has("image") || values == null) return;
        for (int i = 0; i < values.length(); i++) {
            Object v = values.opt(i);
            if (primitive(v) && String.valueOf(v).trim().length() > 0) { inputs.put("image", v); return; }
        }
    }

    private static JSONObject normalizeDynamicInputShapes(JSONObject prompt, JSONObject objectInfo) throws JSONException {
        if (prompt == null || objectInfo == null) return prompt == null ? new JSONObject() : prompt;
        Iterator<String> ids = prompt.keys();
        while (ids.hasNext()) {
            JSONObject node = prompt.optJSONObject(ids.next());
            if (node == null) continue;
            JSONObject inputs = node.optJSONObject("inputs");
            JSONObject def = objectInfo.optJSONObject(node.optString("class_type", ""));
            JSONObject inputDef = def == null ? null : def.optJSONObject("input");
            if (inputs == null || inputDef == null) continue;
            normalizeDynamicSection(inputs, inputDef.optJSONObject("required"), "");
            normalizeDynamicSection(inputs, inputDef.optJSONObject("optional"), "");
        }
        return prompt;
    }

    private static void normalizeDynamicSection(JSONObject inputs, JSONObject section, String prefix) throws JSONException {
        if (section == null) return;
        Iterator<String> keys = section.keys();
        while (keys.hasNext()) {
            String local = keys.next();
            String flatKey = prefix + local;
            JSONArray spec = section.optJSONArray(local);
            if (spec == null) continue;
            String type = String.valueOf(spec.opt(0)).toUpperCase(Locale.US);
            JSONObject config = spec.optJSONObject(1);
            if ("COMFY_DYNAMICCOMBO_V3".equals(type)) {
                Object raw = inputs.opt(flatKey);
                if (raw instanceof JSONObject) {
                    JSONObject nestedValue = (JSONObject) raw;
                    Object selected = nestedValue.has(local) ? nestedValue.opt(local) :
                            nestedValue.has("value") ? nestedValue.opt("value") : firstDynamicOptionKey(config);
                    inputs.put(flatKey, selected == null ? "" : selected);
                    Iterator<String> nestedKeys = nestedValue.keys();
                    while (nestedKeys.hasNext()) {
                        String nestedKey = nestedKeys.next();
                        if (local.equals(nestedKey) || "value".equals(nestedKey)) continue;
                        inputs.put(flatKey + "." + nestedKey, cloneValue(nestedValue.opt(nestedKey)));
                    }
                }
                Object selected = inputs.opt(flatKey);
                JSONObject option = dynamicOption(config, selected);
                JSONObject nested = option == null ? null : option.optJSONObject("inputs");
                normalizeDynamicSection(inputs, nested == null ? null : nested.optJSONObject("required"), flatKey + ".");
                normalizeDynamicSection(inputs, nested == null ? null : nested.optJSONObject("optional"), flatKey + ".");
            } else if ("COMFY_DYNAMICSLOT_V3".equals(type)) {
                JSONObject nested = config == null ? null : config.optJSONObject("inputs");
                normalizeDynamicSection(inputs, nested == null ? null : nested.optJSONObject("required"), flatKey + ".");
                normalizeDynamicSection(inputs, nested == null ? null : nested.optJSONObject("optional"), flatKey + ".");
            }
        }
    }

    private static boolean validInputValue(JSONObject definition, String flatKey, Object value,
                                           JSONObject currentInputs, JSONObject freshInputs) {
        if (isConnection(value)) return true;
        JSONArray spec = findInputSpec(definition, flatKey, currentInputs, freshInputs);
        if (spec == null) return true;
        Object first = spec.opt(0);
        JSONObject config = spec.optJSONObject(1);
        if (first instanceof JSONArray) return arrayContains((JSONArray) first, value);
        String type = String.valueOf(first == null ? "" : first).toUpperCase(Locale.US);
        if ("COMFY_DYNAMICCOMBO_V3".equals(type)) return dynamicOption(config, value) != null;
        if ("COMBO".equals(type)) {
            JSONArray options = config == null ? null : config.optJSONArray("options");
            return options == null || options.length() == 0 || arrayContains(options, value);
        }
        if ("INT".equals(type)) return value instanceof Integer || value instanceof Long;
        if ("FLOAT".equals(type) || "NUMBER".equals(type)) return value instanceof Number;
        if ("BOOLEAN".equals(type) || "BOOL".equals(type)) return value instanceof Boolean;
        if ("STRING".equals(type)) return value instanceof String;
        return true;
    }

    private static JSONArray findInputSpec(JSONObject definition, String flatKey,
                                           JSONObject currentInputs, JSONObject freshInputs) {
        JSONObject input = definition == null ? null : definition.optJSONObject("input");
        if (input == null) return null;
        JSONArray direct = specFromSections(input, flatKey);
        if (direct != null) return direct;
        String[] parts = flatKey.split("\\.");
        if (parts.length < 2) return null;
        JSONObject currentSection = input;
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            JSONArray spec = specFromSections(currentSection, part);
            if (spec == null) return null;
            if (i == parts.length - 1) return spec;
            String type = String.valueOf(spec.opt(0)).toUpperCase(Locale.US);
            if (!"COMFY_DYNAMICCOMBO_V3".equals(type) && !"COMFY_DYNAMICSLOT_V3".equals(type)) return null;
            if (prefix.length() > 0) prefix.append('.');
            prefix.append(part);
            JSONObject config = spec.optJSONObject(1);
            JSONObject nested;
            if ("COMFY_DYNAMICCOMBO_V3".equals(type)) {
                Object selected = currentInputs.has(prefix.toString()) ? currentInputs.opt(prefix.toString()) : freshInputs.opt(prefix.toString());
                JSONObject option = dynamicOption(config, selected);
                nested = option == null ? null : option.optJSONObject("inputs");
            } else {
                nested = config == null ? null : config.optJSONObject("inputs");
            }
            if (nested == null) return null;
            currentSection = nested;
        }
        return null;
    }

    private static JSONArray specFromSections(JSONObject input, String key) {
        JSONObject required = input == null ? null : input.optJSONObject("required");
        JSONArray spec = required == null ? null : required.optJSONArray(key);
        if (spec != null) return spec;
        JSONObject optional = input == null ? null : input.optJSONObject("optional");
        return optional == null ? null : optional.optJSONArray(key);
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

    private static boolean arrayContains(JSONArray array, Object value) {
        if (array == null) return false;
        String target = String.valueOf(value);
        for (int i = 0; i < array.length(); i++) {
            Object option = array.opt(i);
            if (target.equals(String.valueOf(option))) return true;
        }
        return false;
    }

    private static boolean isConnection(Object value) {
        if (!(value instanceof JSONArray)) return false;
        JSONArray link = (JSONArray) value;
        return link.length() >= 2 && (link.opt(0) instanceof String || link.opt(0) instanceof Number) && link.opt(1) instanceof Number;
    }

    private static JSONObject buildOptions(JSONObject prompt, JSONObject objectInfo) throws JSONException {
        JSONObject options = new JSONObject();
        if (prompt == null || objectInfo == null) return options;
        Iterator<String> ids = prompt.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            JSONObject node = prompt.optJSONObject(id);
            if (node == null) continue;
            JSONObject def = objectInfo.optJSONObject(node.optString("class_type", ""));
            JSONObject input = def == null ? null : def.optJSONObject("input");
            addOptions(options, id, input == null ? null : input.optJSONObject("required"), "");
            addOptions(options, id, input == null ? null : input.optJSONObject("optional"), "");
        }
        return options;
    }

    private static void addOptions(JSONObject out, String id, JSONObject section, String prefix) throws JSONException {
        if (section == null) return;
        Iterator<String> it = section.keys();
        while (it.hasNext()) {
            String local = it.next();
            String key = prefix + local;
            Object raw = section.opt(local);
            if (!(raw instanceof JSONArray)) continue;
            JSONArray spec = (JSONArray) raw;
            Object first = spec.opt(0);
            JSONObject config = spec.optJSONObject(1);
            if (first instanceof JSONArray) {
                out.put(id + ":" + key, first);
            } else if ("COMFY_DYNAMICCOMBO_V3".equalsIgnoreCase(String.valueOf(first))) {
                JSONArray optionKeys = new JSONArray();
                JSONArray dynamicOptions = config == null ? null : config.optJSONArray("options");
                if (dynamicOptions != null) {
                    for (int i = 0; i < dynamicOptions.length(); i++) {
                        JSONObject option = dynamicOptions.optJSONObject(i);
                        if (option != null) optionKeys.put(option.opt("key"));
                    }
                }
                out.put(id + ":" + key, optionKeys);
            }
        }
    }

    private static void attachGroupMeta(JSONObject item, JSONObject wf, JSONObject node, String prefix) throws JSONException {
        JSONObject meta = item.optJSONObject("_meta");
        if (meta == null) meta = new JSONObject();
        String cls = item.optString("class_type", "Node");
        if (!meta.has("title")) meta.put("title", titleOf(node, cls));
        String group = groupForNode(wf, node);
        if (group != null && !group.trim().isEmpty()) meta.put("group", prefix == null || prefix.trim().isEmpty() ? group : prefix + " / " + group);
        else if (prefix != null && !prefix.trim().isEmpty()) meta.put("group", prefix);
        item.put("_meta", meta);
    }

    private static String groupForNode(JSONObject wf, JSONObject node) {
        String direct = node.optString("group", "");
        if (!direct.trim().isEmpty()) return direct.trim();
        JSONArray pos = node.optJSONArray("pos");
        JSONArray groups = wf == null ? null : wf.optJSONArray("groups");
        if (pos == null || pos.length() < 2 || groups == null) return "";
        double x = pos.optDouble(0, 0), y = pos.optDouble(1, 0);
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g == null) continue;
            JSONArray b = g.optJSONArray("bounding");
            if (b == null) b = g.optJSONArray("_bounding");
            if (b == null || b.length() < 4) continue;
            double gx = b.optDouble(0), gy = b.optDouble(1), gw = b.optDouble(2), gh = b.optDouble(3);
            if (x >= gx && y >= gy && x <= gx + gw && y <= gy + gh) return g.optString("title", g.optString("name", "Group"));
        }
        return "";
    }

    private static Map<String, JSONObject> subgraphMap(JSONObject root) {
        Map<String, JSONObject> map = new HashMap<>();
        JSONObject defs = root == null ? null : root.optJSONObject("definitions");
        JSONArray arr = defs == null ? null : defs.optJSONArray("subgraphs");
        if (arr == null) return map;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject sg = arr.optJSONObject(i);
            if (sg != null) map.put(sg.optString("id", ""), sg);
        }
        return map;
    }

    private static Map<String, JSONObject> nodesById(JSONArray nodes) {
        Map<String, JSONObject> map = new HashMap<>();
        if (nodes == null) return map;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.optJSONObject(i);
            if (n != null) map.put(n.optString("id", ""), n);
        }
        return map;
    }

    private static Map<String, Link> linksById(JSONArray links) {
        Map<String, Link> map = new HashMap<>();
        if (links == null) return map;
        for (int i = 0; i < links.length(); i++) {
            Object raw = links.opt(i);
            Link l = null;
            if (raw instanceof JSONArray) {
                JSONArray a = (JSONArray) raw;
                l = new Link(String.valueOf(a.opt(0)), String.valueOf(a.opt(1)), a.optInt(2, 0), String.valueOf(a.opt(3)), a.optInt(4, 0));
            } else if (raw instanceof JSONObject) {
                JSONObject o = (JSONObject) raw;
                l = new Link(String.valueOf(o.opt("id")), String.valueOf(o.opt("origin_id")), o.optInt("origin_slot", 0), String.valueOf(o.opt("target_id")), o.optInt("target_slot", 0));
            }
            if (l != null) map.put(l.id, l);
        }
        return map;
    }

    private static Link linkToTarget(Map<String, Link> links, String targetId, int targetSlot) {
        for (Link l : links.values()) if (targetId.equals(l.targetId) && targetSlot == l.targetSlot) return l;
        return null;
    }

    private static JSONObject cloneObject(JSONObject object) {
        if (object == null) return new JSONObject();
        try { return new JSONObject(object.toString()); }
        catch (Exception e) { return new JSONObject(); }
    }

    private static Object cloneValue(Object value) {
        if (value instanceof JSONObject) return cloneObject((JSONObject) value);
        if (value instanceof JSONArray) {
            try { return new JSONArray(value.toString()); }
            catch (Exception ignored) { return new JSONArray(); }
        }
        return value;
    }

    private static boolean looksApiPrompt(JSONObject o) {
        if (o == null) return false;
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            JSONObject n = o.optJSONObject(it.next());
            if (n != null && n.has("class_type")) return true;
        }
        return false;
    }

    private static boolean primitive(Object v) { return v == JSONObject.NULL || v instanceof String || v instanceof Number || v instanceof Boolean; }
    private static boolean isReroute(JSONObject n) { return "reroute".equalsIgnoreCase(n.optString("type", n.optString("class_type", ""))); }
    private static boolean skipNodeType(String type) {
        String s = type == null ? "" : type.toLowerCase(Locale.US);
        return s.contains("markdown") || s.contains("note") || "reroute".equals(s);
    }
    private static String titleOf(JSONObject node, String fallback) {
        String t = node.optString("title", "");
        return t.trim().isEmpty() ? fallback : t;
    }
}
