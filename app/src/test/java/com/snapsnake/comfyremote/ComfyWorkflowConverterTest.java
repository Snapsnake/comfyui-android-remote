package com.snapsnake.comfyremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.snapsnake.comfyremote.core.NodeSchemaRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public class ComfyWorkflowConverterTest {
    @Test public void convertsDynamicComboWidgetsWithoutShiftingScaleMethod() throws Exception {
        JSONObject prompt = ComfyWorkflowConverter.toApiPrompt(frontendWorkflow(), objectInfo());
        JSONObject inputs = prompt.getJSONObject("290").getJSONObject("inputs");

        assertEquals("scale dimensions", inputs.getString("resize_type"));
        assertEquals(1920, inputs.getInt("resize_type.width"));
        assertEquals(1088, inputs.getInt("resize_type.height"));
        assertEquals("center", inputs.getString("resize_type.crop"));
        assertEquals("lanczos", inputs.getString("scale_method"));
    }

    @Test public void repairsMalformedPromptButPreservesValidUserEdits() throws Exception {
        JSONObject malformed = ComfyWorkflowConverter.toApiPrompt(frontendWorkflow(), objectInfo());
        JSONObject inputs = malformed.getJSONObject("290").getJSONObject("inputs");
        inputs.put("scale_method", "scale dimensions");
        malformed.getJSONObject("401").getJSONObject("inputs").put("text", "edited on phone");

        JSONObject repaired = ComfyWorkflowConverter.repairPrompt(malformed, frontendWorkflow(), objectInfo());
        JSONObject repairedResize = repaired.getJSONObject("290").getJSONObject("inputs");

        assertEquals("lanczos", repairedResize.getString("scale_method"));
        assertEquals("scale dimensions", repairedResize.getString("resize_type"));
        assertEquals(1920, repairedResize.getInt("resize_type.width"));
        assertEquals("edited on phone", repaired.getJSONObject("401").getJSONObject("inputs").getString("text"));
    }

    @Test public void exposesSelectedDynamicFieldsToNativeEditor() throws Exception {
        JSONObject prompt = ComfyWorkflowConverter.toApiPrompt(frontendWorkflow(), objectInfo());
        JSONObject node = prompt.getJSONObject("290");
        NodeSchemaRegistry registry = new NodeSchemaRegistry(objectInfo());
        List<NodeSchemaRegistry.FieldSpec> fields = registry.fieldsForNode("290", node);

        NodeSchemaRegistry.FieldSpec selector = find(fields, "resize_type");
        assertNotNull(selector);
        assertTrue(selector.isDynamicSelector());
        assertEquals(NodeSchemaRegistry.Kind.COMBO, selector.kind);
        assertEquals("scale dimensions", selector.value);
        assertEquals(9, selector.options.length());

        assertEquals(1920, ((Number) find(fields, "resize_type.width").value).intValue());
        assertEquals(1088, ((Number) find(fields, "resize_type.height").value).intValue());
        assertEquals("center", find(fields, "resize_type.crop").value);
        assertEquals("lanczos", find(fields, "scale_method").value);
    }

    private static NodeSchemaRegistry.FieldSpec find(List<NodeSchemaRegistry.FieldSpec> fields, String key) {
        for (NodeSchemaRegistry.FieldSpec field : fields) if (key.equals(field.key)) return field;
        return null;
    }

    private static JSONObject frontendWorkflow() throws Exception {
        JSONObject resize = new JSONObject();
        resize.put("id", 290);
        resize.put("type", "ResizeImageMaskNode");
        resize.put("inputs", new JSONArray()
                .put(input("resize_type", "COMFY_DYNAMICCOMBO_V3"))
                .put(input("resize_type.width", "INT"))
                .put(input("resize_type.height", "INT"))
                .put(input("resize_type.crop", "COMBO"))
                .put(input("scale_method", "COMBO")));
        resize.put("widgets_values", new JSONArray()
                .put("scale dimensions")
                .put(1920)
                .put(1088)
                .put("center")
                .put("lanczos"));

        JSONObject text = new JSONObject();
        text.put("id", 401);
        text.put("type", "PrimitiveStringMultiline");
        text.put("inputs", new JSONArray().put(input("text", "STRING")));
        text.put("widgets_values", new JSONArray().put("original text"));

        JSONObject workflow = new JSONObject();
        workflow.put("nodes", new JSONArray().put(resize).put(text));
        workflow.put("links", new JSONArray());
        return workflow;
    }

    private static JSONObject input(String name, String type) throws Exception {
        JSONObject input = new JSONObject();
        input.put("name", name);
        input.put("type", type);
        input.put("widget", new JSONObject().put("name", name));
        input.put("link", JSONObject.NULL);
        return input;
    }

    private static JSONObject objectInfo() throws Exception {
        JSONObject dimensions = new JSONObject();
        dimensions.put("key", "scale dimensions");
        dimensions.put("inputs", new JSONObject().put("required", new JSONObject()
                .put("width", new JSONArray().put("INT").put(new JSONObject().put("default", 512)))
                .put("height", new JSONArray().put("INT").put(new JSONObject().put("default", 512)))
                .put("crop", new JSONArray().put(new JSONArray().put("disabled").put("center"))
                        .put(new JSONObject().put("default", "center")))));

        JSONArray dynamicOptions = new JSONArray()
                .put(dimensions)
                .put(option("scale by multiplier", "multiplier", "FLOAT", 1.0))
                .put(option("scale longer dimension", "longer_size", "INT", 512))
                .put(option("scale shorter dimension", "shorter_size", "INT", 512))
                .put(option("scale width", "width", "INT", 512))
                .put(option("scale height", "height", "INT", 512))
                .put(option("scale total pixels", "megapixels", "FLOAT", 1.0))
                .put(new JSONObject().put("key", "match size").put("inputs", new JSONObject()))
                .put(option("scale to multiple", "multiple", "INT", 8));

        JSONObject resizeRequired = new JSONObject();
        resizeRequired.put("resize_type", new JSONArray()
                .put("COMFY_DYNAMICCOMBO_V3")
                .put(new JSONObject().put("options", dynamicOptions)));
        resizeRequired.put("scale_method", new JSONArray()
                .put(new JSONArray().put("nearest-exact").put("bilinear").put("area").put("bicubic").put("lanczos"))
                .put(new JSONObject().put("default", "area")));

        JSONObject resizeDefinition = new JSONObject()
                .put("display_name", "Resize Image/Mask")
                .put("input", new JSONObject().put("required", resizeRequired));

        JSONObject textDefinition = new JSONObject()
                .put("input", new JSONObject().put("required", new JSONObject()
                        .put("text", new JSONArray().put("STRING").put(new JSONObject().put("default", "")))));

        return new JSONObject()
                .put("ResizeImageMaskNode", resizeDefinition)
                .put("PrimitiveStringMultiline", textDefinition);
    }

    private static JSONObject option(String key, String field, String type, Object defaultValue) throws Exception {
        return new JSONObject()
                .put("key", key)
                .put("inputs", new JSONObject().put("required", new JSONObject()
                        .put(field, new JSONArray().put(type).put(new JSONObject().put("default", defaultValue)))));
    }
}
