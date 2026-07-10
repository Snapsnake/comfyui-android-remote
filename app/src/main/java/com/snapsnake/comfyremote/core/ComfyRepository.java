package com.snapsnake.comfyremote.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public final class ComfyRepository {
    private final ComfyStore store;
    private final ComfyApiClient client;
    private volatile JSONObject objectInfo;
    private volatile WorkflowDocument currentWorkflow;

    public ComfyRepository(ComfyStore store, ServerProfile profile) {
        this.store = store;
        this.client = new ComfyApiClient(profile);
        this.objectInfo = store.objectInfo();
        this.currentWorkflow = store.currentWorkflow();
        normalizeCurrentWorkflow();
    }

    public ComfyApiClient client() { return client; }
    public ComfyStore store() { return store; }
    public ServerProfile profile() { return client.profile(); }
    public JSONObject objectInfo() { return objectInfo; }
    public WorkflowDocument currentWorkflow() { return currentWorkflow; }
    public NodeSchemaRegistry schemaRegistry() { return new NodeSchemaRegistry(objectInfo); }

    public synchronized void setProfile(ServerProfile profile) {
        store.saveActiveProfile(profile);
        client.setProfile(profile);
    }

    public JSONObject connectAndRefreshSchema() throws Exception {
        JSONObject stats = client.systemStats();
        JSONObject latestObjectInfo = client.objectInfo();
        objectInfo = latestObjectInfo;
        store.saveObjectInfo(latestObjectInfo);
        normalizeCurrentWorkflow();
        ServerProfile resolved = profile().withResolvedBaseUrl(client.resolvedBaseUrl());
        store.saveActiveProfile(resolved);
        client.setProfile(resolved);
        return stats;
    }

    public List<TemplateDescriptor> cachedTemplates() {
        ArrayList<TemplateDescriptor> out = new ArrayList<>();
        JSONArray arr = store.templates();
        for (int i = 0; i < arr.length(); i++) {
            TemplateDescriptor item = TemplateDescriptor.fromJson(arr.optJSONObject(i));
            if (!item.name.isEmpty()) out.add(item);
        }
        return out;
    }

    public List<TemplateDescriptor> refreshTemplates() throws Exception {
        LinkedHashMap<String, TemplateDescriptor> unique = new LinkedHashMap<>();
        Exception indexFailure = null;
        try {
            JSONArray index = client.templateIndex();
            for (int i = 0; i < index.length(); i++) {
                JSONObject category = index.optJSONObject(i);
                if (category == null) continue;
                String source = first(category.optString("moduleName", "default"), "default");
                String categoryTitle = first(category.optString("localizedTitle", category.optString("title", source)), "Templates");
                JSONArray templates = category.optJSONArray("templates");
                if (templates == null) continue;
                for (int j = 0; j < templates.length(); j++) {
                    JSONObject raw = templates.optJSONObject(j);
                    if (raw == null) continue;
                    String name = raw.optString("name", "").trim();
                    if (name.isEmpty()) continue;
                    TemplateDescriptor item = new TemplateDescriptor(
                            source,
                            name,
                            raw.optString("localizedTitle", raw.optString("title", name)),
                            raw.optString("localizedDescription", raw.optString("description", "")),
                            categoryTitle,
                            raw.optString("mediaSubtype", "webp")
                    );
                    unique.put(item.id(), item);
                }
            }
        } catch (Exception e) {
            indexFailure = e;
        }

        try {
            JSONObject custom = client.workflowTemplates();
            Iterator<String> modules = custom.keys();
            while (modules.hasNext()) {
                String source = modules.next();
                JSONArray names = custom.optJSONArray(source);
                if (names == null) continue;
                for (int i = 0; i < names.length(); i++) {
                    String name = names.optString(i, "").trim();
                    if (name.isEmpty()) continue;
                    TemplateDescriptor item = new TemplateDescriptor(source, name, name, source, "Custom templates", "webp");
                    unique.put(item.id(), item);
                }
            }
        } catch (Exception e) {
            if (unique.isEmpty() && indexFailure == null) indexFailure = e;
        }

        if (unique.isEmpty() && indexFailure != null) throw indexFailure;
        ArrayList<TemplateDescriptor> out = new ArrayList<>(unique.values());
        JSONArray saved = new JSONArray();
        for (TemplateDescriptor item : out) saved.put(item.toJson());
        store.saveTemplates(saved, System.currentTimeMillis());
        return out;
    }

    public WorkflowDocument openTemplate(TemplateDescriptor template) throws Exception {
        if (template == null || template.name.isEmpty()) throw new IllegalArgumentException("Template is empty");
        String server = client.resolvedBaseUrl();
        File cached = store.templateJsonFile(server, template.id());
        String raw;
        if (cached.exists() && cached.length() > 0) {
            raw = new String(store.readBytes(cached), StandardCharsets.UTF_8);
        } else {
            raw = client.getText(client.templateJsonPath(template.source, template.name));
            store.writeBytes(cached, raw.getBytes(StandardCharsets.UTF_8));
        }
        WorkflowDocument document = WorkflowDocument.importRaw(new JSONObject(raw), objectInfo, template.title);
        materialize(document);
        setCurrentWorkflow(document);
        return document;
    }

    public WorkflowDocument importWorkflow(String raw, String sourceName) throws Exception {
        WorkflowDocument document = WorkflowDocument.importRaw(new JSONObject(raw), objectInfo, sourceName);
        materialize(document);
        setCurrentWorkflow(document);
        return document;
    }

    public synchronized void setCurrentWorkflow(WorkflowDocument document) {
        currentWorkflow = document == null ? WorkflowDocument.empty() : document;
        materialize(currentWorkflow);
        store.saveCurrentWorkflow(currentWorkflow);
    }

    public String queueCurrentWorkflow(String clientId) throws Exception {
        WorkflowDocument document = currentWorkflow;
        if (document == null || document.isEmpty()) throw new IllegalStateException("No workflow loaded");
        materialize(document);
        store.saveCurrentWorkflow(document);
        JSONObject result = client.prompt(document.apiPrompt(), clientId);
        String promptId = result.optString("prompt_id", "");
        if (promptId.isEmpty()) throw new IllegalStateException("ComfyUI did not return prompt_id");
        return promptId;
    }

    public JSONObject queue() throws Exception { return client.queue(); }
    public JSONObject history() throws Exception { return client.history(); }
    public JSONObject history(String promptId) throws Exception { return client.history(promptId); }
    public void interrupt() throws Exception { client.interrupt(); }
    public void clearQueue() throws Exception { client.queueClear(); }
    public void deleteQueued(List<String> promptIds) throws Exception { client.queueDelete(promptIds == null ? Collections.emptyList() : promptIds); }

    public List<OutputAsset> parseOutputs(JSONObject history) {
        ArrayList<OutputAsset> out = new ArrayList<>();
        if (history == null) return out;
        Iterator<String> promptIds = history.keys();
        while (promptIds.hasNext()) {
            String promptId = promptIds.next();
            JSONObject prompt = history.optJSONObject(promptId);
            JSONObject outputs = prompt == null ? null : prompt.optJSONObject("outputs");
            if (outputs == null) continue;
            Iterator<String> nodeIds = outputs.keys();
            while (nodeIds.hasNext()) {
                String nodeId = nodeIds.next();
                JSONObject nodeOutput = outputs.optJSONObject(nodeId);
                if (nodeOutput == null) continue;
                addAssets(out, promptId, nodeId, nodeOutput.optJSONArray("images"), OutputAsset.Kind.IMAGE);
                addAssets(out, promptId, nodeId, nodeOutput.optJSONArray("videos"), OutputAsset.Kind.VIDEO);
                addAssets(out, promptId, nodeId, nodeOutput.optJSONArray("audio"), OutputAsset.Kind.AUDIO);
                addAssets(out, promptId, nodeId, nodeOutput.optJSONArray("audios"), OutputAsset.Kind.AUDIO);
                addAssets(out, promptId, nodeId, nodeOutput.optJSONArray("gifs"), OutputAsset.Kind.GIF);
                addAssets(out, promptId, nodeId, nodeOutput.optJSONArray("files"), OutputAsset.Kind.FILE);
                discoverUnknownAssets(out, promptId, nodeId, nodeOutput);
            }
        }
        return out;
    }

    public JSONArray loadSavedOutputs() { return store.outputs(); }

    public void saveOutputs(List<OutputAsset> assets) {
        JSONArray arr = new JSONArray();
        if (assets != null) for (OutputAsset asset : assets) arr.put(asset.toJson());
        store.saveOutputs(arr);
    }

    public byte[] loadTemplatePreview(TemplateDescriptor item) throws Exception {
        String server = client.resolvedBaseUrl();
        for (String path : client.templatePreviewPaths(item.source, item.name, item.mediaSubtype)) {
            String ext = extension(path);
            File cached = store.previewFile(server, item.id() + "|" + path, ext);
            if (cached.exists() && cached.length() > 0) return store.readBytes(cached);
            try {
                byte[] data = client.getBytes(path);
                if (data.length > 0) {
                    store.writeBytes(cached, data);
                    return data;
                }
            } catch (Exception ignored) {}
        }
        throw new IllegalStateException("Template preview was not found");
    }

    private void normalizeCurrentWorkflow() {
        WorkflowDocument document = currentWorkflow;
        if (document == null || document.isEmpty()) return;
        if (materialize(document) > 0) store.saveCurrentWorkflow(document);
    }

    private int materialize(WorkflowDocument document) {
        if (document == null || document.isEmpty() || objectInfo == null || objectInfo.length() == 0) return 0;
        return new NodeSchemaRegistry(objectInfo).materializeMissingDefaults(document.apiPrompt());
    }

    private void addAssets(ArrayList<OutputAsset> out, String promptId, String nodeId, JSONArray files, OutputAsset.Kind kind) {
        if (files == null) return;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            String filename = file.optString("filename", file.optString("name", ""));
            if (filename.isEmpty()) continue;
            OutputAsset.Kind resolvedKind = kind == OutputAsset.Kind.IMAGE && filename.toLowerCase(Locale.US).endsWith(".gif") ? OutputAsset.Kind.GIF : kind;
            addUnique(out, new OutputAsset(promptId, nodeId, filename,
                    file.optString("subfolder", ""), file.optString("type", "output"), resolvedKind));
        }
    }

    private void discoverUnknownAssets(ArrayList<OutputAsset> out, String promptId, String nodeId, JSONObject nodeOutput) {
        Iterator<String> keys = nodeOutput.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.equals("images") || key.equals("videos") || key.equals("audio") || key.equals("audios") || key.equals("gifs") || key.equals("files")) continue;
            JSONArray arr = nodeOutput.optJSONArray(key);
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject file = arr.optJSONObject(i);
                if (file == null || !file.has("filename")) continue;
                String filename = file.optString("filename", "");
                if (filename.isEmpty()) continue;
                addUnique(out, new OutputAsset(promptId, nodeId, filename,
                        file.optString("subfolder", ""), file.optString("type", "output"), kindFromFilename(filename)));
            }
        }
    }

    private static void addUnique(ArrayList<OutputAsset> list, OutputAsset candidate) {
        for (OutputAsset item : list) {
            if (item.filename.equals(candidate.filename) && item.subfolder.equals(candidate.subfolder) && item.type.equals(candidate.type)) return;
        }
        list.add(candidate);
    }

    private static OutputAsset.Kind kindFromFilename(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.US);
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".bmp")) return OutputAsset.Kind.IMAGE;
        if (lower.endsWith(".gif")) return OutputAsset.Kind.GIF;
        if (lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov") || lower.endsWith(".mkv")) return OutputAsset.Kind.VIDEO;
        if (lower.endsWith(".wav") || lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".ogg") || lower.endsWith(".m4a")) return OutputAsset.Kind.AUDIO;
        return OutputAsset.Kind.FILE;
    }

    private static String extension(String path) {
        int dot = path == null ? -1 : path.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= path.length()) return "bin";
        return path.substring(dot + 1).replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.US);
    }

    private static String first(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
