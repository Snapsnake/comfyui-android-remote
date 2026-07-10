package com.snapsnake.comfyremote.core;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

public class InputImageUploadTest {
    @Test public void addsJpegExtensionWhenAndroidDocumentIdHasNone() {
        byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};
        assertEquals("1000029273.jpg", InputImageUpload.normalizeFilename("1000029273", jpeg));
        assertEquals("image/jpeg", InputImageUpload.mediaType(jpeg, "1000029273.jpg"));
    }

    @Test public void keepsAValidExistingExtension() {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        assertEquals("image.png", InputImageUpload.normalizeFilename("primary:image.png", png));
    }

    @Test public void replacesGenericBinExtensionFromMagicBytes() {
        byte[] webp = new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
        assertEquals("photo.webp", InputImageUpload.normalizeFilename("photo.bin", webp));
        assertEquals("image/webp", InputImageUpload.mediaType(webp, "photo.webp"));
    }

    @Test public void usesExactServerNameAndSubfolderForWorkflow() throws Exception {
        JSONObject response = new JSONObject()
                .put("name", "photo (1).jpg")
                .put("subfolder", "phone/uploads")
                .put("type", "input");
        InputImageUpload.Ref ref = InputImageUpload.fromUploadResponse(response, "fallback.jpg");
        assertEquals("photo (1).jpg", ref.filename);
        assertEquals("phone/uploads", ref.subfolder);
        assertEquals("phone/uploads/photo (1).jpg", ref.workflowValue());

        InputImageUpload.Ref parsed = InputImageUpload.parseWorkflowValue(ref.workflowValue());
        assertEquals(ref.filename, parsed.filename);
        assertEquals(ref.subfolder, parsed.subfolder);
    }
}
