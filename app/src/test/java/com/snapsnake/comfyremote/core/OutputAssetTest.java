package com.snapsnake.comfyremote.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OutputAssetTest {
    @Test public void mp4ReturnedInsideImagesArrayIsStillVideo() {
        OutputAsset asset = new OutputAsset("p", "75", "LTX_output_00001_.mp4", "video", "output", OutputAsset.Kind.IMAGE);
        assertEquals(OutputAsset.Kind.VIDEO, asset.kind);
        assertEquals("video/mp4", asset.mimeType());
    }

    @Test public void viewUrlUsesPrivateNativeDeepLink() {
        ComfyApiClient client = new ComfyApiClient(new ServerProfile("id", "ComfyUI", "https://example.com", "", ""));
        String link = client.viewUrl("result file.mp4", "video", "output");
        assertTrue(link.startsWith("comfyui-mobile-output://open?"));
        assertTrue(link.contains("filename=result+file.mp4"));
        assertTrue(link.contains("subfolder=video"));
    }
}
