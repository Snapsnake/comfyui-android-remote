package com.snapsnake.comfyremote;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.snapsnake.comfyremote.core.InputFileUpload;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Reads SAF documents without relying on opaque numeric Uri path segments. */
public final class AndroidDocumentFile {
    public final String displayName;
    public final String mimeType;
    public final long sizeBytes;
    public final byte[] bytes;

    private AndroidDocumentFile(String displayName, String mimeType, long sizeBytes, byte[] bytes) {
        this.displayName = displayName == null ? "" : displayName;
        this.mimeType = mimeType == null ? "application/octet-stream" : mimeType;
        this.sizeBytes = sizeBytes;
        this.bytes = bytes == null ? new byte[0] : bytes;
    }

    public static AndroidDocumentFile read(Context context, Uri uri) throws Exception {
        if (context == null || uri == null) throw new IllegalArgumentException("File Uri is missing");
        ContentResolver resolver = context.getContentResolver();
        String name = "";
        long size = -1;
        try (Cursor cursor = resolver.query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0) name = cursor.getString(nameIndex);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex);
            }
        } catch (Exception ignored) {}

        String mime = resolver.getType(uri);
        if (mime == null || mime.trim().isEmpty()) mime = "application/octet-stream";
        byte[] bytes;
        try (InputStream input = resolver.openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalStateException("Could not open selected file");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            bytes = output.toByteArray();
        }
        if (bytes.length == 0) throw new IllegalStateException("Selected file is empty");
        if (name == null || name.trim().isEmpty()) {
            String raw = uri.getLastPathSegment();
            name = raw == null || raw.trim().isEmpty() ? "input" : raw;
        }
        name = InputFileUpload.normalizeFilename(name, bytes, mime);
        if (size < 0) size = bytes.length;
        return new AndroidDocumentFile(name, mime, size, bytes);
    }
}
