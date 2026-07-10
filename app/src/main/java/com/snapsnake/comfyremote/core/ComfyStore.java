package com.snapsnake.comfyremote.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ComfyStore {
    private static final String PREFS = "comfy_native_store_v1";
    private static final String KEY_PROFILE = "active_profile";
    private static final String KEY_PROFILES = "profiles";
    private static final String KEY_OBJECT_INFO = "object_info";
    private static final String KEY_TEMPLATES = "templates";
    private static final String KEY_TEMPLATES_AT = "templates_at";
    private static final String KEY_CURRENT_WORKFLOW = "current_workflow";
    private static final String KEY_OUTPUTS = "outputs";
    private static final String KEY_RECENTS = "recent_workflows";

    private final Context context;
    private final SharedPreferences prefs;
    private final File cacheDir;
    private final File workflowDir;

    public ComfyStore(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.cacheDir = new File(this.context.getFilesDir(), "native-cache");
        this.workflowDir = new File(this.context.getFilesDir(), "workflow-library");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        if (!workflowDir.exists()) workflowDir.mkdirs();
    }

    public ServerProfile activeProfile() {
        try {
            JSONObject raw = new JSONObject(prefs.getString(KEY_PROFILE, "{}"));
            ServerProfile p = ServerProfile.fromJson(raw);
            if (!p.baseUrl.isEmpty()) return p;
        } catch (Exception ignored) {}
        return migrateLegacyProfile();
    }

    public void saveActiveProfile(ServerProfile profile) {
        if (profile == null) return;
        prefs.edit().putString(KEY_PROFILE, profile.toJson().toString()).apply();
        List<ServerProfile> profiles = profiles();
        ArrayList<ServerProfile> next = new ArrayList<>();
        next.add(profile);
        for (ServerProfile p : profiles) if (!p.id.equals(profile.id) && !p.baseUrl.equals(profile.baseUrl)) next.add(p);
        saveProfiles(next);
    }

    public List<ServerProfile> profiles() {
        ArrayList<ServerProfile> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_PROFILES, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                ServerProfile p = ServerProfile.fromJson(arr.optJSONObject(i));
                if (!p.baseUrl.isEmpty()) out.add(p);
            }
        } catch (Exception ignored) {}
        if (out.isEmpty()) {
            ServerProfile active = activeProfileWithoutRecursion();
            if (!active.baseUrl.isEmpty()) out.add(active);
        }
        return out;
    }

    public void saveProfiles(List<ServerProfile> profiles) {
        JSONArray arr = new JSONArray();
        if (profiles != null) for (ServerProfile p : profiles) if (p != null) arr.put(p.toJson());
        prefs.edit().putString(KEY_PROFILES, arr.toString()).apply();
    }

    public JSONObject objectInfo() {
        try { return new JSONObject(prefs.getString(KEY_OBJECT_INFO, "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    public void saveObjectInfo(JSONObject objectInfo) {
        prefs.edit().putString(KEY_OBJECT_INFO, objectInfo == null ? "{}" : objectInfo.toString()).apply();
    }

    public JSONArray templates() {
        try { return new JSONArray(prefs.getString(KEY_TEMPLATES, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    public void saveTemplates(JSONArray templates, long updatedAt) {
        prefs.edit()
                .putString(KEY_TEMPLATES, templates == null ? "[]" : templates.toString())
                .putLong(KEY_TEMPLATES_AT, updatedAt)
                .apply();
    }

    public long templatesUpdatedAt() { return prefs.getLong(KEY_TEMPLATES_AT, 0L); }

    public WorkflowDocument currentWorkflow() {
        try {
            JSONObject saved = new JSONObject(prefs.getString(KEY_CURRENT_WORKFLOW, "{}"));
            return WorkflowDocument.fromJson(saved);
        } catch (Exception e) {
            return WorkflowDocument.empty();
        }
    }

    public void saveCurrentWorkflow(WorkflowDocument workflow) {
        prefs.edit().putString(KEY_CURRENT_WORKFLOW, workflow == null ? "{}" : workflow.toJson().toString()).apply();
    }

    public String saveWorkflowToLibrary(String displayName, WorkflowDocument document) throws Exception {
        if (document == null || document.isEmpty()) throw new IllegalArgumentException("Workflow is empty");
        String id = sha1((displayName == null ? "workflow" : displayName) + "|" + System.nanoTime());
        JSONObject entry = document.toJson();
        entry.put("libraryId", id);
        entry.put("displayName", displayName == null || displayName.trim().isEmpty() ? "Workflow" : displayName.trim());
        entry.put("savedAt", System.currentTimeMillis());
        writeText(new File(workflowDir, id + ".json"), entry.toString());
        JSONArray recents = recentWorkflowIds();
        JSONArray next = new JSONArray();
        next.put(id);
        for (int i = 0; i < recents.length() && next.length() < 30; i++) {
            String old = recents.optString(i, "");
            if (!old.isEmpty() && !old.equals(id)) next.put(old);
        }
        prefs.edit().putString(KEY_RECENTS, next.toString()).apply();
        return id;
    }

    public List<JSONObject> recentWorkflows() {
        ArrayList<JSONObject> out = new ArrayList<>();
        JSONArray ids = recentWorkflowIds();
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.optString(i, "");
            if (id.isEmpty()) continue;
            try {
                JSONObject entry = new JSONObject(readText(new File(workflowDir, id + ".json")));
                out.add(entry);
            } catch (Exception ignored) {}
        }
        return out;
    }

    public WorkflowDocument loadWorkflowFromLibrary(String id) {
        try { return WorkflowDocument.fromJson(new JSONObject(readText(new File(workflowDir, id + ".json")))); }
        catch (Exception e) { return WorkflowDocument.empty(); }
    }

    public JSONArray outputs() {
        try { return new JSONArray(prefs.getString(KEY_OUTPUTS, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    public void saveOutputs(JSONArray outputs) {
        prefs.edit().putString(KEY_OUTPUTS, outputs == null ? "[]" : outputs.toString()).apply();
    }

    public File previewFile(String serverBaseUrl, String templateId, String extension) {
        File dir = new File(cacheDir, "previews/" + sha1(serverBaseUrl));
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, sha1(templateId) + "." + safeExt(extension));
    }

    public File templateJsonFile(String serverBaseUrl, String templateId) {
        File dir = new File(cacheDir, "templates/" + sha1(serverBaseUrl));
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, sha1(templateId) + ".json");
    }

    public void writeBytes(File file, byte[] bytes) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File temp = new File(file.getAbsolutePath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { out.write(bytes); }
        if (file.exists() && !file.delete()) throw new IllegalStateException("Could not replace cache file");
        if (!temp.renameTo(file)) throw new IllegalStateException("Could not finalize cache file");
    }

    public byte[] readBytes(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int offset = 0;
            while (offset < buffer.length) {
                int read = in.read(buffer, offset, buffer.length - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset == buffer.length) return buffer;
            byte[] trimmed = new byte[offset];
            System.arraycopy(buffer, 0, trimmed, 0, offset);
            return trimmed;
        }
    }

    private ServerProfile migrateLegacyProfile() {
        SharedPreferences workstation = context.getSharedPreferences("comfy_workstation_v1", Context.MODE_PRIVATE);
        SharedPreferences cache = context.getSharedPreferences("comfy_real_cache_v1", Context.MODE_PRIVATE);
        SharedPreferences remote = context.getSharedPreferences("comfyui_remote_prefs", Context.MODE_PRIVATE);
        String url = first(workstation.getString("url", ""), first(cache.getString("url", ""), remote.getString("comfyui_url", "")));
        String id = first(workstation.getString("cf_id", ""), first(cache.getString("cf_id", ""), remote.getString("cf_access_client_id", "")));
        String secret = first(workstation.getString("cf_secret", ""), first(cache.getString("cf_secret", ""), remote.getString("cf_access_client_secret", "")));
        ServerProfile migrated = new ServerProfile("", "", url, id, secret);
        if (!migrated.baseUrl.isEmpty()) saveActiveProfile(migrated);
        return migrated;
    }

    private ServerProfile activeProfileWithoutRecursion() {
        try { return ServerProfile.fromJson(new JSONObject(prefs.getString(KEY_PROFILE, "{}"))); }
        catch (Exception e) { return new ServerProfile("", "ComfyUI", "", "", ""); }
    }

    private JSONArray recentWorkflowIds() {
        try { return new JSONArray(prefs.getString(KEY_RECENTS, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    private static void writeText(File file, String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
    }

    private static String readText(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStreamEx out = new ByteArrayOutputStreamEx();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String sha1(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(value == null ? 0 : value.hashCode());
        }
    }

    private static String safeExt(String ext) {
        String s = ext == null ? "bin" : ext.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
        return s.isEmpty() ? "bin" : s;
    }

    private static String first(String a, String b) { return a != null && !a.trim().isEmpty() ? a : (b == null ? "" : b); }

    private static final class ByteArrayOutputStreamEx extends java.io.ByteArrayOutputStream {
        byte[] bytes() { return toByteArray(); }
    }
}
