package com.snapsnake.comfyremote;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.Toast;

import com.snapsnake.comfyremote.core.ComfyStore;
import com.snapsnake.comfyremote.core.WorkflowDocument;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small Storage Access Framework activity used by the native client to export
 * a complete workflow without requesting broad storage permissions.
 */
public final class WorkflowExportActivity extends Activity {
    private static final int REQ_DIRECTORY = 2401;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    public static void launch(Context context) {
        Intent intent = new Intent(context, WorkflowExportActivity.class);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state == null) chooseDirectory();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void chooseDirectory() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQ_DIRECTORY);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_DIRECTORY) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            finish();
            return;
        }

        Uri tree = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { getContentResolver().takePersistableUriPermission(tree, flags); }
        catch (Exception ignored) {}

        io.execute(() -> exportTo(tree));
    }

    private void exportTo(Uri tree) {
        try {
            ComfyStore store = new ComfyStore(this);
            WorkflowDocument workflow = store.currentWorkflow();
            if (workflow == null || workflow.isEmpty()) throw new IllegalStateException("No workflow is loaded");

            String base = safeBaseName(workflow.sourceName());
            JSONObject frontend = workflow.frontendWithCurrentPrompt();
            JSONObject api = workflow.apiPromptCopy();
            JSONObject bundle = workflow.toJson();

            writeJson(tree, base + ".workflow.json", frontend);
            writeJson(tree, base + ".api.json", api);
            writeJson(tree, base + ".comfyui-mobile.json", bundle);

            runOnUiThread(() -> {
                Toast.makeText(this,
                        "Exported workflow, API prompt and mobile bundle to the selected folder",
                        Toast.LENGTH_LONG).show();
                finish();
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                String message = e.getMessage();
                if (message == null || message.trim().isEmpty()) message = e.getClass().getSimpleName();
                Toast.makeText(this, "Export failed: " + message, Toast.LENGTH_LONG).show();
                finish();
            });
        }
    }

    private void writeJson(Uri tree, String name, JSONObject value) throws Exception {
        String treeId = DocumentsContract.getTreeDocumentId(tree);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeId);
        Uri file = DocumentsContract.createDocument(getContentResolver(), parent, "application/json", name);
        if (file == null) throw new IllegalStateException("Could not create " + name);
        byte[] bytes = value.toString(2).getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = getContentResolver().openOutputStream(file, "w")) {
            if (out == null) throw new IllegalStateException("Could not write " + name);
            out.write(bytes);
            out.flush();
        }
    }

    private static String safeBaseName(String sourceName) {
        String value = sourceName == null ? "workflow" : sourceName.trim();
        if (value.isEmpty()) value = "workflow";
        value = value.replaceAll("(?i)\\.(comfyui-mobile|workflow|api)?\\.?(json|png|webp)$", "");
        value = value.replaceAll("[^A-Za-z0-9._-]", "_");
        while (value.contains("__")) value = value.replace("__", "_");
        if (value.isEmpty()) value = "workflow";
        return value.toLowerCase(Locale.US).equals("json") ? "workflow" : value;
    }
}
