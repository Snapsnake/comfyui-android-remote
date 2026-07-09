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
            if (converted.length() > 0) return converted;
        }
        JSONObject extra = raw.optJSONObject("extra");
        JSONObject p = extra == null ? null : extra.optJSONObject("prompt");
        if (looksApiPrompt(p)) return p;
        p = raw.optJSONObject("prompt");
        if (looksApiPrompt(p)) return p;
        JSONObject w = raw.optJSONObject("workflow");
        if (looksApiPrompt(w)) return w;
        if (looksApiPrompt(raw)) return raw;
        throw new JSONException("No ComfyUI API prompt or convertible frontend workflow found");
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
        Map<String, JSONObject> topNodes = nodesById(nodes);
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
        addLinkedInputs(inputs, n.optJSONArray("inputs"), resolver);
        addNamedWidgetInputs(inputs, n.optJSONArray("widgets"));
        addWidgetValueInputs(inputs, cls, n.optJSONArray("widgets_values"), objectInfo);
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

    private static void addWidgetValueInputs(JSONObject inputs, String cls, JSONArray values, JSONObject objectInfo) throws JSONException {
        if (values == null || values.length() == 0) return;
        ArrayList<String> names = widgetInputNamesForClass(cls, objectInfo);
        int vi = 0;
        for (String name : names) {
            if (vi >= values.length()) break;
            Object value = values.opt(vi++);
            if (inputs.has(name)) continue;
            if (primitive(value)) inputs.put(name, value);
        }
    }

    private static ArrayList<String> widgetInputNamesForClass(String cls, JSONObject objectInfo) {
        ArrayList<String> names = new ArrayList<>();
        JSONObject def = objectInfo == null ? null : objectInfo.optJSONObject(cls);
        JSONObject input = def == null ? null : def.optJSONObject("input");
        addWidgetInputNames(names, input == null ? null : input.optJSONObject("required"));
        addWidgetInputNames(names, input == null ? null : input.optJSONObject("optional"));
        return names;
    }

    private static void addWidgetInputNames(ArrayList<String> names, JSONObject section) {
        if (section == null) return;
        Iterator<String> it = section.keys();
        while (it.hasNext()) {
            String key = it.next();
            Object raw = section.opt(key);
            if (!(raw instanceof JSONArray)) continue;
            JSONArray spec = (JSONArray) raw;
            Object first = spec.opt(0);
            if (first instanceof JSONArray || isPrimitiveInputType(String.valueOf(first))) names.add(key);
        }
    }

    private static boolean isPrimitiveInputType(String t) {
        String s = t == null ? "" : t.toUpperCase(Locale.US);
        return "INT".equals(s) || "FLOAT".equals(s) || "STRING".equals(s) || "BOOLEAN".equals(s) || "COMBO".equals(s);
    }

    private static void forceLoadImageInput(JSONObject inputs, String cls, JSONArray values) throws JSONException {
        String s = cls == null ? "" : cls.toLowerCase(Locale.US).replace("_", "");
        if (!s.contains("loadimage") || inputs.has("image") || values == null) return;
        for (int i = 0; i < values.length(); i++) {
            Object v = values.opt(i);
            if (primitive(v) && String.valueOf(v).trim().length() > 0) { inputs.put("image", v); return; }
        }
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
            addOptions(options, id, input == null ? null : input.optJSONObject("required"));
            addOptions(options, id, input == null ? null : input.optJSONObject("optional"));
        }
        return options;
    }

    private static void addOptions(JSONObject out, String id, JSONObject section) throws JSONException {
        if (section == null) return;
        Iterator<String> it = section.keys();
        while (it.hasNext()) {
            String key = it.next();
            Object raw = section.opt(key);
            if (raw instanceof JSONArray) {
                Object first = ((JSONArray) raw).opt(0);
                if (first instanceof JSONArray) out.put(id + ":" + key, first);
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
