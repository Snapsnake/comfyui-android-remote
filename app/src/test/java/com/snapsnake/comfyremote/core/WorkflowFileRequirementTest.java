package com.snapsnake.comfyremote.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public class WorkflowFileRequirementTest {
    @Test public void discoversImageAndAudioInputsButNotModelCombos() throws Exception {
        JSONObject prompt = new JSONObject()
                .put("10", node("LoadImage", new JSONObject().put("image", "photo.png")))
                .put("11", node("LoadAudio", new JSONObject().put("audio", "voice.wav")))
                .put("12", node("CheckpointLoaderSimple", new JSONObject().put("ckpt_name", "model.safetensors")));
        WorkflowDocument document = new WorkflowDocument(new JSONObject(), prompt, "files", 1);

        List<WorkflowFileRequirement> files = WorkflowFileRequirement.discover(
                document, new NodeSchemaRegistry(objectInfo()));

        assertEquals(2, files.size());
        assertTrue(files.stream().anyMatch(file -> "10:image".equals(file.id()) && "image/*".equals(file.mimeHint)));
        assertTrue(files.stream().anyMatch(file -> "11:audio".equals(file.id()) && "audio/*".equals(file.mimeHint)));
    }

    private static JSONObject node(String type, JSONObject inputs) throws Exception {
        return new JSONObject().put("class_type", type).put("inputs", inputs);
    }

    private static JSONObject objectInfo() throws Exception {
        JSONObject loadImage = new JSONObject().put("input", new JSONObject().put("required", new JSONObject()
                .put("image", new JSONArray()
                        .put(new JSONArray().put("photo.png"))
                        .put(new JSONObject().put("image_upload", true)))));
        JSONObject loadAudio = new JSONObject().put("input", new JSONObject().put("required", new JSONObject()
                .put("audio", new JSONArray()
                        .put(new JSONArray().put("voice.wav"))
                        .put(new JSONObject().put("audio_upload", true)))));
        JSONObject checkpoint = new JSONObject().put("input", new JSONObject().put("required", new JSONObject()
                .put("ckpt_name", new JSONArray().put(new JSONArray().put("model.safetensors"))
                        .put(new JSONObject()))));
        return new JSONObject()
                .put("LoadImage", loadImage)
                .put("LoadAudio", loadAudio)
                .put("CheckpointLoaderSimple", checkpoint);
    }
}
