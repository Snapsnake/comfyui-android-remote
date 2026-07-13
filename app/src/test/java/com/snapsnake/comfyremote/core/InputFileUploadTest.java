package com.snapsnake.comfyremote.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class InputFileUploadTest {
    @Test public void addsAudioExtensionFromAndroidMimeType() {
        assertEquals("100009231.wav",
                InputFileUpload.normalizeFilename("100009231", new byte[]{1, 2, 3}, "audio/wav"));
        assertEquals("audio/wav",
                InputFileUpload.mediaType(new byte[]{1, 2, 3}, "100009231.wav", "audio/wav"));
    }

    @Test public void keepsOriginalVideoFilenameAndMime() {
        assertEquals("clip 01.mp4",
                InputFileUpload.normalizeFilename("primary:clip 01.mp4", new byte[]{1}, "video/mp4"));
        assertEquals("video/mp4",
                InputFileUpload.mediaType(new byte[]{1}, "clip 01.mp4", "application/octet-stream"));
    }

    @Test public void detectsImageWhenProviderHasNoUsefulMime() {
        byte[] jpeg = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0};
        assertEquals("photo.jpg",
                InputFileUpload.normalizeFilename("photo", jpeg, "application/octet-stream"));
        assertEquals("image/jpeg",
                InputFileUpload.mediaType(jpeg, "photo.jpg", "application/octet-stream"));
    }
}
