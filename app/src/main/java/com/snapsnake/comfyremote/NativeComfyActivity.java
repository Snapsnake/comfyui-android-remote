package com.snapsnake.comfyremote;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.snapsnake.comfyremote.core.ComfyRepository;
import com.snapsnake.comfyremote.core.ComfyStore;
import com.snapsnake.comfyremote.core.NodeSchemaRegistry;
import com.snapsnake.comfyremote.core.OutputAsset;
import com.snapsnake.comfyremote.core.ServerProfile;
import com.snapsnake.comfyremote.core.TemplateDescriptor;
import com.snapsnake.comfyremote.core.WorkflowDocument;
import com.snapsnake.comfyremote.core.WorkflowFileImporter;
import com.snapsnake.comfyremote.ui.DynamicFieldRenderer;
import com.snapsnake.comfyremote.ui.UiKit;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public final class NativeComfyActivity extends Activity {
    private static final String VERSION = "0.14.0-native-foundation";
    private static final int REQ_IMPORT_WORKFLOW = 1101;
    private static final int REQ_UPLOAD_FIELD = 1102;
    private static final int REQ_UPLOAD_LIBRARY = 1103;
    private static final int REQ_SAVE_OUTPUT = 1104;
    private static final int TEMPLATE_PAGE = 30;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(7);
    private final String clientId = "comfyui-mobile-" + UUID.randomUUID();

    private ComfyStore store;
    private ComfyRepository repository;
    private DynamicFieldRenderer fieldRenderer;

    private LinearLayout root;
    private LinearLayout setupPanel;
    private LinearLayout content;
    private LinearLayout bottomNav;
    private ScrollView scroll;
    private EditText urlInput;
    private EditText cfIdInput;
    private EditText cfSecretInput;
    private TextView liveStatusText;

    private String screen = "home";
    private String status = "Ready.";
    private String selectedNodeId = "";
    private String nodeFilter = "";
    private String templateFilter = "";
    private int templateLimit = TEMPLATE_PAGE;
    private boolean busy = false;
    private boolean workflowDirty = false;

    private List<TemplateDescriptor> templates = new ArrayList<>();
    private List<OutputAsset> outputs = new ArrayList<>();
    private JSONObject queueState = new JSONObject();
    private WebSocket webSocket;
    private NodeSchemaRegistry.FieldSpec pendingUploadField;
    private OutputAsset pendingSaveAsset;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try { CookieManager.getInstance().setAcceptCookie(true); } catch (Exception ignored) {}
        store = new ComfyStore(this);
        repository = new ComfyRepository(store, store.activeProfile());
        fieldRenderer = new DynamicFieldRenderer(this);
        templates = repository.cachedTemplates();
        outputs = decodeSavedOutputs(store.outputs());
        buildShell();
        loadProfileIntoInputs(repository.profile());
        if (!repository.profile().baseUrl.isEmpty()) setupPanel.setVisibility(View.GONE);
        render();
        styleSystemBars();
    }

    @Override protected void onDestroy() {
        if (webSocket != null) webSocket.close(1000, "Activity destroyed");
        io.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (!selectedNodeId.isEmpty()) {
            saveWorkflowIfDirty();
            selectedNodeId = "";
            screen = "fields";
            render();
            return;
        }
        super.onBackPressed();
    }

    private void buildShell() {
        root = UiKit.column(this);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(UiKit.BG);

        setupPanel = UiKit.column(this);
        setupPanel.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 12), UiKit.dp(this, 18), UiKit.dp(this, 14));
        setupPanel.setBackgroundColor(UiKit.SURFACE);
        setupPanel.addView(UiKit.title(this, "ComfyUI Mobile", 21));
        setupPanel.addView(UiKit.muted(this, "Native client for ComfyUI running on your computer · " + VERSION, 11), UiKit.match(this, -2, 3));

        urlInput = UiKit.input(this, "https://your-comfyui-domain", true);
        setupPanel.addView(urlInput, UiKit.match(this, 50, 9));
        cfIdInput = UiKit.input(this, "CF-Access-Client-Id (optional)", true);
        setupPanel.addView(cfIdInput, UiKit.match(this, 44, 8));
        cfSecretInput = UiKit.input(this, "CF-Access-Client-Secret (optional)", true);
        cfSecretInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        setupPanel.addView(cfSecretInput, UiKit.match(this, 44, 8));

        LinearLayout connectionActions = UiKit.row(this);
        connectionActions.addView(UiKit.button(this, busy ? "Testing…" : "Test", true, v -> testConnection()), UiKit.weighted(this, 44));
        connectionActions.addView(UiKit.button(this, "Save", false, v -> saveProfileFromInputs()), UiKit.weighted(this, 44));
        connectionActions.addView(UiKit.button(this, "Hide", false, v -> setupPanel.setVisibility(View.GONE)), UiKit.weighted(this, 44));
        setupPanel.addView(connectionActions, UiKit.match(this, -2, 10));
        root.addView(setupPanel, new LinearLayout.LayoutParams(-1, -2));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(UiKit.BG);
        content = UiKit.column(this);
        content.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 16), UiKit.dp(this, 18), UiKit.dp(this, 24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        bottomNav = UiKit.row(this);
        bottomNav.setGravity(Gravity.CENTER);
        bottomNav.setPadding(UiKit.dp(this, 7), UiKit.dp(this, 8), UiKit.dp(this, 7), UiKit.dp(this, 8));
        bottomNav.setBackgroundColor(UiKit.SURFACE);
        root.addView(bottomNav, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 78)));
        setContentView(root);
    }

    private void render() {
        content.removeAllViews();
        renderBottomNav();
        if (!selectedNodeId.isEmpty()) renderNodeEditor(selectedNodeId);
        else if ("templates".equals(screen)) renderTemplates();
        else if ("fields".equals(screen)) renderFields();
        else if ("queue".equals(screen)) renderQueue();
        else if ("outputs".equals(screen)) renderOutputs();
        else if ("settings".equals(screen)) renderSettings();
        else renderHome();
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void renderHome() {
        content.addView(statusCard(), UiKit.section(this));
        content.addView(pageHeader("Workstation", "Control the real ComfyUI server without reproducing its desktop canvas."), UiKit.section(this));

        WorkflowDocument workflow = repository.currentWorkflow();
        LinearLayout workflowCard = UiKit.card(this, workflow != null && !workflow.isEmpty());
        workflowCard.addView(cardTitle("◇", workflow == null || workflow.isEmpty() ? "No workflow loaded" : safeName(workflow.sourceName(), "Workflow")));
        workflowCard.addView(UiKit.muted(this, workflow == null || workflow.isEmpty() ? "Import JSON, PNG or WebP, or open an original ComfyUI template." : workflow.nodeCount() + " API nodes loaded. Original workflow metadata is preserved separately.", 13));
        LinearLayout row = UiKit.row(this);
        row.setPadding(0, UiKit.dp(this, 12), 0, 0);
        row.addView(UiKit.button(this, "Import", true, v -> chooseWorkflow()), UiKit.weighted(this, 44));
        row.addView(UiKit.button(this, "Templates", false, v -> openScreen("templates")), UiKit.weighted(this, 44));
        workflowCard.addView(row);
        if (workflow != null && !workflow.isEmpty()) {
            LinearLayout actions = UiKit.row(this);
            actions.setPadding(0, UiKit.dp(this, 10), 0, 0);
            actions.addView(UiKit.button(this, "Edit fields", false, v -> openScreen("fields")), UiKit.weighted(this, 44));
            actions.addView(UiKit.button(this, "Queue prompt", true, v -> queueCurrentWorkflow()), UiKit.weighted(this, 44));
            workflowCard.addView(actions);
            workflowCard.addView(UiKit.button(this, "Save to local workflow library", false, v -> saveCurrentToLibrary()), UiKit.match(this, 42, 10));
        }
        content.addView(workflowCard, UiKit.section(this));

        LinearLayout upload = UiKit.card(this, false);
        upload.addView(cardTitle("⇧", "Upload input images"));
        upload.addView(UiKit.muted(this, "Select one or more images and upload them to ComfyUI input storage. A LoadImage field can also upload directly from its editor.", 13));
        upload.addView(UiKit.button(this, "Choose images", false, v -> chooseLibraryImages()), UiKit.match(this, 42, 10));
        content.addView(upload, UiKit.section(this));

        List<JSONObject> recents = store.recentWorkflows();
        if (!recents.isEmpty()) {
            content.addView(UiKit.title(this, "Recent workflows", 18), UiKit.section(this));
            int shown = Math.min(8, recents.size());
            for (int i = 0; i < shown; i++) {
                JSONObject item = recents.get(i);
                String id = item.optString("libraryId", "");
                LinearLayout card = UiKit.card(this, false);
                card.setOnClickListener(v -> {
                    WorkflowDocument loaded = store.loadWorkflowFromLibrary(id);
                    repository.setCurrentWorkflow(loaded);
                    setStatus("Loaded local workflow: " + safeName(loaded.sourceName(), "Workflow"));
                    render();
                });
                card.addView(UiKit.title(this, item.optString("displayName", "Workflow"), 15));
                JSONObject prompt = item.optJSONObject("apiPrompt");
                card.addView(UiKit.muted(this, (prompt == null ? 0 : prompt.length()) + " nodes · saved locally", 12));
                content.addView(card, UiKit.section(this));
            }
        }
    }

    private void renderTemplates() {
        content.addView(statusCard(), UiKit.section(this));
        content.addView(pageHeader("Templates", "Original ComfyUI templates, previews and categories."), UiKit.section(this));

        LinearLayout tools = UiKit.card(this, false);
        EditText search = UiKit.input(this, "Search templates…", true);
        search.setText(templateFilter);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                templateFilter = s == null ? "" : s.toString();
                templateLimit = TEMPLATE_PAGE;
                renderTemplates();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        tools.addView(search, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 48)));
        tools.addView(UiKit.muted(this, "Cached: " + templates.size() + cacheAgeText(store.templatesUpdatedAt()), 12), UiKit.match(this, -2, 9));
        LinearLayout actions = UiKit.row(this);
        actions.setPadding(0, UiKit.dp(this, 8), 0, 0);
        actions.addView(UiKit.button(this, busy ? "Refreshing…" : "Refresh", true, v -> refreshTemplates()), UiKit.weighted(this, 44));
        actions.addView(UiKit.button(this, "Clear search", false, v -> { templateFilter = ""; templateLimit = TEMPLATE_PAGE; render(); }), UiKit.weighted(this, 44));
        tools.addView(actions);
        content.addView(tools, UiKit.section(this));

        ArrayList<TemplateDescriptor> filtered = new ArrayList<>();
        String query = templateFilter.trim().toLowerCase(Locale.US);
        for (TemplateDescriptor item : templates) {
            if (query.isEmpty() || item.searchableText().toLowerCase(Locale.US).contains(query)) filtered.add(item);
        }
        int max = Math.min(templateLimit, filtered.size());
        for (int i = 0; i < max; i++) content.addView(templateCard(filtered.get(i)), UiKit.section(this));
        if (filtered.isEmpty()) content.addView(emptyCard(templates.isEmpty() ? "No cached templates. Tap Refresh." : "No matching templates."), UiKit.section(this));
        if (filtered.size() > max) {
            content.addView(UiKit.button(this, "Show more (" + max + "/" + filtered.size() + ")", false, v -> { templateLimit += TEMPLATE_PAGE; render(); }), UiKit.section(this));
        }
    }

    private View templateCard(TemplateDescriptor item) {
        LinearLayout card = UiKit.card(this, false);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setClickable(true);
        card.setOnClickListener(v -> openTemplate(item));

        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setImageResource(R.drawable.ic_launcher);
        preview.setBackground(UiKit.background(this, UiKit.SURFACE_2, 12, UiKit.STROKE, 2));
        preview.setTag(item.id());
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(UiKit.dp(this, 112), UiKit.dp(this, 84));
        imageParams.setMargins(0, 0, UiKit.dp(this, 13), 0);
        card.addView(preview, imageParams);

        LinearLayout copy = UiKit.column(this);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(copy, new LinearLayout.LayoutParams(0, UiKit.dp(this, 84), 1f));
        TextView name = UiKit.title(this, item.title, 16);
        name.setMaxLines(2);
        copy.addView(name);
        TextView description = UiKit.muted(this, safeName(item.description, item.category), 12);
        description.setMaxLines(2);
        copy.addView(description);
        copy.addView(UiKit.muted(this, item.category, 11));
        card.addView(UiKit.centeredIcon(this, "›", 26, UiKit.MUTED), new LinearLayout.LayoutParams(UiKit.dp(this, 22), UiKit.dp(this, 84)));
        loadTemplatePreview(preview, item);
        return card;
    }

    private void renderFields() {
        content.addView(statusCard(), UiKit.section(this));
        WorkflowDocument workflow = repository.currentWorkflow();
        content.addView(pageHeader("Fields", workflow == null || workflow.isEmpty() ? "Load a workflow first." : "Fields come from the server's /object_info schema; connected inputs remain linked."), UiKit.section(this));
        if (workflow == null || workflow.isEmpty()) {
            content.addView(emptyCard("No workflow loaded."), UiKit.section(this));
            return;
        }

        LinearLayout tools = UiKit.card(this, false);
        EditText search = UiKit.input(this, "Search nodes or fields…", true);
        search.setText(nodeFilter);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                nodeFilter = s == null ? "" : s.toString();
                renderFields();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        tools.addView(search, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 48)));
        tools.addView(UiKit.muted(this, workflow.nodeCount() + " nodes · tap a node to edit its native controls", 12), UiKit.match(this, -2, 9));
        content.addView(tools, UiKit.section(this));

        NodeSchemaRegistry registry = repository.schemaRegistry();
        JSONObject prompt = workflow.apiPrompt();
        ArrayList<String> ids = sortedNodeIds(prompt);
        String query = nodeFilter.trim().toLowerCase(Locale.US);
        int shown = 0;
        for (String id : ids) {
            JSONObject node = prompt.optJSONObject(id);
            if (node == null) continue;
            String classType = node.optString("class_type", "Node");
            String title = nodeTitle(node, registry);
            List<NodeSchemaRegistry.FieldSpec> fields = registry.fieldsForNode(id, node);
            StringBuilder searchable = new StringBuilder(id).append(' ').append(classType).append(' ').append(title);
            for (NodeSchemaRegistry.FieldSpec field : fields) searchable.append(' ').append(field.key);
            if (!query.isEmpty() && !searchable.toString().toLowerCase(Locale.US).contains(query)) continue;
            LinearLayout card = UiKit.card(this, false);
            card.setClickable(true);
            card.setOnClickListener(v -> { selectedNodeId = id; render(); });
            card.addView(UiKit.title(this, title, 16));
            int editable = 0;
            int connected = 0;
            for (NodeSchemaRegistry.FieldSpec field : fields) {
                if (field.connected) connected++; else editable++;
            }
            card.addView(UiKit.muted(this, "#" + id + " · " + classType + " · " + editable + " editable" + (connected > 0 ? " · " + connected + " connected" : ""), 12));
            content.addView(card, UiKit.section(this));
            shown++;
        }
        if (shown == 0) content.addView(emptyCard("No matching nodes."), UiKit.section(this));
    }

    private void renderNodeEditor(String nodeId) {
        WorkflowDocument workflow = repository.currentWorkflow();
        JSONObject node = workflow == null ? null : workflow.apiPrompt().optJSONObject(nodeId);
        NodeSchemaRegistry registry = repository.schemaRegistry();
        content.addView(statusCard(), UiKit.section(this));
        content.addView(pageHeader(node == null ? "Node" : nodeTitle(node, registry), node == null ? "" : "#" + nodeId + " · " + node.optString("class_type", "Node")), UiKit.section(this));
        if (node == null) {
            content.addView(emptyCard("Node was not found."), UiKit.section(this));
            return;
        }

        List<NodeSchemaRegistry.FieldSpec> fields = registry.fieldsForNode(nodeId, node);
        LinearLayout editor = UiKit.card(this, false);
        if (fields.isEmpty()) editor.addView(UiKit.muted(this, "This node exposes no editable primitive widgets in /object_info.", 13));
        for (NodeSchemaRegistry.FieldSpec field : fields) {
            editor.addView(fieldRenderer.render(field, node, () -> workflowDirty = true, this::chooseFileForField));
            editor.addView(UiKit.divider(this));
        }
        LinearLayout actions = UiKit.row(this);
        actions.setPadding(0, UiKit.dp(this, 12), 0, 0);
        actions.addView(UiKit.button(this, "Back", false, v -> { saveWorkflowIfDirty(); selectedNodeId = ""; render(); }), UiKit.weighted(this, 44));
        actions.addView(UiKit.button(this, workflowDirty ? "Save changes" : "Saved", true, v -> { saveWorkflowIfDirty(); render(); }), UiKit.weighted(this, 44));
        editor.addView(actions);
        content.addView(editor, UiKit.section(this));
    }

    private void renderQueue() {
        content.addView(statusCard(), UiKit.section(this));
        content.addView(pageHeader("Queue", "Live queue state, execution progress, interrupt and deletion."), UiKit.section(this));
        LinearLayout actions = UiKit.card(this, false);
        LinearLayout first = UiKit.row(this);
        first.addView(UiKit.button(this, "Queue workflow", true, v -> queueCurrentWorkflow()), UiKit.weighted(this, 44));
        first.addView(UiKit.button(this, "Refresh", false, v -> refreshQueue()), UiKit.weighted(this, 44));
        actions.addView(first);
        LinearLayout second = UiKit.row(this);
        second.setPadding(0, UiKit.dp(this, 10), 0, 0);
        second.addView(UiKit.button(this, "Interrupt", false, v -> interruptExecution()), UiKit.weighted(this, 44));
        second.addView(UiKit.button(this, "Clear pending", false, v -> clearQueue()), UiKit.weighted(this, 44));
        actions.addView(second);
        content.addView(actions, UiKit.section(this));

        JSONArray running = queueState.optJSONArray("queue_running");
        JSONArray pending = queueState.optJSONArray("queue_pending");
        content.addView(UiKit.title(this, "Running", 18), UiKit.section(this));
        if (running == null || running.length() == 0) content.addView(emptyCard("Nothing is running."), UiKit.section(this));
        else for (int i = 0; i < running.length(); i++) content.addView(queueItemCard(running.optJSONArray(i), true), UiKit.section(this));
        content.addView(UiKit.title(this, "Pending", 18), UiKit.section(this));
        if (pending == null || pending.length() == 0) content.addView(emptyCard("Queue is empty."), UiKit.section(this));
        else for (int i = 0; i < pending.length(); i++) content.addView(queueItemCard(pending.optJSONArray(i), false), UiKit.section(this));
    }

    private View queueItemCard(JSONArray item, boolean running) {
        LinearLayout card = UiKit.card(this, running);
        String promptId = queuePromptId(item);
        card.addView(UiKit.title(this, running ? "Running prompt" : "Queued prompt", 15));
        card.addView(UiKit.muted(this, promptId.isEmpty() ? "Unknown prompt id" : promptId, 12));
        if (!running && !promptId.isEmpty()) {
            card.addView(UiKit.button(this, "Delete from queue", false, v -> deleteQueued(promptId)), UiKit.match(this, 40, 9));
        }
        return card;
    }

    private void renderOutputs() {
        content.addView(statusCard(), UiKit.section(this));
        content.addView(pageHeader("Outputs", "Images, GIF, video, audio and other files returned by ComfyUI history."), UiKit.section(this));
        LinearLayout tools = UiKit.card(this, false);
        LinearLayout actions = UiKit.row(this);
        actions.addView(UiKit.button(this, "Refresh history", true, v -> refreshOutputs()), UiKit.weighted(this, 44));
        actions.addView(UiKit.button(this, "Open ComfyUI", false, v -> openUrl(repository.client().resolvedBaseUrl())), UiKit.weighted(this, 44));
        tools.addView(actions);
        tools.addView(UiKit.muted(this, outputs.size() + " cached output assets", 12), UiKit.match(this, -2, 9));
        content.addView(tools, UiKit.section(this));
        if (outputs.isEmpty()) {
            content.addView(emptyCard("No outputs found yet."), UiKit.section(this));
            return;
        }
        int max = Math.min(40, outputs.size());
        for (int i = 0; i < max; i++) content.addView(outputCard(outputs.get(i)), UiKit.section(this));
    }

    private View outputCard(OutputAsset asset) {
        LinearLayout card = UiKit.card(this, false);
        card.addView(UiKit.title(this, asset.filename, 15));
        card.addView(UiKit.muted(this, asset.kind.name().toLowerCase(Locale.US) + " · node " + asset.nodeId + (asset.subfolder.isEmpty() ? "" : " · " + asset.subfolder), 12));
        if (asset.kind == OutputAsset.Kind.IMAGE || asset.kind == OutputAsset.Kind.GIF) {
            ImageView preview = new ImageView(this);
            preview.setAdjustViewBounds(true);
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            preview.setBackground(UiKit.background(this, UiKit.SURFACE_2, 12, UiKit.STROKE, 2));
            card.addView(preview, UiKit.match(this, 220, 10));
            loadOutputPreview(preview, asset);
        }
        LinearLayout actions = UiKit.row(this);
        actions.setPadding(0, UiKit.dp(this, 10), 0, 0);
        actions.addView(UiKit.button(this, "Open", true, v -> openUrl(repository.client().viewUrl(asset.filename, asset.subfolder, asset.type))), UiKit.weighted(this, 42));
        actions.addView(UiKit.button(this, "Save", false, v -> chooseSaveDestination(asset)), UiKit.weighted(this, 42));
        card.addView(actions);
        return card;
    }

    private void renderSettings() {
        content.addView(statusCard(), UiKit.section(this));
        content.addView(pageHeader("Connection", "Cloudflare Tunnel works as a normal HTTPS server. Access service tokens remain optional."), UiKit.section(this));
        LinearLayout current = UiKit.card(this, true);
        current.addView(UiKit.title(this, safeName(repository.profile().name, "ComfyUI"), 16));
        current.addView(UiKit.muted(this, safeName(repository.profile().baseUrl, "No server URL"), 13));
        current.addView(UiKit.button(this, "Edit connection", false, v -> setupPanel.setVisibility(View.VISIBLE)), UiKit.match(this, 42, 10));
        content.addView(current, UiKit.section(this));

        List<ServerProfile> profiles = store.profiles();
        if (profiles.size() > 1) {
            content.addView(UiKit.title(this, "Saved servers", 18), UiKit.section(this));
            for (ServerProfile profile : profiles) {
                if (profile.id.equals(repository.profile().id)) continue;
                LinearLayout card = UiKit.card(this, false);
                card.setOnClickListener(v -> {
                    repository.setProfile(profile);
                    loadProfileIntoInputs(profile);
                    setStatus("Selected server: " + profile.name);
                    setupPanel.setVisibility(View.VISIBLE);
                    render();
                });
                card.addView(UiKit.title(this, profile.name, 15));
                card.addView(UiKit.muted(this, profile.baseUrl, 12));
                content.addView(card, UiKit.section(this));
            }
        }
        LinearLayout details = UiKit.card(this, false);
        details.addView(UiKit.label(this, "Native compatibility model"));
        details.addView(UiKit.muted(this, "Node controls are generated from /object_info. Unknown workflow metadata is retained. Custom-node widget adapters can be added without changing the API, storage or workflow layers.", 13));
        details.addView(UiKit.muted(this, "Portrait mode · " + VERSION, 12), UiKit.match(this, -2, 9));
        content.addView(details, UiKit.section(this));
    }

    private View statusCard() {
        LinearLayout card = UiKit.row(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(UiKit.dp(this, 13), UiKit.dp(this, 10), UiKit.dp(this, 13), UiKit.dp(this, 10));
        card.setBackground(UiKit.background(this, UiKit.SURFACE, 16, UiKit.STROKE, 2));
        boolean configured = repository.profile() != null && !repository.profile().baseUrl.isEmpty();
        card.addView(UiKit.centeredIcon(this, configured ? "●" : "○", 17, configured ? UiKit.SUCCESS : UiKit.MUTED), new LinearLayout.LayoutParams(UiKit.dp(this, 28), UiKit.dp(this, 46)));
        LinearLayout copy = UiKit.column(this);
        copy.addView(UiKit.muted(this, configured ? safeName(repository.profile().name, repository.profile().baseUrl) : "No ComfyUI server configured", 12));
        liveStatusText = UiKit.muted(this, status, 12);
        liveStatusText.setMaxLines(3);
        copy.addView(liveStatusText);
        card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(UiKit.centeredIcon(this, "›", 23, UiKit.MUTED), new LinearLayout.LayoutParams(UiKit.dp(this, 24), UiKit.dp(this, 46)));
        card.setOnClickListener(v -> setupPanel.setVisibility(setupPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        return card;
    }

    private View pageHeader(String title, String subtitle) {
        LinearLayout header = UiKit.column(this);
        header.addView(UiKit.title(this, title, 30));
        TextView sub = UiKit.muted(this, subtitle, 14);
        sub.setMaxLines(4);
        header.addView(sub);
        return header;
    }

    private View cardTitle(String icon, String value) {
        LinearLayout row = UiKit.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = UiKit.centeredIcon(this, icon, 14, UiKit.ACCENT);
        badge.setBackground(UiKit.background(this, UiKit.SURFACE_2, 12, UiKit.STROKE, 2));
        row.addView(badge, new LinearLayout.LayoutParams(UiKit.dp(this, 36), UiKit.dp(this, 36)));
        TextView title = UiKit.title(this, value, 18);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
        params.setMargins(UiKit.dp(this, 11), 0, 0, 0);
        row.addView(title, params);
        return row;
    }

    private View emptyCard(String message) {
        LinearLayout card = UiKit.card(this, false);
        card.addView(UiKit.muted(this, message, 14));
        return card;
    }

    private void renderBottomNav() {
        bottomNav.removeAllViews();
        bottomNav.addView(navItem("⌂", "Home", "home"), UiKit.weighted(this, 58));
        bottomNav.addView(navItem("▦", "Templates", "templates"), UiKit.weighted(this, 58));
        bottomNav.addView(navItem("⌘", "Fields", "fields"), UiKit.weighted(this, 58));
        bottomNav.addView(navItem("▷", "Queue", "queue"), UiKit.weighted(this, 58));
        bottomNav.addView(navItem("▧", "Outputs", "outputs"), UiKit.weighted(this, 58));
        bottomNav.addView(navItem("⚙", "Connect", "settings"), UiKit.weighted(this, 58));
    }

    private View navItem(String icon, String label, String target) {
        boolean selected = target.equals(screen) && selectedNodeId.isEmpty();
        LinearLayout item = UiKit.column(this);
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 4));
        item.setBackground(selected ? UiKit.background(this, android.graphics.Color.rgb(48, 36, 24), 14, UiKit.ACCENT, 2) : UiKit.background(this, android.graphics.Color.TRANSPARENT, 14, android.graphics.Color.TRANSPARENT, 0));
        item.addView(UiKit.centeredIcon(this, icon, 18, selected ? UiKit.ACCENT : UiKit.MUTED), new LinearLayout.LayoutParams(-1, UiKit.dp(this, 25)));
        item.addView(UiKit.centeredIcon(this, label, 10, selected ? UiKit.ACCENT : UiKit.MUTED), new LinearLayout.LayoutParams(-1, UiKit.dp(this, 20)));
        item.setOnClickListener(v -> {
            saveWorkflowIfDirty();
            selectedNodeId = "";
            openScreen(target);
        });
        return item;
    }

    private void openScreen(String target) {
        screen = target;
        render();
        if ("queue".equals(target)) refreshQueue();
        if ("outputs".equals(target)) refreshOutputs();
    }

    private void testConnection() {
        if (busy) return;
        saveProfileFromInputs();
        busy = true;
        setStatus("Connecting and loading /object_info…");
        io.execute(() -> {
            try {
                JSONObject stats = repository.connectAndRefreshSchema();
                ui.post(() -> {
                    busy = false;
                    loadProfileIntoInputs(repository.profile());
                    setStatus("Connected · " + systemLabel(stats) + " · " + repository.objectInfo().length() + " node classes");
                    setupPanel.setVisibility(View.GONE);
                    connectWebSocket();
                    render();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    busy = false;
                    setStatus("Connection failed: " + friendlyError(e));
                    setupPanel.setVisibility(View.VISIBLE);
                    render();
                });
            }
        });
    }

    private void saveProfileFromInputs() {
        ServerProfile old = repository.profile();
        ServerProfile profile = new ServerProfile(
                old == null ? "" : old.id,
                old == null ? "" : old.name,
                urlInput.getText().toString().trim(),
                cfIdInput.getText().toString().trim(),
                cfSecretInput.getText().toString().trim()
        );
        repository.setProfile(profile);
        try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
        setStatus("Connection profile saved.");
    }

    private void loadProfileIntoInputs(ServerProfile profile) {
        if (profile == null) return;
        urlInput.setText(profile.baseUrl);
        cfIdInput.setText(profile.cloudflareClientId);
        cfSecretInput.setText(profile.cloudflareClientSecret);
    }

    private void refreshTemplates() {
        if (busy) return;
        busy = true;
        setStatus("Refreshing ComfyUI templates…");
        io.execute(() -> {
            try {
                List<TemplateDescriptor> loaded = repository.refreshTemplates();
                ui.post(() -> {
                    templates = loaded;
                    busy = false;
                    templateLimit = TEMPLATE_PAGE;
                    setStatus("Loaded " + loaded.size() + " templates.");
                    render();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    busy = false;
                    setStatus("Template refresh failed: " + friendlyError(e));
                    render();
                });
            }
        });
    }

    private void openTemplate(TemplateDescriptor item) {
        if (busy) return;
        busy = true;
        setStatus("Opening template: " + item.title);
        io.execute(() -> {
            try {
                WorkflowDocument document = repository.openTemplate(item);
                ui.post(() -> {
                    busy = false;
                    workflowDirty = false;
                    screen = "fields";
                    setStatus("Template loaded: " + document.nodeCount() + " nodes.");
                    render();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    busy = false;
                    setStatus("Template open failed: " + friendlyError(e));
                    render();
                });
            }
        });
    }

    private void loadTemplatePreview(ImageView image, TemplateDescriptor item) {
        io.execute(() -> {
            try {
                byte[] data = repository.loadTemplatePreview(item);
                Bitmap bitmap = decodeScaled(data, 360, 280);
                if (bitmap != null) ui.post(() -> {
                    if (item.id().equals(String.valueOf(image.getTag()))) image.setImageBitmap(bitmap);
                });
            } catch (Exception ignored) {}
        });
    }

    private void chooseWorkflow() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "image/png", "image/webp", "application/octet-stream"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(Intent.createChooser(intent, "Import ComfyUI workflow"), REQ_IMPORT_WORKFLOW);
    }

    private void chooseFileForField(NodeSchemaRegistry.FieldSpec field) {
        pendingUploadField = field;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(Intent.createChooser(intent, "Upload image to ComfyUI"), REQ_UPLOAD_FIELD);
    }

    private void chooseLibraryImages() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(Intent.createChooser(intent, "Upload images to ComfyUI"), REQ_UPLOAD_LIBRARY);
    }

    private void chooseSaveDestination(OutputAsset asset) {
        pendingSaveAsset = asset;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeFor(asset.filename));
        intent.putExtra(Intent.EXTRA_TITLE, asset.filename);
        startActivityForResult(intent, REQ_SAVE_OUTPUT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_IMPORT_WORKFLOW && data.getData() != null) importWorkflowUri(data.getData());
        else if (requestCode == REQ_UPLOAD_FIELD && data.getData() != null) uploadFieldImage(data.getData());
        else if (requestCode == REQ_UPLOAD_LIBRARY) uploadLibraryImages(data);
        else if (requestCode == REQ_SAVE_OUTPUT && data.getData() != null && pendingSaveAsset != null) saveOutput(data.getData(), pendingSaveAsset);
    }

    private void importWorkflowUri(Uri uri) {
        setStatus("Reading workflow metadata…");
        io.execute(() -> {
            try {
                WorkflowFileImporter.Imported imported = WorkflowFileImporter.read(this, uri);
                WorkflowDocument document = WorkflowDocument.importRaw(imported.workflow, repository.objectInfo(), imported.sourceName);
                repository.setCurrentWorkflow(document);
                ui.post(() -> {
                    workflowDirty = false;
                    screen = "fields";
                    setStatus("Imported " + document.nodeCount() + " nodes from " + imported.sourceName + ".");
                    render();
                });
            } catch (Exception e) {
                ui.post(() -> setStatus("Import failed: " + friendlyError(e)));
            }
        });
    }

    private void uploadFieldImage(Uri uri) {
        NodeSchemaRegistry.FieldSpec field = pendingUploadField;
        if (field == null) return;
        setStatus("Uploading image to ComfyUI…");
        io.execute(() -> {
            try {
                byte[] bytes = readUri(uri);
                String filename = filename(uri);
                JSONObject response = repository.client().uploadImage(bytes, filename, true);
                String serverName = response.optString("name", filename);
                WorkflowDocument workflow = repository.currentWorkflow();
                JSONObject node = workflow.apiPrompt().optJSONObject(field.nodeId);
                if (node == null) throw new IllegalStateException("Node no longer exists");
                JSONObject inputs = node.optJSONObject("inputs");
                if (inputs == null) { inputs = new JSONObject(); node.put("inputs", inputs); }
                inputs.put(field.key, serverName);
                repository.setCurrentWorkflow(workflow.snapshot());
                ui.post(() -> {
                    pendingUploadField = null;
                    workflowDirty = false;
                    setStatus("Uploaded: " + serverName);
                    render();
                });
            } catch (Exception e) {
                ui.post(() -> setStatus("Upload failed: " + friendlyError(e)));
            }
        });
    }

    private void uploadLibraryImages(Intent data) {
        ArrayList<Uri> uris = new ArrayList<>();
        if (data.getData() != null) uris.add(data.getData());
        ClipData clip = data.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        if (uris.isEmpty()) return;
        setStatus("Uploading " + uris.size() + " image(s)…");
        io.execute(() -> {
            int uploaded = 0;
            String last = "";
            for (Uri uri : uris) {
                try {
                    String name = filename(uri);
                    JSONObject response = repository.client().uploadImage(readUri(uri), name, true);
                    last = response.optString("name", name);
                    uploaded++;
                } catch (Exception ignored) {}
            }
            int finalUploaded = uploaded;
            String finalLast = last;
            ui.post(() -> setStatus("Uploaded " + finalUploaded + "/" + uris.size() + (finalLast.isEmpty() ? "" : " · last: " + finalLast)));
        });
    }

    private void saveOutput(Uri destination, OutputAsset asset) {
        setStatus("Saving " + asset.filename + "…");
        io.execute(() -> {
            try {
                String path = "/view?filename=" + enc(asset.filename) + "&subfolder=" + enc(asset.subfolder) + "&type=" + enc(asset.type);
                byte[] bytes = repository.client().getBytes(path);
                try (OutputStream out = getContentResolver().openOutputStream(destination)) {
                    if (out == null) throw new IllegalStateException("Could not open destination");
                    out.write(bytes);
                }
                ui.post(() -> setStatus("Saved: " + asset.filename));
            } catch (Exception e) {
                ui.post(() -> setStatus("Save failed: " + friendlyError(e)));
            }
        });
    }

    private void queueCurrentWorkflow() {
        if (busy) return;
        if (repository.currentWorkflow() == null || repository.currentWorkflow().isEmpty()) {
            setStatus("Load a workflow first.");
            return;
        }
        saveWorkflowIfDirty();
        busy = true;
        setStatus("Sending prompt to ComfyUI…");
        io.execute(() -> {
            try {
                String promptId = repository.queueCurrentWorkflow(clientId);
                ui.post(() -> {
                    busy = false;
                    setStatus("Queued prompt: " + promptId);
                    screen = "queue";
                    connectWebSocket();
                    refreshQueue();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    busy = false;
                    setStatus("Queue failed: " + friendlyError(e));
                    render();
                });
            }
        });
    }

    private void refreshQueue() {
        io.execute(() -> {
            try {
                JSONObject queue = repository.queue();
                ui.post(() -> { queueState = queue; if ("queue".equals(screen)) render(); });
            } catch (Exception e) {
                ui.post(() -> setStatus("Queue refresh failed: " + friendlyError(e)));
            }
        });
    }

    private void interruptExecution() {
        io.execute(() -> {
            try {
                repository.interrupt();
                ui.post(() -> { setStatus("Interrupt requested."); refreshQueue(); });
            } catch (Exception e) { ui.post(() -> setStatus("Interrupt failed: " + friendlyError(e))); }
        });
    }

    private void clearQueue() {
        io.execute(() -> {
            try {
                repository.clearQueue();
                ui.post(() -> { setStatus("Pending queue cleared."); refreshQueue(); });
            } catch (Exception e) { ui.post(() -> setStatus("Clear queue failed: " + friendlyError(e))); }
        });
    }

    private void deleteQueued(String promptId) {
        io.execute(() -> {
            try {
                repository.deleteQueued(Collections.singletonList(promptId));
                ui.post(() -> { setStatus("Deleted queued prompt."); refreshQueue(); });
            } catch (Exception e) { ui.post(() -> setStatus("Delete failed: " + friendlyError(e))); }
        });
    }

    private void refreshOutputs() {
        io.execute(() -> {
            try {
                JSONObject history = repository.history();
                List<OutputAsset> loaded = repository.parseOutputs(history);
                repository.saveOutputs(loaded);
                ui.post(() -> {
                    outputs = loaded;
                    if ("outputs".equals(screen)) render();
                    setStatus("History loaded: " + loaded.size() + " output assets.");
                });
            } catch (Exception e) {
                ui.post(() -> setStatus("History refresh failed: " + friendlyError(e)));
            }
        });
    }

    private void loadOutputPreview(ImageView image, OutputAsset asset) {
        io.execute(() -> {
            try {
                String path = "/view?filename=" + enc(asset.filename) + "&subfolder=" + enc(asset.subfolder) + "&type=" + enc(asset.type);
                Bitmap bitmap = decodeScaled(repository.client().getBytes(path), 1000, 800);
                if (bitmap != null) ui.post(() -> image.setImageBitmap(bitmap));
            } catch (Exception ignored) {}
        });
    }

    private void connectWebSocket() {
        if (repository.client().resolvedBaseUrl().isEmpty()) return;
        if (webSocket != null) webSocket.close(1000, "Reconnect");
        try {
            webSocket = repository.client().openWebSocket(clientId, new WebSocketListener() {
                @Override public void onOpen(WebSocket webSocket, Response response) {
                    ui.post(() -> setStatus("Live connection ready."));
                }
                @Override public void onMessage(WebSocket webSocket, String text) {
                    handleSocketMessage(text);
                }
                @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
                    // Binary messages are live preview frames. Final assets remain sourced from /history and /view.
                }
                @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    ui.post(() -> setStatus("Live connection unavailable; REST controls still work: " + shortText(t == null ? "unknown" : t.getMessage(), 120)));
                }
            });
        } catch (Exception e) {
            setStatus("WebSocket unavailable: " + friendlyError(e));
        }
    }

    private void handleSocketMessage(String text) {
        try {
            JSONObject message = new JSONObject(text);
            String type = message.optString("type", "");
            JSONObject data = message.optJSONObject("data");
            if ("progress".equals(type) && data != null) {
                int value = data.optInt("value", 0);
                int max = Math.max(1, data.optInt("max", 1));
                ui.post(() -> setStatus("Generating: " + value + "/" + max + " (" + Math.round(value * 100f / max) + "%)"));
            } else if ("executing".equals(type) && data != null) {
                String node = data.optString("node", "");
                if (node.isEmpty() || "null".equals(node)) {
                    ui.post(() -> { setStatus("Execution finished."); refreshQueue(); refreshOutputs(); });
                } else ui.post(() -> setStatus("Executing node " + node + "…"));
            } else if ("execution_error".equals(type)) {
                ui.post(() -> setStatus("ComfyUI execution error: " + shortText(text, 180)));
            } else if ("status".equals(type)) {
                ui.post(this::refreshQueue);
            }
        } catch (Exception ignored) {}
    }

    private void saveCurrentToLibrary() {
        WorkflowDocument workflow = repository.currentWorkflow();
        if (workflow == null || workflow.isEmpty()) return;
        saveWorkflowIfDirty();
        try {
            store.saveWorkflowToLibrary(safeName(workflow.sourceName(), "Workflow"), workflow.snapshot());
            setStatus("Workflow saved to local library.");
            render();
        } catch (Exception e) {
            setStatus("Could not save workflow: " + friendlyError(e));
        }
    }

    private void saveWorkflowIfDirty() {
        if (!workflowDirty) return;
        WorkflowDocument workflow = repository.currentWorkflow();
        if (workflow != null) repository.setCurrentWorkflow(workflow.snapshot());
        workflowDirty = false;
        setStatus("Workflow values saved locally.");
    }

    private ArrayList<String> sortedNodeIds(JSONObject prompt) {
        ArrayList<String> ids = new ArrayList<>();
        if (prompt == null) return ids;
        Iterator<String> keys = prompt.keys();
        while (keys.hasNext()) ids.add(keys.next());
        ids.sort((a, b) -> {
            try { return Long.compare(Long.parseLong(a), Long.parseLong(b)); }
            catch (Exception ignored) { return a.compareToIgnoreCase(b); }
        });
        return ids;
    }

    private String nodeTitle(JSONObject node, NodeSchemaRegistry registry) {
        JSONObject meta = node.optJSONObject("_meta");
        String title = meta == null ? "" : meta.optString("title", "");
        String classType = node.optString("class_type", "Node");
        return title.trim().isEmpty() ? registry.displayName(classType) : title;
    }

    private String queuePromptId(JSONArray item) {
        if (item == null) return "";
        if (item.length() > 1) return String.valueOf(item.opt(1));
        return "";
    }

    private List<OutputAsset> decodeSavedOutputs(JSONArray raw) {
        ArrayList<OutputAsset> list = new ArrayList<>();
        if (raw == null) return list;
        for (int i = 0; i < raw.length(); i++) {
            OutputAsset asset = OutputAsset.fromJson(raw.optJSONObject(i));
            if (!asset.filename.isEmpty()) list.add(asset);
        }
        return list;
    }

    private Bitmap decodeScaled(byte[] data, int maxWidth, int maxHeight) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inSampleSize = 1;
            while (bounds.outWidth / options.inSampleSize > maxWidth || bounds.outHeight / options.inSampleSize > maxHeight) options.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] readUri(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IllegalStateException("Could not open file");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    private String filename(Uri uri) {
        String raw = uri == null ? "input.png" : uri.getLastPathSegment();
        if (raw == null || raw.trim().isEmpty()) return "input.png";
        int split = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf(':'));
        String name = split >= 0 && split + 1 < raw.length() ? raw.substring(split + 1) : raw;
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return name.isEmpty() ? "input.png" : name;
    }

    private void setStatus(String value) {
        status = value == null ? "" : value;
        if (liveStatusText != null) liveStatusText.setText(status);
    }

    private void openUrl(String url) {
        try {
            if (url == null || url.trim().isEmpty()) { setStatus("No URL to open."); return; }
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            setStatus("Could not open URL.");
        }
    }

    private String systemLabel(JSONObject stats) {
        JSONObject system = stats == null ? null : stats.optJSONObject("system");
        if (system == null) return "ComfyUI";
        String os = system.optString("os", "");
        return os.trim().isEmpty() ? "ComfyUI" : os;
    }

    private String friendlyError(Exception e) {
        String message = e == null ? "unknown error" : e.getMessage();
        if (message == null || message.trim().isEmpty()) message = e == null ? "unknown error" : e.getClass().getSimpleName();
        String lower = message.toLowerCase(Locale.US);
        if (lower.contains("unexpected end of stream") || lower.contains("stream was reset")) return shortText(message, 180) + " · the tunnel closed the HTTP stream";
        if (lower.contains("401") || lower.contains("403")) return shortText(message, 180) + " · check Cloudflare Access service-token fields";
        if (lower.contains("timeout") || lower.contains("failed to connect")) return shortText(message, 180) + " · check the server URL and tunnel";
        return shortText(message.replace('\n', ' '), 220);
    }

    private String cacheAgeText(long time) {
        if (time <= 0) return " · not refreshed";
        long minutes = Math.max(0, (System.currentTimeMillis() - time) / 60000);
        if (minutes < 1) return " · just now";
        if (minutes < 60) return " · " + minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return " · " + hours + "h ago";
        return " · " + (hours / 24) + "d ago";
    }

    private String mimeFor(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.US);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        return "application/octet-stream";
    }

    private String enc(String value) {
        try { return URLEncoder.encode(value == null ? "" : value, "UTF-8"); }
        catch (Exception e) { return ""; }
    }

    private String safeName(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String shortText(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "…";
    }

    private void styleSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
