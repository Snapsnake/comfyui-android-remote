package com.snapsnake.comfyremote;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Method;

public class ComfyMobileActivity extends TemplateBrowserActivity {
    private FrameLayout shell;
    private LinearLayout shellContent;
    private String active = "create";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        showShell("create");
    }

    @Override public void onBackPressed() {
        if (shell != null && "run".equals(active)) {
            showShell("create");
            return;
        }
        super.onBackPressed();
    }

    private void showShell(String tab) {
        active = tab == null ? "create" : tab;
        if (shell == null) {
            shell = new FrameLayout(this);
            shell.setClickable(true);
            shell.setFocusable(true);
            shell.setBackgroundColor(bgRoot());
            ((ViewGroup) getWindow().getDecorView()).addView(shell, new ViewGroup.LayoutParams(-1, -1));
        }
        shell.removeAllViews();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(bgRoot());
        shell.addView(page, new FrameLayout.LayoutParams(-1, -1));

        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        shellContent = new LinearLayout(this);
        shellContent.setOrientation(LinearLayout.VERTICAL);
        shellContent.setPadding(dp(22), dp(18), dp(22), dp(18));
        scroll.addView(shellContent, new ScrollView.LayoutParams(-1, -2));

        if ("run".equals(active)) renderRunShell(); else renderCreateShell();
        page.addView(mockBottomNav(), new LinearLayout.LayoutParams(-1, dp(78)));
    }

    private void renderCreateShell() {
        shellContent.addView(statusChip("●  Connected to ComfyUI Remote", "›", () -> callQuiet("toggleTopPanel")));
        shellContent.addView(pageTitle("Create", "Open Graph, load workflow, then Import."));

        LinearLayout card = card(false);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(iconBadge("‹/›"), new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView workflow = title("Workflow", 20);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, -2, 1);
        wlp.setMargins(dp(10), 0, 0, 0);
        titleRow.addView(workflow, wlp);
        card.addView(titleRow);

        LinearLayout row = row();
        row.setPadding(0, dp(12), 0, dp(12));
        row.addView(actionButton("▦  Templates", false, () -> openTemplates()), weight(dp(54)));
        row.addView(actionButton("⇩  Import", true, () -> { hideShell(); callQuiet("showGraph"); }), weight(dp(54)));
        card.addView(row);

        TextView fallback = label("Fallback: paste API workflow JSON");
        card.addView(fallback);
        EditText json = new EditText(this);
        json.setHint("Paste or drop workflow JSON here…");
        json.setHintTextColor(muted());
        json.setTextColor(Color.WHITE);
        json.setTextSize(14);
        json.setGravity(Gravity.TOP | Gravity.LEFT);
        json.setMinLines(6);
        json.setPadding(dp(14), dp(12), dp(14), dp(12));
        json.setBackground(bg(surface2(), 12, stroke(), 1));
        card.addView(json, new LinearLayout.LayoutParams(-1, dp(170)));

        LinearLayout row2 = row();
        row2.setPadding(0, dp(12), 0, 0);
        row2.addView(actionButton("▱  Load JSON", false, () -> { hideShell(); callQuiet("chooseJson"); }), weight(dp(54)));
        row2.addView(actionButton("✓  Apply JSON", true, () -> { hideShell(); callQuiet("applyJson"); }), weight(dp(54)));
        card.addView(row2);
        shellContent.addView(card, cardParams());

        LinearLayout tip = card(true);
        tip.addView(label("💡  Tip"));
        TextView tipText = mutedText("Use Templates to get started quickly or load your own JSON workflow.", 13);
        tip.addView(tipText);
        shellContent.addView(tip, cardParams());
    }

    private void renderRunShell() {
        shellContent.addView(statusChip("●  Idle • Ready to run", "›", () -> callQuiet("toggleTopPanel")));

        LinearLayout loaded = card(false);
        loaded.setOrientation(LinearLayout.HORIZONTAL);
        loaded.setGravity(Gravity.CENTER_VERTICAL);
        ImageView thumb = new ImageView(this);
        thumb.setImageResource(R.drawable.ic_launcher);
        thumb.setBackground(bg(surface2(), 8, stroke(), 1));
        loaded.addView(thumb, new LinearLayout.LayoutParams(dp(72), dp(72)));
        LinearLayout loadedText = new LinearLayout(this);
        loadedText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
        tlp.setMargins(dp(12), 0, dp(8), 0);
        loaded.addView(loadedText, tlp);
        loadedText.addView(title("Workflow loaded", 14));
        loadedText.addView(mutedText("Ready to execute", 12));
        loaded.addView(actionButton("Change", false, () -> openTemplates()), new LinearLayout.LayoutParams(dp(82), dp(42)));
        shellContent.addView(loaded, cardParams());

        ImageView preview = new ImageView(this);
        preview.setImageResource(R.drawable.ic_launcher);
        preview.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        preview.setPadding(dp(42), dp(42), dp(42), dp(42));
        preview.setBackground(bg(surface(), 16, stroke(), 1));
        shellContent.addView(preview, new LinearLayout.LayoutParams(-1, dp(260)));

        LinearLayout validate = card(false);
        validate.setOrientation(LinearLayout.HORIZONTAL);
        validate.setGravity(Gravity.CENTER_VERTICAL);
        validate.addView(iconBadge("✓"), new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout vt = new LinearLayout(this);
        vt.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, -2, 1);
        vlp.setMargins(dp(10), 0, dp(8), 0);
        validate.addView(vt, vlp);
        vt.addView(title("Ready to run", 14));
        vt.addView(mutedText("All nodes validated • No issues found", 12));
        validate.addView(actionButton("Validate", true, () -> {}), new LinearLayout.LayoutParams(dp(92), dp(42)));
        shellContent.addView(validate, cardParams());

        LinearLayout metrics = row();
        metrics.addView(metric("Nodes", "128"), weight(dp(58)));
        metrics.addView(metric("Models", "6"), weight(dp(58)));
        metrics.addView(metric("Steps", "30"), weight(dp(58)));
        metrics.addView(metric("Est. Time", "02:15"), weight(dp(58)));
        shellContent.addView(metrics, cardParams());

        LinearLayout actions = row();
        actions.addView(actionButton("Queue Prompt", false, () -> {}), weight(dp(54)));
        actions.addView(actionButton("Run  ▷", true, () -> { callQuiet("runWorkflow"); }), weight(dp(54)));
        shellContent.addView(actions, cardParams());

        TextView recent = title("Recent Output", 16);
        shellContent.addView(recent);
    }

    private void openTemplates() {
        try {
            Method m = TemplateBrowserActivity.class.getDeclaredMethod("showTemplates");
            m.setAccessible(true);
            m.invoke(this);
        } catch (Exception e) {
            hideShell();
            callQuiet("showGraph");
        }
    }

    private void hideShell() {
        if (shell != null) shell.setVisibility(View.GONE);
    }

    private View mockBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(14), dp(8), dp(14), dp(8));
        nav.setBackgroundColor(surface());
        nav.addView(navItem("⊞", "Create", "create".equals(active), () -> showShell("create")), weight(dp(62)));
        nav.addView(navItem("⌘", "Nodes", false, () -> { hideShell(); callQuiet("showNodes"); }), weight(dp(62)));
        nav.addView(navItem("▦", "Templates", false, () -> openTemplates()), weight(dp(62)));
        nav.addView(navItem("▷", "Run", "run".equals(active), () -> showShell("run")), weight(dp(62)));
        nav.addView(navItem("▧", "Output", false, () -> { hideShell(); callQuiet("openOutput"); }), weight(dp(62)));
        return nav;
    }

    private View navItem(String icon, String label, boolean selected, Runnable action) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, dp(4), 0, dp(4));
        box.setBackground(selected ? bg(Color.rgb(37, 31, 22), 12, accent(), 1) : bg(Color.TRANSPARENT, 12, Color.TRANSPARENT, 0));
        TextView i = text(icon, 20, selected ? accent() : muted());
        i.setGravity(Gravity.CENTER);
        box.addView(i, new LinearLayout.LayoutParams(-1, dp(24)));
        TextView l = text(label, 10, selected ? accent() : muted());
        l.setGravity(Gravity.CENTER);
        l.setSingleLine(true);
        box.addView(l, new LinearLayout.LayoutParams(-1, dp(20)));
        box.setOnClickListener(v -> action.run());
        return box;
    }

    private LinearLayout pageTitle(String title, String subtitle) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(12), 0, dp(18));
        TextView h = title(title, 28);
        wrap.addView(h);
        wrap.addView(mutedText(subtitle, 15));
        return wrap;
    }

    private LinearLayout statusChip(String left, String right, Runnable action) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(12), 0, dp(12), 0);
        chip.setBackground(bg(surface(), 12, stroke(), 1));
        chip.addView(text(left, 12, muted()), new LinearLayout.LayoutParams(0, -1, 1));
        TextView arrow = text(right, 20, muted());
        arrow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        chip.addView(arrow, new LinearLayout.LayoutParams(dp(24), -1));
        chip.setOnClickListener(v -> action.run());
        return chip;
    }

    private LinearLayout card(boolean accentBorder) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        c.setBackground(bg(surface(), 16, accentBorder ? accent() : stroke(), 1));
        return c;
    }

    private View metric(String label, String value) {
        LinearLayout box = card(false);
        box.setGravity(Gravity.CENTER);
        TextView l = mutedText(label, 11);
        l.setGravity(Gravity.CENTER);
        TextView v = title(value, 14);
        v.setGravity(Gravity.CENTER);
        box.addView(l);
        box.addView(v);
        return box;
    }

    private TextView iconBadge(String s) {
        TextView badge = text(s, 14, accent());
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(bg(Color.rgb(37, 31, 22), 12, stroke(), 1));
        return badge;
    }

    private Button actionButton(String label, boolean primary, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setTextSize(13);
        b.setTextColor(primary ? accent() : Color.WHITE);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(bg(primary ? Color.rgb(44, 35, 25) : surface2(), 12, primary ? accent() : stroke(), 1));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private LinearLayout.LayoutParams weight(int h) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, h, 1);
        p.setMargins(dp(4), 0, dp(4), 0);
        return p;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(14));
        return p;
    }

    private TextView title(String s, int sp) {
        TextView t = text(s, sp, Color.WHITE);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 13, Color.rgb(210, 210, 216));
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private TextView mutedText(String s, int sp) { return text(s, sp, muted()); }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setPadding(dp(2), 0, dp(2), dp(4));
        return t;
    }

    private void callQuiet(String method) {
        try {
            Method m = PolishedNodeActivity.class.getDeclaredMethod(method);
            m.setAccessible(true);
            m.invoke(this);
        } catch (Exception ignored) {}
    }

    private GradientDrawable bg(int color, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private int bgRoot() { return Color.rgb(18, 18, 19); }
    private int surface() { return Color.rgb(28, 28, 30); }
    private int surface2() { return Color.rgb(33, 33, 36); }
    private int stroke() { return Color.rgb(48, 48, 52); }
    private int muted() { return Color.rgb(170, 170, 178); }
    private int accent() { return Color.rgb(218, 143, 60); }
}
