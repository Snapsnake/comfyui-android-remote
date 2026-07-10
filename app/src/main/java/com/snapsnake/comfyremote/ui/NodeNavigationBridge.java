package com.snapsnake.comfyremote.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;

import com.snapsnake.comfyremote.core.ComfyRepository;
import com.snapsnake.comfyremote.core.WorkflowDocument;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Keeps connected-input navigation independent from individual node classes.
 *
 * The current native activity predates a public navigation interface, so this
 * bridge talks to its existing selectedNodeId/render state without mutating
 * workflow links. It can be replaced by a direct interface later without
 * changing field rendering or connection semantics.
 */
public final class NodeNavigationBridge {
    private static final class Entry {
        final String from;
        final String to;
        Entry(String from, String to) {
            this.from = from;
            this.to = to;
        }
    }

    private static final Map<Activity, ArrayDeque<Entry>> HISTORY = new WeakHashMap<>();

    private NodeNavigationBridge() {}

    public static boolean openSourceNode(Context context, String currentNodeId, String sourceNodeId) {
        Activity activity = activity(context);
        if (activity == null || empty(sourceNodeId) || !nodeExists(activity, sourceNodeId)) return false;
        try {
            persistCurrentEdits(activity);
            String current = empty(currentNodeId) ? selectedNodeId(activity) : currentNodeId;
            synchronized (HISTORY) {
                HISTORY.computeIfAbsent(activity, key -> new ArrayDeque<>())
                        .push(new Entry(current, sourceNodeId));
            }
            setSelectedNodeId(activity, sourceNodeId);
            invokeRender(activity);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns true when an upstream-navigation step was consumed. */
    public static boolean goBack(Context context) {
        Activity activity = activity(context);
        if (activity == null) return false;
        try {
            String current = selectedNodeId(activity);
            Entry entry;
            synchronized (HISTORY) {
                ArrayDeque<Entry> stack = HISTORY.get(activity);
                if (stack == null || stack.isEmpty()) return false;
                entry = stack.peek();
                if (!entry.to.equals(current)) {
                    stack.clear();
                    return false;
                }
                stack.pop();
            }
            if (empty(entry.from) || !nodeExists(activity, entry.from)) return false;
            persistCurrentEdits(activity);
            setSelectedNodeId(activity, entry.from);
            invokeRender(activity);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void clear(Context context) {
        Activity activity = activity(context);
        if (activity == null) return;
        synchronized (HISTORY) {
            HISTORY.remove(activity);
        }
    }

    private static boolean nodeExists(Activity activity, String nodeId) {
        try {
            Field field = findField(activity.getClass(), "repository");
            field.setAccessible(true);
            Object value = field.get(activity);
            if (!(value instanceof ComfyRepository)) return false;
            WorkflowDocument workflow = ((ComfyRepository) value).currentWorkflow();
            return workflow != null && workflow.apiPrompt().optJSONObject(nodeId) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static String selectedNodeId(Activity activity) throws Exception {
        Field field = findField(activity.getClass(), "selectedNodeId");
        field.setAccessible(true);
        Object value = field.get(activity);
        return value == null ? "" : String.valueOf(value);
    }

    private static void setSelectedNodeId(Activity activity, String nodeId) throws Exception {
        Field field = findField(activity.getClass(), "selectedNodeId");
        field.setAccessible(true);
        field.set(activity, nodeId);
    }

    private static void persistCurrentEdits(Activity activity) {
        try {
            Method method = findMethod(activity.getClass(), "saveWorkflowIfDirty");
            method.setAccessible(true);
            method.invoke(activity);
        } catch (Exception ignored) {}
    }

    private static void invokeRender(Activity activity) throws Exception {
        Method method = findMethod(activity.getClass(), "render");
        method.setAccessible(true);
        method.invoke(activity);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cursor = cursor.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    private static Method findMethod(Class<?> type, String name) throws NoSuchMethodException {
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredMethod(name); }
            catch (NoSuchMethodException ignored) { cursor = cursor.getSuperclass(); }
        }
        throw new NoSuchMethodException(name);
    }

    private static Activity activity(Context context) {
        Context cursor = context;
        while (cursor instanceof ContextWrapper) {
            if (cursor instanceof Activity) return (Activity) cursor;
            Context base = ((ContextWrapper) cursor).getBaseContext();
            if (base == cursor) break;
            cursor = base;
        }
        return cursor instanceof Activity ? (Activity) cursor : null;
    }

    private static boolean empty(String value) {
        return value == null || value.trim().isEmpty();
    }
}
