package com.snapsnake.comfyremote;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import com.snapsnake.comfyremote.core.ComfyApiClient;
import com.snapsnake.comfyremote.core.ComfyStore;
import com.snapsnake.comfyremote.core.OutputAsset;
import com.snapsnake.comfyremote.ui.UiKit;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Opens ComfyUI outputs without exposing the protected /view URL to a browser.
 * The file is fetched with the saved server profile, cached privately, shown
 * natively, and can then be granted to another Android app as a content URI.
 */
public final class OutputViewerActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private TextView status;
    private LinearLayout viewerHost;
    private Button externalButton;
    private ComfyApiClient client;
    private File cachedFile;
    private String filename = "";
    private String subfolder = "";
    private String type = "output";
    private String mime = "application/octet-stream";
    private MediaPlayer audioPlayer;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        styleSystemBars();

        Uri data = getIntent() == null ? null : getIntent().getData();
        if (data == null || !"comfyui-mobile-output".equals(data.getScheme())) {
            Toast.makeText(this, "Invalid output link", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        filename = safe(data.getQueryParameter("filename"));
        subfolder = safe(data.getQueryParameter("subfolder"));
        type = safe(data.getQueryParameter("type"));
        if (type.isEmpty()) type = "output";
        mime = OutputAsset.mimeForFilename(filename);
        if (filename.isEmpty()) {
            Toast.makeText(this, "Output filename is missing", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        ComfyStore store = new ComfyStore(this);
        client = new ComfyApiClient(store.activeProfile());
        buildUi();
        loadOutput();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        releaseAudio();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = UiKit.column(this);
        root.setBackgroundColor(UiKit.BG);
        root.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 14), UiKit.dp(this, 18), UiKit.dp(this, 20));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = UiKit.column(this);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        content.addView(UiKit.title(this, filename, 25), UiKit.section(this));
        content.addView(UiKit.muted(this,
                mime + (subfolder.isEmpty() ? "" : " · " + subfolder), 13), UiKit.section(this));

        LinearLayout statusCard = UiKit.card(this, true);
        status = UiKit.muted(this, "Downloading securely from ComfyUI…", 14);
        statusCard.addView(status);
        content.addView(statusCard, UiKit.section(this));

        viewerHost = UiKit.card(this, false);
        viewerHost.setGravity(Gravity.CENTER);
        TextView placeholder = UiKit.muted(this, "Preparing preview…", 14);
        placeholder.setGravity(Gravity.CENTER);
        viewerHost.addView(placeholder, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 260)));
        content.addView(viewerHost, UiKit.section(this));

        LinearLayout actions = UiKit.row(this);
        externalButton = UiKit.button(this, "Open in another app", true, v -> openExternal());
        externalButton.setEnabled(false);
        actions.addView(externalButton, UiKit.weighted(this, 46));
        actions.addView(UiKit.button(this, "Close", false, v -> finish()), UiKit.weighted(this, 46));
        content.addView(actions, UiKit.section(this));

        setContentView(root);
    }

    private void loadOutput() {
        io.execute(() -> {
            try {
                File root = OutputFileProvider.cacheRoot(this);
                cachedFile = new File(root, cacheName(filename, subfolder, type));
                client.downloadOutputToFile(filename, subfolder, type, cachedFile);
                runOnUiThread(() -> {
                    status.setText("Downloaded · " + humanSize(cachedFile.length()));
                    externalButton.setEnabled(true);
                    renderCachedFile();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("Could not open output: " + friendly(e));
                    viewerHost.removeAllViews();
                    viewerHost.addView(UiKit.muted(this,
                            "The app could not download this output using the saved ComfyUI connection.", 14));
                });
            }
        });
    }

    private void renderCachedFile() {
        viewerHost.removeAllViews();
        OutputAsset.Kind kind = OutputAsset.kindFromFilename(filename);
        try {
            if (kind == OutputAsset.Kind.IMAGE || kind == OutputAsset.Kind.GIF) {
                renderImage();
            } else if (kind == OutputAsset.Kind.VIDEO) {
                renderVideo();
            } else if (kind == OutputAsset.Kind.AUDIO) {
                renderAudio();
            } else {
                TextView message = UiKit.muted(this,
                        "This file type has no built-in preview. Use Open in another app.", 15);
                message.setGravity(Gravity.CENTER);
                viewerHost.addView(message, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 220)));
            }
        } catch (Exception e) {
            TextView message = UiKit.muted(this,
                    "Native preview is unavailable. The downloaded file can still be opened in another app.", 14);
            message.setGravity(Gravity.CENTER);
            viewerHost.addView(message, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 220)));
        }
    }

    private void renderImage() throws Exception {
        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackground(UiKit.background(this, UiKit.SURFACE_2, 12, UiKit.STROKE, 2));
        viewerHost.addView(image, new LinearLayout.LayoutParams(-1, -2));

        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.Source source = ImageDecoder.createSource(cachedFile);
            Drawable drawable = ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
                int width = info.getSize().getWidth();
                int height = info.getSize().getHeight();
                int max = Math.max(width, height);
                if (max > 2048) {
                    float scale = 2048f / max;
                    decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
                }
            });
            image.setImageDrawable(drawable);
            if (drawable instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) drawable).start();
            return;
        }

        Bitmap bitmap = decodeScaled(cachedFile, 2048, 2048);
        if (bitmap == null) throw new IllegalStateException("Image decode failed");
        image.setImageBitmap(bitmap);
    }

    private void renderVideo() {
        VideoView video = new VideoView(this);
        video.setBackgroundColor(android.graphics.Color.BLACK);
        MediaController controller = new MediaController(this);
        controller.setAnchorView(video);
        video.setMediaController(controller);
        video.setVideoPath(cachedFile.getAbsolutePath());
        video.setOnPreparedListener(player -> {
            player.setLooping(false);
            video.start();
            status.setText("Playing locally · " + humanSize(cachedFile.length()));
        });
        video.setOnErrorListener((player, what, extra) -> {
            status.setText("Built-in player could not decode this video. Try another app.");
            return true;
        });
        viewerHost.addView(video, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 360)));
    }

    private void renderAudio() throws Exception {
        LinearLayout audio = UiKit.column(this);
        audio.setGravity(Gravity.CENTER);
        audio.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 28), UiKit.dp(this, 12), UiKit.dp(this, 28));
        TextView title = UiKit.title(this, "Audio output", 20);
        title.setGravity(Gravity.CENTER);
        audio.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView detail = UiKit.muted(this, filename, 13);
        detail.setGravity(Gravity.CENTER);
        audio.addView(detail, UiKit.match(this, -2, 8));

        Button play = UiKit.button(this, "Play", true, null);
        audio.addView(play, UiKit.match(this, 48, 18));
        viewerHost.addView(audio, new LinearLayout.LayoutParams(-1, -2));

        releaseAudio();
        audioPlayer = new MediaPlayer();
        audioPlayer.setDataSource(cachedFile.getAbsolutePath());
        audioPlayer.prepare();
        audioPlayer.setOnCompletionListener(mp -> play.setText("Play"));
        play.setOnClickListener(v -> {
            if (audioPlayer == null) return;
            if (audioPlayer.isPlaying()) {
                audioPlayer.pause();
                play.setText("Play");
            } else {
                audioPlayer.start();
                play.setText("Pause");
            }
        });
    }

    private void openExternal() {
        if (cachedFile == null || !cachedFile.isFile()) return;
        Uri uri = OutputFileProvider.uriFor(cachedFile);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, "Open output with"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app can open this file type", Toast.LENGTH_LONG).show();
        }
    }

    private void releaseAudio() {
        if (audioPlayer == null) return;
        try { audioPlayer.stop(); } catch (Exception ignored) {}
        try { audioPlayer.release(); } catch (Exception ignored) {}
        audioPlayer = null;
    }

    private static Bitmap decodeScaled(File file, int maxWidth, int maxHeight) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (FileInputStream input = new FileInputStream(file)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > maxWidth ||
                bounds.outHeight / options.inSampleSize > maxHeight) options.inSampleSize *= 2;
        try (FileInputStream input = new FileInputStream(file)) {
            return BitmapFactory.decodeStream(input, null, options);
        }
    }

    private static String cacheName(String filename, String subfolder, String type) {
        String safe = filename == null ? "output.bin" : filename.replaceAll("[^A-Za-z0-9._() -]", "_");
        if (safe.isEmpty()) safe = "output.bin";
        return digest(type + "|" + subfolder + "|" + filename) + "_" + safe;
    }

    private static String digest(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest((value == null ? "" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 8 && i < bytes.length; i++) out.append(String.format(Locale.US, "%02x", bytes[i] & 0xff));
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(value == null ? 0 : value.hashCode());
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    private static String friendly(Exception e) {
        String message = e == null ? "unknown error" : e.getMessage();
        if (message == null || message.trim().isEmpty()) message = e == null ? "unknown error" : e.getClass().getSimpleName();
        message = message.replace('\n', ' ');
        return message.length() <= 220 ? message : message.substring(0, 219) + "…";
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private void styleSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
