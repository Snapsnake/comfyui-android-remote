package com.snapsnake.comfyremote.core;

import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import okhttp3.ResponseBody;
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
    private volatile JSONObject objectInfoCache = new JSONObject();

    public ComfyApiClient(ServerProfile profile) {
        this.profile = profile;
    }

    public synchronized void setProfile(ServerProfile next) {
        this.profile = next;
        this.resolvedBaseUrl = "";
        this.objectInfoCache = new JSONObject();
    }

    public ServerProfile profile() { return profile; }

    public String resolvedBaseUrl() {
        if (!resolvedBaseUrl.isEmpty()) return resolvedBaseUrl;
        List<String> candidates = profile == null ? Collections.emptyList() : profile.candidateBaseUrls();
        return candidates.isEmpty() ? "" : candidates.get(0);
    }

    public JSONObject systemStats() throws Exception { return getJson("/system_stats"); }

    public JSONObject objectInfo() throws Exception {
        JSONObject latest = getJson("/api/object_info");
        objectInfoCache = cloneObject(latest);
        return latest;
    }

    public JSONObject queue() throws Exception { return getJson("/queue"); }
    public JSONObject history() throws Exception { return getJson("/history"); }
    public JSONObject history(String promptId) throws Exception { return getJson("/history/" + enc(promptId)); }
    public JSONArray templateIndex() throws Exception { return new JSONArray(getText("/templates/index.json")); }
    public JSONObject workflowTemplates() throws Exception { return getJson("/api/workflow_templates"); }

    public JSONObject prompt(JSONObject apiPrompt, String clientId) throws Exception {
        JSONObject schema = objectInfoCache;
        if (schema == null || schema.length() == 0) schema = objectInfo();
        JSONObject executablePrompt = DynamicExecutionPrompt.pack(apiPrompt, schema);
        JSONObject payload = new JSONObject();
        payload.put("prompt", executablePrompt);
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
        return requestExists(outputPath(ref.filename, ref.subfolder, "input"));
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

    /**
     * Returns a private app deep link instead of a browser URL. The native
     * viewer downloads the asset with the saved Cloudflare Access headers and
     * can then display it or hand a local content URI to another phone app.
     */
    public String viewUrl(String filename, String subfolder, String type) {
        return "comfyui-mobile-output://open?filename=" + enc(filename)
                + "&subfolder=" + enc(subfolder) + "&type=" + enc(type);
    }

    public String outputPath(String filename, String subfolder, String type) {
        return "/view?filename=" + enc(filename) + "&subfolder=" + enc(subfolder) + "&type=" + enc(type);
    }

    public void downloadOutputToFile(String filename, String subfolder, String type, File destination) throws Exception {
        if (filename == null || filename.trim().isEmpty()) throw new IOException("Output filename is empty");
        requestToFile(outputPath(filename, subfolder, type), destination);
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

    private void requestToFile(String path, File destination) throws Exception {
        ServerProfile current = profile;
        if (current == null) throw new IOException("No server profile configured");
        if (destination == null) throw new IOException("Output destination is missing");
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Could not create output cache folder");
        StringBuilder errors = new StringBuilder();
        for (String base : orderedCandidates(current)) {
            try {
                executeDownload(safeHttp, base + path, destination, true);
                resolvedBaseUrl = base;
                return;
            } catch (Exception e) {
                errors.append(base).append(" safe: ").append(shortError(e)).append('\n');
            }
            try {
                executeDownload(normalHttp, base + path, destination, false);
                resolvedBaseUrl = base;
                return;
            } catch (Exception e) {
                errors.append(base).append(" normal: ").append(shortError(e)).append('\n');
            }
        }
        throw new IOException(errors.length() == 0 ? "Could not download ComfyUI output" : errors.toString().trim());
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

    private void executeDownload(OkHttpClient client, String url, File destination, boolean safeMode) throws Exception {
        Request.Builder request = new Request.Builder().url(url).get();
        applyHeaders(request, url, safeMode);
        File temporary = new File(destination.getParentFile(), destination.getName() + ".part");
        if (temporary.exists()) temporary.delete();
        try (Response response = client.newCall(request.build()).execute()) {
            if (!response.isSuccessful()) {
                String text = response.body() == null ? "" : response.body().string();
                throw new IOException("HTTP " + response.code() + ": " + abbreviate(text, 400));
            }
            ResponseBody body = response.body();
            if (body == null) throw new IOException("ComfyUI returned an empty output response");
            long written = 0;
            try (InputStream input = body.byteStream(); FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    written += read;
                }
                output.flush();
            }
            if (written <= 0) throw new IOException("ComfyUI output is empty");
            if (destination.exists() && !destination.delete()) throw new IOException("Could not replace cached output");
            if (!temporary.renameTo(destination)) throw new IOException("Could not finalize cached output");
        } finally {
            if (temporary.exists() && !temporary.equals(destination)) temporary.delete();
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

    private static JSONObject cloneObject(JSONObject object) {
        if (object == null) return new JSONObject();
        try { return new JSONObject(object.toString()); }
        catch (Exception e) { return new JSONObject(); }
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
