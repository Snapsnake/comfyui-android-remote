package com.snapsnake.comfyremote.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.snapsnake.comfyremote.RefreshNodeDefinitionsActivity;
import com.snapsnake.comfyremote.WorkflowExportActivity;
import com.snapsnake.comfyremote.WorkflowFilesActivity;

public final class UiKit {
    public static final int BG = Color.rgb(17, 17, 18);
    public static final int SURFACE = Color.rgb(29, 29, 31);
    public static final int SURFACE_2 = Color.rgb(36, 35, 37);
    public static final int STROKE = Color.rgb(68, 66, 68);
    public static final int TEXT = Color.rgb(245, 244, 242);
    public static final int MUTED = Color.rgb(178, 175, 178);
    public static final int ACCENT = Color.rgb(218, 143, 60);
    public static final int SUCCESS = Color.rgb(67, 190, 104);

    private UiKit() {}

    public static int dp(Context c, int value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable background(Context c, int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        d.setStroke(dp(c, strokeDp), strokeColor);
        return d;
    }

    private static RippleDrawable ripple(Context c, GradientDrawable content, int radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(c, radiusDp));
        return new RippleDrawable(
                ColorStateList.valueOf(Color.argb(65, 255, 255, 255)),
                content,
                mask
        );
    }

    public static LinearLayout column(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    public static LinearLayout row(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.HORIZONTAL);
        return v;
    }

    public static LinearLayout card(Context c, boolean accent) {
        LinearLayout v = new WorkflowAwareCard(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14));
        v.setBackground(background(c, SURFACE, 18, accent ? ACCENT : STROKE, 2));
        GradientDrawable mask = background(c, Color.WHITE, 18, Color.TRANSPARENT, 0);
        v.setForeground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(48, 255, 255, 255)), null, mask));
        enablePressAnimation(v);
        return v;
    }

    public static TextView text(Context c, String value, int sp, int color) {
        TextView t = new TextView(c);
        t.setText(value == null ? "" : value);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setFontFeatureSettings("kern");
        t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        t.setPadding(dp(c, 2), 0, dp(c, 2), dp(c, 5));
        return t;
    }

    public static TextView title(Context c, String value, int sp) {
        TextView t = text(c, value, sp, TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setMaxLines(3);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    public static TextView muted(Context c, String value, int sp) {
        return text(c, value, sp, MUTED);
    }

    public static TextView label(Context c, String value) {
        TextView t = text(c, value, 13, Color.rgb(222, 220, 218));
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    public static EditText input(Context c, String hint, boolean singleLine) {
        EditText e = new EditText(c);
        e.setHint(hint == null ? "" : hint);
        e.setSingleLine(singleLine);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setTextSize(14);
        e.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        e.setPadding(dp(c, 14), singleLine ? 0 : dp(c, 12), dp(c, 14), singleLine ? 0 : dp(c, 12));
        e.setBackground(background(c, SURFACE_2, 14, STROKE, 2));
        return e;
    }

    public static Button button(Context c, String label, boolean primary, View.OnClickListener listener) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setTextSize(13);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(primary ? ACCENT : TEXT);
        b.setPadding(dp(c, 7), 0, dp(c, 7), 0);
        GradientDrawable content = background(c,
                primary ? Color.rgb(49, 37, 25) : SURFACE_2,
                14, primary ? ACCENT : STROKE, 2);
        b.setBackground(ripple(c, content, 14));
        enablePressAnimation(b);

        View.OnClickListener wrapped = v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) listener.onClick(v);
        };
        if ("Back".equals(label)) {
            b.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (!NodeNavigationBridge.goBack(c) && listener != null) listener.onClick(v);
            });
        } else {
            b.setOnClickListener(wrapped);
        }
        return b;
    }

    public static void enablePressAnimation(View view) {
        if (view == null) return;
        view.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.975f).scaleY(0.975f).setDuration(70).start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
            }
            return false;
        });
    }

    public static LinearLayout.LayoutParams section(Context c) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(c, 14));
        return p;
    }

    public static LinearLayout.LayoutParams weighted(Context c, int heightDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(c, heightDp), 1f);
        p.setMargins(dp(c, 4), 0, dp(c, 4), 0);
        return p;
    }

    public static LinearLayout.LayoutParams match(Context c, int heightDp, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, heightDp < 0 ? heightDp : dp(c, heightDp));
        p.setMargins(0, dp(c, topDp), 0, 0);
        return p;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(STROKE);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(c, 1)));
        return v;
    }

    public static TextView centeredIcon(Context c, String icon, int sp, int color) {
        TextView t = text(c, icon, sp, color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** Adds workflow and connection actions without coupling the main activity to tool screens. */
    private static final class WorkflowAwareCard extends LinearLayout {
        private boolean workflowToolsAdded;
        private boolean schemaRefreshAdded;

        WorkflowAwareCard(Context context) {
            super(context);
        }

        @Override public void onViewAdded(View child) {
            super.onViewAdded(child);
            if (!(child instanceof Button)) return;
            CharSequence raw = ((Button) child).getText();
            String text = raw == null ? "" : raw.toString();

            if (!schemaRefreshAdded && "Edit connection".equals(text)) {
                schemaRefreshAdded = true;
                Button refresh = UiKit.button(getContext(),
                        "Refresh node definitions / model lists", false,
                        v -> getContext().startActivity(
                                new Intent(getContext(), RefreshNodeDefinitionsActivity.class)));
                addView(refresh, UiKit.match(getContext(), 44, 9));
                return;
            }

            if (workflowToolsAdded || !"Save to local workflow library".equals(text)) return;
            workflowToolsAdded = true;

            Button check = UiKit.button(getContext(), "Check workflow and missing files", false,
                    v -> getContext().startActivity(new Intent(getContext(), WorkflowFilesActivity.class)));
            addView(check, UiKit.match(getContext(), 42, 9));

            Button export = UiKit.button(getContext(), "Export workflow to folder", false,
                    v -> WorkflowExportActivity.launch(getContext()));
            addView(export, UiKit.match(getContext(), 42, 9));
        }
    }
}
