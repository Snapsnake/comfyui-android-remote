package com.snapsnake.comfyremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.webkit.CookieManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

public class EnhancedPolishedActivity extends PolishedNodeActivity {
    private static final String PREFS = "comfyui_remote_prefs";
    private static final String KEY_CF_ID = "cf_access_client_id";
    private static final String KEY_CF_SECRET = "cf_access_client_secret";

    private EditText cfIdInput, cfSecretInput;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        enableWebSessionPersistence();
        addCloudflarePanel();
    }

    private void enableWebSessionPersistence() {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.flush();
        } catch (Exception ignored) {}
    }

    private void addCloudflarePanel() {
        LinearLayout top = (LinearLayout) getPrivate("topPanel");
        if (top == null) return;
        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        TextView label = new TextView(this);
        label.setText("Cloudflare Access optional. Fill only after Access protection is enabled.");
        label.setTextSize(12);
        label.setTextColor(Color.rgb(170, 170, 178));
        label.setPadding(dp(2), dp(6), dp(2), dp(6));
        top.addView(label);
        cfIdInput = smallInput("CF-Access-Client-Id", p.getString(KEY_CF_ID, ""), false);
        top.addView(cfIdInput, new LinearLayout.LayoutParams(-1, dp(42)));
        cfSecretInput = smallInput("CF-Access-Client-Secret", p.getString(KEY_CF_SECRET, ""), true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(42));
        sp.setMargins(0, dp(6), 0, 0);
        top.addView(cfSecretInput, sp);
    }

    private EditText smallInput(String hint, String value, boolean secret) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(value == null ? "" : value);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.rgb(170, 170, 178));
        e.setHint(hint);
        e.setTextSize(13);
        e.setPadding(dp(12), 0, dp(12), 0);
        e.setBackground(bg(Color.rgb(33, 33, 36), 12, Color.rgb(48, 48, 52), 1));
        e.setInputType(secret ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD) : InputType.TYPE_CLASS_TEXT);
        return e;
    }

    private void saveConnectionPrefs() {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (cfIdInput != null) e.putString(KEY_CF_ID, cfIdInput.getText().toString().trim());
        if (cfSecretInput != null) e.putString(KEY_CF_SECRET, cfSecretInput.getText().toString().trim());
        e.apply();
        callQuiet("saveUrl");
        try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
    }

    private Map<String, String> accessHeaders() {
        saveConnectionPrefs();
        Map<String, String> h = new HashMap<>();
        String id = cfIdInput == null ? "" : cfIdInput.getText().toString().trim();
        String sec = cfSecretInput == null ? "" : cfSecretInput.getText().toString().trim();
        if (!id.isEmpty() && !sec.isEmpty()) {
            h.put("CF-Access-Client-Id", id);
            h.put("CF-Access-Client-Secret", sec);
        }
        return h;
    }

    private Object getPrivate(String name) {
        try {
            java.lang.reflect.Field f = PolishedNodeActivity.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(this);
        } catch (Exception e) { return null; }
    }

    private Object callQuiet(String name) {
        try {
            java.lang.reflect.Method m = PolishedNodeActivity.class.getDeclaredMethod(name);
            m.setAccessible(true);
            return m.invoke(this);
        } catch (Exception e) { return null; }
    }

    private GradientDrawable bg(int color, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
