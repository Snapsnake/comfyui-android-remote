package com.snapsnake.comfyremote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class MainActivity extends Activity {
    private static final String PREFS_NAME = "comfyui_remote_prefs";
    private static final String KEY_URL = "comfyui_url";
    private static final String KEY_WORKFLOW_API_JSON = "workflow_api_json";
    private static final String KEY_LAST_OUTPUT_URL = "last_output_url";

    private static final int FILE_CHOOSER_REQUEST = 42;
    private static final int IMAGE_PICKER_REQUEST = 43;
    private static final int WORKFLOW_PICKER_REQUEST = 44;

    private EditText urlInput;
    private TextView statusText;
    private ProgressBar progressBar;
    private ScrollView nativeScroll;
    private LinearLayout nativeContent;
    private WebView graphWebView;
    private EditText workflowJsonEditor;

    private ValueCallback<Uri[]> filePathCallback;
    private JSONObject workflowObject;
    private final List<ApiField> apiFields = new ArrayList<>();
    private String pendingImageNodeId;
    private String pendingImageInputKey;
    private String lastOutputUrl;
    private String currentPromptId;
    private int pollAttempts;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static class ApiField {
        final String nodeId;
        final String inputKey;
        final EditText editor;

        ApiField(String nodeId, String inputKey, EditText editor) {
            this.nodeId = nodeId;
            this.inputKey = inputKey;
            this.editor = editor;
        }
    }

    private static class OutputFile {
        final String filename;
        final String subfolder;
        final String type;

        OutputFile(String filename, String subfolder, String type) {
            this.filename = filename;
            this.subfolder = subfolder;
            this.type = type;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        configureGraphWebView();
        loadPrefs();
        renderNativeScreen();
        enterImmersiveMode();
    }

    private void loadPrefs() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        urlInput.setText(prefs.getString(KEY_URL, "http://desktop-name.tailnet.ts.net:8188"));
        lastOutputUrl = prefs.getString(KEY_LAST_OUTPUT_URL, "");
        String savedWorkflow = prefs.getString(KEY_WORKFLOW_API_JSON, "");
        if (!savedWorkflow.trim().isEmpty()) {
            try {
                workflowObject = new JSONObject(savedWorkflow);
            } catch (JSONException ignored) {
                workflowObject = null;
            }
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(2, 6, 23));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        topBar.setBackgroundColor(Color.rgb(15, 23, 42));
        root.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("ComfyUI Mobile Remote");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setPadding(0, 0, 0, dp(4));
        topBar.addView(title, new LinearLayout.LayoutParams(-1, -2));

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setHint("http://desktop-name.tailnet.ts.net:8188");
        urlInput.setTextColor(Color.WHITE);
        urlInput.setHintTextColor(Color.rgb(148, 163, 184));
        urlInput.setTextSize(14);
        urlInput.setPadding(dp(12), 0, dp(12), 0);
        urlInput.setBackground(buttonBackground(Color.rgb(30, 41, 59), dp(12)));
        topBar.addView(urlInput, new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout topButtons = new LinearLayout(this);
        topButtons.setOrientation(LinearLayout.HORIZONTAL);
        topButtons.setPadding(0, dp(8), 0, 0);
        topBar.addView(topButtons, new LinearLayout.LayoutParams(-1, -2));

        Button test = makePrimaryButton("Test");
        Button nativeMode = makeSecondaryButton("Native");
        Button graphMode = makeSecondaryButton("Graph");
        topButtons.addView(test, weightButtonParams());
        topButtons.addView(nativeMode, weightButtonParams());
        topButtons.addView(graphMode, weightButtonParams());
        test.setOnClickListener(v -> testConnection());
        nativeMode.setOnClickListener(v -> showNativeMode());
        graphMode.setOnClickListener(v -> showGraphMode());

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(3)));

        statusText = new TextView(this);
        statusText.setText("Native mode uses ComfyUI API: /upload/image, /prompt, /history and /view.");
        statusText.setTextColor(Color.rgb(203, 213, 225));
        statusText.setTextSize(12);
        statusText.setPadding(dp(12), dp(6), dp(12), dp(6));
        statusText.setBackgroundColor(Color.rgb(15, 23, 42));
        root.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout workspaceFrame = new FrameLayout(this);
        root.addView(workspaceFrame, new LinearLayout.LayoutParams(-1, 0, 1));

        nativeScroll = new ScrollView(this);
        nativeScroll.setFillViewport(false);
        nativeScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        nativeContent = new LinearLayout(this);
        nativeContent.setOrientation(LinearLayout.VERTICAL);
        nativeContent.setPadding(dp(14), dp(14), dp(14), dp(92));
        nativeScroll.addView(nativeContent, new ScrollView.LayoutParams(-1, -2));
        workspaceFrame.addView(nativeScroll, new FrameLayout.LayoutParams(-1, -1));

        graphWebView = new WebView(this);
        graphWebView.setVisibility(View.GONE);
        workspaceFrame.addView(graphWebView, new FrameLayout.LayoutParams(-1, -1));

        buildBottomToolbar(root);
        setContentView(root);
    }

    private void buildBottomToolbar(LinearLayout root) {
        LinearLayout bottomToolbar = new LinearLayout(this);
        bottomToolbar.setOrientation(LinearLayout.HORIZONTAL);
        bottomToolbar.setGravity(Gravity.CENTER);
        bottomToolbar.setPadding(dp(8), dp(8), dp(8), dp(8));
        bottomToolbar.setBackgroundColor(Color.rgb(15, 23, 42));
        root.addView(bottomToolbar, new LinearLayout.LayoutParams(-1, dp(72)));

        Button nativeBtn = makeToolbarButton("Native");
        Button graphBtn = makeToolbarButton("Graph");
        Button runBtn = makeToolbarButton("Run");
        Button outputBtn = makeToolbarButton("Output");
        Button menuBtn = makeToolbarButton("Menu");
        bottomToolbar.addView(nativeBtn, weightButtonParams());
        bottomToolbar.addView(graphBtn, weightButtonParams());
        bottomToolbar.addView(runBtn, weightButtonParams());
        bottomToolbar.addView(outputBtn, weightButtonParams());
        bottomToolbar.addView(menuBtn, weightButtonParams());

        nativeBtn.setOnClickListener(v -> showNativeMode());
        graphBtn.setOnClickListener(v -> showGraphMode());
        runBtn.setOnClickListener(v -> runWorkflow());
        outputBtn.setOnClickListener(v -> openLatestOutput());
        menuBtn.setOnClickListener(v -> showMenuDialog());
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureGraphWebView() {
        WebSettings s = graphWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setTextZoom(100);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        graphWebView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        graphWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent intent;
                try {
                    intent = params.createIntent();
                } catch (Exception e) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                }
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    toast("No file picker available");
                    return false;
                }
            }
        });
        graphWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
                statusText.setText("Graph loading: " + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                statusText.setText("Graph loaded. Native mode stays independent from this page.");
                injectGraphTouchTweaks();
                enterImmersiveMode();
            }
        });
    }

    private void renderNativeScreen() {
        apiFields.clear();
        nativeContent.removeAllViews();
        nativeContent.addView(makeTitle("Native workflow"));
        nativeContent.addView(makeMuted("This screen talks to ComfyUI API directly. WebView is only an advanced Graph mode now."));
        addWorkflowJsonCard();
        if (workflowObject == null) {
            addEmptyWorkflowHelp();
            return;
        }
        addQuickActionsCard();
        addNodeCardsFromWorkflow();
    }

    private void addWorkflowJsonCard() {
        LinearLayout card = makeCard();
        nativeContent.addView(card, cardParams());
        card.addView(makeCardHeader("Workflow API JSON"));
        card.addView(makeMuted("Load or paste a ComfyUI workflow saved in API format. Native mode edits this JSON, then sends it to /prompt."));

        workflowJsonEditor = new EditText(this);
        workflowJsonEditor.setText(workflowObject == null ? "" : formatJson(workflowObject));
        workflowJsonEditor.setTextColor(Color.WHITE);
        workflowJsonEditor.setHintTextColor(Color.rgb(148, 163, 184));
        workflowJsonEditor.setHint("Paste API workflow JSON here");
        workflowJsonEditor.setTextSize(13);
        workflowJsonEditor.setGravity(Gravity.TOP | Gravity.LEFT);
        workflowJsonEditor.setMinLines(5);
        workflowJsonEditor.setMaxLines(10);
        workflowJsonEditor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        workflowJsonEditor.setPadding(dp(12), dp(10), dp(12), dp(10));
        workflowJsonEditor.setBackground(buttonBackground(Color.rgb(15, 23, 42), dp(14)));
        LinearLayout.LayoutParams jsonParams = new LinearLayout.LayoutParams(-1, dp(180));
        jsonParams.setMargins(0, dp(10), 0, dp(10));
        card.addView(workflowJsonEditor, jsonParams);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        Button loadFile = makeSecondaryButton("Load JSON file");
        Button applyJson = makePrimaryButton("Apply JSON");
        row.addView(loadFile, weightButtonParams());
        row.addView(applyJson, weightButtonParams());
        loadFile.setOnClickListener(v -> chooseWorkflowJsonFile());
        applyJson.setOnClickListener(v -> applyWorkflowJsonFromEditor());
    }

    private void addEmptyWorkflowHelp() {
        LinearLayout card = makeCard();
        nativeContent.addView(card, cardParams());
        card.addView(makeCardHeader("No API workflow loaded"));
        card.addView(makeMuted("Export/save your ComfyUI workflow in API format, then load that JSON here. After that this app can upload images, run generation and open results without relying on the desktop canvas."));
    }

    private void addQuickActionsCard() {
        LinearLayout card = makeCard();
        nativeContent.addView(card, cardParams());
        card.addView(makeCardHeader("Run controls"));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        Button apply = makeSecondaryButton("Apply fields");
        Button run = makePrimaryButton("Run workflow");
        row.addView(apply, weightButtonParams());
        row.addView(run, weightButtonParams());
        apply.setOnClickListener(v -> {
            applyFieldsToWorkflowObject();
            saveWorkflowToPrefs();
            toast("Workflow fields applied");
            renderNativeScreen();
        });
        run.setOnClickListener(v -> runWorkflow());

        Button output = makeSecondaryButton("Open latest output");
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(50));
        p.setMargins(dp(3), dp(10), dp(3), 0);
        card.addView(output, p);
        output.setOnClickListener(v -> openLatestOutput());
    }

    private void addNodeCardsFromWorkflow() {
        List<String> nodeIds = sortedWorkflowKeys();
        for (String nodeId : nodeIds) {
            JSONObject node = workflowObject.optJSONObject(nodeId);
            if (node == null) continue;
            JSONObject inputs = node.optJSONObject("inputs");
            String classType = node.optString("class_type", "Node");
            if (inputs == null) continue;
            if (!nodeHasUsefulInputs(classType, inputs)) continue;
            addApiNodeCard(nodeId, classType, inputs);
        }
    }

    private void addApiNodeCard(String nodeId, String classType, JSONObject inputs) {
        LinearLayout card = makeCard();
        nativeContent.addView(card, cardParams());
        card.addView(makeCardHeader("#" + nodeId + "  " + prettifyClassType(classType)));

        if (isLoadImageClass(classType)) {
            card.addView(makeLabel("Image input"));
            String imageKey = findImageInputKey(inputs);
            Button choose = makePrimaryButton("Choose image from phone");
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(58));
            p.setMargins(0, 0, 0, dp(12));
            card.addView(choose, p);
            choose.setOnClickListener(v -> chooseImageForField(nodeId, imageKey));
        }

        if (isOutputClass(classType)) {
            card.addView(makeLabel("Output"));
            Button preview = makeSecondaryButton("Preview latest output");
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(54));
            p.setMargins(0, 0, 0, dp(12));
            card.addView(preview, p);
            preview.setOnClickListener(v -> openLatestOutput());
        }

        List<String> keys = sortedInputKeys(inputs);
        boolean added = false;
        for (String key : keys) {
            Object value = inputs.opt(key);
            if (!isEditablePrimitive(value)) continue;
            addApiField(card, nodeId, key, value, classType);
            added = true;
        }
        if (!added) card.addView(makeMuted("No directly editable primitive inputs in this node."));
    }

    private void addApiField(LinearLayout card, String nodeId, String key, Object value, String classType) {
        card.addView(makeLabel(humanLabel(key, classType)));
        EditText editor = new EditText(this);
        editor.setText(value == JSONObject.NULL ? "" : String.valueOf(value));
        editor.setTextColor(Color.WHITE);
        editor.setHintTextColor(Color.rgb(148, 163, 184));
        editor.setTextSize(17);
        editor.setPadding(dp(12), 0, dp(12), 0);
        editor.setBackground(buttonBackground(Color.rgb(15, 23, 42), dp(14)));
        editor.setSelectAllOnFocus(false);

        boolean multiline = isMultilineField(key, value);
        editor.setSingleLine(!multiline);
        if (multiline) {
            editor.setGravity(Gravity.TOP | Gravity.LEFT);
            editor.setMinLines(3);
            editor.setMaxLines(8);
            editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        } else if (isNumericValue(value) || isNumericKey(key)) {
            editor.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        } else {
            editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, multiline ? dp(116) : dp(56));
        p.setMargins(0, 0, 0, dp(12));
        card.addView(editor, p);
        apiFields.add(new ApiField(nodeId, key, editor));
    }

    private void showNativeMode() {
        nativeScroll.setVisibility(View.VISIBLE);
        graphWebView.setVisibility(View.GONE);
        renderNativeScreen();
        statusText.setText("Native mode. Upload, run and output are handled through ComfyUI API.");
        enterImmersiveMode();
    }

    private void showGraphMode() {
        saveUrl();
        nativeScroll.setVisibility(View.GONE);
        graphWebView.setVisibility(View.VISIBLE);
        String base = getNormalizedUrl();
        if (base.isEmpty()) {
            toast("Enter ComfyUI URL first");
            return;
        }
        String current = graphWebView.getUrl();
        if (current == null || !current.startsWith(base) || current.contains("/view")) graphWebView.loadUrl(base);
        statusText.setText("Graph mode is advanced/fallback. Native mode remains available.");
        enterImmersiveMode();
    }

    private void chooseWorkflowJsonFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(Intent.createChooser(intent, "Choose API workflow JSON"), WORKFLOW_PICKER_REQUEST);
        } catch (Exception e) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            fallback.setType("*/*");
            try {
                startActivityForResult(Intent.createChooser(fallback, "Choose API workflow JSON"), WORKFLOW_PICKER_REQUEST);
            } catch (Exception ex) {
                toast("No file picker available");
            }
        }
    }

    private void applyWorkflowJsonFromEditor() {
        try {
            workflowObject = new JSONObject(workflowJsonEditor.getText().toString());
            saveWorkflowToPrefs();
            toast("Workflow JSON loaded");
            renderNativeScreen();
        } catch (JSONException e) {
            toast("Invalid workflow JSON");
        }
    }

    private void chooseImageForField(String nodeId, String inputKey) {
        if (inputKey == null || inputKey.trim().isEmpty()) {
            toast("Image input not found in this node");
            return;
        }
        clearPendingImagePick();
        pendingImageNodeId = nodeId;
        pendingImageInputKey = inputKey;

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            toast("Opening image picker...");
            startActivityForResult(Intent.createChooser(intent, "Choose image"), IMAGE_PICKER_REQUEST);
        } catch (Exception e) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            fallback.setType("image/*");
            try {
                toast("Opening image picker...");
                startActivityForResult(Intent.createChooser(fallback, "Choose image"), IMAGE_PICKER_REQUEST);
            } catch (Exception ex) {
                clearPendingImagePick();
                toast("No image picker available");
            }
        }
    }

    private void clearPendingImagePick() {
        pendingImageNodeId = null;
        pendingImageInputKey = null;
    }

    private void uploadPickedImage(Uri uri, String nodeId, String inputKey) {
        if (uri == null || nodeId == null || inputKey == null) return;
        String base = getNormalizedUrl();
        if (base.isEmpty()) {
            toast("Enter ComfyUI URL first");
            return;
        }
        setBusy(true, "Uploading image...");
        new Thread(() -> {
            try {
                String name = getDisplayName(uri);
                String mime = getContentResolver().getType(uri);
                byte[] bytes = readBytes(uri);
                String uploadedName = uploadImageMultipart(base, bytes, name, mime);
                setWorkflowInput(nodeId, inputKey, uploadedName);
                saveWorkflowToPrefs();
                mainHandler.post(() -> {
                    setBusy(false, "Uploaded image: " + uploadedName);
                    toast("Image selected: " + uploadedName);
                    renderNativeScreen();
                });
            } catch (Exception e) {
                mainHandler.post(() -> setBusy(false, "Image upload failed: " + e.getClass().getSimpleName()));
            }
        }).start();
    }

    private void runWorkflow() {
        if (workflowObject == null) {
            toast("Load API workflow JSON first");
            return;
        }
        saveUrl();
        applyFieldsToWorkflowObject();
        saveWorkflowToPrefs();
        String base = getNormalizedUrl();
        if (base.isEmpty()) {
            toast("Enter ComfyUI URL first");
            return;
        }
        String body;
        try {
            JSONObject payload = new JSONObject();
            payload.put("prompt", workflowObject);
            payload.put("client_id", "android-remote-" + System.currentTimeMillis());
            body = payload.toString();
        } catch (JSONException e) {
            toast("Could not build prompt JSON");
            return;
        }

        setBusy(true, "Sending prompt to ComfyUI...");
        new Thread(() -> {
            try {
                String response = postJson(base + "/prompt", body);
                JSONObject json = new JSONObject(response);
                String promptId = json.optString("prompt_id", "");
                if (promptId.isEmpty()) throw new IllegalStateException("No prompt_id");
                currentPromptId = promptId;
                pollAttempts = 0;
                mainHandler.post(() -> {
                    setBusy(false, "Queued prompt: " + promptId);
                    pollPromptHistory();
                });
            } catch (Exception e) {
                mainHandler.post(() -> setBusy(false, "Run failed: " + e.getClass().getSimpleName()));
            }
        }).start();
    }

    private void pollPromptHistory() {
        if (currentPromptId == null || currentPromptId.isEmpty()) return;
        pollAttempts++;
        String promptId = currentPromptId;
        String base = getNormalizedUrl();
        statusText.setText("Waiting for output... attempt " + pollAttempts);
        new Thread(() -> {
            try {
                String body = getText(base + "/history/" + enc(promptId));
                OutputFile file = findLatestOutputFile(new JSONObject(body));
                if (file != null) {
                    String outputUrl = base + "/view?filename=" + enc(file.filename) + "&type=" + enc(file.type) + "&subfolder=" + enc(file.subfolder);
                    lastOutputUrl = outputUrl;
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_LAST_OUTPUT_URL, outputUrl).apply();
                    mainHandler.post(() -> statusText.setText("Output ready. Press Output to open it."));
                    return;
                }
            } catch (Exception ignored) {}
            if (pollAttempts < 120) mainHandler.postDelayed(this::pollPromptHistory, 2000);
            else mainHandler.post(() -> statusText.setText("Timed out waiting for output. You can still check Output later."));
        }).start();
    }

    private void openLatestOutput() {
        saveUrl();
        if (lastOutputUrl != null && !lastOutputUrl.trim().isEmpty()) {
            openExternalUrl(lastOutputUrl);
            return;
        }
        String base = getNormalizedUrl();
        if (base.isEmpty()) {
            toast("Enter ComfyUI URL first");
            return;
        }
        setBusy(true, "Looking for latest output...");
        new Thread(() -> {
            try {
                String body = getText(base + "/history");
                OutputFile file = findLatestOutputFile(new JSONObject(body));
                if (file == null) throw new IllegalStateException("No output");
                String outputUrl = base + "/view?filename=" + enc(file.filename) + "&type=" + enc(file.type) + "&subfolder=" + enc(file.subfolder);
                lastOutputUrl = outputUrl;
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_LAST_OUTPUT_URL, outputUrl).apply();
                mainHandler.post(() -> {
                    setBusy(false, "Opening latest output externally.");
                    openExternalUrl(outputUrl);
                });
            } catch (Exception e) {
                mainHandler.post(() -> setBusy(false, "No output found yet"));
            }
        }).start();
    }

    private void openExternalUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            toast("No app can open output URL");
        }
    }

    private void showMenuDialog() {
        String[] items = new String[]{"Load API workflow JSON", "Apply JSON editor", "Test connection", "Clear graph WebView cache"};
        new AlertDialog.Builder(this)
                .setTitle("Menu")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) chooseWorkflowJsonFile();
                    if (which == 1) applyWorkflowJsonFromEditor();
                    if (which == 2) testConnection();
                    if (which == 3) {
                        graphWebView.clearCache(true);
                        graphWebView.clearHistory();
                        toast("Graph cache cleared");
                    }
                })
                .show();
    }

    private void testConnection() {
        saveUrl();
        String base = getNormalizedUrl();
        if (base.isEmpty()) {
            toast("Enter ComfyUI URL first");
            return;
        }
        setBusy(true, "Testing /system_stats...");
        new Thread(() -> {
            try {
                getText(base + "/system_stats");
                mainHandler.post(() -> setBusy(false, "Connection OK. ComfyUI API reachable."));
            } catch (Exception e) {
                mainHandler.post(() -> setBusy(false, "Connection failed: " + e.getClass().getSimpleName()));
            }
        }).start();
    }

    private void applyFieldsToWorkflowObject() {
        if (workflowObject == null) return;
        for (ApiField field : apiFields) setWorkflowInput(field.nodeId, field.inputKey, coerceInputValue(field.editor.getText().toString()));
    }

    private Object coerceInputValue(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;
        if (s.matches("-?\\d+")) {
            try {
                long l = Long.parseLong(s);
                if (l <= Integer.MAX_VALUE && l >= Integer.MIN_VALUE) return (int) l;
                return l;
            } catch (Exception ignored) {}
        }
        if (s.matches("-?\\d+\\.\\d+")) {
            try {
                return Double.parseDouble(s);
            } catch (Exception ignored) {}
        }
        return raw;
    }

    private void setWorkflowInput(String nodeId, String inputKey, Object value) {
        if (workflowObject == null) return;
        JSONObject node = workflowObject.optJSONObject(nodeId);
        if (node == null) return;
        JSONObject inputs = node.optJSONObject("inputs");
        if (inputs == null) return;
        try {
            inputs.put(inputKey, value);
        } catch (JSONException ignored) {}
    }

    private void saveWorkflowToPrefs() {
        if (workflowObject == null) return;
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_WORKFLOW_API_JSON, workflowObject.toString()).apply();
    }

    private void saveUrl() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_URL, getNormalizedUrl()).apply();
    }

    private String getNormalizedUrl() {
        String raw = urlInput.getText().toString().trim();
        if (raw.isEmpty()) return "";
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) raw = "http://" + raw;
        while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
        return raw;
    }

    private byte[] readBytes(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IllegalStateException("No input stream");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private String readUriText(Uri uri) throws Exception {
        return new String(readBytes(uri), "UTF-8");
    }

    private String getDisplayName(Uri uri) {
        String result = null;
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) result = cursor.getString(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        if (result == null || result.trim().isEmpty()) result = "comfy_remote_image.png";
        return result.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String uploadImageMultipart(String base, byte[] bytes, String filename, String mime) throws Exception {
        String boundary = "----ComfyRemote" + System.currentTimeMillis();
        HttpURLConnection c = null;
        try {
            URL url = new URL(base + "/upload/image");
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            OutputStream out = c.getOutputStream();
            writePart(out, boundary, "type", "input");
            writePart(out, boundary, "overwrite", "true");
            writeFilePart(out, boundary, "image", filename, mime == null ? "application/octet-stream" : mime, bytes);
            out.write(("--" + boundary + "--\r\n").getBytes("UTF-8"));
            out.flush();
            out.close();
            int code = c.getResponseCode();
            String body = readStream(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + ": " + body);
            JSONObject json = new JSONObject(body);
            return json.optString("name", filename);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void writePart(OutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes("UTF-8"));
        out.write((value + "\r\n").getBytes("UTF-8"));
    }

    private void writeFilePart(OutputStream out, String boundary, String name, String filename, String mime, byte[] bytes) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n").getBytes("UTF-8"));
        out.write(("Content-Type: " + mime + "\r\n\r\n").getBytes("UTF-8"));
        out.write(bytes);
        out.write("\r\n".getBytes("UTF-8"));
    }

    private String postJson(String url, String jsonBody) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            OutputStream out = c.getOutputStream();
            out.write(jsonBody.getBytes("UTF-8"));
            out.flush();
            out.close();
            int code = c.getResponseCode();
            String body = readStream(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + ": " + body);
            return body;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String getText(String url) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(20000);
            int code = c.getResponseCode();
            String body = readStream(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + ": " + body);
            return body;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String readStream(InputStream in) throws Exception {
        if (in == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    private OutputFile findLatestOutputFile(JSONObject history) throws JSONException {
        OutputFile found = null;
        Iterator<String> promptIds = history.keys();
        while (promptIds.hasNext()) {
            JSONObject item = history.optJSONObject(promptIds.next());
            if (item == null) continue;
            JSONObject outputs = item.optJSONObject("outputs");
            if (outputs == null) continue;
            Iterator<String> nodeIds = outputs.keys();
            while (nodeIds.hasNext()) {
                JSONObject nodeOut = outputs.optJSONObject(nodeIds.next());
                if (nodeOut == null) continue;
                OutputFile f = firstFileInArray(nodeOut.optJSONArray("videos"));
                if (f != null) found = f;
                f = firstFileInArray(nodeOut.optJSONArray("gifs"));
                if (f != null) found = f;
                f = firstFileInArray(nodeOut.optJSONArray("images"));
                if (f != null) found = f;
            }
        }
        return found;
    }

    private OutputFile firstFileInArray(JSONArray arr) {
        if (arr == null || arr.length() == 0) return null;
        JSONObject f = arr.optJSONObject(0);
        if (f == null) return null;
        String filename = f.optString("filename", "");
        if (filename.isEmpty()) return null;
        return new OutputFile(filename, f.optString("subfolder", ""), f.optString("type", "output"));
    }

    private String enc(String s) throws Exception {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    private boolean nodeHasUsefulInputs(String classType, JSONObject inputs) {
        if (isLoadImageClass(classType) || isOutputClass(classType)) return true;
        Iterator<String> keys = inputs.keys();
        while (keys.hasNext()) if (isEditablePrimitive(inputs.opt(keys.next()))) return true;
        return false;
    }

    private List<String> sortedWorkflowKeys() {
        List<String> keys = new ArrayList<>();
        Iterator<String> it = workflowObject.keys();
        while (it.hasNext()) keys.add(it.next());
        Collections.sort(keys, (a, b) -> {
            try {
                return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
            } catch (Exception ignored) {
                return a.compareTo(b);
            }
        });
        return keys;
    }

    private List<String> sortedInputKeys(JSONObject inputs) {
        List<String> keys = new ArrayList<>();
        Iterator<String> it = inputs.keys();
        while (it.hasNext()) keys.add(it.next());
        Collections.sort(keys);
        return keys;
    }

    private boolean isEditablePrimitive(Object value) {
        return value == JSONObject.NULL || value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    private boolean isNumericValue(Object value) {
        return value instanceof Number;
    }

    private boolean isNumericKey(String key) {
        String k = key.toLowerCase();
        return k.contains("width") || k.contains("height") || k.contains("step") || k.contains("seed") || k.contains("cfg") || k.contains("duration") || k.contains("batch") || k.contains("fps") || k.contains("frame");
    }

    private boolean isMultilineField(String key, Object value) {
        String k = key.toLowerCase();
        return k.contains("prompt") || k.contains("text") || String.valueOf(value).length() > 80;
    }

    private boolean isLoadImageClass(String classType) {
        String c = classType.toLowerCase();
        return c.contains("loadimage") || c.contains("load image");
    }

    private boolean isOutputClass(String classType) {
        String c = classType.toLowerCase();
        return c.contains("saveimage") || c.contains("save image") || c.contains("savevideo") || c.contains("save video") || c.contains("previewimage") || c.contains("preview image");
    }

    private String findImageInputKey(JSONObject inputs) {
        Iterator<String> keys = inputs.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("image".equalsIgnoreCase(key) || key.toLowerCase().contains("image")) return key;
        }
        return "image";
    }

    private String humanLabel(String key, String classType) {
        String k = key.toLowerCase();
        if (k.equals("ckpt_name")) return "Checkpoint";
        if (k.equals("lora_name")) return "LoRA";
        if (k.equals("text_encoder")) return "Text encoder";
        if (k.equals("seed") || k.equals("noise_seed")) return "Seed";
        if (k.equals("steps")) return "Steps";
        if (k.equals("cfg")) return "CFG";
        if (k.equals("width")) return "Width";
        if (k.equals("height")) return "Height";
        if (k.equals("filename_prefix")) return "Filename prefix";
        if (k.equals("image")) return "Image";
        if (k.equals("text") || k.equals("prompt") || k.equals("positive")) return "Prompt";
        return prettifyClassType(key.replace('_', ' '));
    }

    private String prettifyClassType(String value) {
        if (value == null || value.isEmpty()) return "Node";
        String spaced = value.replace('_', ' ').replaceAll("([a-z])([A-Z])", "$1 $2");
        return spaced.trim();
    }

    private String formatJson(JSONObject json) {
        try {
            return json.toString(2);
        } catch (JSONException e) {
            return json.toString();
        }
    }

    private LinearLayout makeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(buttonBackground(Color.rgb(30, 41, 59), dp(22)));
        return card;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(14));
        return p;
    }

    private LinearLayout.LayoutParams weightButtonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private TextView makeTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(28);
        t.setPadding(dp(2), 0, dp(2), dp(10));
        return t;
    }

    private TextView makeCardHeader(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(20);
        t.setPadding(dp(2), 0, dp(2), dp(10));
        return t;
    }

    private TextView makeLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.rgb(226, 232, 240));
        t.setTextSize(17);
        t.setPadding(dp(2), dp(6), dp(2), dp(6));
        return t;
    }

    private TextView makeMuted(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.rgb(148, 163, 184));
        t.setTextSize(14);
        t.setPadding(dp(2), 0, dp(2), dp(10));
        return t;
    }

    private Button makePrimaryButton(String text) {
        Button b = makeBaseButton(text);
        b.setBackground(buttonBackground(Color.rgb(37, 99, 235), dp(14)));
        return b;
    }

    private Button makeSecondaryButton(String text) {
        Button b = makeBaseButton(text);
        b.setBackground(buttonBackground(Color.rgb(51, 65, 85), dp(14)));
        return b;
    }

    private Button makeToolbarButton(String text) {
        Button b = makeBaseButton(text);
        b.setTextSize(13);
        b.setBackground(buttonBackground(Color.rgb(30, 41, 59), dp(16)));
        return b;
    }

    private Button makeBaseButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setSingleLine(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setIncludeFontPadding(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(6), 0, dp(6), 0);
        return b;
    }

    private GradientDrawable buttonBackground(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private void setBusy(boolean busy, String message) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        statusText.setText(message);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void injectGraphTouchTweaks() {
        String script = "(function(){var head=document.head||document.documentElement;var meta=document.querySelector('meta[name=viewport]');if(!meta){meta=document.createElement('meta');meta.name='viewport';head.appendChild(meta);}meta.content='width=device-width,initial-scale=1,minimum-scale=0.35,maximum-scale=3,user-scalable=yes,viewport-fit=cover';if(!document.getElementById('comfy-android-remote-style')){var style=document.createElement('style');style.id='comfy-android-remote-style';style.textContent='button,input,select,textarea,[role=button]{min-height:40px!important;font-size:15px!important;}canvas{touch-action:none!important;}';head.appendChild(style);}})();";
        graphWebView.evaluateJavascript(script, null);
    }

    private void enterImmersiveMode() {
        Window window = getWindow();
        View decor = window.getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersiveMode();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (filePathCallback == null) return;
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
            enterImmersiveMode();
            return;
        }
        if (requestCode == IMAGE_PICKER_REQUEST) {
            String nodeId = pendingImageNodeId;
            String inputKey = pendingImageInputKey;
            clearPendingImagePick();
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                uploadPickedImage(data.getData(), nodeId, inputKey);
            } else {
                statusText.setText("Image selection cancelled. You can press Choose image again.");
            }
            enterImmersiveMode();
            return;
        }
        if (requestCode == WORKFLOW_PICKER_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try {
                    workflowJsonEditor.setText(readUriText(data.getData()));
                    applyWorkflowJsonFromEditor();
                } catch (Exception e) {
                    toast("Could not read workflow file");
                }
            }
            enterImmersiveMode();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (graphWebView.getVisibility() == View.VISIBLE) {
            if (graphWebView.canGoBack()) graphWebView.goBack();
            else showNativeMode();
            return;
        }
        super.onBackPressed();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
