package com.snapsnake.comfyremote.core;

import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public final class ComfyApiClient {
    public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int MAX_BODY_BYTES = 96 * 1024 * 1024;

    private final OkHttpClient safeHttp = new OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS)
            .writeTimeout(150, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .build();

    private final OkHttpClient normalHttp = new OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS)
            .writeTimeout(150, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private volatile ServerProfile profile;
    private volatile String resolvedBaseUrl = "";

    public ComfyApiClient(ServerProfile profile) {
        this.profile = profile;
    }

    public synchronized void setProfile(ServerProfile next) {
        this.profile = next;
        this.resolvedBaseUrl = "";
    }

    public ServerProfile profile() { return profile; }

    public String resolvedBaseUrl() {
        if (!resolvedBaseUrl.isEmpty()) return resolvedBaseUrl;
        List<String> candidates = profile == null ? Collections.emptyList() : profile.candidateBaseUrls();
        return candidates.isEmpty() ? "" : candidates.get(0);
    }

    public JSONObject systemStats() throws Exception { return getJson("/system_stats"); }
    public JSONObject objectInfo() throws Exception { return getJson("/api/object_info"); }
    public JSONObject queue() throws Exception { return getJson("/queue"); }
    public JSONObject history() throws Exception { return getJson("/history"); }
    public JSONObject history(String promptId) throws Exception { return getJson("/history/" + enc(promptId)); }
    public JSONArray templateIndex() throws Exception { return new JSONArray(getText("/templates/index.json")); }
    public JSONObject workflowTemplates() throws Exception { return getJson("/api/workflow_templates"); }

    public JSONObject prompt(JSONObject apiPrompt, String clientId) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("prompt", apiPrompt);
        payload.put("client_id", clientId);
        return postJson("/prompt", payload);
    }

    public JSONObject queueDelete(List<String> promptIds) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("delete", new JSONArray(promptIds));
        return postJson("/queue", payload);
    }

    public JSONObject queueClear() throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("clear", true);
        return postJson("/queue", payload);
    }

    public void interrupt() throws Exception {
        requestBytes("/interrupt", RequestBody.create(new byte[0], null), "POST");
    }

    public JSONObject uploadImage(byte[] bytes, String filename, boolean overwrite) throws Exception {
        if (bytes == null || bytes.length == 0) throw new IOException("Selected image is empty");
        String uploadName = InputImageUpload.normalizeFilename(filename, bytes);
        MediaType mediaType = MediaType.parse(InputImageUpload.mediaType(bytes, uploadName));
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", uploadName, RequestBody.create(bytes, mediaType))
                .addFormDataPart("type", "input")
                .addFormDataPart("overwrite", overwrite ? "true" : "false")
                .build();

        byte[] responseBytes = requestBytes("/upload/image", body, "POST");
        if (responseBytes.length == 0) throw new IOException("ComfyUI returned an empty upload response");
        JSONObject response = new JSONObject(new String(responseBytes, StandardCharsets.UTF_8));
        InputImageUpload.Ref uploaded = InputImageUpload.fromUploadResponse(response, uploadName);
        if (uploaded.filename.isEmpty()) throw new IOException("ComfyUI did not return the uploaded filename");
        String returnedType = response.optString("type", "input");
        if (!"input".equals(returnedType)) throw new IOException("ComfyUI saved the file as type " + returnedType + " instead of input");
        if (!inputImageExists(uploaded.workflowValue())) {
            throw new IOException("Upload was acknowledged, but ComfyUI cannot read input/" + uploaded.workflowValue());
        }

        response.put("name", uploaded.filename);
        response.put("subfolder", uploaded.subfolder);
        response.put("type", "input");
        response.put("workflow_value", uploaded.workflowValue());
        return response;
    }

    public boolean inputImageExists(String workflowValue) throws Exception {
        InputImageUpload.Ref ref = InputImageUpload.parseWorkflowValue(workflowValue);
        if (ref.filename.isEmpty()) return false;
        String path = "/view?filename=" + enc(ref.filename) + "&subfolder=" + enc(ref.subfolder) + "&type=input";
        return requestExists(path);
    }

    public String templateJsonPath(String source, String name) {
        if (source == null || source.trim().isEmpty() || "default".equals(source)) {
            return "/templates/" + path(name) + ".json";
        }
        return "/api/workflow_templates/" + path(source) + "/" + path(name) + ".json";
    }

    public List<String> templatePreviewPaths(String source, String name, String mediaSubtype) {
        ArrayList<String> out = new ArrayList<>();
        ArrayList<String> exts = new ArrayList<>();
        if (mediaSubtype != null && !mediaSubtype.trim().isEmpty()) exts.add(mediaSubtype.trim().toLowerCase());
        for (String ext : new String[]{"webp", "png", "jpg", "jpeg", "gif"}) if (!exts.contains(ext)) exts.add(ext);
        for (String ext : exts) {
            if (source == null || source.trim().isEmpty() || "default".equals(source)) {
                out.add("/templates/" + path(name) + "-1." + ext);
                out.add("/templates/" + path(name) + "." + ext);
            } else {
                out.add("/api/workflow_templates/" + path(source) + "/" + path(name) + "." + ext);
            }
        }
        return out;
    }

    public String viewUrl(String filename, String subfolder, String type) {
        return resolvedBaseUrl() + "/view?filename=" + enc(filename) + "&subfolder=" + enc(subfolder) + "&type=" + enc(type);
    }

    public byte[] getBytes(String path) throws Exception { return requestBytes(path, null, "GET"); }
    public String getText(String path) throws Exception { return new String(getBytes(path), StandardCharsets.UTF_8); }
    public JSONObject getJson(String path) throws Exception { return new JSONObject(getText(path)); }
    public JSONObject postJson(String path, JSONObject payload) throws Exception {
        byte[] data = requestBytes(path, RequestBody.create(payload.toString(), JSON), "POST");
        return data.length == 0 ? new JSONObject() : new JSONObject(new String(data, StandardCharsets.UTF_8));
    }

    public WebSocket openWebSocket(String clientId, WebSocketListener listener) {
        String base = resolvedBaseUrl();
        if (base.isEmpty()) throw new IllegalStateException("ComfyUI URL is empty");
        String wsBase = base.startsWith("https://") ? "wss://" + base.substring(8) :
                base.startsWith("http://") ? "ws://" + base.substring(7) : base;
        Request.Builder request = new Request.Builder().url(wsBase + "/ws?clientId=" + enc(clientId));
        applyHeaders(request, wsBase, false);
        return normalHttp.newWebSocket(request.build(), listener);
    }

    private byte[] requestBytes(String path, RequestBody body, String method) throws Exception {
        ServerProfile current = profile;
        if (current == null) throw new IOException("No server profile configured");
        StringBuilder errors = new StringBuilder();
        for (String base : orderedCandidates(current)) {
            try {
                byte[] response = execute(safeHttp, base + path, body, method, true);
                resolvedBaseUrl = base;
                return response;
            } catch (Exception e) {
                errors.append(base).append(" safe: ").append(shortError(e)).append('\n');
            }
            try {
                byte[] response = execute(normalHttp, base + path, body, method, false);
                resolvedBaseUrl = base;
                return response;
            } catch (Exception e) {
                errors.append(base).append(" normal: ").append(shortError(e)).append('\n');
            }
        }
        throw new IOException(errors.length() == 0 ? "No ComfyUI URL configured" : errors.toString().trim());
    }

    private boolean requestExists(String path) throws Exception {
        ServerProfile current = profile;
        if (current == null) throw new IOException("No server profile configured");
        StringBuilder errors = new StringBuilder();
        boolean sawNotFound = false;
        for (String base : orderedCandidates(current)) {
            try {
                Boolean exists = executeExists(safeHttp, base + path, true);
                if (Boolean.TRUE.equals(exists)) {
                    resolvedBaseUrl = base;
                    return true;
                }
                sawNotFound = true;
            } catch (Exception e) {
                errors.append(base).append(" safe: ").append(shortError(e)).append('\n');
            }
            try {
                Boolean exists = executeExists(normalHttp, base + path, false);
                if (Boolean.TRUE.equals(exists)) {
                    resolvedBaseUrl = base;
                    return true;
                }
                sawNotFound = true;
            } catch (Exception e) {
                errors.append(base).append(" normal: ").append(shortError(e)).append('\n');
            }
        }
        if (sawNotFound) return false;
        throw new IOException(errors.length() == 0 ? "Could not verify ComfyUI input file" : errors.toString().trim());
    }

    private List<String> orderedCandidates(ServerProfile current) {
        ArrayList<String> out = new ArrayList<>();
        if (!resolvedBaseUrl.isEmpty()) out.add(resolvedBaseUrl);
        for (String candidate : current.candidateBaseUrls()) if (!out.contains(candidate)) out.add(candidate);
        return out;
    }

    private byte[] execute(OkHttpClient client, String url, RequestBody body, String method, boolean safeMode) throws Exception {
        Request.Builder request = new Request.Builder().url(url);
        applyHeaders(request, url, safeMode);
        if ("POST".equals(method)) request.post(body == null ? RequestBody.create(new byte[0], null) : body);
        else request.get();
        try (Response response = client.newCall(request.build()).execute()) {
            byte[] bytes = response.body() == null ? new byte[0] : response.body().bytes();
            if (bytes.length > MAX_BODY_BYTES) throw new IOException("Response is too large");
            if (!response.isSuccessful()) {
                String text = new String(bytes, StandardCharsets.UTF_8);
                throw new IOException("HTTP " + response.code() + ": " + abbreviate(text, 400));
            }
            return bytes;
        }
    }

    private Boolean executeExists(OkHttpClient client, String url, boolean safeMode) throws Exception {
        Request.Builder request = new Request.Builder().url(url).get();
        applyHeaders(request, url, safeMode);
        try (Response response = client.newCall(request.build()).execute()) {
            if (response.isSuccessful()) return true;
            if (response.code() == 404) return false;
            String text = response.body() == null ? "" : response.body().string();
            throw new IOException("HTTP " + response.code() + ": " + abbreviate(text, 300));
        }
    }

    private void applyHeaders(Request.Builder request, String url, boolean safeMode) {
        request.header("Accept", "application/json,text/plain,image/*,video/*,audio/*,*/*");
        request.header("User-Agent", "ComfyUI-Mobile-Native/1.0 Android");
        if (safeMode) {
            request.header("Connection", "close");
            request.header("Accept-Encoding", "identity");
            request.header("Cache-Control", "no-cache");
            request.header("Pragma", "no-cache");
        }
        ServerProfile current = profile;
        if (current != null && current.hasCloudflareAccess()) {
            request.header("CF-Access-Client-Id", current.cloudflareClientId);
            request.header("CF-Access-Client-Secret", current.cloudflareClientSecret);
        }
        try {
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.trim().isEmpty()) request.header("Cookie", cookie);
        } catch (Exception ignored) {}
    }

    private static String enc(String value) {
        try { return URLEncoder.encode(value == null ? "" : value, "UTF-8"); }
        catch (Exception e) { return ""; }
    }

    private static String path(String value) { return enc(value).replace("%2F", "/"); }
    private static String shortError(Exception e) {
        String message = e == null ? "unknown error" : e.getMessage();
        if (message == null || message.trim().isEmpty()) message = e == null ? "unknown error" : e.getClass().getSimpleName();
        return abbreviate(message.replace('\n', ' '), 320);
    }
    private static String abbreviate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "…";
    }
}
