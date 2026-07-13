package com.snapsnake.comfyremote;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.snapsnake.comfyremote.core.ComfyRepository;
import com.snapsnake.comfyremote.core.ComfyStore;
import com.snapsnake.comfyremote.core.WorkflowDocument;
import com.snapsnake.comfyremote.core.WorkflowFileRequirement;
import com.snapsnake.comfyremote.core.WorkflowPreflight;
import com.snapsnake.comfyremote.ui.UiKit;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Checks workflow input files and lets the user upload replacements natively. */
public final class WorkflowFilesActivity extends Activity {
    private static final int REQ_REPLACE_FILE = 2401;

    private enum FileState { CHECKING, PRESENT, MISSING, EMPTY, ERROR }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, FileState> states = new LinkedHashMap<>();
    private final Map<String, String> stateDetails = new LinkedHashMap<>();

    private ComfyRepository repository;
    private LinearLayout listHost;
    private TextView statusText;
    private List<WorkflowFileRequirement> requirements = new ArrayList<>();
    private List<WorkflowPreflight.Issue> schemaIssues = new ArrayList<>();
    private WorkflowFileRequirement pendingRequirement;
    private boolean busy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ComfyStore store = new ComfyStore(this);
        repository = new ComfyRepository(store, store.activeProfile());
        buildUi();
        scan();
        styleSystemBars();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = UiKit.column(this);
        root.setBackgroundColor(UiKit.BG);
        root.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 14), UiKit.dp(this, 18), UiKit.dp(this, 18));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = UiKit.column(this);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        content.addView(UiKit.title(this, "Workflow check", 30), UiKit.section(this));
        content.addView(UiKit.muted(this,
                "Checks native field types and verifies files referenced under ComfyUI input/. Missing files can be uploaded and rebound without editing JSON.",
                14), UiKit.section(this));

        LinearLayout statusCard = UiKit.card(this, true);
        statusText = UiKit.muted(this, "Preparing check…", 13);
        statusCard.addView(statusText);
        content.addView(statusCard, UiKit.section(this));

        LinearLayout actions = UiKit.row(this);
        actions.addView(UiKit.button(this, "Scan again", true, v -> scan()), UiKit.weighted(this, 46));
        actions.addView(UiKit.button(this, "Close", false, v -> finish()), UiKit.weighted(this, 46));
        content.addView(actions, UiKit.section(this));

        listHost = UiKit.column(this);
        content.addView(listHost, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    private void scan() {
        if (busy) return;
        WorkflowDocument workflow = repository.currentWorkflow();
        if (workflow == null || workflow.isEmpty()) {
            requirements = new ArrayList<>();
            schemaIssues = new ArrayList<>();
            statusText.setText("No workflow is loaded.");
            renderResults();
            return;
        }

        if (repository.objectInfo() == null || repository.objectInfo().length() == 0) {
            busy = true;
            statusText.setText("Loading the current ComfyUI node schema…");
            io.execute(() -> {
                try {
                    repository.connectAndRefreshSchema();
                    runOnUiThread(() -> {
                        busy = false;
                        scan();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        busy = false;
                        statusText.setText("Could not load /object_info: " + friendly(e));
                        renderResults();
                    });
                }
            });
            return;
        }

        busy = true;
        requirements = WorkflowFileRequirement.discover(workflow, repository.schemaRegistry());
        schemaIssues = WorkflowPreflight.validate(workflow, repository.schemaRegistry());
        states.clear();
        stateDetails.clear();
        for (WorkflowFileRequirement file : requirements) {
            states.put(file.id(), file.isEmpty() ? FileState.EMPTY : FileState.CHECKING);
        }
        statusText.setText("Checking " + requirements.size() + " workflow file reference(s)…");
        renderResults();

        io.execute(() -> {
            for (WorkflowFileRequirement file : requirements) {
                if (file.isEmpty()) continue;
                try {
                    boolean exists = repository.client().inputFileExists(file.value);
                    states.put(file.id(), exists ? FileState.PRESENT : FileState.MISSING);
                } catch (Exception e) {
                    states.put(file.id(), FileState.ERROR);
                    stateDetails.put(file.id(), friendly(e));
                }
            }
            runOnUiThread(() -> {
                busy = false;
                updateSummary();
                renderResults();
            });
        });
    }

    private void updateSummary() {
        int present = 0, missing = 0, errors = 0;
        for (FileState state : states.values()) {
            if (state == FileState.PRESENT) present++;
            else if (state == FileState.MISSING || state == FileState.EMPTY) missing++;
            else if (state == FileState.ERROR) errors++;
        }
        String text = present + " available · " + missing + " missing"
                + (schemaIssues.isEmpty() ? "" : " · " + schemaIssues.size() + " field issue(s)")
                + (errors == 0 ? "" : " · " + errors + " check error(s)");
        statusText.setText(text);
    }

    private void renderResults() {
        listHost.removeAllViews();
        if (!schemaIssues.isEmpty()) {
            listHost.addView(UiKit.title(this, "Field validation", 19), UiKit.section(this));
            for (WorkflowPreflight.Issue issue : schemaIssues) {
                LinearLayout card = UiKit.card(this, true);
                card.addView(UiKit.title(this, issue.nodeTitle + " · " + issue.key, 15));
                card.addView(UiKit.muted(this, "Node #" + issue.nodeId + " · " + issue.message, 13));
                listHost.addView(card, UiKit.section(this));
            }
        }

        listHost.addView(UiKit.title(this, "Input files", 19), UiKit.section(this));
        if (requirements.isEmpty()) {
            LinearLayout card = UiKit.card(this, false);
            card.addView(UiKit.muted(this,
                    "No uploadable input-file fields were found in the current server schema.", 14));
            listHost.addView(card, UiKit.section(this));
            return;
        }
        for (WorkflowFileRequirement file : requirements) {
            listHost.addView(fileCard(file), UiKit.section(this));
        }
    }

    private View fileCard(WorkflowFileRequirement file) {
        FileState state = states.get(file.id());
        if (state == null) state = FileState.CHECKING;
        boolean problem = state == FileState.MISSING || state == FileState.EMPTY || state == FileState.ERROR;
        LinearLayout card = UiKit.card(this, problem);

        LinearLayout heading = UiKit.row(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView dot = UiKit.centeredIcon(this, stateIcon(state), 17, stateColor(state));
        heading.addView(dot, new LinearLayout.LayoutParams(UiKit.dp(this, 28), UiKit.dp(this, 32)));
        LinearLayout copy = UiKit.column(this);
        copy.addView(UiKit.title(this, file.nodeTitle + " · " + file.key, 15));
        copy.addView(UiKit.muted(this, "#" + file.nodeId + " · " + file.nodeClass, 11));
        heading.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(heading);

        String value = file.value.trim().isEmpty() ? "No file selected" : file.value;
        card.addView(UiKit.muted(this, value, 13), UiKit.match(this, -2, 8));
        String detail = stateDetails.get(file.id());
        card.addView(UiKit.muted(this,
                detail == null || detail.isEmpty() ? stateLabel(state) : stateLabel(state) + " · " + detail,
                12), UiKit.match(this, -2, 5));

        String action = state == FileState.PRESENT ? "Replace file" : "Choose and upload file";
        card.addView(UiKit.button(this, action, problem, v -> chooseReplacement(file)), UiKit.match(this, 44, 10));
        return card;
    }

    private void chooseReplacement(WorkflowFileRequirement requirement) {
        if (busy) return;
        pendingRequirement = requirement;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(requirement.mimeHint);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(Intent.createChooser(intent,
                "Choose file for " + requirement.nodeTitle + " · " + requirement.key),
                REQ_REPLACE_FILE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_REPLACE_FILE || resultCode != RESULT_OK || data == null
                || data.getData() == null || pendingRequirement == null) return;
        uploadReplacement(pendingRequirement, data.getData());
    }

    private void uploadReplacement(WorkflowFileRequirement requirement, Uri uri) {
        if (busy) return;
        busy = true;
        statusText.setText("Uploading file for " + requirement.nodeTitle + "…");
        io.execute(() -> {
            try {
                AndroidDocumentFile document = AndroidDocumentFile.read(this, uri);
                JSONObject response = repository.client().uploadInputFile(
                        document.bytes, document.displayName, document.mimeType, true
                );
                String workflowValue = response.optString("workflow_value",
                        response.optString("name", document.displayName));
                WorkflowDocument workflow = repository.currentWorkflow();
                JSONObject node = workflow.apiPrompt().optJSONObject(requirement.nodeId);
                if (node == null) throw new IllegalStateException("Workflow node no longer exists");
                JSONObject inputs = node.optJSONObject("inputs");
                if (inputs == null) {
                    inputs = new JSONObject();
                    node.put("inputs", inputs);
                }
                inputs.put(requirement.key, workflowValue);
                repository.setCurrentWorkflow(workflow.snapshot());
                runOnUiThread(() -> {
                    busy = false;
                    pendingRequirement = null;
                    statusText.setText("Uploaded and assigned: " + workflowValue);
                    scan();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    busy = false;
                    statusText.setText("Upload failed: " + friendly(e));
                    renderResults();
                });
            }
        });
    }

    private static String stateIcon(FileState state) {
        if (state == FileState.PRESENT) return "●";
        if (state == FileState.CHECKING) return "◌";
        if (state == FileState.ERROR) return "!";
        return "○";
    }

    private static int stateColor(FileState state) {
        if (state == FileState.PRESENT) return UiKit.SUCCESS;
        if (state == FileState.CHECKING) return UiKit.ACCENT;
        if (state == FileState.ERROR) return Color.rgb(235, 95, 82);
        return Color.rgb(238, 164, 72);
    }

    private static String stateLabel(FileState state) {
        if (state == FileState.PRESENT) return "Available on the ComfyUI server";
        if (state == FileState.MISSING) return "Referenced file is missing from ComfyUI input/";
        if (state == FileState.EMPTY) return "This workflow field has no selected file";
        if (state == FileState.ERROR) return "Could not verify this file";
        return "Checking server…";
    }

    private static String friendly(Exception e) {
        String message = e == null ? "unknown error" : e.getMessage();
        if (message == null || message.trim().isEmpty()) message = e == null ? "unknown error" : e.getClass().getSimpleName();
        message = message.replace('\n', ' ');
        return message.length() <= 180 ? message : message.substring(0, 179) + "…";
    }

    private void styleSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
