package com.snapsnake.comfyremote;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import com.snapsnake.comfyremote.core.OutputAsset;

import java.io.File;
import java.io.FileNotFoundException;

public final class OutputFileProvider extends ContentProvider {
    public static final String AUTHORITY = "com.snapsnake.comfyremote.output-files";

    public static Uri uriFor(File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(file.getName())
                .build();
    }

    public static File cacheRoot(android.content.Context context) {
        File root = new File(context.getCacheDir(), "output-viewer");
        if (!root.exists()) root.mkdirs();
        return root;
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        return OutputAsset.mimeForFilename(uri == null ? "" : uri.getLastPathSegment());
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && !mode.startsWith("r")) throw new FileNotFoundException("Read-only provider");
        File root = cacheRoot(getContext());
        File file = new File(root, uri == null ? "" : safeName(uri.getLastPathSegment()));
        try {
            String rootPath = root.getCanonicalPath() + File.separator;
            String filePath = file.getCanonicalPath();
            if (!filePath.startsWith(rootPath) || !file.isFile()) throw new FileNotFoundException("Output is not cached");
        } catch (java.io.IOException e) {
            throw new FileNotFoundException("Invalid output path");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        File root = cacheRoot(getContext());
        File file = new File(root, uri == null ? "" : safeName(uri.getLastPathSegment()));
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) row.add(file.getName());
            else if (OpenableColumns.SIZE.equals(column)) row.add(file.isFile() ? file.length() : 0L);
            else row.add(null);
        }
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only provider"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }

    private static String safeName(String value) {
        String raw = value == null ? "" : value;
        return raw.replaceAll("[^A-Za-z0-9._() -]", "_");
    }
}
