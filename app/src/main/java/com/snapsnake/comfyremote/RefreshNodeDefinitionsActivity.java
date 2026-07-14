package com.snapsnake.comfyremote;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.snapsnake.comfyremote.core.ComfyRepository;
import com.snapsnake.comfyremote.core.ComfyStore;
import com.snapsnake.comfyremote.ui.UiKit;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicitly refreshes /api/object_info and restarts the native UI with new model lists. */
public final class RefreshNodeDefinitionsActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ComfyRepository repository;
    private TextView status;
    private LinearLayout actions;
    private boolean busy;
    private boolean refreshed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ComfyStore store = new ComfyStore(this);
        repository = new ComfyRepository(store, store.activeProfile());
        buildUi();
        refresh();
        styleSystemBars();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (refreshed) restartMain();
        else super.onBackPressed();
    }

    private void buildUi() {
        LinearLayout root = UiKit.column(this);
        root.setBackgroundColor(UiKit.BG);
        root.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 18), UiKit.dp(this, 18), UiKit.dp(this, 18));

        root.addView(UiKit.title(this, "Node definitions", 30), UiKit.section(this));
        root.addView(UiKit.muted(this,
                "Reloads /api/object_info from ComfyUI. This updates node schemas, checkpoint names, VAE lists, text encoders and other COMBO values after files are added on the desktop.",
                14), UiKit.section(this));

        LinearLayout card = UiKit.card(this, true);
        status = UiKit.muted(this, "Preparing refresh…", 13);
        status.setMaxLines(6);
        card.addView(status);
        root.addView(card, UiKit.section(this));

        actions = UiKit.column(this);
        root.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        renderActions();
        setContentView(root);
    }

    private void renderActions() {
        actions.removeAllViews();
        if (refreshed) {
            actions.addView(UiKit.button(this, "Apply refreshed lists and return", true,
                    v -> restartMain()), UiKit.match(this, 48, 0));
        } else {
            actions.addView(UiKit.button(this, busy ? "Refreshing…" : "Refresh now", true,
                    v -> refresh()), UiKit.match(this, 48, 0));
            actions.addView(UiKit.button(this, "Cancel", false,
                    v -> finish()), UiKit.match(this, 46, 10));
        }
    }

    private void refresh() {
        if (busy) return;
        busy = true;
        refreshed = false;
        status.setText("Requesting fresh /api/object_info from ComfyUI…");
        renderActions();
        io.execute(() -> {
            try {
                JSONObject latest = repository.refreshNodeDefinitions();
                runOnUiThread(() -> {
                    busy = false;
                    refreshed = true;
                    status.setText("Updated " + latest.length()
                            + " node definitions. Cached model and COMBO lists were replaced with the server response.");
                    renderActions();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    busy = false;
                    refreshed = false;
                    status.setText("Refresh failed: " + friendly(e));
                    renderActions();
                });
            }
        });
    }

    private void restartMain() {
        Intent intent = new Intent(this, NativeComfyActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    private static String friendly(Exception error) {
        String message = error == null ? "unknown error" : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error == null ? "unknown error" : error.getClass().getSimpleName();
        }
        message = message.replace('\n', ' ').trim();
        return message.length() <= 260 ? message : message.substring(0, 259) + "…";
    }

    private void styleSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
