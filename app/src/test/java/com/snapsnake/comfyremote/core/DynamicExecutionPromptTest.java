package com.snapsnake.comfyremote.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class DynamicExecutionPromptTest {
    @Test public void keepsOfficialFlatDynamicPromptFormat() throws Exception {
        JSONObject editableInputs = new JSONObject()
                .put("input", new JSONArray().put("269").put(0))
                .put("resize_type", "scale dimensions")
                .put("resize_type.width", new JSONArray().put("320_312").put(0))
                .put("resize_type.height", new JSONArray().put("320_299").put(0))
                .put("resize_type.crop", "center")
                .put("scale_method", "lanczos");
        JSONObject editable = prompt(editableInputs);

        JSONObject execution = DynamicExecutionPrompt.pack(editable, objectInfo());
        JSONObject sent = execution.getJSONObject("320_290").getJSONObject("inputs");

        assertEquals("scale dimensions", sent.getString("resize_type"));
        assertEquals("320_312", sent.getJSONArray("resize_type.width").getString(0));
        assertEquals("320_299", sent.getJSONArray("resize_type.height").getString(0));
        assertEquals("center", sent.getString("resize_type.crop"));
        assertEquals("lanczos", sent.getString("scale_method"));
        assertFalse(sent.opt("resize_type") instanceof JSONObject);

        JSONObject original = editable.getJSONObject("320_290").getJSONObject("inputs");
        assertEquals("scale dimensions", original.getString("resize_type"));
        assertTrue(original.has("resize_type.width"));
    }

    @Test public void repairsShiftedSelectorFromOmittedFrontendSlot() throws Exception {
        JSONObject inputs = new JSONObject()
                .put("input", new JSONArray().put("269").put(0))
                // Older conversion incorrectly consumed the width widget value here.
                .put("resize_type", 1088)
                .put("resize_type.width", new JSONArray().put("320_312").put(0))
                .put("resize_type.height", new JSONArray().put("320_299").put(0))
                .put("resize_type.crop", "center")
                .put("scale_method", "lanczos");

        JSONObject execution = DynamicExecutionPrompt.pack(prompt(inputs), objectInfo());
        JSONObject sent = execution.getJSONObject("320_290").getJSONObject("inputs");

        assertEquals("scale dimensions", sent.getString("resize_type"));
        assertTrue(sent.has("resize_type.width"));
        assertTrue(sent.has("resize_type.height"));
        assertTrue(sent.has("resize_type.crop"));
    }

    @Test public void removesChildrenFromUnselectedDynamicOption() throws Exception {
        JSONObject inputs = new JSONObject()
                .put("input", new JSONArray().put("269").put(0))
                .put("resize_type", "scale width")
                .put("resize_type.width", 768)
                .put("resize_type.height", 999)
                .put("resize_type.crop", "center")
                .put("scale_method", "area");

        JSONObject execution = DynamicExecutionPrompt.pack(prompt(inputs), objectInfo());
        JSONObject sent = execution.getJSONObject("320_290").getJSONObject("inputs");

        assertEquals("scale width", sent.getString("resize_type"));
        assertEquals(768, sent.getInt("resize_type.width"));
        assertFalse(sent.has("resize_type.height"));
        assertFalse(sent.has("resize_type.crop"));
    }

    @Test public void convertsPreviouslyNestedPromptBackToOfficialFlatFormat() throws Exception {
        JSONObject nested = new JSONObject()
                .put("resize_type", "scale dimensions")
                .put("width", 1920)
                .put("height", 1088)
                .put("crop", "center");
        JSONObject inputs = new JSONObject()
                .put("input", new JSONArray().put("269").put(0))
                .put("resize_type", nested)
                .put("scale_method", "lanczos");

        JSONObject execution = DynamicExecutionPrompt.pack(prompt(inputs), objectInfo());
        JSONObject sent = execution.getJSONObject("320_290").getJSONObject("inputs");

        assertEquals("scale dimensions", sent.getString("resize_type"));
        assertEquals(1920, sent.getInt("resize_type.width"));
        assertEquals(1088, sent.getInt("resize_type.height"));
        assertEquals("center", sent.getString("resize_type.crop"));
    }

    private static JSONObject prompt(JSONObject inputs) throws Exception {
        return new JSONObject().put("320_290", new JSONObject()
                .put("class_type", "ResizeImageMaskNode")
                .put("inputs", inputs));
    }

    private static JSONObject objectInfo() throws Exception {
        JSONObject dimensions = option("scale dimensions", new JSONObject()
                .put("width", new JSONArray().put("INT").put(new JSONObject().put("default", 512)))
                .put("height", new JSONArray().put("INT").put(new JSONObject().put("default", 512)))
                .put("crop", new JSONArray().put(new JSONArray().put("disabled").put("center"))
                        .put(new JSONObject().put("default", "center"))));
        JSONObject scaleWidth = option("scale width", new JSONObject()
                .put("width", new JSONArray().put("INT").put(new JSONObject().put("default", 512))));

        JSONArray options = new JSONArray().put(dimensions).put(scaleWidth);
        JSONObject required = new JSONObject()
                .put("input", new JSONArray().put("IMAGE,MASK"))
                .put("resize_type", new JSONArray()
                        .put("COMFY_DYNAMICCOMBO_V3")
                        .put(new JSONObject().put("options", options)))
                .put("scale_method", new JSONArray()
                        .put(new JSONArray().put("nearest-exact").put("bilinear").put("area").put("bicubic").put("lanczos"))
                        .put(new JSONObject().put("default", "area")));

        return new JSONObject().put("ResizeImageMaskNode", new JSONObject()
                .put("input", new JSONObject().put("required", required)));
    }

    private static JSONObject option(String key, JSONObject required) throws Exception {
        return new JSONObject()
                .put("key", key)
                .put("inputs", new JSONObject().put("required", required));
    }
}
