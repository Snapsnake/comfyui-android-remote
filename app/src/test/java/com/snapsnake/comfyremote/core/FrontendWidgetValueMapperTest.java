package com.snapsnake.comfyremote.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class FrontendWidgetValueMapperTest {
    @Test public void mapsLinkedConvertedWidgetsWithoutShiftingLaterValues() throws Exception {
        JSONObject corrected = FrontendWidgetValueMapper.correctFreshPrompt(
                originalWorkflow(), brokenPrompt(), objectInfo());

        JSONObject upscale = corrected.getJSONObject("320_288").getJSONObject("inputs");
        JSONObject initial = corrected.getJSONObject("320_296").getJSONObject("inputs");
        JSONObject resize = corrected.getJSONObject("320_290").getJSONObject("inputs");

        assertEquals(1.0, upscale.getDouble("strength"), 0.00001);
        assertEquals(0.7, initial.getDouble("strength"), 0.00001);
        assertTrue(upscale.opt("bypass") instanceof JSONArray);
        assertTrue(initial.opt("bypass") instanceof JSONArray);

        assertEquals("scale dimensions", resize.getString("resize_type"));
        assertEquals("center", resize.getString("resize_type.crop"));
        assertEquals("lanczos", resize.getString("scale_method"));
        assertTrue(resize.opt("resize_type.width") instanceof JSONArray);
        assertTrue(resize.opt("resize_type.height") instanceof JSONArray);
    }

    @Test public void legacySanitizerOnlyRemovesAmbiguousScalarWidgetValues() throws Exception {
        JSONObject sanitized = FrontendWidgetValueMapper.sanitizeLegacyCurrent(
                originalWorkflow(), brokenPrompt(), objectInfo());

        JSONObject upscale = sanitized.getJSONObject("320_288").getJSONObject("inputs");
        JSONObject resize = sanitized.getJSONObject("320_290").getJSONObject("inputs");

        assertFalse(upscale.has("strength"));
        assertTrue(upscale.opt("bypass") instanceof JSONArray);
        assertFalse(resize.has("resize_type"));
        assertFalse(resize.has("resize_type.crop"));
        assertFalse(resize.has("scale_method"));
        assertTrue(resize.opt("resize_type.width") instanceof JSONArray);
        assertTrue(resize.opt("resize_type.height") instanceof JSONArray);
    }

    @Test public void oldMobileBundleIsMigratedFromPreservedOriginalGraph() throws Exception {
        JSONObject bundle = new JSONObject()
                .put("formatVersion", 3)
                .put("kind", "comfyui-mobile-workflow")
                .put("original", originalWorkflow())
                .put("apiPrompt", brokenPrompt())
                .put("sourceName", "LTX test");

        WorkflowDocument document = WorkflowDocument.importRaw(bundle, objectInfo(), "test.json");
        JSONObject prompt = document.apiPrompt();

        assertEquals(1.0, prompt.getJSONObject("320_288").getJSONObject("inputs")
                .getDouble("strength"), 0.00001);
        assertEquals(0.7, prompt.getJSONObject("320_296").getJSONObject("inputs")
                .getDouble("strength"), 0.00001);
        assertEquals("scale dimensions", prompt.getJSONObject("320_290")
                .getJSONObject("inputs").getString("resize_type"));
        assertEquals(2, document.toJson().getInt("widgetMappingVersion"));
    }

    private static JSONObject originalWorkflow() throws Exception {
        JSONObject wrapper = new JSONObject()
                .put("id", 320)
                .put("type", "ltx-subgraph")
                .put("inputs", new JSONArray())
                .put("outputs", new JSONArray())
                .put("widgets_values", new JSONArray());

        JSONArray internalNodes = new JSONArray()
                .put(ltxNode(288, 1.0, 543))
                .put(ltxNode(296, 0.7, 542))
                .put(resizeNode());

        JSONObject subgraph = new JSONObject()
                .put("id", "ltx-subgraph")
                .put("name", "Image to Video (LTX-2.3)")
                .put("nodes", internalNodes)
                .put("links", new JSONArray());

        return new JSONObject()
                .put("nodes", new JSONArray().put(wrapper))
                .put("links", new JSONArray())
                .put("definitions", new JSONObject()
                        .put("subgraphs", new JSONArray().put(subgraph)));
    }

    private static JSONObject ltxNode(int id, double strength, int bypassLink) throws Exception {
        return new JSONObject()
                .put("id", id)
                .put("type", "LTXVImgToVideoInplace")
                .put("inputs", new JSONArray()
                        .put(input("vae", "VAE", 1, false))
                        .put(input("image", "IMAGE", 2, false))
                        .put(input("latent", "LATENT", 3, false))
                        .put(input("bypass", "BOOLEAN", bypassLink, true)))
                .put("outputs", new JSONArray())
                .put("widgets_values", new JSONArray().put(strength).put(false));
    }

    private static JSONObject resizeNode() throws Exception {
        return new JSONObject()
                .put("id", 290)
                .put("type", "ResizeImageMaskNode")
                .put("inputs", new JSONArray()
                        .put(input("input", "IMAGE,MASK", 535, false))
                        .put(input("resize_type.width", "INT", 558, true))
                        .put(input("resize_type.height", "INT", 559, true)))
                .put("outputs", new JSONArray())
                .put("widgets_values", new JSONArray()
                        .put("scale dimensions")
                        .put(1920)
                        .put(1088)
                        .put("center")
                        .put("lanczos"));
    }

    private static JSONObject input(String name, String type, int link, boolean widget) throws Exception {
        JSONObject out = new JSONObject()
                .put("name", name)
                .put("type", type)
                .put("link", link);
        if (widget) out.put("widget", new JSONObject().put("name", name));
        return out;
    }

    private static JSONObject brokenPrompt() throws Exception {
        return new JSONObject()
                .put("320_288", apiNode("LTXVImgToVideoInplace", new JSONObject()
                        .put("vae", link("320_316", 2))
                        .put("image", link("320_289", 0))
                        .put("latent", link("320_287", 0))
                        .put("bypass", link("320_302", 0))
                        .put("strength", false)))
                .put("320_296", apiNode("LTXVImgToVideoInplace", new JSONObject()
                        .put("vae", link("320_316", 2))
                        .put("image", link("320_289", 0))
                        .put("latent", link("320_295", 0))
                        .put("bypass", link("320_302", 0))
                        .put("strength", false)))
                .put("320_290", apiNode("ResizeImageMaskNode", new JSONObject()
                        .put("input", link("269", 0))
                        .put("resize_type.width", link("320_312", 0))
                        .put("resize_type.height", link("320_299", 0))
                        .put("resize_type", 1088)
                        .put("resize_type.crop", "center")
                        .put("scale_method", "lanczos")));
    }

    private static JSONObject apiNode(String type, JSONObject inputs) throws Exception {
        return new JSONObject().put("class_type", type).put("inputs", inputs);
    }

    private static JSONArray link(String node, int slot) {
        return new JSONArray().put(node).put(slot);
    }

    private static JSONObject objectInfo() throws Exception {
        JSONObject ltxRequired = new JSONObject()
                .put("vae", new JSONArray().put("VAE").put(new JSONObject()))
                .put("image", new JSONArray().put("IMAGE").put(new JSONObject()))
                .put("latent", new JSONArray().put("LATENT").put(new JSONObject()))
                .put("strength", new JSONArray().put("FLOAT")
                        .put(new JSONObject().put("default", 1.0)))
                .put("bypass", new JSONArray().put("BOOLEAN")
                        .put(new JSONObject().put("default", false)));
        JSONObject ltx = new JSONObject()
                .put("input", new JSONObject().put("required", ltxRequired))
                .put("input_order", new JSONObject().put("required", new JSONArray()
                        .put("vae").put("image").put("latent").put("strength").put("bypass")));

        JSONObject dimensionsRequired = new JSONObject()
                .put("width", new JSONArray().put("INT").put(new JSONObject().put("default", 512)))
                .put("height", new JSONArray().put("INT").put(new JSONObject().put("default", 512)))
                .put("crop", new JSONArray().put(new JSONArray().put("disabled").put("center"))
                        .put(new JSONObject().put("default", "center")));
        JSONObject dimensions = new JSONObject()
                .put("key", "scale dimensions")
                .put("inputs", new JSONObject().put("required", dimensionsRequired));
        JSONObject resizeRequired = new JSONObject()
                .put("input", new JSONArray().put("IMAGE,MASK").put(new JSONObject()))
                .put("resize_type", new JSONArray().put("COMFY_DYNAMICCOMBO_V3")
                        .put(new JSONObject().put("options", new JSONArray().put(dimensions))))
                .put("scale_method", new JSONArray()
                        .put(new JSONArray().put("nearest-exact").put("bilinear").put("area")
                                .put("bicubic").put("lanczos"))
                        .put(new JSONObject().put("default", "area")));
        JSONObject resize = new JSONObject()
                .put("input", new JSONObject().put("required", resizeRequired))
                .put("input_order", new JSONObject().put("required", new JSONArray()
                        .put("input").put("resize_type").put("scale_method")));

        return new JSONObject()
                .put("LTXVImgToVideoInplace", ltx)
                .put("ResizeImageMaskNode", resize);
    }
}
