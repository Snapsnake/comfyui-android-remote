package com.snapsnake.comfyremote.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class DynamicExecutionPromptTest {
    @Test public void resizeImageMaskReceivesOneNestedResizeTypeArgument() throws Exception {
        JSONObject editableInputs = new JSONObject()
                .put("input", new JSONArray().put("269").put(0))
                .put("resize_type", "scale dimensions")
                .put("resize_type.width", new JSONArray().put("320_312").put(0))
                .put("resize_type.height", new JSONArray().put("320_299").put(0))
                .put("resize_type.crop", "center")
                .put("scale_method", "lanczos");
        JSONObject editable = prompt(editableInputs);

        JSONObject execution = DynamicExecutionPrompt.pack(editable, objectInfo());
        JSONObject sentInputs = execution.getJSONObject("320_290").getJSONObject("inputs");
        JSONObject resizeType = sentInputs.getJSONObject("resize_type");

        assertEquals("scale dimensions", resizeType.getString("resize_type"));
        assertEquals("320_312", resizeType.getJSONArray("width").getString(0));
        assertEquals(0, resizeType.getJSONArray("width").getInt(1));
        assertEquals("320_299", resizeType.getJSONArray("height").getString(0));
        assertEquals("center", resizeType.getString("crop"));
        assertEquals("lanczos", sentInputs.getString("scale_method"));

        assertFalse(sentInputs.has("resize_type.width"));
        assertFalse(sentInputs.has("resize_type.height"));
        assertFalse(sentInputs.has("resize_type.crop"));

        // Packing is send-only and must not damage the editable workflow.
        JSONObject originalInputs = editable.getJSONObject("320_290").getJSONObject("inputs");
        assertTrue(originalInputs.has("resize_type.width"));
        assertEquals("scale dimensions", originalInputs.getString("resize_type"));
    }

    @Test public void onlySelectedDynamicOptionIsSent() throws Exception {
        JSONObject inputs = new JSONObject()
                .put("input", new JSONArray().put("269").put(0))
                .put("resize_type", "scale width")
                .put("resize_type.width", 768)
                .put("resize_type.height", 999)
                .put("resize_type.crop", "center")
                .put("scale_method", "area");

        JSONObject execution = DynamicExecutionPrompt.pack(prompt(inputs), objectInfo());
        JSONObject resizeType = execution.getJSONObject("320_290")
                .getJSONObject("inputs").getJSONObject("resize_type");

        assertEquals("scale width", resizeType.getString("resize_type"));
        assertEquals(768, resizeType.getInt("width"));
        assertFalse(resizeType.has("height"));
        assertFalse(resizeType.has("crop"));
    }

    @Test public void alreadyNestedApiPromptRemainsValid() throws Exception {
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
        JSONObject packed = execution.getJSONObject("320_290")
                .getJSONObject("inputs").getJSONObject("resize_type");
        assertEquals(1920, packed.getInt("width"));
        assertEquals(1088, packed.getInt("height"));
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
