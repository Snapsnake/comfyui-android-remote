package com.snapsnake.comfyremote.ui;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.snapsnake.comfyremote.core.NodeSchemaRegistry;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;

public final class DynamicFieldRenderer {
    public interface ChangeListener { void onChanged(); }
    public interface FileRequestListener { void onFileRequested(NodeSchemaRegistry.FieldSpec field); }
    public interface ConnectionListener { void onOpenSource(NodeSchemaRegistry.FieldSpec field); }

    private final Context context;

    public DynamicFieldRenderer(Context context) {
        this.context = context;
    }

    public View render(NodeSchemaRegistry.FieldSpec field, JSONObject node, ChangeListener changes,
                       FileRequestListener files) {
        return render(field, node, changes, files, null);
    }

    public View render(NodeSchemaRegistry.FieldSpec field, JSONObject node, ChangeListener changes,
                       FileRequestListener files, ConnectionListener connections) {
        LinearLayout block = UiKit.column(context);
        block.setPadding(0, UiKit.dp(context, 6), 0, UiKit.dp(context, 8));

        LinearLayout heading = UiKit.row(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = UiKit.label(context, field.key);
        heading.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        if (!field.required) heading.addView(UiKit.muted(context, "optional", 11));
        block.addView(heading);

        if (field.connected) {
            block.addView(connectedEditor(field, connections), UiKit.match(context, -2, 6));
            return block;
        }

        switch (field.kind) {
            case BOOLEAN:
                block.addView(booleanEditor(field, node, changes), UiKit.match(context, 48, 6));
                break;
            case COMBO:
                block.addView(comboEditor(field, node, changes), UiKit.match(context, 52, 6));
                break;
            case INTEGER:
            case FLOAT:
                block.addView(numberEditor(field, node, changes), UiKit.match(context, 48, 6));
                break;
            case FILE:
                if (field.options.length() > 0) block.addView(comboEditor(field, node, changes), UiKit.match(context, 52, 6));
                else block.addView(textEditor(field, node, changes), UiKit.match(context, field.multiline ? 116 : 48, 6));
                if (files != null) {
                    block.addView(UiKit.button(context, "Choose file", false, v -> files.onFileRequested(field)), UiKit.match(context, 42, 8));
                }
                break;
            case STRING:
                block.addView(textEditor(field, node, changes), UiKit.match(context, field.multiline ? 116 : 48, 6));
                break;
            case UNKNOWN:
            default:
                TextView socket = UiKit.muted(context, "Connection-only socket", 12);
                socket.setPadding(UiKit.dp(context, 12), UiKit.dp(context, 12), UiKit.dp(context, 12), UiKit.dp(context, 12));
                socket.setBackground(UiKit.background(context, UiKit.SURFACE_2, 14, UiKit.STROKE, 2));
                block.addView(socket, UiKit.match(context, -2, 6));
                break;
        }
        return block;
    }

    private View connectedEditor(NodeSchemaRegistry.FieldSpec field, ConnectionListener connections) {
        LinearLayout card = UiKit.column(context);
        card.setPadding(UiKit.dp(context, 12), UiKit.dp(context, 11), UiKit.dp(context, 12), UiKit.dp(context, 11));
        card.setBackground(UiKit.background(context, UiKit.SURFACE_2, 14, UiKit.STROKE, 2));

        card.addView(UiKit.title(context, "Connected from " + field.connectionSummary(), 13));
        card.addView(UiKit.muted(context,
                "This value comes from another node. The link is preserved; open the source node to edit it.",
                12), UiKit.match(context, -2, 4));

        if (!field.sourceNodeId.isEmpty()) {
            card.addView(UiKit.button(context, "Open source node", false, v -> {
                if (connections != null) {
                    connections.onOpenSource(field);
                    return;
                }
                if (!NodeNavigationBridge.openSourceNode(context, field.nodeId, field.sourceNodeId)) {
                    Toast.makeText(context, "Source node was not found in the current workflow", Toast.LENGTH_SHORT).show();
                }
            }), UiKit.match(context, 40, 8));
        }
        return card;
    }

    private View booleanEditor(NodeSchemaRegistry.FieldSpec field, JSONObject node, ChangeListener changes) {
        Switch toggle = new Switch(context);
        toggle.setText(bool(field.value) ? "Enabled" : "Disabled");
        toggle.setTextColor(UiKit.TEXT);
        toggle.setTextSize(14);
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setPadding(UiKit.dp(context, 12), 0, UiKit.dp(context, 12), 0);
        toggle.setBackground(UiKit.background(context, UiKit.SURFACE_2, 14, UiKit.STROKE, 2));
        toggle.setChecked(bool(field.value));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            button.setText(checked ? "Enabled" : "Disabled");
            put(node, field.key, checked);
            if (changes != null) changes.onChanged();
        });
        return toggle;
    }

    private View comboEditor(NodeSchemaRegistry.FieldSpec field, JSONObject node, ChangeListener changes) {
        Spinner spinner = new Spinner(context, Spinner.MODE_DROPDOWN);
        spinner.setPadding(UiKit.dp(context, 8), 0, UiKit.dp(context, 8), 0);
        spinner.setBackground(UiKit.background(context, UiKit.SURFACE_2, 14, UiKit.STROKE, 2));
        ArrayList<String> values = new ArrayList<>();
        for (int i = 0; i < field.options.length(); i++) values.add(String.valueOf(field.options.opt(i)));
        String current = String.valueOf(field.value == JSONObject.NULL ? "" : field.value);
        if (!current.isEmpty() && !values.contains(current)) values.add(0, current);
        if (values.isEmpty()) values.add(current);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, values) {
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(UiKit.TEXT);
                view.setTextSize(14);
                return view;
            }
            @Override public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(UiKit.TEXT);
                view.setBackgroundColor(UiKit.SURFACE_2);
                view.setPadding(UiKit.dp(context, 14), UiKit.dp(context, 12), UiKit.dp(context, 14), UiKit.dp(context, 12));
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int selected = values.indexOf(current);
        if (selected >= 0) spinner.setSelection(selected, false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean initial = true;
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (initial) { initial = false; return; }
                if (position < 0 || position >= values.size()) return;
                String next = values.get(position);
                if (field.isDynamicSelector()) removePrefixedInputs(node, field.key + ".");
                put(node, field.key, next);
                if (changes != null) changes.onChanged();
                if (field.isDynamicSelector()) spinner.post(() -> NodeNavigationBridge.refreshCurrentNode(context));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        return spinner;
    }

    private View numberEditor(NodeSchemaRegistry.FieldSpec field, JSONObject node, ChangeListener changes) {
        EditText edit = UiKit.input(context, "", true);
        edit.setText(field.value == JSONObject.NULL ? "" : String.valueOf(field.value));
        int flags = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED;
        if (field.kind == NodeSchemaRegistry.Kind.FLOAT) flags |= InputType.TYPE_NUMBER_FLAG_DECIMAL;
        edit.setInputType(flags);
        edit.addTextChangedListener(watcher(value -> {
            Object parsed = parseNumber(value, field.kind);
            if (parsed != null) {
                put(node, field.key, parsed);
                if (changes != null) changes.onChanged();
            }
        }));
        return edit;
    }

    private View textEditor(NodeSchemaRegistry.FieldSpec field, JSONObject node, ChangeListener changes) {
        EditText edit = UiKit.input(context, "", !field.multiline);
        edit.setText(field.value == JSONObject.NULL ? "" : String.valueOf(field.value));
        edit.setGravity(field.multiline ? Gravity.TOP | Gravity.START : Gravity.CENTER_VERTICAL | Gravity.START);
        edit.setInputType(InputType.TYPE_CLASS_TEXT |
                (field.multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0) |
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.addTextChangedListener(watcher(value -> {
            put(node, field.key, value);
            if (changes != null) changes.onChanged();
        }));
        return edit;
    }

    private interface StringConsumer { void accept(String value); }

    private static TextWatcher watcher(StringConsumer consumer) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { consumer.accept(s == null ? "" : s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        };
    }

    private static Object parseNumber(String value, NodeSchemaRegistry.Kind kind) {
        String s = value == null ? "" : value.trim();
        if (s.isEmpty() || s.equals("-") || s.equals(".") || s.equals("-.")) return null;
        try {
            if (kind == NodeSchemaRegistry.Kind.INTEGER) return Long.parseLong(s);
            return Double.parseDouble(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static void removePrefixedInputs(JSONObject node, String prefix) {
        JSONObject inputs = node == null ? null : node.optJSONObject("inputs");
        if (inputs == null || prefix == null || prefix.isEmpty()) return;
        ArrayList<String> remove = new ArrayList<>();
        Iterator<String> keys = inputs.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith(prefix)) remove.add(key);
        }
        for (String key : remove) inputs.remove(key);
    }

    private static void put(JSONObject node, String key, Object value) {
        try {
            JSONObject inputs = node.optJSONObject("inputs");
            if (inputs == null) {
                inputs = new JSONObject();
                node.put("inputs", inputs);
            }
            inputs.put(key, value);
        } catch (Exception ignored) {}
    }
}
