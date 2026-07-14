package com.snapsnake.comfyremote.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public class WorkflowPreflightTest {
    @Test public void catchesShiftedStrengthAndDynamicSelectorTypes() throws Exception {
        JSONObject prompt = new JSONObject()
                .put("288", node("LTXVImgToVideoInplace", new JSONObject()
                        .put("strength", false)
                        .put("bypass", new JSONArray().put("302").put(0))))
                .put("290", node("ResizeImageMaskNode", new JSONObject()
                        .put("resize_type", 1088)
                        .put("scale_method", "lanczos")));
        WorkflowDocument document = new WorkflowDocument(new JSONObject(), prompt, "test", 1);
        NodeSchemaRegistry registry = new NodeSchemaRegistry(objectInfo());

        List<WorkflowPreflight.Issue> issues = WorkflowPreflight.validate(document, registry);
        assertEquals(2, issues.size());
        assertEquals(2, WorkflowPreflight.blockingIssues(issues).size());
        assertTrue(issues.get(0).summary().contains("strength") || issues.get(1).summary().contains("strength"));
        assertTrue(issues.get(0).summary().contains("resize_type") || issues.get(1).summary().contains("resize_type"));
    }

    @Test public void staleCheckpointListsProduceWarningsNotBlockers() throws Exception {
        JSONObject prompt = new JSONObject()
                .put("1", node("CheckpointLoaderSimple", new JSONObject()
                        .put("ckpt_name", "newly_added_model.safetensors")))
                .put("2", node("LTXVAudioVAELoader", new JSONObject()
                        .put("ckpt_name", "new_audio_vae.safetensors")))
                .put("3", node("LTXAVTextEncoderLoader", new JSONObject()
                        .put("ckpt_name", "new_text_encoder.safetensors")));
        WorkflowDocument document = new WorkflowDocument(new JSONObject(), prompt, "models", 1);

        List<WorkflowPreflight.Issue> issues = WorkflowPreflight.validate(
                document, new NodeSchemaRegistry(objectInfo()));

        assertEquals(3, issues.size());
        assertTrue(WorkflowPreflight.blockingIssues(issues).isEmpty());
        assertEquals(3, WorkflowPreflight.warnings(issues).size());
        assertTrue(WorkflowPreflight.hasUnknownComboWarning(issues));
        for (WorkflowPreflight.Issue issue : issues) {
            assertFalse(issue.blocking);
            assertEquals(WorkflowPreflight.CODE_UNKNOWN_COMBO, issue.code);
        }
    }

    @Test public void refreshedCheckpointValueIsAcceptedWithoutWarning() throws Exception {
        JSONObject prompt = new JSONObject().put("1", node("CheckpointLoaderSimple", new JSONObject()
                .put("ckpt_name", "newly_added_model.safetensors")));
        JSONObject schema = objectInfo();
        schema.getJSONObject("CheckpointLoaderSimple")
                .getJSONObject("input")
                .getJSONObject("required")
                .put("ckpt_name", combo("old.safetensors", "newly_added_model.safetensors"));

        List<WorkflowPreflight.Issue> issues = WorkflowPreflight.validate(
                new WorkflowDocument(new JSONObject(), prompt, "models", 1),
                new NodeSchemaRegistry(schema));
        assertTrue(issues.isEmpty());
    }

    @Test public void linkedFieldsAreNotRejectedAsWrongPrimitiveType() throws Exception {
        JSONObject prompt = new JSONObject().put("288", node("LTXVImgToVideoInplace", new JSONObject()
                .put("strength", 0.7)
                .put("bypass", new JSONArray().put("302").put(0))));
        WorkflowDocument document = new WorkflowDocument(new JSONObject(), prompt, "test", 1);
        assertTrue(WorkflowPreflight.validate(document, new NodeSchemaRegistry(objectInfo())).isEmpty());
    }

    private static JSONObject node(String type, JSONObject inputs) throws Exception {
        return new JSONObject().put("class_type", type).put("inputs", inputs);
    }

    private static JSONArray combo(String... values) {
        JSONArray options = new JSONArray();
        for (String value : values) options.put(value);
        return new JSONArray().put(options).put(new JSONObject());
    }

    private static JSONObject objectInfo() throws Exception {
        JSONObject ltx = new JSONObject().put("input", new JSONObject().put("required", new JSONObject()
                .put("strength", new JSONArray().put("FLOAT").put(new JSONObject().put("default", 1.0)))
                .put("bypass", new JSONArray().put("BOOLEAN").put(new JSONObject().put("default", false)))));

        JSONObject dimensions = new JSONObject()
                .put("key", "scale dimensions")
                .put("inputs", new JSONObject().put("required", new JSONObject()
                        .put("width", new JSONArray().put("INT").put(new JSONObject().put("default", 512)))
                        .put("height", new JSONArray().put("INT").put(new JSONObject().put("default", 512)))));
        JSONObject resize = new JSONObject().put("input", new JSONObject().put("required", new JSONObject()
                .put("resize_type", new JSONArray().put("COMFY_DYNAMICCOMBO_V3")
                        .put(new JSONObject().put("options", new JSONArray().put(dimensions))))
                .put("scale_method", new JSONArray().put(new JSONArray().put("area").put("lanczos"))
                        .put(new JSONObject().put("default", "area")))));

        JSONObject checkpoint = new JSONObject().put("input", new JSONObject().put("required",
                new JSONObject().put("ckpt_name", combo("old.safetensors"))));
        JSONObject audioVae = new JSONObject().put("input", new JSONObject().put("required",
                new JSONObject().put("ckpt_name", combo("old_audio_vae.safetensors"))));
        JSONObject textEncoder = new JSONObject().put("input", new JSONObject().put("required",
                new JSONObject().put("ckpt_name", combo("old_text_encoder.safetensors"))));

        return new JSONObject()
                .put("LTXVImgToVideoInplace", ltx)
                .put("ResizeImageMaskNode", resize)
                .put("CheckpointLoaderSimple", checkpoint)
                .put("LTXVAudioVAELoader", audioVae)
                .put("LTXAVTextEncoderLoader", textEncoder);
    }
}
